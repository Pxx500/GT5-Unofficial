package gregtech.common.tileentities.machines.multi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.jupiter.api.Test;

import gregtech.api.metatileentity.implementations.integratedfluid.IFNHeatPumpMachineWorkPlan;
import gregtech.api.metatileentity.implementations.integratedfluid.machine.IFNBatchState;
import gregtech.api.metatileentity.implementations.integratedfluid.machine.IFNMachineOutputBuffer;
import gregtech.api.metatileentity.implementations.integratedfluid.machine.IFNMachineProcess;
import gregtech.common.tileentities.machines.multi.heatpump.HeatPumpMachineProcessState;

class HeatPumpMachineProcessStateTest {

    @Test
    void interruptedWorkResumesWithOriginalCostAndOnlyRemainingTicks() {
        HeatPumpMachineProcessState state = new HeatPumpMachineProcessState();
        state.outputBuffer()
            .addPendingOutput("red", IFNBatchState.of("water", 1_000L, 350.0d, 1.0f));
        state.beginWork(IFNHeatPumpMachineWorkPlan.of(false, false, 2_560L, 0));
        for (int tick = 0; tick < 7; tick++) {
            state.recordPoweredTick();
        }

        NBTTagCompound saved = new NBTTagCompound();
        state.save(saved);
        HeatPumpMachineProcessState resumed = new HeatPumpMachineProcessState();
        resumed.load(saved);
        assertEquals(13, resumed.remainingWorkTicks());
        assertEquals(128, resumed.workEuPerTick());
        assertEquals(
            1_000L,
            resumed.outputBuffer()
                .pendingOutput("red")
                .amountQ());

        for (int tick = 0; tick < 13; tick++) {
            assertTrue(resumed.hasUnfinishedWork());
            resumed.recordPoweredTick();
        }
        assertFalse(resumed.hasUnfinishedWork());
        resumed.recordPoweredTick();
        assertEquals(0, resumed.remainingWorkTicks());
    }

    @Test
    void unfinishedPoweredWorkSurvivesSaveAndReload() {
        NBTTagCompound saved = new NBTTagCompound();
        NBTTagCompound processTag = new NBTTagCompound();
        processTag.setInteger("remainingWorkTicks", 13);
        processTag.setInteger("workEuPerTick", 128);
        saved.setTag(HeatPumpMachineProcessState.NBT_KEY, processTag);

        HeatPumpMachineProcessState state = new HeatPumpMachineProcessState();
        state.load(saved);
        NBTTagCompound reloaded = new NBTTagCompound();
        state.save(reloaded);

        NBTTagCompound restored = reloaded.getCompoundTag(HeatPumpMachineProcessState.NBT_KEY);
        assertEquals(13, restored.getInteger("remainingWorkTicks"));
        assertEquals(128, restored.getInteger("workEuPerTick"));
    }

    @Test
    void machineProcessStateRoundTripsThroughHeatPumpNbt() {
        IFNMachineProcess process = IFNMachineProcess.create();
        process.setEnabled(false);
        NBTTagCompound processTag = new NBTTagCompound();
        process.writeToNBT(processTag);
        IFNMachineOutputBuffer outputBuffer = new IFNMachineOutputBuffer();
        outputBuffer.addPendingOutput("red", IFNBatchState.of("water", 1_000L, 300.0d, 1.0f));
        NBTTagCompound outputBufferTag = new NBTTagCompound();
        outputBuffer.writeToNBT(outputBufferTag);

        NBTTagCompound inputTag = new NBTTagCompound();
        inputTag.setTag("ifnMachineProcess", processTag);
        inputTag.setTag("ifnMachineOutputBuffer", outputBufferTag);
        HeatPumpMachineProcessState state = new HeatPumpMachineProcessState();
        state.load(inputTag);

        NBTTagCompound outputTag = new NBTTagCompound();
        state.save(outputTag);
        IFNMachineProcess restored = IFNMachineProcess.readFromNBT(outputTag.getCompoundTag("ifnMachineProcess"));
        IFNMachineOutputBuffer restoredOutputBuffer = IFNMachineOutputBuffer
            .readFromNBT(outputTag.getCompoundTag("ifnMachineOutputBuffer"));

        assertFalse(restored.isEnabled());
        assertEquals(
            1_000L,
            restoredOutputBuffer.pendingOutput("red")
                .amountQ());
    }
}
