package gregtech.api.metatileentity.implementations.integratedfluid;


import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import gregtech.api.metatileentity.implementations.integratedfluid.state.IFNCanonicalState;

class IntegratedFluidNetworkStateTest {

    @Test
    void liquidStrokeStopsAtReceiverSetpointWithoutLosingMassOrSpringEnergy() {
        var water = IFNTestSupport.waterFluid();
        var source = IFNTestSupport.newNetwork(water, 10000, 10000, 20);
        var receiver = IFNTestSupport.newNetwork(water, 10000, 10000, 20);
        long supply = 11_000_000_000L, existing = 9_700_000_000L;
        source.replaceCanonicalState(IFNCanonicalState.fromRaw("water", supply, -100_000 * supply));
        receiver.replaceCanonicalState(IFNCanonicalState.fromRaw("water", existing, -100_000 * existing));
        double ceiling = 120_000;
        assertTrue(receiver.getPressurePa() < ceiling && source.getPressurePa() > ceiling);
        var before = receiver.getCanonicalState();
        long moved = source.meterTo(receiver, 10_000_000_000L, ceiling);
        assertTrue(moved > 0 && moved < supply);
        assertEquals(ceiling, receiver.getPressurePa(), 1);
        assertTrue(receiver.getPressurePa() <= ceiling);
        assertEquals(supply - moved, source.getAmountQ());
        assertEquals(existing + moved, receiver.getAmountQ());
        assertEquals(-100_000 * (supply + existing),
            source.getCanonicalState().totalEnergy().rawUnits() + receiver.getCanonicalState().totalEnergy().rawUnits());
        assertEquals(existing, before.substanceAmount().rawUnits());
        assertEquals(-100_000 * existing, before.totalEnergy().rawUnits());
        assertEquals(0, source.meterTo(receiver, 10_000_000_000L, ceiling));
    }
}
