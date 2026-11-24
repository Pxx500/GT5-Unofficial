package gregtech.api.metatileentity.implementations.integratedfluid;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;
import net.minecraft.world.storage.MapStorage;

import gregtech.api.metatileentity.implementations.integratedfluid.state.IFNCanonicalState;

public class IntegratedFluidNetworkSavedData extends WorldSavedData {

    private static final String DATA_NAME = "GregTech_IntegratedFluidNetworkData";
    private static final String TAG_NETWORKS = "Networks";
    private static final String TAG_ID_MOST = "IdMost";
    private static final String TAG_ID_LEAST = "IdLeast";
    private static final String TAG_EXPECTED = "ExpectedMembers";
    private static final String TAG_FLUID_NAME = "FluidName";
    private static final String TAG_AMOUNT_Q = "MassMicroKg";
    private static final String TAG_ENERGY_Q = "TotalMicroEu";
    private static final String TAG_FROZEN_REASON = "FrozenReason";
    private static final String BASE_LITERS = "ChamberBaseLiters";
    private static final String STROKE_LITERS = "ChamberStrokeLiters";
    private static final String FORMAT = "ChamberFormat";
    private boolean unsupportedFormat;

    private final Map<UUID, NetworkState> networks = new HashMap<>();

    public IntegratedFluidNetworkSavedData() {
        super(DATA_NAME);
    }

    public IntegratedFluidNetworkSavedData(String name) {
        super(name);
    }

    public static IntegratedFluidNetworkSavedData get(World world) {
        MapStorage storage = world.mapStorage;
        IntegratedFluidNetworkSavedData data =
            (IntegratedFluidNetworkSavedData) storage.loadData(IntegratedFluidNetworkSavedData.class, DATA_NAME);
        if (data == null) {
            data = new IntegratedFluidNetworkSavedData();
            storage.setData(DATA_NAME, data);
        }
        return data;
    }

    public NetworkState getState(UUID id) {
        requireSupportedFormat();
        return networks.get(id);
    }

    public void restoreInto(IntegratedFluidNetwork network) {
        NetworkState state = getState(network.getNetworkId());
        if (state != null) network.loadInventory(state.inventory, state.expectedMemberCount, state.baseLiters, state.strokeLiters, state.frozenReason);
    }

    public void upsertState(UUID id, IntegratedFluidNetwork network) {
        requireSupportedFormat();
        if (id == null || network == null) return;
        NetworkState state = networks.computeIfAbsent(id, ignored -> new NetworkState());
        state.expectedMemberCount = network.getExpectedMemberCount();
        state.inventory = network.getCanonicalState();
        state.baseLiters = network.chamberBaseLiters();
        state.strokeLiters = network.chamberStrokeLiters();
        state.frozenReason = network.getFrozenReason();
        markDirty();
    }

    public void removeState(UUID id) {
        requireSupportedFormat();
        if (id == null) return;
        networks.remove(id);
        markDirty();
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        unsupportedFormat = !nbt.hasKey(FORMAT, 3) || nbt.getInteger(FORMAT) != 2 || !nbt.hasKey(TAG_NETWORKS, 9);
        if (unsupportedFormat) return;
        networks.clear();
        NBTTagList list = nbt.getTagList(TAG_NETWORKS, 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound entry = list.getCompoundTagAt(i);
            long most = entry.getLong(TAG_ID_MOST);
            long least = entry.getLong(TAG_ID_LEAST);
            UUID id = new UUID(most, least);
            NetworkState state = new NetworkState();
            state.expectedMemberCount = entry.getInteger(TAG_EXPECTED);
            state.baseLiters = entry.getLong(BASE_LITERS);
            state.strokeLiters = entry.getLong(STROKE_LITERS);
            String fluid = entry.getString(TAG_FLUID_NAME);
            long amount = entry.getLong(TAG_AMOUNT_Q);
            if (!entry.hasKey(TAG_ID_MOST, 4) || !entry.hasKey(TAG_ID_LEAST, 4)
                || !entry.hasKey(BASE_LITERS, 4) || !entry.hasKey(STROKE_LITERS, 4) || state.baseLiters < 0 || state.strokeLiters < 0
                || !entry.hasKey(TAG_AMOUNT_Q, 4) || !entry.hasKey(TAG_ENERGY_Q, 4) || networks.containsKey(id)
                || amount < 0 || (amount > 0 ? fluid.trim().isEmpty() : !fluid.isEmpty() || entry.getLong(TAG_ENERGY_Q) != 0)) {
                unsupportedFormat = true;
                return;
            }
            if (amount > 0) {
                state.inventory = IFNCanonicalState.fromRaw(fluid, amount, entry.getLong(TAG_ENERGY_Q));
            }
            state.frozenReason = entry.hasKey(TAG_FROZEN_REASON) ? entry.getString(TAG_FROZEN_REASON) : null;
            networks.put(id, state);
        }
    }

    private void requireSupportedFormat() {
        if (unsupportedFormat) throw new IllegalStateException("Unsupported VFN storage format: use a fresh test world. Existing data was not migrated.");
    }

    @Override
    public void writeToNBT(NBTTagCompound nbt) {
        requireSupportedFormat();
        nbt.setInteger(FORMAT, 2);
        NBTTagList list = new NBTTagList();
        for (Map.Entry<UUID, NetworkState> entry : networks.entrySet()) {
            UUID id = entry.getKey();
            NetworkState state = entry.getValue();
            NBTTagCompound tag = new NBTTagCompound();
            tag.setLong(TAG_ID_MOST, id.getMostSignificantBits());
            tag.setLong(TAG_ID_LEAST, id.getLeastSignificantBits());
            tag.setInteger(TAG_EXPECTED, state.expectedMemberCount);
            tag.setLong(BASE_LITERS, state.baseLiters);
            tag.setLong(STROKE_LITERS, state.strokeLiters);
            if (!state.inventory.isEmpty()) {
                tag.setString(TAG_FLUID_NAME, state.inventory.fluidId().get());
            }
            tag.setLong(TAG_AMOUNT_Q, state.inventory.substanceAmount().rawUnits());
            tag.setLong(TAG_ENERGY_Q, state.inventory.totalEnergy().rawUnits());
            if (state.frozenReason != null && !state.frozenReason.trim().isEmpty()) {
                tag.setString(TAG_FROZEN_REASON, state.frozenReason);
            }
            list.appendTag(tag);
        }
        nbt.setTag(TAG_NETWORKS, list);
    }

    public static class NetworkState {
        IFNCanonicalState inventory = IFNCanonicalState.empty();
        String frozenReason;
        int expectedMemberCount = 0;
        long baseLiters;
        long strokeLiters;
    }
}
