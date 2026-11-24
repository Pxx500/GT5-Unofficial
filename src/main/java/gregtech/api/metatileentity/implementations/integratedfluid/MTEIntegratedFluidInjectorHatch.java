package gregtech.api.metatileentity.implementations.integratedfluid;

import static gregtech.api.enums.Textures.BlockIcons.OVERLAY_PIPE_IN;
import static gregtech.api.enums.Textures.BlockIcons.OVERLAY_PIPE_OUT;

import java.util.List;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.IFluidHandler;

import gregtech.api.enums.Dyes;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatch;
import gregtech.api.render.TextureFactory;
import mcp.mobius.waila.api.IWailaConfigHandler;
import mcp.mobius.waila.api.IWailaDataAccessor;

/**
 * Integrated Fluid Injector Hatch - Bridges normal GT fluid pipes with the integrated fluid network.
 * This hatch can receive fluid from normal GT fluid pipes and inject it into the integrated fluid network.
 * It acts as an IFluidHandler to accept fluids from connected GT pipes.
 */
public class MTEIntegratedFluidInjectorHatch extends MTEHatch implements IIntegratedFluidMember, IFluidHandler {

    private IntegratedFluidNetwork network;
    private java.util.UUID networkId;

    public MTEIntegratedFluidInjectorHatch(int aID, String aName, String aNameRegional, int aTier) {
        super(
            aID,
            aName,
            aNameRegional,
            aTier,
            0, // No inventory slots
            new String[] { "Integrated Fluid Injector Hatch", "Bridges GT Fluid Pipes with Integrated Fluid Network",
                "Accepts supported untagged fluids at local ambient temperature", "GT steam enters as saturated vapor at 100 C",
                "Injector hatch does not add network capacity",
                "Fills the network up to 0.90 bar" });
    }

    public MTEIntegratedFluidInjectorHatch(String aName, int aTier, String[] aDescription, ITexture[][][] aTextures) {
        super(aName, aTier, 0, aDescription, aTextures);
    }

    @Override
    public IMetaTileEntity newMetaEntity(IGregTechTileEntity aTileEntity) {
        return new MTEIntegratedFluidInjectorHatch(mName, mTier, mDescriptionArray, mTextures);
    }

    @Override
    public ITexture[] getTexturesActive(ITexture aBaseTexture) {
        return new ITexture[] { aBaseTexture,
            TextureFactory.of(OVERLAY_PIPE_IN, Dyes.getModulation(-1, new short[] { 255, 255, 64, 255 })),
            TextureFactory.of(OVERLAY_PIPE_OUT, Dyes.getModulation(-1, new short[] { 64, 255, 255, 255 })) };
    }

    @Override
    public ITexture[] getTexturesInactive(ITexture aBaseTexture) {
        return new ITexture[] { aBaseTexture,
            TextureFactory.of(OVERLAY_PIPE_IN, Dyes.getModulation(-1, new short[] { 192, 192, 64, 255 })),
            TextureFactory.of(OVERLAY_PIPE_OUT, Dyes.getModulation(-1, new short[] { 64, 192, 192, 255 })) };
    }

    @Override
    public boolean isFacingValid(ForgeDirection facing) {
        return true;
    }

    @Override
    public boolean isAccessAllowed(EntityPlayer aPlayer) {
        return true;
    }

    @Override
    public boolean isValidSlot(int aIndex) {
        return false;
    }

    @Override
    public void saveNBTData(NBTTagCompound aNBT) {
        super.saveNBTData(aNBT);
        if (networkId != null) {
            aNBT.setLong("ifnNetworkIdMost", networkId.getMostSignificantBits());
            aNBT.setLong("ifnNetworkIdLeast", networkId.getLeastSignificantBits());
        }
    }

    @Override
    public void loadNBTData(NBTTagCompound aNBT) {
        super.loadNBTData(aNBT);
        if (aNBT.hasKey("ifnNetworkIdMost") && aNBT.hasKey("ifnNetworkIdLeast")) {
            long most = aNBT.getLong("ifnNetworkIdMost");
            long least = aNBT.getLong("ifnNetworkIdLeast");
            networkId = new java.util.UUID(most, least);
        } else {
            networkId = null;
        }
    }

    @Override
    public void onFirstTick(IGregTechTileEntity aBaseMetaTileEntity) {
        super.onFirstTick(aBaseMetaTileEntity);
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
            } else {
                // Check if any neighbors have different networks
                List<IIntegratedFluidMember> neighbors = manager.findConnectedNeighbors(this);
                boolean shouldMerge = false;

                for (IIntegratedFluidMember neighbor : neighbors) {
                    if (neighbor.getNetwork() != null && neighbor.getNetwork() != network) {
                        shouldMerge = true;
                        break;
                    }
                }

                if (shouldMerge) {
                    manager.onMemberAdded(this);
                }
            }
        }
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

    @Override
    public void getWailaNBTData(EntityPlayerMP player, TileEntity tile, NBTTagCompound tag, World world, int x, int y,
        int z) {
        super.getWailaNBTData(player, tile, tag, world, x, y, z);
        IFNWailaFormatter.writeNetworkStatus(network, tag);
    }

    @Override
    public void getWailaBody(ItemStack itemStack, List<String> currenttip, IWailaDataAccessor accessor,
        IWailaConfigHandler config) {
        super.getWailaBody(itemStack, currenttip, accessor, config);
        NBTTagCompound tag = accessor.getNBTData();
        if (tag.getBoolean("hasNetwork")) {
            IFNWailaFormatter.addNetworkStatus(tag, currenttip);
            IFNWailaFormatter.addFluidStorageSummary(tag, currenttip, "Network Fluid", false);
            currenttip.add("Network Members: " + tag.getInteger("memberCount"));
            float pressure = tag.getFloat("pressure");
            IFNWailaFormatter.addOperatingConditions(tag, currenttip);
            if (pressure >= IFNPressurePolicy.injectorCutoffPressureBar()) {
                currenttip.add(
                    EnumChatFormatting.RED + "Intake closed at 0.90 bar" + EnumChatFormatting.RESET);
            }
        } else {
            currenttip.add(EnumChatFormatting.RED + "No network connected" + EnumChatFormatting.RESET);
        }
    }

    // IIntegratedFluidMember implementation

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
    public void onNetworkUpdate() {
        if (getBaseMetaTileEntity() != null) {
            getBaseMetaTileEntity().issueTextureUpdate();
        }
    }

    @Override
    public int getCapacityContribution() {
        // Injector hatch adds 0L (0 mB) of capacity
        return 0;
    }

    // IFluidHandler implementation - allows GT fluid pipes to push fluid into us

    @Override
    public int fill(ForgeDirection from, FluidStack resource, boolean doFill) {
        return fill(resource, doFill);
    }

    @Override
    public int fill(FluidStack resource, boolean doFill) {
        if (network == null) {
            NetworkManager manager = NetworkManager.getInstance(getBaseMetaTileEntity().getWorld());
            manager.onMemberAdded(this);
        }
        return network == null ? 0 : network.admit(resource, doFill, IFNAmbientTemperature.atPort(getBaseMetaTileEntity()));
    }

    @Override
    public void onBlockDestroyed() {
        NetworkManager.onBlockDestroyed(this, getBaseMetaTileEntity());
        super.onBlockDestroyed();
    }

    @Override
    public void onRemoval() {
        super.onRemoval();
        // Use NetworkManager to properly handle removal
        IGregTechTileEntity baseTile = getBaseMetaTileEntity();
        if (baseTile != null && baseTile.isServerSide()) {
            NetworkManager manager = NetworkManager.getInstance(baseTile.getWorld());
            manager.onMemberRemoved(this);
        }
    }

    @Override
    public void onMachineBlockUpdate() {
        // DON'T rebuild network here - causes fluid scaling issues
        // Network is properly managed via onFirstTick/onMemberAdded
    }

}
