package gregtech.api.metatileentity.implementations.integratedfluid.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.Fluid;

import gregtech.api.metatileentity.implementations.integratedfluid.IFNTestSupport;
import gregtech.api.metatileentity.implementations.integratedfluid.IIntegratedFluidMember;
import gregtech.api.metatileentity.implementations.integratedfluid.IntegratedFluidNetwork;
import gregtech.api.metatileentity.implementations.integratedfluid.IntegratedFluidNetworkSavedData;

class IntegratedFluidNetworkCanonicalStateTest {

    @Test
    void clearFluidExposesEmptyCanonicalState() {
        IntegratedFluidNetwork network = new IntegratedFluidNetwork();

        network.clearFluid();

        assertTrue(network.getCanonicalState().isEmpty());
    }

    @Test
    void loadedTraceSubstanceRemainsCanonicalState() {
        Fluid fluid = IFNTestSupport.waterFluid();
        IntegratedFluidNetwork network = IFNTestSupport.newNetwork(fluid, 1000, 0, 100.0f);

        network.replaceCanonicalState(IFNCanonicalState.fromRaw("water", 1, 2_242_506));
        var previousWorld = new IntegratedFluidNetworkSavedData();
        previousWorld.upsertState(network.getNetworkId(), network);
        var disk = new NBTTagCompound();
        previousWorld.writeToNBT(disk);
        var nextWorld = new IntegratedFluidNetworkSavedData();
        nextWorld.readFromNBT(disk);
        var restored = new IntegratedFluidNetwork(network.getNetworkId());
        nextWorld.restoreInto(restored);

        IFNCanonicalState state = restored.getCanonicalState();
        assertFalse(state.isEmpty());
        assertEquals(fluid.getName(), state.fluidId().get());
        assertEquals(1L, state.substanceAmount().rawUnits());
        assertEquals(2_242_506L, state.totalEnergy().rawUnits());
    }

    @Test
    void replaceCanonicalStateCommitsNonEmptyState() {
        Fluid fluid = IFNTestSupport.waterFluid();
        IntegratedFluidNetwork network = IFNTestSupport.newNetwork(fluid, 1000, 0, 100.0f);

        network.replaceCanonicalState(IFNCanonicalState.of(
            fluid.getName(),
            gregtech.api.metatileentity.implementations.integratedfluid.amount.SubstanceAmount.fromRawUnits(12L),
            gregtech.api.metatileentity.implementations.integratedfluid.amount.EnergyAmount.fromRawUnits(34L)
        ));

        IFNCanonicalState state = network.getCanonicalState();
        assertFalse(state.isEmpty());
        assertEquals(fluid.getName(), state.fluidId().get());
        assertEquals(12L, state.substanceAmount().rawUnits());
        assertEquals(34L, state.totalEnergy().rawUnits());
    }

    @Test
    void unsupportedSubstanceCannotAcquireWaterPropertiesEvenAfterReload() {
        var network = new IntegratedFluidNetwork();
        network.addMember(new FixedCapacityMember(1000));
        var foreign = IFNCanonicalState.fromRaw("unknown_test_substance", 1_000_000, 2_242_506_000_000L);
        network.replaceCanonicalState(foreign);
        assertTrue(network.getSolvedState().isEmpty());
        assertEquals(IFNNetworkStatus.FROZEN, network.getNetworkStatus());

        var data = new IntegratedFluidNetworkSavedData();
        data.upsertState(network.getNetworkId(), network);
        var disk = new NBTTagCompound();
        data.writeToNBT(disk);
        var next = new IntegratedFluidNetworkSavedData();
        next.readFromNBT(disk);
        var restored = new IntegratedFluidNetwork(network.getNetworkId());
        next.restoreInto(restored);
        restored.addMember(new FixedCapacityMember(1000));
        assertTrue(restored.getSolvedState().isEmpty());
        assertEquals(IFNNetworkStatus.FROZEN, restored.getNetworkStatus());
        assertEquals("unknown_test_substance", restored.getFluidName());
        assertEquals(foreign.totalEnergy().rawUnits(), restored.getCanonicalState().totalEnergy().rawUnits());
        assertEquals(foreign.substanceAmount().rawUnits(), restored.getAmountQ());
    }

    @Test
    void replaceCanonicalStateWithEmptyClearsNetwork() {
        Fluid fluid = IFNTestSupport.waterFluid();
        IntegratedFluidNetwork network = IFNTestSupport.newNetwork(fluid, 1000, 0, 100.0f);
        IFNTestSupport.restoreDrySteamAt475K(network, 1);
        assertTrue(network.getAmountQ() > 0);

        network.replaceCanonicalState(IFNCanonicalState.empty());

        assertTrue(network.getCanonicalState().isEmpty());
        assertEquals(0L, network.getAmountQ());
        assertEquals(0L, network.getCanonicalState().totalEnergy().rawUnits());
    }

    @Test
    void previousReadoutIsUnchangedWhenTheInventoryChanges() {
        var network = IFNTestSupport.newNetwork(IFNTestSupport.waterFluid(), 1000, 0, 100);
        IFNTestSupport.restoreDrySteamAt475K(network, 1);
        var first = network.getSolvedState().orElseThrow();
        long mass = network.getAmountQ();
        IFNTestSupport.restoreDrySteamAt475K(network, 2);
        var next = network.getSolvedState().orElseThrow();
        assertEquals(1, first.pressurePa() / 100000, 0.00001);
        assertEquals(2, next.pressurePa() / 100000, 0.00001);
        assertEquals(2 * mass, network.getAmountQ(), 1L);
    }

    @Test
    void addingEmptyPipeReducesPressureWithoutChangingTheLedger() {
        var network = IFNTestSupport.newNetwork(IFNTestSupport.waterFluid(), 1000, 0, 100);
        IFNTestSupport.restoreDrySteamAt475K(network, 3);
        var first = network.getSolvedState().orElseThrow();
        long mass = network.getAmountQ();
        long energy = network.getCanonicalState().totalEnergy().rawUnits();
        network.addMember(new FixedCapacityMember(2000));
        var expanded = network.getSolvedState().orElseThrow();
        assertEquals(3, first.pressurePa() / 100000, 0.00001);
        assertEquals(1, expanded.pressurePa() / 100000, 0.00001);
        assertEquals(3000, network.getOccupiedVolume(), 0.00001);
        assertEquals(mass, network.getAmountQ());
        assertEquals(energy, network.getCanonicalState().totalEnergy().rawUnits());
    }

    private static final class FixedCapacityMember implements IIntegratedFluidMember {

        private final int capacityContribution;
        private IntegratedFluidNetwork network;
        private UUID networkId;

        private FixedCapacityMember(int capacityContribution) {
            this.capacityContribution = capacityContribution;
        }

        @Override
        public IntegratedFluidNetwork getNetwork() {
            return network;
        }

        @Override
        public void setNetwork(IntegratedFluidNetwork network) {
            this.network = network;
        }

        @Override
        public UUID getNetworkId() {
            return networkId;
        }

        @Override
        public void setNetworkId(UUID id) {
            this.networkId = id;
        }

        @Override
        public void onNetworkUpdate() {}

        @Override
        public int getCapacityContribution() {
            return capacityContribution;
        }
    }
}
