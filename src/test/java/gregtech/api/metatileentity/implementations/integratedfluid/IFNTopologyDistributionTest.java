package gregtech.api.metatileentity.implementations.integratedfluid;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import gregtech.api.metatileentity.implementations.integratedfluid.amount.EnergyAmount;
import gregtech.api.metatileentity.implementations.integratedfluid.amount.SubstanceAmount;
import gregtech.api.metatileentity.implementations.integratedfluid.state.IFNCanonicalState;
import gregtech.api.metatileentity.implementations.integratedfluid.topology.IFNStateDistributor;

class IFNTopologyDistributionTest {

    @ParameterizedTest
    @ValueSource(longs = { 100L, -100L })
    void splitConservesSubstanceAndSignedEnergy(long energy) {
        IFNCanonicalState source = IFNCanonicalState
            .of("water", SubstanceAmount.fromRawUnits(10), EnergyAmount.fromRawUnits(energy));

        IFNCanonicalState[] shares = IFNStateDistributor.splitStateByWeights(source, new long[] { 1, 2 });

        assertEquals(
            "water",
            shares[0].fluidId()
                .get());
        assertEquals(
            "water",
            shares[1].fluidId()
                .get());

        long splitSubstance = shares[0].substanceAmount()
            .plus(shares[1].substanceAmount())
            .rawUnits();
        long splitEnergy = shares[0].internalEnergy()
            .plus(shares[1].internalEnergy())
            .rawUnits();

        assertEquals(10L, splitSubstance);
        assertEquals(energy, splitEnergy);
    }
}
