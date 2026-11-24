package gttests.vfn;

import java.util.ArrayList;
import java.util.List;

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
import gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidInputHatch;
import gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidOutputHatch;
import gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidLiquidPump;
import gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidPipe;
import gregtech.api.metatileentity.implementations.integratedfluid.IntegratedFluidNetwork;
import gregtech.api.metatileentity.implementations.integratedfluid.state.IFNCanonicalState;

@GameTestHolder(value = Mods.ModIDs.GREG_TECH, templatePrefix = "vfn")
public class VaporOutletTests {

    @GameTest(template = "radiator", timeoutTicks = 900)
    public static void liquidPumpDoesNotBankMissedStrokes(GameTestHelper h) {
        var rig = LiquidPumpTests.place(h);
        var feed = rig.input();
        var pump = rig.controller();
        var machine = (gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidLiquidPump) pump.getMetaTileEntity();
        var outlet = rig.output();
        long[] firstTransfer = new long[1];
        h.startSequence().thenWaitUntil(() -> h.assertTrue(feed.getNetwork() != null && outlet.getNetwork() != null
            && feed.getNetwork() != outlet.getNetwork(), "Separate inlet and outlet")).thenExecute(() -> {
                h.assertTrue(machine.checkStructure(true, pump), "Liquid pump formed");
                machine.fixAllIssues();
                machine.setMode(2);
                machine.setTarget(2);
                machine.setInterval(5);
                pump.enableWorking();
                feed.getNetwork().replaceCanonicalState(IFNCanonicalState.fromRaw("water", 5_000_000_000L, -1_500_000_000_000_000L));
                outlet.getNetwork().replaceCanonicalState(IFNCanonicalState.fromRaw("water", 1_000_000_000L, -300_000_000_000_000L));
            }).thenIdle(210).thenExecute(() -> {
                h.assertEquals(1_000_000_000L, outlet.getNetwork().getAmountQ(), "Two unpowered strokes moved nothing");
                h.assertEquals(1L, rig.supply().injectEnergyUnits(ForgeDirection.NORTH, 128, 1), "MV hatch accepted power");
            }).thenWaitUntil(() -> h.assertTrue(outlet.getNetwork().getAmountQ() > 1_000_000_000L, "First powered transfer: " + machine.status()
                + " at " + rig.supply().getStoredEU() + " EU"))
            .thenExecute(() -> {
                h.assertEquals(1_001_000_000L, outlet.getNetwork().getAmountQ(), "Only one whole kg fits, with no catch-up");
                firstTransfer[0] = pump.getTimer();
            }).thenWaitUntil(() -> h.assertTrue(outlet.getNetwork().getAmountQ() > 1_001_000_000L, "Second powered transfer"))
            .thenExecute(() -> {
                h.assertEquals(100L, pump.getTimer() - firstTransfer[0], "Configured period between transfers");
                h.assertEquals(1_002_000_000L, outlet.getNetwork().getAmountQ(), "Two single-kg deliveries");
                h.assertEquals(4_998_000_000L, feed.getNetwork().getAmountQ(), "Source debited exactly twice");
            }).thenSucceed();
    }

    @GameTest(template = "regulator", timeoutTicks = 180)
    public static void heliumOutletDrawsVaporFromWetCharge(GameTestHelper h) {
        var site = RemoteMachineSite.at(h, "source");
        var inlet = (MTEIntegratedFluidPipe) site.place(0, 0, 0, 5770, ForgeDirection.EAST).getMetaTileEntity();
        var injector = (MTEIntegratedFluidInjectorHatch) site.place(-1, 0, 0, 5773, ForgeDirection.EAST).getMetaTileEntity();
        site.place(0, 0, 1, 5771, ForgeDirection.NORTH);
        site.place(1, 0, 0, 5778, ForgeDirection.EAST);
        var outlet = (MTEIntegratedFluidPipe) site.place(2, 0, 0, 5770, ForgeDirection.WEST).getMetaTileEntity();
        inlet.connect(ForgeDirection.EAST);
        inlet.connect(ForgeDirection.WEST);
        inlet.connect(ForgeDirection.SOUTH);
        outlet.connect(ForgeDirection.WEST);
        var a = new AmbientTests.AmbientMeter(h, inlet::getNetwork);
        var b = new AmbientTests.AmbientMeter(h, outlet::getNetwork);
        long mass = 1_200_000_000L, heat = -4_500_000_000_000L;
        long[] admittedHeat = new long[1];
        h.startSequence().thenWaitUntil(() -> h.assertTrue(inlet.getNetwork() != null && outlet.getNetwork() != null
            && inlet.getNetwork() == injector.getNetwork() && inlet.getNetwork() != outlet.getNetwork()
            && inlet.getNetwork().getTopologySnapshot().baseVolume().toWholeLiters() == 10100,
            "Injector and machine hatch share the cold inlet, separate from the exhaust")).thenExecute(() -> {
                inlet.getNetwork().replaceCanonicalState(IFNCanonicalState.fromRaw("helium", mass, heat));
                h.assertEquals(1, injector.fill(FluidRegistry.getFluidStack("helium", 1), true), "Ambient GT helium admitted");
                admittedHeat[0] = a.withoutAmbient(inlet.getNetwork()) - heat;
                h.assertTrue(admittedHeat[0] > 150_000_000_000L && admittedHeat[0] < 200_000_000_000L,
                    "Ambient helium brings warm stream enthalpy, not free cooling");
                h.assertTrue(inlet.getNetwork().getQuality() > 0 && inlet.getNetwork().getQuality() < 0.05,
                    "Reservoir absorbs the incoming heat and remains mostly liquid");
            }).thenWaitUntil(() -> h.assertTrue(outlet.getNetwork().getAmountQ() > 0, "Separated vapor reached exhaust"))
            .thenExecute(() -> {
                var receiver = outlet.getNetwork();
                long drawn = receiver.getAmountQ();
                h.assertEquals("helium", receiver.getFluidName(), "Receiver kept substance identity");
                h.assertTrue(drawn <= 333333 && drawn % 111111 == 0, "One stroke meters gas volume in whole GT portions");
                h.assertEquals(mass + 111111, inlet.getNetwork().getAmountQ() + drawn, "Exactly one external unit added");
                h.assertEquals(heat + admittedHeat[0], a.withoutAmbient(inlet.getNetwork()) + b.withoutAmbient(receiver), "Closed energy ledger");
                h.assertTrue(b.withoutAmbient(receiver) > 15000L * drawn, "Vapor carries its enthalpy, not bulk energy");
                h.recordDiagnostic("Injected 111111 microkg with " + admittedHeat[0] + " microEU, vapor stroke "
                    + drawn + " microkg with " + b.withoutAmbient(receiver) + " microEU");
            }).thenSucceed();
    }

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

    record Plant(TurbineTests.Rig rig, MTEIntegratedFluidInjectorHatch feed, MTEIntegratedFluidPipe drum, IGregTechTileEntity boiler) {
        void ready(GameTestHelper helper) {
            rig.steam().ready(helper);
            helper.assertTrue(feed.getNetwork() != null && feed.getNetwork() == drum.getNetwork()
                && feed.getNetwork() != rig.steam().hot().getNetwork() && feed.getNetwork() != rig.steam().cold().getNetwork(),
                "Boiler water, dry line and turbine exhaust are distinct");
        }

    }

    record ColdStart(Plant plant, List<IGregTechTileEntity> drumHeaters, IGregTechTileEntity reheater) {
        static final int WATER_KG = 16;

        void fill(GameTestHelper h) {
            h.assertEquals(WATER_KG, plant.feed().fill(new FluidStack(FluidRegistry.WATER, WATER_KG), true), "Only ambient GT water supplied");
            var drum = plant.feed().getNetwork();
            h.assertTrue(drum.getTemperature() > 273 && drum.getTemperature() < 302 && drum.getQuality() < 0.01,
                "Cold liquid in the drum: " + drum.getTemperature() + " K, vapor fraction " + drum.getQuality());
            h.assertEquals(0L, plant.rig().steam().hot().getNetwork().getAmountQ(), "No inlet priming");
            h.assertEquals(0L, plant.rig().steam().cold().getNetwork().getAmountQ(), "No exhaust priming");
        }

        long powerDrum() {
            long eu = 0;
            for (var heater : drumHeaters) eu += acceptMv(heater, heater.getFrontFacing());
            return eu;
        }

        long powerReheater() {
            var line = plant.rig().steam().hot().getNetwork();
            return line != null && line.getAmountQ() > 0 && line.getQuality() < 1 ? acceptMv(reheater, ForgeDirection.UP) : 0;
        }

        long drumBuffer() {
            long eu = 0;
            for (var heater : drumHeaters) eu += heater.getStoredEU();
            return eu;
        }
    }

    static ColdStart coldStart(GameTestHelper h) {
        var site = RemoteMachineSite.at(h, "source");
        var plant = place(h, site, ForgeDirection.EAST, 3);
        TurbineTests.installRotor(plant.rig().turbine(), gregtech.common.items.IDMetaTool01.TURBINE_LARGE, gregtech.api.enums.Materials.HSSE);
        var heaters = new ArrayList<IGregTechTileEntity>();
        heaters.add(plant.boiler());
        var previous = plant.drum();
        for (int y = 0; y <= 2; y++) {
            var pipe = y == 0 ? plant.drum() : (MTEIntegratedFluidPipe) site.place(0, y, 2, 5770, ForgeDirection.UP).getMetaTileEntity();
            if (y != 0) h.assertTrue(previous.connect(ForgeDirection.UP) > 0, "Connected boiler manifold");
            for (var face : ForgeDirection.VALID_DIRECTIONS) {
                if (y == 0 && face != ForgeDirection.DOWN && face != ForgeDirection.SOUTH) continue;
                if (y > 0 && (face == ForgeDirection.DOWN || (face == ForgeDirection.UP && y != 2))) continue;
                heaters.add(site.place(face.offsetX, y + face.offsetY, 2 + face.offsetZ, 5776, face));
                h.assertTrue(pipe.connect(face) > 0, "Each boiler has a thermal contact");
            }
            previous = pipe;
        }
        var reheater = site.place(0, 1, 0, 5776, ForgeDirection.UP);
        h.assertTrue(plant.rig().steam().source().connect(ForgeDirection.UP) > 0, "Separate steam-line reheater");
        return new ColdStart(plant, heaters, reheater);
    }

    private static Plant place(GameTestHelper helper, RemoteMachineSite site, ForgeDirection boilerFace, int electricalTier) {
        helper.assertNotNull(GregTechAPI.METATILEENTITIES[5778], "Registered VFN vapor outlet");
        var rig = TurbineTests.place(helper, site, electricalTier);
        site.place(0, 0, 1, 5778, ForgeDirection.NORTH);
        var drum = (MTEIntegratedFluidPipe) site.place(0, 0, 2, 5770, ForgeDirection.NORTH).getMetaTileEntity();
        var feed = (MTEIntegratedFluidInjectorHatch) site.place(-1, 0, 2, 5773, ForgeDirection.EAST).getMetaTileEntity();
        var boiler = site.place(boilerFace.offsetX, boilerFace.offsetY, 2, 5776, boilerFace);
        helper.assertTrue(drum.connect(ForgeDirection.WEST) > 0 && drum.connect(boilerFace) > 0
            && drum.connect(ForgeDirection.NORTH) > 0 && rig.steam().source().connect(ForgeDirection.SOUTH) > 0,
            "Feedwater and vapor ports connected");
        return new Plant(rig, feed, drum, boiler);
    }

    record ReturnLoop(Plant plant, IGregTechTileEntity pump, IGregTechTileEntity power,
        MTEIntegratedFluidInputHatch suction, MTEIntegratedFluidOutputHatch delivery, IGregTechTileEntity reheater) {
        static final long DRUM_MICRO_KG = 1_400_000_000L, EXHAUST_MICRO_KG = 1_000_000_000L;

        void primeWarm() {
            pump.disableWorking();
            TurbineTests.installDiluteSteamRotor(plant.rig().turbine());
            var controller = (MTEIntegratedFluidLiquidPump) pump.getMetaTileEntity();
            controller.setMode(2);
            controller.setTarget(2);
            plant.feed().getNetwork().replaceCanonicalState(IFNCanonicalState.fromRaw("water", DRUM_MICRO_KG, DRUM_MICRO_KG * 145_000L));
            suction.getNetwork().replaceCanonicalState(IFNCanonicalState.fromRaw("water", EXHAUST_MICRO_KG, EXHAUST_MICRO_KG * -45_000L));
        }

        long powerHeaters() {
            long accepted = acceptMv(plant.boiler(), ForgeDirection.UP);
            if (plant.rig().steam().hot().getNetwork().getQuality() < 1) accepted += acceptMv(reheater, ForgeDirection.UP);
            return accepted;
        }

        void ready(GameTestHelper helper) {
            var drum = plant.feed().getNetwork();
            var dry = plant.rig().steam().hot().getNetwork();
            var exhaust = plant.rig().steam().cold().getNetwork();
            helper.assertTrue(drum != null && dry != null && exhaust != null && dry != exhaust && drum != exhaust && drum != dry
                && suction.getNetwork() == exhaust && delivery.getNetwork() == drum, "Pump connects exhaust back to the separate drum");
            var controller = (MTEIntegratedFluidLiquidPump) pump.getMetaTileEntity();
            helper.assertTrue(controller.checkStructure(true, pump), "Return pump forms with four hatches");
            controller.fixAllIssues();
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
                pumpSupply += 128 * loop.power().injectEnergyUnits(ForgeDirection.NORTH, 128, 1);
                if (p.rig().steam().hot().getNetwork().getQuality() < 1)
                    heaterSupply += 128 * loop.reheater().injectEnergyUnits(ForgeDirection.UP, 128, 1);
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
            long pump = pumpSupply - bufferEu(loop.power());
            long consumed = heaters + pump;
            long load = bufferEu(rig.load());
            long shaft = rig.shaftWork(load);
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
        return placeReturnLoop(helper, site, 3);
    }

    private static ReturnLoop placeReturnLoop(GameTestHelper helper, RemoteMachineSite site, int electricalTier) {
        var plant = place(helper, site, ForgeDirection.UP, electricalTier);
        var reheater = site.place(0, 1, 0, 5776, ForgeDirection.UP);
        helper.assertTrue(plant.rig().steam().source().connect(ForgeDirection.UP) > 0, "Reheater contacts the turbine inlet");
        HeatPumpTests.placeSteelCasings(site.world(), site.x() + 1, site.y() + 3, site.z());
        var pump = site.place(1, 3, 0, 5779, ForgeDirection.NORTH);
        site.assignOwner(pump);
        var power = site.place(0, 3, 0, 42, ForgeDirection.NORTH);
        site.place(2, 3, 0, 15497, ForgeDirection.NORTH);
        var suction = (MTEIntegratedFluidInputHatch) site.place(2, 2, 0, 5771, ForgeDirection.DOWN).getMetaTileEntity();
        var delivery = (MTEIntegratedFluidOutputHatch) site.place(1, 2, 2, 5772, ForgeDirection.DOWN).getMetaTileEntity();
        var riser = (MTEIntegratedFluidPipe) site.place(2, 1, 0, 5770, ForgeDirection.UP).getMetaTileEntity();
        var downpipe = (MTEIntegratedFluidPipe) site.place(1, 1, 2, 5770, ForgeDirection.UP).getMetaTileEntity();
        var elbow = (MTEIntegratedFluidPipe) site.place(1, 0, 2, 5770, ForgeDirection.UP).getMetaTileEntity();
        helper.assertTrue(riser.connect(ForgeDirection.UP) > 0 && riser.connect(ForgeDirection.DOWN) > 0
            && downpipe.connect(ForgeDirection.UP) > 0 && downpipe.connect(ForgeDirection.DOWN) > 0
            && elbow.connect(ForgeDirection.WEST) > 0, "Both pump hatches piped into the loop");
        return new ReturnLoop(plant, pump, power, suction, delivery, reheater);
    }

    @GameTest(template = "return_plant", timeoutTicks = 10000)
    public static void pumpedCondensateReturnsToTheBoiler(GameTestHelper h) {
        var loop = placeReturnLoop(h);
        var rig = loop.plant().rig();
        var boiler = loop.plant().boiler();
        var feed = loop.plant().feed();
        var dry = rig.steam().hot();
        var exhaust = rig.steam().cold();
        var drumMeter = new AmbientTests.AmbientMeter(h, feed::getNetwork);
        var dryMeter = new AmbientTests.AmbientMeter(h, dry::getNetwork);
        var exhaustMeter = new AmbientTests.AmbientMeter(h, exhaust::getNetwork);
        java.util.function.LongSupplier retained = () -> drumMeter.withoutAmbient(feed.getNetwork())
            + dryMeter.withoutAmbient(dry.getNetwork()) + exhaustMeter.withoutAmbient(exhaust.getNetwork());
        long drumMass = ReturnLoop.DRUM_MICRO_KG, coldMass = ReturnLoop.EXHAUST_MICRO_KG;
        long[] initial = new long[1], heatSupply = new long[1], pumpSupply = new long[1], loadEu = new long[1];
        long[] beforeReturn = new long[3];
        boolean[] running = new boolean[1];
        int[] ticks = new int[1];
        h.onEachTick("supply machines and meter turbine output", () -> {
            if (!running[0]) return;
            heatSupply[0] += loop.powerHeaters();
            pumpSupply[0] += acceptMv(loop.power(), ForgeDirection.NORTH);
            long received = rig.load().getStoredEU();
            if (received > 0 && rig.load().decreaseStoredEnergyUnits(received, false)) loadEu[0] += received;
            if (++ticks[0] % 200 == 0) h.recordDiagnostic("t=" + ticks[0] + " drum=" + feed.getNetwork().getPressure()
                + " bar, inlet=" + dry.getNetwork().getPressure() + " bar q=" + dry.getNetwork().getQuality()
                + ", exhaust=" + exhaust.getNetwork().getAmountQ() + " microkg, load=" + loadEu[0] + " EU");
        });
        h.startSequence().thenWaitUntil(() -> loop.ready(h)).thenExecute(() -> {
            loop.primeWarm();
            h.assertTrue(feed.getNetwork().getQuality() > 0 && feed.getNetwork().getQuality() < 1, "Wet boiler at operating temperature");
            h.assertEquals(0L, dry.getNetwork().getAmountQ(), "All inlet steam must pass through the vapor outlet");
            initial[0] = retained.getAsLong();
            running[0] = true;
        }).thenWaitUntil(() -> h.assertTrue(loadEu[0] > 0, "Steam reaches the rotor and powers the load: inlet "
            + dry.getNetwork().getAmountQ() + " microkg, " + dry.getNetwork().getPressurePa() + " Pa, quality " + dry.getNetwork().getQuality()
            + ", exhaust " + exhaust.getNetwork().getPressurePa() + " Pa, dynamo " + rig.dynamo().getStoredEU() + " EU"))
            .thenWaitUntil(() -> h.assertTrue(exhaust.getNetwork().getAmountQ() >= coldMass + 1_000_000,
                "Wait for the first kilogram of new turbine exhaust"))
            .thenExecute(() -> rig.turbine().disableWorking()).thenIdle(22).thenExecute(() -> {
                beforeReturn[0] = exhaust.getNetwork().getAmountQ();
                beforeReturn[1] = feed.getNetwork().getAmountQ() + dry.getNetwork().getAmountQ();
                beforeReturn[2] = pumpSupply[0] - loop.power().getStoredEU();
                h.assertEquals(0L, beforeReturn[2], "Closed return has not consumed power");
                h.assertTrue(feed.getNetwork().getPressurePa() > exhaust.getNetwork().getPressurePa(), "Return is uphill");
                loop.pump().enableWorking();
            }).thenWaitUntil(() -> h.assertTrue(exhaust.getNetwork().getAmountQ() < beforeReturn[0], "First return stroke"))
            .thenExecute(() -> {
                h.assertEquals(1_000_000L, beforeReturn[0] - exhaust.getNetwork().getAmountQ(), "Exactly one liquid portion returned");
                h.assertEquals(beforeReturn[1] + 1_000_000L, feed.getNetwork().getAmountQ() + dry.getNetwork().getAmountQ(),
                    "The returned water reached the boiler circuit");
                h.assertTrue(pumpSupply[0] > loop.power().getStoredEU(), "Return consumed electrical work");
                beforeReturn[2] = loadEu[0];
                loop.pump().disableWorking();
                rig.turbine().enableWorking();
            }).thenWaitUntil(() -> h.assertTrue(exhaust.getNetwork().getAmountQ() >= beforeReturn[0] && loadEu[0] > beforeReturn[2],
                "A second kilogram reached the exhaust while the turbine supplied the load"))
            .thenExecute(() -> {
                rig.turbine().disableWorking();
                beforeReturn[0] = exhaust.getNetwork().getAmountQ();
                loop.pump().enableWorking();
            }).thenWaitUntil(() -> h.assertEquals(beforeReturn[0] - 1_000_000, exhaust.getNetwork().getAmountQ(), "Second full return"))
            .thenExecute(() -> {
                long heaters = heatSupply[0] - boiler.getStoredEU() - loop.reheater().getStoredEU();
                long pump = pumpSupply[0] - loop.power().getStoredEU();
                long load = loadEu[0] + rig.load().getStoredEU();
                long shaft = rig.shaftWork(load);
                h.assertEquals(drumMass + coldMass, feed.getNetwork().getAmountQ() + dry.getNetwork().getAmountQ()
                    + exhaust.getNetwork().getAmountQ(), "Sealed plant mass after repeated returns");
                h.assertEquals(initial[0] + (heaters + pump - shaft) * 1_000_000L, retained.getAsLong(),
                    "Whole-loop balance including both heaters, pump, turbine and ambient");
                h.recordDiagnostic("Warm-start loop: " + ticks[0] + " ticks, heaters=" + heaters + " EU, pump=" + pump
                    + " EU, load=" + load + " EU, shaft=" + shaft + " EU, accumulated exhaust="
                    + (beforeReturn[0] - coldMass + 1_000_000) + " microkg");
            }).thenSucceed();
    }

    private static long acceptMv(IGregTechTileEntity tile, ForgeDirection face) {
        return tile.getEUCapacity() - tile.getStoredEU() < 128 ? 0 : 128 * tile.injectEnergyUnits(face, 128, 1);
    }

    record CondensingPlant(ReturnLoop loop, List<IGregTechTileEntity> heaters, MTEIntegratedFluidInjectorHatch coolant) {
        long powerBoiler() {
            long supply = 0;
            for (var heater : heaters) supply += acceptMv(heater, heater.getFrontFacing());
            return supply;
        }

        void ready(GameTestHelper h) {
            loop.ready(h);
            h.assertTrue(coolant.getNetwork() != null && coolant.getNetwork() != loop.suction().getNetwork(), "Independent cooling water");
        }

        void fill(GameTestHelper h) {
            h.assertEquals(16, loop.plant().feed().fill(new FluidStack(FluidRegistry.WATER, 16), true), "16 kg of ambient feedwater");
            h.assertEquals(1200, coolant.fill(new FluidStack(FluidRegistry.WATER, 1200), true), "Separate ambient cooling water");
            h.assertEquals(0L, loop.plant().rig().steam().hot().getNetwork().getAmountQ() + loop.suction().getNetwork().getAmountQ(),
                "Empty steam and exhaust");
        }
    }

    static CondensingPlant placeCondensingPlant(GameTestHelper h) {
        var site = RemoteMachineSite.at(h, "source");
        var loop = placeReturnLoop(h, site, 4);
        var plant = loop.plant();
        var rig = plant.rig();
        TurbineTests.installRotor(rig.turbine(), gregtech.common.items.IDMetaTool01.TURBINE_LARGE, gregtech.api.enums.Materials.HSSE);
        for (int z : new int[] { 0, 2 }) {
            var junction = (MTEIntegratedFluidPipe) site.place(0, -1, z, 5770, ForgeDirection.UP).getMetaTileEntity();
            var branch = (MTEIntegratedFluidPipe) site.place(-1, -1, z, 5770, ForgeDirection.EAST).getMetaTileEntity();
            h.assertTrue(junction.connect(ForgeDirection.UP) > 0 && branch.connect(ForgeDirection.EAST) > 0, "Steam header connected");
        }
        for (int x = -1; x <= 0; x++) {
            site.place(x, -1, 1, 5778, ForgeDirection.NORTH);
            var steam = (MTEIntegratedFluidPipe)site.tile(x, -1, 0).getMetaTileEntity();
            var water = (MTEIntegratedFluidPipe)site.tile(x, -1, 2).getMetaTileEntity();
            h.assertTrue(steam.connect(ForgeDirection.SOUTH) > 0 && water.connect(ForgeDirection.NORTH) > 0, "Parallel vapor outlet");
        }
        var heaters = new ArrayList<IGregTechTileEntity>();
        heaters.add(plant.boiler());
        var drumPipe = plant.drum();
        for (int z = 3; z < 13; z++) {
            var next = (MTEIntegratedFluidPipe) site.place(0, 0, z, 5770, ForgeDirection.NORTH).getMetaTileEntity();
            h.assertTrue(drumPipe.connect(ForgeDirection.SOUTH) > 0, "Boiler header connected");
            for (var side : new ForgeDirection[] { ForgeDirection.UP, ForgeDirection.DOWN, ForgeDirection.EAST, ForgeDirection.WEST }) {
                heaters.add(site.place(side.offsetX, side.offsetY, z, 5776, side));
                h.assertTrue(next.connect(side) > 0, "Heating surface connected");
            }
            drumPipe = next;
        }
        var exhaustPipe = rig.steam().outlet();
        MTEIntegratedFluidPipe coolantPipe = null;
        for (int z = 1; z <= 20; z++) {
            var hot = (MTEIntegratedFluidPipe) site.place(2, 0, z, 5770, ForgeDirection.EAST).getMetaTileEntity();
            var cold = (MTEIntegratedFluidPipe) site.place(4, 0, z, 5770, ForgeDirection.WEST).getMetaTileEntity();
            site.place(3, 0, z, 5775, ForgeDirection.EAST);
            h.assertTrue(exhaustPipe.connect(ForgeDirection.SOUTH) > 0 && (coolantPipe == null || coolantPipe.connect(ForgeDirection.SOUTH) > 0)
                && hot.connect(ForgeDirection.EAST) > 0 && cold.connect(ForgeDirection.WEST) > 0, "Condenser surface connected");
            if (z >= 4) {
                for (var side : new ForgeDirection[] { ForgeDirection.UP, ForgeDirection.DOWN }) {
                    site.place(2, side.offsetY, z, 5775, side);
                    var jacket = (MTEIntegratedFluidPipe)site.place(2, 2 * side.offsetY, z, 5770, side.getOpposite()).getMetaTileEntity();
                    h.assertTrue(hot.connect(side) > 0 && jacket.connect(side.getOpposite()) > 0, "Jacket heat contact");
                    if (z > 4) h.assertTrue(jacket.connect(ForgeDirection.NORTH) > 0, "Continuous cooling header");
                    if (z == 20) {
                        var link = (MTEIntegratedFluidPipe) site.place(3, 2 * side.offsetY, z, 5770, ForgeDirection.WEST).getMetaTileEntity();
                        var bend = (MTEIntegratedFluidPipe) site.place(4, 2 * side.offsetY, z, 5770, ForgeDirection.WEST).getMetaTileEntity();
                        var end = (MTEIntegratedFluidPipe) site.place(4, side.offsetY, z, 5770, side).getMetaTileEntity();
                        h.assertTrue(link.connect(ForgeDirection.WEST) > 0 && bend.connect(ForgeDirection.WEST) > 0
                            && end.connect(side) > 0 && end.connect(side.getOpposite()) > 0, "Jacket returns to the cooling circuit");
                    }
                }
            }
            exhaustPipe = hot;
            coolantPipe = cold;
        }
        var coolant = (MTEIntegratedFluidInjectorHatch) site.place(5, 0, 20, 5773, ForgeDirection.WEST).getMetaTileEntity();
        h.assertTrue(coolantPipe.connect(ForgeDirection.EAST) > 0, "Cooling water port connected");
        return new CondensingPlant(loop, heaters, coolant);
    }

    @GameTest(template = "condensing_plant", timeoutTicks = 9900)
    public static void coldPlantReturnsItsOwnCondensate(GameTestHelper h) {
        var installation = placeCondensingPlant(h);
        var loop = installation.loop();
        var plant = loop.plant();
        var rig = plant.rig();
        var coolant = installation.coolant();
        var drum = new AmbientTests.AmbientMeter(h, plant.feed()::getNetwork);
        var inlet = new AmbientTests.AmbientMeter(h, rig.steam().hot()::getNetwork);
        var exhaust = new AmbientTests.AmbientMeter(h, loop.suction()::getNetwork);
        var sink = new AmbientTests.AmbientMeter(h, coolant::getNetwork);
        java.util.function.LongSupplier retained = () -> drum.withoutAmbient(plant.feed().getNetwork())
            + inlet.withoutAmbient(rig.steam().hot().getNetwork()) + exhaust.withoutAmbient(loop.suction().getNetwork())
            + sink.withoutAmbient(coolant.getNetwork());
        class Reading {
            boolean running;
            int ticks;
            long initial, sinkInitial, heat, pump, load, firstReturnWork, previousExhaust, returned;
            void tick() {
                if (!running) return;
                heat += installation.powerBoiler();
                pump += acceptMv(loop.power(), ForgeDirection.NORTH);
                long received = rig.load().getStoredEU();
                if (received > 0 && rig.load().decreaseStoredEnergyUnits(received, false)) load += received;
                var out = loop.suction().getNetwork();
                long loss = previousExhaust - out.getAmountQ();
                if (loss > 0) {
                    returned += loss;
                    if (firstReturnWork == 0) {
                        firstReturnWork = rig.shaftWork(load);
                        h.recordDiagnostic("First condensate return: t=" + ticks + ", net exhaust decrease=" + loss
                            + " microkg, pump=" + (pump - loop.power().getStoredEU()) + " EU, shaft=" + firstReturnWork);
                    }
                }
                previousExhaust = out.getAmountQ();
                if (++ticks % 500 == 0) h.recordDiagnostic("t=" + ticks + " drum=" + plant.feed().getNetwork().getPressure()
                    + " bar, inlet=" + rig.steam().hot().getNetwork().getAmountQ() + " microkg, inlet q=" + rig.steam().hot().getNetwork().getQuality()
                    + ", exhaust=" + out.getAmountQ() + " microkg, exhaust q=" + out.getQuality() + ", sink K=" + coolant.getNetwork().getTemperature()
                    + ", pump=" + ((MTEIntegratedFluidLiquidPump)loop.pump().getMetaTileEntity()).status() + ", delivered=" + load);
            }
        }
        var meter = new Reading();
        h.onEachTick("supply energy and observe autonomous fluid transfers", meter::tick);
        h.startSequence().thenWaitUntil(() -> installation.ready(h)).thenExecute(() -> {
            var pump = (MTEIntegratedFluidLiquidPump)loop.pump().getMetaTileEntity();
            pump.setMode(2);
            pump.setTarget(2);
            installation.fill(h);
            meter.initial = retained.getAsLong();
            meter.sinkInitial = sink.withoutAmbient(coolant.getNetwork());
            meter.running = true;
        }).thenWaitUntil(() -> h.assertTrue(meter.returned > 0 && meter.firstReturnWork > 0
            && rig.shaftWork(meter.load) > meter.firstReturnWork && meter.load > meter.firstReturnWork,
            "Fresh condensate returned and the load received electricity from a subsequent stroke"))
            .thenExecute(() -> {
                meter.running = false;
                long heating = meter.heat - installation.heaters().stream().mapToLong(IGregTechTileEntity::getStoredEU).sum();
                long pumping = meter.pump - loop.power().getStoredEU();
                long shaft = rig.shaftWork(meter.load);
                long sinkGain = sink.withoutAmbient(coolant.getNetwork()) - meter.sinkInitial;
                h.assertTrue(pumping > 0 && sinkGain > 0, "Paid pump work and heat rejected into cooling water");
                h.assertEquals(16_000_000L, plant.feed().getNetwork().getAmountQ() + rig.steam().hot().getNetwork().getAmountQ()
                    + loop.suction().getNetwork().getAmountQ(), "All process water retained");
                h.assertEquals(1_200_000_000L, coolant.getNetwork().getAmountQ(), "No coolant mixed into the return");
                h.assertEquals(meter.initial + (heating + pumping - shaft) * 1_000_000L, retained.getAsLong(), "All four chamber ledgers balance");
                h.recordDiagnostic("Cold autonomous loop: ticks=" + meter.ticks + ", net returned microkg=" + meter.returned + ", boiler EU="
                    + heating + ", pump EU=" + pumping + ", shaft EU=" + shaft + ", load EU=" + meter.load + ", heat sink microEU=" + sinkGain);
            }).thenSucceed();
    }

    @GameTest(template = "turbine", timeoutTicks = 9500)
    public static void wetBoilerPowersDryTurbine(GameTestHelper h) {
        var start = coldStart(h);
        var plant = start.plant();
        var rig = plant.rig();
        var drumHeat = new AmbientTests.AmbientMeter(h, plant.feed()::getNetwork);
        var inletHeat = new AmbientTests.AmbientMeter(h, rig.steam().hot()::getNetwork);
        var exhaustHeat = new AmbientTests.AmbientMeter(h, rig.steam().cold()::getNetwork);
        long[] initial = new long[1], supply = new long[2];
        boolean[] powered = new boolean[1];
        int[] ticks = { 0 };
        h.onEachTick("supply and meter cold-start heat", () -> {
            if (!powered[0]) return;
            supply[0] += start.powerDrum();
            supply[1] += start.powerReheater();
            if (++ticks[0] % 500 == 0) h.recordDiagnostic("t=" + ticks[0] + " drum=" + plant.feed().getNetwork().getPressurePa()
                + " Pa, inlet=" + rig.steam().hot().getNetwork().getAmountQ() + " microkg, q=" + rig.steam().hot().getNetwork().getQuality());
        });
        h.startSequence().thenWaitUntil(() -> plant.ready(h)).thenExecute(() -> start.fill(h)).thenIdle(40).thenExecute(() -> {
            h.assertEquals(0L, rig.load().getStoredEU() + rig.dynamo().getStoredEU(), "No cold-start electricity without heat");
            initial[0] = drumHeat.withoutAmbient(plant.feed().getNetwork()) + inletHeat.withoutAmbient(rig.steam().hot().getNetwork())
                + exhaustHeat.withoutAmbient(rig.steam().cold().getNetwork());
            powered[0] = true;
        }).thenWaitUntil(() -> h.assertTrue(rig.load().getStoredEU() >= 512, "First HV packet: inlet "
            + rig.steam().hot().getNetwork().getAmountQ() + " microkg, quality " + rig.steam().hot().getNetwork().getQuality()))
            .thenExecute(() -> {
                powered[0] = false;
                var drum = plant.feed().getNetwork();
                var inlet = rig.steam().hot().getNetwork();
                var exhaust = rig.steam().cold().getNetwork();
                long inventory = ColdStart.WATER_KG * 1_000_000L;
                h.assertTrue(drum.getQuality() > 0 && drum.getAmountQ() * (1 - drum.getQuality()) > inventory * 0.9,
                    "The drum is still boiling and retains over 90% of the original mass as liquid");
                h.assertEquals(1.0, inlet.getQuality(), "Dry steam at the rotor");
                h.assertTrue(exhaust.getAmountQ() > 0 && exhaust.getAmountQ() % 6250 == 0, "Whole steam portions through turbine");
                h.assertEquals(inventory, drum.getAmountQ() + inlet.getAmountQ() + exhaust.getAmountQ(), "All cold feedwater accounted");
                long boiler = supply[0] - start.drumBuffer(), reheat = supply[1] - start.reheater().getStoredEU();
                long load = rig.load().getStoredEU(), shaft = rig.shaftWork(load);
                long stored = drumHeat.withoutAmbient(drum) + inletHeat.withoutAmbient(inlet) + exhaustHeat.withoutAmbient(exhaust);
                h.assertEquals(initial[0] + (boiler + reheat - shaft) * 1_000_000L, stored,
                    "Boiler and line heat, environmental exchange, dynamo and load balance");
                h.recordDiagnostic("Cold start: ticks=" + ticks[0] + ", boiler=" + boiler + " EU, reheat=" + reheat
                    + " EU, drum=" + drum.getAmountQ() + " microkg, inlet=" + inlet.getAmountQ() + " microkg, exhaust="
                    + exhaust.getAmountQ() + " microkg, shaft=" + shaft + " EU, HV load=" + load + " EU");
            }).thenSucceed();
    }
}
