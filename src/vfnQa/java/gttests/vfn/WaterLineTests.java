package gttests.vfn;

import java.math.BigDecimal;
import java.util.ArrayList;

import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;

import gregtech.api.covers.CoverRegistry;
import gregtech.api.enums.ItemList;
import gregtech.api.enums.Mods;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidPipe;
import gregtech.api.metatileentity.implementations.integratedfluid.thermodynamics.ClosedFluidChamber;
import gregtech.api.metatileentity.implementations.integratedfluid.thermodynamics.PureFluid;
import gregtech.common.covers.CoverPump;
import gregtech.common.covers.modes.TransferMode;
import gtPlusPlus.core.block.ModBlocks;
import gtPlusPlus.core.tileentities.general.TileEntityInfiniteFluid;

@GameTestHolder(value = Mods.ModIDs.GREG_TECH, templatePrefix = "vfn")
public final class WaterLineTests {

    record Line(HeatPumpTests.Rig rig, IGregTechTileEntity sourceTank, IGregTechTileEntity outputTank,
        MTEIntegratedFluidPipe input, MTEIntegratedFluidPipe output, IGregTechTileEntity extractor) {
        void start(GameTestHelper helper) {
            start(helper, 310, 8);
        }

        void start(GameTestHelper helper, float targetK, int liters) {
            rig.pump().setTargetTemperatureDelta(targetK);
            rig.pump().setFluidAmountPerOperation(liters);
            helper.assertTrue(rig.pump().checkStructure(true, rig.pump().getBaseMetaTileEntity()), "Heat pump formed without injected hatch lists");
            rig.pump().fixAllIssues();
            rig.pump().getBaseMetaTileEntity().enableWorking();
        }

        void measure(GameTestHelper helper, String event) {
            var outlet = output.getNetwork();
            var stored = outputTank.drain(ForgeDirection.UNKNOWN, Integer.MAX_VALUE, false);
            helper.recordDiagnostic(event + ": outlet kg=" + outlet.getAmountQ() / 1e6 + ", K=" + outlet.getTemperature()
                + ", bar=" + outlet.getPressure() + ", destination GT liters=" + (stored == null ? 0 : stored.amount));
        }
    }

    static Line place(GameTestHelper helper) {
        return place(helper, (short) 44, ItemList.Electric_Pump_LV);
    }

    private static Line place(GameTestHelper helper, short hatchId, ItemList feedPump) {
        var rig = HeatPumpTests.place(helper, hatchId);
        rig.pump().getBaseMetaTileEntity().disableWorking();
        var w = helper.getWorld();
        var p = helper.absolute("controller");
        int x = p.x(), y = p.y(), z = p.z() + 1;
        w.setBlock(x - 5, y, z, ModBlocks.blockInfiniteFLuidTank, 0, 3);
        ((TileEntityInfiniteFluid) w.getTileEntity(x - 5, y, z)).setFluid(new FluidStack(FluidRegistry.WATER, 1000));
        var source = InjectorTests.placeAt(w, x - 4, y, z, (short) 130, ForgeDirection.NORTH);
        InjectorTests.placeAt(w, x - 3, y, z, (short) 5773, ForgeDirection.EAST);
        var input = (MTEIntegratedFluidPipe) InjectorTests.placeAt(w, x - 2, y, z, (short) 5770, ForgeDirection.EAST).getMetaTileEntity();
        var output = (MTEIntegratedFluidPipe) InjectorTests.placeAt(w, x + 2, y, z, (short) 5770, ForgeDirection.WEST).getMetaTileEntity();
        var extractor = InjectorTests.placeAt(w, x + 3, y, z, (short) 32032, ForgeDirection.WEST);
        var destination = InjectorTests.placeAt(w, x + 4, y, z, (short) 130, ForgeDirection.NORTH);
        for (var pipe : new MTEIntegratedFluidPipe[] { input, output }) {
            pipe.connect(ForgeDirection.WEST);
            pipe.connect(ForgeDirection.EAST);
        }
        attachPump(source, ForgeDirection.WEST, TransferMode.IMPORT, feedPump);
        attachPump(extractor, ForgeDirection.EAST, TransferMode.EXPORT);
        return new Line(rig, source, destination, input, output, extractor);
    }

    static void attachPump(IGregTechTileEntity tile, ForgeDirection side, TransferMode mode) {
        attachPump(tile, side, mode, ItemList.Electric_Pump_LV);
    }

    private static void attachPump(IGregTechTileEntity tile, ForgeDirection side, TransferMode mode, ItemList pump) {
        var cover = (CoverPump) CoverRegistry.buildCover(pump.get(1), side, tile);
        cover.setIOMode(mode);
        tile.attachCover(cover);
    }

    @GameTest(template = "water_line", timeoutTicks = 1300)
    public static void primedWaterLineAt310K(GameTestHelper helper) {
        exercisePrimedLine(helper, 310);
    }

    @GameTest(template = "water_line", timeoutTicks = 1300)
    public static void primedWaterLineAt400K(GameTestHelper helper) {
        exercisePrimedLine(helper, 400);
    }

    record Commissioning(Line line, int x, int y, int z) {
        void requireFilled(GameTestHelper helper) {
            var inlet = line.input().getNetwork();
            helper.assertTrue(inlet != null && inlet == line.output().getNetwork() && inlet == line.rig().input().getNetwork()
                && inlet == line.rig().output().getNetwork() && inlet.getMaxCapacity() > 20_000
                && inlet.getAmountQ() * 1.043e-6 > inlet.getMaxCapacity() - 1.1, "Single source filled the connected circuits");
        }

        void removeBridge(GameTestHelper helper) {
            var world = helper.getWorld();
            for (int dx = -2; dx <= 2; dx++) world.setBlockToAir(x + dx, y, z + 3);
            world.setBlockToAir(x - 2, y, z + 2);
            world.setBlockToAir(x + 2, y, z + 2);
        }

        void requireSeparated(GameTestHelper helper) {
            requireSeparated(helper, 10100);
        }

        void requireSeparated(GameTestHelper helper, int inletLiters) {
            var a = line.input().getNetwork();
            var b = line.output().getNetwork();
            helper.assertTrue(a != null && b != null && a != b && a == line.rig().input().getNetwork()
                && b == line.rig().output().getNetwork() && !a.isPending() && !b.isPending()
                && a.getMaxCapacity() == inletLiters && b.getMaxCapacity() == 10100, "Original circuits restored without the startup bridge");
        }
    }

    static Commissioning commissioning(GameTestHelper helper) {
        var line = place(helper, (short) 48, ItemList.Electric_Pump_EV);
        var p = helper.absolute("controller");
        var w = helper.getWorld();
        var pipes = new ArrayList<MTEIntegratedFluidPipe>();
        for (int dz = 2; dz <= 3; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                if (dz == 2 && Math.abs(dx) != 2) continue;
                pipes.add((MTEIntegratedFluidPipe) InjectorTests.placeAt(w, p.x() + dx, p.y(), p.z() + dz,
                    (short) 5770, ForgeDirection.NORTH).getMetaTileEntity());
            }
        }
        for (var pipe : pipes) for (var side : ForgeDirection.VALID_DIRECTIONS) pipe.connect(side);
        line.input().connect(ForgeDirection.SOUTH);
        line.output().connect(ForgeDirection.SOUTH);
        attachPump(line.sourceTank(), ForgeDirection.EAST, TransferMode.EXPORT, ItemList.Electric_Pump_EV);
        return new Commissioning(line, p.x(), p.y(), p.z());
    }

    record FeedStage(Commissioning setup, HeatPumpTests.Rig auxiliary) {
        boolean settledExport() {
            var line = setup.line();
            return storedWater() > 0 && line.extractor().drain(ForgeDirection.EAST, 1, false) == null;
        }

        int storedWater() {
            var tank = setup.line().outputTank().drain(ForgeDirection.UNKNOWN, Integer.MAX_VALUE, false);
            return tank != null && tank.getFluid() == FluidRegistry.WATER ? tank.amount : 0;
        }

        void startAuxiliary(GameTestHelper helper) {
            var pump = auxiliary.pump();
            helper.assertTrue(pump.checkStructure(true, pump.getBaseMetaTileEntity()), "Auxiliary heat pump formed");
            pump.setTargetTemperatureDelta(435);
            pump.setFluidAmountPerOperation(800);
            pump.fixAllIssues();
            pump.getBaseMetaTileEntity().enableWorking();
        }

        long delivered() {
            var line = setup.line();
            return line.output().getNetwork().getAmountQ() + 1_000_000L * storedWater();
        }

        long installedMass() {
            var line = setup.line();
            return line.input().getNetwork().getAmountQ() + delivered() + line.rig().inventory().getLong("pumpMicroKg")
                + auxiliary.inventory().getLong("pumpMicroKg");
        }
    }

    static FeedStage placeFeedStage(GameTestHelper helper) {
        var setup = commissioning(helper);
        var p = helper.absolute("controller");
        var w = helper.getWorld();
        var auxiliary = HeatPumpTests.placeAt(w, p.x(), p.y(), p.z() - 5, (short) 48);
        auxiliary.pump().getBaseMetaTileEntity().disableWorking();
        var contacts = new ArrayList<MTEIntegratedFluidPipe>();
        for (int dz = -4; dz <= 0; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                if (dx != -2 && !(dz == -2 || dx == 2 && dz < -2)) continue;
                contacts.add((MTEIntegratedFluidPipe) InjectorTests.placeAt(w, p.x() + dx, p.y(), p.z() + dz,
                    (short) 5770, ForgeDirection.NORTH).getMetaTileEntity());
            }
        }
        for (var pipe : contacts) for (var face : ForgeDirection.VALID_DIRECTIONS) pipe.connect(face);
        setup.line().input().connect(ForgeDirection.NORTH);
        return new FeedStage(setup, auxiliary);
    }

    static long feedUV(IGregTechTileEntity hatch) {
        return hatch.getEUCapacity() - hatch.getStoredEU() < 524288 ? 0 : 524288L * hatch.injectEnergyUnits(ForgeDirection.NORTH, 524288, 1);
    }

    @GameTest(template = "water_line", timeoutTicks = 2200)
    public static void paidFeedPulseRestartsMainStage(GameTestHelper helper) {
        var stages = placeFeedStage(helper);
        var line = stages.setup().line();
        var aux = stages.auxiliary();
        var main = line.rig();
        boolean[] mainPower = { false }, auxPower = { false };
        long[] mainEU = { 0 }, auxEU = { 0 }, first = new long[5];
        float[] inletPeak = { 0 };
        int[] monitored = { 0 };
        helper.onEachTick("meter both independent UV hatches", () -> {
            if (mainPower[0]) mainEU[0] += feedUV(main.supply());
            if (auxPower[0]) {
                auxEU[0] += feedUV(aux.supply());
                inletPeak[0] = Math.max(inletPeak[0], line.input().getNetwork().getPressure());
                if (++monitored[0] % 200 == 0) helper.recordDiagnostic("Feed tick " + monitored[0]
                    + ": aux held kg=" + aux.inventory().getLong("pumpMicroKg") / 1e6 + ", aux K=" + aux.pump().getCurrentOutputTemperature()
                    + ", aux work=" + (auxEU[0] - aux.supply().getStoredEU()) + ", aux state=" + aux.pump().getCheckRecipeResult().getDisplayString()
                    + ", inlet bar=" + line.input().getNetwork().getPressure() + ", peak=" + inletPeak[0] + ", main held kg=" + main.inventory().getLong("pumpMicroKg") / 1e6);
            }
        });
        helper.startSequence().thenWaitUntil(() -> stages.setup().requireFilled(helper))
            .thenExecute(() -> stages.setup().removeBridge(helper))
            .thenWaitUntil(() -> stages.setup().requireSeparated(helper, 31200))
            .thenExecute(() -> {
                helper.assertTrue(aux.input().getNetwork() == line.input().getNetwork() && aux.output().getNetwork() == line.input().getNetwork(),
                    "The auxiliary takes and returns fluid only on the feed line");
                line.start(helper, 400, 250);
                mainPower[0] = true;
            })
            .thenWaitUntil(() -> helper.assertTrue(stages.settledExport()
                && main.pump().getCheckRecipeResult().equals(gregtech.api.recipe.check.SimpleCheckRecipeResult.ofFailure("vfn_inlet_head")),
                "First discharge completed and the main chamber cannot refill"))
            .thenExecute(() -> {
                first[0] = stages.delivered();
                first[1] = main.inventory().getLong("pumpMicroKg");
                first[2] = stages.installedMass();
                first[3] = stages.storedWater();
                first[4] = mainEU[0] - main.supply().getStoredEU();
                stages.startAuxiliary(helper);
                auxPower[0] = true;
            })
            .thenWaitUntil(() -> helper.assertTrue(main.inventory().getLong("pumpMicroKg") > first[1]
                && main.inventory().getBoolean("pumpBatchActive"), "Main machine took a second charge, not just its heel"))
            .thenWaitUntil(() -> {
                helper.assertTrue(stages.settledExport() && stages.storedWater() > first[3] && stages.delivered() - first[0] > first[1],
                    "Second export exceeds the original retained mass");
            }).thenExecute(() -> {
                long mainWork = mainEU[0] - main.supply().getStoredEU() - first[4];
                long feedWork = auxEU[0] - aux.supply().getStoredEU();
                long injected = stages.installedMass() - first[2];
                helper.assertTrue(injected > 0 && injected % 1_000_000 == 0, "Fresh whole water units entered via the only injector");
                helper.assertTrue(mainWork > 0 && feedWork > 0, "Both the feed pulse and the second batch spent real EU");
                helper.recordDiagnostic("Second stroke: fresh injector kg=" + injected / 1e6 + ", feed peak bar=" + inletPeak[0]
                    + ", feed EU=" + feedWork + ", main EU since stall=" + mainWork + ", new delivered kg="
                    + (stages.delivered() - first[0]) / 1e6 + ", first GT L=" + first[3]);
                line.measure(helper, "Second delivery");
            }).thenIdle(200).thenExecute(() -> line.measure(helper, "After 200 further ticks")).thenSucceed();
    }

    private static void exercisePrimedLine(GameTestHelper helper, int targetK) {
        var setup = commissioning(helper);
        var line = setup.line();
        var rig = line.rig();
        boolean[] run = { false };
        long[] primed = { 0 }, supply = { 0 }, lastDeliveredMass = { 0 };
        int[] discharges = { 0 };
        float[] maximumPressure = { 0 };
        helper.onEachTick("measure the UV working stage", () -> {
            if (!run[0]) return;
            // GT accepts a whole packet but caps the visible inventory at the hatch capacity
            if (rig.supply().getEUCapacity() - rig.supply().getStoredEU() >= 524288) {
                supply[0] += 524288L * rig.supply().injectEnergyUnits(ForgeDirection.NORTH, 524288, 1);
            }
            var received = line.outputTank().drain(ForgeDirection.UNKNOWN, Integer.MAX_VALUE, false);
            long deliveredMass = line.output().getNetwork().getAmountQ() + (received == null ? 0 : 1_000_000L * received.amount);
            if (deliveredMass > lastDeliveredMass[0]) discharges[0]++;
            lastDeliveredMass[0] = deliveredMass;
            maximumPressure[0] = Math.max(maximumPressure[0], line.output().getNetwork().getPressure());
        });
        helper.startSequence().thenWaitUntil(() -> setup.requireFilled(helper))
            .thenExecute(() -> setup.removeBridge(helper)).thenWaitUntil(() -> setup.requireSeparated(helper))
            .thenExecute(() -> {
                primed[0] = line.output().getNetwork().getAmountQ();
                lastDeliveredMass[0] = primed[0];
                helper.assertTrue(line.outputTank().drain(ForgeDirection.UNKNOWN, 1, false) == null, "Priming alone delivered no GT water");
                line.measure(helper, "After removing the priming bridge");
                line.start(helper, targetK, 250);
                run[0] = true;
            }).thenIdle(350).thenExecute(() -> {
                var outlet = line.output().getNetwork();
                var tank = line.outputTank().drain(ForgeDirection.UNKNOWN, Integer.MAX_VALUE, false);
                long units = tank == null ? 0 : tank.amount;
                long work = supply[0] - rig.supply().getStoredEU();
                line.measure(helper, "After 350 powered ticks at " + targetK + " K");
                var saved = rig.inventory();
                var held = ClosedFluidChamber.of(PureFluid.water(),
                    BigDecimal.valueOf(saved.getLong("pumpMicroKg"), 6), 1000,
                    BigDecimal.valueOf(saved.getLong("pumpMicroEu"), 6));
                helper.recordDiagnostic("Discharge ticks=" + discharges[0] + ", held bar=" + held.pressurePa() / 100_000
                    + ", waiting on output=" + saved.getBoolean("pumpOutputReady"));
                helper.recordDiagnostic("Peak outlet bar=" + maximumPressure[0] + ", spent EU=" + work + ", batch K="
                    + rig.pump().getCurrentOutputTemperature() + ", held kg=" + rig.inventory().getLong("pumpMicroKg") / 1e6
                    + ", inlet bar=" + line.input().getNetwork().getPressure() + ", net received kg="
                    + ((outlet.getAmountQ() - primed[0]) / 1e6 + units));
                helper.assertTrue(work > 0, "The machine really conditioned a batch");
                var expected = gregtech.api.recipe.check.SimpleCheckRecipeResult.ofFailure(
                    targetK == 310 ? "vfn_outlet_head" : "vfn_inlet_head");
                helper.assertEquals(expected, rig.pump().getCheckRecipeResult(), "Terminal identifies the actual stalled port");
                if (targetK == 310) {
                    helper.assertTrue(maximumPressure[0] < 1.1 && units == 0, "Cool batch cannot export without sufficient pressure");
                } else {
                    helper.assertTrue(tank != null && tank.getFluid() == FluidRegistry.WATER && units > 0, "Paid thermal head supplied water to GT");
                    helper.assertTrue(outlet.getAmountQ() >= primed[0], "Final GT water was not obtained by depleting the priming inventory");
                }
            }).thenSucceed();
    }

    @GameTest(template = "water_line", timeoutTicks = 1400)
    public static void creativeWaterReachesHeatedOutlet(GameTestHelper helper) {
        var line = place(helper);
        var rig = line.rig();
        boolean[] powered = { false };
        long[] supplied = { 0 };
        helper.onEachTick("supply the real EV hatch", () -> {
            if (!powered[0]) return;
            long before = rig.supply().getStoredEU();
            rig.supply().injectEnergyUnits(ForgeDirection.NORTH, 2048, 2);
            supplied[0] += rig.supply().getStoredEU() - before;
        });
        helper.startSequence().thenWaitUntil(() -> {
            var tank = line.sourceTank().drain(ForgeDirection.UNKNOWN, 1, false);
            helper.assertTrue(tank != null && tank.getFluid() == FluidRegistry.WATER, "Creative water reached the first Super Tank");
            helper.assertTrue(line.input().getNetwork() != null && line.input().getNetwork() == rig.input().getNetwork()
                && line.output().getNetwork() != null && line.output().getNetwork() == rig.output().getNetwork(), "Pipe circuits joined");
        }).thenExecute(() -> {
            helper.assertEquals(0L, line.input().getNetwork().getAmountQ() + line.output().getNetwork().getAmountQ(), "Both VFN circuits start empty");
            attachPump(line.sourceTank(), ForgeDirection.EAST, TransferMode.EXPORT);
        }).thenWaitUntil(() -> helper.assertTrue(line.input().getNetwork().getAmountQ() > 0, "Injector received water through its cover-fed GT port"))
            .thenExecute(() -> {
                line.start(helper);
                powered[0] = true;
            }).thenWaitUntil(() -> helper.assertTrue(line.output().getNetwork().getAmountQ() > 0,
                "Processed water reached output VFN storage: inlet kgQ=" + line.input().getNetwork().getAmountQ()
                    + ", held kgQ=" + rig.inventory().getLong("pumpMicroKg") + ", batch K=" + rig.pump().getCurrentOutputTemperature()))
            .thenExecute(() -> {
                helper.assertTrue(supplied[0] > rig.supply().getStoredEU(), "A real energy hatch paid for the first hot batch");
                helper.assertTrue(Math.abs(rig.pump().getCurrentOutputTemperature() - 310) < 0.001, "Output came from a 310 K batch");
                line.measure(helper, "First discharge");
            }).thenIdle(200).thenExecute(() -> line.measure(helper, "After 200 more ticks")).thenSucceed();
    }
}
