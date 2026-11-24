package gregtech.api.metatileentity.implementations.integratedfluid;

import static gregtech.api.enums.GTValues.V;
import static gregtech.api.enums.Textures.BlockIcons.OVERLAY_FRONT_LARGE_BOILER;
import static gregtech.api.enums.Textures.BlockIcons.OVERLAY_FRONT_LARGE_BOILER_ACTIVE;

import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.render.TextureFactory;
import mcp.mobius.waila.api.IWailaConfigHandler;
import mcp.mobius.waila.api.IWailaDataAccessor;

public final class MTEIntegratedFluidElectricBoiler extends MTEIntegratedFluidBoundary {

    private long paidHeatEu;

    public MTEIntegratedFluidElectricBoiler(int id, String name, String regionalName, int tier) {
        super(id, name, regionalName, tier, new String[] {
            "Power the front and connect a VFN pipe to the back",
            "Each EU heats the sealed charge without adding fluid" });
    }

    private MTEIntegratedFluidElectricBoiler(String name, int tier, String[] description, ITexture[][][] textures) {
        super(name, tier, description, textures);
    }

    @Override
    public IMetaTileEntity newMetaEntity(IGregTechTileEntity tile) {
        return new MTEIntegratedFluidElectricBoiler(mName, mTier, mDescriptionArray, mTextures);
    }

    @Override
    boolean acceptsNetworkOn(ForgeDirection side, ForgeDirection front) {
        return side == front.getOpposite();
    }

    @Override
    void onContact(IntegratedFluidNetwork back, IntegratedFluidNetwork front) {
        var tile = getBaseMetaTileEntity();
        paidHeatEu = back != null && tile.isAllowedToWork() ? back.heatFromElectricity(tile, maxEUInput() * CONTACT_TICKS) : 0;
        tile.setActive(paidHeatEu > 0);
    }

    @Override
    public boolean isEnetInput() {
        return true;
    }

    @Override
    public boolean isInputFacing(ForgeDirection side) {
        return side == getBaseMetaTileEntity().getFrontFacing();
    }

    @Override
    public long maxEUInput() {
        return V[mTier];
    }

    @Override
    public long maxEUStore() {
        return 2 * CONTACT_TICKS * maxEUInput();
    }

    @Override
    public ITexture[] getTexturesActive(ITexture base) {
        return new ITexture[] { base, TextureFactory.of(OVERLAY_FRONT_LARGE_BOILER_ACTIVE) };
    }

    @Override
    public ITexture[] getTexturesInactive(ITexture base) {
        return new ITexture[] { base, TextureFactory.of(OVERLAY_FRONT_LARGE_BOILER) };
    }

    @Override
    public void getWailaNBTData(EntityPlayerMP player, TileEntity tile, NBTTagCompound tag, World world, int x, int y, int z) {
        super.getWailaNBTData(player, tile, tag, world, x, y, z);
        tag.setDouble("boilerHeatEuPerTick", paidHeatEu / (double) CONTACT_TICKS);
    }

    @Override
    public void getWailaBody(ItemStack stack, List<String> tip, IWailaDataAccessor accessor, IWailaConfigHandler config) {
        super.getWailaBody(stack, tip, accessor, config);
        tip.add(StatCollector.translateToLocalFormatted("GT5U.vfn.boiler_heat",
            accessor.getNBTData().getDouble("boilerHeatEuPerTick")));
    }
}
