package gregtech.api.metatileentity.implementations.integratedfluid.topology;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import gregtech.api.metatileentity.implementations.integratedfluid.amount.EnergyAmount;
import gregtech.api.metatileentity.implementations.integratedfluid.amount.SubstanceAmount;
import gregtech.api.metatileentity.implementations.integratedfluid.state.IFNCanonicalState;

class IFNMergeSplitPolicyTest {

    @Test
    void differentPhaseIdsOfSameSubstanceMergeIntoCanonicalSubstance() {
        IFNCanonicalState merged = IFNMergePolicy.merge(java.util.List.of(
            state("water", 10, 100),
            state("steam", 5, 40)
        ));

        assertEquals("water", merged.fluidId().get());
        assertEquals(15, merged.substanceAmount().toWholeKilograms());
        assertEquals(140, merged.totalEnergy().toWholeEu());
    }

    private static IFNCanonicalState state(String fluidId, long kilograms, long energyEu) {
        return IFNCanonicalState.of(
            fluidId,
            SubstanceAmount.fromKilograms(kilograms),
            EnergyAmount.fromEu(energyEu));
    }
}
