package gregtech.api.gui.modularui;

import com.gtnewhorizons.modularui.api.UIInfos;
import com.gtnewhorizons.modularui.api.screen.ITileWithModularUI;
import com.gtnewhorizons.modularui.api.screen.ModularUIContext;
import com.gtnewhorizons.modularui.api.screen.ModularWindow;
import com.gtnewhorizons.modularui.api.screen.UIBuildContext;
import com.gtnewhorizons.modularui.common.builder.UIBuilder;
import com.gtnewhorizons.modularui.common.builder.UIInfo;
import com.gtnewhorizons.modularui.common.internal.network.NetworkUtils;
import com.gtnewhorizons.modularui.common.internal.wrapper.ModularGui;
import com.gtnewhorizons.modularui.common.internal.wrapper.ModularUIContainer;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import gregtech.api.enums.GT_Values;
import gregtech.api.interfaces.tileentity.ICoverable;
import gregtech.api.interfaces.tileentity.IHasWorldObjectAndCoords;
import gregtech.api.net.GT_Packet_SendCoverData;
import gregtech.api.util.GT_CoverBehaviorBase;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

public class GT_UIInfos {

    /**
     * Generator for {@link UIInfo} which is responsible for registering and opening UIs.
     * Unlike {@link com.gtnewhorizons.modularui.api.UIInfos#TILE_MODULAR_UI}, this accepts
     * custom constructors for UI.
     * <br> Do NOT run {@link UIBuilder#build} on-the-fly, otherwise MP client won't register UIs.
     * Instead, store to static field, just like {@link #GTTileEntityDefaultUI}.
     * Such mistake can be easily overlooked by testing only SP.
     */
    public static final Function<ContainerConstructor, UIInfo<?, ?>> GTTileEntityUIFactory =
            containerConstructor -> UIBuilder.of()
                    .container((player, world, x, y, z) -> {
                        TileEntity te = world.getTileEntity(x, y, z);
                        if (te instanceof ITileWithModularUI) {
                            return createTileEntityContainer(
                                    player,
                                    ((ITileWithModularUI) te)::createWindow,
                                    te::markDirty,
                                    containerConstructor);
                        }
                        return null;
                    })
                    .gui(((player, world, x, y, z) -> {
                        if (!world.isRemote) return null;
                        TileEntity te = world.getTileEntity(x, y, z);
                        if (te instanceof ITileWithModularUI) {
                            return createTileEntityGuiContainer(
                                    player, ((ITileWithModularUI) te)::createWindow, containerConstructor);
                        }
                        return null;
                    }))
                    .build();

    private static final UIInfo<?, ?> GTTileEntityDefaultUI = GTTileEntityUIFactory.apply(ModularUIContainer::new);

    private static final Map<Byte, UIInfo<?, ?>> coverUI = new HashMap<>();

    static {
        for (byte i = 0; i < ForgeDirection.VALID_DIRECTIONS.length; i++) {
            final byte side = i;
            coverUI.put(
                    side,
                    UIBuilder.of()
                            .container((player, world, x, y, z) -> {
                                TileEntity te = world.getTileEntity(x, y, z);
                                if (!(te instanceof ICoverable)) return null;
                                ICoverable gtTileEntity = (ICoverable) te;
                                GT_CoverBehaviorBase<?> cover = gtTileEntity.getCoverBehaviorAtSideNew(side);
                                return createCoverContainer(
                                        player,
                                        cover::createWindow,
                                        te::markDirty,
                                        gtTileEntity.getCoverIDAtSide(side),
                                        side,
                                        gtTileEntity);
                            })
                            .gui((player, world, x, y, z) -> {
                                if (!world.isRemote) return null;
                                TileEntity te = world.getTileEntity(x, y, z);
                                if (!(te instanceof ICoverable)) return null;
                                ICoverable gtTileEntity = (ICoverable) te;
                                GT_CoverBehaviorBase<?> cover = gtTileEntity.getCoverBehaviorAtSideNew(side);
                                return createCoverGuiContainer(
                                        player,
                                        cover::createWindow,
                                        gtTileEntity.getCoverIDAtSide(side),
                                        side,
                                        gtTileEntity);
                            })
                            .build());
        }
    }

    /**
     * Opens TileEntity UI, created by {@link ITileWithModularUI#createWindow}.
     */
    public static void openGTTileEntityUI(IHasWorldObjectAndCoords aTileEntity, EntityPlayer aPlayer) {
        if (aTileEntity.isClientSide()) return;
        GTTileEntityDefaultUI.open(
                aPlayer,
                aTileEntity.getWorld(),
                aTileEntity.getXCoord(),
                aTileEntity.getYCoord(),
                aTileEntity.getZCoord());
    }

    /**
     * Opens cover UI, created by {@link GT_CoverBehaviorBase#createWindow}.
     */
    public static void openCoverUI(ICoverable tileEntity, EntityPlayer player, byte side) {
        if (tileEntity.isClientSide()) return;

        GT_Values.NW.sendToPlayer(
                new GT_Packet_SendCoverData(
                        side,
                        tileEntity.getCoverIDAtSide(side),
                        tileEntity.getComplexCoverDataAtSide(side),
                        tileEntity),
                (EntityPlayerMP) player);

        coverUI.get(side)
                .open(
                        player,
                        tileEntity.getWorld(),
                        tileEntity.getXCoord(),
                        tileEntity.getYCoord(),
                        tileEntity.getZCoord());
    }

    /**
     * Opens UI for player's item, created by {@link com.gtnewhorizons.modularui.api.screen.IItemWithModularUI#createWindow}.
     */
    public static void openPlayerHeldItemUI(EntityPlayer player) {
        if (NetworkUtils.isClient()) return;
        UIInfos.PLAYER_HELD_ITEM_UI.open(player);
    }

    private static ModularUIContainer createTileEntityContainer(
            EntityPlayer player,
            Function<UIBuildContext, ModularWindow> windowCreator,
            Runnable onWidgetUpdate,
            ContainerConstructor containerCreator) {
        UIBuildContext buildContext = new UIBuildContext(player);
        ModularWindow window = windowCreator.apply(buildContext);
        if (window == null) {
            return null;
        }
        return containerCreator.of(new ModularUIContext(buildContext, onWidgetUpdate), window);
    }

    @SideOnly(Side.CLIENT)
    private static ModularGui createTileEntityGuiContainer(
            EntityPlayer player,
            Function<UIBuildContext, ModularWindow> windowCreator,
            ContainerConstructor containerConstructor) {
        ModularUIContainer container = createTileEntityContainer(player, windowCreator, null, containerConstructor);
        if (container == null) {
            return null;
        }
        return new ModularGui(container);
    }

    private static ModularUIContainer createCoverContainer(
            EntityPlayer player,
            Function<GT_CoverUIBuildContext, ModularWindow> windowCreator,
            Runnable onWidgetUpdate,
            int coverID,
            byte side,
            ICoverable tile) {
        GT_CoverUIBuildContext buildContext = new GT_CoverUIBuildContext(player, coverID, side, tile, false);
        ModularWindow window = windowCreator.apply(buildContext);
        if (window == null) {
            return null;
        }
        return new ModularUIContainer(new ModularUIContext(buildContext, onWidgetUpdate), window);
    }

    @SideOnly(Side.CLIENT)
    private static ModularGui createCoverGuiContainer(
            EntityPlayer player,
            Function<GT_CoverUIBuildContext, ModularWindow> windowCreator,
            int coverID,
            byte side,
            ICoverable tile) {
        ModularUIContainer container = createCoverContainer(player, windowCreator, null, coverID, side, tile);
        if (container == null) {
            return null;
        }
        return new ModularGui(container);
    }

    @FunctionalInterface
    public interface ContainerConstructor {
        ModularUIContainer of(ModularUIContext context, ModularWindow mainWindow);
    }
}
