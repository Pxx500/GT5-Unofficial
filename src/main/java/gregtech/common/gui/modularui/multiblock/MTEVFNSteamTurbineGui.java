package gregtech.common.gui.modularui.multiblock;

import java.util.Locale;

import net.minecraft.util.StatCollector;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.value.sync.IntSyncValue;
import com.cleanroommc.modularui.value.sync.FloatSyncValue;
import com.cleanroommc.modularui.value.sync.LongSyncValue;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import com.cleanroommc.modularui.widgets.ListWidget;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.layout.Column;
import com.cleanroommc.modularui.widgets.layout.Flow;
import com.cleanroommc.modularui.widgets.layout.Row;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;

import gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidSteamTurbine;
import gregtech.common.gui.modularui.multiblock.base.MTEMultiBlockBaseGui;

public final class MTEVFNSteamTurbineGui extends MTEMultiBlockBaseGui<MTEIntegratedFluidSteamTurbine> {
    public MTEVFNSteamTurbineGui(MTEIntegratedFluidSteamTurbine turbine) { super(turbine); }

    @Override protected void registerSyncValues(PanelSyncManager s) {
        super.registerSyncValues(s);
        s.syncValue("admission", new IntSyncValue(multiblock::admissionPercent, multiblock::setAdmissionPercent).allowC2S());
        s.syncValue("inletFloor", new IntSyncValue(multiblock::minimumInletCbar, multiblock::setMinimumInletCbar).allowC2S());
        s.syncValue("shaft", new LongSyncValue(multiblock::lastStrokeEu));
        s.syncValue("dynamoRoom", new LongSyncValue(multiblock::dynamoRoomEu));
        s.syncValue("inletBar", new FloatSyncValue(multiblock::inletBar));
        s.syncValue("exhaustBar", new FloatSyncValue(multiblock::outletBar));
    }

    @Override protected ListWidget<IWidget, ?> createTerminalTextWidget(PanelSyncManager s, ModularPanel parent) {
        var work = s.findSyncHandler("shaft", LongSyncValue.class);
        return super.createTerminalTextWidget(s, parent)
            .child(IKey.dynamic(() -> StatCollector.translateToLocalFormatted("GT5U.vfn.turbine.shaft", work.getValue())).asWidget());
    }

    @Override protected Flow createRightPanelGapRow(ModularPanel parent, PanelSyncManager s) {
        var inlet = s.findSyncHandler("inletBar", FloatSyncValue.class);
        var exhaust = s.findSyncHandler("exhaustBar", FloatSyncValue.class);
        var room = s.findSyncHandler("dynamoRoom", LongSyncValue.class);
        var panel = s.syncedPanel("turbineSettings", true, (ps, handler) -> new ModularPanel("turbineSettings")
            .relative(parent).leftRel(1).topRel(0).size(158, 100)
            .child(new Column().sizeRel(1).padding(5)
                .child(IKey.lang("GT5U.vfn.turbine.settings").asWidget().height(16))
                .child(field("admission", "GT5U.vfn.turbine.opening", s, 100))
                .child(field("inletFloor", "GT5U.vfn.turbine.inlet_floor", s, 35000))
                .child(IKey.dynamic(() -> StatCollector.translateToLocalFormatted("GT5U.vfn.turbine.pressures",
                    String.format(Locale.ROOT, "%.2f", inlet.getValue()), String.format(Locale.ROOT, "%.2f", exhaust.getValue()))).asWidget().height(16))
                .child(IKey.dynamic(() -> StatCollector.translateToLocalFormatted("GT5U.vfn.turbine.dynamo_room", room.getValue())).asWidget().height(16))));
        return super.createRightPanelGapRow(parent, s).child(new ButtonWidget<>().name("turbineSettingsButton")
            .size(18, 18).overlay(gregtech.api.modularui2.GTGuiTextures.OVERLAY_BUTTON_MACHINEMODE_STEAM)
            .tooltipBuilder(t -> t.addLine(IKey.lang("GT5U.vfn.turbine.settings")))
            .onMousePressed(d -> { if (panel.isPanelOpen()) panel.closePanel(); else panel.openPanel(); return true; }));
    }

    private static IWidget field(String name, String label, PanelSyncManager s, int max) {
        return new Row().widthRel(1).height(17).child(IKey.lang(label).asWidget().width(93))
            .child(new TextFieldWidget().name(name).width(38).height(15).setNumbers(0, max)
                .value(s.findSyncHandler(name, IntSyncValue.class)));
    }
}
