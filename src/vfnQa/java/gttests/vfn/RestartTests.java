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
import gregtech.api.metatileentity.implementations.integratedfluid.IFNAmbientTemperature;
import gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidPipe;
import gregtech.common.tileentities.machines.multi.HeatPumpMode;

@GameTestHolder(value = Mods.ModIDs.GREG_TECH, templatePrefix = "vfn")
public class RestartTests {

    private static final int OXYGEN_UNITS = 100;

    private static Path checkpoint() {
        String path = System.getProperty("vfn.qa.restartCheckpoint");
        if (path == null) throw new IllegalStateException("Run misc/vfn_server_qa.py --restart");
        return Paths.get(path);
    }

    private static void writeCheckpoint(Properties expected) {
        expected.setProperty("launch", System.getProperty("horizonqa.launchId"));
        try (var stream = Files.newOutputStream(checkpoint(), StandardOpenOption.CREATE_NEW)) {
            expected.store(stream, "Observed before the first server stopped");
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static Properties readCheckpoint(GameTestHelper helper) throws IOException {
        var expected = new Properties();
        try (var stream = Files.newInputStream(checkpoint())) {
            expected.load(stream);
        }
        helper.assertTrue(!System.getProperty("horizonqa.launchId").equals(expected.getProperty("launch")), "Independent launch");
        return expected;
    }

    private static RemoteMachineSite site(GameTestHelper helper, boolean oxygen) {
        // Outside the cells that Horizon QA prepares for either launch
        var world = (WorldServer) helper.getWorld();
        if (oxygen) {
            // The isolated dedicated restart fixture runs in the only ticking CI dimension
            int id = world.provider.dimensionId;
            float previous = IFNAmbientTemperature.getAmbientTemperature(world);
            helper.afterTest(() -> IFNAmbientTemperature.registerDimensionTemperature(id, previous));
            IFNAmbientTemperature.registerDimensionTemperature(id, 100);
        }
        var site = new RemoteMachineSite(world, 8200, 240, 8200);
        site.reloadForObservation(helper);
        return site;
    }

    @GameTest(template = "radiator", timeoutTicks = 600)
    public static void stopWithPartlyPaidCharge(GameTestHelper helper) {
        prepareCharge(helper, false);
    }

    @GameTest(template = "radiator", timeoutTicks = 3800)
    public static void stopWithOxygenCharge(GameTestHelper helper) {
        prepareCharge(helper, true);
    }

    private static void prepareCharge(GameTestHelper helper, boolean oxygen) {
        var site = site(helper, oxygen);
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = 0; dz <= 2; dz++)
            helper.assertTrue(site.world().isAirBlock(site.x() + dx, site.y() + dy, site.z() + dz), "Fresh restart fixture");
        var rig = HeatPumpTests.placeAt(site.world(), site.x(), site.y(), site.z(), (short) 42);
        rig.pump().getBaseMetaTileEntity().disableWorking();
        var radiator = oxygen ? RadiatorTests.placeAt(site.world(), site.x() - 5, site.y(), site.z()) : null;
        int[] accepted = { 0 };
        boolean[] filling = { false };
        if (oxygen) {
            var injector = site.place(-2, 1, 1, 5773, ForgeDirection.DOWN);
            var pipe = (MTEIntegratedFluidPipe) site.place(-2, 0, 1, 5770, ForgeDirection.WEST).getMetaTileEntity();
            site.place(2, 0, 1, 32032, ForgeDirection.WEST);
            RadiatorTests.connectSharedLoop(site.world(), site.x() - 5, site.y(), site.z());
            pipe.connect(ForgeDirection.WEST);
            pipe.connect(ForgeDirection.EAST);
            pipe.connect(ForgeDirection.UP);
            helper.onEachTick("feed GT oxygen as cooling opens inlet head", () -> {
                if (filling[0] && accepted[0] < OXYGEN_UNITS) {
                    var stack = FluidRegistry.getFluidStack("oxygen", OXYGEN_UNITS - accepted[0]);
                    accepted[0] += injector.fill(ForgeDirection.UP, stack, true);
                }
            });
        }
        site.assignOwner();
        helper.startSequence().thenIdle(20).thenExecute(() -> {
            if (oxygen) {
                var tile = radiator.machine().getBaseMetaTileEntity();
                helper.assertTrue(radiator.machine().checkStructure(true, tile), "Passive precooler formed in the 100 K fixture");
                helper.assertTrue(rig.input().getNetwork() != null && rig.input().getNetwork() == radiator.inlet().getNetwork()
                    && radiator.inlet().getNetwork() == radiator.outlet().getNetwork(), "Radiator cools the injector supply in place");
                var inlet = rig.input().getNetwork();
                var tileIn = site.tile(-2, 1, 1);
                var injectionPort = (gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidInjectorHatch) tileIn.getMetaTileEntity();
                helper.assertTrue(injectionPort.getNetwork() == inlet, "Injector pipe must connect to the shared supply");
                helper.assertTrue(tileIn.fill(ForgeDirection.UP, FluidRegistry.getFluidStack("oxygen", OXYGEN_UNITS), false) > 0,
                    "Oxygen import: timer=" + tileIn.getTimer() + ", input=" + injectionPort.isLiquidInput(ForgeDirection.UP)
                        + ", network=" + inlet.getNetworkStatus() + ", capacity=" + inlet.getMaxCapacity()
                        + ", ambient=" + gregtech.api.metatileentity.implementations.integratedfluid.IFNAmbientTemperature.getAmbientTemperature(site.world()));
                radiator.machine().fixAllIssues();
                radiator.machine().setMachineMode(0);
                tile.enableWorking();
                filling[0] = true;
            }
        }).thenWaitUntil(() -> {
            if (oxygen) helper.assertEquals(OXYGEN_UNITS, accepted[0], "Cold injector supply at " + rig.input().getNetwork().getTemperature()
                + " K, " + rig.input().getNetwork().getPressure() + " bar");
        }).thenExecute(() -> {
            if (oxygen) {
                helper.assertEquals(accepted[0] * 888_888L, rig.input().getNetwork().getAmountQ(), "All inlet mass came from the injector");
                helper.recordDiagnostic("Oxygen supply: GT units=" + accepted[0] + ", K=" + rig.input().getNetwork().getTemperature()
                    + ", bar=" + rig.input().getNetwork().getPressure());
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
                if (oxygen) helper.assertTrue(state.getLong("pumpMicroKg") >= 2 * 888_888L, "At least two whole portions before shutdown");
                helper.assertEquals(oxygen ? OXYGEN_UNITS * 888_888L : 10_000_000L, state.getLong("pumpMicroKg") + inlet.getAmountQ(), "Original mass");
                helper.assertEquals(0L, rig.supply().getStoredEU(), "No stored power at shutdown");
                helper.assertEquals(0L, rig.output().getNetwork().getAmountQ(), "No early discharge");
                var expected = new Properties();
                expected.setProperty("inlet", inlet.getNetworkId().toString());
                expected.setProperty("massMicroKg", Long.toString(state.getLong("pumpMicroKg")));
                expected.setProperty("energyMicroEu", Long.toString(state.getLong("pumpMicroEu")));
                writeCheckpoint(expected);
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
        var expected = readCheckpoint(helper);
        var site = site(helper, oxygen);
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
                helper.assertEquals(oxygen ? OXYGEN_UNITS * 888_888L : 10_000_000L, held + rig.input().getNetwork().getAmountQ(), "Total mass after restart");
                if (oxygen) {
                    helper.assertEquals(0L, output[0] % 888_888L, "Only whole oxygen portions leave the machine");
                    helper.assertTrue(rig.output().getNetwork().getPressurePa() < 110_000, "Restarted outlet is below export pressure");
                    helper.assertTrue(site.tile(2, 0, 1).drain(ForgeDirection.EAST, 1, true) == null, "No free compression on export");
                    helper.recordDiagnostic("Restarted oxygen: batch microkg=" + held + ", output microkg=" + output[0]
                        + ", remaining work EU=" + (delivered[0] - rig.supply().getStoredEU()));
                }
            }).thenSucceed();
    }

    @GameTest(template = "radiator", timeoutTicks = 600)
    public static void stopWithRadiatorCharge(GameTestHelper h) {
        var site = site(h, false);
        var rig = RadiatorTests.placeAt(site.world(), site.x(), site.y(), site.z());
        site.assignOwner();
        h.startSequence().thenIdle(20).thenExecute(() -> {
            rig.machine().setConstantOperationTicks(160);
            rig.charge(h);
        }).thenWaitUntil(() -> {
            var ticks = rig.inventory().getInteger("loopTicks");
            h.assertTrue(ticks > 0 && ticks < 155 && rig.heldMass() > 0, "Partly cooled charge");
        }).thenExecute(() -> {
            rig.machine().stopMachine(gregtech.api.util.shutdown.ShutDownReasonRegistry.NONE);
            rig.inlet().getNetwork().freeze("Isolate the timed restart batch");
            var state = rig.inventory();
            h.assertEquals(0L, rig.outlet().getNetwork().getAmountQ(), "Nothing discharged before stop");
            var expected = new Properties();
            expected.setProperty("inlet", rig.inlet().getNetwork().getNetworkId().toString());
            expected.setProperty("massMicroKg", Long.toString(rig.heldMass()));
            expected.setProperty("energyMicroEu", Long.toString(state.getLong("loopMicroEu")));
            expected.setProperty("ticks", Integer.toString(state.getInteger("loopTicks")));
            writeCheckpoint(expected);
        }).thenSucceed();
    }

    @GameTest(template = "radiator", timeoutTicks = 650)
    public static void finishRadiatorCharge(GameTestHelper h) throws IOException {
        var expected = readCheckpoint(h);
        var site = site(h, false);
        var rig = RadiatorTests.readRig(site);
        int ticks = Integer.parseInt(expected.getProperty("ticks"));
        long held = Long.parseLong(expected.getProperty("massMicroKg")), heat = Long.parseLong(expected.getProperty("energyMicroEu"));
        long[] output = new long[1];
        h.startSequence().thenWaitUntil(() -> h.assertTrue(rig.inlet().getNetwork() != null && rig.outlet().getNetwork() != null,
            "Radiator hatches restored")).thenIdle(30).thenExecute(() -> {
                var saved = rig.inventory();
                h.assertEquals(expected.getProperty("inlet"), rig.inlet().getNetwork().getNetworkId().toString(), "Original supply circuit");
                h.assertTrue(rig.inlet().getNetwork().isFrozen(), "Supply remains isolated");
                h.assertEquals(ticks, saved.getInteger("loopTicks"), "No offline cooling ticks");
                h.assertEquals(heat, saved.getLong("loopMicroEu"), "No offline heat transfer");
                h.assertEquals(held, rig.heldMass(), "Restored batch");
                h.assertEquals(0L, rig.outlet().getNetwork().getAmountQ(), "No premature release");
                rig.machine().getBaseMetaTileEntity().enableWorking();
            }).thenWaitUntil(() -> {
                var state = rig.inventory();
                h.assertTrue(state.getInteger("loopTicks") > 0 && state.getInteger("loopTicks") < ticks
                    && state.getLong("loopMicroEu") < heat, "Cool the original charge on resumed ticks");
                h.assertEquals(0L, rig.outlet().getNetwork().getAmountQ(), "Not ready yet");
            }).thenWaitUntil(() -> h.assertTrue(rig.outlet().getNetwork().getAmountQ() > 0, "Timed batch discharged"))
            .thenExecute(() -> output[0] = rig.outlet().getNetwork().getAmountQ()).thenIdle(50).thenExecute(() -> {
                h.assertEquals(output[0], rig.outlet().getNetwork().getAmountQ(), "No duplicate discharge");
                h.assertEquals(held, output[0] + rig.heldMass(), "Outlet and retained heel balance");
                h.assertEquals(10000000L, held + rig.inlet().getNetwork().getAmountQ(), "Sealed installation kept all water");
                h.recordDiagnostic("Radiator resumed " + ticks + " ticks with " + held + " microkg and discharged " + output[0]);
            }).thenSucceed();
    }
}
