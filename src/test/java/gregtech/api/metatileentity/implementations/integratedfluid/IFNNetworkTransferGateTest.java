package gregtech.api.metatileentity.implementations.integratedfluid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraftforge.fluids.Fluid;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import gregtech.api.interfaces.tileentity.IBasicEnergyContainer;
import gregtech.api.metatileentity.implementations.integratedfluid.state.IFNCanonicalState;
import gregtech.api.metatileentity.implementations.integratedfluid.thermodynamics.ClosedFluidChamber;

class IFNNetworkTransferGateTest {

    @Test
    void rejectedShaftWorkLeavesBothFluidLedgersUntouched() {
        var inlet = IFNTestSupport.newNetwork(IFNTestSupport.waterFluid(), 100, 0, 20);
        var outlet = IFNTestSupport.newNetwork(IFNTestSupport.waterFluid(), 100, 0, 20);
        long mass = 37500, energy = mass * 2257000;
        inlet.replaceCanonicalState(IFNCanonicalState.fromRaw("water", mass, energy));
        var dynamo = Mockito.mock(IBasicEnergyContainer.class);
        Mockito.when(dynamo.getEUCapacity()).thenReturn(2560L);
        var stage = new ClosedFluidChamber.ExpansionStage(8, 1);
        var stroke = BigDecimal.valueOf(21);
        assertNull(inlet.expandTo(outlet, stroke, stage, dynamo, 2560));
        assertEquals(mass, inlet.getAmountQ());
        assertEquals(energy, inlet.getCanonicalState().totalEnergy().rawUnits());
        assertEquals(0, outlet.getAmountQ());
        assertEquals(0, outlet.getCanonicalState().totalEnergy().rawUnits());

        var credited = new AtomicLong();
        Mockito.when(dynamo.increaseStoredEnergyUnits(Mockito.anyLong(), Mockito.eq(false))).thenAnswer(call -> {
            credited.addAndGet(call.getArgument(0));
            return true;
        });
        var moved = inlet.expandTo(outlet, stroke, stage, dynamo, 2560);
        assertNotNull(moved);
        assertEquals(6250, outlet.getAmountQ());
        assertEquals(mass - 6250, inlet.getAmountQ());
        assertTrue(credited.get() > 0);
        assertEquals(energy, inlet.getCanonicalState().totalEnergy().rawUnits() + outlet.getCanonicalState().totalEnergy().rawUnits()
            + credited.get() * 1_000_000L);
    }

    @Test
    void operationalGateRejectsNullPendingAndFrozenNetworks() {
        Fluid fluid = IFNTestSupport.liquidFluid();
        IntegratedFluidNetwork network = IFNTestSupport.newNetwork(fluid, 1_000, 0, 1.0f);

        assertTrue(IFNNetworkTransferGate.isOperational(network));
        assertFalse(IFNNetworkTransferGate.isOperational(null));

        network.setPending(true);

        assertFalse(IFNNetworkTransferGate.isOperational(network));

        network.setPending(false);
        network.freeze("test freeze");

        assertFalse(IFNNetworkTransferGate.isOperational(network));
    }

    @Test
    void injectorGateAlsoRequiresPressureAtCutoff() {
        Fluid fluid = IFNTestSupport.vaporFluid();
        IntegratedFluidNetwork network = IFNTestSupport.newNetwork(fluid, 1_000, 0, 1.0f);

        assertTrue(IFNNetworkTransferGate.canInjectFromGtPipe(network));

        IFNTestSupport.restoreDrySteamAt475K(network, IFNPressurePolicy.injectorCutoffPressureBar() + 0.01f);

        assertFalse(IFNNetworkTransferGate.canInjectFromGtPipe(network));
    }

    @Test
    void machineHatchGatesUseOperationalNetworkPolicy() {
        Fluid fluid = IFNTestSupport.liquidFluid();
        IntegratedFluidNetwork network = IFNTestSupport.newNetwork(fluid, 1_000, 0, 1.0f);

        assertTrue(IFNNetworkTransferGate.canReceiveFromMachine(network));
        assertTrue(IFNNetworkTransferGate.canProvideToMachine(network));

        network.setPending(true);

        assertFalse(IFNNetworkTransferGate.canReceiveFromMachine(network));
        assertFalse(IFNNetworkTransferGate.canProvideToMachine(network));
    }
}
