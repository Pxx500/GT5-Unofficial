package gregtech.api.metatileentity.implementations.integratedfluid.topology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import gregtech.api.metatileentity.implementations.integratedfluid.amount.EnergyAmount;
import gregtech.api.metatileentity.implementations.integratedfluid.amount.SubstanceAmount;
import gregtech.api.metatileentity.implementations.integratedfluid.state.IFNCanonicalState;

class IFNMergePolicyTest {

    @Test
    void emptyAndNonEmptyNetworksAreCompatible() {
        IFNCanonicalState merged = IFNMergePolicy.merge(java.util.List.of(
            IFNCanonicalState.empty(),
            state("water", 10, 100)
        ));

        assertFalse(merged.isEmpty());
        assertEquals("water", merged.fluidId().get());
        assertEquals(10, merged.substanceAmount().toWholeKilograms());
        assertEquals(100, merged.totalEnergy().toWholeEu());
    }

    @Test
    void sameFluidMergeSumsSubstanceAndEnergy() {
        IFNCanonicalState merged = IFNMergePolicy.merge(java.util.List.of(
            state("water", 10, 100),
            state("water", 5, 40)
        ));

        assertEquals("water", merged.fluidId().get());
        assertEquals(15, merged.substanceAmount().toWholeKilograms());
        assertEquals(140, merged.totalEnergy().toWholeEu());
    }

    @Test
    void hotAndColdInventoriesSettleIndependentlyOfTraversalOrder() {
        var hot = IFNCanonicalState.fromRaw("water", 7, Long.MAX_VALUE - 2);
        var warm = IFNCanonicalState.fromRaw("water", 6, 21);
        var cold = IFNCanonicalState.fromRaw("water", 5, -20);
        for (var states : java.util.List.of(java.util.List.of(hot, warm, cold), java.util.List.of(cold, warm, hot))) {
            var combined = IFNMergePolicy.merge(states);
            assertEquals(18, combined.substanceAmount().rawUnits());
            assertEquals(Long.MAX_VALUE - 1, combined.totalEnergy().rawUnits());
        }
    }

    @Test
    void differentNonEmptyFluidsAreIncompatible() {
        assertFalse(IFNMergePolicy.canMerge(state("water", 1, 1), state("hydrogen", 1, 1)));
    }

    @Test
    void emptyStatesMergeToEmpty() {
        IFNCanonicalState merged = IFNMergePolicy.merge(java.util.List.of(IFNCanonicalState.empty(), IFNCanonicalState.empty()));

        assertTrue(merged.isEmpty());
    }

    private static IFNCanonicalState state(String fluidId, long refL, long eu) {
        return IFNCanonicalState.of(fluidId, SubstanceAmount.fromKilograms(refL), EnergyAmount.fromEu(eu));
    }
}
