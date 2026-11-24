package gregtech.api.metatileentity.implementations.integratedfluid;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import gregtech.api.metatileentity.implementations.integratedfluid.state.IFNCanonicalState;
import gregtech.api.metatileentity.implementations.integratedfluid.thermodynamics.ClosedFluidChamber;
import gregtech.api.metatileentity.implementations.integratedfluid.thermodynamics.PureFluid;
import gregtech.common.tileentities.machines.multi.heatpump.HeatPumpMachineProcessState;
import gregtech.common.tileentities.machines.multi.heatpump.HeatPumpMachineProcessState.Intake;

class HeatPumpOperatingPressureTest {

    private static Stream<Arguments> normal() {
        return cases(0.01, 0.05, 0.1, 0.5, 1, 2, 5, 10, 20);
    }

    private static Stream<Arguments> paired() {
        return cases(0.05, 0.1, 0.5, 1, 2, 5, 10);
    }

    private static Stream<Arguments> cases(double... pressures) {
        return Arrays.stream(pressures).boxed().flatMap(bar -> Stream.of(Arguments.of(bar, false), Arguments.of(bar, true)));
    }

    @ParameterizedTest(name = "normal: {0} bar, heating={1}")
    @MethodSource("normal")
    void conditionsTheChargeThenDischargesAgainstTheActualReceiver(double bar, boolean heating) {
        double kelvin = bar > 10 ? 495 : 475;
        var feed = circuit(bar, kelvin);
        var outlet = circuit(bar * 0.25, kelvin);
        var pump = primed(bar, kelvin, false);
        var before = saved(pump);
        long totalMass = feed.getAmountQ() + outlet.getAmountQ() + before.getLong("pumpMicroKg");
        long energyBefore = energy(feed) + energy(outlet) + before.getLong("pumpMicroEu");
        long feedBefore = feed.getAmountQ();
        double target = heating ? 499 : 455;
        boolean filled = pump.fill(new Intake(feed, null, 10, 1, false), target, 0, 0);
        if (steamPortions(bar, kelvin, 10) == 0) {
            assertFalse(filled, "The inlet volume cannot supply one steam portion");
            assertEquals(feedBefore, feed.getAmountQ());
            assertEquals(before, saved(pump));
            assertEquals(energyBefore, energy(feed) + energy(outlet) + before.getLong("pumpMicroEu"));
            return;
        }
        assertTrue(filled);
        assertTrue(feed.getAmountQ() < feedBefore);
        assertTrue(feed.getPressurePa() + 1e-6 >= workingPa(saved(pump), false));
        assertEquals(energyBefore, energy(feed) + energy(outlet) + saved(pump).getLong("pumpMicroEu"));
        double admittedK = pump.temperature();
        assertTrue(heating ? admittedK < target : admittedK > target, "Expected working temperature on the intake side of target: " + admittedK);
        long[] paid = { 0 };
        assertTrue(pump.advance(300, 2_000_000_000L, eu -> { paid[0] += eu; return true; }) > 0);
        assertTrue(paid[0] > 0 && pump.ready());
        assertEquals(target, pump.temperature(), 0.001);
        long readyEnergy = energy(feed) + energy(outlet) + saved(pump).getLong("pumpMicroEu");
        assertTrue(heating ? readyEnergy > energyBefore : readyEnergy < energyBefore);
        long outletBefore = outlet.getAmountQ();
        assertTrue(pump.discharge(outlet, null));
        assertTrue(outlet.getAmountQ() > outletBefore);
        var after = saved(pump);
        assertTrue(workingPa(after, false) + 1e-6 >= outlet.getPressurePa());
        assertEquals(totalMass, feed.getAmountQ() + outlet.getAmountQ() + after.getLong("pumpMicroKg"));
        assertEquals(readyEnergy, energy(feed) + energy(outlet) + after.getLong("pumpMicroEu"));
    }

    @ParameterizedTest(name = "paired: {0} bar, independent feeds={1}")
    @MethodSource("paired")
    void metersBothColorsAndKeepsTheBlockedCharge(double bar, boolean independent) {
        double kelvin = 475;
        var redFeed = circuit(bar, kelvin);
        var blueFeed = independent ? circuit(bar * 0.9, kelvin) : null;
        var redOut = circuit(0.25 * bar, kelvin);
        var blueOut = circuit(1.4 * bar, kelvin);
        var pump = primed(bar, kelvin, true);
        long feedMass = redFeed.getAmountQ() + (independent ? blueFeed.getAmountQ() : 0);
        long feedsEnergy = energy(redFeed) + (independent ? energy(blueFeed) : 0);
        var before = saved(pump);
        long redBefore = before.getLong("pumpMicroKg"), blueBefore = before.getLong("pumpPartnerMicroKg");
        long portions = steamPortions(bar, kelvin, 50);
        boolean filled = pump.fill(new Intake(redFeed, blueFeed, 50, independent ? 1 : 0.35, false), 490, 0, 0);
        if (independent ? steamPortions(bar * 0.9, kelvin, 50) == 0 : portions * 35 / 100 == 0) {
            assertFalse(filled, "Both branches must be able to admit a whole portion");
            assertEquals(feedMass, redFeed.getAmountQ() + (independent ? blueFeed.getAmountQ() : 0));
            assertEquals(feedsEnergy, energy(redFeed) + (independent ? energy(blueFeed) : 0));
            assertEquals(before, saved(pump));
            return;
        }
        assertTrue(filled);
        var admitted = saved(pump);
        long redIn = admitted.getLong("pumpMicroKg") - redBefore, blueIn = admitted.getLong("pumpPartnerMicroKg") - blueBefore;
        assertTrue(redIn > 0 && blueIn > 0);
        assertTrue(redFeed.getPressurePa() + 1e-6 >= workingPa(admitted, false));
        assertTrue((independent ? blueFeed : redFeed).getPressurePa() + 1e-6 >= workingPa(admitted, true));
        assertEquals(feedMass, redFeed.getAmountQ() + (independent ? blueFeed.getAmountQ() : 0) + redIn + blueIn);
        assertEquals(0, redIn % 6250);
        assertEquals(0, blueIn % 6250);
        if (!independent) assertEquals(((redIn + blueIn) / 6250 * 35 / 100) * 6250, redIn);
        assertEquals(feedsEnergy + sumEnergy(before), energy(redFeed) + (independent ? energy(blueFeed) : 0) + sumEnergy(admitted));
        long[] paid = { 0 };
        pump.advance(300, 2_000_000L, eu -> { paid[0] += eu; return true; });
        assertTrue(pump.ready() && paid[0] > 0, "Both streams must complete the paid stroke");
        assertEquals(490, pump.temperature(), 0.001);
        var ready = saved(pump);
        assertEquals(sumEnergy(admitted) + paid[0] * 1_000_000L, sumEnergy(ready));
        long oldRed = redOut.getAmountQ(), oldBlue = blueOut.getAmountQ(), redEnergy = energy(redOut);
        assertTrue(pump.discharge(redOut, blueOut));
        assertTrue(pump.ready() && redOut.getAmountQ() > oldRed);
        assertEquals(oldBlue, blueOut.getAmountQ());
        var waiting = saved(pump);
        assertTrue(workingPa(waiting, false) + 1e-6 >= redOut.getPressurePa());
        assertEquals(ready.getLong("pumpMicroKg"), waiting.getLong("pumpMicroKg") + redOut.getAmountQ() - oldRed);
        assertEquals(ready.getLong("pumpMicroEu"), waiting.getLong("pumpMicroEu") + energy(redOut) - redEnergy);
        assertEquals(ready.getLong("pumpPartnerMicroEu"), waiting.getLong("pumpPartnerMicroEu"));
        assertFalse(pump.discharge(redOut, blueOut));
        var released = circuit(0.25 * bar, kelvin);
        long oldReceiver = released.getAmountQ(), oldEnergy = energy(released);
        assertTrue(pump.discharge(redOut, released));
        assertFalse(pump.active());
        var done = saved(pump);
        assertTrue(workingPa(done, true) + 1e-6 >= released.getPressurePa());
        assertEquals(waiting.getLong("pumpMicroKg"), done.getLong("pumpMicroKg"));
        assertEquals(ready.getLong("pumpPartnerMicroKg"), done.getLong("pumpPartnerMicroKg") + released.getAmountQ() - oldReceiver);
        assertEquals(ready.getLong("pumpPartnerMicroEu"), done.getLong("pumpPartnerMicroEu") + energy(released) - oldEnergy);
    }

    @Test
    void pairedCoolingStopsPayingWhenThePartnerCannotTakeMoreHeat() {
        var source = circuit(1, 950);
        var other = circuit(0.9, 950);
        var pump = primed(1, 950, true);
        assertTrue(pump.fill(new Intake(source, other, 100, 1, false), 940, 0, 0));
        var initial = saved(pump);
        assertEquals(0, pump.advance(300, 0, eu -> fail("Empty power supply billed")));
        assertFalse(pump.awaitingTemperatureChange());
        assertEquals(0, pump.advance(300, 2_000_000, eu -> false));
        assertEquals(initial, saved(pump));
        long[] paid = { 0 };
        for (int tick = 0; tick < 20; tick++) {
            pump.advance(300, 2_000_000, eu -> { paid[0] += eu; return true; });
        }
        var stopped = saved(pump);
        assertTrue(paid[0] > 0 && !pump.ready(), "paid=" + paid[0] + ", ready=" + pump.ready() + ", K=" + pump.temperature());
        assertTrue(pump.temperature() > 940);
        assertEquals(sumEnergy(initial) + paid[0] * 1_000_000, sumEnergy(stopped));
        assertEquals(0, pump.advance(300, 2_000_000, eu -> fail("Stalled pair billed again")));
        assertEquals(stopped, saved(pump));
        assertFalse(pump.discharge(circuit(0.25, 475), circuit(0.25, 475)));
        assertTrue(pump.awaitingTemperatureChange());
        var restored = new HeatPumpMachineProcessState();
        restored.load(stopped);
        assertTrue(restored.awaitingTemperatureChange());
        assertFalse(restored.retargetStalledCharge(940, 0, 0));
        assertFalse(restored.retargetStalledCharge(1001, 0, 0));
        double target = restored.temperature() + 1;
        assertTrue(restored.retargetStalledCharge(target, 0, 0));
        assertEquals(sumEnergy(stopped), sumEnergy(saved(restored)));
        assertFalse(restored.ready());
        long work = restored.advance(300, 2_000_000, eu -> true);
        assertTrue(restored.ready() && work > 0);
        assertEquals(target, restored.temperature(), 0.001);
        assertEquals(sumEnergy(stopped) + work * 1_000_000, sumEnergy(saved(restored)));
    }

    @Test
    void hotSteamAdmissionIsLimitedBeforeItOverheatsTheWorkingChamber() {
        var feed = circuit(20, 990);
        var smallPacket = primed(20, 990, false);
        var largePacket = primed(20, 990, false);
        var otherFeed = circuit(20, 990);
        assertTrue(smallPacket.fill(new Intake(feed, null, 10, 1, false), 999, 0, 0));
        assertTrue(largePacket.fill(new Intake(otherFeed, null, 100, 1, false), 999, 0, 0));
        assertTrue(smallPacket.temperature() < 999);
        assertTrue(largePacket.temperature() <= 1000 && largePacket.temperature() > 999);
        assertTrue(saved(largePacket).getLong("pumpMicroKg") > saved(smallPacket).getLong("pumpMicroKg"));
        assertEquals(feed.getAmountQ() + saved(smallPacket).getLong("pumpMicroKg"), otherFeed.getAmountQ() + saved(largePacket).getLong("pumpMicroKg"));
        assertEquals(energy(feed) + saved(smallPacket).getLong("pumpMicroEu"), energy(otherFeed) + saved(largePacket).getLong("pumpMicroEu"));
    }

    private static Stream<Arguments> repeatedStrokes() {
        var random = new Random(0x5A17C0FFEEBABEL);
        return IntStream.range(0, 40).mapToObj(i -> Arguments.of(i, 0.05 * Math.pow(120, random.nextDouble()),
            460 + 15 * random.nextDouble(), i % 2 == 0 ? 490 : 445, 5 + random.nextInt(70)));
    }

    @ParameterizedTest(name = "repeated stroke {0}: {1} bar, {2} K, target {3} K, {4} L")
    @MethodSource("repeatedStrokes")
    void successivePaidStrokesLeaveNoUnbookedMassOrHeat(int index, double bar, double kelvin, int target, int liters) {
        var feed = circuit(bar, kelvin);
        var receiver = circuit(0.25 * bar, kelvin);
        var pump = primed(bar, kelvin, false);
        long mass = feed.getAmountQ() + receiver.getAmountQ() + saved(pump).getLong("pumpMicroKg");
        for (int stroke = 0; stroke < 8; stroke++) {
            long energy = energy(feed) + energy(receiver) + saved(pump).getLong("pumpMicroEu");
            long inlet = feed.getAmountQ(), outlet = receiver.getAmountQ();
            var intake = new Intake(feed, null, liters, 1, false);
            var before = saved(pump);
            boolean filled = pump.fill(intake, target, 0, 0);
            if (inlet * liters / 10000 < 6250) {
                assertFalse(filled, "Not enough steam in the swept inlet volume");
                assertFalse(pump.fill(intake, target, 0, 0), "Waiting cannot bank fractional steam");
                assertEquals(before, saved(pump));
                assertEquals(inlet, feed.getAmountQ());
                assertEquals(energy, energy(feed) + energy(receiver) + before.getLong("pumpMicroEu"));
                break;
            }
            assertTrue(filled, "Expected intake at stroke " + stroke);
            assertTrue(feed.getAmountQ() < inlet);
            long admittedEnergy = saved(pump).getLong("pumpMicroEu");
            assertEquals(energy, energy(feed) + energy(receiver) + admittedEnergy);
            long[] paid = { 0 };
            double start = pump.temperature();
            long work = pump.advance(300, 100_000_000L, eu -> { paid[0] += eu; return true; });
            assertEquals(paid[0], work);
            assertTrue(pump.ready());
            assertEquals(target, pump.temperature(), 0.001);
            long heat = saved(pump).getLong("pumpMicroEu") - admittedEnergy;
            assertTrue(heat == 0 || (heat > 0) == (target > start));
            assertTrue(heat == 0 || paid[0] > 0);
            if (!pump.discharge(receiver, null)) {
                var retained = saved(pump);
                double kg = retained.getLong("pumpMicroKg") * 1e-6;
                double head = kg * 461.5 * target;
                // An adiabatic 6.25 g draw lowers pressure in the 1000 L working chamber.
                // The same transported energy raises pressure in the 10000 L dry receiver
                double nextHead = head * Math.pow(1 - 0.00625 / kg, 2010 / (2010 - 461.5));
                assertTrue(receiver.getTemperature() > 499.99 || receiver.getPressurePa() + (head - nextHead) / 10 >= nextHead - 1,
                    "Unexplained refusal at stroke " + stroke + ": receiver K=" + receiver.getTemperature()
                        + ", Pa=" + receiver.getPressurePa() + ", working Pa=" + head);
                assertTrue(pump.ready());
                assertEquals(outlet, receiver.getAmountQ());
                assertEquals(energy + heat, energy(feed) + energy(receiver) + retained.getLong("pumpMicroEu"));
                assertFalse(pump.discharge(receiver, null));
                assertEquals(retained, saved(pump));
                long beforeCooling = energy(receiver);
                receiver.exchangeThroughRadiator(300, 1e8, 445);
                long removed = beforeCooling - energy(receiver);
                assertTrue(removed > 0);
                energy -= removed;
                assertTrue(pump.discharge(receiver, null), "Cooling the receiver releases the same paid charge");
            }
            assertTrue(receiver.getAmountQ() > outlet);
            assertEquals(mass, feed.getAmountQ() + receiver.getAmountQ() + saved(pump).getLong("pumpMicroKg"));
            assertEquals(energy + heat, energy(feed) + energy(receiver) + saved(pump).getLong("pumpMicroEu"));
        }
    }

    private static double workingPa(NBTTagCompound state, boolean partner) {
        String prefix = partner ? "pumpPartner" : "pump";
        return ClosedFluidChamber.of(PureFluid.water(), BigDecimal.valueOf(state.getLong(prefix + "MicroKg"), 6),
            1000, BigDecimal.valueOf(state.getLong(prefix + "MicroEu"), 6)).pressurePa();
    }

    private static long sumEnergy(NBTTagCompound state) {
        return state.getLong("pumpMicroEu") + state.getLong("pumpPartnerMicroEu");
    }

    private static long energy(IntegratedFluidNetwork network) {
        return network.getCanonicalState().totalEnergy().rawUnits();
    }

    private static NBTTagCompound saved(HeatPumpMachineProcessState pump) {
        var tag = new NBTTagCompound();
        pump.save(tag);
        return tag;
    }

    private static IntegratedFluidNetwork circuit(double bar, double kelvin) {
        var network = IFNTestSupport.newNetwork(IFNTestSupport.waterFluid(), 10000, 0, 50);
        network.replaceCanonicalState(vapor(bar, kelvin, 10000));
        assertEquals(bar, network.getPressure(), 1e-5);
        return network;
    }

    private static HeatPumpMachineProcessState primed(double bar, double kelvin, boolean paired) {
        var heel = vapor(0.72 * bar, kelvin, 1000);
        var tag = new NBTTagCompound();
        tag.setString("pumpSubstance", "water");
        tag.setLong("pumpMicroKg", heel.substanceAmount().rawUnits());
        tag.setLong("pumpMicroEu", heel.totalEnergy().rawUnits());
        if (paired) {
            tag.setString("pumpPartnerSubstance", "water");
            tag.setLong("pumpPartnerMicroKg", heel.substanceAmount().rawUnits());
            tag.setLong("pumpPartnerMicroEu", heel.totalEnergy().rawUnits());
        }
        var pump = new HeatPumpMachineProcessState();
        pump.load(tag);
        return pump;
    }

    private static IFNCanonicalState vapor(double bar, double kelvin, int liters) {
        // A rigid volume of single-phase vapor: pV = mRT and stored u = h - RT
        long mass = Math.round(1e6 * 100 * bar * liters / (461.5 * kelvin));
        long energy = Math.round(mass * (2_257_000 + 2010 * (kelvin - 373.15) - 461.5 * kelvin));
        return IFNCanonicalState.fromRaw("water", mass, energy);
    }

    private static long steamPortions(double bar, double kelvin, int liters) {
        return (long) (100 * bar * liters / (461.5 * kelvin) * 160);
    }
}
