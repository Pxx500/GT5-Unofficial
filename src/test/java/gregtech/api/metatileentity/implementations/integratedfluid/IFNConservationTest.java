package gregtech.api.metatileentity.implementations.integratedfluid;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import gregtech.api.metatileentity.implementations.integratedfluid.state.IFNCanonicalState;

class IFNConservationTest {

    @Test
    void unrepresentableIncomingEnergyLeavesBothChambersUnchanged() {
        var source = chamber(1000);
        IFNTestSupport.restoreDrySteamAt475K(source, 12);
        var receiver = chamber(1_000_000_000);
        long almostFull = Long.MAX_VALUE - 10;
        long mass = almostFull / 2_242_506;
        receiver.replaceCanonicalState(IFNCanonicalState.fromRaw("water", mass, almostFull));
        long sourceMass = source.getAmountQ(), sourceEnergy = energy(source);
        assertTrue(source.getPressurePa() > receiver.getPressurePa());
        assertEquals(0, source.meterTo(receiver, 10_000_000L, Double.MAX_VALUE));
        assertEquals(sourceMass, source.getAmountQ());
        assertEquals(sourceEnergy, energy(source));
        assertEquals(mass, receiver.getAmountQ());
        assertEquals(almostFull, energy(receiver));

        long withRoom = almostFull - 1_000_000_000_000L;
        receiver.replaceCanonicalState(IFNCanonicalState.fromRaw("water", mass, withRoom));
        long moved = source.meterTo(receiver, 10_000_000L, Double.MAX_VALUE);
        assertTrue(moved > 0);
        assertEquals(mass + moved, receiver.getAmountQ());
        assertEquals(sourceEnergy - energy(source), energy(receiver) - withRoom);
    }

    @ParameterizedTest
    @ValueSource(ints = { 1, 10, 100 })
    void reversingCirculationOnlyAddsOrRemovesReservoirHeat(int packetLiters) {
        var a = chamber(10_000);
        var b = chamber(10_000);
        IFNTestSupport.restoreDrySteamAt475K(a, 1);
        IFNTestSupport.restoreDrySteamAt475K(b, 1);
        long mass = a.getAmountQ() + b.getAmountQ();
        long expectedEnergy = energy(a) + energy(b);
        for (int stroke = 0; stroke < 20; stroke++) {
            var source = stroke % 2 == 0 ? a : b;
            var receiver = stroke % 2 == 0 ? b : a;
            expectedEnergy += contact(source, 490) + contact(receiver, 450);
            long sourceBefore = source.getAmountQ(), receivedBefore = receiver.getAmountQ();
            long moved = source.meterTo(receiver, packetLiters * 1_000_000L, Double.MAX_VALUE);
            if (sourceBefore * packetLiters / 10_000 < 6250) assertEquals(0, moved);
            else assertTrue(moved > 0 && source.getPressurePa() >= receiver.getPressurePa());
            assertEquals(sourceBefore - moved, source.getAmountQ());
            assertEquals(receivedBefore + moved, receiver.getAmountQ());
            assertEquals(mass, a.getAmountQ() + b.getAmountQ());
            assertEquals(expectedEnergy, energy(a) + energy(b));
        }
    }

    private static long contact(IntegratedFluidNetwork network, double kelvin) {
        double initialK = network.getSolvedState().orElseThrow().temperatureKelvin();
        long before = energy(network);
        network.exchangeThroughRadiator(kelvin, 1e9, kelvin);
        assertEquals(kelvin, network.getTemperature(), 0.001);
        long heat = energy(network) - before;
        // For dry steam in a rigid chamber the reservoir supplies m * cv * deltaT
        assertEquals(network.getAmountQ() * (2010 - 461.5) * (kelvin - initialK), (double) heat, 5);
        return heat;
    }

    @Test
    void fractionalTransferDoesNotRoundEitherChamberToWholeKilograms() {
        var source = chamber(1000);
        var receiver = chamber(1000);
        IFNTestSupport.restoreDrySteamAt475K(source, 2);
        IFNTestSupport.restoreDrySteamAt475K(receiver, 1);
        long initial = source.getAmountQ(), previous = receiver.getAmountQ();
        long totalEnergy = energy(source) + energy(receiver);
        long moved = source.meterTo(receiver, 10_000_000L, Double.MAX_VALUE);
        assertEquals(6250L, moved);
        assertTrue(moved > 0 && moved < 1_000_000);
        assertEquals(initial - moved, source.getAmountQ());
        assertEquals(previous + moved, receiver.getAmountQ());
        assertEquals(totalEnergy, energy(source) + energy(receiver));
    }

    @Test
    void frozenWaterRetainsItsColdStateAfterUnequalSplitMergeAndWorldSave() {
        var scenario = HorizonQAVfnScenario.create(IFNTestSupport.waterFluid());
        var small = scenario.pipe("small", 100);
        var large = scenario.pipe("large", 200);
        scenario.connect(small, large);
        scenario.addNetworkFrom(small);
        var original = small.getNetwork();
        var members = scenario.members(small, large);
        coldWater(original, 250);
        long coldEnergy = energy(original);
        assertTrue(coldEnergy < 0 && original.getSolvedState().orElseThrow().solidMassFraction() > 0.99);

        scenario.disconnect(small, large);
        scenario.changedAt(small);
        scenario.assertNetworkGroups(members, 2);
        assertEquals(3_000_000L, small.getNetwork().getAmountQ());
        assertEquals(6_000_000L, large.getNetwork().getAmountQ());
        assertEquals(coldEnergy, energy(small.getNetwork()) + energy(large.getNetwork()));
        scenario.connect(small, large);
        scenario.changedAt(small);
        scenario.roundTripSavedDataAndReloadFrom(small, members);
        assertTrue(small.getNetwork().isPending());
        assertEquals(coldEnergy, energy(small.getNetwork()));
        scenario.addNetworkFrom(large);
        scenario.assertSingleNetwork(members, 2);
        var restored = large.getNetwork();
        assertEquals(9_000_000L, restored.getAmountQ());
        assertEquals(coldEnergy, energy(restored));
        assertEquals(250, restored.getTemperature(), 0.001);
        assertTrue(restored.getSolvedState().orElseThrow().solidMassFraction() > 0.99);
    }

    @Test
    void repeatedRefusalsCannotHeatColdWaterOrSpendItTwice() {
        var input = chamber(100);
        var output = chamber(100);
        coldWater(input, 290);
        output.freeze("isolated");
        long coldEnergy = energy(input);
        assertTrue(coldEnergy < 0);
        for (int i = 0; i < 16; i++) {
            assertEquals(0, input.meterTo(output, 20_000_000, Double.MAX_VALUE));
            assertEquals(9_000_000L, input.getAmountQ());
            assertEquals(coldEnergy, energy(input));
            assertEquals(0, output.getAmountQ());
            assertEquals(0, energy(output));
        }
        output.clearFrozen();
        assertTrue(input.meterTo(output, 20_000_000, Double.MAX_VALUE) > 0);
        assertEquals(9_000_000L, input.getAmountQ() + output.getAmountQ());
        assertEquals(coldEnergy, energy(input) + energy(output));
    }

    @Test
    void exchangingHeatChangesEnergyWithoutMovingSubstance() {
        var hot = chamber(1000);
        var cold = chamber(1000);
        IFNTestSupport.restoreDrySteamAt475K(hot, 1);
        coldWater(cold, 290);
        long hotMass = hot.getAmountQ(), coldMass = cold.getAmountQ(), hotEnergy = energy(hot), coldEnergy = energy(cold);
        long heat = hot.conductHeatTo(cold, 100);
        assertTrue(heat > 0);
        assertEquals(hotEnergy - heat, energy(hot));
        assertEquals(coldEnergy + heat, energy(cold));
        assertEquals(hotMass, hot.getAmountQ());
        assertEquals(coldMass, cold.getAmountQ());
    }

    @Test
    void mergingColdWaterWithSteamPreservesSignedEnergyAndRemovesTheDonorInventory() {
        var first = chamber(1000);
        var second = chamber(2000);
        coldWater(first, 285);
        IFNTestSupport.restoreDrySteamAt475K(second, 1);
        assertTrue(energy(first) < 0 && energy(second) > 0);
        long mass = first.getAmountQ() + second.getAmountQ(), energy = energy(first) + energy(second);
        new NetworkManager(null, new IntegratedFluidNetworkSavedData()).mergeIntoNetwork(first, java.util.Set.of(second));
        assertEquals(mass, first.getAmountQ());
        assertEquals(energy, energy(first));
        assertEquals(0, second.getAmountQ());
        assertEquals(0, energy(second));
        assertEquals(3000, first.getOccupiedVolume(), 0.001);
    }

    private static void coldWater(IntegratedFluidNetwork chamber, double kelvin) {
        chamber.replaceCanonicalState(IFNCanonicalState.fromRaw("water", 9_000_000, -2_700_000_000_000L));
        chamber.exchangeThroughRadiator(kelvin, 1e9, kelvin);
        assertEquals(kelvin, chamber.getTemperature(), 0.001);
    }

    private static IntegratedFluidNetwork chamber(int liters) {
        return IFNTestSupport.newNetwork(IFNTestSupport.waterFluid(), liters, 0, 20);
    }

    private static long energy(IntegratedFluidNetwork chamber) {
        return chamber.getCanonicalState().totalEnergy().rawUnits();
    }
}
