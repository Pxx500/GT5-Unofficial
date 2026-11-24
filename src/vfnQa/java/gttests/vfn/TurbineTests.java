package gttests.vfn;

import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidRegistry;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;

import gregtech.api.enums.MetaTileEntityIDs;
import gregtech.api.enums.Mods;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.items.MetaGeneratedTool;

@GameTestHolder(value = Mods.ModIDs.GREG_TECH, templatePrefix = "vfn")
public class TurbineTests {

    record Rig(ExchangerTests.Bench steam, IGregTechTileEntity turbine, IGregTechTileEntity load) {}

    static Rig place(GameTestHelper helper) {
        return place(helper, RemoteMachineSite.at(helper, "source"));
    }

    static Rig place(GameTestHelper helper, RemoteMachineSite site) {
        var steam = ExchangerTests.place(helper, site, false, (short) 5777);
        var load = site.place(1, 1, 0, MetaTileEntityIDs.ENERGY_HATCH_MV.ID, ForgeDirection.DOWN);
        var turbine = site.tile(1, 0, 0);
        installSteelRotor(turbine);
        return new Rig(steam, turbine, load);
    }

    static void installSteelRotor(IGregTechTileEntity turbine) {
        turbine.setInventorySlotContents(0, gregtech.common.items.MetaGeneratedTool01.INSTANCE.getToolWithStats(
            gregtech.common.items.IDMetaTool01.TURBINE.ID, 1, gregtech.api.enums.Materials.Steel, gregtech.api.enums.Materials.Steel, null));
    }

    @GameTest(template = "regulator", timeoutTicks = 1800)
    public static void reboiledSteamPowersLoad(GameTestHelper helper) {
        var rig = place(helper);
        var bench = rig.steam();
        var position = helper.absolute("source");
        var boiler = InjectorTests.placeAt(helper.getWorld(), position.x(), position.y(), position.z() - 1,
            (short) 5776, ForgeDirection.NORTH);
        helper.assertTrue(bench.source().connect(ForgeDirection.NORTH) > 0, "Boiler contact on the steam inlet");
        rig.turbine().disableWorking();
        var inlet = new AmbientTests.AmbientMeter(helper, bench.hot()::getNetwork);
        var exhaust = new AmbientTests.AmbientMeter(helper, bench.cold()::getNetwork);
        boolean[] power = new boolean[1];
        long[] supplied = new long[1];
        long[] initial = new long[1];
        helper.onEachTick("supply the boiler", () -> {
            if (power[0]) supplied[0] += 128 * boiler.injectEnergyUnits(ForgeDirection.NORTH, 128, 1);
        });
        helper.startSequence()
            .thenWaitUntil(() -> bench.ready(helper))
            .thenExecute(() -> helper.assertEquals(1, bench.hot().fill(FluidRegistry.getFluidStack("steam", 1), true), "Steam admitted"))
            .thenWaitUntil(() -> helper.assertTrue(bench.hot().getNetwork().getQuality() < 0.99, "Condensate present"))
            .thenExecute(() -> rig.turbine().enableWorking())
            .thenIdle(25)
            .thenExecute(() -> {
                helper.assertEquals(0L, bench.cold().getNetwork().getAmountQ(), "Wet feed does not traverse the dry inlet");
                helper.assertEquals(0L, rig.load().getStoredEU(), "No power from wet feed");
                initial[0] = inlet.withoutAmbient(bench.hot().getNetwork()) + exhaust.withoutAmbient(bench.cold().getNetwork());
                power[0] = true;
            })
            .thenWaitUntil(() -> helper.assertTrue(rig.load().getStoredEU() >= 128, "Reboiled steam runs the turbine"))
            .thenExecute(() -> {
                power[0] = false;
                long delivered = rig.load().getStoredEU();
                long generated = rig.turbine().getStoredEU() + delivered + delivered / 128 * 2;
                long remaining = inlet.withoutAmbient(bench.hot().getNetwork()) + exhaust.withoutAmbient(bench.cold().getNetwork());
                helper.assertEquals(initial[0] + (supplied[0] - boiler.getStoredEU()) * 1_000_000L,
                    remaining + generated * 1_000_000L, "Boiler heat, exhaust and GT output balance");
                helper.assertEquals(6250L, bench.hot().getNetwork().getAmountQ() + bench.cold().getNetwork().getAmountQ(),
                    "Working substance conserved");
            })
            .thenSucceed();
    }

    @GameTest(template = "regulator", timeoutTicks = 200)
    public static void fullDynamoHoldsSteamUntilLoadConnects(GameTestHelper helper) {
        var rig = place(helper);
        var port = helper.absolute("valve");
        helper.getWorld().setBlockToAir(port.x(), port.y() + 1, port.z());
        helper.startSequence()
            .thenWaitUntil(() -> rig.steam().ready(helper))
            .thenExecute(() -> {
                helper.assertTrue(rig.turbine().increaseStoredEnergyUnits(rig.turbine().getEUCapacity(), false), "Dynamo precharged");
                helper.assertEquals(6, rig.steam().hot().fill(FluidRegistry.getFluidStack("steam", 6), true), "Steam admitted");
            })
            .thenIdle(45)
            .thenExecute(() -> {
                helper.assertEquals(0L, rig.steam().cold().getNetwork().getAmountQ(), "Full dynamo does not vent steam");
                helper.assertEquals(37_500L, rig.steam().hot().getNetwork().getAmountQ(), "Inlet keeps its entire charge");
                helper.assertEquals(0L, MetaGeneratedTool.getToolDamage(rig.turbine().getStackInSlot(0)), "No rotor wear while blocked");
                InjectorTests.placeAt(helper.getWorld(), port.x(), port.y() + 1, port.z(),
                    (short) MetaTileEntityIDs.ENERGY_HATCH_MV.ID, ForgeDirection.DOWN);
            })
            .thenWaitUntil(() -> helper.assertTrue(rig.steam().cold().getNetwork().getAmountQ() > 0,
                "Accepting load makes room and expansion resumes"))
            .thenSucceed();
    }

    @GameTest(template = "regulator", timeoutTicks = 320)
    public static void emptyRotorPassesSteamWithoutShaftWork(GameTestHelper helper) {
        var rig = place(helper);
        rig.turbine().setInventorySlotContents(0, null);
        var a = new AmbientTests.AmbientMeter(helper, rig.steam().hot()::getNetwork);
        var b = new AmbientTests.AmbientMeter(helper, rig.steam().cold()::getNetwork);
        long[] energy = new long[1];
        helper.startSequence().thenWaitUntil(() -> rig.steam().ready(helper)).thenExecute(() -> {
            helper.assertEquals(6, rig.steam().hot().fill(FluidRegistry.getFluidStack("steam", 6), true), "Dry charge");
            helper.assertEquals(1, rig.steam().cold().fill(FluidRegistry.getFluidStack("water", 1), true), "Prime the low-pressure receiver");
            energy[0] = a.withoutAmbient(rig.steam().hot().getNetwork()) + b.withoutAmbient(rig.steam().cold().getNetwork());
        }).thenWaitUntil(() -> helper.assertTrue(rig.steam().cold().getNetwork().getAmountQ() > 1000000, "Passive bypass flows"))
            .thenExecute(() -> {
                var inlet = rig.steam().hot().getNetwork();
                var outlet = rig.steam().cold().getNetwork();
                helper.assertEquals(0L, rig.turbine().getStoredEU() + rig.load().getStoredEU(), "No rotor, no shaft work");
                helper.assertEquals(1037500L, inlet.getAmountQ() + outlet.getAmountQ(), "Mass retained");
                helper.assertEquals(energy[0], a.withoutAmbient(inlet) + b.withoutAmbient(outlet), "All flow energy reaches exhaust");
            }).thenSucceed();
    }

    @GameTest(template = "regulator", timeoutTicks = 320)
    public static void rotorWearDistinguishesStageFromExhaustPipe(GameTestHelper helper) {
        var rig = place(helper);
        var bench = rig.steam();
        var rotor = rig.turbine().getStackInSlot(0);
        var seq = helper.startSequence().thenWaitUntil(() -> bench.ready(helper));
        long[] before = new long[1];
        for (int kelvin : new int[] { 495, 430 }) {
            seq.thenExecute(() -> {
                before[0] = MetaGeneratedTool.getToolDamage(rotor);
                bench.cold().getNetwork().clearFluid();
                double internalEuPerKg = 2257000 + 2010 * (kelvin - 373.15) - 461.5 * kelvin;
                bench.hot().getNetwork().replaceCanonicalState(gregtech.api.metatileentity.implementations.integratedfluid.state.IFNCanonicalState
                    .fromRaw("water", 100000L, Math.round(100000 * internalEuPerKg)));
            }).thenWaitUntil(() -> helper.assertTrue(bench.cold().getNetwork().getAmountQ() > 0, "Paid stroke"))
                .thenExecute(() -> {
                    helper.assertEquals(1d, bench.cold().getNetwork().getQuality(), "The filled exhaust pipe contains dry vapor");
                    helper.assertEquals(kelvin == 495 ? 1L : 6L, MetaGeneratedTool.getToolDamage(rotor) - before[0],
                        "Rotor wear at " + kelvin + " K");
                    var tile = rig.turbine();
                    var reading = new net.minecraft.nbt.NBTTagCompound();
                    tile.getMetaTileEntity().getWailaNBTData(null, (net.minecraft.tileentity.TileEntity) tile, reading,
                        helper.getWorld(), tile.getXCoord(), tile.getYCoord(), tile.getZCoord());
                    helper.assertTrue(reading.hasKey("vfnLastWetExpansion"), "The turbine has a measured stroke");
                    helper.assertTrue(reading.getBoolean("vfnLastWetExpansion") == (kelvin == 430),
                        "The turbine reports rotor conditions rather than the final pipe phase");
                });
        }
        seq.thenSucceed();
    }

    @GameTest(template = "regulator", timeoutTicks = 320)
    public static void lastRotorStrokeThenPassiveFlow(GameTestHelper helper) {
        var rig = place(helper);
        var rotor = rig.turbine().getStackInSlot(0);
        MetaGeneratedTool.setToolDamage(rotor, MetaGeneratedTool.getToolMaxDamage(rotor) - 1);
        long[] checkpoint = new long[2];
        helper.startSequence().thenWaitUntil(() -> rig.steam().ready(helper)).thenExecute(() -> {
            helper.assertEquals(6, rig.steam().hot().fill(FluidRegistry.getFluidStack("steam", 6), true), "Charge ready");
        }).thenWaitUntil(() -> helper.assertTrue(rig.turbine().getStackInSlot(0) == null, "GT removed the worn rotor"))
            .thenExecute(() -> {
                checkpoint[0] = rig.steam().cold().getNetwork().getAmountQ();
                checkpoint[1] = generated(rig);
                helper.assertTrue(checkpoint[0] > 0 && checkpoint[1] > 0, "Final stroke expanded and generated before the rotor broke");
            }).thenWaitUntil(() -> helper.assertTrue(rig.steam().cold().getNetwork().getAmountQ() > checkpoint[0], "Steam still passes"))
            .thenExecute(() -> helper.assertEquals(checkpoint[1], generated(rig), "No new EU in bypass")).thenSucceed();
    }

    private static long generated(Rig rig) {
        long delivered = rig.load().getStoredEU();
        return rig.turbine().getStoredEU() + delivered + delivered / 128 * 2;
    }

    @GameTest(template = "regulator", timeoutTicks = 320)
    public static void steamPowersGtLoad(GameTestHelper helper) {
        var rig = place(helper);
        var bench = rig.steam();
        var inlet = new AmbientTests.AmbientMeter(helper, bench.hot()::getNetwork);
        var exhaust = new AmbientTests.AmbientMeter(helper, bench.cold()::getNetwork);
        long[] start = new long[1];
        helper.startSequence()
            .thenWaitUntil(() -> bench.ready(helper))
            .thenExecute(() -> {
                helper.assertEquals(6, bench.hot().fill(FluidRegistry.getFluidStack("steam", 6), true), "Standard steam intake");
                start[0] = inlet.withoutAmbient(bench.hot().getNetwork()) + exhaust.withoutAmbient(bench.cold().getNetwork());
            })
            .thenWaitUntil(() -> helper.assertTrue(rig.load().getStoredEU() >= 128, "An actual GT hatch receives MV power"))
            .thenExecute(() -> {
                var a = bench.hot().getNetwork();
                var b = bench.cold().getNetwork();
                long delivered = rig.load().getStoredEU();
                long spent = start[0] - inlet.withoutAmbient(a) - exhaust.withoutAmbient(b);
                helper.assertEquals((rig.turbine().getStoredEU() + delivered + delivered / 128 * 2) * 1_000_000L,
                    spent, "Steam energy pays for the dynamo, the load and GT's MV output loss");
                helper.assertEquals(37_500L, a.getAmountQ() + b.getAmountQ(), "Exhaust retains all transferred mass");
                helper.assertTrue(b.getAmountQ() > 0 && a.getPressure() >= b.getPressure(), "Finite exhaust backpressure");
            })
            .thenSucceed();
    }
}
