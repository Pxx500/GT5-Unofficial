package gregtech.api.metatileentity.implementations.integratedfluid.topology;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import gregtech.api.metatileentity.implementations.integratedfluid.amount.EnergyAmount;
import gregtech.api.metatileentity.implementations.integratedfluid.amount.SubstanceAmount;
import gregtech.api.metatileentity.implementations.integratedfluid.state.IFNCanonicalState;

class IFNPipeConnectionPolicyTest {

    @Test
    void incompatibleNonEmptySubstancesPreventPipeConnection() {
        assertFalse(IFNPipeConnectionPolicy.canConnectPipeStates(
            state("water"),
            state("hydrogen")
        ));
    }

    @Test
    void pipeRefusesUnrepresentableJoinBeforeConnecting() {
        var full = IFNCanonicalState.fromRaw("water", 2_500_000_000_000L, 5_509_483_750_000_000_000L);
        assertFalse(IFNPipeConnectionPolicy.canConnectPipeStates(full, full));
        var cold = IFNCanonicalState.fromRaw("water", 2_500_000_000_000L, -500_000_000_000_000_000L);
        assertTrue(IFNPipeConnectionPolicy.canConnectPipeStates(full, cold));
    }

    @Test
    void differentPhaseIdsOfSameSubstanceCanConnectAsPipes() {
        assertTrue(IFNPipeConnectionPolicy.canConnectPipeStates(
            state("water"),
            state("steam")
        ));
    }

    @Test
    void emptyPipeNetworkCanConnectToNonEmptyPipeNetwork() {
        assertTrue(IFNPipeConnectionPolicy.canConnectPipeStates(
            IFNCanonicalState.empty(),
            state("water")
        ));
    }

    private static IFNCanonicalState state(String fluidId) {
        return IFNCanonicalState.of(fluidId, SubstanceAmount.fromKilograms(1), EnergyAmount.fromEu(1));
    }
}
