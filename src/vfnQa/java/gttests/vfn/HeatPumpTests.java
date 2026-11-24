package gttests.vfn;

import java.math.BigDecimal;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;

import gregtech.api.GregTechAPI;
import gregtech.api.enums.Mods;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidInputHatch;
import gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidLiquidPump;
import gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidOutputHatch;
import gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidPipe;
import gregtech.api.metatileentity.implementations.integratedfluid.state.IFNCanonicalState;
import gregtech.api.metatileentity.implementations.integratedfluid.thermodynamics.ClosedFluidChamber;
import gregtech.api.metatileentity.implementations.integratedfluid.thermodynamics.PureFluid;
import gregtech.common.tileentities.machines.multi.HeatPumpMode;
import gregtech.common.tileentities.machines.multi.MTEHeatPump;

@GameTestHolder(value = Mods.ModIDs.GREG_TECH, templatePrefix = "vfn")
public class HeatPumpTests {

    record Rig(MTEHeatPump pump, MTEIntegratedFluidInputHatch input, MTEIntegratedFluidOutputHatch output,
        IGregTechTileEntity supply) {
        void charge(GameTestHelper helper, float target) {
            var controller = pump.getBaseMetaTileEntity();
            helper.assertTrue(pump.checkStructure(true, controller), "3x3x3 heat pump forms");
            helper.assertTrue(input.getNetwork() != null && output.getNetwork() != null && input.getNetwork() != output.getNetwork(),
                "Two distinct VFN circuits");
            input.getNetwork().replaceCanonicalState(IFNCanonicalState.fromRaw("water", 10_000_000, 10_423_434_875_000L));
            pump.setFluidAmountPerOperation(100);
            pump.setTargetTemperatureDelta(target);
            pump.fixAllIssues();
            controller.enableWorking();
        }

        NBTTagCompound inventory() {
            var saved = new NBTTagCompound();
            pump.saveNBTData(saved);
            return saved;
        }
    }

    static Rig place(GameTestHelper helper) {
        return place(helper, (short) 42);
    }

    static Rig place(GameTestHelper helper, short energyHatchId) {
        var p = helper.absolute("controller");
        return placeAt(helper.getWorld(), p.x(), p.y(), p.z(), energyHatchId);
    }

    static Rig placeDiluteOxygen(GameTestHelper h) {
        var rig = place(h);
        var p = h.absolute("controller");
        InjectorTests.placeAt(h.getWorld(), p.x() - 2, p.y(), p.z() + 1, (short) 5773, ForgeDirection.EAST);
        return rig;
    }

    static void startDiluteOxygen(GameTestHelper h, Rig rig) {
        var tile = rig.pump().getBaseMetaTileEntity();
        var p = h.absolute("controller");
        var injector = (IGregTechTileEntity) h.getWorld().getTileEntity(p.x() - 2, p.y(), p.z() + 1);
        h.assertTrue(rig.pump().checkStructure(true, tile), "Heat pump formed");
        rig.pump().setFluidAmountPerOperation(1000);
        rig.pump().setOperatingMode(HeatPumpMode.TARGET_ENERGY);
        rig.pump().setTargetEnergy(128);
        rig.pump().setTargetHeating(false);
        rig.pump().fixAllIssues();
        h.assertEquals(8, injector.fill(ForgeDirection.WEST, net.minecraftforge.fluids.FluidRegistry.getFluidStack("oxygen", 8), true),
            "Eight ordinary units supplied to a 10000 L inlet");
        h.assertEquals(1L, rig.supply().injectEnergyUnits(ForgeDirection.NORTH, 128, 1), "Power available");
        tile.enableWorking();
    }

    @GameTest(template = "radiator", timeoutTicks = 450)
    public static void roomTemperatureHeliumCannotEnterTheWorkingChamber(GameTestHelper h) {
        var pos = h.absolute("controller");
        var rig = place(h, (short) gregtech.api.enums.MetaTileEntityIDs.ENERGY_HATCH_IV.ID);
        var injector = (gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidInjectorHatch)
            InjectorTests.placeAt(h.getWorld(), pos.x()-2, pos.y(), pos.z()+1, (short) 5773, ForgeDirection.EAST).getMetaTileEntity();
        long[] supplied = { 0 }, admitted = { 0 };
        boolean[] running = { false };
        h.onEachTick("deliver GT helium and power", () -> {
            if (!running[0]) return;
            if (supplied[0] == 0) supplied[0] += 8192 * rig.supply().injectEnergyUnits(ForgeDirection.NORTH, 8192, 1);
            admitted[0] += injector.fill(net.minecraftforge.fluids.FluidRegistry.getFluidStack("helium", 32), true);
        });
        h.startSequence().thenWaitUntil(() -> h.assertTrue(injector.getNetwork() != null
            && injector.getNetwork() == rig.input().getNetwork() && rig.output().getNetwork() != null
            && rig.pump().checkStructure(true, rig.pump().getBaseMetaTileEntity()), "Formed machine and separate ports"))
            .thenExecute(() -> {
                rig.pump().setFluidAmountPerOperation(10000);
                rig.pump().setOperatingMode(HeatPumpMode.TARGET_TEMPERATURE);
                rig.pump().setTargetTemperatureDelta(3);
                rig.pump().fixAllIssues();
                rig.pump().getBaseMetaTileEntity().enableWorking();
                running[0] = true;
            }).thenWaitUntil(() -> h.assertEquals(gregtech.api.recipe.check.SimpleCheckRecipeResult.ofFailure("vfn_chamber_intake"),
                rig.pump().getCheckRecipeResult(), "A full stroke is available but cannot enter the working chamber"))
            .thenIdle(80).thenExecute(() -> {
                var feed = rig.input().getNetwork();
                h.assertTrue(admitted[0] > 1 && !feed.intakeStrokeTooSmall(10000, 1), "More than one feed portion at the inlet");
                h.assertTrue(feed.getPressure() > 0 && feed.getPressure() <= 0.9, "Positive pressure within the injector limit");
                h.assertEquals(admitted[0] * 111111L, feed.getAmountQ(), "Every offered portion retained at inlet");
                h.assertEquals(0L, rig.inventory().getLong("pumpMicroKg") + rig.output().getNetwork().getAmountQ(), "No partial intake");
                h.assertTrue(supplied[0] > 0, "Working power supply");
                h.assertEquals(supplied[0], rig.supply().getStoredEU(), "No charge for refused intake");
                h.recordDiagnostic("Warm inlet: " + admitted[0] + " GT units, " + feed.getTemperature() + " K, "
                    + feed.getPressure() + " bar, " + supplied[0] + " EU retained");
            }).thenSucceed();
    }

    @GameTest(template = "water_line", timeoutTicks = 8000)
    public static void injectorHeliumStartsPoweredCoolingLine(GameTestHelper h) {
        var site = RemoteMachineSite.at(h, "controller");
        var feed = placeSteelShell(site.world(), site.x(), site.y(), site.z(),
            MTEIntegratedFluidLiquidPump.HIGH_PRESSURE_ID,
            (short) gregtech.api.enums.MetaTileEntityIDs.ENERGY_HATCH_LuV.ID);
        var chiller = placeSteelShell(site.world(), site.x() + 4, site.y(), site.z(), (short) 32030, (short) 44,
            MTEIntegratedFluidInputHatch.REINFORCED_ID, (short) 5772);
        var link = (MTEIntegratedFluidPipe)
            site.place(2, 0, 1, MTEIntegratedFluidPipe.REINFORCED_ID,
                ForgeDirection.WEST).getMetaTileEntity();
        h.assertTrue(link.connect(ForgeDirection.WEST) > 0 && link.connect(ForgeDirection.EAST) > 0, "Interstage pipe connected");
        site.place(2, 0, 2, 5775, ForgeDirection.SOUTH);
        var jacket = (MTEIntegratedFluidPipe)
            site.place(2, 0, 3, 5770, ForgeDirection.NORTH).getMetaTileEntity();
        var water = site.place(2, 0, 4, 5773, ForgeDirection.NORTH);
        h.assertTrue(link.connect(ForgeDirection.SOUTH) > 0 && jacket.connect(ForgeDirection.NORTH) > 0
            && jacket.connect(ForgeDirection.SOUTH) > 0, "Water and delivery header share a thermal contact");
        for (int y = 1; y <= 2; y++) {
            var hot = (MTEIntegratedFluidPipe) site.place(2, y, 1, MTEIntegratedFluidPipe.REINFORCED_ID, ForgeDirection.DOWN).getMetaTileEntity();
            site.place(2, y, 2, 5775, ForgeDirection.SOUTH);
            var cold = (MTEIntegratedFluidPipe) site.place(2, y, 3, 5770, ForgeDirection.DOWN).getMetaTileEntity();
            h.assertTrue(hot.connect(ForgeDirection.DOWN) > 0 && cold.connect(ForgeDirection.DOWN) > 0
                && hot.connect(ForgeDirection.SOUTH) > 0 && cold.connect(ForgeDirection.NORTH) > 0, "Connected aftercooler row");
        }
        var waterMeter = new AmbientTests.AmbientMeter(h, jacket::getNetwork);
        var injector = site.place(-2, 0, 1, 5773, ForgeDirection.EAST);
        var pump = (MTEIntegratedFluidLiquidPump) feed.controller().getMetaTileEntity();
        var cooler = (MTEHeatPump) chiller.controller().getMetaTileEntity();
        boolean[] running = { false };
        long[] admitted = { 0 }, feedEu = { 0 }, chillerEu = { 0 }, waterEnergy = { 0 };
        h.onEachTick("deliver ordinary GT helium and meter power at both hatches", () -> {
            if (!running[0]) return;
            admitted[0] += injector.fill(ForgeDirection.WEST, net.minecraftforge.fluids.FluidRegistry.getFluidStack("helium", 32), true);
            if (feed.supply().getEUCapacity() - feed.supply().getStoredEU() >= 8192)
                feedEu[0] += 8192 * feed.supply().injectEnergyUnits(ForgeDirection.NORTH, 8192, 1);
            if (chiller.supply().getEUCapacity() - chiller.supply().getStoredEU() >= 2048)
                chillerEu[0] += 2048 * chiller.supply().injectEnergyUnits(ForgeDirection.NORTH, 2048, 1);
        });
        h.startSequence().thenWaitUntil(() -> h.assertTrue(link.getNetwork() != null && link.getNetwork() == feed.output().getNetwork()
            && link.getNetwork() == chiller.input().getNetwork() && pump.checkStructure(true, feed.controller())
            && cooler.checkStructure(true, chiller.controller())
            && water.fill(ForgeDirection.SOUTH, new net.minecraftforge.fluids.FluidStack(net.minecraftforge.fluids.FluidRegistry.WATER, 64), false) > 0,
            "Two formed multiblocks with an operational water supply"))
            .thenExecute(() -> {
                h.assertEquals(0L, feed.input().getNetwork().getAmountQ() + link.getNetwork().getAmountQ()
                    + chiller.output().getNetwork().getAmountQ(), "Empty plant");
                h.assertTrue(water.fill(ForgeDirection.SOUTH, new net.minecraftforge.fluids.FluidStack(net.minecraftforge.fluids.FluidRegistry.WATER, 64), true) > 0,
                    "Ambient water supplied through its own injector");
                waterEnergy[0] = waterMeter.withoutAmbient(jacket.getNetwork());
                pump.fixAllIssues();
                pump.setGasIntake(true);
                pump.setMode(2);
                pump.setInterval(10);
                pump.setTarget(3000);
                cooler.fixAllIssues();
                cooler.setFluidAmountPerOperation(10000);
                cooler.setOperatingMode(HeatPumpMode.TARGET_TEMPERATURE);
                cooler.setTargetTemperatureDelta(320);
                feed.controller().enableWorking();
                chiller.controller().enableWorking();
                running[0] = true;
            }).thenWaitUntil(() -> {
                var outlet = chiller.output().getNetwork();
                var saved = new NBTTagCompound();
                cooler.saveNBTData(saved);
                long compressed = feedEu[0] - feed.supply().getStoredEU(), cooled = chillerEu[0] - chiller.supply().getStoredEU();
                h.assertTrue(outlet.getAmountQ() > 0 && cooled > 0, "Waiting for cooled helium: interstage " + link.getNetwork().getPressure()
                    + " bar, " + link.getNetwork().getTemperature() + " K, charge " + saved.getLong("pumpMicroKg") + " microkg, outlet " + outlet.getAmountQ()
                    + " microkg, paid " + compressed + " and " + cooled + " EU, " + pump.status() + ", " + cooler.getCheckRecipeResult().getDisplayString());
                h.assertEquals(admitted[0] * 111111L, feed.input().getNetwork().getAmountQ() + link.getNetwork().getAmountQ()
                    + saved.getLong("pumpMicroKg") + outlet.getAmountQ(), "All helium entered through the injector");
                h.assertTrue(compressed > 0, "Separate feed pump paid for delivery");
                h.assertTrue(waterMeter.withoutAmbient(jacket.getNetwork()) > waterEnergy[0], "Cooling water received compression heat");
                h.recordDiagnostic(admitted[0] + " GT units admitted, " + outlet.getAmountQ() + " microkg out, feed pump "
                    + compressed + " EU, heat pump " + cooled + " EU");
            }).thenSucceed();
    }

    @GameTest(template = "radiator", timeoutTicks = 1500)
    public static void compressedWarmHeliumYieldsLiquid(GameTestHelper h) {
        var rig = placeLiquefier(h);
        h.startSequence().thenWaitUntil(() -> rig.ready(h)).thenExecute(() -> rig.start(h))
            .thenWaitUntil(() -> rig.checkLiquid(h)).thenSucceed();
    }

    static Liquefier placeLiquefier(GameTestHelper h) {
        var site = RemoteMachineSite.at(h, "controller");
        var shell = placeSteelShell(site.world(), site.x(), site.y(), site.z(), (short) 32030,
            (short) gregtech.api.enums.MetaTileEntityIDs.ENERGY_HATCH_UXV.ID,
            MTEIntegratedFluidInputHatch.REINFORCED_ID, MTEIntegratedFluidOutputHatch.REINFORCED_ID);
        for (int dy = 0; dy < 3; dy++) for (int dz = 1; dz <= 3; dz++) {
            var pipe = (MTEIntegratedFluidPipe) site.place(2, dy, dz, MTEIntegratedFluidPipe.REINFORCED_ID,
                ForgeDirection.WEST).getMetaTileEntity();
            var join = dz > 1 ? ForgeDirection.NORTH : dy > 0 ? ForgeDirection.DOWN : ForgeDirection.WEST;
            h.assertTrue(pipe.connect(join) > 0, "Cryogenic receiver connected");
        }
        for (int dy = 0; dy < 4; dy++) for (int dz = 1; dz <= 7; dz++) {
            var pipe = (MTEIntegratedFluidPipe)
                site.place(-2, dy, dz, MTEIntegratedFluidPipe.REINFORCED_ID,
                    ForgeDirection.EAST).getMetaTileEntity();
            ForgeDirection join = dz > 1 ? ForgeDirection.NORTH : dy > 0 ? ForgeDirection.DOWN : ForgeDirection.EAST;
            h.assertTrue(pipe.connect(join) > 0, "Feed manifold connected");
        }
        return new Liquefier(h, shell);
    }

    static final class Liquefier {
        private final SteelShell shell;
        private final MTEHeatPump pump;
        private final AmbientTests.AmbientMeter hotMeter, coldMeter;
        private static final long START_MASS = 29 * 300000L, START_HEAT = 29 * 281920739991L;
        private long paidIn;
        private boolean power;

        Liquefier(GameTestHelper h, SteelShell shell) {
            this.shell = shell;
            pump = (MTEHeatPump) shell.controller().getMetaTileEntity();
            hotMeter = new AmbientTests.AmbientMeter(h, shell.input()::getNetwork);
            coldMeter = new AmbientTests.AmbientMeter(h, shell.output()::getNetwork);
            h.onEachTick("meter electricity to the liquefier", () -> {
                if (!power) return;
                long volts = shell.supply().getInputVoltage();
                if (shell.supply().getEUCapacity() - shell.supply().getStoredEU() >= volts)
                    paidIn += volts * shell.supply().injectEnergyUnits(ForgeDirection.NORTH, volts, 1);
            });
        }

        void ready(GameTestHelper h) {
            h.assertTrue(shell.input().getNetwork() != null && shell.input().getNetwork().getTopologySnapshot().baseVolume().toWholeLiters() == 2900
                && shell.output().getNetwork() != null && shell.output().getNetwork().getTopologySnapshot().baseVolume().toWholeLiters() == 1000
                && pump.checkStructure(true, shell.controller()), "Rated feed and receiver connected");
        }

        void start(GameTestHelper h) {
            var feed = shell.input().getNetwork();
            feed.replaceCanonicalState(IFNCanonicalState.fromRaw("helium", START_MASS, START_HEAT));
            h.assertTrue(Math.abs(feed.getTemperature() - 300) < 0.1 && Math.abs(feed.getPressure() - 19) < 0.2,
                "Prepared warm compressed gas, no cryogenic charge");
            h.assertEquals(0L, shell.output().getNetwork().getAmountQ(), "Empty receiver");
            pump.setFluidAmountPerOperation(10000);
            pump.setOperatingMode(HeatPumpMode.TARGET_TEMPERATURE);
            pump.setTargetTemperatureDelta(2.49f);
            pump.fixAllIssues();
            shell.controller().enableWorking();
            power = true;
        }

        void checkLiquid(GameTestHelper h) {
            var outlet = shell.output().getNetwork();
            var state = outlet.getSolvedState();
            h.assertTrue(state.isPresent() && state.get().liquidMassFraction() * outlet.getAmountQ() >= 111111,
                "Waiting for one liquid portion: " + outlet.getAmountQ() + " microkg at " + outlet.getTemperature() + " K");
            var saved = new NBTTagCompound();
            pump.saveNBTData(saved);
            long mass = saved.getLong("pumpMicroKg") + shell.input().getNetwork().getAmountQ() + outlet.getAmountQ();
            h.assertEquals(START_MASS, mass, "No makeup helium after charging the warm supply");
            long paid = paidIn - shell.supply().getStoredEU();
            long removed = START_HEAT - hotMeter.withoutAmbient(shell.input().getNetwork()) - coldMeter.withoutAmbient(outlet) - saved.getLong("pumpMicroEu");
            h.assertTrue(paid > 0 && removed > 0, "Paid refrigeration removed internal energy");
            h.recordDiagnostic("Cold outlet: " + outlet.getAmountQ() + " microkg, " + outlet.getTemperature()
                + " K, liquid fraction " + state.get().liquidMassFraction() + ", paid " + paid + " EU");
        }
    }

    @GameTest(template = "radiator", timeoutTicks = 450)
    public static void diluteOxygenWaitsWithoutPowerDebit(GameTestHelper h) {
        var rig = placeDiluteOxygen(h);
        var p = h.absolute("controller");
        var injector = (IGregTechTileEntity) h.getWorld().getTileEntity(p.x() - 2, p.y(), p.z() + 1);
        long[] atCutoff = new long[1];
        h.startSequence().thenIdle(25).thenExecute(() -> startDiluteOxygen(h, rig))
            .thenWaitUntil(() -> h.assertEquals(gregtech.api.recipe.check.SimpleCheckRecipeResult.ofFailure("vfn_inlet_portion"),
            rig.pump().getCheckRecipeResult(), "Controller identifies a stroke with less than one oxygen portion"))
            .thenIdle(120).thenExecute(() -> {
                h.assertEquals(128L, rig.supply().getStoredEU(), "Repeated intake attempts cost no EU");
                h.assertEquals(8 * 888_888L, rig.input().getNetwork().getAmountQ(), "All oxygen stayed at the inlet");
                h.assertEquals(0L, rig.inventory().getLong("pumpMicroKg"), "No hidden fraction in the machine");
                h.assertEquals(0L, rig.output().getNetwork().getAmountQ(), "No oxygen delivered");
                var gas = net.minecraftforge.fluids.FluidRegistry.getFluidStack("oxygen", 100);
                int extra = injector.fill(ForgeDirection.WEST, gas, true);
                h.assertTrue(extra > 0, "Initial eight units did not fill the inlet to its limit");
                atCutoff[0] = (8 + extra) * 888_888L;
                h.assertEquals(0, injector.fill(ForgeDirection.WEST, gas, false), "Another whole GT unit would exceed the inlet limit");
            }).thenIdle(120).thenExecute(() -> {
                h.assertEquals(atCutoff[0], rig.input().getNetwork().getAmountQ(), "Even a full passive supply has no deliverable stroke");
                h.assertEquals(128L, rig.supply().getStoredEU(), "Waiting at the cutoff cannot debit electricity");
                h.recordDiagnostic("Uncooled supply at cutoff: " + atCutoff[0] / 888_888 + " GT units, "
                    + rig.input().getNetwork().getTemperature() + " K, " + rig.input().getNetwork().getPressure() + " bar");
            }).thenSucceed();
    }

    static Rig placeAt(World w, int x, int y, int z, short energyHatchId) {
        var shell = placeSteelShell(w, x, y, z, (short) 32030, energyHatchId);
        return new Rig((MTEHeatPump) shell.controller().getMetaTileEntity(), shell.input(), shell.output(), shell.supply());
    }

    record SteelShell(IGregTechTileEntity controller, MTEIntegratedFluidInputHatch input,
        MTEIntegratedFluidOutputHatch output, IGregTechTileEntity supply) {}

    static void placeSteelCasings(World w, int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = 0; dz <= 2; dz++) w.setBlock(x + dx, y + dy, z + dz, GregTechAPI.sBlockCasings2, 0, 3);
            }
        }
    }

    static SteelShell placeSteelShell(World w, int x, int y, int z, short controllerId, short energyHatchId) {
        return placeSteelShell(w, x, y, z, controllerId, energyHatchId, (short) 5771, (short) 5772);
    }

    static SteelShell placeSteelShell(World w, int x, int y, int z, short controllerId, short energyHatchId, short inputId, short outputId) {
        return placeSteelShell(w, x, y, z, controllerId, energyHatchId, inputId, outputId, ForgeDirection.NORTH);
    }

    static SteelShell placeSteelShell(World w, int x, int y, int z, short controllerId, short energyHatchId, short inputId,
        short outputId, ForgeDirection controllerFace) {
        return placeSteelShell(w, x, y, z, controllerId, energyHatchId, inputId, outputId, controllerFace, 1, 0);
    }

    static SteelShell placeSteelShell(World w, int x, int y, int z, short controllerId, short energyHatchId, short inputId,
        short outputId, ForgeDirection controllerFace, int portRow, int powerHeight) {
        if (controllerFace != ForgeDirection.NORTH && controllerFace != ForgeDirection.SOUTH) throw new IllegalArgumentException("Controller face");
        placeSteelCasings(w, x, y, z);
        var controller = InjectorTests.placeAt(w, x, y, controllerFace == ForgeDirection.SOUTH ? z + 2 : z, controllerId, controllerFace);
        var input = InjectorTests.placeAt(w, x - 1, y, z + portRow, inputId, ForgeDirection.WEST);
        var output = InjectorTests.placeAt(w, x + 1, y, z + portRow, outputId, ForgeDirection.EAST);
        var supply = InjectorTests.placeAt(w, x - 1, y + powerHeight, z, energyHatchId, ForgeDirection.NORTH);
        InjectorTests.placeAt(w, x + 1, y, z, (short) 15497, ForgeDirection.NORTH);
        return new SteelShell(controller, (MTEIntegratedFluidInputHatch) input.getMetaTileEntity(),
            (MTEIntegratedFluidOutputHatch) output.getMetaTileEntity(), supply);
    }

    @GameTest(template = "radiator", timeoutTicks = 550)
    public static void meteredPowerBoilsAWorkingCharge(GameTestHelper helper) {
        var rig = place(helper, (short) 44);
        long[] delivered = { 0 };
        double[] initialVapor = { 0 };
        boolean[] enabled = { false };
        helper.onEachTick("deliver one EV packet", () -> {
            if (!enabled[0]) return;
            long before = rig.supply().getStoredEU();
            rig.supply().injectEnergyUnits(ForgeDirection.NORTH, 2048, 1);
            delivered[0] += rig.supply().getStoredEU() - before;
        });
        helper.startSequence().thenIdle(20).thenExecute(() -> {
            rig.charge(helper, 310);
            rig.pump().setFluidAmountPerOperation(1000);
            rig.pump().setOperatingMode(HeatPumpMode.TARGET_ENERGY);
            rig.pump().setTargetEnergy(2000);
            rig.pump().setTargetHeating(true);
        }).thenWaitUntil(() -> helper.assertTrue(rig.inventory().getLong("pumpMicroKg") > 0, "An unpowered charge waits inside"))
            .thenExecute(() -> {
                initialVapor[0] = heldVaporFraction(rig);
                helper.assertTrue(initialVapor[0] > 0 && initialVapor[0] < 1, "Working charge starts wet: " + initialVapor[0]);
                enabled[0] = true;
            }).thenWaitUntil(() -> helper.assertTrue(delivered[0] > rig.supply().getStoredEU(), "First paid tick"))
            .thenExecute(() -> {
                helper.assertEquals(2000L, delivered[0] - rig.supply().getStoredEU(), "Draw configured EU/t from the real hatch");
                helper.assertTrue(heldVaporFraction(rig) > initialVapor[0], "Paid heat boils some of the charge");
                helper.assertEquals(0L, rig.output().getNetwork().getAmountQ(), "Do not discharge after only one paid tick");
            }).thenWaitUntil(() -> helper.assertTrue(rig.output().getNetwork().getAmountQ() > 0, "Processed fluid reaches output"))
            .thenExecute(() -> {
                helper.assertEquals(40_000L, delivered[0] - rig.supply().getStoredEU(), "Twenty paid 2000 EU ticks per charge");
                helper.assertEquals(10_000_000L, rig.input().getNetwork().getAmountQ() + rig.output().getNetwork().getAmountQ()
                    + rig.inventory().getLong("pumpMicroKg"), "Power-mode discharge conserves mass");
            }).thenSucceed();
    }

    @GameTest(template = "radiator", timeoutTicks = 1200)
    public static void coolingCycleSurvivesChunkReload(GameTestHelper helper) {
        var site = RemoteMachineSite.reserve(helper, 128, 1);
        var original = placeAt(site.world(), site.x(), site.y(), site.z(), (short) 42);
        site.assignOwner();
        var rig = new Rig[] { original };
        var beforeUnload = new NBTTagCompound[1];
        long[] delivered = { 0 };
        long[] initialEnergy = { 0 };
        boolean[] powered = { false };
        helper.onEachTick("supply the reloaded controller", () -> {
            if (!powered[0]) return;
            var hatch = rig[0].supply();
            long before = hatch.getStoredEU();
            hatch.injectEnergyUnits(ForgeDirection.NORTH, 128, 1);
            delivered[0] += hatch.getStoredEU() - before;
        });
        helper.startSequence().thenIdle(20).thenExecute(() -> {
            original.charge(helper, 310);
            original.pump().setFluidAmountPerOperation(1000);
            original.pump().setOperatingMode(HeatPumpMode.TARGET_ENERGY);
            original.pump().setTargetEnergy(128);
            original.pump().setTargetHeating(false);
        }).thenWaitUntil(() -> helper.assertTrue(original.inventory().getLong("pumpMicroKg") > 0, "Charge admitted"))
            .thenExecute(() -> {
                initialEnergy[0] = original.inventory().getLong("pumpMicroEu");
                original.supply().injectEnergyUnits(ForgeDirection.NORTH, 128, 1);
            }).thenWaitUntil(() -> helper.assertTrue(original.inventory().getLong("pumpMicroEu") < initialEnergy[0],
                "One paid MV tick removed heat from the fluid"))
            .thenExecute(() -> {
                beforeUnload[0] = original.inventory();
                site.requestUnload();
            }).thenWaitUntil(() -> helper.assertTrue(site.absent(), "Working controller actually unloaded"))
            .thenIdle(30).thenExecute(() -> {
                site.reloadForObservation(helper);
                rig[0] = readPump(site);
                helper.assertTrue(rig[0].pump() != original.pump(), "New controller instance read from disk");
                var restored = rig[0].inventory();
                helper.assertEquals(beforeUnload[0].getLong("pumpMicroEu"), restored.getLong("pumpMicroEu"), "Paid cooling survives");
                helper.assertEquals(beforeUnload[0].getLong("pumpMicroKg"), restored.getLong("pumpMicroKg"), "Working mass survives");
                powered[0] = true;
            }).thenWaitUntil(() -> helper.assertTrue(rig[0].output().getNetwork() != null
                && rig[0].output().getNetwork().getAmountQ() > 0, "Reloaded machine finishes and discharges"))
            .thenExecute(() -> helper.assertEquals(19L * 128, delivered[0] - rig[0].supply().getStoredEU(),
                "Only the remaining nineteen cooling ticks are charged")).thenSucceed();
    }

    @GameTest(template = "radiator", timeoutTicks = 1600)
    public static void splitChargeWaitsForItsBlueOutlet(GameTestHelper helper) {
        var rig = place(helper);
        var blue = addBlueOutlet(helper, rig);
        boolean[] powered = { false };
        long[] start = new long[3];
        long[] redAtStop = new long[1];
        helper.onEachTick("power the split-flow stage", () -> {
            if (powered[0]) rig.supply().injectEnergyUnits(ForgeDirection.NORTH, 128, 1);
        });
        helper.startSequence().thenIdle(20).thenExecute(() -> {
            rig.charge(helper, 400);
            rig.pump().setSplitFlowMode(true);
            rig.pump().setSplitRatio(0.25f);
            helper.assertTrue(rig.pump().hasValidSplitFlowHatches() && blue.getNetwork() != rig.output().getNetwork(),
                "Red and blue ports serve distinct circuits");
            blue.getNetwork().replaceCanonicalState(IFNCanonicalState.fromRaw("water", 10_000_000, 22_037_935_000_000L));
        }).thenWaitUntil(() -> helper.assertTrue(rig.input().getNetwork().getAmountQ() < 10_000_000, "A split charge entered both branches"))
            .thenExecute(() -> {
                var saved = rig.inventory();
                long red = saved.getLong("pumpMicroKg"), cold = saved.getLong("pumpPartnerMicroKg");
                helper.assertTrue(red > 0 && Math.abs(3*red - cold) <= 3, "One quarter of the admitted mass enters red");
                helper.assertEquals(10_000_000L, rig.input().getNetwork().getAmountQ() + red + cold, "No intake loss");
                start[0] = saved.getLong("pumpMicroEu");
                start[1] = saved.getLong("pumpPartnerMicroEu");
                start[2] = rig.input().getNetwork().getAmountQ();
                rig.supply().injectEnergyUnits(ForgeDirection.NORTH, 128, 1);
            }).thenWaitUntil(() -> helper.assertTrue(rig.inventory().getLong("pumpMicroEu") > start[0], "A paid tick heats red"))
            .thenExecute(() -> {
                var saved = rig.inventory();
                helper.assertTrue(saved.getLong("pumpPartnerMicroEu") < start[1], "Blue, not ambient, supplies the extracted heat");
                helper.assertEquals(128_000_000L, saved.getLong("pumpMicroEu") - start[0] + saved.getLong("pumpPartnerMicroEu") - start[1],
                    "Combined energy gain equals the real MV packet");
                powered[0] = true;
            }).thenWaitUntil(() -> helper.assertTrue(rig.output().getNetwork().getAmountQ() > 0, "Red discharges despite blue backpressure"))
            .thenExecute(() -> redAtStop[0] = rig.output().getNetwork().getAmountQ()).thenIdle(60).thenExecute(() -> {
                helper.assertEquals(redAtStop[0], rig.output().getNetwork().getAmountQ(), "Red cannot deliver its charge twice");
                helper.assertEquals(start[2], rig.input().getNetwork().getAmountQ(), "Incomplete discharge blocks the next intake");
                helper.assertEquals(10_000_000L, blue.getNetwork().getAmountQ(), "Blue held against pressure");
                blue.getNetwork().replaceCanonicalState(IFNCanonicalState.empty());
            }).thenWaitUntil(() -> helper.assertTrue(blue.getNetwork().getAmountQ() > 0, "Blue resumes when backpressure is released"))
            .thenExecute(() -> helper.assertEquals(10_000_000L, rig.input().getNetwork().getAmountQ()
                + rig.output().getNetwork().getAmountQ() + blue.getNetwork().getAmountQ()
                + rig.inventory().getLong("pumpMicroKg") + rig.inventory().getLong("pumpPartnerMicroKg"), "Both outputs and retained heels conserve mass"))
            .thenSucceed();
    }

    @GameTest(template = "radiator", timeoutTicks = 2300)
    public static void splitDischargeSurvivesChunkReload(GameTestHelper helper) {
        var site = RemoteMachineSite.reserve(helper, 192, 1);
        var original = placeAt(site.world(), site.x(), site.y(), site.z(), (short) 42);
        site.assignOwner();
        var blue = addBlueOutlet(helper, original);
        Rig[] restored = new Rig[1];
        MTEIntegratedFluidOutputHatch[] second = new MTEIntegratedFluidOutputHatch[1];
        NBTTagCompound[] held = new NBTTagCompound[1];
        long[] redTaken = new long[1];
        long[] powerAtFirstDelivery = new long[1];
        boolean[] supplying = { true };
        helper.onEachTick("power only the original batch", () -> {
            if (supplying[0]) original.supply().injectEnergyUnits(ForgeDirection.NORTH, 128, 1);
        });
        helper.startSequence().thenIdle(20).thenExecute(() -> {
            original.charge(helper, 400);
            original.pump().setSplitFlowMode(true);
            original.pump().setSplitRatio(0.25f);
            blue.getNetwork().replaceCanonicalState(IFNCanonicalState.fromRaw("water", 10_000_000, 22_037_935_000_000L));
        }).thenWaitUntil(() -> helper.assertTrue(original.output().getNetwork().getAmountQ() > 0, "Red delivers first"))
            .thenExecute(() -> {
                supplying[0] = false;
                helper.assertEquals(gregtech.api.recipe.check.SimpleCheckRecipeResult.ofFailure("vfn_blue_outlet_head"),
                    original.pump().getCheckRecipeResult(), "The terminal names the blocked blue outlet");
                powerAtFirstDelivery[0] = original.supply().getStoredEU();
                held[0] = original.inventory();
                helper.assertTrue(held[0].getLong("pumpMicroKg") > 0 && held[0].getLong("pumpPartnerMicroKg") > 0,
                    "Red retains a heel and blue retains its charge");
                original.input().getNetwork().freeze("Isolate the inlet after the measured batch");
                site.requestUnload();
            }).thenWaitUntil(() -> helper.assertTrue(site.absent(), "Partially discharged machine unloaded"))
            .thenIdle(30).thenExecute(() -> {
                site.reloadForObservation(helper);
                restored[0] = readPump(site);
                second[0] = (MTEIntegratedFluidOutputHatch) site.tile(1, 1, 1).getMetaTileEntity();
                helper.assertTrue(restored[0].pump() != original.pump(), "New split controller loaded");
                var saved = restored[0].inventory();
                for (String key : new String[] { "pumpMicroKg", "pumpMicroEu", "pumpPartnerMicroKg", "pumpPartnerMicroEu" })
                    helper.assertEquals(held[0].getLong(key), saved.getLong(key), "Restored chamber ledger: " + key);
            }).thenWaitUntil(() -> helper.assertTrue(restored[0].output().getNetwork() != null && second[0].getNetwork() != null
                && restored[0].input().getNetwork() != null, "All saved ports reattached"))
            .thenExecute(() -> {
                var red = restored[0].output().getNetwork();
                redTaken[0] = red.getAmountQ();
                helper.assertTrue(redTaken[0] > 0, "First red delivery survived on disk");
                // Account for the collected red water and leave an evacuated receiver for the retry
                red.clearFluid();
            }).thenIdle(140).thenExecute(() -> {
                helper.assertEquals(0L, restored[0].output().getNetwork().getAmountQ(), "No second red delivery into vacuum");
                helper.assertEquals(10_000_000L, second[0].getNetwork().getAmountQ(), "Blue receiver still blocks discharge");
                restored[0].pump().getBaseMetaTileEntity().disableWorking();
                second[0].getNetwork().clearFluid();
            }).thenIdle(100).thenExecute(() -> {
                helper.assertEquals(0L, second[0].getNetwork().getAmountQ(), "An open outlet cannot drain a disabled machine");
                helper.assertEquals(held[0].getLong("pumpPartnerMicroEu"), restored[0].inventory().getLong("pumpPartnerMicroEu"),
                    "Blue retains its stored energy until restarted");
                restored[0].pump().getBaseMetaTileEntity().enableWorking();
            }).thenWaitUntil(() -> helper.assertTrue(second[0].getNetwork().getAmountQ() > 0, "Reloaded machine delivers blue"))
            .thenIdle(100).thenExecute(() -> {
                var rig = restored[0];
                var saved = rig.inventory();
                helper.assertEquals(0L, rig.output().getNetwork().getAmountQ(), "Blue release did not reopen red");
                helper.assertEquals(held[0].getLong("pumpMicroKg"), saved.getLong("pumpMicroKg"), "Red heel is unchanged");
                helper.assertEquals(held[0].getLong("pumpMicroEu"), saved.getLong("pumpMicroEu"), "No second energy debit on red");
                helper.assertEquals(held[0].getLong("pumpPartnerMicroKg"),
                    second[0].getNetwork().getAmountQ() + saved.getLong("pumpPartnerMicroKg"), "Blue discharged exactly once");
                helper.assertEquals(10_000_000L, rig.input().getNetwork().getAmountQ() + redTaken[0]
                    + saved.getLong("pumpMicroKg") + saved.getLong("pumpPartnerMicroKg") + second[0].getNetwork().getAmountQ(),
                    "Original source, collected red, retained chambers and blue output balance");
                helper.assertEquals(powerAtFirstDelivery[0], rig.supply().getStoredEU(), "Retrying an already paid charge costs no extra EU");
                helper.recordDiagnostic("Collected red kg=" + redTaken[0] / 1e6 + ", delivered blue kg="
                    + second[0].getNetwork().getAmountQ() / 1e6 + ", retained red kg=" + saved.getLong("pumpMicroKg") / 1e6
                    + ", retained blue kg=" + saved.getLong("pumpPartnerMicroKg") / 1e6);
            }).thenSucceed();
    }

    static Rig readPump(RemoteMachineSite site) {
        return new Rig((MTEHeatPump) site.tile(0, 0, 0).getMetaTileEntity(),
            (MTEIntegratedFluidInputHatch) site.tile(-1, 0, 1).getMetaTileEntity(),
            (MTEIntegratedFluidOutputHatch) site.tile(1, 0, 1).getMetaTileEntity(), site.tile(-1, 0, 0));
    }

    static MTEIntegratedFluidOutputHatch addBlueOutlet(GameTestHelper helper, Rig rig) {
        var p = rig.pump().getBaseMetaTileEntity();
        var blue = InjectorTests.placeAt(helper.getWorld(), p.getXCoord() + 1, p.getYCoord() + 1, p.getZCoord() + 1, (short) 5772, ForgeDirection.UP);
        blue.setColorization((byte) 4);
        rig.output().getBaseMetaTileEntity().setColorization((byte) 1);
        return (MTEIntegratedFluidOutputHatch) blue.getMetaTileEntity();
    }

    @GameTest(template = "radiator", timeoutTicks = 1700)
    public static void splitCopMeasuresBothWorkingFluids(GameTestHelper helper) {
        var rig = place(helper);
        var blue = addBlueOutlet(helper, rig);
        boolean[] enabled = { false };
        helper.onEachTick("power cooling between the two streams", () -> {
            if (enabled[0]) rig.supply().injectEnergyUnits(ForgeDirection.NORTH, 128, 2);
        });
        helper.startSequence().thenIdle(20).thenExecute(() -> {
            rig.charge(helper, 310);
            rig.pump().setSplitFlowMode(true);
            rig.pump().setSplitRatio(0.25f);
            rig.pump().setOperatingMode(HeatPumpMode.TARGET_COP);
            rig.pump().setTargetCOP(5);
            rig.pump().setTargetHeating(false);
            var blocked = IFNCanonicalState.fromRaw("water", 10_000_000, 22_037_935_000_000L);
            rig.output().getNetwork().replaceCanonicalState(blocked);
            blue.getNetwork().replaceCanonicalState(blocked);
            enabled[0] = true;
        }).thenWaitUntil(() -> helper.assertTrue(rig.inventory().getBoolean("pumpOutputReady"), "Both branches finished"))
            .thenExecute(() -> {
                var saved = rig.inventory();
                double cold = heldChamber(saved, "pump").state().temperatureKelvin();
                double hot = heldChamber(saved, "pumpPartner").state().temperatureKelvin();
                double cop = cold / (hot - cold);
                helper.assertTrue(Math.abs(cop - 5) < 0.01, "Cooling COP uses both measured temperatures: " + cop
                    + " at " + cold + " and " + hot);
                helper.assertTrue(Math.abs(rig.pump().getCOP() - cop) < 0.01, "GUI reports the final stream COP, not a trial packet: " + rig.pump().getCOP());
            }).thenSucceed();
    }

    record ColoredRig(Rig red, MTEIntegratedFluidInputHatch blueInput, MTEIntegratedFluidOutputHatch blueOutput) {}

    static ColoredRig coloredRig(GameTestHelper helper) {
        return coloredRig(helper, (short) 42);
    }

    private static ColoredRig coloredRig(GameTestHelper helper, short energyHatch) {
        var red = place(helper, energyHatch);
        var blueOutput = addBlueOutlet(helper, red);
        var p = helper.absolute("controller");
        var tile = InjectorTests.placeAt(helper.getWorld(), p.x() - 1, p.y() + 1, p.z() + 1, (short) 5771, ForgeDirection.UP);
        tile.setColorization((byte) 4);
        red.input().getBaseMetaTileEntity().setColorization((byte) 1);
        return new ColoredRig(red, (MTEIntegratedFluidInputHatch) tile.getMetaTileEntity(), blueOutput);
    }

    record LimitedExchanger(ColoredRig ports) {
        private static final long CHARGE = 450_000, RECEIVER = 1_000_000;

        void start(GameTestHelper helper) {
            var rig = ports.red();
            var pump = rig.pump();
            var tile = pump.getBaseMetaTileEntity();
            pump.setHeatExchangerMode(true);
            pump.setConfiguringHotStream(false);
            pump.setTargetTemperatureDelta(480);
            helper.assertTrue(pump.checkStructure(true, tile) && pump.hasValidHeatExchangerHatches(), "Four-port pump formed");
            pump.fixAllIssues();
            // Resume a cooling stroke with the hot side already near its calibrated ceiling
            var saved = rig.inventory();
            saved.setBoolean("pumpBatchActive", true);
            saved.setBoolean("pumpExchanger", true);
            saved.setBoolean("pumpBluePrimary", true);
            saved.setDouble("pumpBatchTargetK", 480);
            saved.setString("pumpSubstance", "water");
            saved.setLong("pumpMicroKg", CHARGE);
            saved.setLong("pumpMicroEu", vaporEnergy(CHARGE, 495));
            saved.setString("pumpPartnerSubstance", "water");
            saved.setLong("pumpPartnerMicroKg", CHARGE);
            saved.setLong("pumpPartnerMicroEu", vaporEnergy(CHARGE, 997));
            pump.loadNBTData(saved);
            var primed = IFNCanonicalState.fromRaw("water", RECEIVER, vaporEnergy(RECEIVER, 450));
            rig.output().getNetwork().replaceCanonicalState(primed);
            ports.blueOutput().getNetwork().replaceCanonicalState(primed);
            tile.enableWorking();
        }

        void supply(GameTestHelper helper) {
            helper.assertEquals(524288L, WaterLineTests.feedUV(ports.red().supply()), "The energy hatch accepted one UV packet");
        }

        private static long vaporEnergy(long microKg, double kelvin) {
            return Math.round(microKg * (2_257_000 + 2010 * (kelvin - 373.15) - 461.5 * kelvin));
        }

        void requireBlocked(GameTestHelper helper) {
            var rig = ports.red();
            helper.assertEquals(gregtech.api.recipe.check.SimpleCheckRecipeResult.ofFailure("vfn_paired_temperature_limit"),
                rig.pump().getCheckRecipeResult(), "Unreachable target shown on the terminal");
            helper.assertEquals(RECEIVER, rig.output().getNetwork().getAmountQ(), "Red has not discharged");
            helper.assertEquals(RECEIVER, ports.blueOutput().getNetwork().getAmountQ(), "Blue has not discharged");
            long paid = 524288 - rig.supply().getStoredEU();
            helper.assertTrue(paid > 0 && paid < 524288, "Paid heat moved and power is still available");
            var held = rig.inventory();
            helper.assertEquals(vaporEnergy(CHARGE, 495) + vaporEnergy(CHARGE, 997) + paid * 1_000_000,
                held.getLong("pumpMicroEu") + held.getLong("pumpPartnerMicroEu"), "Electrical debit balances both streams");
        }

        void reviseTarget(GameTestHelper helper) {
            var pump = ports.red().pump();
            pump.setConfiguringHotStream(true);
            pump.setUniversalValue(499);
            helper.assertTrue(ports.red().inventory().getBoolean("pumpTemperatureBlocked"), "Editing red cannot release blue");
            pump.setConfiguringHotStream(false);
            pump.setUniversalValue(499);
        }

        void requireDelivered(GameTestHelper helper, long retainedEu) {
            var rig = ports.red();
            var held = rig.inventory();
            long red = rig.output().getNetwork().getAmountQ() - RECEIVER, blue = ports.blueOutput().getNetwork().getAmountQ() - RECEIVER;
            helper.assertTrue(red > 0 && blue > 0, "Both original charges reached their own outlets");
            helper.assertEquals(CHARGE, red + held.getLong("pumpPartnerMicroKg"), "Red retained its mass");
            helper.assertEquals(CHARGE, blue + held.getLong("pumpMicroKg"), "Blue retained its mass");
            helper.assertEquals(retainedEu, rig.supply().getStoredEU(), "Releasing already cold enough water did not bill again");
        }
    }

    static LimitedExchanger limitedExchanger(GameTestHelper helper) {
        return new LimitedExchanger(coloredRig(helper, (short) 48));
    }

    @GameTest(template = "radiator", timeoutTicks = 1000)
    public static void temperatureLimitedChargeResumesAtRevisedTarget(GameTestHelper helper) {
        var line = limitedExchanger(helper);
        long[] retained = new long[1];
        helper.startSequence().thenIdle(20).thenExecute(() -> line.start(helper))
            .thenWaitUntil(() -> line.supply(helper)).thenWaitUntil(() -> line.requireBlocked(helper))
            .thenExecute(() -> retained[0] = line.ports().red().supply().getStoredEU())
            .thenIdle(100).thenExecute(() -> {
                helper.assertEquals(retained[0], line.ports().red().supply().getStoredEU(), "A parked batch stays paid");
                line.reviseTarget(helper);
            }).thenWaitUntil(() -> line.requireDelivered(helper, retained[0])).thenSucceed();
    }

    @GameTest(template = "radiator", timeoutTicks = 1400)
    public static void exchangerBillsWorkWithoutCrossingMass(GameTestHelper helper) {
        var line = coloredRig(helper);
        var rig = line.red();
        boolean[] power = { false };
        long[] amounts = new long[2], energy = new long[2];
        helper.onEachTick("power both streams", () -> {
            if (power[0]) rig.supply().injectEnergyUnits(ForgeDirection.NORTH, 128, 2);
        });
        helper.startSequence().thenIdle(20).thenExecute(() -> {
            rig.charge(helper, 350);
            rig.pump().setHeatExchangerMode(true);
            rig.pump().setConfiguringHotStream(false);
            rig.pump().setOperatingMode(HeatPumpMode.TARGET_COP);
            rig.pump().setTargetCOP(4);
            helper.assertTrue(rig.pump().hasValidHeatExchangerHatches(), "Four colored hatches form the exchanger");
        }).thenIdle(150).thenExecute(() -> {
            helper.assertEquals(10_000_000L, rig.input().getNetwork().getAmountQ(), "An empty blue inlet cannot consume red feed");
            line.blueInput().getNetwork().replaceCanonicalState(IFNCanonicalState.fromRaw("water", 6_000_000, 6_254_060_925_000L));
            line.blueOutput().getNetwork().replaceCanonicalState(IFNCanonicalState.fromRaw("water", 10_000_000, 22_037_935_000_000L));
        }).thenWaitUntil(() -> helper.assertTrue(line.blueInput().getNetwork().getAmountQ() < 6_000_000, "Both charges admitted"))
            .thenExecute(() -> {
                var saved = rig.inventory();
                amounts[0] = 10_000_000L - rig.input().getNetwork().getAmountQ();
                amounts[1] = 6_000_000L - line.blueInput().getNetwork().getAmountQ();
                helper.assertTrue(amounts[0] > amounts[1] && amounts[1] > 0, "Equal inlet liters contain different masses");
                helper.assertEquals(amounts[1], saved.getLong("pumpMicroKg"), "Blue is the controlled charge");
                helper.assertEquals(amounts[0], saved.getLong("pumpPartnerMicroKg"), "Red stays in its own chamber");
                energy[0] = saved.getLong("pumpMicroEu");
                energy[1] = saved.getLong("pumpPartnerMicroEu");
                rig.supply().injectEnergyUnits(ForgeDirection.NORTH, 128, 1);
            }).thenWaitUntil(() -> helper.assertTrue(rig.inventory().getLong("pumpMicroEu") < energy[0], "Cooling removed heat from blue"))
            .thenExecute(() -> {
                var saved = rig.inventory();
                helper.assertTrue(saved.getLong("pumpPartnerMicroEu") > energy[1], "Heat was delivered to red");
                helper.assertEquals(128_000_000L, saved.getLong("pumpMicroEu") - energy[0] + saved.getLong("pumpPartnerMicroEu") - energy[1],
                    "Only paid electricity increases combined energy");
                rig.pump().setConfiguringHotStream(true);
                power[0] = true;
            }).thenWaitUntil(() -> helper.assertTrue(rig.output().getNetwork().getAmountQ() > 0, "Unblocked red outlet receives its own stream"))
            .thenIdle(40).thenExecute(() -> {
                var saved = rig.inventory();
                helper.assertEquals(amounts[0], rig.output().getNetwork().getAmountQ() + saved.getLong("pumpPartnerMicroKg"), "Red ledger closes");
                helper.assertEquals(amounts[1], saved.getLong("pumpMicroKg"), "Blue remains inside under backpressure");
                helper.assertTrue(Math.abs(rig.pump().getCOP() - 4) < 0.01, "Active batch kept blue COP 4 despite changing the controls");
                energy[0] = saved.getLong("pumpMicroEu");
                line.blueInput().getNetwork().freeze("Isolate feed while the sub-portion heel settles");
                line.blueOutput().getNetwork().replaceCanonicalState(IFNCanonicalState.empty());
            }).thenWaitUntil(() -> helper.assertTrue(!rig.inventory().getBoolean("pumpBatchActive"), "Open outlet releases the cycle without a fractional delivery"))
            .thenExecute(() -> {
                var held = rig.inventory();
                helper.assertEquals(0L, line.blueOutput().getNetwork().getAmountQ(), "Sub-portion mass did not leave the machine");
                helper.assertEquals(amounts[1], held.getLong("pumpMicroKg"), "Blue mass stays in the blue chamber");
                helper.assertEquals(energy[0], held.getLong("pumpMicroEu"), "Completing the stroke cannot reset the blue energy");
                rig.pump().setFluidAmountPerOperation(1000);
                rig.pump().setOperatingMode(HeatPumpMode.TARGET_ENERGY);
                rig.pump().setTargetEnergy(32);
                line.blueInput().getNetwork().clearFrozen();
            }).thenWaitUntil(() -> helper.assertTrue(line.blueOutput().getNetwork().getAmountQ() > 0,
                "A later batch delivers a whole portion through the blue outlet"))
            .thenExecute(() -> {
                var saved = rig.inventory();
                long primary = saved.getLong("pumpMicroKg"), partner = saved.getLong("pumpPartnerMicroKg");
                boolean bluePrimary = saved.getBoolean("pumpBluePrimary");
                helper.assertEquals(10_000_000L, rig.input().getNetwork().getAmountQ() + rig.output().getNetwork().getAmountQ()
                    + (bluePrimary ? partner : primary), "No mixing after changing the controlled side");
                helper.assertEquals(6_000_000L, line.blueInput().getNetwork().getAmountQ() + line.blueOutput().getNetwork().getAmountQ()
                    + (bluePrimary ? primary : partner), "Blue mass survives independent discharge");
            }).thenSucceed();
    }

    @GameTest(template = "radiator", timeoutTicks = 1100)
    public static void exchangerSwitchesControlWithoutSwappingHeels(GameTestHelper helper) {
        var line = coloredRig(helper);
        var rig = line.red();
        boolean[] enabled = { false };
        long[] delivered = { 0 };
        helper.onEachTick("feed the energy hatch", () -> {
            if (!enabled[0]) return;
            long before = rig.supply().getStoredEU();
            rig.supply().injectEnergyUnits(ForgeDirection.NORTH, 128, 1);
            delivered[0] += rig.supply().getStoredEU() - before;
        });
        helper.startSequence().thenIdle(20).thenExecute(() -> {
            rig.charge(helper, 310);
            rig.pump().setHeatExchangerMode(true);
            rig.pump().setConfiguringHotStream(false);
            rig.pump().setOperatingMode(HeatPumpMode.TARGET_ENERGY);
            rig.pump().setTargetEnergy(128);
            line.blueInput().getNetwork().replaceCanonicalState(IFNCanonicalState.fromRaw("water", 6_000_000, 6_254_060_925_000L));
            enabled[0] = true;
        }).thenWaitUntil(() -> helper.assertTrue(rig.pump().getTotalEnergyCost() == 128, "First blue-controlled tick"))
            .thenExecute(() -> rig.pump().setConfiguringHotStream(true))
            .thenWaitUntil(() -> helper.assertTrue(rig.output().getNetwork().getAmountQ() > 0 && line.blueOutput().getNetwork().getAmountQ() > 0,
                "Both outlets discharged the first batch"))
            .thenExecute(() -> {
                helper.assertEquals(20L * 128, delivered[0] - rig.supply().getStoredEU(), "First cycle spent the configured work");
                enabled[0] = false;
            }).thenWaitUntil(() -> {
                var saved = rig.inventory();
                helper.assertTrue(saved.getBoolean("pumpBatchActive") && !saved.getBoolean("pumpBluePrimary"), "Next batch is controlled by red");
            }).thenExecute(() -> {
                var saved = rig.inventory();
                helper.assertEquals(10_000_000L, rig.input().getNetwork().getAmountQ() + rig.output().getNetwork().getAmountQ()
                    + saved.getLong("pumpMicroKg"), "Red control reused the red heel");
                helper.assertEquals(6_000_000L, line.blueInput().getNetwork().getAmountQ() + line.blueOutput().getNetwork().getAmountQ()
                    + saved.getLong("pumpPartnerMicroKg"), "Blue control left its mass on blue");
            }).thenSucceed();
    }

    private static ClosedFluidChamber heldChamber(NBTTagCompound saved, String prefix) {
        return ClosedFluidChamber.of(PureFluid.water(), BigDecimal.valueOf(saved.getLong(prefix + "MicroKg"), 6),
            1000, BigDecimal.valueOf(saved.getLong(prefix + "MicroEu"), 6));
    }

    private static double heldVaporFraction(Rig rig) {
        var saved = rig.inventory();
        return ClosedFluidChamber.of(PureFluid.water(),
            BigDecimal.valueOf(saved.getLong("pumpMicroKg"), 6), 1000,
            BigDecimal.valueOf(saved.getLong("pumpMicroEu"), 6)).state().vaporMassFraction();
    }

    @GameTest(template = "radiator", timeoutTicks = 1800)
    public static void hotBatchesMixInOccupiedOutlet(GameTestHelper helper) {
        var rig = place(helper);
        var temperatures = new double[2];
        var amounts = new long[2];
        boolean[] enabled = { false };
        helper.onEachTick("power the heat pump", () -> {
            if (enabled[0]) rig.supply().injectEnergyUnits(ForgeDirection.NORTH, 128, 2);
        });
        helper.startSequence().thenIdle(20).thenExecute(() -> {
            rig.charge(helper, 400);
            var outlet = rig.output().getNetwork();
            outlet.replaceCanonicalState(IFNCanonicalState.fromRaw("water", 5_000_000, -1_000_000_000_000L));
            temperatures[0] = outlet.getTemperature();
            amounts[0] = outlet.getAmountQ();
            helper.assertTrue(temperatures[0] > 273 && temperatures[0] < 350, "Outlet starts with cooler fluid: " + temperatures[0]);
            enabled[0] = true;
        }).thenWaitUntil(() -> helper.assertTrue(rig.output().getNetwork().getAmountQ() > amounts[0], "First hot discharge"))
            .thenExecute(() -> {
                var outlet = rig.output().getNetwork();
                amounts[1] = outlet.getAmountQ();
                temperatures[1] = outlet.getTemperature();
                helper.assertTrue(Math.abs(rig.pump().getCurrentOutputTemperature() - 400) < 0.001, "Batch reached its setpoint");
                helper.assertTrue(temperatures[1] > temperatures[0] && temperatures[1] < 399,
                    "First batch warmed the occupied outlet without imposing its setpoint: " + temperatures[1]);
            }).thenWaitUntil(() -> helper.assertTrue(rig.output().getNetwork().getAmountQ() > amounts[1], "Second hot discharge"))
            .thenExecute(() -> {
                var outlet = rig.output().getNetwork();
                helper.assertTrue(outlet.getTemperature() > temperatures[1] && outlet.getTemperature() < 400,
                    "Another discharge brings this outlet closer to the setpoint: " + outlet.getTemperature());
                helper.assertEquals(15_000_000L, rig.input().getNetwork().getAmountQ() + rig.inventory().getLong("pumpMicroKg")
                    + outlet.getAmountQ(), "Discharge adds the batch without replacing the outlet contents");
            }).thenSucceed();
    }

    @GameTest(template = "radiator", timeoutTicks = 1200)
    public static void requestedCopUsesWorldReservoir(GameTestHelper helper) {
        var rig = place(helper);
        boolean[] running = { false };
        helper.onEachTick("supply the COP-controlled stage", () -> {
            if (running[0]) rig.supply().injectEnergyUnits(ForgeDirection.NORTH, 128, 2);
        });
        helper.startSequence().thenIdle(20).thenExecute(() -> {
            rig.charge(helper, 310);
            rig.pump().setOperatingMode(HeatPumpMode.TARGET_COP);
            rig.pump().setTargetCOP(4);
            rig.pump().setTargetHeating(true);
            running[0] = true;
        }).thenWaitUntil(() -> helper.assertTrue(rig.input().getNetwork().getAmountQ() < 10_000_000,
            "The COP controller admitted a charge"))
            .thenWaitUntil(() -> helper.assertTrue(rig.output().getNetwork().getAmountQ() > 0, "COP-controlled charge discharged"))
            .thenExecute(() -> {
                helper.assertTrue(Math.abs(rig.pump().getCurrentOutputTemperature() - 400) < 0.001,
                    "COP 4 relative to 300 K sets a 400 K heating target");
                helper.assertTrue(Math.abs(rig.pump().getCOP() - 4) < 0.001 && rig.pump().getEffectiveCOP() < 4,
                    "The terminal distinguishes ideal COP from actual paid performance");
            }).thenSucceed();
    }

    @GameTest(template = "radiator", timeoutTicks = 1600)
    public static void poweredCooling(GameTestHelper helper) {
        var rig = place(helper);
        var inputHeat = new AmbientTests.AmbientMeter(helper, rig.input()::getNetwork);
        var outputHeat = new AmbientTests.AmbientMeter(helper, rig.output()::getNetwork);
        long[] baseline = new long[1];
        long[] supplied = new long[1];
        boolean[] enabled = { false };
        helper.onEachTick("supply the cooling stage", () -> {
            if (!enabled[0]) return;
            long before = rig.supply().getStoredEU();
            rig.supply().injectEnergyUnits(ForgeDirection.NORTH, 128, 2);
            supplied[0] += rig.supply().getStoredEU() - before;
        });
        helper.startSequence().thenIdle(20).thenExecute(() -> {
            rig.charge(helper, 280);
            baseline[0] = inputHeat.withoutAmbient(rig.input().getNetwork());
            enabled[0] = true;
        }).thenWaitUntil(() -> helper.assertTrue(rig.output().getNetwork().getAmountQ() > 0, "Cold batch reached the outlet"))
            .thenExecute(() -> {
                enabled[0] = false;
                double t = rig.pump().getCurrentOutputTemperature();
                helper.assertTrue(Math.abs(t - 280) < 0.001, "Working chamber reached 280 K: " + t);
                var saved = rig.inventory();
                long removed = baseline[0] - inputHeat.withoutAmbient(rig.input().getNetwork())
                    - outputHeat.withoutAmbient(rig.output().getNetwork()) - saved.getLong("pumpMicroEu");
                long work = supplied[0] - rig.supply().getStoredEU();
                helper.assertTrue(work > 0 && removed > 0 && removed <= work * 14_000_000L,
                    "Cooling a batch to 280 K against a 300 K reservoir cannot exceed a COP of 14");
                helper.assertEquals(10_000_000L, saved.getLong("pumpMicroKg") + rig.input().getNetwork().getAmountQ()
                    + rig.output().getNetwork().getAmountQ(), "Cold discharge conserves mass");
            }).thenSucceed();
    }

    @GameTest(template = "radiator", timeoutTicks = 1600)
    public static void interruptedHeating(GameTestHelper helper) {
        var rig = place(helper);
        long[] before = new long[2];
        long[] held = new long[2];
        boolean[] powered = { false };
        helper.onEachTick("reconnect MV supply", () -> {
            if (powered[0]) rig.supply().injectEnergyUnits(ForgeDirection.NORTH, 128, 2);
        });
        helper.startSequence().thenIdle(20).thenExecute(() -> rig.charge(helper, 400))
            .thenWaitUntil(() -> helper.assertTrue(rig.input().getNetwork().getAmountQ() < 10_000_000, "A physical charge entered the pump"))
            .thenExecute(() -> {
                var saved = rig.inventory();
                before[0] = saved.getLong("pumpMicroEu");
                before[1] = saved.getLong("pumpMicroKg");
                helper.assertTrue(before[1] > 0, "Charge retained inside the pump");
                helper.assertEquals(1L, rig.supply().injectEnergyUnits(ForgeDirection.NORTH, 128, 1), "One MV packet supplied");
            }).thenWaitUntil(() -> {
                var saved = rig.inventory();
                held[0] = saved.getLong("pumpMicroEu");
                held[1] = saved.getLong("pumpMicroKg");
                helper.assertTrue(held[0] > before[0] && rig.supply().getStoredEU() == 0, "Paid heat is already in the fluid");
                helper.assertTrue(held[0] - before[0] <= 512_000_000L, "At 400 K against 300 K, COP is at most four");
            }).thenIdle(60).thenExecute(() -> {
                var saved = rig.inventory();
                helper.assertEquals(held[0], saved.getLong("pumpMicroEu"), "No heating without power");
                helper.assertEquals(before[1], saved.getLong("pumpMicroKg"), "Power loss keeps the batch");
                helper.assertEquals(0L, rig.output().getNetwork().getAmountQ(), "No unfinished output");
                powered[0] = true;
            }).thenWaitUntil(() -> helper.assertTrue(rig.output().getNetwork().getAmountQ() > 0, "Output resumes after reconnecting power"))
            .thenExecute(() -> {
                helper.assertEquals(10_000_000L, rig.input().getNetwork().getAmountQ() + rig.output().getNetwork().getAmountQ()
                    + rig.inventory().getLong("pumpMicroKg"), "All fluid accounted for after discharge");
            }).thenSucceed();
    }
}
