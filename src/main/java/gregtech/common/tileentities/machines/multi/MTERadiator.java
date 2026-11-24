package gregtech.common.tileentities.machines.multi;

import static com.gtnewhorizon.structurelib.structure.StructureUtility.*;
import static gregtech.api.enums.HatchElement.*;
import static gregtech.api.util.GTStructureUtility.buildHatchAdder;
import static gregtech.api.util.GTStructureUtility.ofHatchAdder;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraftforge.common.util.ForgeDirection;

import org.jetbrains.annotations.NotNull;

import com.gtnewhorizon.structurelib.alignment.constructable.ISurvivalConstructable;
import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.ISurvivalBuildEnvironment;
import com.gtnewhorizon.structurelib.structure.StructureDefinition;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;
import gregtech.api.metatileentity.implementations.integratedfluid.IFNAmbientTemperature;
import gregtech.api.metatileentity.implementations.integratedfluid.IFNNetworkTransferGate;
import gregtech.api.metatileentity.implementations.integratedfluid.thermodynamics.ClosedFluidChamber;
import gregtech.api.metatileentity.implementations.integratedfluid.state.IFNCanonicalState;
import gregtech.api.metatileentity.implementations.integratedfluid.thermodynamics.PureFluid;
import gregtech.api.metatileentity.implementations.integratedfluid.IntegratedFluidNetwork;
import gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidInputHatch;
import gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidOutputHatch;
import gregtech.api.modularui2.GTGuiTextures;
import gregtech.api.recipe.check.CheckRecipeResult;
import gregtech.api.recipe.check.CheckRecipeResultRegistry;
import gregtech.api.recipe.check.SimpleCheckRecipeResult;
import gregtech.api.render.TextureFactory;
import gregtech.api.util.GTUtility;
import gregtech.api.util.MultiblockTooltipBuilder;
import gregtech.common.blocks.BlockCasings2;
import gregtech.common.gui.modularui.multiblock.MTERadiatorGui;
import gregtech.common.gui.modularui.multiblock.base.MTEMultiBlockBaseGui;
import gregtech.common.tileentities.machines.multi.radiator.RadiatorLoopAnalyzer;
import gregtech.common.tileentities.machines.multi.radiator.RadiatorLoopSnapshot;
import gregtech.common.tileentities.machines.multi.radiator.RadiatorThermo;

public class MTERadiator extends MTEEnhancedMultiBlockBase<MTERadiator> implements ISurvivalConstructable {

    private static final String STRUCTURE_PIECE_MAIN = "main";
    private static final int LOOP_VOLUME_L = 1000;
    private static final int MODE_CONSTANT_TIME = 0;
    private static final int MODE_TARGET_TEMPERATURE = 1;
    private static final int DEFAULT_CONSTANT_OPERATION_TICKS = 20;
    private static final int MIN_CONSTANT_OPERATION_TICKS = 20;
    private static final int MAX_CONSTANT_OPERATION_TICKS = 20 * 60;
    private static final float DEFAULT_TARGET_TEMPERATURE = 310.0f;
    private static final float TARGET_TEMPERATURE_STEP = 5.0f;
    private static final float MIN_TARGET_TEMPERATURE = 1.0f;
    private static final float MAX_TARGET_TEMPERATURE = 5000.0f;

    private final List<MTEIntegratedFluidInputHatch> mIntegratedInputHatches = new ArrayList<>();
    private final List<MTEIntegratedFluidOutputHatch> mIntegratedOutputHatches = new ArrayList<>();

    private static final IStructureDefinition<MTERadiator> STRUCTURE_DEFINITION = StructureDefinition
        .<MTERadiator>builder()
        .addShape(
            STRUCTURE_PIECE_MAIN,
            transpose(
                new String[][] {
                    { "CCC", "CCC", "CCC" },
                    { "C~C", "CCC", "CCC" },
                    { "CCC", "CCC", "CCC" }
                }))
        .addElement(
            'C',
            ofChain(
                buildHatchAdder(MTERadiator.class)
                    .atLeast(Energy, Maintenance)
                    .casingIndex(((BlockCasings2) GregTechAPI.sBlockCasings2).getTextureIndex(0))
                    .hint(1)
                    .buildAndChain(onElementPass(x -> ++((MTERadiator) x).mCasingAmount, ofBlock(GregTechAPI.sBlockCasings2, 0))),
                ofHatchAdder(
                    MTERadiator::addIntegratedInputHatch,
                    ((BlockCasings2) GregTechAPI.sBlockCasings2).getTextureIndex(0),
                    1
                ),
                ofHatchAdder(
                    MTERadiator::addIntegratedOutputHatch,
                    ((BlockCasings2) GregTechAPI.sBlockCasings2).getTextureIndex(0),
                    1
                ),
                onElementPass(
                    x -> {
                        ++((MTERadiator) x).mCasingAmount;
                        ++((MTERadiator) x).radiatorPortCount;
                    },
                    ofBlock(GregTechAPI.sBlockCasings11, RadiatorLoopAnalyzer.PORT_META))))
        .build();

    private ClosedFluidChamber loop = ClosedFluidChamber.empty(PureFluid.water(), LOOP_VOLUME_L);
    private int remainingTicks;
    private boolean outputReady;
    private float batchTargetKelvin;
    private boolean heatingToTarget;

    private int mCasingAmount;
    private int radiatorPortCount;
    private int constantOperationTicks = DEFAULT_CONSTANT_OPERATION_TICKS;
    private float targetTemperature = DEFAULT_TARGET_TEMPERATURE;
    private RadiatorLoopSnapshot lastLoopSnapshot = RadiatorLoopSnapshot.invalid("radiator_loop_missing");
    private float lastLoopTemperature = RadiatorThermo.AMBIENT_TEMPERATURE;

    public MTERadiator(int aID, String aName, String aNameRegional) {
        super(aID, aName, aNameRegional);
    }

    public MTERadiator(String aName) {
        super(aName);
    }

    @Override
    public IMetaTileEntity newMetaEntity(IGregTechTileEntity aTileEntity) {
        return new MTERadiator(this.mName);
    }

    @Override
    protected MultiblockTooltipBuilder createTooltip() {
        MultiblockTooltipBuilder tt = new MultiblockTooltipBuilder();
        tt.addMachineType("Radiator")
            .addInfo("Moves fluid temperature toward ambient using an external loop")
            .addInfo("Requires 2 Radiator Loop Ports inside the multiblock shell")
            .addInfo("Connect the ports with Radiator Loop Pipe blocks")
            .addInfo("Loop Pipe adds heat transfer, but also loses 0.01 bar per segment")
            .addInfo("Heat Exchange Modules improve transfer directly")
            .addInfo("Conduction Modules extend Heat Exchange Modules without lengthening the loop")
            .addInfo("Constant Time mode: fixed duration, variable output temperature")
            .addInfo("Target Temperature mode: fixed output temperature, variable duration")
            .addSeparator()
            .beginStructureBlock(3, 3, 3, true)
            .addController("Front center")
            .addCasingInfoMin("Solid Steel Machine Casing / Radiator Loop Port", 18, false)
            .addInputHatch("Any casing (Integrated Fluid type)", 1)
            .addOutputHatch("Any casing (Integrated Fluid type)", 1)
            .addEnergyHatch("Any casing", 1)
            .addMaintenanceHatch("Any casing", 1)
            .toolTipFinisher("GregTech");
        return tt;
    }

    @Override
    public IStructureDefinition<MTERadiator> getStructureDefinition() {
        return STRUCTURE_DEFINITION;
    }

    @Override
    public ITexture[] getTexture(IGregTechTileEntity baseMetaTileEntity, ForgeDirection side, ForgeDirection facing,
        int colorIndex, boolean active, boolean redstoneLevel) {
        if (side == facing) {
            return new ITexture[] {
                TextureFactory.of(GregTechAPI.sBlockCasings2, 0),
                TextureFactory.of(active
                    ? gregtech.api.enums.Textures.BlockIcons.OVERLAY_FRONT_VACUUM_FREEZER_ACTIVE
                    : gregtech.api.enums.Textures.BlockIcons.OVERLAY_FRONT_VACUUM_FREEZER)
            };
        }
        return new ITexture[] { TextureFactory.of(GregTechAPI.sBlockCasings2, 0) };
    }

    @Override
    public boolean checkMachine(IGregTechTileEntity aBaseMetaTileEntity, ItemStack aStack) {
        mCasingAmount = 0;
        radiatorPortCount = 0;
        mIntegratedInputHatches.clear();
        mIntegratedOutputHatches.clear();

        boolean valid = checkPiece(STRUCTURE_PIECE_MAIN, 1, 1, 0)
            && mCasingAmount >= 16
            && radiatorPortCount == 2
            && !mIntegratedInputHatches.isEmpty()
            && !mIntegratedOutputHatches.isEmpty()
            && !mEnergyHatches.isEmpty()
            && !mMaintenanceHatches.isEmpty();

        if (valid) {
            // External pressure can release a blocked batch without changing a GT hatch slot
            doPeriodicChecks = true;
            refreshLoopSnapshot();
        } else {
            lastLoopSnapshot = RadiatorLoopSnapshot.invalid("radiator_loop_missing");
        }
        return valid;
    }

    @Override
    public @NotNull CheckRecipeResult checkProcessing() {
        if (mIntegratedInputHatches.isEmpty() || mIntegratedOutputHatches.isEmpty()) {
            return CheckRecipeResultRegistry.NO_RECIPE;
        }

        MTEIntegratedFluidInputHatch inputHatch = mIntegratedInputHatches.get(0);
        MTEIntegratedFluidOutputHatch outputHatch = mIntegratedOutputHatches.get(0);
        IntegratedFluidNetwork inputNetwork = inputHatch.getNetwork();
        IntegratedFluidNetwork outputNetwork = outputHatch.getNetwork();
        refreshLoopSnapshot();
        if (!lastLoopSnapshot.valid) return SimpleCheckRecipeResult.ofFailure("awaiting_configuration");
        if (outputReady) {
            if (!IFNNetworkTransferGate.canReceiveFromMachine(outputNetwork)) return CheckRecipeResultRegistry.ITEM_OUTPUT_FULL;
            double pressureDropPa = lastLoopSnapshot.pressureDropBar * 100_000.0;
            if (loop.pressurePa() <= outputNetwork.getPressurePa() + pressureDropPa) {
                return SimpleCheckRecipeResult.ofFailure("vfn_radiator_head");
            }
            var after = outputNetwork.drainMachineChamber(loop, LOOP_VOLUME_L, pressureDropPa);
            if (after == loop) return CheckRecipeResultRegistry.ITEM_OUTPUT_FULL;
            loop = after;
            outputReady = false;
            getBaseMetaTileEntity().markDirty();
        }
        // An interrupted batch inside the loop can finish without either external port
        if (remainingTicks == 0 || loop.isEmpty()) {
            if (!IFNNetworkTransferGate.canProvideToMachine(inputNetwork)) return CheckRecipeResultRegistry.NO_RECIPE;
            if (!IFNNetworkTransferGate.canReceiveFromMachine(outputNetwork)) return CheckRecipeResultRegistry.ITEM_OUTPUT_FULL;
        }
        if (remainingTicks == 0) {
            double ambient = ambientKelvin();
            if (machineMode == MODE_TARGET_TEMPERATURE && !reachable(inputNetwork.getTemperature(), ambient, targetTemperature)) {
                return SimpleCheckRecipeResult.ofFailure("awaiting_configuration");
            }
            double inputTemperatureBeforeFill = inputNetwork.getTemperature();
            if (loop.isEmpty() && inputNetwork == outputNetwork) {
                if (inputNetwork.getAmountQ() == 0 || inputTemperatureBeforeFill == ambient) return CheckRecipeResultRegistry.NO_RECIPE;
            } else {
                var filled = inputNetwork.fillMachineChamber(loop, LOOP_VOLUME_L);
                if (filled == loop) return CheckRecipeResultRegistry.NO_RECIPE;
                loop = filled;
            }
            remainingTicks = machineMode == MODE_TARGET_TEMPERATURE ? -1 : constantOperationTicks;
            batchTargetKelvin = targetTemperature;
            heatingToTarget = targetTemperature > inputTemperatureBeforeFill;
            getBaseMetaTileEntity().markDirty();
        }
        // GT owns the tick clock. A target batch uses short progress windows until the thermostat opens
        mMaxProgresstime = remainingTicks < 0 ? 20 : remainingTicks;
        mEfficiency = 10000;
        mEUt = 0;
        return CheckRecipeResultRegistry.SUCCESSFUL;
    }

    private double ambientKelvin() {
        return IFNAmbientTemperature.getAmbientTemperature(getBaseMetaTileEntity().getWorld());
    }

    private static boolean reachable(double from, double ambient, double target) {
        return target >= Math.min(from, ambient) && target <= Math.max(from, ambient);
    }

    @Override
    public boolean onRunningTick(ItemStack stack) {
        var shared = loop.isEmpty() ? sharedNetwork() : null;
        if (remainingTicks == 0 || !lastLoopSnapshot.valid || loop.isEmpty() && shared == null) {
            if (loop.isEmpty()) remainingTicks = 0;
            mMaxProgresstime = mProgresstime + 1;
            return true;
        }
        double ambient = ambientKelvin();
        double before = shared == null ? loop.state().temperatureKelvin() : shared.getTemperature();
        double conductance = RadiatorThermo.computeEffectiveConductance(lastLoopSnapshot, 0.5 * (before + ambient));
        var stop = remainingTicks < 0 ? batchTargetKelvin : ambient;
        double after;
        if (shared == null) {
            loop = loop.exchangeWithAmbient(ambient, conductance, stop);
            after = loop.state().temperatureKelvin();
        } else {
            shared.exchangeThroughRadiator(ambient, conductance, stop);
            after = shared.getTemperature();
        }
        lastLoopTemperature = (float) after;
        if (remainingTicks > 0) remainingTicks--;
        else if (heatingToTarget ? after >= stop - 0.001 : after <= stop + 0.001) remainingTicks = 0;
        if (remainingTicks == 0) {
            outputReady = !loop.isEmpty();
            mMaxProgresstime = mProgresstime + 1;
        }
        return true;
    }

    private IntegratedFluidNetwork sharedNetwork() {
        if (mIntegratedInputHatches.isEmpty() || mIntegratedOutputHatches.isEmpty()) return null;
        var inlet = mIntegratedInputHatches.get(0).getNetwork();
        return inlet == mIntegratedOutputHatches.get(0).getNetwork() ? inlet : null;
    }

    @Override
    public void saveNBTData(NBTTagCompound aNBT) {
        super.saveNBTData(aNBT);
        IFNCanonicalState.writeWorkingChamber(aNBT, "loop", loop);
        aNBT.setInteger("loopTicks", remainingTicks);
        aNBT.setBoolean("loopOutputReady", outputReady);
        aNBT.setFloat("loopTargetK", batchTargetKelvin);
        aNBT.setBoolean("loopHeating", heatingToTarget);
        aNBT.setInteger("radiatorMode", machineMode);
        aNBT.setInteger("radiatorConstantTicks", constantOperationTicks);
        aNBT.setFloat("radiatorTargetTemperature", targetTemperature);
    }

    @Override
    public void loadNBTData(NBTTagCompound aNBT) {
        super.loadNBTData(aNBT);
        loop = IFNCanonicalState.readWorkingChamber(aNBT, "loop", LOOP_VOLUME_L);
        remainingTicks = aNBT.getInteger("loopTicks");
        outputReady = aNBT.getBoolean("loopOutputReady");
        batchTargetKelvin = aNBT.getFloat("loopTargetK");
        heatingToTarget = aNBT.getBoolean("loopHeating");
        if (!loop.isEmpty()) lastLoopTemperature = (float) loop.state().temperatureKelvin();
        if (aNBT.hasKey("radiatorMode")) {
            machineMode = aNBT.getInteger("radiatorMode");
        }
        if (aNBT.hasKey("radiatorConstantTicks")) {
            constantOperationTicks = clampConstantTicks(aNBT.getInteger("radiatorConstantTicks"));
        }
        if (aNBT.hasKey("radiatorTargetTemperature")) {
            targetTemperature = clampTargetTemperature(aNBT.getFloat("radiatorTargetTemperature"));
        }
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

    @Override
    protected boolean useMui2() {
        return true;
    }

    @Override
    protected @NotNull MTEMultiBlockBaseGui<?> getGui() {
        return new MTERadiatorGui(this).withMachineModeIcons(
            GTGuiTextures.OVERLAY_BUTTON_MACHINEMODE_DEFAULT,
            GTGuiTextures.OVERLAY_BUTTON_MACHINEMODE_SIMPLEWASHER
        );
    }

    @Override
    public boolean supportsMachineModeSwitch() {
        return true;
    }

    @Override
    public String getMachineModeKey() {
        return "GT5U.RADIATOR.mode." + machineMode;
    }

    @Override
    public void onScrewdriverRightClick(ForgeDirection side, EntityPlayer aPlayer, float aX, float aY, float aZ,
        ItemStack aTool) {
        setMachineMode(nextMachineMode());
        GTUtility.sendChatTrans(aPlayer, "GT5U.MULTI_MACHINE_CHANGE", new ChatComponentTranslation(getMachineModeKey()));
    }

    @Override
    public boolean onWireCutterRightClick(ForgeDirection side, ForgeDirection wrenchingSide, EntityPlayer aPlayer,
        float aX, float aY, float aZ, ItemStack aTool) {
        if (machineMode == MODE_TARGET_TEMPERATURE) {
            targetTemperature = clampTargetTemperature(
                targetTemperature + (aPlayer.isSneaking() ? -TARGET_TEMPERATURE_STEP : TARGET_TEMPERATURE_STEP)
            );
            GTUtility.sendChatToPlayer(aPlayer, "Radiator target temperature: " + String.format("%.1f K", targetTemperature));
            return true;
        }

        constantOperationTicks = clampConstantTicks(
            constantOperationTicks + (aPlayer.isSneaking() ? -MIN_CONSTANT_OPERATION_TICKS : MIN_CONSTANT_OPERATION_TICKS)
        );
        GTUtility.sendChatToPlayer(aPlayer, "Radiator constant time: " + constantOperationTicks + " ticks");
        return true;
    }

    public float getInputTemperature() {
        if (mIntegratedInputHatches.isEmpty()) return 0.0f;
        var network = mIntegratedInputHatches.get(0).getNetwork();
        return network != null ? network.getTemperature() : 0.0f;
    }

    public float getOutputTemperature() {
        return lastLoopTemperature;
    }

    public long getInputBaseLiters() {
        var network = mIntegratedInputHatches.isEmpty() ? null : mIntegratedInputHatches.get(0).getNetwork();
        return network == null ? 0 : network.getTopologySnapshot().baseVolume().toWholeLiters();
    }

    public long getInputMicroKg() {
        var network = mIntegratedInputHatches.isEmpty() ? null : mIntegratedInputHatches.get(0).getNetwork();
        return network == null ? 0 : network.getAmountQ();
    }

    public long getOutputBaseLiters() {
        var network = mIntegratedOutputHatches.isEmpty() ? null : mIntegratedOutputHatches.get(0).getNetwork();
        return network == null ? 0 : network.getTopologySnapshot().baseVolume().toWholeLiters();
    }

    public long getOutputMicroKg() {
        var network = mIntegratedOutputHatches.isEmpty() ? null : mIntegratedOutputHatches.get(0).getNetwork();
        return network == null ? 0 : network.getAmountQ();
    }

    public String getFluidName() {
        if (mIntegratedInputHatches.isEmpty()) return "";
        var network = mIntegratedInputHatches.get(0).getNetwork();
        if (network == null) return "";
        var fluid = network.getFluid();
        return fluid != null ? fluid.getLocalizedName() : "";
    }

    public int getLoopSegmentCount() {
        refreshLoopSnapshot();
        return lastLoopSnapshot != null ? lastLoopSnapshot.segmentCount : 0;
    }

    public int getLoopConductionModuleCount() {
        refreshLoopSnapshot();
        return lastLoopSnapshot != null ? lastLoopSnapshot.conductionModuleCount : 0;
    }

    public int getLoopHeatExchangeModuleCount() {
        refreshLoopSnapshot();
        return lastLoopSnapshot != null ? lastLoopSnapshot.heatExchangeModuleCount : 0;
    }

    public float getLoopPressureDropBar() {
        refreshLoopSnapshot();
        return lastLoopSnapshot != null ? lastLoopSnapshot.pressureDropBar : 0.0f;
    }

    public float getLoopOutletPressure() {
        var outlet = mIntegratedOutputHatches.isEmpty() ? null : mIntegratedOutputHatches.get(0).getNetwork();
        return outlet == null ? 0 : outlet.getPressure();
    }

    public int getConstantOperationTicks() {
        return constantOperationTicks;
    }

    public void setConstantOperationTicks(int ticks) {
        constantOperationTicks = clampConstantTicks(ticks);
    }

    public float getTargetTemperatureSetting() {
        return targetTemperature;
    }

    public void setTargetTemperatureSetting(float temperature) {
        if (Float.isFinite(temperature)) targetTemperature = clampTargetTemperature(temperature);
    }

    public String getMachineModeName() {
        return machineMode == MODE_TARGET_TEMPERATURE ? "Target Temperature" : "Constant Time";
    }

    public String getLoopStatus() {
        refreshLoopSnapshot();
        String statusKey = lastLoopSnapshot != null ? lastLoopSnapshot.statusKey : "radiator_loop_missing";
        return switch (statusKey) {
            case "ok" -> "Complete";
            case "radiator_loop_ports" -> "Port layout invalid";
            case "radiator_loop_missing" -> "Loop pipe missing";
            case "radiator_loop_invalid" -> "Loop path invalid";
            default -> "Not ready";
        };
    }

    public boolean addIntegratedInputHatch(IGregTechTileEntity baseMetaTileEntity, Short color) {
        IMetaTileEntity mte = baseMetaTileEntity.getMetaTileEntity();
        if (mte instanceof MTEIntegratedFluidInputHatch hatch) {
            hatch.updateTexture(((BlockCasings2) GregTechAPI.sBlockCasings2).getTextureIndex(0));
            return mIntegratedInputHatches.add(hatch);
        }
        return false;
    }

    public boolean addIntegratedOutputHatch(IGregTechTileEntity baseMetaTileEntity, Short color) {
        IMetaTileEntity mte = baseMetaTileEntity.getMetaTileEntity();
        if (mte instanceof MTEIntegratedFluidOutputHatch hatch) {
            hatch.updateTexture(((BlockCasings2) GregTechAPI.sBlockCasings2).getTextureIndex(0));
            return mIntegratedOutputHatches.add(hatch);
        }
        return false;
    }

    private static int clampConstantTicks(int ticks) {
        return Math.max(MIN_CONSTANT_OPERATION_TICKS, Math.min(MAX_CONSTANT_OPERATION_TICKS, ticks));
    }

    private static float clampTargetTemperature(float temperature) {
        return Math.max(MIN_TARGET_TEMPERATURE, Math.min(MAX_TARGET_TEMPERATURE, temperature));
    }

    private void refreshLoopSnapshot() {
        IGregTechTileEntity base = getBaseMetaTileEntity();
        if (base == null || base.getWorld() == null) {
            lastLoopSnapshot = RadiatorLoopSnapshot.invalid("radiator_loop_missing");
            return;
        }
        lastLoopSnapshot = RadiatorLoopAnalyzer.analyze(
            base.getWorld(),
            base.getXCoord(),
            base.getYCoord(),
            base.getZCoord(),
            base.getFrontFacing()
        );
    }
}
