package gregtech.api.metatileentity.implementations.integratedfluid;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTankInfo;

import gregtech.api.enums.GTValues;

import gregtech.api.enums.Textures;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatch;
import gregtech.api.render.TextureFactory;

/**
 * Integrated Fluid Extractor - Extracts fluid from Integrated Fluid Network
 * and outputs it as standard fluid (without temperature/pressure properties).
 *
 * Acts as a bridge between the integrated network and standard fluid systems.
 */
public class MTEIntegratedFluidExtractor extends MTEHatch implements IIntegratedFluidMember {

    private IntegratedFluidNetwork network;
    private int textureIndex = 0;
    private java.util.UUID networkId;

    public MTEIntegratedFluidExtractor(int aID, String aName, String aNameRegional, int aTier) {
        super(aID, aName, aNameRegional, aTier, 0, new String[] {
            "Extracts fluid from Integrated Fluid Network",
            "Converts network fluid to standard fluid",
            "Removes temperature and pressure properties",
            "Passively responds to drain requests",
            "Retains at least 1.10 bar in the network"
        });
    }

    public MTEIntegratedFluidExtractor(String aName, int aTier, String[] aDescription, ITexture[][][] aTextures) {
        super(aName, aTier, 0, aDescription, aTextures);
    }

    @Override
    public MetaTileEntity newMetaEntity(IGregTechTileEntity aTileEntity) {
        return new MTEIntegratedFluidExtractor(this.mName, this.mTier, this.mDescriptionArray, this.mTextures);
    }

    @Override
    public ITexture[] getTexturesActive(ITexture aBaseTexture) {
        return new ITexture[] {
            aBaseTexture,
            TextureFactory.of(Textures.BlockIcons.OVERLAY_PIPE_OUT)
        };
    }

    @Override
    public ITexture[] getTexturesInactive(ITexture aBaseTexture) {
        return new ITexture[] {
            aBaseTexture,
            TextureFactory.of(Textures.BlockIcons.OVERLAY_PIPE_OUT)
        };
    }

    @Override
    public boolean isFacingValid(ForgeDirection facing) {
        return true;
    }

    @Override
    public boolean isValidSlot(int aIndex) {
        return false;
    }

    @Override
    public boolean allowPullStack(IGregTechTileEntity aBaseMetaTileEntity, int aIndex, ForgeDirection side,
        ItemStack aStack) {
        return false;
    }

    @Override
    public boolean allowPutStack(IGregTechTileEntity aBaseMetaTileEntity, int aIndex, ForgeDirection side,
        ItemStack aStack) {
        return false;
    }

    // ===== IIntegratedFluidMember Implementation =====

    @Override
    public IntegratedFluidNetwork getNetwork() {
        return network;
    }

    @Override
    public void setNetwork(IntegratedFluidNetwork network) {
        this.network = network;
    }

    @Override
    public java.util.UUID getNetworkId() {
        return networkId;
    }

    @Override
    public void setNetworkId(java.util.UUID id) {
        this.networkId = id;
    }

    @Override
    public int getCapacityContribution() {
        return 0; // Extractor doesn't add capacity to network
    }

    public void onNetworkChanged() {
        // Network changed - update state if needed
    }

    @Override
    public void onNetworkUpdate() {
        // Called when network data updates
    }

    /**
     * Updates the texture index to match multiblock casing.
     */
    public void setTextureIndex(int aTextureIndex) {
        this.textureIndex = aTextureIndex;
    }

    // ===== Fluid Handling - Override MTEHatch methods =====

    @Override
    public FluidStack getFluid() {
        return drain(1, false);
    }

    @Override
    public int getFluidAmount() {
        // Extractor has no local storage
        return 0;
    }

    @Override
    public int getCapacity() {
        // Extractor has no local storage - it's a passthrough to network
        return 0;
    }

    @Override
    public FluidStack drain(int maxDrain, boolean doDrain) {
        return network == null ? null : network.export(null, maxDrain, doDrain, IFNAmbientTemperature.atPort(getBaseMetaTileEntity()));
    }

    @Override
    public FluidStack drain(ForgeDirection side, FluidStack request, int amount, boolean doDrain) {
        return request == null || network == null ? null : network.export(request, amount, doDrain, IFNAmbientTemperature.atPort(getBaseMetaTileEntity()));
    }

    @Override
    public FluidTankInfo[] getTankInfo(ForgeDirection side) {
        FluidStack available = drain(Integer.MAX_VALUE, false);
        return available == null ? GTValues.emptyFluidTankInfo
            : new FluidTankInfo[] { new FluidTankInfo(available, available.amount) };
    }

    static boolean canExtractFromNetwork(IntegratedFluidNetwork network) {
        return IFNNetworkTransferGate.canExtractToGtPipe(network);
    }

    @Override
    public int fill(FluidStack resource, boolean doFill) {
        // Extractor is OUTPUT-ONLY - it only extracts from network, never inputs
        // If something tries to fill the extractor, reject it (return 0)
        // Fluid should go to network through Input Hatches or pipes instead
        return 0;
    }

    // ===== Tick Update =====

    @Override
    public void onFirstTick(IGregTechTileEntity aBaseMetaTileEntity) {
        super.onFirstTick(aBaseMetaTileEntity);
        // Add extractor to network - it IS a member, but with 0 capacity
        if (aBaseMetaTileEntity.isServerSide()) {
            NetworkManager manager = NetworkManager.getInstance(aBaseMetaTileEntity.getWorld());
            manager.onMemberAdded(this);
        }
    }

    @Override
    public void onPostTick(IGregTechTileEntity aBaseMetaTileEntity, long aTick) {
        super.onPostTick(aBaseMetaTileEntity, aTick);

        if (aBaseMetaTileEntity.isServerSide() && aTick % 20 == 0) {
            // Periodically check network connectivity
            NetworkManager manager = NetworkManager.getInstance(aBaseMetaTileEntity.getWorld());

            // AGGRESSIVE INITIALIZATION: Always try to join/create network
            if (network == null) {
                manager.onMemberAdded(this);
            }
        }

        // NO AUTO-OUTPUT!
        // Extractor is PASSIVE - it only outputs when something tries to drain() from it
        // This prevents race conditions with Heat Pump adding fluid
    }

    // ===== NBT =====

    @Override
    public void saveNBTData(NBTTagCompound aNBT) {
        super.saveNBTData(aNBT);
        aNBT.setInteger("mTextureIndex", textureIndex);
        if (networkId != null) {
            aNBT.setLong("ifnNetworkIdMost", networkId.getMostSignificantBits());
            aNBT.setLong("ifnNetworkIdLeast", networkId.getLeastSignificantBits());
        }
    }

    @Override
    public void loadNBTData(NBTTagCompound aNBT) {
        super.loadNBTData(aNBT);
        if (aNBT.hasKey("mTextureIndex")) {
            textureIndex = aNBT.getInteger("mTextureIndex");
        }
        if (aNBT.hasKey("ifnNetworkIdMost") && aNBT.hasKey("ifnNetworkIdLeast")) {
            long most = aNBT.getLong("ifnNetworkIdMost");
            long least = aNBT.getLong("ifnNetworkIdLeast");
            networkId = new java.util.UUID(most, least);
        } else {
            networkId = null;
        }
    }

    // ===== Network Management =====

    @Override
    public void onBlockDestroyed() {
        NetworkManager.onBlockDestroyed(this, getBaseMetaTileEntity());
        super.onBlockDestroyed();
    }

    @Override
    public void onRemoval() {
        IGregTechTileEntity baseTile = getBaseMetaTileEntity();
        if (baseTile != null && baseTile.isServerSide()) {
            NetworkManager manager = NetworkManager.getInstance(baseTile.getWorld());
            manager.onMemberRemoved(this);
        }
        super.onRemoval();
    }

    @Override
    public boolean onRightclick(IGregTechTileEntity aBaseMetaTileEntity, EntityPlayer aPlayer) {
        if (aBaseMetaTileEntity.isClientSide()) {
            return true;
        }

        String message;
        if (network == null) {
            message = "Not connected to network";
        } else {
            var inventory = network.getCanonicalState();
            String contents = inventory.isEmpty() ? "Empty"
                : inventory.substanceAmount().formatKilograms() + " kg " + inventory.fluidId().get();
            var geometry = network.getTopologySnapshot();
            message = "Network: " + contents + " | Capacity: " + geometry.totalVolume().toWholeLiters() + " L";
            var state = network.getSolvedState();
            if (state.isPresent()) {
                message += String.format(java.util.Locale.ROOT, " | %.1f K, %.2f bar", state.get().temperatureKelvin(),
                    state.get().pressurePa() / 100000);
            } else {
                message += inventory.isEmpty() ? " | 0 bar" : " | State unavailable";
            }
            long stroke = geometry.accumulatorVolume().toWholeLiters();
            if (stroke > 0) message += " (hydrophore: " + stroke + " L)";
        }
        aPlayer.addChatMessage(new net.minecraft.util.ChatComponentText(message));
        return true;
    }
}
