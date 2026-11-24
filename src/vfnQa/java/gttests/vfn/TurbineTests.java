package gttests.vfn;

import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidRegistry;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;

import gregtech.api.enums.MetaTileEntityIDs;
import gregtech.api.enums.Mods;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.items.MetaGeneratedTool;
import gregtech.api.enums.Materials;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidSteamTurbine;
import gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidInjectorHatch;
import gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidPipe;
import gregtech.api.metatileentity.implementations.integratedfluid.state.IFNCanonicalState;
import gregtech.api.util.GTUtility;
import gregtech.common.items.IDMetaTool01;

@GameTestHolder(value = Mods.ModIDs.GREG_TECH, templatePrefix = "vfn")
public class TurbineTests {

    @GameTest(template = "radiator", timeoutTicks = 500)
    public static void formedTurbineCreditsItsDynamo(GameTestHelper h) {
        var s = RemoteMachineSite.at(h, "controller");
        var rig = HeatPumpTests.placeSteelShell(s.world(), s.x(), s.y(), s.z(), (short) 5777, (short) 32);
        var load = s.place(-1, 0, -1, MetaTileEntityIDs.ENERGY_HATCH_MV.ID, ForgeDirection.SOUTH);
        var injector = (MTEIntegratedFluidInjectorHatch)
            s.place(-2, 0, 1, 5773, ForgeDirection.EAST).getMetaTileEntity();
        var exhaustFill = (MTEIntegratedFluidInjectorHatch)
            s.place(2, 0, 1, 5773, ForgeDirection.WEST).getMetaTileEntity();
        var src = new AmbientTests.AmbientMeter(h, rig.input()::getNetwork);
        var out = new AmbientTests.AmbientMeter(h, rig.output()::getNetwork);
        long[] start = new long[2];
        h.startSequence().thenIdle(25).thenExecute(() -> {
            h.assertTrue(rig.controller().getMetaTileEntity() instanceof MTEMultiBlockBase,
                "VFN turbine requires an assembled multiblock");
            var multi = (MTEMultiBlockBase) rig.controller().getMetaTileEntity();
            s.world().setBlockToAir(s.x(), s.y() + 1, s.z() + 1);
            h.assertFalse(multi.checkStructure(true, rig.controller()), "Missing casing stops formation");
            multi.fixAllIssues();
            installDiluteSteamRotor(rig.controller());
            h.assertTrue(injector.getNetwork() == rig.input().getNetwork(), "Injector feeds the inlet hatch");
            h.assertEquals(600, injector.fill(FluidRegistry.getFluidStack("steam", 600), true), "GT steam charges the inlet");
            start[0] = rig.input().getNetwork().getAmountQ();
            start[1] = src.withoutAmbient(rig.input().getNetwork()) + out.withoutAmbient(rig.output().getNetwork());
            rig.controller().enableWorking();
        }).thenIdle(40).thenExecute(() -> {
            h.assertEquals(start[0], rig.input().getNetwork().getAmountQ(), "Unformed turbine leaves the feed intact");
            h.assertEquals(0L, rig.output().getNetwork().getAmountQ(), "No exhaust without a casing");
            s.world().setBlock(s.x(), s.y() + 1, s.z() + 1, gregtech.api.GregTechAPI.sBlockCasings2, 0, 3);
            var multi = (MTEMultiBlockBase) rig.controller().getMetaTileEntity();
            h.assertTrue(multi.checkStructure(true, rig.controller()), "Steel turbine with inlet, outlet, dynamo and maintenance forms");
            multi.fixAllIssues();
            rig.controller().enableWorking();
        }).thenIdle(120).thenExecute(() -> {
            h.assertEquals(start[0], rig.input().getNetwork().getAmountQ(), "Whole stroke into vacuum exceeds this dynamo's capacity");
            h.assertEquals(0L, rig.output().getNetwork().getAmountQ(), "No venting when the whole stroke cannot be credited");
            h.assertEquals(200, exhaustFill.fill(FluidRegistry.getFluidStack("steam", 200), true), "Pressurize the separate exhaust through a GT injector");
            start[1] = src.withoutAmbient(rig.input().getNetwork()) + out.withoutAmbient(rig.output().getNetwork());
        }).thenWaitUntil(() -> h.assertTrue(load.getStoredEU() >= 128, "Waiting for the MV load: "
            + ((MTEIntegratedFluidSteamTurbine) rig.controller().getMetaTileEntity()).getCheckRecipeResult().getDisplayString()
            + ", inlet=" + rig.input().getNetwork().getPressurePa() + " Pa, exhaust=" + rig.output().getNetwork().getAmountQ()
            + " microkg, dynamo=" + rig.supply().getStoredEU() + "/" + rig.supply().getEUCapacity() + " EU"))
            .thenExecute(() -> {
                long exhaust = rig.output().getNetwork().getAmountQ() - 200 * 6250L, delivered = load.getStoredEU();
                long work = delivered + 2 * (delivered / 128) + rig.supply().getStoredEU();
                h.assertTrue(exhaust > 0 && exhaust % 6250 == 0, "Only whole steam portions come out");
                h.assertEquals(start[0] - exhaust, rig.input().getNetwork().getAmountQ(), "Both fluid ports balance");
                h.assertEquals(start[1] - work * 1_000_000L, src.withoutAmbient(rig.input().getNetwork()) + out.withoutAmbient(rig.output().getNetwork()),
                    "The fluid paid for the dynamo, external load and packet losses");
                h.assertEquals(0L, rig.controller().getStoredEU(), "No duplicate electricity in the controller");
                h.recordDiagnostic("Exhaust " + exhaust + " microkg, shaft " + work + " EU, load " + delivered + " EU");
            }).thenSucceed();
    }

    @GameTest(template = "radiator", timeoutTicks = 600)
    public static void hvDynamoStartsAgainstVacuum(GameTestHelper h) {
        var s = RemoteMachineSite.at(h, "controller");
        var rig = HeatPumpTests.placeSteelShell(s.world(), s.x(), s.y(), s.z(), (short) 5777, (short) 33);
        var load = s.place(-1, 0, -1, MetaTileEntityIDs.ENERGY_HATCH_HV.ID, ForgeDirection.SOUTH);
        var turbine = (MTEIntegratedFluidSteamTurbine) rig.controller().getMetaTileEntity();
        var injector = (MTEIntegratedFluidInjectorHatch)
            s.place(-2, 0, 1, 5773, ForgeDirection.EAST).getMetaTileEntity();
        h.startSequence().thenIdle(25).thenExecute(() -> {
            h.assertTrue(turbine.checkStructure(true, rig.controller()), "HV dynamo formed in the same housing");
            turbine.fixAllIssues();
            installDiluteSteamRotor(rig.controller());
            h.assertEquals(600, injector.fill(FluidRegistry.getFluidStack("steam", 600), true), "Same dilute inlet");
            turbine.setMinimumInletCbar(100);
            rig.controller().enableWorking();
        }).thenIdle(100).thenExecute(() -> {
            h.assertEquals(0L, rig.output().getNetwork().getAmountQ(), "Below start pressure the admission stays closed");
            turbine.setMinimumInletCbar(0);
            turbine.setAdmissionPercent(50);
        }).thenIdle(100).thenExecute(() -> {
            h.assertEquals(0L, rig.output().getNetwork().getAmountQ(), "Half the intake cannot carry a whole portion");
            h.assertEquals(0L, MetaGeneratedTool.getToolDamage(turbine.getControllerSlot()), "No idle rotor wear");
            turbine.setAdmissionPercent(100);
        }).thenWaitUntil(() -> h.assertTrue(load.getStoredEU() >= 512, "Dynamo delivered an HV packet: " + turbine.getCheckRecipeResult().getDisplayString()))
            .thenExecute(() -> {
                long moved = rig.output().getNetwork().getAmountQ();
                h.assertTrue(moved > 0 && moved % 6250 == 0, "Whole portions entered the initially empty exhaust");
                h.assertEquals(3_750_000L - moved, rig.input().getNetwork().getAmountQ(), "No source mass discrepancy");
                h.recordDiagnostic("HV load " + load.getStoredEU() + " EU, exhaust " + moved + " microkg");
            }).thenSucceed();
    }

    record Rig(ExchangerTests.Bench steam, IGregTechTileEntity turbine, IGregTechTileEntity dynamo, IGregTechTileEntity load) {
        long shaftWork(long delivered) {
            long voltage = dynamo.getOutputVoltage();
            long packetLoss = 1L << Math.max(0, GTUtility.getTier(voltage) - 1);
            return dynamo.getStoredEU() + delivered + delivered / voltage * packetLoss;
        }
    }

    @GameTest(template = "radiator", timeoutTicks = 1200)
    public static void stoppedRotorSurvivesChunkReload(GameTestHelper h) {
        var site = RemoteMachineSite.reserve(h, 175, 1);
        var rig = place(h, site, true);
        installDiluteSteamRotor(rig.turbine());
        var turbine = (MTEIntegratedFluidSteamTurbine) rig.turbine().getMetaTileEntity();
        var saved = new long[2];
        ItemStack[] rotor = new ItemStack[1];
        Rig[] restored = new Rig[1];
        h.startSequence().thenWaitUntil(() -> rig.steam().ready(h)).thenExecute(() -> {
            turbine.setAdmissionPercent(90);
            turbine.setMinimumInletCbar(50);
            h.assertEquals(600, rig.steam().hot().fill(FluidRegistry.getFluidStack("steam", 600), true), "Steam supplied");
        }).thenWaitUntil(() -> h.assertTrue(rig.load().getStoredEU() >= 512, "One shaft stroke reached the HV load"))
            .thenExecute(() -> {
                rig.turbine().disableWorking();
                saved[0] = rig.steam().cold().getNetwork().getAmountQ();
                saved[1] = generated(rig);
                rotor[0] = turbine.getControllerSlot().copy();
                site.requestUnload();
            }).thenWaitUntil(() -> h.assertTrue(site.absent(), "Whole turbine chunk unloaded"))
            .thenIdle(80).thenExecute(() -> {
                site.reloadForObservation(h);
                var controller = site.tile(1, 0, -3);
                var m = (MTEIntegratedFluidSteamTurbine) controller.getMetaTileEntity();
                h.assertTrue(m != turbine, "Controller instance restored from disk");
                h.assertEquals(90, m.admissionPercent(), "Saved throttle");
                h.assertEquals(50, m.minimumInletCbar(), "Saved inlet threshold");
                h.assertTrue(ItemStack.areItemStacksEqual(rotor[0], m.getControllerSlot()), "Same rotor with exact prior damage");
                var hot = (MTEIntegratedFluidInjectorHatch) site.tile(-1, 0, 0).getMetaTileEntity();
                var cold = (MTEIntegratedFluidInjectorHatch) site.tile(3, 0, 0).getMetaTileEntity();
                var source = (MTEIntegratedFluidPipe) site.tile(0, 0, 0).getMetaTileEntity();
                var outlet = (MTEIntegratedFluidPipe) site.tile(2, 0, 0).getMetaTileEntity();
                restored[0] = new Rig(new ExchangerTests.Bench(hot, cold, source, outlet), controller, site.tile(1, 0, -1), site.tile(1, 0, 0));
            }).thenWaitUntil(() -> restored[0].steam().ready(h)).thenIdle(100).thenExecute(() -> {
                var r = restored[0];
                h.assertEquals(saved[0], r.steam().cold().getNetwork().getAmountQ(), "No repeated exhaust delivery");
                h.assertEquals(3_750_000L - saved[0], r.steam().hot().getNetwork().getAmountQ(), "No repeated inlet debit");
                h.assertEquals(saved[1], generated(r), "No repeated dynamo credit");
                r.turbine().enableWorking();
            }).thenWaitUntil(() -> h.assertTrue(restored[0].steam().cold().getNetwork().getAmountQ() > saved[0]
                && generated(restored[0]) > saved[1], "Another paid stroke after re-enabling the restored turbine")).thenSucceed();
    }

    static Rig place(GameTestHelper helper) {
        return place(helper, RemoteMachineSite.at(helper, "source"));
    }

    static Rig place(GameTestHelper helper, RemoteMachineSite site) {
        return place(helper, site, false);
    }

    static Rig place(GameTestHelper helper, RemoteMachineSite site, boolean hv) {
        return place(helper, site, hv ? 3 : 2);
    }

    static Rig place(GameTestHelper helper, RemoteMachineSite site, int electricalTier) {
        helper.assertTrue(electricalTier >= 2 && electricalTier <= 4, "MV, HV or EV dynamo and matching load");
        HeatPumpTests.placeSteelCasings(site.world(), site.x() + 1, site.y(), site.z() - 3);
        var turbine = site.place(1, 0, -3, 5777, ForgeDirection.NORTH);
        site.assignOwner(turbine);
        site.place(0, 0, -3, 15497, ForgeDirection.NORTH);
        site.place(0, 0, -1, 5771, ForgeDirection.SOUTH);
        site.place(2, 0, -1, 5772, ForgeDirection.SOUTH);
        var dynamo = site.place(1, 0, -1, MetaTileEntityIDs.DYNAMO_HATCH_MV.ID + electricalTier - 2, ForgeDirection.SOUTH);
        var load = site.place(1, 0, 0, MetaTileEntityIDs.ENERGY_HATCH_MV.ID + electricalTier - 2, ForgeDirection.NORTH);
        var hot = (MTEIntegratedFluidInjectorHatch)
            site.place(-1, 0, 0, 5773, ForgeDirection.EAST).getMetaTileEntity();
        var cold = (MTEIntegratedFluidInjectorHatch)
            site.place(3, 0, 0, 5773, ForgeDirection.WEST).getMetaTileEntity();
        var source = (MTEIntegratedFluidPipe)
            site.place(0, 0, 0, 5770, ForgeDirection.EAST).getMetaTileEntity();
        var outlet = (MTEIntegratedFluidPipe)
            site.place(2, 0, 0, 5770, ForgeDirection.WEST).getMetaTileEntity();
        helper.assertTrue(source.connect(ForgeDirection.WEST) > 0 && source.connect(ForgeDirection.NORTH) > 0
            && outlet.connect(ForgeDirection.EAST) > 0 && outlet.connect(ForgeDirection.NORTH) > 0, "Steam pipes connect to their respective hatches");
        var multi = (MTEIntegratedFluidSteamTurbine)turbine.getMetaTileEntity();
        helper.assertTrue(multi.checkStructure(true, turbine), "Turbine housing formed");
        multi.fixAllIssues();
        installSteelRotor(turbine);
        return new Rig(new ExchangerTests.Bench(hot, cold, source, outlet), turbine, dynamo, load);
    }

    static void installSteelRotor(IGregTechTileEntity turbine) {
        installSteelRotor(turbine, IDMetaTool01.TURBINE);
    }

    static void installSteelRotor(IGregTechTileEntity turbine, IDMetaTool01 size) {
        installRotor(turbine, size, Materials.Steel);
    }

    static void installRotor(IGregTechTileEntity turbine, IDMetaTool01 size, Materials material) {
        turbine.setInventorySlotContents(1, gregtech.common.items.MetaGeneratedTool01.INSTANCE.getToolWithStats(
            size.ID, 1, material, material, null));
    }

    static void installDiluteSteamRotor(IGregTechTileEntity turbine) {
        installRotor(turbine, IDMetaTool01.TURBINE_HUGE, Materials.TungstenSteel);
    }

    @GameTest(template = "turbine", timeoutTicks = 6000)
    public static void reboiledSteamPowersLoad(GameTestHelper helper) {
        var site = RemoteMachineSite.at(helper, "source");
        var rig = place(helper, site, true);
        installDiluteSteamRotor(rig.turbine());
        var bench = rig.steam();
        var manifold = (MTEIntegratedFluidPipe)
            site.place(0, 0, 1, 5770, ForgeDirection.NORTH).getMetaTileEntity();
        helper.assertTrue(bench.source().connect(ForgeDirection.SOUTH) > 0, "Boiler manifold joined the inlet");
        var heaters = new java.util.ArrayList<IGregTechTileEntity>();
        for (int z = 0; z <= 1; z++) {
            for (var side : new ForgeDirection[] { ForgeDirection.UP, ForgeDirection.DOWN, ForgeDirection.EAST, ForgeDirection.SOUTH }) {
                if (z == 0 && side.offsetY == 0) continue;
                heaters.add(site.place(side.offsetX, side.offsetY, z + side.offsetZ, 5776, side));
                helper.assertTrue((z == 0 ? bench.source() : manifold).connect(side) > 0, "Thermal contact to a boiler");
            }
        }
        rig.turbine().disableWorking();
        var inlet = new AmbientTests.AmbientMeter(helper, bench.hot()::getNetwork);
        var exhaust = new AmbientTests.AmbientMeter(helper, bench.cold()::getNetwork);
        boolean[] power = new boolean[1];
        long[] supplied = new long[1];
        long[] initial = new long[1];
        helper.onEachTick("supply the boiler", () -> {
            if (power[0]) for (var heater : heaters) supplied[0] += 128 * heater.injectEnergyUnits(heater.getFrontFacing(), 128, 1);
        });
        helper.startSequence()
            .thenWaitUntil(() -> bench.ready(helper))
            .thenExecute(() -> {
                helper.assertEquals(500, bench.hot().fill(FluidRegistry.getFluidStack("steam", 500), true), "Dry GT steam admitted");
                helper.assertEquals(1.0, bench.hot().getNetwork().getQuality(), "Initially dry steam");
                helper.assertEquals(1, bench.hot().fill(FluidRegistry.getFluidStack("water", 1), true), "Add ambient feedwater");
            })
            .thenWaitUntil(() -> helper.assertTrue(bench.hot().getNetwork().getQuality() < 0.99, "Condensate present"))
            .thenExecute(() -> rig.turbine().enableWorking())
            .thenWaitUntil(() -> helper.assertTrue(((MTEIntegratedFluidSteamTurbine)
                rig.turbine().getMetaTileEntity()).getCheckRecipeResult().equals(gregtech.api.recipe.check.SimpleCheckRecipeResult.ofFailure("vfn_turbine_wrong_phase")), "A formed turbine refused wet steam"))
            .thenIdle(25)
            .thenExecute(() -> {
                helper.assertEquals(0L, bench.cold().getNetwork().getAmountQ(), "Wet feed does not traverse the dry inlet");
                helper.assertEquals(0L, rig.load().getStoredEU(), "No power from wet feed");
                helper.recordDiagnostic("Preheat: " + bench.hot().getNetwork().getTemperature() + " K, vapor fraction "
                    + bench.hot().getNetwork().getQuality());
                initial[0] = inlet.withoutAmbient(bench.hot().getNetwork()) + exhaust.withoutAmbient(bench.cold().getNetwork());
                power[0] = true;
            })
            .thenWaitUntil(() -> helper.assertTrue(rig.load().getStoredEU() >= 512, "Reboiled steam runs the turbine: quality " + bench.hot().getNetwork().getQuality()))
            .thenExecute(() -> {
                power[0] = false;
                long delivered = rig.load().getStoredEU();
                long generated = generated(rig);
                long paidHeat = supplied[0];
                for (var heater : heaters) paidHeat -= heater.getStoredEU();
                long remaining = inlet.withoutAmbient(bench.hot().getNetwork()) + exhaust.withoutAmbient(bench.cold().getNetwork());
                helper.assertEquals(initial[0] + paidHeat * 1_000_000L,
                    remaining + generated * 1_000_000L, "Boiler heat, exhaust and GT output balance");
                helper.assertEquals(4_125_000L, bench.hot().getNetwork().getAmountQ() + bench.cold().getNetwork().getAmountQ(),
                    "Working substance conserved");
                helper.recordDiagnostic("Reheat " + paidHeat + " EU, shaft " + generated
                    + " EU, load " + delivered + " EU, exhaust " + bench.cold().getNetwork().getAmountQ() + " microkg");
            })
            .thenSucceed();
    }

    @GameTest(template = "turbine", timeoutTicks = 200)
    public static void fullDynamoHoldsSteamUntilLoadConnects(GameTestHelper helper) {
        var rig = place(helper, RemoteMachineSite.at(helper, "source"), true);
        installDiluteSteamRotor(rig.turbine());
        var port = helper.absolute("valve");
        helper.getWorld().setBlockToAir(port.x(), port.y(), port.z());
        helper.startSequence()
            .thenWaitUntil(() -> rig.steam().ready(helper))
            .thenExecute(() -> {
                helper.assertTrue(rig.dynamo().increaseStoredEnergyUnits(rig.dynamo().getEUCapacity(), false), "Dynamo precharged");
                helper.assertEquals(600, rig.steam().hot().fill(FluidRegistry.getFluidStack("steam", 600), true), "Steam admitted");
            })
            .thenIdle(45)
            .thenExecute(() -> {
                helper.assertEquals(0L, rig.steam().cold().getNetwork().getAmountQ(), "Full dynamo does not vent steam");
                helper.assertEquals(3_750_000L, rig.steam().hot().getNetwork().getAmountQ(), "Inlet keeps its entire charge");
                helper.assertEquals(0L, MetaGeneratedTool.getToolDamage(rig.turbine().getStackInSlot(1)), "No rotor wear while blocked");
                InjectorTests.placeAt(helper.getWorld(), port.x(), port.y(), port.z(),
                    (short) MetaTileEntityIDs.ENERGY_HATCH_HV.ID, ForgeDirection.NORTH);
            })
            .thenWaitUntil(() -> helper.assertTrue(rig.steam().cold().getNetwork().getAmountQ() > 0,
                "Accepting load makes room and expansion resumes"))
            .thenSucceed();
    }

    @GameTest(template = "turbine", timeoutTicks = 320)
    public static void emptyRotorPassesSteamWithoutShaftWork(GameTestHelper helper) {
        var rig = place(helper);
        rig.turbine().setInventorySlotContents(1, null);
        var a = new AmbientTests.AmbientMeter(helper, rig.steam().hot()::getNetwork);
        var b = new AmbientTests.AmbientMeter(helper, rig.steam().cold()::getNetwork);
        long[] energy = new long[1];
        helper.startSequence().thenWaitUntil(() -> rig.steam().ready(helper)).thenExecute(() -> {
            helper.assertEquals(6, rig.steam().hot().fill(FluidRegistry.getFluidStack("helium", 6), true), "Inject GT helium");
        }).thenWaitUntil(() -> {
            var controller = (MTEIntegratedFluidSteamTurbine) rig.turbine().getMetaTileEntity();
            helper.assertTrue(controller.getCheckRecipeResult().equals(
                gregtech.api.recipe.check.SimpleCheckRecipeResult.ofFailure("vfn_turbine_wrong_fluid")),
                "Identify unsupported helium rather than waiting for more pressure: " + controller.getCheckRecipeResult().getDisplayString());
        }).thenExecute(() -> {
            helper.assertEquals(666_666L, rig.steam().hot().getNetwork().getAmountQ(), "Refused feed remains in the inlet");
            helper.assertEquals(0L, rig.steam().cold().getNetwork().getAmountQ(), "No helium in exhaust");
            chargeDrySteam(rig.steam(), 495);
            helper.assertEquals(1, rig.steam().cold().fill(FluidRegistry.getFluidStack("water", 1), true), "Prime the low-pressure receiver");
            energy[0] = a.withoutAmbient(rig.steam().hot().getNetwork()) + b.withoutAmbient(rig.steam().cold().getNetwork());
        }).thenWaitUntil(() -> helper.assertTrue(rig.steam().cold().getNetwork().getAmountQ() > 1000000, "Passive bypass flows"))
            .thenExecute(() -> {
                var inlet = rig.steam().hot().getNetwork();
                var outlet = rig.steam().cold().getNetwork();
                helper.assertEquals(0L, rig.dynamo().getStoredEU() + rig.load().getStoredEU(), "No rotor, no shaft work");
                helper.assertEquals(11_100_000L, inlet.getAmountQ() + outlet.getAmountQ(), "Mass retained");
                helper.assertEquals(0L, (outlet.getAmountQ() - 1_000_000) % 6250, "Bypass moves whole steam portions");
                helper.assertEquals(energy[0], a.withoutAmbient(inlet) + b.withoutAmbient(outlet), "All flow energy reaches exhaust");
            }).thenSucceed();
    }

    @GameTest(template = "turbine", timeoutTicks = 320)
    public static void rotorWearDistinguishesStageFromExhaustPipe(GameTestHelper helper) {
        var rig = place(helper, RemoteMachineSite.at(helper, "source"), true);
        var bench = rig.steam();
        var rotor = rig.turbine().getStackInSlot(1);
        var seq = helper.startSequence().thenWaitUntil(() -> bench.ready(helper));
        long[] before = new long[1];
        for (int kelvin : new int[] { 495, 430 }) {
            seq.thenExecute(() -> {
                before[0] = MetaGeneratedTool.getToolDamage(rotor);
                bench.cold().getNetwork().clearFluid();
                chargeDrySteam(bench, kelvin);
            }).thenWaitUntil(() -> helper.assertTrue(bench.cold().getNetwork().getAmountQ() > 0, "Paid stroke"))
                .thenExecute(() -> {
                    helper.assertEquals(1d, bench.cold().getNetwork().getQuality(), "The filled exhaust pipe contains dry vapor");
                    helper.assertEquals(kelvin == 495 ? 1L : 6L, MetaGeneratedTool.getToolDamage(rotor) - before[0],
                        "Rotor wear at " + kelvin + " K");
                    var tile = rig.turbine();
                    var reading = new net.minecraft.nbt.NBTTagCompound();
                    var viewer = FakePlayerFactory.getMinecraft((WorldServer) helper.getWorld());
                    tile.getMetaTileEntity().getWailaNBTData(viewer, (TileEntity) tile, reading,
                        helper.getWorld(), tile.getXCoord(), tile.getYCoord(), tile.getZCoord());
                    helper.assertTrue(reading.hasKey("vfnLastWetExpansion"), "The turbine has a measured stroke");
                    helper.assertTrue(reading.getBoolean("vfnLastWetExpansion") == (kelvin == 430),
                        "The turbine reports rotor conditions rather than the final pipe phase");
                });
        }
        seq.thenSucceed();
    }

    @GameTest(template = "turbine", timeoutTicks = 320)
    public static void lastRotorStrokeThenPassiveFlow(GameTestHelper helper) {
        var rig = place(helper, RemoteMachineSite.at(helper, "source"), true);
        var rotor = rig.turbine().getStackInSlot(1);
        MetaGeneratedTool.setToolDamage(rotor, MetaGeneratedTool.getToolMaxDamage(rotor) - 1);
        long[] checkpoint = new long[2];
        helper.startSequence().thenWaitUntil(() -> rig.steam().ready(helper)).thenExecute(() -> {
            chargeDrySteam(rig.steam(), 495);
        }).thenWaitUntil(() -> helper.assertTrue(rig.turbine().getStackInSlot(1) == null, "GT removed the worn rotor"))
            .thenExecute(() -> {
                checkpoint[0] = rig.steam().cold().getNetwork().getAmountQ();
                checkpoint[1] = generated(rig);
                helper.assertTrue(checkpoint[0] > 0 && checkpoint[1] > 0, "Final stroke expanded and generated before the rotor broke");
            }).thenWaitUntil(() -> helper.assertTrue(rig.steam().cold().getNetwork().getAmountQ() > checkpoint[0], "Steam still passes"))
            .thenExecute(() -> helper.assertEquals(checkpoint[1], generated(rig), "No new EU in bypass")).thenSucceed();
    }

    private static long generated(Rig rig) {
        return rig.shaftWork(rig.load().getStoredEU());
    }

    private static void chargeDrySteam(ExchangerTests.Bench bench, int kelvin) {
        double internalEuPerKg = 2257000 + 2010 * (kelvin - 373.15) - 461.5 * kelvin;
        bench.hot().getNetwork().replaceCanonicalState(IFNCanonicalState
            .fromRaw("water", 10_100_000L, Math.round(10_100_000 * internalEuPerKg)));
    }

    @GameTest(template = "turbine", timeoutTicks = 320)
    public static void steamPowersGtLoad(GameTestHelper helper) {
        var rig = place(helper);
        var bench = rig.steam();
        var inlet = new AmbientTests.AmbientMeter(helper, bench.hot()::getNetwork);
        var exhaust = new AmbientTests.AmbientMeter(helper, bench.cold()::getNetwork);
        long[] start = new long[1];
        int[] admitted = { 0 };
        helper.startSequence()
            .thenWaitUntil(() -> bench.ready(helper))
            .thenExecute(() -> {
                admitted[0] = bench.hot().fill(FluidRegistry.getFluidStack("steam", 1000), true);
                helper.assertEquals(200, bench.cold().fill(FluidRegistry.getFluidStack("steam", 200), true), "Prime the exhaust for the MV dynamo");
                helper.assertTrue(admitted[0] > 0, "Injector admits ordinary GT steam");
                helper.recordDiagnostic("Steam supply: " + admitted[0] + " GT units, " + bench.hot().getNetwork().getPressurePa()
                    + " Pa, " + bench.hot().getNetwork().getTemperature() + " K");
                start[0] = inlet.withoutAmbient(bench.hot().getNetwork()) + exhaust.withoutAmbient(bench.cold().getNetwork());
            }).thenIdle(25).thenExecute(() -> {
                helper.assertEquals(0L, generated(rig), "Standard rotor cannot admit a full portion of this steam");
                helper.assertEquals(1_250_000L, bench.cold().getNetwork().getAmountQ(), "No fractional exhaust");
                helper.assertEquals(0L, MetaGeneratedTool.getToolDamage(rig.turbine().getStackInSlot(1)), "No idle wear");
                installDiluteSteamRotor(rig.turbine());
            })
            .thenWaitUntil(() -> helper.assertTrue(rig.load().getStoredEU() >= 128, "An actual GT hatch receives MV power"))
            .thenExecute(() -> {
                var a = bench.hot().getNetwork();
                var b = bench.cold().getNetwork();
                long delivered = rig.load().getStoredEU();
                long spent = start[0] - inlet.withoutAmbient(a) - exhaust.withoutAmbient(b);
                helper.assertEquals((rig.dynamo().getStoredEU() + delivered + delivered / 128 * 2) * 1_000_000L,
                    spent, "Steam energy pays for the dynamo, the load and GT's MV output loss");
                helper.assertEquals((admitted[0] + 200) * 6250L, a.getAmountQ() + b.getAmountQ(), "Exhaust retains all transferred mass");
                helper.assertEquals(0L, b.getAmountQ() % 6250, "Only whole portions traversed the rotor");
                helper.assertTrue(b.getAmountQ() > 1_250_000 && a.getPressure() >= b.getPressure(), "Finite exhaust backpressure");
                helper.recordDiagnostic("Exhaust received " + (b.getAmountQ() - 1_250_000L) + " microkg, shaft " + generated(rig) + " EU, MV load " + delivered + " EU");
            })
            .thenSucceed();
    }
}
