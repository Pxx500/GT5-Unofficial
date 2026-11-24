package gregtech.api.metatileentity.implementations.integratedfluid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import gregtech.api.metatileentity.implementations.integratedfluid.amount.SubstanceAmount;
import gregtech.api.metatileentity.implementations.integratedfluid.state.IFNCanonicalState;

import org.junit.jupiter.api.Test;

class IFNCanonicalStatePersistenceTest {

    @Test
    void persistedNetworkStateStoresCanonicalSubstanceAndEnergyOnly() {
        IntegratedFluidNetwork network = new IntegratedFluidNetwork();
        long amountQ = 12L * SubstanceAmount.SCALE;
        long energyQ = 34L * gregtech.api.metatileentity.implementations.integratedfluid.amount.EnergyAmount.SCALE;
        UUID id = new UUID(1L, 2L);

        network.replaceCanonicalState(IFNCanonicalState.fromRaw("water", amountQ, energyQ));

        IntegratedFluidNetworkSavedData data = new IntegratedFluidNetworkSavedData();
        data.upsertState(id, network);
        NBTTagCompound root = new NBTTagCompound();
        data.writeToNBT(root);

        NBTTagCompound entry = onlyNetworkEntry(root);
        assertEquals("water", entry.getString("FluidName"));
        assertEquals(amountQ, entry.getLong("MassMicroKg"));
        assertEquals(energyQ, entry.getLong("TotalMicroEu"));
        assertFalse(entry.hasKey("Pressure"));
        assertFalse(entry.hasKey("Temperature"));
    }

    @Test
    void canonicalStateRoundTripsThroughSavedData() {
        long amountQ = 7L * SubstanceAmount.SCALE;
        long energyQ = 11L * gregtech.api.metatileentity.implementations.integratedfluid.amount.EnergyAmount.SCALE;
        UUID id = new UUID(3L, 4L);
        NBTTagCompound entry = new NBTTagCompound();
        entry.setLong("IdMost", id.getMostSignificantBits());
        entry.setLong("IdLeast", id.getLeastSignificantBits());
        entry.setInteger("ExpectedMembers", 5);
        entry.setLong("ChamberBaseLiters", 300);
        entry.setLong("ChamberStrokeLiters", 0);
        entry.setString("FluidName", "water");
        entry.setLong("MassMicroKg", amountQ);
        entry.setLong("TotalMicroEu", energyQ);
        entry.setFloat("Pressure", 123.0f);
        entry.setFloat("Temperature", 456.0f);
        NBTTagList networks = new NBTTagList();
        networks.appendTag(entry);
        NBTTagCompound root = new NBTTagCompound();
        root.setInteger("ChamberFormat", 2);
        root.setTag("Networks", networks);

        IntegratedFluidNetworkSavedData data = new IntegratedFluidNetworkSavedData();
        data.readFromNBT(root);

        IntegratedFluidNetworkSavedData.NetworkState state = data.getState(id);
        assertNotNull(state);
        assertEquals("water", state.inventory.fluidId().get());
        assertEquals(amountQ, state.inventory.substanceAmount().rawUnits());
        assertEquals(energyQ, state.inventory.totalEnergy().rawUnits());
        assertEquals(5, state.expectedMemberCount);
    }

    @Test
    void legacySaveCannotBeUsedOrOverwritten() {
        UUID id = new UUID(5L, 6L);
        int legacyAmount = 3;
        float legacyTemperature = 330.0f;
        NBTTagCompound legacyFluid = new NBTTagCompound();
        legacyFluid.setString("FluidName", "water");
        legacyFluid.setInteger("Amount", legacyAmount);
        NBTTagCompound entry = new NBTTagCompound();
        entry.setLong("IdMost", id.getMostSignificantBits());
        entry.setLong("IdLeast", id.getLeastSignificantBits());
        entry.setInteger("ExpectedMembers", 2);
        entry.setTag("Fluid", legacyFluid);
        entry.setFloat("Temperature", legacyTemperature);
        NBTTagList networks = new NBTTagList();
        networks.appendTag(entry);
        NBTTagCompound root = new NBTTagCompound();
        root.setTag("Networks", networks);

        IntegratedFluidNetworkSavedData data = new IntegratedFluidNetworkSavedData();
        data.readFromNBT(root);

        assertThrows(IllegalStateException.class, () -> data.getState(id));
        assertThrows(IllegalStateException.class, () -> data.writeToNBT(new NBTTagCompound()));
    }

    private static NBTTagCompound onlyNetworkEntry(NBTTagCompound root) {
        NBTTagList networks = root.getTagList("Networks", 10);
        assertEquals(1, networks.tagCount());
        return networks.getCompoundTagAt(0);
    }
}
