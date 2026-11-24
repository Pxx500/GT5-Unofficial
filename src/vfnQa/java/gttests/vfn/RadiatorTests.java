package gttests.vfn;

import java.util.ArrayList;

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
import gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidOutputHatch;
import gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidPipe;
import gregtech.api.metatileentity.implementations.integratedfluid.state.IFNCanonicalState;
import gregtech.common.tileentities.machines.multi.MTERadiator;
import gregtech.common.tileentities.machines.multi.radiator.RadiatorLoopAnalyzer;

@GameTestHolder(value = Mods.ModIDs.GREG_TECH, templatePrefix = "vfn")
public class RadiatorTests {

    record Rig(MTERadiator machine, MTEIntegratedFluidInputHatch inlet, MTEIntegratedFluidOutputHatch outlet) {
        void charge(GameTestHelper helper) {
            var controller = machine.getBaseMetaTileEntity();
            helper.assertTrue(machine.checkStructure(true, controller), "3x3x3 steel radiator formed");
            helper.assertEquals(2, machine.getLoopSegmentCount(), "Both external loop pipes detected");
            helper.assertTrue(inlet.getNetwork() != null && outlet.getNetwork() != null && inlet.getNetwork() != outlet.getNetwork(),
                "Separate input and output circuits");
            // Wet steam can enter an evacuated loop without exceeding the calibrated steam temperature
            inlet.getNetwork().replaceCanonicalState(IFNCanonicalState.fromRaw("water", 10_000_000, 10_423_434_875_000L));
            machine.fixAllIssues();
            controller.enableWorking();
        }

        NBTTagCompound inventory() {
            var saved = new NBTTagCompound();
            machine.saveNBTData(saved);
            return saved;
        }

        long heldMass() {
            return inventory().getLong("loopMicroKg");
        }
    }

    static Rig place(GameTestHelper helper) {
        var p = helper.absolute("controller");
        return placeAt(helper.getWorld(), p.x(), p.y(), p.z());
    }

    private static Rig placeAt(World world, int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = 0; dz <= 2; dz++) {
                    world.setBlock(x + dx, y + dy, z + dz, GregTechAPI.sBlockCasings2, 0, 3);
                }
            }
        }
        for (int dz = 1; dz <= 2; dz++) {
            world.setBlock(x, y + 1, z + dz, GregTechAPI.sBlockCasings11, RadiatorLoopAnalyzer.PORT_META, 3);
            world.setBlock(x, y + 2, z + dz, GregTechAPI.sBlockCasings11, RadiatorLoopAnalyzer.LOOP_PIPE_META, 3);
        }
        var controller = InjectorTests.placeAt(world, x, y, z, (short) 32031, ForgeDirection.NORTH);
        var machine = (MTERadiator) controller.getMetaTileEntity();
        var inlet = (MTEIntegratedFluidInputHatch) InjectorTests.placeAt(world, x - 1, y, z + 1, (short) 5771, ForgeDirection.WEST).getMetaTileEntity();
        var outlet = (MTEIntegratedFluidOutputHatch) InjectorTests.placeAt(world, x + 1, y, z + 1, (short) 5772, ForgeDirection.EAST).getMetaTileEntity();
        InjectorTests.placeAt(world, x - 1, y, z, (short) 41, ForgeDirection.NORTH);
        InjectorTests.placeAt(world, x + 1, y, z, (short) 15497, ForgeDirection.NORTH);
        return new Rig(machine, inlet, outlet);
    }

    record WaterReturn(Rig rig, IGregTechTileEntity extractor, IGregTechTileEntity tank, MTEIntegratedFluidPipe receiver,
        AmbientTests.AmbientMeter ambient, AmbientTests.AmbientMeter returnedAmbient) {
        long charge(GameTestHelper helper) {
            var controller = rig.machine().getBaseMetaTileEntity();
            helper.assertTrue(rig.machine().checkStructure(true, controller), "Connected radiator forms");
            var network = rig.inlet().getNetwork();
            helper.assertTrue(network != null && network == rig.outlet().getNetwork(), "Both ports share a chamber");
            // Resume the final part of passive warming. The hydrofores have head for one whole GT unit
            var geometry = network.getTopologySnapshot().chamberVolume().orElseThrow();
            double pressure = 110000 + 1.5 / geometry.complianceLitersPerPa();
            double liters = geometry.litersAtPressure(pressure);
            long mass = Math.round(1e6 * liters * (2.2e9 + pressure) / (1.043 * 2.2001e9));
            double h = 4180 * (299.9989997 - 373.15) + 0.001 * 1.043 * 2.2001e9 * Math.log1p((pressure - 100000) / 2.2001e9);
            long energy = Math.round(mass * h + 1e6 * (geometry.elasticEnergyEu(pressure) - 0.001 * pressure * liters));
            network.replaceCanonicalState(IFNCanonicalState.fromRaw("water", mass, energy));
            helper.assertTrue(extractor.drain(ForgeDirection.SOUTH, 1, false) == null, "Before warming, GT cannot take the water");
            rig.machine().fixAllIssues();
            controller.enableWorking();
            return ambient.withoutAmbient(network);
        }

        void requireWarmWater(GameTestHelper helper, long baseline) {
            var network = rig.inlet().getNetwork();
            helper.assertTrue(ambient.withoutAmbient(network) > baseline, "Radiator supplies heat beyond ordinary pipe contact");
            helper.assertTrue(extractor.drain(ForgeDirection.SOUTH, 1, false) != null, "After warming, GT can take the water");
        }

        void pumpIntoTank() {
            rig.machine().stopMachine(gregtech.api.util.shutdown.ShutDownReasonRegistry.NONE);
            attachPump(extractor);
        }

        void requireTank(GameTestHelper helper) {
            var stored = tank.drain(ForgeDirection.UNKNOWN, Integer.MAX_VALUE, false);
            helper.assertTrue(stored != null && stored.getFluid() == net.minecraftforge.fluids.FluidRegistry.WATER && stored.amount == 1,
                "The extractor pump delivered one ordinary water unit to the Super Tank");
        }

        void pumpIntoNetwork() {
            attachPump(tank);
        }

        private static void attachPump(IGregTechTileEntity tile) {
            var item = gregtech.api.enums.ItemList.Electric_Pump_LV.get(1);
            tile.attachCover(gregtech.api.covers.CoverRegistry.buildCover(item, ForgeDirection.SOUTH, tile));
        }
    }

    static WaterReturn placeWaterReturn(GameTestHelper helper) {
        var rig = place(helper);
        var w = helper.getWorld();
        var p = helper.absolute("controller");
        int x = p.x(), y = p.y(), z = p.z();
        var pipes = new ArrayList<MTEIntegratedFluidPipe>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = 1; dz <= 3; dz++) {
                if (Math.abs(dx) < 2 && dz < 3) continue;
                pipes.add((MTEIntegratedFluidPipe)
                    InjectorTests.placeAt(w, x + dx, y, z + dz, (short) 5770, ForgeDirection.NORTH).getMetaTileEntity());
            }
        }
        var extractor = InjectorTests.placeAt(w, x, y, z + 4, (short) 32032, ForgeDirection.NORTH);
        var tank = InjectorTests.placeAt(w, x, y, z + 5, (short) 130, ForgeDirection.WEST);
        InjectorTests.placeAt(w, x, y, z + 6, (short) 5773, ForgeDirection.SOUTH);
        var receiver = (MTEIntegratedFluidPipe)
            InjectorTests.placeAt(w, x, y, z + 7, (short) 5770, ForgeDirection.NORTH).getMetaTileEntity();
        receiver.connect(ForgeDirection.NORTH);
        for (var pipe : pipes) {
            for (var side : ForgeDirection.VALID_DIRECTIONS) pipe.connect(side);
        }
        var ambient = new AmbientTests.AmbientMeter(helper, rig.inlet()::getNetwork);
        return new WaterReturn(rig, extractor, tank, receiver, ambient, new AmbientTests.AmbientMeter(helper, receiver::getNetwork));
    }

    record HeatedLine(HeatPumpTests.Rig heater, Rig radiator, MTEIntegratedFluidPipe bridge) {
        long totalMass() {
            var hot = heater.output().getNetwork();
            var inlet = radiator.inlet().getNetwork();
            return heater.input().getNetwork().getAmountQ() + heater.inventory().getLong("pumpMicroKg")
                + hot.getAmountQ() + (hot == inlet ? 0 : inlet.getAmountQ())
                + radiator.heldMass() + radiator.outlet().getNetwork().getAmountQ();
        }

        void start(GameTestHelper helper, int packetLiters, int targetK) {
            var inlet = radiator.inlet().getNetwork();
            helper.assertTrue(inlet != null && inlet == heater.output().getNetwork() && inlet == bridge.getNetwork()
                && radiator.outlet().getNetwork() != null && inlet != radiator.outlet().getNetwork(), "Pump feeds the radiator through one pipe");
            helper.assertEquals(0L, inlet.getAmountQ() + radiator.heldMass() + radiator.outlet().getNetwork().getAmountQ(), "Cold side starts empty");
            var tile = radiator.machine().getBaseMetaTileEntity();
            helper.assertTrue(radiator.machine().checkStructure(true, tile), "Radiator formed with its physical loop");
            radiator.machine().fixAllIssues();
            tile.disableWorking();
            radiator.machine().setConstantOperationTicks(80);
            heater.charge(helper, targetK);
            heater.pump().setFluidAmountPerOperation(packetLiters);
        }

        void splitAfterDelivery(GameTestHelper helper, int targetK) {
            helper.assertTrue(heater.output().getNetwork().getAmountQ() > 0, "Paid charge reached the radiator circuit");
            helper.assertTrue(Math.abs(heater.pump().getCurrentOutputTemperature() - targetK) < 0.01, "Heater reached its setpoint");
            helper.assertEquals(10_000_000L, totalMass(), "All water accounted for before disconnecting");
            heater.pump().getBaseMetaTileEntity().disableWorking();
            bridge.disconnect(ForgeDirection.EAST);
        }
    }

    static HeatedLine placeHeatedLine(GameTestHelper helper) {
        var p = helper.absolute("controller");
        var world = helper.getWorld();
        var heater = HeatPumpTests.placeAt(world, p.x() - 2, p.y(), p.z(), (short) 48);
        var radiator = placeAt(world, p.x() + 2, p.y(), p.z());
        var bridge = (MTEIntegratedFluidPipe) InjectorTests.placeAt(world, p.x(), p.y(), p.z() + 1, (short) 5770,
            ForgeDirection.NORTH).getMetaTileEntity();
        bridge.connect(ForgeDirection.WEST);
        bridge.connect(ForgeDirection.EAST);
        return new HeatedLine(heater, radiator, bridge);
    }

    @GameTest(template = "water_line", timeoutTicks = 1500)
    public static void deliversPaidPumpDischargeAcrossPipeSplit(GameTestHelper helper) {
        runIsolatedLoop(helper, 1000, 350, true);
    }

    @GameTest(template = "water_line", timeoutTicks = 1200)
    public static void smallCooledChargeCannotOvercomeLoopLoss(GameTestHelper helper) {
        runIsolatedLoop(helper, 100, 400, false);
    }

    private static void runIsolatedLoop(GameTestHelper helper, int packetLiters, int targetK, boolean delivers) {
        var line = placeHeatedLine(helper);
        var heater = line.heater();
        var radiator = line.radiator();
        long[] paidIn = { 0 }, afterSplit = { 0 }, hotSide = { 0 }, startEnergy = { 0 };
        boolean[] power = { true };
        helper.onEachTick("supply UV to the heater only", () -> {
            if (power[0]) paidIn[0] += WaterLineTests.feedUV(heater.supply());
        });
        var cycle = helper.startSequence().thenIdle(20).thenExecute(() -> line.start(helper, packetLiters, targetK))
            .thenWaitUntil(() -> helper.assertTrue(heater.output().getNetwork().getAmountQ() > 0, "Waiting for hot discharge"))
            .thenExecute(() -> {
                power[0] = false;
                helper.assertTrue(paidIn[0] > heater.supply().getStoredEU(), "Real work was paid");
                line.splitAfterDelivery(helper, targetK);
            }).thenWaitUntil(() -> helper.assertTrue(heater.output().getNetwork() != radiator.inlet().getNetwork(), "Pipe isolated the two sides"))
            .thenExecute(() -> {
                afterSplit[0] = line.totalMass();
                helper.assertTrue(afterSplit[0] >= 9_999_999 && afterSplit[0] <= 10_000_000, "At most one microkg lost in a two-way split");
                hotSide[0] = heater.output().getNetwork().getAmountQ();
                helper.assertTrue(hotSide[0] > 0 && radiator.inlet().getNetwork().getAmountQ() > 0, "Both sides retained their shares");
                radiator.machine().getBaseMetaTileEntity().enableWorking();
            }).thenWaitUntil(() -> helper.assertTrue(radiator.heldMass() > 0, "Waiting for intake: kg=" + radiator.inlet().getNetwork().getAmountQ() / 1e6
                + ", pressure Pa=" + radiator.inlet().getNetwork().getPressurePa() + ", K=" + radiator.inlet().getNetwork().getTemperature()
                + ", status=" + radiator.inlet().getNetwork().getNetworkStatus() + ", work=" + radiator.machine().getBaseMetaTileEntity().isAllowedToWork()))
            .thenExecute(() -> startEnergy[0] = radiator.inventory().getLong("loopMicroEu"));
        if (!delivers) {
            cycle.thenWaitUntil(() -> helper.assertTrue(radiator.heldMass() > 0 && radiator.inventory().getLong("loopMicroEu") < startEnergy[0],
                "Ambient contact removed heat from the physical working charge"));
        }
        cycle.thenWaitUntil(() -> {
                if (delivers) {
                    helper.assertTrue(radiator.outlet().getNetwork().getAmountQ() > 0, "Expected discharge: held microkg=" + radiator.heldMass()
                        + ", loop K=" + radiator.machine().getOutputTemperature() + ", output ready=" + radiator.inventory().getBoolean("loopOutputReady"));
                } else {
                    var saved = radiator.inventory();
                    helper.assertTrue(saved.getBoolean("loopOutputReady"), "Small charge finished cooling");
                    var charge = gregtech.api.metatileentity.implementations.integratedfluid.thermodynamics.ClosedFluidChamber.of(
                        gregtech.api.metatileentity.implementations.integratedfluid.thermodynamics.PureFluid.water(),
                        java.math.BigDecimal.valueOf(radiator.heldMass(), 6), 1000, java.math.BigDecimal.valueOf(saved.getLong("loopMicroEu"), 6));
                    double drop = radiator.machine().getLoopPressureDropBar() * 100_000;
                    helper.assertTrue(charge.pressurePa() <= drop, "Loop pressure Pa=" + charge.pressurePa() + ", drop Pa=" + drop);
                    helper.assertEquals(0L, radiator.outlet().getNetwork().getAmountQ(), "An evacuated outlet cannot cancel the loop loss");
                    helper.assertEquals(gregtech.api.recipe.check.SimpleCheckRecipeResult.ofFailure("vfn_radiator_head"),
                        radiator.machine().getCheckRecipeResult(), "Display the insufficient loop head, Pa=" + charge.pressurePa() + ", drop=" + drop);
                }
            })
            .thenExecute(() -> {
                helper.assertEquals(hotSide[0], heater.output().getNetwork().getAmountQ(), "Cooling did not consume the isolated hot side");
                helper.assertEquals(afterSplit[0], line.totalMass(), "No duplicated water at either machine");
                line.bridge().connect(ForgeDirection.EAST);
            }).thenWaitUntil(() -> helper.assertTrue(heater.output().getNetwork() == radiator.inlet().getNetwork(), "Hatches reconnected"))
            .thenExecute(() -> {
                helper.assertEquals(afterSplit[0], line.totalMass(), "Reconnection preserved remaining mass");
                helper.recordDiagnostic("Paid EU=" + (paidIn[0] - heater.supply().getStoredEU()) + ", retained microkg=" + afterSplit[0]
                    + ", receiver microkg=" + radiator.outlet().getNetwork().getAmountQ());
            }).thenSucceed();
    }

    @GameTest(template = "radiator", timeoutTicks = 1400)
    public static void ambientWaterReturnsThroughGtTank(GameTestHelper helper) {
        var line = placeWaterReturn(helper);
        long[] baseline = new long[1];
        long[] before = new long[2];
        helper.startSequence().thenIdle(20).thenExecute(() -> baseline[0] = line.charge(helper))
            .thenWaitUntil(() -> line.requireWarmWater(helper, baseline[0]))
            .thenExecute(() -> {
                var source = line.rig().inlet().getNetwork();
                before[0] = line.ambient().withoutAmbient(source);
                before[1] = source.getAmountQ();
                line.pumpIntoTank();
            }).thenWaitUntil(() -> line.requireTank(helper)).thenExecute(line::pumpIntoNetwork)
            .thenWaitUntil(() -> helper.assertEquals(1_000_000L, line.receiver().getNetwork().getAmountQ(), "Second pump returns one kilogram"))
            .thenExecute(() -> {
                var source = line.rig().inlet().getNetwork();
                var returned = line.receiver().getNetwork();
                helper.assertTrue(line.tank().drain(ForgeDirection.UNKNOWN, 1, false) == null, "Super Tank is empty");
                helper.assertEquals(before[1], source.getAmountQ() + returned.getAmountQ(), "The round trip conserves mass");
                helper.assertTrue(line.ambient().withoutAmbient(source) + line.returnedAmbient().withoutAmbient(returned) <= before[0],
                    "No heat credit on export and reimport");
                helper.assertEquals(0L, line.rig().heldMass(), "The shared chamber was not counted again as a batch");
            }).thenSucceed();
    }

    @GameTest(template = "radiator", timeoutTicks = 1800)
    public static void targetBatchWaitsForBackpressure(GameTestHelper helper) {
        var rig = place(helper);
        long[] waiting = new long[1];
        helper.startSequence().thenIdle(20).thenExecute(() -> {
            rig.charge(helper);
            rig.machine().setMachineMode(1);
            rig.machine().setTargetTemperatureSetting(350);
            rig.outlet().getNetwork().replaceCanonicalState(IFNCanonicalState.fromRaw("water", 10_000_000, 22_037_935_000_000L));
        }).thenWaitUntil(() -> {
            var saved = new net.minecraft.nbt.NBTTagCompound();
            rig.machine().saveNBTData(saved);
            helper.assertTrue(saved.getBoolean("loopOutputReady"), "Target batch finished inside the loop");
            helper.assertTrue(Math.abs(rig.machine().getOutputTemperature() - 350) < 0.01, "Actual loop temperature at target");
            waiting[0] = rig.heldMass();
        }).thenIdle(60).thenExecute(() -> {
            helper.assertEquals(waiting[0], rig.heldMass(), "Backpressure retains the batch");
            helper.assertEquals(10_000_000L, rig.outlet().getNetwork().getAmountQ(), "No output against backpressure");
            rig.inlet().getNetwork().freeze("Isolate the inlet after conditioning");
            rig.outlet().getNetwork().replaceCanonicalState(IFNCanonicalState.empty());
        }).thenWaitUntil(() -> helper.assertTrue(rig.outlet().getNetwork().getAmountQ() > 0, "Outlet resumes after pressure is released"))
            .thenExecute(() -> helper.assertEquals(10_000_000L, rig.inlet().getNetwork().getAmountQ()
                + rig.heldMass() + rig.outlet().getNetwork().getAmountQ(), "Held material counted once after restart of flow"))
            .thenSucceed();
    }

    @GameTest(template = "radiator", timeoutTicks = 3000)
    public static void timedLoopSurvivesChunkReload(GameTestHelper helper) {
        var site = RemoteMachineSite.reserve(helper, 160, 2);
        var original = placeAt(site.world(), site.x(), site.y(), site.z());
        site.assignOwner();
        Rig[] reloaded = new Rig[1];
        NBTTagCompound[] unloaded = new NBTTagCompound[1];
        helper.startSequence().thenIdle(20).thenExecute(() -> {
            original.machine().setConstantOperationTicks(1000);
            original.charge(helper);
        }).thenWaitUntil(() -> {
            var loop = original.inventory();
            int ticks = loop.getInteger("loopTicks");
            helper.assertTrue(original.heldMass() > 0 && ticks > 0 && ticks < 995, "Cooling in progress");
            helper.assertEquals(0L, original.outlet().getNetwork().getAmountQ(), "No output before unload");
        }).thenExecute(site::requestUnload)
            .thenWaitUntil(() -> helper.assertTrue(site.absent(), "Radiator chunk unloaded"))
            .thenExecute(() -> {
                unloaded[0] = original.inventory();
                helper.assertTrue(unloaded[0].getInteger("loopTicks") > 0 && unloaded[0].getLong("loopMicroKg") > 0,
                    "The saved radiator still holds an unfinished batch");
            })
            .thenIdle(100).thenExecute(() -> {
                site.reloadForObservation(helper);
                var machine = (MTERadiator) site.tile(0, 0, 0).getMetaTileEntity();
                var inlet = (MTEIntegratedFluidInputHatch) site.tile(-1, 0, 1).getMetaTileEntity();
                var outlet = (MTEIntegratedFluidOutputHatch) site.tile(1, 0, 1).getMetaTileEntity();
                helper.assertTrue(machine != original.machine(), "A new radiator read the saved chunk");
                reloaded[0] = new Rig(machine, inlet, outlet);
                var restored = reloaded[0].inventory();
                helper.assertEquals(unloaded[0].getLong("loopMicroKg"), restored.getLong("loopMicroKg"), "Held mass survives unload");
                helper.assertEquals(unloaded[0].getLong("loopMicroEu"), restored.getLong("loopMicroEu"), "Held energy survives unload");
                helper.assertEquals(unloaded[0].getInteger("loopTicks"), restored.getInteger("loopTicks"), "Offline time did not finish the batch");
            }).thenWaitUntil(() -> {
                var rig = reloaded[0];
                var current = rig.inventory();
                helper.assertTrue(current.getInteger("loopTicks") < unloaded[0].getInteger("loopTicks")
                    && current.getInteger("loopTicks") > 0 && rig.outlet().getNetwork() != null, "Restored batch resumed, ticks=" + current.getInteger("loopTicks")
                        + ", saved=" + unloaded[0].getInteger("loopTicks") + ", mass=" + rig.heldMass());
                helper.assertTrue(current.getLong("loopMicroEu") < unloaded[0].getLong("loopMicroEu"), "Actual cooling resumed");
                helper.assertEquals(0L, rig.outlet().getNetwork().getAmountQ(), "No early release");
            }).thenWaitUntil(() -> {
                var outlet = reloaded[0].outlet().getNetwork();
                helper.assertTrue(outlet != null && outlet.getAmountQ() > 0, "Restored batch discharged, remaining ticks="
                    + reloaded[0].inventory().getInteger("loopTicks") + ", held mass=" + reloaded[0].heldMass());
            })
            .thenIdle(120).thenExecute(() -> {
                var rig = reloaded[0];
                helper.assertEquals(10_000_000L, rig.inlet().getNetwork().getAmountQ() + rig.heldMass()
                    + rig.outlet().getNetwork().getAmountQ(), "No duplicate output after subsequent operations");
            }).thenSucceed();
    }

    @GameTest(template = "radiator", timeoutTicks = 600)
    public static void coolsSteamThroughExternalLoop(GameTestHelper helper) {
        var rig = place(helper);
        helper.startSequence().thenIdle(20).thenExecute(() -> rig.charge(helper))
            .thenWaitUntil(() -> helper.assertTrue(rig.heldMass() > 0, "Working fluid enters the radiator"))
            .thenExecute(() -> helper.assertEquals(0L, rig.outlet().getNetwork().getAmountQ(), "No instantaneous output"))
            .thenWaitUntil(() -> helper.assertTrue(rig.outlet().getNetwork().getAmountQ() > 0, "Processed steam reaches the output hatch"))
            .thenExecute(() -> {
                helper.assertEquals(10_000_000L, rig.inlet().getNetwork().getAmountQ() + rig.outlet().getNetwork().getAmountQ()
                    + rig.heldMass(), "Input, radiator and output conserve mass exactly");
                helper.assertTrue(rig.machine().getOutputTemperature() < rig.inlet().getNetwork().getTemperature(), "Loop cooled below feed temperature");
            }).thenSucceed();
    }
}
