package gttests.vfn;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;

import gregtech.api.enums.ItemList;
import gregtech.api.enums.MetaTileEntityIDs;
import gregtech.api.enums.Mods;
import gregtech.api.metatileentity.implementations.MTEHatchMaintenance;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.util.shutdown.ShutDownReasonRegistry;
import gregtech.common.tileentities.machines.multi.HeatPumpMode;

@GameTestHolder(value = Mods.ModIDs.GREG_TECH, templatePrefix = "vfn")
public class MaintenanceTests {

    private static MTEHatchMaintenance manualHatch(GameTestHelper helper) {
        var p = helper.absolute("controller");
        return (MTEHatchMaintenance) InjectorTests.placeAt(helper.getWorld(), p.x() + 1, p.y(), p.z(),
            (short) MetaTileEntityIDs.MAINTENANCE_HATCH.ID, ForgeDirection.NORTH).getMetaTileEntity();
    }

    private static void breakMaintenance(MTEMultiBlockBase machine) {
        machine.mWrench = machine.mScrewdriver = machine.mSoftMallet = machine.mHardHammer =
            machine.mSolderingTool = machine.mCrowbar = false;
    }

    private static void requireStopped(GameTestHelper helper, MTEMultiBlockBase machine) {
        var controller = machine.getBaseMetaTileEntity();
        helper.assertTrue(!controller.isAllowedToWork() && !controller.isActive(), "Controller stopped itself");
        helper.assertEquals(ShutDownReasonRegistry.NO_REPAIR.getKey(), controller.getLastShutDownReason().getKey(), "Maintenance shutdown");
    }

    private static void repair(GameTestHelper helper, MTEHatchMaintenance hatch) {
        var tape = ItemList.Duct_Tape.get(1);
        hatch.onToolClick(tape, FakePlayerFactory.getMinecraft((WorldServer) helper.getWorld()));
        helper.assertEquals(0, tape.stackSize, "Repair consumes one GT duct tape");
    }

    @GameTest(template = "radiator", timeoutTicks = 650)
    public static void pumpFinishesOnlyTheUnpaidWork(GameTestHelper helper) {
        var rig = HeatPumpTests.place(helper, (short) 44);
        var hatch = manualHatch(helper);
        var controller = rig.pump().getBaseMetaTileEntity();
        var paused = new NBTTagCompound[1];
        helper.startSequence().thenIdle(20).thenExecute(() -> {
            rig.charge(helper, 310);
            rig.pump().setFluidAmountPerOperation(1000);
            rig.pump().setOperatingMode(HeatPumpMode.TARGET_ENERGY);
            rig.pump().setTargetEnergy(128);
            rig.pump().setTargetHeating(false);
            helper.assertTrue(rig.supply().increaseStoredEnergyUnits(2560, false), "Supply exactly one batch");
            helper.assertEquals(2560L, rig.supply().getStoredEU(), "Entire test budget fits in the energy hatch");
        }).thenWaitUntil(() -> {
            int ticks = rig.inventory().getInteger("pumpTicksLeft");
            helper.assertTrue(ticks > 0 && ticks < 20, "An unfinished paid charge");
        }).thenExecute(() -> {
            paused[0] = rig.inventory();
            rig.input().getNetwork().freeze("No second intake");
            breakMaintenance(rig.pump());
        }).thenWaitUntil(() -> requireStopped(helper, rig.pump())).thenIdle(40).thenExecute(() -> {
            var now = rig.inventory();
            helper.assertEquals(paused[0].getLong("pumpMicroKg"), now.getLong("pumpMicroKg"), "Working mass held");
            helper.assertEquals(paused[0].getLong("pumpMicroEu"), now.getLong("pumpMicroEu"), "No cooling while stopped");
            helper.assertEquals(paused[0].getInteger("pumpTicksLeft") * 128L, rig.supply().getStoredEU(), "Power left for unfinished ticks");
            helper.assertEquals(0L, rig.output().getNetwork().getAmountQ(), "No premature discharge");
            repair(helper, hatch);
        }).thenWaitUntil(() -> helper.assertEquals(6, rig.pump().getRepairStatus(), "Manual hatch repaired controller"))
            .thenExecute(controller::enableWorking)
            .thenWaitUntil(() -> helper.assertTrue(rig.output().getNetwork().getAmountQ() > 0, "Repaired pump discharged"))
            .thenExecute(() -> {
                helper.assertEquals(0L, rig.supply().getStoredEU(), "No repeated electrical bill");
                helper.assertEquals(paused[0].getLong("pumpMicroKg"), rig.output().getNetwork().getAmountQ()
                    + rig.inventory().getLong("pumpMicroKg"), "Only the held charge was delivered");
            }).thenSucceed();
    }

    @GameTest(template = "radiator", timeoutTicks = 650)
    public static void radiatorFinishesIsolatedChargeAfterRepair(GameTestHelper helper) {
        var rig = RadiatorTests.place(helper);
        var hatch = manualHatch(helper);
        var paused = new NBTTagCompound[1];
        helper.startSequence().thenIdle(20).thenExecute(() -> {
            rig.machine().setConstantOperationTicks(80);
            rig.charge(helper);
        }).thenWaitUntil(() -> {
            var state = rig.inventory();
            int ticks = state.getInteger("loopTicks");
            helper.assertTrue(state.getLong("loopMicroKg") > 0 && ticks > 0 && ticks < 78, "Working loop already cooling");
        }).thenExecute(() -> {
            paused[0] = rig.inventory();
            rig.inlet().getNetwork().freeze("Isolate the filled radiator");
            breakMaintenance(rig.machine());
        }).thenWaitUntil(() -> requireStopped(helper, rig.machine())).thenIdle(40).thenExecute(() -> {
            helper.assertEquals(paused[0].getLong("loopMicroEu"), rig.inventory().getLong("loopMicroEu"), "No operating heat transfer during shutdown");
            helper.assertEquals(paused[0].getInteger("loopTicks"), rig.inventory().getInteger("loopTicks"), "Operating time paused");
            helper.assertEquals(0L, rig.outlet().getNetwork().getAmountQ(), "No early output");
            repair(helper, hatch);
        }).thenWaitUntil(() -> helper.assertEquals(6, rig.machine().getRepairStatus(), "Manual hatch repaired controller"))
            .thenExecute(() -> rig.machine().getBaseMetaTileEntity().enableWorking())
            .thenWaitUntil(() -> helper.assertTrue(rig.outlet().getNetwork().getAmountQ() > 0, "Held charge discharged without another intake"))
            .thenExecute(() -> helper.assertEquals(paused[0].getLong("loopMicroKg"),
                rig.outlet().getNetwork().getAmountQ() + rig.heldMass(), "Entire isolated charge accounted for"))
            .thenSucceed();
    }
}
