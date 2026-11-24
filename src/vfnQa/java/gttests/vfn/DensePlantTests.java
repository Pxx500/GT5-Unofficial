package gttests.vfn;

import static net.minecraftforge.common.util.ForgeDirection.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.common.util.FakePlayerFactory;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;

import gregtech.api.covers.CoverRegistry;
import gregtech.api.enums.ItemList;
import gregtech.api.enums.MetaTileEntityIDs;
import gregtech.api.enums.Mods;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.items.MetaGeneratedTool;
import gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidLiquidPump;
import gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidPipe;
import gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidSteamTurbine;
import gregtech.api.metatileentity.implementations.integratedfluid.state.IFNCanonicalState;
import gregtech.common.covers.CoverEUMeter;
import gregtech.common.covers.CoverIFNDetectorBase;

@GameTestHolder(value = Mods.ModIDs.GREG_TECH, templatePrefix = "vfn")
public class DensePlantTests {
    enum Charge {
        LOWER(28500, 10_530_273, 29_251_517_120_246L, 15_184_954, 28_934_544_163_364L),
        HIGHER(33150, 12_894_426, 35_530_474_686_141L, 21_301_564, 37_551_663_742_696L);

        final int targetCbar;
        final long hotMass, hotEnergy, coldMass, coldEnergy;

        Charge(int targetCbar, long hotMass, long hotEnergy, long coldMass, long coldEnergy) {
            this.targetCbar = targetCbar;
            this.hotMass = hotMass;
            this.hotEnergy = hotEnergy;
            this.coldMass = coldMass;
            this.coldEnergy = coldEnergy;
        }
    }

    static final class Plant {
        final PressureLineTests.DenseLoop loop;
        final MTEIntegratedFluidPipe hot, cold, sink;
        final IGregTechTileEntity pump, turbine, boiler, load, distributor;
        final IGregTechTileEntity[] loadTransformers;
        final AmbientTests.AmbientMeter hotMeter, coldMeter, sinkMeter;
        final Charge charge;
        final long startupEu;
        boolean running, started;
        long boilerBudget = 25_600;
        long suppliedHeat, initialWater, initialSink, initialPump, shaft;
        long hotSections, coldSections, wear, lastPumpEnergy, pumpStrokes, consumed;

        Plant(GameTestHelper h, Charge charge, boolean governed) {
            this.charge = charge;
            startupEu = governed ? 204800 : 122880;
            var site = RemoteMachineSite.at(h, "controller");
            loop = PressureLineTests.denseLoop(h, 1, governed);
            turbine = loop.turbine().controller();
            pump = loop.pump();
            hot = (MTEIntegratedFluidPipe)site.tile(-2, 0, 0).getMetaTileEntity();
            cold = (MTEIntegratedFluidPipe)site.tile(2, 0, 0).getMetaTileEntity();
            boiler = site.place(-3, 0, 0, 5776, WEST);
            site.place(3, 0, 0, 5775, EAST);
            sink = (MTEIntegratedFluidPipe)site.place(4, 0, 0, MTEIntegratedFluidPipe.REINFORCED_ID, WEST).getMetaTileEntity();
            h.assertTrue(hot.connect(WEST) > 0 && cold.connect(EAST) > 0 && sink.connect(WEST) > 0, "Heating and separate cooling circuit");
            int busHeight = governed ? 1 : 0;
            distributor = site.place(-1, busHeight, -1, MetaTileEntityIDs.transformer_LuV_IV.ID, SOUTH);
            loadTransformers = new IGregTechTileEntity[]{
                site.place(-1, 3, governed ? -2 : -1, MetaTileEntityIDs.transformer_HV_MV.ID, DOWN),
                site.place(-1, 2, governed ? -2 : -1, MetaTileEntityIDs.transformer_EV_HV.ID, governed ? SOUTH : DOWN),
                site.place(-1, busHeight+1, -1, MetaTileEntityIDs.transformer_IV_EV.ID, DOWN)};
            load = site.place(0, 3, governed ? -2 : -1, MetaTileEntityIDs.ENERGY_HATCH_MV.ID, WEST);
            TurbineTests.installDiluteSteamRotor(turbine);
            var settings = (MTEIntegratedFluidLiquidPump)pump.getMetaTileEntity();
            settings.setMode(2);
            settings.setTarget(10);
            settings.setInterval(3);
            hotMeter = new AmbientTests.AmbientMeter(h, hot::getNetwork);
            coldMeter = new AmbientTests.AmbientMeter(h, cold::getNetwork);
            sinkMeter = new AmbientTests.AmbientMeter(h, sink::getNetwork);
            h.onEachTick("meter the operating plant", () -> {
                if (!started) {
                    if (pumpEnergy() < startupEu) loop.power().injectEnergyUnits(SOUTH, 2048, 1);
                    return;
                }
                if (running && suppliedHeat < boilerBudget && boiler.getEUCapacity() - boiler.getStoredEU() >= 128)
                    suppliedHeat += 128 * boiler.injectEnergyUnits(WEST, 128, 1);
                long damage = MetaGeneratedTool.getToolDamage(turbine.getStackInSlot(1));
                if (damage != wear) {
                    var tag = new NBTTagCompound();
                    var tile = (TileEntity)turbine;
                    var reader = FakePlayerFactory.getMinecraft(site.world());
                    turbine.getMetaTileEntity().getWailaNBTData(reader, tile, tag, h.getWorld(), tile.xCoord, tile.yCoord, tile.zCoord);
                    shaft += Math.round(tag.getDouble("shaftEuPerTick") * 20);
                    wear = damage;
                }
                if (pumpEnergy() < lastPumpEnergy) pumpStrokes++;
                lastPumpEnergy = pumpEnergy();
                long received = load.getStoredEU();
                if (received > 0 && load.decreaseStoredEnergyUnits(received, false)) consumed += received;
            });
        }

        void ready(GameTestHelper h) {
            loop.ready(h);
            h.assertTrue(sink.getNetwork() != null && sink.getNetwork() != hot.getNetwork() && sink.getNetwork() != cold.getNetwork()
                && pumpEnergy() == startupEu, "Independent cooling circuit and charged startup supply");
        }


        void start(GameTestHelper h) {
            hotSections = PressureLineTests.rigidReferenceVolumes(hot);
            coldSections = PressureLineTests.rigidReferenceVolumes(cold);
            hot.getNetwork().replaceCanonicalState(IFNCanonicalState.fromRaw("water", hotSections * charge.hotMass, hotSections * charge.hotEnergy));
            cold.getNetwork().replaceCanonicalState(IFNCanonicalState.fromRaw("water", coldSections * charge.coldMass, coldSections * charge.coldEnergy));
            sink.getNetwork().replaceCanonicalState(IFNCanonicalState.fromRaw("water", 50_000_000L, -15_000_000_000_000L));
            h.assertTrue(Math.abs(hot.getNetwork().getTemperature() - 850) < .01 && Math.abs(cold.getNetwork().getTemperature() - 650) < .01,
                "Matched hot and cold temperatures");
            h.assertTrue(Math.abs(hot.getNetwork().getPressure() - charge.targetCbar / 100.0) < .01
                && Math.abs(cold.getNetwork().getPressure() - charge.targetCbar / 150.0) < .01, "Matched 1.5 pressure ratio");
            initialWater = water();
            initialSink = sinkMeter.withoutAmbient(sink.getNetwork());
            initialPump = lastPumpEnergy = pumpEnergy();
            started = running = true;
            pump.enableWorking();
            turbine.enableWorking();
        }

        long water() { return hotMeter.withoutAmbient(hot.getNetwork()) + coldMeter.withoutAmbient(cold.getNetwork()); }

        long pumpEnergy() { return raw(loop.power()); }

        void stop() {
            running = false;
            turbine.disableWorking();
            pump.disableWorking();
        }

        private static long raw(IGregTechTileEntity tile) {
            return ((gregtech.api.metatileentity.BaseMetaTileEntity)tile).getStoredEUuncapped();
        }

        long loadEu() { return consumed + raw(load); }

        long electricalInventory() {
            long eu = loadEu() + pumpEnergy() + raw(loop.turbine().supply()) + raw(distributor);
            for (var transformer : loadTransformers) eu += raw(transformer);
            return eu;
        }

        long pumpWork(GameTestHelper h) {
            long emitted = shaft - raw(loop.turbine().supply());
            h.assertEquals(0L, emitted % 32800, "Dynamo emitted complete LuV packets");
            long iv = emitted / 32800 * 32768 - raw(distributor);
            h.assertEquals(0L, iv % 8208, "Shared transformer emitted complete IV packets");
            // Trace the load back to IV: the remaining IV packets went to the pump
            long loadBranch = loadEu();
            long v = 128;
            for (var transformer : loadTransformers) {
                long loss = 1L << (gregtech.api.util.GTUtility.getTier(v)-1);
                h.assertEquals(0L, loadBranch % v, "Complete load-branch packets at " + v + " V");
                loadBranch += loadBranch / v * loss + raw(transformer);
                v *= 4;
            }
            return initialPump + iv / 8208 * 8192 - loadBranch - pumpEnergy();
        }

        void verify(GameTestHelper h) {
            long heat = suppliedHeat - boiler.getStoredEU();
            long rejected = sinkMeter.withoutAmbient(sink.getNetwork()) - initialSink;
            long paid = pumpWork(h);
            long net = loadEu() + pumpEnergy() - initialPump;
            h.recordDiagnostic("Plant " + charge + ": heat=" + heat + " EU, shaft=" + shaft + " EU, pump=" + paid
                + " EU, load=" + loadEu() + " EU, pumpBufferDelta=" + (pumpEnergy()-initialPump)
                + " EU, loadPlusPumpDelta=" + net + " EU, sinkHeat=" + rejected + " microEU, pumpStrokes=" + pumpStrokes
                + ", electricDeltaIncludingLoad=" + (electricalInventory()-initialPump) + " EU, hotMassDrift="
                + (hot.getNetwork().getAmountQ() - hotSections * charge.hotMass) + " microkg");
            h.assertEquals(25600L, heat, "Both benchmarks receive exactly the same measured boiler heat");
            h.assertTrue(loadEu() > 0 && paid > 0 && pumpStrokes >= 2 && rejected > 0, "External load, repeated paid returns and heat rejection");
            h.assertTrue(net > 0, "External delivery exceeds depletion of pump startup electricity");
            h.assertEquals(hotSections*charge.hotMass + coldSections*charge.coldMass, hot.getNetwork().getAmountQ() + cold.getNetwork().getAmountQ(),
                "Closed working inventory");
            h.assertEquals((heat + paid - shaft) * 1_000_000L, water() - initialWater + rejected,
                "Heat and both machines account for every change of fluid energy");
        }
    }

    @GameTest(template = "water_line", timeoutTicks = 850)
    public static void denseReturnPlant(GameTestHelper h) { run(h, Charge.HIGHER); }

    @GameTest(template = "water_line", timeoutTicks = 850)
    public static void lowerPressureReturnPlant(GameTestHelper h) { run(h, Charge.LOWER); }

    /** Pipe detector covers power GT work controllers on the facing machine surfaces. */
    static final class GovernedPlant {
        final Plant plant;
        final IGregTechTileEntity[] heaters;
        long supplied;
        boolean reserveClosed, reopened;
        long loadAtReopen;
        double minHot, maxHot, minCold, maxCold;

        GovernedPlant(GameTestHelper h) {
            plant = new Plant(h, Charge.HIGHER, true);
            plant.boilerBudget = 0;
            var site = RemoteMachineSite.at(h, "controller");
            TurbineTests.installSteelRotor(plant.turbine);
            ((MTEIntegratedFluidSteamTurbine)plant.turbine.getMetaTileEntity()).setMinimumInletCbar(Charge.HIGHER.targetCbar);
            ((MTEIntegratedFluidLiquidPump)plant.pump.getMetaTileEntity()).setTarget(5);
            var bank = new ArrayList<IGregTechTileEntity>();
            Collections.addAll(bank, plant.boiler, site.place(-3, 0, 1, 5776, WEST),
                site.place(-2, 1, 0, 5776, UP), site.place(-3, 0, -1, 5776, WEST),
                site.place(-2, 1, 1, 5776, UP), site.place(-2, 1, -1, 5776, UP));
            plant.hot.connect(UP);
            for (int z : new int[]{-1,1}) {
                var pipe = (MTEIntegratedFluidPipe) site.tile(-2, 0, z).getMetaTileEntity();
                pipe.connect(WEST);
                pipe.connect(UP);
            }
            addThermalHeaders(h, site, bank);
            heaters = bank.toArray(new IGregTechTileEntity[0]);
            for (var heater : heaters) {
                var side = heater.getFrontFacing().getOpposite();
                control(h, heater, side, ItemList.Cover_IFNTemperatureDetector, 850.1);
            }
            control(h, plant.turbine, EAST, ItemList.Cover_IFNTemperatureDetector, 650);
            control(h, plant.pump, WEST, ItemList.Cover_IFNPressureDetector, 331.5);
            var power = plant.loop.power();
            var gate = plant.loadTransformers[1];
            var meter = ItemList.Cover_EnergyDetector.get(1);
            var work = ItemList.Cover_Controller.get(1);
            h.assertTrue(power.getTileEntityAtSide(UP) == gate && CoverRegistry.getCoverPlacer(meter).isCoverPlaceable(UP, meter, power)
                && power.getMetaTileEntity().allowCoverOnSide(UP, meter) && CoverRegistry.getCoverPlacer(work).isCoverPlaceable(DOWN, work, gate)
                && gate.getMetaTileEntity().allowCoverOnSide(DOWN, work), "Reserve meter faces the external-load transformer");
            power.attachCover(((CoverEUMeter)CoverRegistry.buildCover(meter, UP, power)).setThresdhold(140000));
            gate.attachCover(CoverRegistry.buildCover(work, DOWN, gate));
            h.onEachTick("supply the boiler bank", () -> {
                if (!plant.running) return;
                var hot = plant.hot.getNetwork();
                var cold = plant.cold.getNetwork();
                if (!gate.isAllowedToWork()) reserveClosed = true;
                if (!reopened && reserveClosed && gate.isAllowedToWork() && plant.pumpStrokes >= 2 && plant.pumpEnergy() >= 140000) {
                    reopened = true;
                    loadAtReopen = plant.loadEu();
                    h.recordDiagnostic("Export reserve recovered: pump=" + plant.pumpEnergy() + " EU, prior load=" + loadAtReopen);
                }
                for (var heater : heaters) if (heater.getEUCapacity() - heater.getStoredEU() >= 128)
                    supplied += 128 * heater.injectEnergyUnits(heater.getFrontFacing(), 128, 1);
                minHot = Math.min(minHot, hot.getTemperature());
                maxHot = Math.max(maxHot, hot.getTemperature());
                minCold = Math.min(minCold, cold.getTemperature());
                maxCold = Math.max(maxCold, cold.getTemperature());
            });
        }

        private void addThermalHeaders(GameTestHelper h, RemoteMachineSite site, List<IGregTechTileEntity> bank) {
            for (int sign : new int[]{-1, 1}) {
                var inward = sign < 0 ? EAST : WEST;
                for (int col = 2; col <= 4; col++) {
                    for (int z = -7; z <= 5; z++) {
                        var pipe = (MTEIntegratedFluidPipe)site.place(sign*col, -1, z, MTEIntegratedFluidPipe.REINFORCED_ID, NORTH).getMetaTileEntity();
                        if (z > -7) h.assertTrue(pipe.connect(NORTH) > 0, "Buffer row connected");
                        if (z == 0) h.assertTrue(pipe.connect(col == 2 ? UP : inward) > 0, "Buffer rows joined to the header");
                        if (sign < 0 && col >= 3) {
                            bank.add(site.place(-col, -2, z, 5776, DOWN));
                            h.assertTrue(pipe.connect(DOWN) > 0, "Boiler on hot header");
                        }
                    }
                }
            }
            var previous = plant.sink;
            for (int x = 5; x <= 6; x++) {
                var next = (MTEIntegratedFluidPipe)site.place(x, 0, 0, MTEIntegratedFluidPipe.REINFORCED_ID, WEST).getMetaTileEntity();
                h.assertTrue(previous.connect(EAST) > 0, "Cooling water header connected");
                previous = next;
            }
            for (int z = -2; z <= 2; z++) {
                var coolant = (MTEIntegratedFluidPipe)site.place(6, -1, z, MTEIntegratedFluidPipe.REINFORCED_ID, NORTH).getMetaTileEntity();
                if (z > -2) h.assertTrue(coolant.connect(NORTH) > 0, "Parallel cooling surfaces connected");
                if (z == 0) h.assertTrue(coolant.connect(UP) > 0, "Cooling water joined");
                if (z % 2 == 0) {
                    site.place(5, -1, z, 5775, EAST);
                    var hot = (MTEIntegratedFluidPipe)site.tile(4, -1, z).getMetaTileEntity();
                    h.assertTrue(hot.connect(EAST) > 0 && coolant.connect(WEST) > 0, "Separate exchanger contacts");
                }
            }
        }

        static CoverIFNDetectorBase control(GameTestHelper h, IGregTechTileEntity machine, ForgeDirection side,
            ItemList detector, double threshold) {
            var sensorTile = (IGregTechTileEntity) machine.getTileEntityAtSide(side);
            var facing = side.getOpposite();
            var sensor = detector.get(1);
            var controller = ItemList.Cover_Controller.get(1);
            h.assertTrue(CoverRegistry.getCoverPlacer(sensor).isCoverPlaceable(facing, sensor, sensorTile)
                && sensorTile.getMetaTileEntity().allowCoverOnSide(facing, sensor)
                && CoverRegistry.getCoverPlacer(controller).isCoverPlaceable(side, controller, machine)
                && machine.getMetaTileEntity().allowCoverOnSide(side, controller), "Detector and controller can be installed");
            var cover = ((CoverIFNDetectorBase) CoverRegistry.buildCover(sensor, facing, sensorTile)).setRange(0, threshold);
            sensorTile.attachCover(cover);
            machine.attachCover(CoverRegistry.buildCover(controller, side, machine));
            return cover;
        }

        Sample sample(GameTestHelper h) {
            long input = supplied;
            for (var heater : heaters) input -= heater.getStoredEU();
            var hot = plant.hot.getNetwork();
            var cold = plant.cold.getNetwork();
            var heater = heaters[0];
            var side = heater.getFrontFacing().getOpposite();
            var sensor = (IGregTechTileEntity)heater.getTileEntityAtSide(side);
            h.recordDiagnostic("Controls: boilerAllowed=" + heater.isAllowedToWork() + ", heaterBuffer=" + heater.getStoredEU()
                + ", supply=" + supplied + ", heat=" + input + ", detectorOut=" + sensor.getOutputRedstoneSignal(side.getOpposite())
                + ", controllerIn=" + heater.getInputRedstoneSignal(side) + ", turbineAllowed=" + plant.turbine.isAllowedToWork()
                + ", pumpAllowed=" + plant.pump.isAllowedToWork() + ", pumpEU=" + plant.pumpEnergy()
                + ", pumpStatus=" + ((MTEIntegratedFluidLiquidPump)plant.pump.getMetaTileEntity()).status()
                + ", hot=" + hot.getPressure() + " bar " + hot.getTemperature() + " K, cold=" + cold.getPressure() + " bar " + cold.getTemperature() + " K");
            return new Sample(input, plant.loadEu(), plant.pumpEnergy(), Plant.raw(plant.loop.turbine().supply()),
                hot.getCanonicalState().totalEnergy().rawUnits() + cold.getCanonicalState().totalEnergy().rawUnits(),
                plant.water(), plant.sinkMeter.withoutAmbient(plant.sink.getNetwork()), hot.getAmountQ(), plant.shaft, plant.pumpWork(h), plant.electricalInventory());
        }

        void resetExtrema() {
            minHot = maxHot = plant.hot.getNetwork().getTemperature();
            minCold = maxCold = plant.cold.getNetwork().getTemperature();
        }
    }

    record Sample(long heat, long load, long pump, long turbine, long water, long withoutAmbient, long sink, long hotMass, long shaft,
        long pumpWork, long electricity) {}

    @GameTest(template = "water_line", timeoutTicks = 9900)
    public static void sustainedDensePlantSuppliesRepeatedLoads(GameTestHelper h) {
        var rig = new GovernedPlant(h);
        var p = rig.plant;
        var samples = new Sample[4];
        var seq = h.startSequence().thenWaitUntil(() -> p.ready(h)).thenExecute(() -> {
            p.start(h);
            rig.resetExtrema();
        }).thenWaitUntil(() -> h.assertTrue(rig.reopened && p.loadEu() >= rig.loadAtReopen + 2048 && p.pumpStrokes >= 2
            && Math.abs(p.hot.getNetwork().getTemperature()-850) < 1 && Math.abs(p.cold.getNetwork().getTemperature()-650) < 1,
            "Reserve reopened=" + rig.reopened + ", load=" + p.loadEu() + " EU, pump=" + p.pumpEnergy() + " EU, strokes=" + p.pumpStrokes
                + ", hot=" + p.hot.getNetwork().getTemperature() + " K, cold=" + p.cold.getNetwork().getTemperature() + " K"))
            .thenExecute(() -> {
            h.recordDiagnostic("Commissioned: load=" + p.loadEu() + " EU, startup reserve=" + p.startupEu + " EU, returns=" + p.pumpStrokes);
            samples[0] = rig.sample(h);
            rig.resetExtrema();
        });
        for (int i = 1; i < samples.length; i++) {
            final int n = i;
            seq.thenIdle(1000).thenExecute(() -> {
                var a = samples[n-1];
                var b = samples[n] = rig.sample(h);
                long generated = b.shaft-a.shaft, paid = b.pumpWork-a.pumpWork;
                h.recordDiagnostic("Sustained window " + n + ": heat=" + (b.heat-a.heat) + ", load=" + (b.load-a.load)
                    + ", pumpDelta=" + (b.pump-a.pump) + ", turbineDelta=" + (b.turbine-a.turbine)
                    + ", waterDeltaMicroEU=" + (b.water-a.water) + ", hotMassDeltaMg=" + (b.hotMass-a.hotMass)
                    + ", coolingMicroEU=" + (b.sink-a.sink) + ", hotK=" + rig.minHot + ".." + rig.maxHot
                    + ", coldK=" + rig.minCold + ".." + rig.maxCold + ", shaft=" + generated + ", pumpWork=" + paid);
                h.assertEquals(p.hotSections*p.charge.hotMass + p.coldSections*p.charge.coldMass, p.hot.getNetwork().getAmountQ()
                    + p.cold.getNetwork().getAmountQ(), "Closed working inventory");
                h.assertTrue(b.heat > a.heat && b.sink > a.sink && b.load > a.load, "Paid heat, cooling and external output in every window");
                h.assertTrue(paid > 0, "The return pump drew electricity");
                h.assertEquals((b.heat-a.heat+paid-generated)*1_000_000L, b.withoutAmbient-a.withoutAmbient + b.sink-a.sink,
                    "Both thermal circuits close against the metered boiler and shafts");
                h.assertTrue(rig.minHot > 849 && rig.maxHot < 851 && rig.minCold > 649 && rig.maxCold < 651,
                    "Both temperatures remain within one kelvin");
                rig.resetExtrema();
            });
        }
        seq.thenExecute(() -> {
            var a = samples[0];
            var b = samples[3];
            long net = b.electricity - a.electricity;
            h.recordDiagnostic("Full 3000 ticks: energy including load=" + net + " EU, load=" + (b.load-a.load) + " EU, pumpWork="
                + (b.pumpWork-a.pumpWork) + " EU, heat=" + (b.heat-a.heat) + " EU, hotMassChange=" + (b.hotMass-a.hotMass) + " microkg");
            h.assertTrue(net > 0 && Math.abs(b.hotMass-a.hotMass) < 50_000, "Positive output without draining a working line");
            p.stop();
        }).thenSucceed();
    }

    private static void run(GameTestHelper h, Charge charge) {
        var plant = new Plant(h, charge, false);
        h.startSequence().thenWaitUntil(() -> plant.ready(h)).thenExecute(() -> plant.start(h))
            .thenIdle(220).thenExecute(plant::stop).thenIdle(40).thenExecute(() -> plant.verify(h)).thenSucceed();
    }
}
