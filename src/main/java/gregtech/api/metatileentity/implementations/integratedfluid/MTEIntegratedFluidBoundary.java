package gregtech.api.metatileentity.implementations.integratedfluid;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.util.ForgeDirection;

import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.MetaPipeEntity;
import gregtech.api.metatileentity.implementations.MTEHatch;

/** A machine connected to chambers without contributing to their inventories. */
abstract class MTEIntegratedFluidBoundary extends MTEHatch {

    static final int CONTACT_TICKS = 20;

    MTEIntegratedFluidBoundary(int id, String name, String regionalName, int tier, String[] description) {
        super(id, name, regionalName, tier, 0, description);
    }

    MTEIntegratedFluidBoundary(String name, int tier, String[] description, ITexture[][][] textures) {
        super(name, tier, 0, description, textures);
    }

    @Override
    public final void onPostTick(IGregTechTileEntity tile, long tick) {
        super.onPostTick(tile, tick);
        if (!tile.isServerSide() || tick % CONTACT_TICKS != 0) return;
        ForgeDirection front = tile.getFrontFacing();
        ForgeDirection back = front.getOpposite();
        onContact(acceptsNetworkOn(back, front) ? adjacent(tile, back) : null,
            acceptsNetworkOn(front, front) ? adjacent(tile, front) : null);
    }

    boolean acceptsNetworkOn(ForgeDirection side, ForgeDirection front) {
        return side == front || side == front.getOpposite();
    }

    abstract void onContact(IntegratedFluidNetwork back, IntegratedFluidNetwork front);

    private static IntegratedFluidNetwork adjacent(IGregTechTileEntity tile, ForgeDirection side) {
        if (!(tile.getTileEntityAtSide(side) instanceof IGregTechTileEntity neighbor)
            || !(neighbor.getMetaTileEntity() instanceof IIntegratedFluidMember member)) return null;
        ForgeDirection face = side.getOpposite();
        boolean connected = member instanceof MetaPipeEntity pipe ? pipe.isConnectedAtSide(face) : neighbor.getFrontFacing() == face;
        return connected ? member.getNetwork() : null;
    }

    @Override
    public boolean isFacingValid(ForgeDirection facing) {
        return true;
    }

    @Override
    public boolean isAccessAllowed(EntityPlayer player) {
        return true;
    }

    @Override
    public boolean isValidSlot(int index) {
        return false;
    }

    @Override
    public boolean allowPullStack(IGregTechTileEntity tile, int index, ForgeDirection side, ItemStack stack) {
        return false;
    }

    @Override
    public boolean allowPutStack(IGregTechTileEntity tile, int index, ForgeDirection side, ItemStack stack) {
        return false;
    }
}
