package gregtech.api.metatileentity.implementations.integratedfluid;

import static gregtech.api.enums.HatchElement.Dynamo;
import static gregtech.api.enums.Textures.BlockIcons.STEAM_TURBINE_SIDE;
import static gregtech.api.enums.Textures.BlockIcons.STEAM_TURBINE_SIDE_ACTIVE;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizon.structurelib.structure.IStructureDefinition;

import gregtech.api.GregTechAPI;
import gregtech.api.enums.Materials;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.items.MetaGeneratedTool;
import gregtech.api.metatileentity.implementations.integratedfluid.thermodynamics.ClosedFluidChamber;
import gregtech.api.recipe.check.CheckRecipeResult;
import gregtech.api.recipe.check.SimpleCheckRecipeResult;
import gregtech.api.render.TextureFactory;
import gregtech.api.util.MultiblockTooltipBuilder;
import gregtech.api.util.TurbineStatCalculator;
import gregtech.common.gui.modularui.multiblock.MTEVFNSteamTurbineGui;
import gregtech.common.tools.ToolTurbine;
import mcp.mobius.waila.api.IWailaConfigHandler;
import mcp.mobius.waila.api.IWailaDataAccessor;

public final class MTEIntegratedFluidSteamTurbine extends MTEVFNFlowMachine<MTEIntegratedFluidSteamTurbine> {

    private static final IStructureDefinition<MTEIntegratedFluidSteamTurbine> STRUCTURE = steelHousing(MTEIntegratedFluidSteamTurbine.class, Dynamo);
    private static final int STROKE_TICKS = 20;
    private long shaftEu;
    private Boolean lastWetExpansion;
    private int opening = 100, minInletCbar;

    public MTEIntegratedFluidSteamTurbine(int id, String name, String regionalName) { super(id, name, regionalName); }
    private MTEIntegratedFluidSteamTurbine(String name) { super(name); }

    @Override public IMetaTileEntity newMetaEntity(IGregTechTileEntity tile) { return new MTEIntegratedFluidSteamTurbine(mName); }
    @Override protected boolean hasPowerHatch() { return mDynamoHatches.size() == 1; }
    @Override public IStructureDefinition<MTEIntegratedFluidSteamTurbine> getStructureDefinition() { return STRUCTURE; }

    @Override public CheckRecipeResult checkProcessing() {
        shaftEu = 0;
        var back = input != null && input.isValid() ? input.getNetwork() : null;
        var front = output != null && output.isValid() ? output.getNetwork() : null;
        if (back == front || !IFNNetworkTransferGate.isOperational(back) || !IFNNetworkTransferGate.isOperational(front))
            return idle("networks");
        if (!hasPowerHatch() || !mDynamoHatches.get(0).isValid()) return idle("no_dynamo");
        if (opening == 0) return idle("closed");
        if (back.getAmountQ() == 0) return idle("empty");
        if (!back.containsWater()) return idle("wrong_fluid");
        if (back.getPressurePa() < minInletCbar * 1000.0) return idle("inlet_floor");
        if (back.getPressurePa() <= front.getPressurePa()) return idle("backpressure");
        var rotor = getControllerSlot();
        final String operation;
        if (rotor == null) {
            if (back.bypassSteamTo(front, intake(8)) == 0) return idle("bypass_wait");
            operation = "bypass";
        } else {
            if (!usableRotor(rotor)) return idle("invalid");
            if (!back.hasExpandableWater()) return idle("wrong_phase");
            var dynamo = mDynamoHatches.get(0);
            var store = dynamo.getBaseMetaTileEntity();
            if (store.getStoredEU() >= store.getEUCapacity()) return idle("dynamo_full");
            var tool = (MetaGeneratedTool) rotor.getItem();
            var rating = RotorRating.of(tool, rotor);
            double inletK = back.getTemperature(), inletPa = back.getPressurePa();
            if (!(rating.liters > 0 && rating.efficiency > 0)) return idle("invalid_rating");
            var stroke = intake(rating.liters);
            var move = back.expandTo(front, stroke, new ClosedFluidChamber.ExpansionStage(8, rating.efficiency),
                store, dynamo.maxEUOutput() * dynamo.maxAmperesOut() * STROKE_TICKS);
            if (move == null) return idle(back.intakeStrokeTooSmall(stroke.doubleValue(), 1) ? "small_intake" : "no_stroke");
            shaftEu = move.shaftWorkEu();
            lastWetExpansion = move.wetStageExit();
            tool.doDamage(rotor, rating.wear(inletK, inletPa, move.wetStageExit()));
            if (rotor.stackSize <= 0) mInventory[getControllerSlotIndex()] = null;
            store.markDirty();
            operation = "running";
        }
        getBaseMetaTileEntity().markDirty();
        mEUt = 0;
        mEfficiency = 10000;
        mMaxProgresstime = STROKE_TICKS;
        return SimpleCheckRecipeResult.ofSuccess("vfn_turbine_" + operation);
    }

    private BigDecimal intake(double liters) { return BigDecimal.valueOf(liters * opening / 100.0); }
    private CheckRecipeResult idle(String reason) {
        return SimpleCheckRecipeResult.ofFailure("vfn_turbine_" + reason);
    }

    private static boolean usableRotor(ItemStack stack) {
        return stack != null && stack.stackSize == 1 && stack.getItemDamage() % 2 == 0
            && stack.getItem() instanceof MetaGeneratedTool tool && tool.getToolStats(stack) instanceof ToolTurbine
            && MetaGeneratedTool.getToolDamage(stack) < MetaGeneratedTool.getToolMaxDamage(stack);
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

    public int admissionPercent() { return opening; }
    public void setAdmissionPercent(int value) { opening = Math.max(0, Math.min(100, value)); getBaseMetaTileEntity().markDirty(); }
    public int minimumInletCbar() { return minInletCbar; }
    public void setMinimumInletCbar(int value) { minInletCbar = Math.max(0, Math.min(35000, value)); getBaseMetaTileEntity().markDirty(); }
    public long lastStrokeEu() { return shaftEu; }
    public long dynamoRoomEu() {
        if (!hasPowerHatch() || !mDynamoHatches.get(0).isValid()) return 0;
        var tile = mDynamoHatches.get(0).getBaseMetaTileEntity();
        return tile.getEUCapacity() - tile.getStoredEU();
    }

    @Override public void saveNBTData(NBTTagCompound tag) {
        super.saveNBTData(tag);
        tag.setInteger("admissionPct", opening);
        tag.setInteger("minInletCbar", minInletCbar);
    }
    @Override public void loadNBTData(NBTTagCompound tag) {
        super.loadNBTData(tag);
        if (tag.hasKey("admissionPct")) opening = Math.max(0, Math.min(100, tag.getInteger("admissionPct")));
        minInletCbar = Math.max(0, Math.min(35000, tag.getInteger("minInletCbar")));
    }
    @Override protected boolean useMui2() { return true; }
    @Override protected MTEVFNSteamTurbineGui getGui() { return new MTEVFNSteamTurbineGui(this); }
    @Override protected MultiblockTooltipBuilder createTooltip() {
        return new MultiblockTooltipBuilder().addMachineType("Steam Turbine")
            .addInfo("Expands dry steam or dense water through a GT rotor")
            .addInfo("The dynamo receives the work. Without a rotor, steam bypasses the shaft")
            .beginStructureBlock(3, 3, 3, true).addController("Front center")
            .addCasingInfoExactly("Solid Steel Machine Casing", 22, false)
            .addInputHatch("One VFN input hatch", 1).addOutputHatch("One VFN exhaust hatch", 1)
            .addDynamoHatch("One dynamo hatch", 1).addMaintenanceHatch("One maintenance hatch", 1).toolTipFinisher("GregTech");
    }
    @Override public ITexture[] getTexture(IGregTechTileEntity tile, ForgeDirection side, ForgeDirection facing, int color,
        boolean active, boolean redstone) {
        var casing = TextureFactory.of(GregTechAPI.sBlockCasings2, 0);
        return side == facing ? new ITexture[] { casing, TextureFactory.of(active ? STEAM_TURBINE_SIDE_ACTIVE : STEAM_TURBINE_SIDE) }
            : new ITexture[] { casing };
    }

    @Override
    public void getWailaNBTData(EntityPlayerMP player, TileEntity tile, NBTTagCompound tag, World world, int x, int y, int z) {
        super.getWailaNBTData(player, tile, tag, world, x, y, z);
        tag.setDouble("shaftEuPerTick", (mMaxProgresstime > 0 ? shaftEu : 0) / (double) STROKE_TICKS);
        if (lastWetExpansion != null) tag.setBoolean("vfnLastWetExpansion", lastWetExpansion);
        var stack = getControllerSlot();
        if (stack != null && !usableRotor(stack)) tag.setBoolean("vfnInvalidRotor", true);
        if (usableRotor(stack)) {
            var rating = RotorRating.of((MetaGeneratedTool) stack.getItem(), stack);
            tag.setDouble("vfnRotorIntake", intake(rating.liters).doubleValue());
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
        if (tag.getBoolean("vfnInvalidRotor")) tip.add(StatCollector.translateToLocal("GT5U.gui.text.recipe_result.vfn_turbine_invalid"));
        else if (!tag.hasKey("vfnRotorIntake")) tip.add(StatCollector.translateToLocal("GT5U.vfn.turbine_bypass"));
        else {
            tip.add(StatCollector.translateToLocalFormatted("GT5U.vfn.rotor_rating",
                String.format(Locale.ROOT, "%.2f", tag.getDouble("vfnRotorIntake")),
                Math.round(tag.getDouble("vfnRotorEfficiency")), tag.getInteger("vfnRotorPressure")));
            if (tag.getInteger("vfnRotorTemperature") > 0) tip.add(StatCollector.translateToLocalFormatted(
                "GT5U.vfn.rotor_temperature", tag.getInteger("vfnRotorTemperature")));
        }
    }
}
