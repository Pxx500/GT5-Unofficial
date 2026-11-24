package gregtech.api.metatileentity.implementations.integratedfluid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.jupiter.api.Test;

import gregtech.api.metatileentity.implementations.integratedfluid.amount.VolumeAmount;
import gregtech.api.metatileentity.implementations.integratedfluid.state.IFNCanonicalState;

class MTEIntegratedFluidPressureRegulatorTest {

    @Test
    void persistedConfigurationRoundTrips() {
        MTEIntegratedFluidPressureRegulator regulator = newRegulator();
        regulator.setSetpointPressureBar(6.0f);
        regulator.setOvershootToleranceBar(0.25f);
        regulator.setMaxPacketAmountQ(25L * VolumeAmount.SCALE);
        NBTTagCompound tag = new NBTTagCompound();

        regulator.saveNBTData(tag);
        MTEIntegratedFluidPressureRegulator loaded = newRegulator();
        loaded.loadNBTData(tag);

        assertEquals(6.0f, loaded.getSetpointPressureBar(), 0.0001f);
        assertEquals(0.25f, loaded.getOvershootToleranceBar(), 0.0001f);
        assertEquals(25L * VolumeAmount.SCALE, loaded.getMaxPacketAmountQ());
    }

    @Test
    void transferBetweenNetworksUsesConfiguredLimits() {
        var input = vapor(125000);
        var output = vapor(25000);
        var regulator = newRegulator();
        regulator.setSetpointPressureBar(1);
        regulator.setMaxPacketAmountL(1);
        assertEquals(0, regulator.transferBetweenNetworks(input, output));
        regulator.setMaxPacketAmountL(5);
        assertEquals(6250, regulator.transferBetweenNetworks(input, output));
        assertEquals(118750, input.getAmountQ());
        assertEquals(31250, output.getAmountQ());
        assertEquals(0, regulator.transferBetweenNetworks(output, input));
        regulator.setSetpointPressureBar(output.getPressure());
        assertEquals(0, regulator.transferBetweenNetworks(input, output));
    }

    @Test
    void guiConfigurationUsesPlayerFacingUnits() {
        MTEIntegratedFluidPressureRegulator regulator = newRegulator();

        regulator.setSetpointPressureCentibar(325);
        regulator.setOvershootToleranceCentibar(7);
        regulator.setMaxPacketAmountL(25);

        assertEquals(3.25f, regulator.getSetpointPressureBar(), 0.0001f);
        assertEquals(0.07f, regulator.getOvershootToleranceBar(), 0.0001f);
        assertEquals(25L * VolumeAmount.SCALE, regulator.getMaxPacketAmountQ());
        assertEquals(325, regulator.getSetpointPressureCentibar());
        assertEquals(7, regulator.getOvershootToleranceCentibar());
        assertEquals(25, regulator.getMaxPacketAmountL());
    }

    private static MTEIntegratedFluidPressureRegulator newRegulator() {
        return new MTEIntegratedFluidPressureRegulator(
            "test.pressure_regulator",
            1,
            new String[] { "Test Pressure Regulator" },
            null);
    }

    @Test
    void largePacketCannotCrossEquilibriumOrSpendFrozenInventory() {
        var input = vapor(125000);
        var output = vapor(25000);
        var regulator = newRegulator();
        var total = input.getCanonicalState().totalEnergy().rawUnits() + output.getCanonicalState().totalEnergy().rawUnits();
        regulator.setSetpointPressureBar(5);
        regulator.setMaxPacketAmountL(100);
        assertTrue(regulator.transferBetweenNetworks(input, output) > 0);
        assertTrue(input.getPressure() >= output.getPressure());
        assertEquals(0, regulator.transferBetweenNetworks(input, output), "A further whole portion would reverse the head");
        assertEquals(150000, input.getAmountQ() + output.getAmountQ());
        assertEquals(total, input.getCanonicalState().totalEnergy().rawUnits() + output.getCanonicalState().totalEnergy().rawUnits());
        var fresh = vapor(125000);
        fresh.freeze("maintenance");
        assertEquals(0, regulator.transferBetweenNetworks(fresh, output));
        assertEquals(125000, fresh.getAmountQ());
    }

    private static IntegratedFluidNetwork vapor(long microKg) {
        var network = IFNTestSupport.newNetwork(IFNTestSupport.waterFluid(), 100, 0, 10);
        network.replaceCanonicalState(IFNCanonicalState.fromRaw("water", microKg, microKg * 2_242_506L));
        return network;
    }
}
