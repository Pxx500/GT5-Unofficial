package gregtech.api.metatileentity.implementations.integratedfluid;

import static gregtech.api.enums.Textures.BlockIcons.OVERLAY_PIPE_IN;
import static gregtech.api.enums.Textures.BlockIcons.OVERLAY_PIPE_OUT;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;

import com.gtnewhorizons.modularui.api.math.Alignment;
import com.gtnewhorizons.modularui.api.math.Color;
import com.gtnewhorizons.modularui.api.screen.ModularWindow;
import com.gtnewhorizons.modularui.api.screen.UIBuildContext;
import com.gtnewhorizons.modularui.api.widget.Widget;
import com.gtnewhorizons.modularui.common.widget.DrawableWidget;
import com.gtnewhorizons.modularui.common.widget.TextWidget;
import com.gtnewhorizons.modularui.common.widget.textfield.TextFieldWidget;

import gregtech.api.metatileentity.implementations.integratedfluid.amount.VolumeAmount;
import gregtech.api.enums.Dyes;
import gregtech.api.gui.modularui.GTUITextures;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.render.TextureFactory;

public class MTEIntegratedFluidPressureRegulator extends MTEIntegratedFluidBoundary {

    private static final String TAG_SETPOINT_PRESSURE_BAR = "ifnRegulatorSetpointPressureBar";
    private static final String TAG_OVERSHOOT_TOLERANCE_BAR = "ifnRegulatorOvershootToleranceBar";
    private static final String TAG_MAX_PACKET_AMOUNT_Q = "ifnRegulatorMaxPacketAmountQ";

    static final float DEFAULT_SETPOINT_PRESSURE_BAR = 1.0f;
    static final float DEFAULT_OVERSHOOT_TOLERANCE_BAR = 0.05f;
    static final long DEFAULT_MAX_PACKET_AMOUNT_Q = 100L * VolumeAmount.SCALE;
    private static final int GUI_PRESSURE_SCALE_CENTIBAR = 100;
    private static final int GUI_MIN_CENTIBAR = 1;
    private static final int GUI_MAX_CENTIBAR = 1_000_000;
    private static final int GUI_MIN_PACKET_L = 1;
    private static final int GUI_MAX_PACKET_L = Integer.MAX_VALUE;

    private float setpointPressureBar = DEFAULT_SETPOINT_PRESSURE_BAR;
    private float overshootToleranceBar = DEFAULT_OVERSHOOT_TOLERANCE_BAR;
    private long maxPacketAmountQ = DEFAULT_MAX_PACKET_AMOUNT_Q;

    public MTEIntegratedFluidPressureRegulator(int aID, String aName, String aNameRegional, int aTier) {
        super(
            aID,
            aName,
            aNameRegional,
            aTier,
            new String[] { "Integrated Fluid Pressure Regulator",
                "Boundary block between two Integrated Fluid Networks",
                "Transfers from back side to front side only below set pressure",
                "Regulator does not add network capacity" });
    }

    public MTEIntegratedFluidPressureRegulator(String aName, int aTier, String[] aDescription,
        ITexture[][][] aTextures) {
        super(aName, aTier, aDescription, aTextures);
    }

    @Override
    public IMetaTileEntity newMetaEntity(IGregTechTileEntity aTileEntity) {
        return new MTEIntegratedFluidPressureRegulator(mName, mTier, mDescriptionArray, mTextures);
    }

    @Override
    public ITexture[] getTexturesActive(ITexture aBaseTexture) {
        return new ITexture[] { aBaseTexture,
            TextureFactory.of(OVERLAY_PIPE_IN, Dyes.getModulation(-1, new short[] { 255, 192, 64, 255 })),
            TextureFactory.of(OVERLAY_PIPE_OUT, Dyes.getModulation(-1, new short[] { 64, 255, 128, 255 })) };
    }

    @Override
    public ITexture[] getTexturesInactive(ITexture aBaseTexture) {
        return new ITexture[] { aBaseTexture,
            TextureFactory.of(OVERLAY_PIPE_IN, Dyes.getModulation(-1, new short[] { 192, 144, 64, 255 })),
            TextureFactory.of(OVERLAY_PIPE_OUT, Dyes.getModulation(-1, new short[] { 64, 192, 96, 255 })) };
    }

    @Override
    void onContact(IntegratedFluidNetwork back, IntegratedFluidNetwork front) {
        transferBetweenNetworks(back, front);
    }

    long transferBetweenNetworks(IntegratedFluidNetwork inputNetwork, IntegratedFluidNetwork outputNetwork) {
        if (inputNetwork == null || outputNetwork == null || outputNetwork.getPressure() >= setpointPressureBar) return 0;
        double ceilingPa = ((double) setpointPressureBar + overshootToleranceBar) * 100_000;
        return inputNetwork.meterTo(outputNetwork, maxPacketAmountQ, ceilingPa);
    }

    @Override
    public void saveNBTData(NBTTagCompound aNBT) {
        aNBT.setFloat(TAG_SETPOINT_PRESSURE_BAR, setpointPressureBar);
        aNBT.setFloat(TAG_OVERSHOOT_TOLERANCE_BAR, overshootToleranceBar);
        aNBT.setLong(TAG_MAX_PACKET_AMOUNT_Q, maxPacketAmountQ);
    }

    @Override
    public void loadNBTData(NBTTagCompound aNBT) {
        setpointPressureBar = aNBT.hasKey(TAG_SETPOINT_PRESSURE_BAR) ? aNBT.getFloat(TAG_SETPOINT_PRESSURE_BAR)
            : DEFAULT_SETPOINT_PRESSURE_BAR;
        overshootToleranceBar = aNBT.hasKey(TAG_OVERSHOOT_TOLERANCE_BAR)
            ? aNBT.getFloat(TAG_OVERSHOOT_TOLERANCE_BAR)
            : DEFAULT_OVERSHOOT_TOLERANCE_BAR;
        maxPacketAmountQ = aNBT.hasKey(TAG_MAX_PACKET_AMOUNT_Q) ? aNBT.getLong(TAG_MAX_PACKET_AMOUNT_Q)
            : DEFAULT_MAX_PACKET_AMOUNT_Q;
        clampConfiguration();
    }

    public float getSetpointPressureBar() {
        return setpointPressureBar;
    }

    public void setSetpointPressureBar(float setpointPressureBar) {
        this.setpointPressureBar = setpointPressureBar;
        clampConfiguration();
    }

    public float getOvershootToleranceBar() {
        return overshootToleranceBar;
    }

    public void setOvershootToleranceBar(float overshootToleranceBar) {
        this.overshootToleranceBar = overshootToleranceBar;
        clampConfiguration();
    }

    public long getMaxPacketAmountQ() {
        return maxPacketAmountQ;
    }

    public void setMaxPacketAmountQ(long maxPacketAmountQ) {
        this.maxPacketAmountQ = maxPacketAmountQ;
        clampConfiguration();
    }

    public int getSetpointPressureCentibar() {
        return Math.round(setpointPressureBar * GUI_PRESSURE_SCALE_CENTIBAR);
    }

    public void setSetpointPressureCentibar(int centibar) {
        setSetpointPressureBar((float) centibar / GUI_PRESSURE_SCALE_CENTIBAR);
    }

    public int getOvershootToleranceCentibar() {
        return Math.round(overshootToleranceBar * GUI_PRESSURE_SCALE_CENTIBAR);
    }

    public void setOvershootToleranceCentibar(int centibar) {
        setOvershootToleranceBar((float) centibar / GUI_PRESSURE_SCALE_CENTIBAR);
    }

    public int getMaxPacketAmountL() {
        return (int) Math.min(Integer.MAX_VALUE, maxPacketAmountQ / VolumeAmount.SCALE);
    }

    public void setMaxPacketAmountL(int liters) {
        setMaxPacketAmountQ((long) liters * VolumeAmount.SCALE);
    }

    private void clampConfiguration() {
        if (!Float.isFinite(setpointPressureBar)) {
            setpointPressureBar = DEFAULT_SETPOINT_PRESSURE_BAR;
        }
        setpointPressureBar = Math.max(IFNPressurePolicy.MIN_PRESSURE_BAR, setpointPressureBar);

        if (!Float.isFinite(overshootToleranceBar)) {
            overshootToleranceBar = DEFAULT_OVERSHOOT_TOLERANCE_BAR;
        }
        overshootToleranceBar = Math.max(0.0f, overshootToleranceBar);

        if (maxPacketAmountQ < VolumeAmount.SCALE) {
            maxPacketAmountQ = VolumeAmount.SCALE;
        }
    }

    @Override
    public boolean onRightclick(IGregTechTileEntity tile, EntityPlayer player) {
        openGui(player);
        return true;
    }

    @Override
    public void addUIWidgets(ModularWindow.Builder builder, UIBuildContext buildContext) {
        builder.widget(
            new DrawableWidget().setDrawable(GTUITextures.PICTURE_SCREEN_BLACK)
                .setPos(7, 16)
                .setSize(162, 62))
            .widget(new TextWidget("Set pressure").setDefaultColor(COLOR_TEXT_WHITE.get())
                .setPos(12, 22)
                .setSize(82, 12))
            .widget(createIntegerField(
                this::getSetpointPressureCentibar,
                this::setSetpointPressureCentibar,
                GUI_MIN_CENTIBAR,
                GUI_MAX_CENTIBAR).setPos(98, 20)
                    .setSize(42, 12))
            .widget(new TextWidget("cbar").setDefaultColor(COLOR_TEXT_WHITE.get())
                .setPos(143, 22)
                .setSize(24, 12))
            .widget(new TextWidget("Tolerance").setDefaultColor(COLOR_TEXT_WHITE.get())
                .setPos(12, 40)
                .setSize(82, 12))
            .widget(createIntegerField(
                this::getOvershootToleranceCentibar,
                this::setOvershootToleranceCentibar,
                0,
                GUI_MAX_CENTIBAR).setPos(98, 38)
                    .setSize(42, 12))
            .widget(new TextWidget("cbar").setDefaultColor(COLOR_TEXT_WHITE.get())
                .setPos(143, 40)
                .setSize(24, 12))
            .widget(new TextWidget("Packet").setDefaultColor(COLOR_TEXT_WHITE.get())
                .setPos(12, 58)
                .setSize(82, 12))
            .widget(createIntegerField(
                this::getMaxPacketAmountL,
                this::setMaxPacketAmountL,
                GUI_MIN_PACKET_L,
                GUI_MAX_PACKET_L).setPos(98, 56)
                    .setSize(42, 12))
            .widget(new TextWidget("L").setDefaultColor(COLOR_TEXT_WHITE.get())
                .setPos(143, 58)
                .setSize(24, 12));
    }

    private Widget createIntegerField(IntGetter getter, IntSetter setter, int min, int max) {
        return new TextFieldWidget().setGetter(() -> Integer.toString(getter.get()))
            .setSetter(value -> setter.set(parseInteger(value, getter.get())))
            .setNumbers(min, max)
            .setTextColor(Color.WHITE.dark(1))
            .setTextAlignment(Alignment.CenterRight)
            .setBackground(GTUITextures.BACKGROUND_TEXT_FIELD.withOffset(-1, -1, 2, 2));
    }

    private static int parseInteger(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private interface IntGetter {

        int get();
    }

    private interface IntSetter {

        void set(int value);
    }
}
