package gttests.vfn;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Properties;

import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidRegistry;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;

import gregtech.api.enums.Mods;
import gregtech.common.tileentities.machines.multi.HeatPumpMode;

@GameTestHolder(value = Mods.ModIDs.GREG_TECH, templatePrefix = "vfn")
public class RestartTests {

    private static Path checkpoint() {
        String path = System.getProperty("vfn.qa.restartCheckpoint");
        if (path == null) throw new IllegalStateException("Run misc/vfn_server_qa.py --restart");
        return Paths.get(path);
    }

    private static RemoteMachineSite site(GameTestHelper helper) {
        // Outside the cells that Horizon QA prepares for either launch
        var site = new RemoteMachineSite((WorldServer) helper.getWorld(), 8200, 240, 8200);
        site.reloadForObservation(helper);
        return site;
    }

    @GameTest(template = "radiator", timeoutTicks = 600)
    public static void stopWithPartlyPaidCharge(GameTestHelper helper) {
        prepareCharge(helper, false);
    }

    @GameTest(template = "radiator", timeoutTicks = 600)
    public static void stopWithOxygenCharge(GameTestHelper helper) {
        prepareCharge(helper, true);
    }

    private static void prepareCharge(GameTestHelper helper, boolean oxygen) {
        var file = checkpoint();
        var site = site(helper);
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = 0; dz <= 2; dz++)
            helper.assertTrue(site.world().isAirBlock(site.x() + dx, site.y() + dy, site.z() + dz), "Fresh restart fixture");
        var rig = HeatPumpTests.placeAt(site.world(), site.x(), site.y(), site.z(), (short) 42);
        if (oxygen) {
            InjectorTests.placeAt(site.world(), site.x() - 2, site.y(), site.z() + 1, (short) 5773, ForgeDirection.EAST);
            InjectorTests.placeAt(site.world(), site.x() + 2, site.y(), site.z() + 1, (short) 32032, ForgeDirection.WEST);
        }
        site.assignOwner();
        helper.startSequence().thenIdle(20).thenExecute(() -> {
            if (oxygen) {
                var stack = FluidRegistry.getFluidStack("oxygen", 8);
                helper.assertTrue(stack != null, "Ordinary GT oxygen is registered");
                helper.assertEquals(8, site.tile(-2, 0, 1).fill(ForgeDirection.WEST, stack, true), "Eight units of GT oxygen injected");
                helper.assertEquals("oxygen", rig.input().getNetwork().getFluidName(), "Oxygen at the pump inlet");
                helper.assertTrue(rig.pump().checkStructure(true, rig.pump().getBaseMetaTileEntity()), "Formed oxygen heat pump");
                rig.pump().fixAllIssues();
                rig.pump().getBaseMetaTileEntity().enableWorking();
            } else {
                rig.charge(helper, 310);
            }
            rig.pump().setFluidAmountPerOperation(1000);
            rig.pump().setOperatingMode(HeatPumpMode.TARGET_ENERGY);
            rig.pump().setTargetEnergy(128);
            rig.pump().setTargetHeating(false);
        }).thenWaitUntil(() -> helper.assertEquals(20, rig.inventory().getInteger("pumpTicksLeft"), "New cooling charge"))
            .thenExecute(() -> {
                rig.input().getNetwork().freeze("Isolate the single restart charge");
                rig.supply().injectEnergyUnits(ForgeDirection.NORTH, 128, 1);
            }).thenWaitUntil(() -> helper.assertEquals(19, rig.inventory().getInteger("pumpTicksLeft"), "One paid tick"))
            .thenExecute(() -> {
                var state = rig.inventory();
                var inlet = rig.input().getNetwork();
                helper.assertTrue(state.getLong("pumpMicroKg") > 0, "Measured working charge");
                helper.assertEquals(oxygen ? 8 * 888_888L : 10_000_000L, state.getLong("pumpMicroKg") + inlet.getAmountQ(), "Original mass");
                helper.assertEquals(0L, rig.supply().getStoredEU(), "No stored power at shutdown");
                helper.assertEquals(0L, rig.output().getNetwork().getAmountQ(), "No early discharge");
                var expected = new Properties();
                expected.setProperty("launch", System.getProperty("horizonqa.launchId"));
                expected.setProperty("inlet", inlet.getNetworkId().toString());
                expected.setProperty("massMicroKg", Long.toString(state.getLong("pumpMicroKg")));
                expected.setProperty("energyMicroEu", Long.toString(state.getLong("pumpMicroEu")));
                try (var stream = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW)) {
                    expected.store(stream, "Observed before the first server stopped");
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            }).thenSucceed();
    }

    @GameTest(template = "radiator", timeoutTicks = 800)
    public static void finishPartlyPaidCharge(GameTestHelper helper) throws IOException {
        finishCharge(helper, false);
    }

    @GameTest(template = "radiator", timeoutTicks = 800)
    public static void finishOxygenCharge(GameTestHelper helper) throws IOException {
        finishCharge(helper, true);
    }

    private static void finishCharge(GameTestHelper helper, boolean oxygen) throws IOException {
        var expected = new Properties();
        try (var stream = Files.newInputStream(checkpoint())) {
            expected.load(stream);
        }
        helper.assertTrue(!System.getProperty("horizonqa.launchId").equals(expected.getProperty("launch")), "Independent launch");
        var site = site(helper);
        var rig = HeatPumpTests.readPump(site);
        long held = Long.parseLong(expected.getProperty("massMicroKg"));
        long[] delivered = { 0 };
        long[] output = { 0 };
        boolean[] power = { false };
        helper.onEachTick("supply the restarted pump", () -> {
            if (!power[0]) return;
            long before = rig.supply().getStoredEU();
            rig.supply().injectEnergyUnits(ForgeDirection.NORTH, 128, 1);
            delivered[0] += rig.supply().getStoredEU() - before;
        });
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(rig.input().getNetwork() != null
            && rig.output().getNetwork() != null, "Restored hatches")).thenIdle(30).thenExecute(() -> {
                var inlet = rig.input().getNetwork();
                var state = rig.inventory();
                helper.assertEquals(expected.getProperty("inlet"), inlet.getNetworkId().toString(), "Same saved network");
                helper.assertTrue(inlet.isFrozen(), "No second intake");
                helper.assertEquals(held, state.getLong("pumpMicroKg"), "Restored mass");
                helper.assertEquals(Long.parseLong(expected.getProperty("energyMicroEu")), state.getLong("pumpMicroEu"), "Restored energy");
                helper.assertEquals(19, state.getInteger("pumpTicksLeft"), "No free ticks while the server was down");
                helper.assertEquals(0L, rig.output().getNetwork().getAmountQ(), "No output without power");
                power[0] = true;
            }).thenWaitUntil(() -> helper.assertTrue(rig.output().getNetwork().getAmountQ() > 0, "Paid batch discharged"))
            .thenExecute(() -> {
                power[0] = false;
                output[0] = rig.output().getNetwork().getAmountQ();
            }).thenIdle(40).thenExecute(() -> {
                helper.assertEquals(19 * 128L, delivered[0] - rig.supply().getStoredEU(), "Only outstanding work billed");
                helper.assertEquals(output[0], rig.output().getNetwork().getAmountQ(), "No duplicate discharge");
                helper.assertEquals(oxygen ? "oxygen" : "water", rig.output().getNetwork().getFluidName(), "Working substance survives restart");
                helper.assertEquals(held, output[0] + rig.inventory().getLong("pumpMicroKg"), "Discharge and retained heel");
                helper.assertEquals(oxygen ? 8 * 888_888L : 10_000_000L, held + rig.input().getNetwork().getAmountQ(), "Total mass after restart");
                if (oxygen) {
                    helper.assertTrue(rig.output().getNetwork().getPressurePa() < 110_000, "Restarted outlet is below export pressure");
                    helper.assertTrue(site.tile(2, 0, 1).drain(ForgeDirection.EAST, 1, true) == null, "No free compression on export");
                    helper.recordDiagnostic("Restarted oxygen: batch microkg=" + held + ", output microkg=" + output[0]
                        + ", remaining work EU=" + (delivered[0] - rig.supply().getStoredEU()));
                }
            }).thenSucceed();
    }
}
