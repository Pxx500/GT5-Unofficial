package gregtech.api.metatileentity.implementations.integratedfluid;

import static gregtech.api.enums.GTValues.V;
import static gregtech.api.enums.Textures.BlockIcons.OVERLAY_PIPE_OUT;

import java.math.BigDecimal;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.StatCollector;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizons.modularui.api.screen.ModularWindow;
import com.gtnewhorizons.modularui.api.screen.UIBuildContext;
import com.gtnewhorizons.modularui.common.widget.CycleButtonWidget;
import com.gtnewhorizons.modularui.common.widget.TextWidget;
import com.gtnewhorizons.modularui.common.widget.textfield.NumericWidget;

import gregtech.api.gui.modularui.GTUITextures;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.integratedfluid.machine.IFNPumpPlanner;
import gregtech.api.metatileentity.implementations.integratedfluid.machine.IFNPumpPlanner.Mode;
import gregtech.api.metatileentity.implementations.integratedfluid.machine.IFNPumpPlanner.Request;
import gregtech.api.render.TextureFactory;

public final class MTEIntegratedFluidLiquidPump extends MTEIntegratedFluidBoundary {

    public static final short HIGH_PRESSURE_ID = 5781;
    private static final int MAX_FLOW_L_PER_TICK = 16;
    private Mode mode = Mode.TARGET_OUTPUT_PRESSURE;
    private int outletCbar = 200, riseCbar = 100;
    private int strokeLiters = CONTACT_TICKS;
    private int contactInterval = 1;

    public MTEIntegratedFluidLiquidPump(int id, String name, String regionalName, int tier) {
        super(id, name, regionalName, tier, new String[] {
            "Pumps liquid or dense fluid from the back to the front. Supply EU on a side face",
            "Maximum lift: " + maxRiseBar(tier) + " bar. Maximum flow: 16 L/t" });
    }

    private MTEIntegratedFluidLiquidPump(String name, int tier, String[] description, ITexture[][][] textures) {
        super(name, tier, description, textures);
    }

    @Override
    public IMetaTileEntity newMetaEntity(IGregTechTileEntity tile) {
        return new MTEIntegratedFluidLiquidPump(mName, mTier, mDescriptionArray, mTextures);
    }

    @Override
    void onContact(IntegratedFluidNetwork back, IntegratedFluidNetwork front) {
        var tile = getBaseMetaTileEntity();
        boolean moved = false;
        if (mode == Mode.TARGET_FLOW && tile.getTimer() / CONTACT_TICKS % contactInterval != 0) {
            tile.setActive(false);
            return;
        }
        if (back != null && front != null && tile.isAllowedToWork() && tile.getStoredEU() > 0) {
            float inlet = back.getPressure(), outlet = front.getPressure();
            int maxRise = maxRiseBar(mTier);
            var request = switch (mode) {
                case TARGET_OUTPUT_PRESSURE -> Request.targetOutputPressure(inlet, outlet, outletCbar / 100f, maxRise, MAX_FLOW_L_PER_TICK);
                case TARGET_PRESSURE_DIFFERENTIAL -> Request.targetPressureDifferential(inlet, outlet, riseCbar / 100f, maxRise, MAX_FLOW_L_PER_TICK);
                case TARGET_FLOW -> Request.targetFlow(inlet, outlet, (double) strokeLiters / CONTACT_TICKS, maxRise, MAX_FLOW_L_PER_TICK);
            };
            var plan = IFNPumpPlanner.plan(request);
            if (plan.status() == IFNPumpPlanner.Status.READY) {
                double ceilingPa = plan.machineOutputPressureBar() * 100000.0;
                moved = back.pumpLiquidTo(front, tile, BigDecimal.valueOf(plan.flowLitersPerTick() * CONTACT_TICKS),
                    ceilingPa, Math.min(maxRise * 100000.0, Math.max(0, ceilingPa - back.getPressurePa())),
                    maxEUInput() * CONTACT_TICKS * (mode == Mode.TARGET_FLOW ? contactInterval : 1)) > 0;
            }
        }
        tile.setActive(moved);
    }

    @Override
    public boolean isEnetInput() { return true; }

    @Override
    public boolean isInputFacing(ForgeDirection side) {
        return side != ForgeDirection.UNKNOWN && !acceptsNetworkOn(side, getBaseMetaTileEntity().getFrontFacing());
    }

    @Override
    public long maxEUInput() { return V[mTier]; }

    @Override
    public long maxEUStore() { return 2 * CONTACT_TICKS * maxEUInput(); }

    @Override
    public ITexture[] getTexturesActive(ITexture base) {
        return new ITexture[] { base, TextureFactory.of(OVERLAY_PIPE_OUT) };
    }

    @Override
    public ITexture[] getTexturesInactive(ITexture base) { return getTexturesActive(base); }

    @Override
    public boolean onRightclick(IGregTechTileEntity tile, EntityPlayer player) {
        openGui(player);
        return true;
    }

    @Override
    public void addUIWidgets(ModularWindow.Builder builder, UIBuildContext context) {
        builder.widget(new CycleButtonWidget().setForEnum(Mode.class, () -> mode, next -> {
            mode = next;
            getBaseMetaTileEntity().markDirty();
        }).setStaticTexture(GTUITextures.OVERLAY_BUTTON_CYCLIC).setPos(8, 20).setSize(18, 18));
        builder.widget(TextWidget.dynamicString(() -> StatCollector.translateToLocal(switch (mode) {
            case TARGET_OUTPUT_PRESSURE -> "GT5U.vfn.pump.outlet";
            case TARGET_PRESSURE_DIFFERENTIAL -> "GT5U.vfn.pump.rise";
            case TARGET_FLOW -> "GT5U.vfn.pump.flow";
        })).setPos(31, 23));
        builder.widget(new NumericWidget().setGetter(() -> switch (mode) {
            case TARGET_OUTPUT_PRESSURE -> outletCbar;
            case TARGET_PRESSURE_DIFFERENTIAL -> riseCbar;
            case TARGET_FLOW -> strokeLiters;
        }).setSetter(value -> {
            switch (mode) {
                case TARGET_OUTPUT_PRESSURE -> outletCbar = clamp((int) value, maxTargetCbar());
                case TARGET_PRESSURE_DIFFERENTIAL -> riseCbar = clamp((int) value, maxRiseBar(mTier) * 100);
                case TARGET_FLOW -> strokeLiters = clamp((int) value, MAX_FLOW_L_PER_TICK * CONTACT_TICKS);
            }
            getBaseMetaTileEntity().markDirty();
        }).setBounds(1, maxTargetCbar()).setIntegerOnly(true)
            .setTextColor(com.gtnewhorizons.modularui.api.math.Color.WHITE.normal)
            .setBackground(GTUITextures.BACKGROUND_TEXT_FIELD)
            .setPos(31, 43).setSize(64, 16));
        builder.widget(new NumericWidget().setGetter(() -> contactInterval).setSetter(value -> {
            contactInterval = clamp((int) value, 3000);
            getBaseMetaTileEntity().markDirty();
        }).setBounds(1, 3000).setIntegerOnly(true)
            .setTextColor(com.gtnewhorizons.modularui.api.math.Color.WHITE.normal).setBackground(GTUITextures.BACKGROUND_TEXT_FIELD)
            .setPos(31, 62).setSize(42, 16).setEnabled(w -> mode == Mode.TARGET_FLOW));
        builder.widget(TextWidget.dynamicString(() -> StatCollector.translateToLocal("GT5U.vfn.pump.interval"))
            .setPos(76, 66).setEnabled(w -> mode == Mode.TARGET_FLOW));
    }

    @Override
    public void saveNBTData(NBTTagCompound tag) {
        super.saveNBTData(tag);
        tag.setString("pumpMode", mode.name());
        tag.setInteger("outletCbar", outletCbar);
        tag.setInteger("riseCbar", riseCbar);
        tag.setInteger("strokeLiters", strokeLiters);
        tag.setInteger("contactInterval", contactInterval);
    }

    @Override
    public void loadNBTData(NBTTagCompound tag) {
        super.loadNBTData(tag);
        for (var setting : Mode.values()) if (setting.name().equals(tag.getString("pumpMode"))) mode = setting;
        if (tag.hasKey("outletCbar")) outletCbar = clamp(tag.getInteger("outletCbar"), maxTargetCbar());
        if (tag.hasKey("riseCbar")) riseCbar = clamp(tag.getInteger("riseCbar"), maxRiseBar(mTier) * 100);
        if (tag.hasKey("strokeLiters")) strokeLiters = clamp(tag.getInteger("strokeLiters"), MAX_FLOW_L_PER_TICK * CONTACT_TICKS);
        if (tag.hasKey("contactInterval")) contactInterval = clamp(tag.getInteger("contactInterval"), 3000);
    }

    private static int clamp(int value, int max) { return Math.max(1, Math.min(max, value)); }

    private static int maxRiseBar(int tier) { return tier == 4 ? 120 : 3; }

    private int maxTargetCbar() { return mTier == 4 ? 35000 : 10000; }
}
