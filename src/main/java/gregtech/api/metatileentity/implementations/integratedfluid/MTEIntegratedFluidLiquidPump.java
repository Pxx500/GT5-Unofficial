package gregtech.api.metatileentity.implementations.integratedfluid;

import static gregtech.api.enums.HatchElement.Energy;

import java.math.BigDecimal;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizon.structurelib.structure.IStructureDefinition;

import gregtech.api.GregTechAPI;
import gregtech.api.enums.GTValues;
import gregtech.api.enums.Textures;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.integratedfluid.machine.IFNPumpPlanner;
import gregtech.api.metatileentity.implementations.integratedfluid.machine.IFNPumpPlanner.Mode;
import gregtech.api.metatileentity.implementations.integratedfluid.machine.IFNPumpPlanner.Request;
import gregtech.api.recipe.check.CheckRecipeResult;
import gregtech.api.recipe.check.CheckRecipeResultRegistry;
import gregtech.api.render.TextureFactory;
import gregtech.api.util.MultiblockTooltipBuilder;
import gregtech.common.gui.modularui.multiblock.MTELiquidPumpGui;

public final class MTEIntegratedFluidLiquidPump extends MTEVFNFlowMachine<MTEIntegratedFluidLiquidPump> {

    public static final short HIGH_PRESSURE_ID = 5781;
    private static final int STROKE_TICKS = 20, MAX_FLOW = 16;
    private static final IStructureDefinition<MTEIntegratedFluidLiquidPump> STRUCTURE = steelHousing(MTEIntegratedFluidLiquidPump.class, Energy);

    private final int tier;
    private Mode mode = Mode.TARGET_OUTPUT_PRESSURE;
    private int outletCbar = 200, riseCbar = 100, strokeLiters = 20, contactInterval = 1, inletFloorCbar;
    private String status = "Idle";
    private boolean gasIntake;

    public MTEIntegratedFluidLiquidPump(int id, String name, String regionalName, int tier) {
        super(id, name, regionalName);
        this.tier = tier;
    }

    private MTEIntegratedFluidLiquidPump(String name, int tier) {
        super(name);
        this.tier = tier;
    }

    @Override
    public IMetaTileEntity newMetaEntity(IGregTechTileEntity tile) { return new MTEIntegratedFluidLiquidPump(mName, tier); }

    @Override protected boolean hasPowerHatch() { return mEnergyHatches.size() == 1; }

    @Override
    public CheckRecipeResult checkProcessing() {
        var a = input == null ? null : input.getNetwork();
        var b = output == null ? null : output.getNetwork();
        if (a == b || !IFNNetworkTransferGate.isOperational(a) || !IFNNetworkTransferGate.isOperational(b))
            return idle("Connect two separate networks");
        if (a.getAmountQ() == 0) return idle("Empty inlet");
        if (a.getPressurePa() < inletFloorCbar * 1000.0) return idle("Inlet below minimum pressure");
        float inlet = a.getPressure(), outlet = b.getPressure();
        // Pressure settings have one centibar of resolution
        if (mode != Mode.TARGET_FLOW) {
            double targetPa = mode == Mode.TARGET_OUTPUT_PRESSURE ? outletCbar * 1000.0 : a.getPressurePa() + riseCbar * 1000.0;
            if (b.getPressurePa() >= targetPa - 1000) return idle("Pressure target reached");
        }
        int sweepTicks = sweepTicks();
        var request = switch (mode) {
            case TARGET_OUTPUT_PRESSURE -> Request.targetOutputPressure(inlet, outlet, outletCbar / 100f, maxRiseBar(), MAX_FLOW);
            case TARGET_PRESSURE_DIFFERENTIAL -> Request.targetPressureDifferential(inlet, outlet, riseCbar / 100f, maxRiseBar(), MAX_FLOW);
            case TARGET_FLOW -> Request.targetFlow(inlet, outlet, (double) strokeLiters / sweepTicks, maxRiseBar(), MAX_FLOW);
        };
        var plan = IFNPumpPlanner.plan(request);
        if (plan.status() != IFNPumpPlanner.Status.READY) return idle("Outlet at or above pressure limit");
        var supply = mEnergyHatches.get(0).getBaseMetaTileEntity();
        if (supply.getStoredEU() == 0) return idle("No power in energy hatch");
        int period = STROKE_TICKS * contactInterval;
        double ceiling = plan.machineOutputPressureBar() * 100000.0;
        double head = Math.min(maxRiseBar() * 100000.0, Math.max(0, ceiling - a.getPressurePa()));
        long paid = a.pumpTo(b, supply, BigDecimal.valueOf(plan.flowLitersPerTick() * sweepTicks),
            ceiling, head, Math.min(GTValues.V[tier], getMaxInputPower()) * period, inletFloorCbar * 1000.0, gasIntake);
        if (paid == 0) return idle("No whole portion within pressure and power limits");
        status = "Pumping";
        mEfficiency = 10000;
        mEUt = 0;
        mMaxProgresstime = period;
        return CheckRecipeResultRegistry.SUCCESSFUL;
    }

    private CheckRecipeResult idle(String reason) {
        status = reason;
        return CheckRecipeResultRegistry.NO_RECIPE;
    }

    public int maxRiseBar() { return tier == 4 ? 120 : 3; }
    public Mode mode() { return mode; }
    public void setMode(int value) {
        if (value >= 0 && value < Mode.values().length) mode = Mode.values()[value];
        getBaseMetaTileEntity().markDirty();
    }
    public int target() {
        return switch (mode) { case TARGET_OUTPUT_PRESSURE -> outletCbar; case TARGET_PRESSURE_DIFFERENTIAL -> riseCbar; case TARGET_FLOW -> strokeLiters; };
    }
    public void setTarget(int value) {
        switch (mode) {
            case TARGET_OUTPUT_PRESSURE -> outletCbar = clamp(value, tier == 4 ? 35000 : 10000);
            case TARGET_PRESSURE_DIFFERENTIAL -> riseCbar = clamp(value, maxRiseBar() * 100);
            case TARGET_FLOW -> strokeLiters = clamp(value, MAX_FLOW * sweepTicks());
        }
        getBaseMetaTileEntity().markDirty();
    }
    public int interval() { return contactInterval; }
    public void setInterval(int value) {
        contactInterval = clamp(value, 3000);
        limitStroke();
        getBaseMetaTileEntity().markDirty();
    }
    private int sweepTicks() { return STROKE_TICKS * (gasIntake ? contactInterval : 1); }
    private void limitStroke() { strokeLiters = clamp(strokeLiters, MAX_FLOW * sweepTicks()); }
    public boolean gasIntake() { return gasIntake; }
    public void setGasIntake(boolean value) {
        gasIntake = value;
        limitStroke();
        getBaseMetaTileEntity().markDirty();
    }
    public int inletFloor() { return inletFloorCbar; }
    public void setInletFloor(int value) { inletFloorCbar = Math.max(0, Math.min(35000, value)); getBaseMetaTileEntity().markDirty(); }
    public String status() { return status; }

    @Override protected boolean useMui2() { return true; }
    @Override protected MTELiquidPumpGui getGui() { return new MTELiquidPumpGui(this); }
    @Override public IStructureDefinition<MTEIntegratedFluidLiquidPump> getStructureDefinition() { return STRUCTURE; }
    @Override protected MultiblockTooltipBuilder createTooltip() {
        return new MultiblockTooltipBuilder().addMachineType("Pressure Pump")
            .addInfo("Controls outlet pressure, pressure rise or delivery rate")
            .addInfo("Select liquid or gas intake. A longer gas stroke sweeps more inlet volume")
            .addInfo("Maximum pressure rise: " + maxRiseBar() + " bar")
            .beginStructureBlock(3, 3, 3, true).addController("Front center")
            .addCasingInfoExactly("Solid Steel Machine Casing", 22, false)
            .addInputHatch("One VFN input hatch", 1).addOutputHatch("One VFN output hatch", 1)
            .addEnergyHatch("One energy hatch", 1).addMaintenanceHatch("One maintenance hatch", 1).toolTipFinisher("GregTech");
    }
    @Override public ITexture[] getTexture(IGregTechTileEntity tile, ForgeDirection side, ForgeDirection facing, int color,
        boolean active, boolean redstone) {
        var casing = TextureFactory.of(GregTechAPI.sBlockCasings2, 0);
        return side == facing ? new ITexture[] { casing, TextureFactory.of(Textures.BlockIcons.OVERLAY_PIPE_OUT) } : new ITexture[] { casing };
    }
    @Override public void saveNBTData(NBTTagCompound tag) {
        super.saveNBTData(tag);
        tag.setString("pumpMode", mode.name());
        tag.setBoolean("gasIntake", gasIntake);
        tag.setInteger("outletCbar", outletCbar);
        tag.setInteger("riseCbar", riseCbar);
        tag.setInteger("strokeLiters", strokeLiters);
        tag.setInteger("contactInterval", contactInterval);
        tag.setInteger("inletFloorCbar", inletFloorCbar);
    }
    @Override public void loadNBTData(NBTTagCompound tag) {
        super.loadNBTData(tag);
        for (Mode candidate : Mode.values()) if (candidate.name().equals(tag.getString("pumpMode"))) mode = candidate;
        if (tag.hasKey("outletCbar")) outletCbar = clamp(tag.getInteger("outletCbar"), tier == 4 ? 35000 : 10000);
        if (tag.hasKey("riseCbar")) riseCbar = clamp(tag.getInteger("riseCbar"), maxRiseBar() * 100);
        gasIntake = tag.getBoolean("gasIntake");
        if (tag.hasKey("contactInterval")) contactInterval = clamp(tag.getInteger("contactInterval"), 3000);
        if (tag.hasKey("strokeLiters")) strokeLiters = clamp(tag.getInteger("strokeLiters"), MAX_FLOW * sweepTicks());
        inletFloorCbar = Math.max(0, Math.min(35000, tag.getInteger("inletFloorCbar")));
    }
    private static int clamp(int value, int max) { return Math.max(1, Math.min(max, value)); }
}
