package gregtech.api.metatileentity.implementations.integratedfluid.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import gregtech.api.metatileentity.implementations.integratedfluid.thermodynamics.ClosedFluidChamber;
import gregtech.api.metatileentity.implementations.integratedfluid.thermodynamics.PureFluid;
import gregtech.api.metatileentity.implementations.integratedfluid.IFNTestSupport;
import gregtech.api.metatileentity.implementations.integratedfluid.IntegratedFluidNetwork;

class IntegratedFluidNetworkStatusTest {

    @Test
    void expectedMissingMembersMakeNetworkPending() {
        IntegratedFluidNetwork network = new IntegratedFluidNetwork();

        network.setExpectedMemberCount(1);

        assertEquals(IFNNetworkStatus.PENDING, network.getNetworkStatus());
    }

    @Test
    void frozenStatusOverridesPendingStatus() {
        IntegratedFluidNetwork network = new IntegratedFluidNetwork();

        network.setExpectedMemberCount(1);
        network.freeze("fluid conflict");

        assertEquals(IFNNetworkStatus.FROZEN, network.getNetworkStatus());
        assertEquals("fluid conflict", network.getFrozenReason());
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void unavailableNetworkCannotSupplyOrAcceptAMachineCharge(boolean frozen) {
        var water = IFNTestSupport.waterFluid();
        var inlet = IFNTestSupport.newNetwork(water, 10000, 0, 20);
        var outlet = IFNTestSupport.newNetwork(water, 10000, 0, 20);
        var wet = IFNCanonicalState.fromRaw("water", 10_000_000, 10_423_434_875_000L);
        inlet.replaceCanonicalState(wet);
        var empty = ClosedFluidChamber.empty(PureFluid.water(), 1000);
        for (var network : new IntegratedFluidNetwork[] { inlet, outlet }) {
            if (frozen) network.freeze("Isolate the port");
            else network.setPending(true);
        }
        assertSame(empty, inlet.fillMachineChamber(empty, 100));
        assertEquals(wet.substanceAmount().rawUnits(), inlet.getAmountQ());
        assertEquals(wet.totalEnergy().rawUnits(), inlet.getCanonicalState().totalEnergy().rawUnits());
        if (frozen) inlet.clearFrozen();
        else inlet.setPending(false);
        var charge = inlet.fillMachineChamber(empty, 100);
        assertTrue(charge.massKg().signum() > 0);
        assertSame(charge, outlet.drainMachineChamber(charge, 1000, 0));
        assertEquals(0, outlet.getAmountQ());
        if (frozen) outlet.clearFrozen();
        else outlet.setPending(false);
        var heel = outlet.drainMachineChamber(charge, 1000, 0);
        assertTrue(outlet.getAmountQ() > 0);
        assertEquals(wet.substanceAmount().rawUnits(), inlet.getAmountQ() + heel.massKg().movePointRight(6).longValueExact() + outlet.getAmountQ());
        assertEquals(wet.totalEnergy().rawUnits(), inlet.getCanonicalState().totalEnergy().rawUnits()
            + heel.totalEnergyEu().movePointRight(6).longValueExact() + outlet.getCanonicalState().totalEnergy().rawUnits());
    }
}
