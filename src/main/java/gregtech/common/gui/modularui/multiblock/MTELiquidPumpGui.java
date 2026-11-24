package gregtech.common.gui.modularui.multiblock;

import java.util.Locale;
import net.minecraft.util.EnumChatFormatting;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.value.sync.BooleanSyncValue;
import com.cleanroommc.modularui.value.sync.FloatSyncValue;
import com.cleanroommc.modularui.value.sync.IntSyncValue;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import com.cleanroommc.modularui.value.sync.StringSyncValue;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.ListWidget;
import com.cleanroommc.modularui.widgets.layout.Column;
import com.cleanroommc.modularui.widgets.layout.Flow;
import com.cleanroommc.modularui.widgets.layout.Row;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;

import gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidLiquidPump;
import gregtech.common.gui.modularui.multiblock.base.MTEMultiBlockBaseGui;

public final class MTELiquidPumpGui extends MTEMultiBlockBaseGui<MTEIntegratedFluidLiquidPump> {

    private static final String[] MODES = { "Outlet pressure", "Pressure rise", "Delivery volume" };

    public MTELiquidPumpGui(MTEIntegratedFluidLiquidPump pump) { super(pump); }

    @Override
    protected void registerSyncValues(PanelSyncManager s) {
        super.registerSyncValues(s);
        s.syncValue("gas", new BooleanSyncValue(multiblock::gasIntake, multiblock::setGasIntake).allowC2S());
        s.syncValue("mode", new IntSyncValue(() -> multiblock.mode().ordinal(), multiblock::setMode).allowC2S());
        s.syncValue("target", new IntSyncValue(multiblock::target, multiblock::setTarget).allowC2S());
        s.syncValue("floor", new IntSyncValue(multiblock::inletFloor, multiblock::setInletFloor).allowC2S());
        s.syncValue("period", new IntSyncValue(multiblock::interval, multiblock::setInterval).allowC2S());
        s.syncValue("inletBar", new FloatSyncValue(multiblock::inletBar));
        s.syncValue("outletBar", new FloatSyncValue(multiblock::outletBar));
        s.syncValue("pumpStatus", new StringSyncValue(multiblock::status));
    }

    @Override
    protected ListWidget<IWidget, ?> createTerminalTextWidget(PanelSyncManager s, ModularPanel parent) {
        var inlet = s.findSyncHandler("inletBar", FloatSyncValue.class);
        var outlet = s.findSyncHandler("outletBar", FloatSyncValue.class);
        var status = s.findSyncHandler("pumpStatus", StringSyncValue.class);
        var mode = s.findSyncHandler("mode", IntSyncValue.class);
        var target = s.findSyncHandler("target", IntSyncValue.class);
        return super.createTerminalTextWidget(s, parent)
            .child(IKey.dynamic(() -> EnumChatFormatting.WHITE + MODES[mode.getValue()] + ": " + target.getValue() + (mode.getValue() == 2 ? " L" : " cbar")).asWidget())
            .child(IKey.dynamic(() -> EnumChatFormatting.AQUA + String.format(Locale.ROOT, "In: %.2f bar   Out: %.2f bar", inlet.getValue(), outlet.getValue())).asWidget())
            .child(IKey.dynamic(() -> EnumChatFormatting.YELLOW + status.getValue()).asWidget());
    }

    @Override
    protected Flow createRightPanelGapRow(ModularPanel parent, PanelSyncManager s) {
        var mode = s.findSyncHandler("mode", IntSyncValue.class);
        var target = s.findSyncHandler("target", IntSyncValue.class);
        var floor = s.findSyncHandler("floor", IntSyncValue.class);
        var period = s.findSyncHandler("period", IntSyncValue.class);
        var gas = s.findSyncHandler("gas", BooleanSyncValue.class);
        var panel = s.syncedPanel("pumpSettings", true, (ps, handler) -> new ModularPanel("pumpSettings")
            .relative(parent).leftRel(1).topRel(0).size(158, 144)
            .child(new Column().sizeRel(1).padding(5)
                .child(IKey.str("Pump settings").asWidget().height(16))
                .child(new ButtonWidget<>().name("pumpMode").widthRel(1).height(18)
                    .overlay(IKey.dynamic(() -> MODES[mode.getValue()]))
                    .onMousePressed(d -> { mode.setValue((mode.getValue() + 1) % MODES.length); return true; }))
                .child(field("pumpTarget", IKey.dynamic(() -> mode.getValue() == 2 ? "Stroke (L)" : "Target (cbar)"), target, 1, 960000))
                .child(field("pumpFloor", IKey.str("Min inlet (cbar)"), floor, 0, 35000))
                .child(field("pumpInterval", IKey.str("Period (x20 t)"), period, 1, 3000))
                .child(new ButtonWidget<>().name("pumpIntake").widthRel(1).height(18)
                    .overlay(IKey.dynamic(() -> gas.getBoolValue() ? "Intake: gas" : "Intake: liquid"))
                    .onMousePressed(d -> { gas.setBoolValue(!gas.getBoolValue()); return true; }))
                .child(IKey.str("Stops within 1 cbar").asWidget().setEnabledIf(w -> mode.getValue() != 2))));
        return super.createRightPanelGapRow(parent, s).child(new ButtonWidget<>().name("pumpSettingsButton")
            .size(18, 18).overlay(IKey.str("P")).tooltipBuilder(t -> t.addLine(IKey.str("Pump settings")))
            .onMousePressed(d -> { if (panel.isPanelOpen()) panel.closePanel(); else panel.openPanel(); return true; }));
    }

    private static Flow field(String id, IKey label, IntSyncValue sync, int min, int max) {
        return new Row().widthRel(1).height(19).child(label.asWidget().width(91))
            .child(new TextFieldWidget().name(id).width(50).height(16).setNumbers(min, max).value(sync));
    }
}
