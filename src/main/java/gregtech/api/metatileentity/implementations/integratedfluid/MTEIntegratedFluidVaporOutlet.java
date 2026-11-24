package gregtech.api.metatileentity.implementations.integratedfluid;

import static gregtech.api.enums.Textures.BlockIcons.FLUID_OUT_SIGN;
import static gregtech.api.enums.Textures.BlockIcons.OVERLAY_PIPE_OUT;

import java.math.BigDecimal;
import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;

import gregtech.GTMod;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.render.TextureFactory;
import mcp.mobius.waila.api.IWailaConfigHandler;
import mcp.mobius.waila.api.IWailaDataAccessor;

public final class MTEIntegratedFluidVaporOutlet extends MTEIntegratedFluidBoundary {

    private static final BigDecimal VAPOR_LITERS_PER_PACKET = BigDecimal.valueOf(20);
    private long movedMicroKg;

    public MTEIntegratedFluidVaporOutlet(int id, String name, String regionalName, int tier) {
        super(id, name, regionalName, tier, new String[] {
            "Draws vapor from the rear chamber into the front pipe",
            "Liquid stays behind. Requires higher inlet pressure" });
    }

    private MTEIntegratedFluidVaporOutlet(String name, int tier, String[] description, ITexture[][][] textures) {
        super(name, tier, description, textures);
    }

    @Override
    public IMetaTileEntity newMetaEntity(IGregTechTileEntity tile) {
        return new MTEIntegratedFluidVaporOutlet(mName, mTier, mDescriptionArray, mTextures);
    }

    @Override
    void onContact(IntegratedFluidNetwork back, IntegratedFluidNetwork front) {
        movedMicroKg = back == null ? 0 : back.drawVaporTo(front, VAPOR_LITERS_PER_PACKET);
        getBaseMetaTileEntity().setActive(movedMicroKg > 0);
    }

    @Override
    public ITexture[] getTexturesActive(ITexture base) {
        return GTMod.proxy.mRenderIndicatorsOnHatch
            ? new ITexture[] { base, TextureFactory.of(OVERLAY_PIPE_OUT), TextureFactory.of(FLUID_OUT_SIGN) }
            : new ITexture[] { base, TextureFactory.of(OVERLAY_PIPE_OUT) };
    }

    @Override
    public ITexture[] getTexturesInactive(ITexture base) {
        return getTexturesActive(base);
    }

    @Override
    public void getWailaNBTData(EntityPlayerMP player, TileEntity tile, NBTTagCompound tag, World world, int x, int y, int z) {
        super.getWailaNBTData(player, tile, tag, world, x, y, z);
        tag.setDouble("vaporGramsPerTick", movedMicroKg / (1000d * CONTACT_TICKS));
    }

    @Override
    public void getWailaBody(ItemStack stack, List<String> tip, IWailaDataAccessor accessor, IWailaConfigHandler config) {
        super.getWailaBody(stack, tip, accessor, config);
        tip.add(StatCollector.translateToLocalFormatted("GT5U.vfn.vapor_flow", accessor.getNBTData().getDouble("vaporGramsPerTick")));
    }
}
