package gregtech.common.tileentities.machines.multi.heatpump;

import net.minecraft.nbt.NBTTagCompound;

import gregtech.api.metatileentity.implementations.integratedfluid.IFNHeatPumpMachineWorkPlan;
import gregtech.api.metatileentity.implementations.integratedfluid.machine.IFNMachineOutputBuffer;
import gregtech.api.metatileentity.implementations.integratedfluid.machine.IFNMachineProcess;

public final class HeatPumpMachineProcessState {

    public static final String NBT_KEY = "ifnMachineProcess";
    public static final String OUTPUT_BUFFER_NBT_KEY = "ifnMachineOutputBuffer";

    private IFNMachineProcess process = IFNMachineProcess.create();
    private IFNMachineOutputBuffer outputBuffer = new IFNMachineOutputBuffer();
    private int remainingWorkTicks;
    private int workEuPerTick;

    public IFNMachineProcess process() {
        return process;
    }

    public IFNMachineOutputBuffer outputBuffer() {
        return outputBuffer;
    }

    public void beginWork(IFNHeatPumpMachineWorkPlan plan) {
        remainingWorkTicks = plan.getMaxProgressTime();
        workEuPerTick = -plan.getEuT();
    }

    public boolean hasUnfinishedWork() {
        return remainingWorkTicks > 0;
    }

    public int remainingWorkTicks() {
        return remainingWorkTicks;
    }

    public int workEuPerTick() {
        return workEuPerTick;
    }

    public void recordPoweredTick() {
        if (hasUnfinishedWork()) {
            remainingWorkTicks--;
        }
    }

    public void save(NBTTagCompound tag) {
        NBTTagCompound processTag = new NBTTagCompound();
        process.writeToNBT(processTag);
        processTag.setInteger("remainingWorkTicks", remainingWorkTicks);
        processTag.setInteger("workEuPerTick", workEuPerTick);
        tag.setTag(NBT_KEY, processTag);
        NBTTagCompound outputBufferTag = new NBTTagCompound();
        outputBuffer.writeToNBT(outputBufferTag);
        tag.setTag(OUTPUT_BUFFER_NBT_KEY, outputBufferTag);
    }

    public void load(NBTTagCompound tag) {
        if (tag != null && tag.hasKey(NBT_KEY)) {
            NBTTagCompound processTag = tag.getCompoundTag(NBT_KEY);
            process = IFNMachineProcess.readFromNBT(processTag);
            remainingWorkTicks = processTag.getInteger("remainingWorkTicks");
            workEuPerTick = processTag.getInteger("workEuPerTick");
        }
        if (tag != null && tag.hasKey(OUTPUT_BUFFER_NBT_KEY)) {
            outputBuffer = IFNMachineOutputBuffer.readFromNBT(tag.getCompoundTag(OUTPUT_BUFFER_NBT_KEY));
        }
    }
}
