package gregtech.common.gui.modularui.multiblock;

import static com.gtnewhorizon.gtnhlib.util.numberformatting.NumberFormatUtil.formatNumber;

import net.minecraft.util.EnumChatFormatting;

import com.cleanroommc.modularui.api.IPanelHandler;
import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.value.sync.FloatSyncValue;
import com.cleanroommc.modularui.value.sync.IntSyncValue;
import com.cleanroommc.modularui.value.sync.LongSyncValue;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import com.cleanroommc.modularui.value.sync.StringSyncValue;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.ListWidget;
import com.cleanroommc.modularui.widgets.TextWidget;
import com.cleanroommc.modularui.widgets.layout.Column;
import com.cleanroommc.modularui.widgets.layout.Flow;
import com.cleanroommc.modularui.widgets.layout.Row;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;

import gregtech.api.metatileentity.implementations.integratedfluid.amount.SubstanceAmount;
import gregtech.api.util.GTUtility;
import gregtech.common.gui.modularui.multiblock.base.MTEMultiBlockBaseGui;
import gregtech.common.tileentities.machines.multi.MTERadiator;

public class MTERadiatorGui extends MTEMultiBlockBaseGui<MTERadiator> {

    private IPanelHandler settingsPanel;

    public MTERadiatorGui(MTERadiator multiblock) {
        super(multiblock);
    }

    @Override
    protected ListWidget<IWidget, ?> createTerminalTextWidget(PanelSyncManager syncManager, ModularPanel parent) {
        FloatSyncValue inputTempSync = syncManager.findSyncHandler("inputTemp", FloatSyncValue.class);
        FloatSyncValue outputTempSync = syncManager.findSyncHandler("outputTemp", FloatSyncValue.class);
        FloatSyncValue loopOutletPressureSync = syncManager.findSyncHandler("loopOutletPressure", FloatSyncValue.class);
        FloatSyncValue loopPressureDropSync = syncManager.findSyncHandler("loopPressureDrop", FloatSyncValue.class);
        FloatSyncValue targetTempSync = syncManager.findSyncHandler("targetTemperature", FloatSyncValue.class);
        LongSyncValue inputBaseLitersSync = syncManager.findSyncHandler("inputBaseLiters", LongSyncValue.class);
        LongSyncValue inputMicroKgSync = syncManager.findSyncHandler("inputMicroKg", LongSyncValue.class);
        LongSyncValue outputBaseLitersSync = syncManager.findSyncHandler("outputBaseLiters", LongSyncValue.class);
        LongSyncValue outputMicroKgSync = syncManager.findSyncHandler("outputMicroKg", LongSyncValue.class);
        IntSyncValue loopSegmentsSync = syncManager.findSyncHandler("loopSegments", IntSyncValue.class);
        IntSyncValue loopConductionSync = syncManager.findSyncHandler("loopConduction", IntSyncValue.class);
        IntSyncValue loopExchangeSync = syncManager.findSyncHandler("loopExchange", IntSyncValue.class);
        IntSyncValue constantTicksSync = syncManager.findSyncHandler("constantTicks", IntSyncValue.class);
        StringSyncValue fluidNameSync = syncManager.findSyncHandler("fluidName", StringSyncValue.class);
        StringSyncValue loopStatusSync = syncManager.findSyncHandler("loopStatus", StringSyncValue.class);
        StringSyncValue modeNameSync = syncManager.findSyncHandler("modeName", StringSyncValue.class);

        return super.createTerminalTextWidget(syncManager, parent)
            .child(
                IKey.dynamic(
                    () -> EnumChatFormatting.GRAY + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    .asWidget()
                    .setEnabledIf(w -> multiblock.getBaseMetaTileEntity().isActive()))
            .child(
                IKey.dynamic(
                    () -> EnumChatFormatting.WHITE + "Fluid: "
                        + EnumChatFormatting.AQUA + fluidNameSync.getValue())
                    .asWidget()
                    .setEnabledIf(w -> multiblock.getBaseMetaTileEntity().isActive()
                        && GTUtility.isStringValid(fluidNameSync.getValue())))
            .child(
                IKey.dynamic(
                    () -> EnumChatFormatting.WHITE + "Mode: "
                        + EnumChatFormatting.GOLD + modeNameSync.getValue())
                    .asWidget())
            .child(
                IKey.dynamic(
                    () -> EnumChatFormatting.WHITE + "Loop: "
                        + EnumChatFormatting.YELLOW + loopStatusSync.getValue())
                    .asWidget())
            .child(
                IKey.dynamic(
                    () -> EnumChatFormatting.WHITE + "  Segments: "
                        + EnumChatFormatting.GREEN + loopSegmentsSync.getValue()
                        + EnumChatFormatting.WHITE + "  Conduction: "
                        + EnumChatFormatting.AQUA + loopConductionSync.getValue()
                        + EnumChatFormatting.WHITE + "  Exchange: "
                        + EnumChatFormatting.RED + loopExchangeSync.getValue())
                    .asWidget())
            .child(
                IKey.dynamic(
                    () -> EnumChatFormatting.WHITE + "  Pressure Drop: "
                        + EnumChatFormatting.RED + String.format("%.2f", loopPressureDropSync.getValue()) + " bar"
                        + EnumChatFormatting.WHITE + "  Outlet: "
                        + EnumChatFormatting.AQUA + String.format("%.2f", loopOutletPressureSync.getValue()) + " bar")
                    .asWidget())
            .child(
                IKey.dynamic(
                    () -> EnumChatFormatting.WHITE + "  Constant Time: "
                        + EnumChatFormatting.GREEN + constantTicksSync.getValue() + " ticks"
                        + EnumChatFormatting.WHITE + "  Target: "
                        + EnumChatFormatting.YELLOW + String.format("%.1f", targetTempSync.getValue()) + "K")
                    .asWidget())
            .child(
                IKey.dynamic(
                    () -> EnumChatFormatting.WHITE + "Input Network:")
                    .asWidget()
                    .setEnabledIf(w -> inputBaseLitersSync.getLongValue() > 0))
            .child(
                IKey.dynamic(
                    () -> EnumChatFormatting.WHITE + "  Temperature: "
                        + EnumChatFormatting.RED + String.format("%.1f", inputTempSync.getValue()) + "K")
                    .asWidget()
                    .setEnabledIf(w -> inputBaseLitersSync.getLongValue() > 0))
            .child(
                IKey.dynamic(
                    () -> EnumChatFormatting.WHITE + "  Mass: "
                        + EnumChatFormatting.GREEN + SubstanceAmount.fromRawUnits(inputMicroKgSync.getLongValue()).formatKilograms() + " kg")
                    .asWidget()
                    .setEnabledIf(w -> inputBaseLitersSync.getLongValue() > 0))
            .child(
                IKey.dynamic(
                    () -> EnumChatFormatting.WHITE + "  Base volume: "
                        + EnumChatFormatting.YELLOW + formatNumber(inputBaseLitersSync.getLongValue()) + " L")
                    .asWidget()
                    .setEnabledIf(w -> inputBaseLitersSync.getLongValue() > 0))
            .child(
                IKey.dynamic(
                    () -> EnumChatFormatting.WHITE + "Output Network:")
                    .asWidget()
                    .setEnabledIf(w -> outputBaseLitersSync.getLongValue() > 0))
            .child(
                IKey.dynamic(
                    () -> EnumChatFormatting.WHITE + "  Temperature: "
                        + EnumChatFormatting.AQUA + String.format("%.1f", outputTempSync.getValue()) + "K"
                        + EnumChatFormatting.GRAY + " (loop)")
                    .asWidget()
                    .setEnabledIf(w -> outputBaseLitersSync.getLongValue() > 0))
            .child(
                IKey.dynamic(
                    () -> EnumChatFormatting.WHITE + "  Mass: "
                        + EnumChatFormatting.GREEN + SubstanceAmount.fromRawUnits(outputMicroKgSync.getLongValue()).formatKilograms() + " kg")
                    .asWidget()
                    .setEnabledIf(w -> outputBaseLitersSync.getLongValue() > 0))
            .child(
                IKey.dynamic(
                    () -> EnumChatFormatting.WHITE + "  Base volume: "
                        + EnumChatFormatting.YELLOW + formatNumber(outputBaseLitersSync.getLongValue()) + " L")
                    .asWidget()
                    .setEnabledIf(w -> outputBaseLitersSync.getLongValue() > 0));
    }

    @Override
    protected void registerSyncValues(PanelSyncManager syncManager) {
        super.registerSyncValues(syncManager);

        FloatSyncValue inputTempSync = new FloatSyncValue(multiblock::getInputTemperature);
        syncManager.syncValue("inputTemp", inputTempSync);

        FloatSyncValue outputTempSync = new FloatSyncValue(multiblock::getOutputTemperature);
        syncManager.syncValue("outputTemp", outputTempSync);

        FloatSyncValue loopOutletPressureSync = new FloatSyncValue(multiblock::getLoopOutletPressure);
        syncManager.syncValue("loopOutletPressure", loopOutletPressureSync);

        FloatSyncValue loopPressureDropSync = new FloatSyncValue(multiblock::getLoopPressureDropBar);
        syncManager.syncValue("loopPressureDrop", loopPressureDropSync);

        FloatSyncValue targetTempSync = new FloatSyncValue(multiblock::getTargetTemperatureSetting,
            multiblock::setTargetTemperatureSetting);
        syncManager.syncValue("targetTemperature", targetTempSync.allowC2S());

        LongSyncValue inputBaseLitersSync = new LongSyncValue(multiblock::getInputBaseLiters);
        syncManager.syncValue("inputBaseLiters", inputBaseLitersSync);

        LongSyncValue inputMicroKgSync = new LongSyncValue(multiblock::getInputMicroKg);
        syncManager.syncValue("inputMicroKg", inputMicroKgSync);

        LongSyncValue outputBaseLitersSync = new LongSyncValue(multiblock::getOutputBaseLiters);
        syncManager.syncValue("outputBaseLiters", outputBaseLitersSync);

        LongSyncValue outputMicroKgSync = new LongSyncValue(multiblock::getOutputMicroKg);
        syncManager.syncValue("outputMicroKg", outputMicroKgSync);

        IntSyncValue loopSegmentsSync = new IntSyncValue(multiblock::getLoopSegmentCount);
        syncManager.syncValue("loopSegments", loopSegmentsSync);

        IntSyncValue loopConductionSync = new IntSyncValue(multiblock::getLoopConductionModuleCount);
        syncManager.syncValue("loopConduction", loopConductionSync);

        IntSyncValue loopExchangeSync = new IntSyncValue(multiblock::getLoopHeatExchangeModuleCount);
        syncManager.syncValue("loopExchange", loopExchangeSync);

        IntSyncValue constantTicksSync = new IntSyncValue(multiblock::getConstantOperationTicks,
            multiblock::setConstantOperationTicks);
        syncManager.syncValue("constantTicks", constantTicksSync.allowC2S());

        StringSyncValue fluidNameSync = new StringSyncValue(multiblock::getFluidName);
        syncManager.syncValue("fluidName", fluidNameSync);

        StringSyncValue loopStatusSync = new StringSyncValue(multiblock::getLoopStatus);
        syncManager.syncValue("loopStatus", loopStatusSync);

        StringSyncValue modeNameSync = new StringSyncValue(multiblock::getMachineModeName);
        syncManager.syncValue("modeName", modeNameSync);

        IntSyncValue modeSync = new IntSyncValue(multiblock::getMachineMode, multiblock::setMachineMode);
        syncManager.syncValue("machineMode", modeSync.allowC2S());
    }

    @Override
    protected Flow createRightPanelGapRow(ModularPanel parent, PanelSyncManager syncManager) {
        return Flow.row()
            .mainAxisAlignment(Alignment.MainAxis.END)
            .crossAxisAlignment(Alignment.CrossAxis.CENTER)
            .reverseLayout(true)
            .align(Alignment.CenterRight)
            .coverChildrenWidth()
            .heightRel(1)
            .childIf(multiblock.supportsPowerPanel(), () -> createPowerPanelButton(syncManager, parent))
            .child(createSettingsPanelButton(syncManager, parent));
    }

    private IWidget createSettingsPanelButton(PanelSyncManager syncManager, ModularPanel parent) {
        IntSyncValue modeSync = syncManager.findSyncHandler("machineMode", IntSyncValue.class);
        IntSyncValue constantTicksSync = syncManager.findSyncHandler("constantTicks", IntSyncValue.class);
        FloatSyncValue targetTempSync = syncManager.findSyncHandler("targetTemperature", FloatSyncValue.class);

        settingsPanel = syncManager.syncedPanel(
            "radiatorSettings",
            true,
            (pSyncManager, syncHandler) -> openSettingsPanel(parent, modeSync, constantTicksSync, targetTempSync)
        );

        return new ButtonWidget<>().name("radiatorSettingsButton").size(18, 18)
            .marginRight(4)
            .overlay(IKey.str(EnumChatFormatting.AQUA + "R"))
            .onMousePressed(d -> {
                if (settingsPanel.isPanelOpen()) {
                    settingsPanel.closePanel();
                } else {
                    settingsPanel.openPanel();
                }
                return true;
            })
            .tooltipBuilder(t -> t.addLine(IKey.str("Radiator Settings"))
                .addLine(IKey.str("Configure mode, time and target temperature")));
    }

    private ModularPanel openSettingsPanel(ModularPanel parent, IntSyncValue modeSync, IntSyncValue constantTicksSync,
        FloatSyncValue targetTempSync) {
        return new ModularPanel("radiatorSettings").relative(parent)
            .leftRel(1)
            .topRel(0)
            .size(145, 145)
            .child(
                new Column().sizeRel(1)
                    .padding(5)
                    .child(
                        new TextWidget<>(EnumChatFormatting.UNDERLINE + "Radiator Settings")
                            .alignment(Alignment.Center)
                            .widthRel(1)
                            .height(18)
                            .marginBottom(4))
                    .child(createModeButton("Target Temperature", 1, modeSync))
                    .child(createModeButton("Constant Time", 0, modeSync))
                    .child(createTargetTemperatureField(targetTempSync))
                    .child(createConstantTimeField(constantTicksSync)));
    }

    private IWidget createModeButton(String label, int mode, IntSyncValue modeSync) {
        return new ButtonWidget<>().name(mode == 0 ? "radiatorTimed" : "radiatorTarget").widthRel(1)
            .height(18)
            .marginBottom(4)
            .overlay(IKey.dynamic(() -> (modeSync.getValue() == mode ? EnumChatFormatting.GREEN : EnumChatFormatting.GRAY)
                + label))
            .onMousePressed(d -> {
                modeSync.updateCacheFromSource(false);
                modeSync.setValue(mode);
                return true;
            });
    }

    private IWidget createTargetTemperatureField(FloatSyncValue targetTempSync) {
        return new Row().widthRel(1).height(18).marginTop(4)
            .child(
                new TextWidget<>("Target (K):")
                    .width(70)
                    .alignment(Alignment.CenterLeft))
            .child(
                new TextFieldWidget().name("radiatorTemperature")
                    .setNumbersDouble(value -> value)
                    .width(60)
                    .height(18)
                    .value(targetTempSync)
                    .setTextAlignment(Alignment.Center));
    }

    private IWidget createConstantTimeField(IntSyncValue constantTicksSync) {
        return new Row().widthRel(1).height(18).marginTop(4)
            .child(
                new TextWidget<>("Time (ticks):")
                    .width(70)
                    .alignment(Alignment.CenterLeft))
            .child(
                new TextFieldWidget().name("radiatorTicks")
                    .width(60)
                    .height(18)
                    .setNumbers(20, 20 * 60)
                    .value(constantTicksSync)
                    .setTextAlignment(Alignment.Center));
    }
}
