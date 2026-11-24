package gttests.vfn;

import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;

import gregtech.api.GregTechAPI;
import gregtech.api.enums.Mods;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidInjectorHatch;
import gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidPipe;
import gregtech.api.metatileentity.implementations.integratedfluid.IntegratedFluidNetwork;
import gregtech.api.metatileentity.implementations.integratedfluid.state.IFNCanonicalState;

@GameTestHolder(value = Mods.ModIDs.GREG_TECH, templatePrefix = "vfn")
public class VaporOutletTests {

    @GameTest(template = "regulator", timeoutTicks = 180)
    public static void vaporOutletWaitsForWholeSteamPackets(GameTestHelper h) {
        var site = RemoteMachineSite.at(h, "source");
        var hot = (MTEIntegratedFluidPipe) site.place(0, 0, 0, 5770, ForgeDirection.EAST).getMetaTileEntity();
        site.place(1, 0, 0, 5778, ForgeDirection.EAST);
        var cold = (MTEIntegratedFluidPipe) site.place(2, 0, 0, 5770, ForgeDirection.WEST).getMetaTileEntity();
        hot.connect(ForgeDirection.EAST);
        cold.connect(ForgeDirection.WEST);
        h.startSequence().thenWaitUntil(() -> h.assertTrue(hot.getNetwork() != null && cold.getNetwork() != null
            && hot.getNetwork() != cold.getNetwork(), "Two separate vessels")).thenExecute(() ->
                hot.getNetwork().replaceCanonicalState(IFNCanonicalState.fromRaw("water", 30_000, 66_113_805_000L)))
            .thenIdle(25).thenExecute(() -> {
                h.assertEquals(0L, cold.getNetwork().getAmountQ(), "A 20 L stroke containing only 6 g stays put");
                h.assertEquals(30_000L, hot.getNetwork().getAmountQ(), "The source kept the incomplete packet");
                hot.getNetwork().replaceCanonicalState(IFNCanonicalState.fromRaw("water", 65_000, 143_246_577_500L));
            }).thenWaitUntil(() -> h.assertTrue(cold.getNetwork().getAmountQ() > 0, "First metered discharge"))
            .thenExecute(() -> {
                h.assertEquals(12500L, cold.getNetwork().getAmountQ(), "Two whole steam packets, not 13 g");
                h.assertEquals(52500L, hot.getNetwork().getAmountQ(), "All remaining steam stayed upstream");
            }).thenSucceed();
    }

    record Plant(TurbineTests.Rig rig, MTEIntegratedFluidInjectorHatch feed, IGregTechTileEntity boiler) {
        void ready(GameTestHelper helper) {
            rig.steam().ready(helper);
            helper.assertTrue(feed.getNetwork() != null && feed.getNetwork().getMemberCount() == 2
                && feed.getNetwork() != rig.steam().hot().getNetwork() && feed.getNetwork() != rig.steam().cold().getNetwork(),
                "Boiler water, dry line and turbine exhaust are distinct");
        }

        void fill(GameTestHelper helper) {
            helper.assertEquals(1, feed.fill(new FluidStack(FluidRegistry.WATER, 1), true), "Feed only liquid GT water");
        }
    }

    static Plant place(GameTestHelper helper) {
        return place(helper, RemoteMachineSite.at(helper, "source"), ForgeDirection.EAST);
    }

    private static Plant place(GameTestHelper helper, RemoteMachineSite site, ForgeDirection boilerFace) {
        helper.assertNotNull(GregTechAPI.METATILEENTITIES[5778], "Registered VFN vapor outlet");
        var rig = TurbineTests.place(helper, site);
        site.place(0, 0, 1, 5778, ForgeDirection.NORTH);
        var drum = (MTEIntegratedFluidPipe) site.place(0, 0, 2, 5770, ForgeDirection.NORTH).getMetaTileEntity();
        var feed = (MTEIntegratedFluidInjectorHatch) site.place(-1, 0, 2, 5773, ForgeDirection.EAST).getMetaTileEntity();
        var boiler = site.place(boilerFace.offsetX, boilerFace.offsetY, 2, 5776, boilerFace);
        helper.assertTrue(drum.connect(ForgeDirection.WEST) > 0 && drum.connect(boilerFace) > 0
            && drum.connect(ForgeDirection.NORTH) > 0 && rig.steam().source().connect(ForgeDirection.SOUTH) > 0,
            "Feedwater and vapor ports connected");
        return new Plant(rig, feed, boiler);
    }

    record ReturnLoop(Plant plant, IGregTechTileEntity pump, IGregTechTileEntity reheater) {
        void ready(GameTestHelper helper) {
            plant.rig().steam().ready(helper);
            var drum = plant.feed().getNetwork();
            helper.assertTrue(drum != null && drum.getMemberCount() == 4 && drum != plant.rig().steam().cold().getNetwork()
                && drum != plant.rig().steam().hot().getNetwork(), "Continuous return pipe into a separate boiler");
        }
    }

    /** An identical initial inventory, wiring and fixed operating window for the two-world comparison. */
    static final class ReservoirTrial {
        final ReturnLoop loop;
        private final RemoteMachineSite site;
        private final AmbientTests.AmbientMeter drumMeter, inletMeter, exhaustMeter;
        private long initialEnergy, heaterSupply, pumpSupply, heatBeforePower, poweredSince;
        private boolean running;

        ReservoirTrial(GameTestHelper helper, RemoteMachineSite site) {
            this.site = site;
            loop = placeReturnLoop(helper, site);
            var p = loop.plant();
            drumMeter = new AmbientTests.AmbientMeter(helper, p.feed()::getNetwork);
            inletMeter = new AmbientTests.AmbientMeter(helper, p.rig().steam().hot()::getNetwork);
            exhaustMeter = new AmbientTests.AmbientMeter(helper, this::exhaust);
            helper.onEachTick("feed matched MV equipment", () -> {
                if (!running) return;
                heaterSupply += 128 * p.boiler().injectEnergyUnits(ForgeDirection.UP, 128, 1);
                pumpSupply += 128 * loop.pump().injectEnergyUnits(ForgeDirection.UP, 128, 1);
                if (p.rig().steam().hot().getNetwork().getQuality() < 1)
                    heaterSupply += 128 * loop.reheater().injectEnergyUnits(ForgeDirection.NORTH, 128, 1);
            });
        }

        IntegratedFluidNetwork exhaust() {
            return loop.plant().rig().steam().cold().getNetwork();
        }

        void prime() {
            var p = loop.plant();
            p.rig().turbine().disableWorking();
            var drum = p.feed().getNetwork();
            var dry = p.rig().steam().hot().getNetwork();
            var gas = IFNCanonicalState.fromRaw("water", 5000, 10423950000L);
            drum.replaceCanonicalState(IFNCanonicalState.fromRaw("water", 990000, -297000000000L));
            dry.replaceCanonicalState(gas);
            exhaust().replaceCanonicalState(gas);
            initialEnergy = storedMicroEu();
        }

        long ambientHeat() {
            return drumMeter.heatMicroEu() + inletMeter.heatMicroEu() + exhaustMeter.heatMicroEu();
        }

        long elapsed() { return loop.pump().getTimer() - poweredSince; }

        private long storedMicroEu() {
            var p = loop.plant();
            return p.feed().getNetwork().getCanonicalState().totalEnergy().rawUnits()
                + p.rig().steam().hot().getNetwork().getCanonicalState().totalEnergy().rawUnits()
                + exhaust().getCanonicalState().totalEnergy().rawUnits();
        }

        void start(GameTestHelper helper) {
            helper.assertEquals(5000L, exhaust().getAmountQ(), "No flow into the sealed exhaust before startup");
            helper.assertEquals(initialEnergy + ambientHeat(), storedMicroEu(), "Only the reservoirs supplied startup heat");
            helper.recordDiagnostic("Sealed exhaust dim=" + site.world().provider.dimensionId + " K=" + exhaust().getTemperature()
                + " liquid mg=" + exhaust().getAmountQ() * (1 - exhaust().getQuality()) + " reservoir microEU=" + ambientHeat());
            poweredSince = loop.pump().getTimer();
            heatBeforePower = ambientHeat();
            loop.plant().rig().turbine().enableWorking();
            running = true;
        }

        private static long bufferEu(IGregTechTileEntity tile) {
            return ((gregtech.api.metatileentity.MetaTileEntity) tile.getMetaTileEntity()).getEUVar();
        }

        void finish(GameTestHelper helper) {
            running = false;
            var p = loop.plant();
            var rig = p.rig();
            // GT accepts whole packets. The displayed buffer is capped even when the stored packet extends past capacity
            long heaters = heaterSupply - bufferEu(p.boiler()) - bufferEu(loop.reheater());
            long pump = pumpSupply - bufferEu(loop.pump());
            long consumed = heaters + pump;
            long load = bufferEu(rig.load());
            long shaft = bufferEu(rig.turbine()) + load + 2 * (load / 128);
            helper.recordDiagnostic("Operating loop dim=" + site.world().provider.dimensionId + " ticks=" + elapsed()
                + " heater EU=" + heaters + " pump EU=" + pump + " shaft EU=" + shaft + " load EU=" + load + " net EU=" + (load - consumed)
                + " operating reservoir microEU=" + (ambientHeat() - heatBeforePower) + " retained microEU=" + storedMicroEu());
            helper.assertEquals(1000000L, p.feed().getNetwork().getAmountQ() + rig.steam().hot().getNetwork().getAmountQ() + exhaust().getAmountQ(),
                "Full kilogram remains in the plant");
            helper.assertTrue(heaters > 0 && pump >= 0 && shaft > 0, "Electrical work and actual steam expansion");
            helper.assertEquals(initialEnergy + ambientHeat() + (consumed - shaft) * 1000000L, storedMicroEu(),
                "Mass, electricity and reservoir heat account for each plant independently");
        }
    }

    static ReturnLoop placeReturnLoop(GameTestHelper helper) {
        return placeReturnLoop(helper, RemoteMachineSite.at(helper, "source"));
    }

    static ReturnLoop placeReturnLoop(GameTestHelper helper, RemoteMachineSite site) {
        var plant = place(helper, site, ForgeDirection.UP);
        var reheater = site.place(0, 0, -1, 5776, ForgeDirection.NORTH);
        helper.assertTrue(plant.rig().steam().source().connect(ForgeDirection.NORTH) > 0, "Reheater contacts the turbine inlet");
        var pump = site.place(2, 0, 1, 5779, ForgeDirection.SOUTH);
        var elbow = (MTEIntegratedFluidPipe) site.place(2, 0, 2, 5770, ForgeDirection.WEST).getMetaTileEntity();
        var pipe = (MTEIntegratedFluidPipe) site.place(1, 0, 2, 5770, ForgeDirection.WEST).getMetaTileEntity();
        helper.assertTrue(plant.rig().steam().outlet().connect(ForgeDirection.SOUTH) > 0 && elbow.connect(ForgeDirection.NORTH) > 0
            && elbow.connect(ForgeDirection.WEST) > 0 && pipe.connect(ForgeDirection.WEST) > 0, "Condensate return connections");
        return new ReturnLoop(plant, pump, reheater);
    }

    @GameTest(template = "regulator", timeoutTicks = 4400)
    public static void pumpedCondensateReturnsToTheBoiler(GameTestHelper helper) {
        var loop = placeReturnLoop(helper);
        var boiler = loop.plant().boiler();
        var pump = loop.pump();
        var rig = loop.plant().rig();
        var exhaust = rig.steam().cold();
        var feed = loop.plant().feed();
        var dry = rig.steam().hot();
        var a = new AmbientTests.AmbientMeter(helper, feed::getNetwork);
        var b = new AmbientTests.AmbientMeter(helper, dry::getNetwork);
        var c = new AmbientTests.AmbientMeter(helper, exhaust::getNetwork);
        long[] baseline = new long[4];
        long[] initialEnergy = new long[1], heaterSupply = new long[1], loadBeforeRestart = new long[1];
        long[] previousPumpEu = { -1 };
        int[] repeatStrokes = { 0 };
        boolean[] heating = { false };
        int[] ticks = { 0 };
        helper.onEachTick("power the boiler", () -> {
            if (heating[0]) {
                heaterSupply[0] += 128 * boiler.injectEnergyUnits(ForgeDirection.UP, 128, 1);
                if (dry.getNetwork().getQuality() < 1)
                    heaterSupply[0] += 128 * loop.reheater().injectEnergyUnits(ForgeDirection.NORTH, 128, 1);
                if (previousPumpEu[0] >= 0) {
                    if (pump.getStoredEU() < previousPumpEu[0]) repeatStrokes[0]++;
                    previousPumpEu[0] = pump.getStoredEU();
                }
            }
            if (heating[0] && ++ticks[0] % 300 == 0) {
                var d = feed.getNetwork();
                var x = exhaust.getNetwork();
                helper.recordDiagnostic("Tick " + ticks[0] + ": drum " + d.getPressurePa() + " Pa " + d.getTemperature()
                    + " K, dry " + dry.getNetwork().getAmountQ() + " microkg, exhaust " + x.getAmountQ() + " microkg "
                    + x.getPressurePa() + " Pa q=" + x.getQuality() + ", load " + rig.load().getStoredEU() + " EU");
            }
        });
        helper.startSequence().thenWaitUntil(() -> loop.ready(helper)).thenExecute(() -> {
            loop.plant().fill(helper);
            initialEnergy[0] = a.withoutAmbient(feed.getNetwork()) + b.withoutAmbient(dry.getNetwork()) + c.withoutAmbient(exhaust.getNetwork());
            heating[0] = true;
        }).thenWaitUntil(() -> helper.assertTrue(rig.load().getStoredEU() >= 128 && exhaust.getNetwork().getAmountQ() > 5000,
            "Turbine produces electricity and at least 5 g of steam exhaust"))
            .thenExecute(() -> rig.turbine().disableWorking())
            .thenWaitUntil(() -> helper.assertTrue(exhaust.getNetwork().getQuality() < 0.97
                && feed.getNetwork().getPressurePa() > exhaust.getNetwork().getPressurePa(),
                "Exhaust pipe condenses its trapped steam against positive return head")).thenExecute(() -> {
                heating[0] = false;
                boiler.disableWorking();
                loop.reheater().disableWorking();
                baseline[0] = exhaust.getNetwork().getAmountQ();
                baseline[1] = feed.getNetwork().getAmountQ() + dry.getNetwork().getAmountQ();
                baseline[2] = a.withoutAmbient(feed.getNetwork()) + b.withoutAmbient(dry.getNetwork()) + c.withoutAmbient(exhaust.getNetwork());
            }).thenIdle(22).thenExecute(() -> {
                helper.assertEquals(baseline[0], exhaust.getNetwork().getAmountQ(), "No return without electricity");
                helper.assertEquals(1L, pump.injectEnergyUnits(ForgeDirection.UP, 128, 1), "Pump accepted MV on a side");
            }).thenWaitUntil(() -> helper.assertTrue(exhaust.getNetwork().getAmountQ() < baseline[0], "Condensate returned"))
            .thenExecute(() -> {
                long returned = baseline[0] - exhaust.getNetwork().getAmountQ();
                long paid = 128 - pump.getStoredEU();
                helper.assertTrue(paid > 0 && feed.getNetwork().getPressurePa() > exhaust.getNetwork().getPressurePa(), "Paid positive lift");
                helper.assertEquals(baseline[1] + returned, feed.getNetwork().getAmountQ() + dry.getNetwork().getAmountQ(), "Mass back on boiler side");
                helper.assertEquals(1_000_000L, feed.getNetwork().getAmountQ() + dry.getNetwork().getAmountQ() + exhaust.getNetwork().getAmountQ(),
                    "One initial kilogram and no makeup water");
                helper.assertEquals(baseline[2] + paid * 1_000_000L,
                    a.withoutAmbient(feed.getNetwork()) + b.withoutAmbient(dry.getNetwork()) + c.withoutAmbient(exhaust.getNetwork()),
                    "Return stroke accounts for every paid EU");
                helper.recordDiagnostic("Return stroke: " + returned + " microkg, " + paid + " EU, boiler " + feed.getNetwork().getPressurePa()
                    + " Pa, condenser " + exhaust.getNetwork().getPressurePa() + " Pa");
                previousPumpEu[0] = pump.getStoredEU();
                loadBeforeRestart[0] = rig.load().getStoredEU();
                rig.turbine().enableWorking();
                boiler.enableWorking();
                loop.reheater().enableWorking();
                heating[0] = true;
            }).thenWaitUntil(() -> helper.assertTrue(repeatStrokes[0] >= 2 && rig.load().getStoredEU() > loadBeforeRestart[0],
                "Two more paid returns while the turbine supplies the load again")).thenExecute(() -> {
                long load = rig.load().getStoredEU();
                long shaft = rig.turbine().getStoredEU() + load + 2 * (load / 128);
                long heatingEu = heaterSupply[0] - boiler.getStoredEU() - loop.reheater().getStoredEU();
                long pumpEu = 128 - pump.getStoredEU();
                long retained = a.withoutAmbient(feed.getNetwork()) + b.withoutAmbient(dry.getNetwork()) + c.withoutAmbient(exhaust.getNetwork());
                helper.assertEquals(initialEnergy[0] + (heatingEu + pumpEu - shaft) * 1_000_000L, retained, "Complete plant energy balance");
                helper.assertEquals(1_000_000L, feed.getNetwork().getAmountQ() + dry.getNetwork().getAmountQ() + exhaust.getNetwork().getAmountQ(),
                    "Repeated returns need no makeup water");
                helper.recordDiagnostic("Closed loop: heaters=" + heatingEu + " EU, pump=" + pumpEu + " EU, shaft=" + shaft + " EU, GT load=" + load
                    + " EU, net load minus paid work=" + (load - heatingEu - pumpEu) + " EU, subsequent strokes=" + repeatStrokes[0]);
            }).thenSucceed();
    }

    @GameTest(template = "regulator", timeoutTicks = 1500)
    public static void wetBoilerPowersDryTurbine(GameTestHelper helper) {
        var plant = place(helper);
        var rig = plant.rig();
        var drumHeat = new AmbientTests.AmbientMeter(helper, plant.feed()::getNetwork);
        var inletHeat = new AmbientTests.AmbientMeter(helper, rig.steam().hot()::getNetwork);
        var exhaustHeat = new AmbientTests.AmbientMeter(helper, rig.steam().cold()::getNetwork);
        long[] initial = new long[1];
        long[] supplied = new long[1];
        boolean[] enabled = new boolean[1];
        helper.onEachTick("feed boiler EU", () -> {
            if (enabled[0]) supplied[0] += 128 * plant.boiler().injectEnergyUnits(ForgeDirection.EAST, 128, 1);
        });
        helper.startSequence()
            .thenWaitUntil(() -> plant.ready(helper))
            .thenExecute(() -> {
                plant.fill(helper);
                initial[0] = drumHeat.withoutAmbient(plant.feed().getNetwork());
                enabled[0] = true;
            })
            .thenWaitUntil(() -> helper.assertTrue(rig.load().getStoredEU() >= 128, "Dry turbine delivered a real MV packet"))
            .thenExecute(() -> {
                var drum = plant.feed().getNetwork();
                var inlet = rig.steam().hot().getNetwork();
                var exhaust = rig.steam().cold().getNetwork();
                helper.assertTrue(drum.getQuality() > 0 && drum.getQuality() < 0.1 && drum.getAmountQ() > 900_000L,
                    "Most feedwater remains as liquid in the boiling chamber");
                helper.assertEquals(1.0, inlet.getQuality(), "Dry vapor at the turbine inlet");
                helper.assertTrue(exhaust.getAmountQ() > 0, "Steam traversed turbine");
                helper.assertEquals(1_000_000L, drum.getAmountQ() + inlet.getAmountQ() + exhaust.getAmountQ(), "One kilogram total");
                long delivered = rig.load().getStoredEU();
                long generated = rig.turbine().getStoredEU() + delivered + 2 * (delivered / 128);
                long stored = drumHeat.withoutAmbient(drum) + inletHeat.withoutAmbient(inlet) + exhaustHeat.withoutAmbient(exhaust);
                helper.assertEquals(initial[0] + (supplied[0] - plant.boiler().getStoredEU()) * 1_000_000L,
                    stored + generated * 1_000_000L, "All paid heat stays in the three chambers or powers the dynamo");
            })
            .thenSucceed();
    }
}
