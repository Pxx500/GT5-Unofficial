package gttests.vfn;

import static net.minecraftforge.common.util.ForgeDirection.*;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;

import gregtech.api.enums.MetaTileEntityIDs;
import gregtech.api.enums.ItemList;
import gregtech.api.covers.CoverRegistry;
import gregtech.common.covers.CoverIFNDetectorBase;
import gregtech.api.enums.Mods;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidLiquidPump;
import gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidPipe;
import gregtech.api.metatileentity.implementations.integratedfluid.state.IFNCanonicalState;

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
        final MTEIntegratedFluidPipe hot, cold, sink;
        final IGregTechTileEntity pump, turbine, boiler, load;
        final AmbientTests.AmbientMeter hotMeter, coldMeter, sinkMeter;
        final Charge charge;
        boolean running, started;
        long boilerBudget = 25_600;
        long suppliedHeat, initialWater, initialSink, initialPump, shaft, pumpingTicks;

        Plant(GameTestHelper h, Charge charge) {
            this.charge = charge;
            var site = RemoteMachineSite.at(h, "source");
            short pipe = MTEIntegratedFluidPipe.REINFORCED_ID;
            hot = (MTEIntegratedFluidPipe) site.place(0, 0, 0, pipe, EAST).getMetaTileEntity();
            cold = (MTEIntegratedFluidPipe) site.place(2, 0, 0, pipe, WEST).getMetaTileEntity();
            var feed = (MTEIntegratedFluidPipe) site.place(0, 0, -1, pipe, EAST).getMetaTileEntity();
            var drain = (MTEIntegratedFluidPipe) site.place(2, 0, -1, pipe, WEST).getMetaTileEntity();
            turbine = site.place(1, 0, 0, 5777, EAST);
            pump = site.place(1, 0, -1, MTEIntegratedFluidLiquidPump.HIGH_PRESSURE_ID, WEST);
            load = site.place(1, 0, 1, MetaTileEntityIDs.ENERGY_HATCH_MV.ID, NORTH);
            boiler = site.place(-1, 0, 0, 5776, WEST);
            site.place(3, 0, 0, 5775, EAST);
            sink = (MTEIntegratedFluidPipe) site.place(4, 0, 0, pipe, WEST).getMetaTileEntity();
            hot.connect(EAST);
            hot.connect(WEST);
            hot.connect(NORTH);
            cold.connect(WEST);
            cold.connect(EAST);
            cold.connect(NORTH);
            feed.connect(EAST);
            drain.connect(WEST);
            sink.connect(WEST);
            TurbineTests.installSteelRotor(turbine);
            turbine.disableWorking();
            pump.disableWorking();
            var settings = new NBTTagCompound();
            pump.getMetaTileEntity().saveNBTData(settings);
            // Return about 32 g per stroke at each initial inlet density
            settings.setString("pumpMode", "TARGET_FLOW");
            settings.setDouble("litersPerTick", charge == Charge.HIGHER ? 0.0075 : 0.0105);
            pump.getMetaTileEntity().loadNBTData(settings);
            hotMeter = new AmbientTests.AmbientMeter(h, hot::getNetwork);
            coldMeter = new AmbientTests.AmbientMeter(h, cold::getNetwork);
            sinkMeter = new AmbientTests.AmbientMeter(h, sink::getNetwork);
            h.onEachTick("meter the operating plant", () -> {
                if (!started) pump.injectEnergyUnits(UP, 2048, 1);
                if (running && suppliedHeat < boilerBudget) suppliedHeat += 128 * boiler.injectEnergyUnits(WEST, 128, 1);
                if (!started) return;
                if (turbine.getTimer() % 20 == 0) {
                    var tag = new NBTTagCompound();
                    var tile = (TileEntity) turbine;
                    turbine.getMetaTileEntity().getWailaNBTData(null, tile, tag, h.getWorld(), tile.xCoord, tile.yCoord, tile.zCoord);
                    shaft += Math.round(tag.getDouble("shaftEuPerTick") * 20);
                }
                if (running && pump.isActive()) pumpingTicks++;
            });
        }

        void ready(GameTestHelper h) {
            h.assertTrue(hot.getNetwork() != null && cold.getNetwork() != null && sink.getNetwork() != null
                && hot.getNetwork().getMemberCount() == 2 && cold.getNetwork().getMemberCount() == 2
                && hot.getNetwork() != cold.getNetwork() && pump.getStoredEU() >= 81920, "Two 200 L circuits and a charged startup buffer");
        }

        void start(GameTestHelper h) {
            hot.getNetwork().replaceCanonicalState(IFNCanonicalState.fromRaw("water", 2 * charge.hotMass, 2 * charge.hotEnergy));
            cold.getNetwork().replaceCanonicalState(IFNCanonicalState.fromRaw("water", 2 * charge.coldMass, 2 * charge.coldEnergy));
            sink.getNetwork().replaceCanonicalState(IFNCanonicalState.fromRaw("water", 50_000_000L, -15_000_000_000_000L));
            h.assertTrue(Math.abs(hot.getNetwork().getTemperature() - 850) < .01 && Math.abs(cold.getNetwork().getTemperature() - 650) < .01,
                "Matched hot and cold temperatures");
            h.assertTrue(Math.abs(hot.getNetwork().getPressure() - charge.targetCbar / 100.0) < .01
                && Math.abs(cold.getNetwork().getPressure() - charge.targetCbar / 150.0) < .01, "Matched 1.5 pressure ratio");
            initialWater = water();
            initialSink = sinkMeter.withoutAmbient(sink.getNetwork());
            initialPump = pumpEnergy();
            started = running = true;
            pump.enableWorking();
            turbine.enableWorking();
        }

        long water() { return hotMeter.withoutAmbient(hot.getNetwork()) + coldMeter.withoutAmbient(cold.getNetwork()); }

        long pumpEnergy() { return ((gregtech.api.metatileentity.BaseMetaTileEntity) pump).getStoredEUuncapped(); }

        void stop() {
            running = false;
            turbine.disableWorking();
            pump.disableWorking();
        }

        void verify(GameTestHelper h) {
            long heat = suppliedHeat - boiler.getStoredEU();
            long rejected = sinkMeter.withoutAmbient(sink.getNetwork()) - initialSink;
            long generated = shaft;
            long emitted = generated - turbine.getStoredEU();
            h.assertEquals(0L, emitted % 130, "MV packets include the two EU transmission loss");
            long sent = emitted / 130 * 128;
            // GT accepts a whole final packet. Its displayed buffer clips the stored excess
            long net = load.getStoredEU() + pumpEnergy() - initialPump;
            long paid = sent - net;
            h.recordDiagnostic("Plant " + charge + ": heat=" + heat + " EU, shaft=" + generated + " EU, pump=" + paid
                + " EU, load=" + load.getStoredEU() + " EU, pumpBufferDelta=" + (net - load.getStoredEU())
                + " EU, turbineBuffer=" + turbine.getStoredEU() + " EU, coolingMicroEu=" + rejected
                + ", deltaWaterMicroEuExcludingAmbient=" + (water() - initialWater) + ", pumpActiveTicks=" + pumpingTicks
                + ", hotMassDriftMg=" + (hot.getNetwork().getAmountQ() - 2 * charge.hotMass)
                + ", ambientMicroEu=" + (hotMeter.heatMicroEu() + coldMeter.heatMicroEu())
                + ", finalHotK=" + hot.getNetwork().getTemperature() + ", finalColdK=" + cold.getNetwork().getTemperature());
            h.assertEquals(25600L, heat, "Both benchmarks receive exactly the same measured boiler heat");
            h.assertTrue(load.getStoredEU() > 0 && paid > 0 && pumpingTicks >= 40 && rejected > 0, "External load, repeated powered return and cooling");
            h.assertTrue(net > 0, "External power remains after charging the startup buffer against output");
            h.assertEquals(2 * (charge.hotMass + charge.coldMass), hot.getNetwork().getAmountQ() + cold.getNetwork().getAmountQ(), "Closed loop mass");
            h.assertEquals((heat + paid - generated) * 1_000_000L, water() - initialWater + rejected,
                "Heat and both machines account for every change of fluid energy");
        }
    }

    @GameTest(template = "regulator", timeoutTicks = 500)
    public static void denseReturnPlant(GameTestHelper h) { run(h, Charge.HIGHER); }

    @GameTest(template = "regulator", timeoutTicks = 500)
    public static void lowerPressureReturnPlant(GameTestHelper h) { run(h, Charge.LOWER); }

    /** Pipe detector covers power GT work controllers on the facing machine surfaces. */
    static final class GovernedPlant {
        final Plant plant;
        final IGregTechTileEntity[] heaters;
        long supplied, consumed;
        double minHot, maxHot, minCold, maxCold;

        GovernedPlant(GameTestHelper h) {
            plant = new Plant(h, Charge.HIGHER);
            plant.boilerBudget = 0;
            var site = RemoteMachineSite.at(h, "source");
            heaters = new IGregTechTileEntity[] { plant.boiler, site.place(0, 0, 1, 5776, SOUTH),
                site.place(0, 1, 0, 5776, UP), site.place(-1, 0, -1, 5776, WEST),
                site.place(0, 0, -2, 5776, NORTH), site.place(0, 1, -1, 5776, UP) };
            plant.hot.connect(SOUTH);
            plant.hot.connect(UP);
            var feed = (MTEIntegratedFluidPipe) site.tile(0, 0, -1).getMetaTileEntity();
            feed.connect(WEST);
            feed.connect(NORTH);
            feed.connect(UP);
            for (var heater : heaters) {
                var side = heater.getFrontFacing().getOpposite();
                control(h, heater, side, ItemList.Cover_IFNTemperatureDetector, 850.1);
            }
            control(h, plant.turbine, EAST, ItemList.Cover_IFNTemperatureDetector, 650);
            control(h, plant.pump, WEST, ItemList.Cover_IFNPressureDetector, 331.5);
            h.onEachTick("supply the boiler bank and meter the load", () -> {
                if (!plant.running) return;
                var hot = plant.hot.getNetwork();
                var cold = plant.cold.getNetwork();
                for (var heater : heaters) supplied += 128 * heater.injectEnergyUnits(heater.getFrontFacing(), 128, 1);
                long load = plant.load.getStoredEU();
                if (load > 0 && plant.load.decreaseStoredEnergyUnits(load, false)) consumed += load;
                minHot = Math.min(minHot, hot.getTemperature());
                maxHot = Math.max(maxHot, hot.getTemperature());
                minCold = Math.min(minCold, cold.getTemperature());
                maxCold = Math.max(maxCold, cold.getTemperature());
            });
        }

        private static void control(GameTestHelper h, IGregTechTileEntity machine, ForgeDirection side,
            ItemList detector, double threshold) {
            var sensorTile = (IGregTechTileEntity) machine.getTileEntityAtSide(side);
            var facing = side.getOpposite();
            var sensor = detector.get(1);
            var controller = ItemList.Cover_Controller.get(1);
            h.assertTrue(CoverRegistry.getCoverPlacer(sensor).isCoverPlaceable(facing, sensor, sensorTile)
                && sensorTile.getMetaTileEntity().allowCoverOnSide(facing, sensor)
                && CoverRegistry.getCoverPlacer(controller).isCoverPlaceable(side, controller, machine)
                && machine.getMetaTileEntity().allowCoverOnSide(side, controller), "Detector and controller can be installed");
            sensorTile.attachCover(((CoverIFNDetectorBase) CoverRegistry.buildCover(sensor, facing, sensorTile)).setRange(0, threshold));
            machine.attachCover(CoverRegistry.buildCover(controller, side, machine));
        }

        Sample sample() {
            long input = supplied;
            for (var heater : heaters) input -= heater.getStoredEU();
            var hot = plant.hot.getNetwork();
            var cold = plant.cold.getNetwork();
            return new Sample(input, consumed + plant.load.getStoredEU(), plant.pumpEnergy(), plant.turbine.getStoredEU(),
                hot.getCanonicalState().totalEnergy().rawUnits() + cold.getCanonicalState().totalEnergy().rawUnits(),
                plant.water(), plant.sinkMeter.withoutAmbient(plant.sink.getNetwork()), hot.getAmountQ(), plant.shaft);
        }

        void resetExtrema() {
            minHot = maxHot = plant.hot.getNetwork().getTemperature();
            minCold = maxCold = plant.cold.getNetwork().getTemperature();
        }
    }

    record Sample(long heat, long load, long pump, long turbine, long water, long withoutAmbient, long sink, long hotMass, long shaft) {}

    @GameTest(template = "regulator", timeoutTicks = 3700)
    public static void sustainedDensePlantSuppliesRepeatedLoads(GameTestHelper h) {
        var rig = new GovernedPlant(h);
        var p = rig.plant;
        var samples = new Sample[4];
        var seq = h.startSequence().thenWaitUntil(() -> p.ready(h)).thenExecute(() -> {
            p.start(h);
            rig.resetExtrema();
        }).thenIdle(400).thenExecute(() -> {
            samples[0] = rig.sample();
            rig.resetExtrema();
        });
        for (int i = 1; i < samples.length; i++) {
            final int n = i;
            seq.thenIdle(1000).thenExecute(() -> {
                var a = samples[n-1];
                var b = samples[n] = rig.sample();
                long generated = b.shaft-a.shaft, emitted = generated - (b.turbine-a.turbine);
                h.assertEquals(0L, emitted % 130, "Measured shaft and dynamo inventory resolve whole MV packets");
                long paid = emitted / 130 * 128 - (b.load-a.load) - (b.pump-a.pump);
                h.recordDiagnostic("Sustained window " + n + ": heat=" + (b.heat-a.heat) + ", load=" + (b.load-a.load)
                    + ", pumpDelta=" + (b.pump-a.pump) + ", turbineDelta=" + (b.turbine-a.turbine)
                    + ", waterDeltaMicroEU=" + (b.water-a.water) + ", hotMassDeltaMg=" + (b.hotMass-a.hotMass)
                    + ", coolingMicroEU=" + (b.sink-a.sink) + ", hotK=" + rig.minHot + ".." + rig.maxHot
                    + ", coldK=" + rig.minCold + ".." + rig.maxCold + ", shaft=" + generated + ", pumpWork=" + paid);
                h.assertEquals(2 * (p.charge.hotMass + p.charge.coldMass), p.hot.getNetwork().getAmountQ()
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
            long net = b.load - a.load + b.pump - a.pump + b.turbine - a.turbine;
            h.assertTrue(net > 0 && Math.abs(b.hotMass-a.hotMass) < 50_000, "Positive output without draining a working line");
            p.stop();
        }).thenSucceed();
    }

    private static void run(GameTestHelper h, Charge charge) {
        var plant = new Plant(h, charge);
        h.startSequence().thenWaitUntil(() -> plant.ready(h)).thenExecute(() -> plant.start(h))
            .thenIdle(220).thenExecute(plant::stop).thenIdle(40).thenExecute(() -> plant.verify(h)).thenSucceed();
    }
}
