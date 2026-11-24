package gregtech.api.metatileentity.implementations.integratedfluid;

import static gregtech.api.enums.Textures.BlockIcons.OVERLAY_FRONT_HEAT_EXCHANGER;
import static gregtech.api.enums.Textures.BlockIcons.OVERLAY_FRONT_HEAT_EXCHANGER_ACTIVE;

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

public final class MTEIntegratedFluidHeatExchanger extends MTEIntegratedFluidBoundary {

    private static final double CONDUCTANCE_EU_PER_TICK_KELVIN = 1.0;
    private long heatToFrontMicroEu;

    public MTEIntegratedFluidHeatExchanger(int id, String name, String regionalName, int tier) {
        super(id, name, regionalName, tier, new String[] {
            "Transfers heat between the front and back VFN connections",
            "The hotter network heats the colder one without mixing fluids" });
    }

    private MTEIntegratedFluidHeatExchanger(String name, int tier, String[] description, ITexture[][][] textures) {
        super(name, tier, description, textures);
    }

    @Override
    public IMetaTileEntity newMetaEntity(IGregTechTileEntity tile) {
        return new MTEIntegratedFluidHeatExchanger(mName, mTier, mDescriptionArray, mTextures);
    }

    @Override
    void onContact(IntegratedFluidNetwork back, IntegratedFluidNetwork front) {
        heatToFrontMicroEu = back == null ? 0 : back.conductHeatTo(front, CONTACT_TICKS * CONDUCTANCE_EU_PER_TICK_KELVIN);
        getBaseMetaTileEntity().setActive(heatToFrontMicroEu != 0);
    }

    @Override
    public ITexture[] getTexture(IGregTechTileEntity tile, ForgeDirection side, ForgeDirection facing,
        int color, boolean active, boolean redstone) {
        return super.getTexture(tile, side == facing.getOpposite() ? facing : side, facing, color, active, redstone);
    }

    @Override
    public ITexture[] getTexturesActive(ITexture base) {
        return new ITexture[] { base, TextureFactory.of(OVERLAY_FRONT_HEAT_EXCHANGER_ACTIVE) };
    }

    @Override
    public ITexture[] getTexturesInactive(ITexture base) {
        return new ITexture[] { base, TextureFactory.of(OVERLAY_FRONT_HEAT_EXCHANGER) };
    }

    @Override
    public void getWailaNBTData(EntityPlayerMP player, TileEntity tile, NBTTagCompound tag, World world, int x, int y, int z) {
        super.getWailaNBTData(player, tile, tag, world, x, y, z);
        tag.setDouble("heatToFrontEuPerTick", heatToFrontMicroEu / (1_000_000d * CONTACT_TICKS));
    }

    @Override
    public void getWailaBody(ItemStack stack, List<String> tip, IWailaDataAccessor accessor, IWailaConfigHandler config) {
        super.getWailaBody(stack, tip, accessor, config);
        tip.add(StatCollector.translateToLocalFormatted("GT5U.vfn.heat_to_front",
            accessor.getNBTData().getDouble("heatToFrontEuPerTick")));
    }
}
