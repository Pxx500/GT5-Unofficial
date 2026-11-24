package gregtech.common.tileentities.machines.multi;

import static com.gtnewhorizon.structurelib.structure.StructureUtility.*;
import static gregtech.api.enums.HatchElement.*;
import static gregtech.api.util.GTStructureUtility.buildHatchAdder;
import static gregtech.api.util.GTStructureUtility.ofHatchAdder;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.item.ItemStack;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.Fluid;

import org.jetbrains.annotations.NotNull;

import com.gtnewhorizon.structurelib.alignment.constructable.ISurvivalConstructable;
import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.ISurvivalBuildEnvironment;
import com.gtnewhorizon.structurelib.structure.StructureDefinition;

import gregtech.api.GregTechAPI;
import gregtech.api.enums.Textures;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;
import gregtech.api.metatileentity.implementations.integratedfluid.IFNAmbientTemperature;
import gregtech.api.metatileentity.implementations.integratedfluid.IFNNetworkTransferGate;
import gregtech.api.metatileentity.implementations.integratedfluid.IFNHeatPumpHatchLayout;
import gregtech.api.metatileentity.implementations.integratedfluid.IFNMachineThermo;
import gregtech.api.metatileentity.implementations.integratedfluid.IntegratedFluidNetwork;
import gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidInputHatch;
import gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidOutputHatch;
import gregtech.api.recipe.check.CheckRecipeResult;
import gregtech.api.recipe.check.CheckRecipeResultRegistry;
import gregtech.api.recipe.check.SimpleCheckRecipeResult;
import gregtech.api.render.TextureFactory;
import gregtech.api.util.MultiblockTooltipBuilder;
import gregtech.common.blocks.BlockCasings2;
import gregtech.common.gui.modularui.multiblock.MTEHeatPumpGui;
import gregtech.common.gui.modularui.multiblock.base.MTEMultiBlockBaseGui;
import gregtech.common.tileentities.machines.multi.heatpump.HeatPumpMachineProcessState;

import gregtech.common.tileentities.machines.multi.heatpump.HeatPumpMachineProcessState.Intake;
import gregtech.common.tileentities.machines.multi.heatpump.HeatPumpMachineProcessState.Layout;

public class MTEHeatPump extends MTEEnhancedMultiBlockBase<MTEHeatPump> implements ISurvivalConstructable {

    private static final String STRUCTURE_PIECE_MAIN = "main";
    private static final int MAX_FLUID_PER_OPERATION = 1000; // Maximum fluid amount per operation (in mB)
    private static final float DEFAULT_TARGET_TEMPERATURE = 310.0f; // Default target output temperature (310K)
    private static final float DEFAULT_TARGET_COP = 5.0f; // Default COP target
    private static final int DEFAULT_TARGET_ENERGY_PER_TICK = 100; // Default 100 EU/t

    // Custom hatch lists for Integrated Fluid Hatches
    private final List<MTEIntegratedFluidInputHatch> mIntegratedInputHatches = new ArrayList<>();
    private final List<MTEIntegratedFluidOutputHatch> mIntegratedOutputHatches = new ArrayList<>();

    // Operating mode and targets
    private HeatPumpMode operatingMode = HeatPumpMode.TARGET_TEMPERATURE;
    private boolean heatExchangerMode = false; // Heat Exchanger Mode enabled/disabled
    private boolean configuringHotStream = true; // true = configuring hot stream, false = configuring cold stream
    private boolean splitFlowMode = false; // Split Flow Mode - divides input into 2 streams with temp differential
    private float splitRatio = 0.5f; // Ratio for split (0.1 to 0.9) - fraction that goes to hot/primary stream
    private boolean targetHeating = true; // Direction for COP/Energy modes (true = heating, false = cooling)

    // Cache for hatch validation (updated when structure changes or every 20 ticks)
    private boolean cachedHatchValidation = false;
    private long lastValidationCheck = 0L;

    private float targetTemperature = DEFAULT_TARGET_TEMPERATURE; // For TARGET_TEMPERATURE mode (absolute temperature
                                                                  // in K)
    private float targetCOP = DEFAULT_TARGET_COP; // For TARGET_COP mode
    private int targetEnergyPerTick = DEFAULT_TARGET_ENERGY_PER_TICK; // For TARGET_ENERGY mode (EU per tick)
    private int fluidAmountPerOperation = MAX_FLUID_PER_OPERATION; // Amount of fluid to process per operation (in mB)
    private float lowerTemperatureTolerance = 0.5f; // Lower temperature tolerance for passthrough (heating threshold in
                                                    // K)
    private float upperTemperatureTolerance = 0.5f; // Upper temperature tolerance for passthrough (cooling threshold in
                                                    // K)

    // Current calculated values for GUI display
    private float currentCOP = 0.0f;
    private float currentOutputTemperature = 0.0f;
    private float currentTemperatureDelta = 0.0f; // NEW: for efficiency warnings
    private float currentEfficiencyPenalty = 1.0f; // NEW: shows if penalty is applied
    private float effectiveCOP = 0.0f; // Real COP including penalty (currentCOP / penalty)
    private int totalEnergyCost = 0; // Total energy cost per tick including penalty for GUI

    private final HeatPumpMachineProcessState machineProcessState = new HeatPumpMachineProcessState();

    private static final IStructureDefinition<MTEHeatPump> STRUCTURE_DEFINITION = StructureDefinition
        .<MTEHeatPump>builder()
        .addShape(
            STRUCTURE_PIECE_MAIN,
            transpose(new String[][] { { "CCC", "CCC", "CCC" }, { "C~C", "CCC", "CCC" }, { "CCC", "CCC", "CCC" } }))
        .addElement(
            'C',
            ofChain(
                // FIRST: Let buildHatchAdder capture Energy and Maintenance hatches
                buildHatchAdder(MTEHeatPump.class).atLeast(Energy, Maintenance)
                    .casingIndex(((BlockCasings2) GregTechAPI.sBlockCasings2).getTextureIndex(0))
                    .hint(1)
                    .buildAndChain(
                        onElementPass(x -> ++((MTEHeatPump) x).mCasingAmount, ofBlock(GregTechAPI.sBlockCasings2, 0))),
                // THEN: Accept any remaining GregTech machines (like Integrated Fluid Hatches)
                ofHatchAdder(
                    MTEHeatPump::addIntegratedInputHatch,
                    ((BlockCasings2) GregTechAPI.sBlockCasings2).getTextureIndex(0),
                    1),
                ofHatchAdder(
                    MTEHeatPump::addIntegratedOutputHatch,
                    ((BlockCasings2) GregTechAPI.sBlockCasings2).getTextureIndex(0),
                    1)))
        .build();

    private int mCasingAmount;

    public MTEHeatPump(int aID, String aName, String aNameRegional) {
        super(aID, aName, aNameRegional);
    }

    public MTEHeatPump(String aName) {
        super(aName);
    }

    @Override
    public IMetaTileEntity newMetaEntity(IGregTechTileEntity aTileEntity) {
        return new MTEHeatPump(this.mName);
    }

    @Override
    protected MultiblockTooltipBuilder createTooltip() {
        MultiblockTooltipBuilder tt = new MultiblockTooltipBuilder();
        tt.addMachineType("Heat Pump")
            .addInfo("Heats fluid from Input Hatch to Output Hatch")
            .addInfo("3 Operating Modes (configurable via Settings button):")
            .addInfo("1) Target Temperature - Set output temperature")
            .addInfo("   COP and energy are calculated")
            .addInfo("2) Target COP - Set efficiency (Coefficient of Performance)")
            .addInfo("   Temperature and energy are calculated")
            .addInfo("3) Target Energy - Set energy consumption limit")
            .addInfo("   Temperature and COP are calculated")
            .addInfo("Intake is metered in liters at inlet conditions")
            .addInfo("Uses ideal Carnot COP formula")
            .addInfo("Requires Integrated Fluid Input and Output Hatches")
            .addSeparator()
            .beginStructureBlock(3, 3, 3, true)
            .addController("Front center")
            .addCasingInfoMin("Solid Steel Machine Casing", 18, false)
            .addInputHatch("Any casing (Integrated Fluid type)", 1)
            .addOutputHatch("Any casing (Integrated Fluid type)", 1)
            .addEnergyHatch("Any casing", 1)
            .addMaintenanceHatch("Any casing", 1)
            .toolTipFinisher("GregTech");
        return tt;
    }

    @Override
    public IStructureDefinition<MTEHeatPump> getStructureDefinition() {
        return STRUCTURE_DEFINITION;
    }

    @Override
    public ITexture[] getTexture(IGregTechTileEntity baseMetaTileEntity, ForgeDirection side, ForgeDirection facing,
        int colorIndex, boolean active, boolean redstoneLevel) {
        if (side == facing) {
            if (active) {
                return new ITexture[] { TextureFactory.of(GregTechAPI.sBlockCasings2, 0),
                    TextureFactory.of(Textures.BlockIcons.OVERLAY_FRONT_MULTI_SMELTER_ACTIVE) };
            }
            return new ITexture[] { TextureFactory.of(GregTechAPI.sBlockCasings2, 0),
                TextureFactory.of(Textures.BlockIcons.OVERLAY_FRONT_MULTI_SMELTER) };
        }
        return new ITexture[] { TextureFactory.of(GregTechAPI.sBlockCasings2, 0) };
    }

    @Override
    public boolean checkMachine(IGregTechTileEntity aBaseMetaTileEntity, ItemStack aStack) {
        mCasingAmount = 0;
        mIntegratedInputHatches.clear();
        mIntegratedOutputHatches.clear();

        boolean valid = checkPiece(STRUCTURE_PIECE_MAIN, 1, 1, 0) && mCasingAmount >= 18
            && !mIntegratedInputHatches.isEmpty() && !mIntegratedOutputHatches.isEmpty()
            && !mEnergyHatches.isEmpty() && !mMaintenanceHatches.isEmpty();
        if (valid) doPeriodicChecks = true;
        return valid;
    }

    @Override
    public @NotNull CheckRecipeResult checkProcessing() {
        if (machineProcessState.awaitingTemperatureChange()) return SimpleCheckRecipeResult.ofFailure("vfn_paired_temperature_limit");
        Layout layout = activeLayout();
        boolean paired = layout != Layout.SINGLE, hx = layout == Layout.EXCHANGER;
        if (!hx && (mIntegratedInputHatches.size() != 1 || mIntegratedOutputHatches.size() != (paired ? 2 : 1)))
            return CheckRecipeResultRegistry.NO_RECIPE;
        boolean blue = bluePrimary();
        int otherColor = blue ? IFNHeatPumpHatchLayout.RED : IFNHeatPumpHatchLayout.BLUE;
        var inlet = controlledInlet();
        var otherInlet = hx ? coloredInlet(otherColor) : null;
        var outlet = controlledOutlet();
        var otherOutlet = paired ? coloredOutlet(otherColor) : null;
        if (paired && (outlet == null || otherOutlet == null || outlet == otherOutlet || inlet == otherOutlet
            || (hx ? otherInlet == null || inlet == otherInlet || outlet == otherInlet : inlet == outlet)))
            return CheckRecipeResultRegistry.NO_RECIPE;
        if (machineProcessState.ready()) {
            if (machineProcessState.discharge(outlet, otherOutlet)) getBaseMetaTileEntity().markDirty();
            if (machineProcessState.ready()) {
                return switch (machineProcessState.blockedOutlet(outlet, otherOutlet)) {
                    case SINGLE -> SimpleCheckRecipeResult.ofFailure("vfn_outlet_head");
                    case RED -> SimpleCheckRecipeResult.ofFailure("vfn_red_outlet_head");
                    case BLUE -> SimpleCheckRecipeResult.ofFailure("vfn_blue_outlet_head");
                    case BOTH -> SimpleCheckRecipeResult.ofFailure("vfn_both_outlet_heads");
                    case NONE -> CheckRecipeResultRegistry.ITEM_OUTPUT_FULL;
                };
            }
        }
        if (!machineProcessState.active()) {
            if (layout != activeLayout() || blue != bluePrimary()) return CheckRecipeResultRegistry.NO_RECIPE;
            if (!IFNNetworkTransferGate.isOperational(outlet) || (paired && !IFNNetworkTransferGate.isOperational(otherOutlet)))
                return CheckRecipeResultRegistry.ITEM_OUTPUT_FULL;
            if (!IFNNetworkTransferGate.isOperational(inlet) || (hx && !IFNNetworkTransferGate.isOperational(otherInlet)))
                return CheckRecipeResultRegistry.NO_RECIPE;
            var intake = new Intake(inlet, otherInlet, fluidAmountPerOperation, layout == Layout.SPLIT ? splitRatio : 1, blue);
            boolean heating = hx ? !blue : targetHeating;
            boolean admitted;
            if (operatingMode == HeatPumpMode.TARGET_ENERGY) {
                admitted = machineProcessState.fillForPower(intake, targetEnergyPerTick, heating);
            } else if (paired && operatingMode == HeatPumpMode.TARGET_COP) {
                admitted = machineProcessState.fillForPairedCop(intake, targetCOP, heating);
            } else {
                boolean copControl = operatingMode == HeatPumpMode.TARGET_COP;
                double target = copControl ? IFNMachineThermo.computeReservoirTargetCopOutputTemperature(
                    IFNAmbientTemperature.getAmbientTemperature(getBaseMetaTileEntity().getWorld()), targetCOP, heating)
                    : targetTemperature;
                if (inlet.getAmountQ() > 0 && !inlet.supportsFluidTemperature(target))
                    return SimpleCheckRecipeResult.ofFailure("awaiting_configuration");
                boolean directional = hx || copControl;
                admitted = machineProcessState.fill(intake, target,
                    directional && !heating ? Double.POSITIVE_INFINITY : lowerTemperatureTolerance,
                    directional && heating ? Double.POSITIVE_INFINITY : upperTemperatureTolerance);
            }
            if (!admitted) return machineProcessState.inletPressureTooLow(intake)
                ? SimpleCheckRecipeResult.ofFailure("vfn_inlet_head") : CheckRecipeResultRegistry.NO_RECIPE;
        }
        getBaseMetaTileEntity().markDirty();
        mMaxProgresstime = 20;
        mEfficiency = 10000;
        mEUt = 0;
        return CheckRecipeResultRegistry.SUCCESSFUL;
    }

    @Override
    public boolean onRunningTick(ItemStack stack) {
        totalEnergyCost = 0;
        mEUt = 0;
        if (machineProcessState.active() && !machineProcessState.ready()) {
            long budget = Math.min(Integer.MAX_VALUE, getMaxInputPower());
            long available = 0;
            for (var hatch : mEnergyHatches) {
                if (hatch.isValid()) available += Math.min(budget - available, hatch.getBaseMetaTileEntity().getStoredEU());
                if (available == budget) break;
            }
            double ambient = IFNAmbientTemperature.getAmbientTemperature(getBaseMetaTileEntity().getWorld());
            long spent = machineProcessState.advance(ambient, available, this::drainEnergyInput);
            if (spent > 0) applyHeatPumpMetrics(machineProcessState.metrics());
            totalEnergyCost = (int) spent;
            mEUt = -totalEnergyCost;
            currentOutputTemperature = (float) machineProcessState.temperature();
            if (machineProcessState.awaitingTemperatureChange()) {
                getBaseMetaTileEntity().markDirty();
                mMaxProgresstime = mProgresstime + 1;
                return true;
            }
            if (spent == 0 && !machineProcessState.ready()) return false;
        }
        if (!machineProcessState.active() || machineProcessState.ready()) mMaxProgresstime = mProgresstime + 1;
        return true;
    }

    @Override
    public void construct(ItemStack stackSize, boolean hintsOnly) {
        buildPiece(STRUCTURE_PIECE_MAIN, stackSize, hintsOnly, 1, 1, 0);
    }

    @Override
    public int survivalConstruct(ItemStack stackSize, int elementBudget, ISurvivalBuildEnvironment env) {
        if (mMachine) return -1;
        return survivialBuildPiece(STRUCTURE_PIECE_MAIN, stackSize, 1, 1, 0, elementBudget, env, false, true);
    }

    // ===== GUI Methods =====
    @Override
    protected boolean useMui2() {
        return true;
    }

    @Override
    protected @NotNull MTEMultiBlockBaseGui<?> getGui() {
        return new MTEHeatPumpGui(this);
    }

    // ===== Helper Methods for GUI =====
    public float getInputTemperature() {
        var network = controlledInlet();
        return getNetworkTemperature(network);
    }

    public float getOutputTemperature() {
        var network = controlledOutlet();
        return getNetworkTemperature(network);
    }

    public long getInputBaseLiters() {
        var network = controlledInlet();
        return network == null ? 0 : network.getTopologySnapshot().baseVolume().toWholeLiters();
    }

    public long getInputMicroKg() {
        var network = controlledInlet();
        return network == null ? 0 : network.getAmountQ();
    }

    public long getOutputBaseLiters() {
        var network = controlledOutlet();
        return network == null ? 0 : network.getTopologySnapshot().baseVolume().toWholeLiters();
    }

    public long getOutputMicroKg() {
        var network = controlledOutlet();
        return network == null ? 0 : network.getAmountQ();
    }

    public String getFluidName() {
        var network = controlledInlet();
        if (network == null) return "";
        Fluid fluid = network.getFluid();
        return fluid != null ? fluid.getLocalizedName() : "";
    }

    private static float getNetworkTemperature(IntegratedFluidNetwork network) {
        return network == null || network.getAmountQ() == 0 ? 0 : network.getTemperature();
    }

    private void applyHeatPumpMetrics(IFNMachineThermo.HeatPumpMetrics metrics) {
        currentCOP = metrics.cop();
        currentTemperatureDelta = (float) metrics.temperatureDelta();
        currentEfficiencyPenalty = metrics.efficiencyPenalty();
        effectiveCOP = metrics.effectiveCop();
    }

    public float getCOP() {
        return currentCOP;
    }

    public float getEffectiveCOP() {
        return effectiveCOP;
    }

    public float getCurrentTemperatureDelta() {
        return currentTemperatureDelta;
    }

    public float getCurrentEfficiencyPenalty() {
        return currentEfficiencyPenalty;
    }

    public int getTotalEnergyCost() {
        return totalEnergyCost;
    }

    public float getCurrentOutputTemperature() {
        return currentOutputTemperature;
    }

    public HeatPumpMode getOperatingMode() {
        return operatingMode;
    }

    public void setOperatingMode(HeatPumpMode mode) {
        this.operatingMode = mode;
    }

    public int getOperatingModeId() {
        return operatingMode.getId();
    }

    public void setOperatingModeById(int id) {
        this.operatingMode = HeatPumpMode.fromId(id);
    }

    // Target value methods
    public float getTargetTemperatureDelta() {
        return targetTemperature;
    }

    public void setTargetTemperatureDelta(float temperature) {
        if (!Float.isFinite(temperature) || temperature <= 0) return;
        this.targetTemperature = temperature;
        if (operatingMode != HeatPumpMode.TARGET_TEMPERATURE || !machineProcessState.awaitingTemperatureChange()
            || configuredLayout() != machineProcessState.layout()
            || (heatExchangerMode && !configuringHotStream) != machineProcessState.bluePrimary()) return;
        double lower = heatExchangerMode && !configuringHotStream ? Double.POSITIVE_INFINITY : lowerTemperatureTolerance;
        double upper = heatExchangerMode && configuringHotStream ? Double.POSITIVE_INFINITY : upperTemperatureTolerance;
        if (machineProcessState.retargetStalledCharge(temperature, lower, upper)) getBaseMetaTileEntity().markDirty();
    }

    // Universal value - interpreted based on operating mode
    public float getUniversalValue() {
        switch (operatingMode) {
            case TARGET_TEMPERATURE:
                return targetTemperature;
            case TARGET_COP:
                return targetCOP;
            case TARGET_ENERGY:
                return (float) targetEnergyPerTick;
            default:
                return 0.0f;
        }
    }

    public void setUniversalValue(float value) {
        if (!Float.isFinite(value)) return;
        switch (operatingMode) {
            case TARGET_TEMPERATURE:
                setTargetTemperatureDelta(value);
                break;
            case TARGET_COP:
                this.targetCOP = Math.max(1.1f, Math.min(value, 100.0f));
                break;
            case TARGET_ENERGY:
                this.targetEnergyPerTick = (int) Math.max(5, Math.min(value, 50000));
                break;
        }
    }

    public float getTargetCOP() {
        return targetCOP;
    }

    public void setTargetCOP(float cop) {
        this.targetCOP = Math.max(1.1f, Math.min(cop, 50.0f));
    }

    public int getTargetEnergy() {
        return targetEnergyPerTick;
    }

    public void setTargetEnergy(int energy) {
        this.targetEnergyPerTick = Math.max(5, Math.min(energy, 50000));
    }

    // Fluid amount per operation
    public int getFluidAmountPerOperation() {
        return fluidAmountPerOperation;
    }

    public void setFluidAmountPerOperation(int amount) {
        this.fluidAmountPerOperation = Math.max(1, Math.min(amount, 10000));
    }

    // Temperature tolerance for passthrough mode
    public float getLowerTemperatureTolerance() {
        return lowerTemperatureTolerance;
    }

    public void setLowerTemperatureTolerance(float tolerance) {
        if (!Float.isFinite(tolerance)) return;
        this.lowerTemperatureTolerance = Math.max(0.0f, Math.min(tolerance, 50.0f));
    }

    public float getUpperTemperatureTolerance() {
        return upperTemperatureTolerance;
    }

    public void setUpperTemperatureTolerance(float tolerance) {
        if (!Float.isFinite(tolerance)) return;
        this.upperTemperatureTolerance = Math.max(0.0f, Math.min(tolerance, 50.0f));
    }

    // Heat Exchanger Mode
    public boolean isHeatExchangerMode() {
        return heatExchangerMode;
    }

    public void setHeatExchangerMode(boolean enabled) {
        this.heatExchangerMode = enabled;
        // Mutual exclusion: disable Split Flow if HX is enabled
        if (enabled && splitFlowMode) {
            splitFlowMode = false;
        }
    }

    public boolean isConfiguringHotStream() {
        return configuringHotStream;
    }

    public void setConfiguringHotStream(boolean hot) {
        this.configuringHotStream = hot;
        this.targetHeating = hot;
    }

    // Split Flow Mode
    public boolean isSplitFlowMode() {
        return splitFlowMode;
    }

    public void setSplitFlowMode(boolean enabled) {
        this.splitFlowMode = enabled;
        // Mutual exclusion: disable Heat Exchanger if Split Flow is enabled
        if (enabled && heatExchangerMode) {
            heatExchangerMode = false;
        }
    }

    public float getSplitRatio() {
        return splitRatio;
    }

    public void setSplitRatio(float ratio) {
        if (!Float.isFinite(ratio)) return;
        this.splitRatio = Math.max(0.1f, Math.min(0.9f, ratio)); // Clamp to 0.1-0.9
    }

    public boolean isTargetHeating() {
        return targetHeating;
    }

    public void setTargetHeating(boolean heating) {
        this.targetHeating = heating;
    }

    /**
     * Checks if Normal Mode has too many hatches (should be exactly 2: 1 input + 1 output).
     * Returns true if there are more than 2 hatches total.
     */
    public boolean hasTooManyHatchesForNormalMode() {
        if (heatExchangerMode || splitFlowMode) {
            return false; // In HX or Split Flow mode, multiple hatches are expected
        }

        return IFNHeatPumpHatchLayout
            .hasTooManyNormalModeHatches(mIntegratedInputHatches.size(), mIntegratedOutputHatches.size());
    }

    /**
     * Checks if Split Flow Mode has valid hatch configuration.
     * Returns true if structure has exactly 1 input and 2 colored outputs (Red and Blue).
     *
     * Note: getColorization() returns MC spray metadata:
     * - Red spray (MC metadata 1) → getColorization() returns 1
     * - Blue spray (MC metadata 4) → getColorization() returns 4
     */
    public boolean hasValidSplitFlowHatches() {
        if (!splitFlowMode) {
            return true; // Not in Split Flow mode, so don't show warning
        }

        return IFNHeatPumpHatchLayout.isValidSplitFlowLayout(mIntegratedInputHatches.size(), outputHatchColors());
    }

    /**
     * Checks if Heat Exchanger Mode has required colored hatches.
     * Returns true if structure has at least 1 red and 1 blue hatch of each type.
     *
     * Uses caching to avoid checking every GUI frame (was causing spam).
     * Cache is invalidated when structure changes or every 20 ticks.
     *
     * Note: getColorization() returns the ORIGINAL MC spray metadata:
     * - Red spray (MC metadata 1) → getColorization() returns 1
     * - Blue spray (MC metadata 4) → getColorization() returns 4
     */
    public boolean hasValidHeatExchangerHatches() {
        if (!heatExchangerMode) {
            return true; // Not in HX mode, so don't show warning
        }

        // Use cached result if checked recently (within 20 ticks / 1 second)
        long currentTick = getBaseMetaTileEntity().getTimer();
        if (lastValidationCheck > 0 && (currentTick - lastValidationCheck) < 20) {
            return cachedHatchValidation;
        }

        cachedHatchValidation = IFNHeatPumpHatchLayout
            .isValidHeatExchangerLayout(inputHatchColors(), outputHatchColors());
        lastValidationCheck = currentTick;

        return cachedHatchValidation;
    }

    private int[] inputHatchColors() {
        int[] colors = new int[mIntegratedInputHatches.size()];
        for (int i = 0; i < mIntegratedInputHatches.size(); i++) {
            colors[i] = mIntegratedInputHatches.get(i)
                .getBaseMetaTileEntity()
                .getColorization();
        }
        return colors;
    }

    private Layout activeLayout() {
        return machineProcessState.active() ? machineProcessState.layout() : configuredLayout();
    }

    private Layout configuredLayout() {
        return heatExchangerMode ? Layout.EXCHANGER : splitFlowMode ? Layout.SPLIT : Layout.SINGLE;
    }

    private boolean bluePrimary() {
        return machineProcessState.active() ? machineProcessState.bluePrimary() : heatExchangerMode && !configuringHotStream;
    }

    private IntegratedFluidNetwork controlledInlet() {
        if (activeLayout() == Layout.EXCHANGER) return coloredInlet(bluePrimary() ? IFNHeatPumpHatchLayout.BLUE : IFNHeatPumpHatchLayout.RED);
        return mIntegratedInputHatches.isEmpty() ? null : mIntegratedInputHatches.get(0).getNetwork();
    }

    private IntegratedFluidNetwork controlledOutlet() {
        if (activeLayout() != Layout.SINGLE) return coloredOutlet(bluePrimary() ? IFNHeatPumpHatchLayout.BLUE : IFNHeatPumpHatchLayout.RED);
        return mIntegratedOutputHatches.isEmpty() ? null : mIntegratedOutputHatches.get(0).getNetwork();
    }

    private IntegratedFluidNetwork coloredInlet(int color) {
        for (var hatch : mIntegratedInputHatches) {
            if (hatch.getBaseMetaTileEntity().getColorization() == color) return hatch.getNetwork();
        }
        return null;
    }

    private IntegratedFluidNetwork coloredOutlet(int color) {
        for (var hatch : mIntegratedOutputHatches) {
            if (hatch.getBaseMetaTileEntity().getColorization() == color) return hatch.getNetwork();
        }
        return null;
    }

    private int[] outputHatchColors() {
        int[] colors = new int[mIntegratedOutputHatches.size()];
        for (int i = 0; i < mIntegratedOutputHatches.size(); i++) {
            colors[i] = mIntegratedOutputHatches.get(i)
                .getBaseMetaTileEntity()
                .getColorization();
        }
        return colors;
    }

    // ===== NBT Methods =====
    @Override
    public void saveNBTData(net.minecraft.nbt.NBTTagCompound aNBT) {
        super.saveNBTData(aNBT);

        // Save operating mode
        aNBT.setInteger("operatingMode", operatingMode.getId());

        // Save target values
        aNBT.setFloat("targetTemperature", targetTemperature);
        aNBT.setFloat("targetCOP", targetCOP);
        aNBT.setInteger("targetEnergyPerTick", targetEnergyPerTick);
        aNBT.setInteger("fluidAmountPerOperation", fluidAmountPerOperation);
        aNBT.setFloat("lowerTemperatureTolerance", lowerTemperatureTolerance);
        aNBT.setFloat("upperTemperatureTolerance", upperTemperatureTolerance);
        aNBT.setBoolean("heatExchangerMode", heatExchangerMode);
        aNBT.setBoolean("configuringHotStream", configuringHotStream);
        aNBT.setBoolean("splitFlowMode", splitFlowMode);
        aNBT.setFloat("splitRatio", splitRatio);
        aNBT.setBoolean("targetHeating", targetHeating);
        machineProcessState.save(aNBT);

    }

    @Override
    public void loadNBTData(net.minecraft.nbt.NBTTagCompound aNBT) {
        super.loadNBTData(aNBT);

        // Load operating mode
        if (aNBT.hasKey("operatingMode")) {
            operatingMode = HeatPumpMode.fromId(aNBT.getInteger("operatingMode"));
        }

        // Load target values
        if (aNBT.hasKey("targetTemperature")) {
            targetTemperature = aNBT.getFloat("targetTemperature");
        } else if (aNBT.hasKey("targetTemperatureDelta")) {
            // Backward compatibility - old saves had delta, convert to absolute (assume 300K input)
            targetTemperature = 300.0f + aNBT.getFloat("targetTemperatureDelta");
        }
        if (aNBT.hasKey("targetCOP")) {
            targetCOP = aNBT.getFloat("targetCOP");
        }
        if (aNBT.hasKey("targetEnergyPerTick")) {
            targetEnergyPerTick = aNBT.getInteger("targetEnergyPerTick");
        }
        if (aNBT.hasKey("fluidAmountPerOperation")) {
            fluidAmountPerOperation = aNBT.getInteger("fluidAmountPerOperation");
        }
        if (aNBT.hasKey("lowerTemperatureTolerance")) {
            lowerTemperatureTolerance = aNBT.getFloat("lowerTemperatureTolerance");
        } else if (aNBT.hasKey("targetTemperatureTolerance")) {
            // Backward compatibility - old single tolerance becomes both
            lowerTemperatureTolerance = aNBT.getFloat("targetTemperatureTolerance");
            upperTemperatureTolerance = aNBT.getFloat("targetTemperatureTolerance");
        }
        if (aNBT.hasKey("upperTemperatureTolerance")) {
            upperTemperatureTolerance = aNBT.getFloat("upperTemperatureTolerance");
        }
        if (aNBT.hasKey("heatExchangerMode")) {
            heatExchangerMode = aNBT.getBoolean("heatExchangerMode");
        }
        if (aNBT.hasKey("configuringHotStream")) {
            configuringHotStream = aNBT.getBoolean("configuringHotStream");
        }
        if (aNBT.hasKey("splitFlowMode")) {
            splitFlowMode = aNBT.getBoolean("splitFlowMode");
        }
        if (aNBT.hasKey("splitRatio")) {
            splitRatio = aNBT.getFloat("splitRatio");
        }
        if (aNBT.hasKey("targetHeating")) {
            targetHeating = aNBT.getBoolean("targetHeating");
        }

        if (aNBT.hasKey("targetEnergy")) {
            // Backward compatibility - convert old total energy to per-tick
            targetEnergyPerTick = aNBT.getInteger("targetEnergy") / 20;
        }
        machineProcessState.load(aNBT);
    }

    public boolean addIntegratedInputHatch(IGregTechTileEntity aBaseMetaTileEntity, Short aColor) {
        if (aBaseMetaTileEntity == null) return false;
        IMetaTileEntity mte = aBaseMetaTileEntity.getMetaTileEntity();
        if (mte instanceof MTEIntegratedFluidInputHatch hatch) {
            hatch.updateTexture(((BlockCasings2) GregTechAPI.sBlockCasings2).getTextureIndex(0));
            return mIntegratedInputHatches.add(hatch);
        }
        return false;
    }

    public boolean addIntegratedOutputHatch(IGregTechTileEntity aBaseMetaTileEntity, Short aColor) {
        if (aBaseMetaTileEntity == null) return false;
        IMetaTileEntity mte = aBaseMetaTileEntity.getMetaTileEntity();
        if (mte instanceof MTEIntegratedFluidOutputHatch hatch) {
            hatch.updateTexture(((BlockCasings2) GregTechAPI.sBlockCasings2).getTextureIndex(0));
            return mIntegratedOutputHatches.add(hatch);
        }
        return false;
    }
}
