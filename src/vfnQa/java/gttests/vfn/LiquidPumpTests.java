package gttests.vfn;

import java.util.function.LongSupplier;

import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;

import gregtech.api.enums.Mods;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidLiquidPump;
import gregtech.api.metatileentity.implementations.integratedfluid.state.IFNCanonicalState;
import gregtech.api.metatileentity.implementations.integratedfluid.thermodynamics.ClosedFluidChamber;
import gregtech.api.metatileentity.implementations.integratedfluid.thermodynamics.PureFluid;

@GameTestHolder(value = Mods.ModIDs.GREG_TECH, templatePrefix = "vfn")
public class LiquidPumpTests {

    static HeatPumpTests.SteelShell place(GameTestHelper h) {
        var p = h.absolute("controller");
        return HeatPumpTests.placeSteelShell(h.getWorld(), p.x(), p.y(), p.z(), (short) 5779, (short) 42);
    }

    private static LongSupplier meterMvSupply(GameTestHelper h, HeatPumpTests.SteelShell rig) {
        long[] delivered = { 0 };
        h.onEachTick("supply metered MV power", () -> {
            if (rig.supply().getEUCapacity() - rig.supply().getStoredEU() >= 128)
                delivered[0] += 128 * rig.supply().injectEnergyUnits(ForgeDirection.NORTH, 128, 1);
        });
        return () -> delivered[0] - rig.supply().getStoredEU();
    }

    @GameTest(template = "radiator", timeoutTicks = 300)
    public static void gasIntakeCompressesInjectorHelium(GameTestHelper h) {
        var p = h.absolute("controller");
        var rig = HeatPumpTests.placeSteelShell(h.getWorld(), p.x(), p.y(), p.z(),
            MTEIntegratedFluidLiquidPump.HIGH_PRESSURE_ID, (short) gregtech.api.enums.MetaTileEntityIDs.ENERGY_HATCH_LuV.ID);
        var pump = (MTEIntegratedFluidLiquidPump) rig.controller().getMetaTileEntity();
        var left = InjectorTests.placeAt(h.getWorld(), p.x() - 2, p.y(), p.z() + 1, (short) 5773, ForgeDirection.EAST);
        var right = InjectorTests.placeAt(h.getWorld(), p.x() + 2, p.y(), p.z() + 1, (short) 5773, ForgeDirection.WEST);
        var suction = new AmbientTests.AmbientMeter(h, rig.input()::getNetwork);
        var delivery = new AmbientTests.AmbientMeter(h, rig.output()::getNetwork);
        LongSupplier energy = () -> suction.withoutAmbient(rig.input().getNetwork()) + delivery.withoutAmbient(rig.output().getNetwork());
        long[] initial = new long[2], supplied = { 0 };
        rig.controller().disableWorking();
        h.onEachTick("charge the idle pressure pump", () -> {
            if (supplied[0] < 262144) supplied[0] += 8192 * rig.supply().injectEnergyUnits(ForgeDirection.NORTH, 8192, 1);
        });
        h.startSequence().thenWaitUntil(() -> h.assertEquals(262144L, rig.supply().getStoredEU(), "Metered work available"))
            .thenExecute(() -> {
                h.assertTrue(pump.checkStructure(true, rig.controller()), "EV pump formed");
                pump.fixAllIssues();
                var gas = net.minecraftforge.fluids.FluidRegistry.getFluidStack("helium", 32);
                int units = left.fill(ForgeDirection.WEST, gas.copy(), true);
                h.assertTrue(units > 1, "Several GT portions on suction");
                h.assertEquals(units, right.fill(ForgeDirection.EAST, gas.copy(), true), "Matched initial gas charges");
                initial[0] = rig.input().getNetwork().getAmountQ();
                initial[1] = energy.getAsLong();
                pump.setGasIntake(true);
                pump.setMode(2);
                pump.setInterval(16);
                pump.setTarget(3200);
                rig.controller().enableWorking();
            }).thenWaitUntil(() -> h.assertTrue(rig.output().getNetwork().getAmountQ() > initial[0], "Whole gas portion delivered"))
            .thenExecute(() -> {
                var inlet = rig.input().getNetwork();
                var outlet = rig.output().getNetwork();
                long moved = outlet.getAmountQ() - initial[0], paid = supplied[0] - rig.supply().getStoredEU();
                h.assertEquals(0L, moved % 111111L, "Only whole helium portions");
                h.assertEquals(initial[0] - moved, inlet.getAmountQ(), "No lost gas");
                h.assertTrue(outlet.getPressurePa() > 90000 && outlet.getPressurePa() > inlet.getPressurePa(),
                    "Pump delivered above the injector cutoff");
                h.assertTrue(paid > 0, "Shaft work paid");
                h.assertEquals(initial[1] + paid * 1000000L, energy.getAsLong(), "All pump work accounted for");
                h.recordDiagnostic("Helium: " + moved + " microkg, " + paid + " EU, " + inlet.getPressure()
                    + " -> " + outlet.getPressure() + " bar");
            }).thenSucceed();
    }

    @GameTest(template = "radiator", timeoutTicks = 300)
    public static void injectorHeliumBelowStrokeVolumeKeepsMassAndPower(GameTestHelper h) {
        var rig = place(h);
        var p = h.absolute("controller");
        var injector = InjectorTests.placeAt(h.getWorld(), p.x() - 2, p.y(), p.z() + 1, (short) 5773, ForgeDirection.EAST);
        var pump = (MTEIntegratedFluidLiquidPump) rig.controller().getMetaTileEntity();
        int[] units = { 0 };
        h.startSequence().thenIdle(25).thenExecute(() -> {
            h.assertTrue(pump.checkStructure(true, rig.controller()), "MV pressure pump formed");
            pump.fixAllIssues();
            pump.setMode(2);
            pump.setTarget(320);
            units[0] = injector.fill(ForgeDirection.WEST, net.minecraftforge.fluids.FluidRegistry.getFluidStack("helium", 32), true);
            h.assertTrue(units[0] > 0, "Ordinary GT helium admitted through injector");
            h.assertEquals(1L, rig.supply().injectEnergyUnits(ForgeDirection.NORTH, 128, 1), "Energy available");
            rig.controller().enableWorking();
        }).thenWaitUntil(() -> h.assertEquals("No whole portion within pressure and power limits", pump.status(),
            "Controller attempted a stroke")).thenIdle(40).thenExecute(() -> {
                var inlet = rig.input().getNetwork();
                h.assertTrue(inlet.intakeStrokeTooSmall(320, 1), "A whole gas portion exceeds the maximum stroke");
                h.assertEquals(units[0] * 111111L, inlet.getAmountQ(), "Helium stayed in the suction line");
                h.assertEquals(0L, rig.output().getNetwork().getAmountQ(), "Empty delivery line");
                h.assertEquals(128L, rig.supply().getStoredEU(), "Refused strokes spent no EU");
                h.recordDiagnostic(units[0] + " helium units at " + inlet.getPressure() + " bar, max stroke 320 L");
            }).thenSucceed();
    }

    @GameTest(template = "radiator", timeoutTicks = 300)
    public static void evPumpOvercomesFourBarHead(GameTestHelper h) {
        pumpAgainstHead(h, true);
    }

    @GameTest(template = "radiator", timeoutTicks = 300)
    public static void mvPumpHoldsAtFourBarHead(GameTestHelper h) {
        pumpAgainstHead(h, false);
    }

    private static void pumpAgainstHead(GameTestHelper h, boolean ev) {
        var pos = h.absolute("controller");
        var rig = HeatPumpTests.placeSteelShell(h.getWorld(), pos.x(), pos.y(), pos.z(),
            ev ? MTEIntegratedFluidLiquidPump.HIGH_PRESSURE_ID : (short) 5779, (short) (ev ? 44 : 42));
        var pump = (MTEIntegratedFluidLiquidPump) rig.controller().getMetaTileEntity();
        long inlet = 10_550_000_000L, outlet = 14_850_000_000L;
        int voltage = ev ? 1024 : 128;
        long[] supplied = { 0 };
        h.onEachTick("charge the pump energy hatch", () -> {
            if (supplied[0] < 1024) supplied[0] += voltage * rig.supply().injectEnergyUnits(ForgeDirection.NORTH, voltage, 1);
        });
        var suction = new AmbientTests.AmbientMeter(h, rig.input()::getNetwork);
        var delivery = new AmbientTests.AmbientMeter(h, rig.output()::getNetwork);
        LongSupplier fluidEnergy = () -> suction.withoutAmbient(rig.input().getNetwork())
            + delivery.withoutAmbient(rig.output().getNetwork());
        long[] initial = new long[1];
        h.startSequence().thenWaitUntil(() -> h.assertEquals(1024L, rig.supply().getStoredEU(),
            "Equal initial work budget in both energy hatches")).thenExecute(() -> {
            h.assertTrue(pump.checkStructure(true, rig.controller()), "Pump formed with its own tier's energy hatch");
            pump.fixAllIssues();
            pump.setMode(2);
            pump.setTarget(2);
            pump.setInterval(100);
            rig.input().getNetwork().replaceCanonicalState(IFNCanonicalState.fromRaw("water", inlet, -200000 * inlet));
            rig.output().getNetwork().replaceCanonicalState(IFNCanonicalState.fromRaw("water", outlet, -200000 * outlet));
            double head = rig.output().getNetwork().getPressurePa() - rig.input().getNetwork().getPressurePa();
            h.assertTrue(head > 400000 && rig.output().getNetwork().getPressurePa() < 1000000, "Four bar lift within ordinary hatch rating: " + head);
            initial[0] = fluidEnergy.getAsLong();
            rig.controller().enableWorking();
        }).thenIdle(120).thenExecute(() -> {
            long moved = rig.output().getNetwork().getAmountQ() - outlet;
            long paid = 1024 - rig.supply().getStoredEU();
            h.assertEquals(ev ? 1_000_000L : 0L, moved, "Only the EV pump can deliver a liquid portion");
            h.assertEquals(inlet - moved, rig.input().getNetwork().getAmountQ(), "Suction matches delivery");
            h.assertTrue(ev ? paid > 0 : paid == 0, "Only a successful stroke consumes power");
            h.assertEquals(initial[0] + paid * 1_000_000L, fluidEnergy.getAsLong(), "Hatch work reaches the fluid");
            h.recordDiagnostic("Moved " + moved + " microkg for " + paid + " EU against "
                + (rig.output().getNetwork().getPressurePa() - rig.input().getNetwork().getPressurePa()) + " Pa");
        }).thenSucceed();
    }

    @GameTest(template = "radiator", timeoutTicks = 900)
    public static void differentialPumpProtectsItsSupply(GameTestHelper h) {
        var rig = place(h);
        var pump = (MTEIntegratedFluidLiquidPump) rig.controller().getMetaTileEntity();
        var consumed = meterMvSupply(h, rig);
        long charge = 10_550_000_000L;
        long[] held = new long[2];
        h.startSequence().thenIdle(25).thenExecute(() -> {
            h.assertTrue(pump.checkStructure(true, rig.controller()), "Differential pump formed");
            pump.fixAllIssues();
            pump.setMode(1);
            pump.setTarget(25);
            pump.setInletFloor(190);
            for (var net : new gregtech.api.metatileentity.implementations.integratedfluid.IntegratedFluidNetwork[] {
                rig.input().getNetwork(), rig.output().getNetwork() })
                net.replaceCanonicalState(IFNCanonicalState.fromRaw("water", charge, -200000 * charge));
            rig.controller().enableWorking();
        }).thenWaitUntil(() -> h.assertTrue(rig.output().getNetwork().getAmountQ() > charge, "A smaller whole stroke fits"))
            .thenIdle(100).thenExecute(() -> {
                held[0] = rig.input().getNetwork().getAmountQ();
                held[1] = consumed.getAsLong();
                h.assertTrue(rig.input().getNetwork().getPressurePa() >= 190000, "Final inlet respects the floor");
                h.assertTrue(rig.output().getNetwork().getPressurePa() - rig.input().getNetwork().getPressurePa() < 20000,
                    "Supply protection takes precedence over the requested 25 cbar rise");
            }).thenIdle(100).thenExecute(() -> {
                h.assertEquals(held[0], rig.input().getNetwork().getAmountQ(), "Protected inlet no longer drained");
                h.assertEquals(held[1], consumed.getAsLong(), "No repeated debit at the floor");
                pump.setInletFloor(0);
            }).thenWaitUntil(() -> h.assertTrue(rig.input().getNetwork().getAmountQ() < held[0], "Lowering the floor releases the pump")).thenSucceed();
    }

    @GameTest(template = "radiator", timeoutTicks = 600)
    public static void pressureSetpointUsesPaidStrokePeriod(GameTestHelper h) {
        longPressureStroke(h, 0);
    }

    @GameTest(template = "radiator", timeoutTicks = 600)
    public static void pressureRiseUsesPaidStrokePeriod(GameTestHelper h) {
        longPressureStroke(h, 1);
    }

    private static void longPressureStroke(GameTestHelper h, int mode) {
        var pos = h.absolute("controller");
        var rig = HeatPumpTests.placeSteelShell(h.getWorld(), pos.x(), pos.y(), pos.z(),
            MTEIntegratedFluidLiquidPump.HIGH_PRESSURE_ID, (short) gregtech.api.enums.MetaTileEntityIDs.ENERGY_HATCH_LuV.ID);
        var pump = (MTEIntegratedFluidLiquidPump) rig.controller().getMetaTileEntity();
        rig.controller().disableWorking();
        var a = new AmbientTests.AmbientMeter(h, rig.input()::getNetwork);
        var b = new AmbientTests.AmbientMeter(h, rig.output()::getNetwork);
        LongSupplier energy = () -> a.withoutAmbient(rig.input().getNetwork()) + b.withoutAmbient(rig.output().getNetwork());
        long[] initial = new long[1], spent = new long[1], after = new long[1], supplied = new long[1];
        int voltage = 2048;
        long budget = voltage * 60L;
        long charge = 2 * budget;
        h.onEachTick("supply the energy for two complete cycles", () -> {
            if (supplied[0] < charge) supplied[0] += voltage * rig.supply().injectEnergyUnits(ForgeDirection.NORTH, voltage, 1);
        });
        h.startSequence().thenWaitUntil(() -> h.assertEquals(charge, rig.supply().getStoredEU(), "Power available before startup"))
            .thenExecute(() -> {
                h.assertTrue(pump.checkStructure(true, rig.controller()), "EV pump formed with a larger energy hatch");
                pump.fixAllIssues();
                pump.setMode(mode);
                pump.setTarget(mode == 0 ? 35000 : 12000);
                pump.setInterval(3);
                long inlet = 10_550_000_000L, outlet = 14_850_000_000L;
                rig.input().getNetwork().replaceCanonicalState(IFNCanonicalState.fromRaw("water", inlet, -200000 * inlet));
                rig.output().getNetwork().replaceCanonicalState(IFNCanonicalState.fromRaw("water", outlet, -200000 * outlet));
                initial[0] = energy.getAsLong();
                rig.controller().enableWorking();
            }).thenWaitUntil(() -> h.assertTrue(rig.output().getNetwork().getAmountQ() > 14_850_000_000L, "First discrete stroke"))
            .thenExecute(() -> {
                spent[0] = charge - rig.supply().getStoredEU();
                after[0] = rig.output().getNetwork().getAmountQ();
                h.assertTrue(spent[0] > voltage * 20L && spent[0] <= budget, "Work exceeds one period but fits three: " + spent[0]);
                h.assertEquals(0L, (after[0] - 14_850_000_000L) % 1_000_000L, "Whole liquid portions");
                h.assertEquals(25_400_000_000L, rig.input().getNetwork().getAmountQ() + after[0], "Both port masses balance");
                h.assertEquals(initial[0] + spent[0] * 1_000_000L, energy.getAsLong(), "Hatch paid for all fluid work");
                h.recordDiagnostic("Pressure mode " + mode + ", stroke=" + (after[0] - 14_850_000_000L) / 1_000_000L + " kg, work=" + spent[0] + " EU");
            }).thenIdle(55).thenExecute(() -> {
                h.assertEquals(after[0], rig.output().getNetwork().getAmountQ(), "No repeat on the old twenty-tick schedule");
                h.assertEquals(charge - spent[0], rig.supply().getStoredEU(), "Second stroke still fully funded");
            }).thenWaitUntil(() -> h.assertTrue(rig.output().getNetwork().getAmountQ() > after[0], "Second paid stroke"))
            .thenExecute(() -> h.assertEquals(initial[0] + (charge - rig.supply().getStoredEU()) * 1_000_000L, energy.getAsLong(),
                "Energy from both strokes reached the fluid")).thenSucceed();
    }

    @GameTest(template = "radiator", timeoutTicks = 1800)
    public static void holdsPressureAndRestartsAfterDraw(GameTestHelper h) {
        var rig = place(h);
        var controller = rig.controller();
        var consumed = meterMvSupply(h, rig);
        h.assertTrue(controller.getMetaTileEntity() instanceof MTEMultiBlockBase, "Liquid pump is a multiblock");
        var pump = (MTEMultiBlockBase) controller.getMetaTileEntity();
        long charge = 10_550_000_000L;
        long[] stopped = new long[3];
        h.startSequence().thenIdle(25).thenExecute(() -> {
            h.assertTrue(pump.checkStructure(true, controller), "Controller formed with separate fluid and energy hatches");
            pump.fixAllIssues();
            var inlet = rig.input().getNetwork();
            var outlet = rig.output().getNetwork();
            h.assertTrue(inlet != null && outlet != null && inlet != outlet, "Two hatch networks");
            inlet.replaceCanonicalState(IFNCanonicalState.fromRaw("water", charge, -200000 * charge));
            outlet.replaceCanonicalState(IFNCanonicalState.fromRaw("water", charge, -200000 * charge));
            h.recordDiagnostic("Initial outlet " + outlet.getPressurePa() + " Pa");
            h.assertTrue(outlet.getPressurePa() < 198000, "Receiver starts below the 2 bar setpoint");
            controller.enableWorking();
        }).thenWaitUntil(() -> h.assertTrue(rig.output().getNetwork().getPressurePa() >= 199000, "Receiver within one centibar"))
            .thenExecute(() -> {
                stopped[0] = rig.output().getNetwork().getAmountQ();
                stopped[1] = consumed.getAsLong();
                h.assertTrue(stopped[0] > charge && stopped[1] > 0, "Actual paid transfer");
                h.assertTrue(rig.output().getNetwork().getPressurePa() <= 200001, "No overshoot");
            }).thenIdle(150).thenExecute(() -> {
                h.assertEquals(stopped[0], rig.output().getNetwork().getAmountQ(), "No flow while satisfied");
                h.assertEquals(stopped[1], consumed.getAsLong(), "No standby energy debit");
                h.recordDiagnostic("Hold: " + rig.output().getNetwork().getPressurePa() + " Pa, delivered "
                    + (stopped[0] - charge) / 1000000 + " kg, paid " + stopped[1] + " EU");
                var delivered = stopped[0] - charge;
                h.assertEquals(charge - delivered, rig.input().getNetwork().getAmountQ(), "Balanced input debit");
                var receiver = rig.output().getNetwork().fillMachineChamber(ClosedFluidChamber.empty(PureFluid.water(), 1000), 100);
                stopped[2] = rig.output().getNetwork().getAmountQ();
                h.assertTrue(receiver.massKg().signum() > 0, "A downstream machine admitted water");
                h.assertEquals(stopped[0] - stopped[2], receiver.massKg().movePointRight(6).longValueExact(), "Withdrawal accounted");
            }).thenWaitUntil(() -> h.assertTrue(rig.output().getNetwork().getAmountQ() > stopped[2], "Resumed without changing settings"))
            .thenExecute(() -> h.assertTrue(consumed.getAsLong() > stopped[1], "Restart paid by the hatch")).thenSucceed();
    }
}
