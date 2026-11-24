package gregtech.api.metatileentity.implementations.integratedfluid;

import static gregtech.api.enums.GTValues.V;
import static gregtech.api.enums.Textures.BlockIcons.STEAM_TURBINE_SIDE;
import static gregtech.api.enums.Textures.BlockIcons.STEAM_TURBINE_SIDE_ACTIVE;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizons.modularui.api.screen.ModularWindow;
import com.gtnewhorizons.modularui.api.screen.UIBuildContext;
import com.gtnewhorizons.modularui.common.widget.SlotWidget;
import gregtech.api.enums.Materials;
import gregtech.api.items.MetaGeneratedTool;
import gregtech.api.util.TurbineStatCalculator;
import gregtech.common.tools.ToolTurbine;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.integratedfluid.thermodynamics.ClosedFluidChamber;
import gregtech.api.render.TextureFactory;
import mcp.mobius.waila.api.IWailaConfigHandler;
import mcp.mobius.waila.api.IWailaDataAccessor;

public final class MTEIntegratedFluidSteamTurbine extends MTEIntegratedFluidBoundary {

    private static final BigDecimal BYPASS_LITERS = BigDecimal.valueOf(8);
    private long shaftEu;
    private Boolean lastWetExpansion;

    public MTEIntegratedFluidSteamTurbine(int id, String name, String regionalName, int tier) {
        super(id, name, regionalName, tier, 1, new String[] {
            "Dry steam or dense water in the back, exhaust through the front",
            "Install a GT turbine rotor to generate power. An empty turbine bypasses steam",
            "Supplies MV power through the four remaining sides" });
    }

    private MTEIntegratedFluidSteamTurbine(String name, int tier, String[] description, ITexture[][][] textures) {
        super(name, tier, 1, description, textures);
    }

    @Override
    public IMetaTileEntity newMetaEntity(IGregTechTileEntity tile) {
        return new MTEIntegratedFluidSteamTurbine(mName, mTier, mDescriptionArray, mTextures);
    }

    @Override
    void onContact(IntegratedFluidNetwork back, IntegratedFluidNetwork front) {
        var tile = getBaseMetaTileEntity();
        shaftEu = 0;
        if (back != null && front != null && tile.isAllowedToWork()) {
            var rotor = mInventory[0];
            if (!usableRotor(rotor)) back.bypassSteamTo(front, BYPASS_LITERS);
            else runRotor(back, front, rotor);
        }
        tile.setActive(shaftEu > 0);
    }

    private static boolean usableRotor(ItemStack stack) {
        return stack != null && stack.stackSize == 1 && stack.getItemDamage() % 2 == 0
            && stack.getItem() instanceof MetaGeneratedTool tool && tool.getToolStats(stack) instanceof ToolTurbine
            && MetaGeneratedTool.getToolDamage(stack) < MetaGeneratedTool.getToolMaxDamage(stack);
    }

    private void runRotor(IntegratedFluidNetwork back, IntegratedFluidNetwork front, ItemStack stack) {
        long stored = getEUVar();
        long budget = Math.min(maxEUStore() - stored, maxEUOutput() * CONTACT_TICKS);
        if (budget <= 0) return;
        var tool = (MetaGeneratedTool) stack.getItem();
        var rating = RotorRating.of(tool, stack);
        if (!(rating.liters > 0 && rating.efficiency > 0)) return;
        double inletK = back.getTemperature(), inletPa = back.getPressurePa();
        var move = back.expandTo(front, BigDecimal.valueOf(rating.liters), new ClosedFluidChamber.ExpansionStage(8, rating.efficiency), budget);
        if (move == null) return;
        shaftEu = move.shaftWorkEu();
        lastWetExpansion = move.wetStageExit();
        setEUVar(stored + shaftEu);
        tool.doDamage(stack, rating.wear(inletK, inletPa, move.wetStageExit()));
        if (stack.stackSize <= 0) mInventory[0] = null;
        getBaseMetaTileEntity().markDirty();
    }

    private record RotorRating(double liters, double efficiency, int temperatureK, int pressureBar) {
        static RotorRating of(MetaGeneratedTool tool, ItemStack stack) {
            var stats = new TurbineStatCalculator(tool, stack);
            // A standard steel rotor admits 8 physical liters per stroke. GT flow stats scale that intake, not EU
            double reference = 100.0 * Materials.Steel.mToolSpeed * Materials.Steel.mSteamMultiplier;
            return new RotorRating(Math.min(64, 8 * stats.getOptimalSteamFlow() / reference),
                Math.min(1, 0.7 * stats.getSteamEfficiency()), stats.tMaterial.mMeltingPoint,
                Math.max(0, stats.tMaterial.mToolQuality) + 1);
        }

        int wear(double inletK, double inletPa, boolean wetExit) {
            return 1 + (wetExit ? 5 : 0) + (temperatureK > 0 && inletK > temperatureK ? 2 : 0)
                + (inletPa > pressureBar * 100000.0 ? 2 : 0);
        }
    }

    @Override
    public boolean isValidSlot(int index) { return index == 0; }

    @Override
    public boolean onRightclick(IGregTechTileEntity tile, EntityPlayer player) {
        openGui(player);
        return true;
    }

    @Override
    public void addUIWidgets(ModularWindow.Builder builder, UIBuildContext context) {
        builder.widget(new SlotWidget(inventoryHandler, 0).setFilter(MTEIntegratedFluidSteamTurbine::usableRotor)
            .setBackground(getGUITextureSet().getItemSlot()).setPos(79, 31));
    }

    @Override
    public boolean isEnetOutput() {
        return true;
    }

    @Override
    public boolean isOutputFacing(ForgeDirection side) {
        return side != ForgeDirection.UNKNOWN && !acceptsNetworkOn(side, getBaseMetaTileEntity().getFrontFacing());
    }

    @Override
    public long maxEUOutput() {
        return V[mTier];
    }

    @Override
    public long getMinimumStoredEU() {
        return 0;
    }

    @Override
    public long maxEUStore() {
        return 2 * CONTACT_TICKS * maxEUOutput();
    }

    @Override
    public ITexture[] getTexture(IGregTechTileEntity tile, ForgeDirection side, ForgeDirection facing, int color,
        boolean active, boolean redstone) {
        return super.getTexture(tile, acceptsNetworkOn(side, facing) ? side : facing, facing, color, active, redstone);
    }

    @Override
    public ITexture[] getTexturesActive(ITexture base) {
        return new ITexture[] { base, TextureFactory.of(STEAM_TURBINE_SIDE_ACTIVE) };
    }

    @Override
    public ITexture[] getTexturesInactive(ITexture base) {
        return new ITexture[] { base, TextureFactory.of(STEAM_TURBINE_SIDE) };
    }

    @Override
    public void getWailaNBTData(EntityPlayerMP player, TileEntity tile, NBTTagCompound tag, World world, int x, int y, int z) {
        super.getWailaNBTData(player, tile, tag, world, x, y, z);
        tag.setDouble("shaftEuPerTick", shaftEu / (double) CONTACT_TICKS);
        if (lastWetExpansion != null) tag.setBoolean("vfnLastWetExpansion", lastWetExpansion);
        var stack = mInventory[0];
        if (usableRotor(stack)) {
            var rating = RotorRating.of((MetaGeneratedTool) stack.getItem(), stack);
            tag.setDouble("vfnRotorIntake", rating.liters);
            tag.setDouble("vfnRotorEfficiency", rating.efficiency * 100);
            tag.setInteger("vfnRotorPressure", rating.pressureBar);
            tag.setInteger("vfnRotorTemperature", rating.temperatureK);
        }
    }

    @Override
    public void getWailaBody(ItemStack stack, List<String> tip, IWailaDataAccessor accessor, IWailaConfigHandler config) {
        super.getWailaBody(stack, tip, accessor, config);
        var tag = accessor.getNBTData();
        tip.add(StatCollector.translateToLocalFormatted("GT5U.vfn.turbine_work", String.format(Locale.ROOT, "%.2f", tag.getDouble("shaftEuPerTick"))));
        if (tag.hasKey("vfnLastWetExpansion")) tip.add(StatCollector.translateToLocal(tag.getBoolean("vfnLastWetExpansion")
            ? "GT5U.vfn.turbine_stage_wet" : "GT5U.vfn.turbine_stage_dry"));
        if (!tag.hasKey("vfnRotorIntake")) tip.add(StatCollector.translateToLocal("GT5U.vfn.turbine_bypass"));
        else {
            tip.add(StatCollector.translateToLocalFormatted("GT5U.vfn.rotor_rating",
                String.format(Locale.ROOT, "%.2f", tag.getDouble("vfnRotorIntake")),
                Math.round(tag.getDouble("vfnRotorEfficiency")), tag.getInteger("vfnRotorPressure")));
            if (tag.getInteger("vfnRotorTemperature") > 0) tip.add(StatCollector.translateToLocalFormatted(
                "GT5U.vfn.rotor_temperature", tag.getInteger("vfnRotorTemperature")));
        }
    }
}
