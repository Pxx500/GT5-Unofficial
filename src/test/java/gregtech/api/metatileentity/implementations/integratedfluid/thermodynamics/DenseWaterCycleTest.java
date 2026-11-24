package gregtech.api.metatileentity.implementations.integratedfluid.thermodynamics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.function.ToDoubleFunction;

import org.junit.jupiter.api.Test;

class DenseWaterCycleTest {

    private static final PureFluid WATER = PureFluid.water();
    private static final double PUMP_EFFICIENCY = 0.8;
    private static final double STEEL_ROTOR_EFFICIENCY = 0.77;

    @Test
    void sealedIndustrialCoolantsCrossTheirOwnCriticalRegionsAndRecoverTheColdCharge() {
        for (String id : new String[] { "ic2coolant", "supercoolant" }) {
            var fluid = PureFluid.forSubstance(id).get();
            var vessel = ChamberVolume.rigid(20);
            double criticalK = id.equals("ic2coolant") ? 650 : 680;
            var cold = ClosedFluidChamber.of(fluid, BigDecimal.ONE, vessel,
                BigDecimal.valueOf(fluid.specificTotalEnergyAtTemperature(vessel, 1, 300)));
            assertTrue(cold.state().liquidMassFraction() > 0.99);
            var warmed = cold;
            for (double t : new double[] { 599.99, 600, 600.01, criticalK - 1, criticalK + 2 }) {
                var energy = BigDecimal.valueOf(fluid.specificTotalEnergyAtTemperature(vessel, 1, t));
                assertTrue(energy.compareTo(warmed.totalEnergyEu()) > 0);
                var next = warmed.withHeat(energy.subtract(warmed.totalEnergyEu()));
                assertEquals(t, next.state().temperatureKelvin(), 1e-6);
                assertTrue(next.pressurePa() >= warmed.pressurePa());
                if (t == 600.01) assertTrue(next.pressurePa() < warmed.pressurePa() * 1.001);
                warmed = next;
            }
            assertEquals(PureFluid.DenseRegion.SUPERCRITICAL, warmed.state().denseRegion());
            assertTrue(!warmed.state().isPureStream(true) && !warmed.state().isPureStream(false));
            var restored = warmed.withHeat(cold.totalEnergyEu().subtract(warmed.totalEnergyEu()));
            assertEquals(cold.pressurePa(), restored.pressurePa(), 1e-6);
            assertEquals(300, restored.state().temperatureKelvin(), 1e-6);
            assertEquals(0, restored.massKg().compareTo(cold.massKg()));
        }
    }

    @Test
    void aDenseTurbineStrokePaysTheLoadFromTheTwoChambers() {
        var inlet = ClosedFluidChamber.of(WATER, new BigDecimal("12.894426"), 100, new BigDecimal("35530474.686141"));
        var exhaust = ClosedFluidChamber.of(WATER, new BigDecimal("21.301564"), 100, new BigDecimal("37551663.742696"));
        var stage = new ClosedFluidChamber.ExpansionStage(8, STEEL_ROTOR_EFFICIENCY);
        var port = BigDecimal.valueOf(8);
        var blocked = inlet.expandTo(exhaust, port, stage, 2560);
        assertEquals(0, blocked.transferredMassKg().signum(), "An MV dynamo cannot accept the work from a full dense portion");
        var move = inlet.expandTo(exhaust, port, stage, 160000);
        assertEquals(1, move.transferredMassKg().intValueExact());
        assertTrue(move.shaftWorkEu() > 2560 && move.shaftWorkEu() <= 160000);
        assertEquals(0, move.source().massKg().add(move.receiver().massKg()).compareTo(inlet.massKg().add(exhaust.massKg())));
        assertEquals(0, inlet.totalEnergyEu().add(exhaust.totalEnergyEu()).subtract(move.source().totalEnergyEu())
            .subtract(move.receiver().totalEnergyEu()).compareTo(BigDecimal.valueOf(move.shaftWorkEu())));
        assertTrue(!move.wetStageExit());
        var ideal = WATER.idealExpansionOutlet(inlet.state(), exhaust.pressurePa(), 8);
        var expected = isentropic(exhaust.pressurePa(), inlet.state().specificEntropyEuPerKgKelvin());
        assertEquals(exhaust.pressurePa(), ideal.pressurePa(), 0.001);
        assertEquals(expected.enthalpy(), ideal.specificEnthalpyEuPerKg(), 1e-5);
    }

    @Test
    void denseCompressionRequiresHeadAndElectricityAndStoresAllOfThePaidWork() {
        var low = ClosedFluidChamber.of(WATER, new BigDecimal("213.01564"), 1000, new BigDecimal("375516637.42696"));
        var high = ClosedFluidChamber.of(WATER, new BigDecimal("128.94426"), 1000, new BigDecimal("355304746.86141"));
        var stroke = BigDecimal.valueOf(6);
        assertEquals(0, low.pumpLiquidTo(high, stroke, 35e6, 3e5, 81920).transferredMassKg().signum());
        assertEquals(0, low.pumpLiquidTo(high, stroke, 35e6, 12e6, 0).transferredMassKg().signum());
        assertEquals(0, low.pumpLiquidTo(high, stroke, 35e6, 12e6, 40960).transferredMassKg().signum());
        var move = low.pumpLiquidTo(high, stroke, 35e6, 12e6, 81920);
        double kg = move.transferredMassKg().doubleValue();
        long paid = -move.shaftWorkEu();
        assertEquals(1, move.transferredMassKg().intValueExact());
        assertTrue(paid > 40960 && paid <= 81920);
        assertTrue(move.receiver().pressurePa() > high.pressurePa());
        var ideal = isentropic(high.pressurePa(), low.state().specificEntropyEuPerKgKelvin());
        assertTrue(paid >= kg * (ideal.enthalpy() - low.state().specificEnthalpyEuPerKg()) / PUMP_EFFICIENCY);
        assertEquals(0, move.source().totalEnergyEu().add(move.receiver().totalEnergyEu()).subtract(low.totalEnergyEu())
            .subtract(high.totalEnergyEu()).compareTo(BigDecimal.valueOf(paid)));
    }

    @Test
    void sealedWaterRecoversItsPressureAfterHeatingAndCoolingWithOrWithoutASpring() {
        double p = 22.1e6;
        var target = WATER.hotStreamAtPressure(650, p);
        for (double stroke : new double[] { 0, 0.6 }) {
            double compliance = stroke / (p - 1e5);
            var volume = new ChamberVolume(target.litersPerKg() - stroke, compliance, 1e5, stroke * 2);
            var start = ClosedFluidChamber.of(WATER, BigDecimal.ONE, volume,
                BigDecimal.valueOf(WATER.specificTotalEnergyAtTemperature(volume, 1, 480)));
            double spring = 0.001 * (p + 1e5) / 2 * stroke;
            var hotEnergy = BigDecimal.valueOf(target.enthalpy() - 0.001 * p * target.litersPerKg() + spring);
            var supplied = hotEnergy.subtract(start.totalEnergyEu());
            var hot = start.withHeat(supplied);
            assertEquals(650, hot.state().temperatureKelvin(), 1e-6);
            assertEquals(p, hot.pressurePa(), 0.01);
            assertEquals(spring, hot.elasticEnergyEu().doubleValue(), 1e-5);
            assertEquals(PureFluid.DenseRegion.SUPERCRITICAL, hot.state().denseRegion());
            assertTrue(!hot.state().isPureStream(false) && !hot.state().isPureStream(true));
            var restored = ClosedFluidChamber.of(WATER, hot.massKg(), volume, hot.totalEnergyEu());
            var cold = restored.withHeat(supplied.negate());
            assertEquals(start.pressurePa(), cold.pressurePa(), 1e-6);
            assertEquals(480, cold.state().temperatureKelvin(), 1e-6);
            assertEquals(0, cold.totalEnergyEu().compareTo(start.totalEnergyEu()));
        }
    }

    @Test
    void heatingASealedWetChargeThroughTheCrossoverDoesNotResetItsPressureOrEnergy() {
        var vessel = ChamberVolume.rigid(5);
        var initial = ClosedFluidChamber.of(WATER, BigDecimal.ONE, vessel,
            BigDecimal.valueOf(WATER.specificTotalEnergyAtTemperature(vessel, 1, 499.99)));
        var warmed = initial;
        for (double t : new double[] { 500, 500.00001, 500.01, 550, 647, 660 }) {
            var energy = BigDecimal.valueOf(WATER.specificTotalEnergyAtTemperature(vessel, 1, t));
            assertTrue(energy.compareTo(warmed.totalEnergyEu()) > 0);
            var next = warmed.withHeat(energy.subtract(warmed.totalEnergyEu()));
            assertEquals(t, next.state().temperatureKelvin(), 1e-6);
            assertTrue(next.pressurePa() >= warmed.pressurePa());
            if (t <= 500.01) assertTrue(next.pressurePa() < initial.pressurePa() * 1.001);
            warmed = next;
        }
        assertEquals(PureFluid.DenseRegion.SUPERCRITICAL, warmed.state().denseRegion());
        assertEquals(initial.pressurePa(), warmed.withHeat(initial.totalEnergyEu().subtract(warmed.totalEnergyEu())).pressurePa(), 1e-6);
    }

    @Test
    void denseReturnUsesLessCompressionWorkWithoutAwardingTheTurbineExtraPower() {
        var ordinary = cycle(19e6, false);
        var dense = cycle(22.1e6, false);
        assertTrue(dense.inletLitersPerKg < ordinary.inletLitersPerKg);
        assertTrue(dense.compression < ordinary.compression);
        assertTrue(dense.shaft < ordinary.shaft);
        assertTrue(ordinary.efficiency() > 0 && dense.efficiency() > ordinary.efficiency());
        assertTrue(dense.efficiency() < 1 - 650.0 / 850);
        var recovered = cycle(22.1e6, true);
        assertEquals(dense.shaft - dense.compression, recovered.shaft - recovered.compression, 1e-7);
        assertTrue(recovered.heat < dense.heat && recovered.efficiency() < 1 - 650.0 / 850);
        System.out.printf(java.util.Locale.ROOT, "190 bar %s%n221 bar %s%n221 bar with heat recovery %s%n", ordinary, dense, recovered);
    }

    @Test
    void compressionFollowedByExpansionCannotPowerAnIsolatedLoop() {
        for (double inletK : new double[] { 650, 700, 800 }) {
            var inlet = WATER.hotStreamAtPressure(inletK, 22.1e6);
            var ideal = isentropic(33.15e6, inlet.entropy());
            double paid = (ideal.enthalpy() - inlet.enthalpy()) / PUMP_EFFICIENCY;
            var discharge = atEnthalpy(33.15e6, inlet.enthalpy() + paid);
            double recovered = STEEL_ROTOR_EFFICIENCY * (discharge.enthalpy() - isentropic(22.1e6, discharge.entropy()).enthalpy());
            assertTrue(recovered > 0 && recovered < paid);
            assertEquals(inlet.enthalpy(), isentropic(22.1e6, ideal.entropy()).enthalpy(), 1e-6);
        }
    }

    @Test
    void mechanicalAndThermalReadingsDescribeTheSameInventory() {
        for (double t : new double[] { 625, 650, 700, 850 }) for (double p : new double[] { 15e6, 22.1e6, 35e6 }) {
            var state = WATER.hotStreamAtPressure(t, p);
            var left = WATER.hotStreamAtPressure(t, p - 100);
            var right = WATER.hotStreamAtPressure(t, p + 100);
            double dh = right.enthalpy() - left.enthalpy();
            double ds = right.entropy() - left.entropy();
            assertEquals(0.001 * state.litersPerKg() * 200, dh - t * ds, 1e-6);
            var hotter = WATER.hotStreamAtPressure(t + 0.01, p);
            assertTrue(right.litersPerKg() < left.litersPerKg() && hotter.enthalpy() > state.enthalpy());
            assertEquals(hotter.enthalpy() - state.enthalpy(), (t + 0.005) * (hotter.entropy() - state.entropy()), 1e-5);
        }
    }

    // One kilogram traverses two adiabatic machines and returns to the same inlet after cooling
    private static Cycle cycle(double lowPa, boolean recuperate) {
        double highPa = 1.5 * lowPa;
        var cold = WATER.hotStreamAtPressure(650, lowPa);
        var hot = WATER.hotStreamAtPressure(850, highPa);
        var compressed = isentropic(highPa, cold.entropy());
        var expanded = isentropic(lowPa, hot.entropy());
        double compression = (compressed.enthalpy() - cold.enthalpy()) / PUMP_EFFICIENCY;
        double shaft = STEEL_ROTOR_EFFICIENCY * (hot.enthalpy() - expanded.enthalpy());
        double feedH = cold.enthalpy() + compression;
        double exhaustH = hot.enthalpy() - shaft;
        double recoveredHeat = 0;
        if (recuperate) {
            // A single passive contact stops before either equal-mass stream can cross the other's exit temperature
            double feedT = temperatureAt(highPa, feedH, PureFluid.HotStream::enthalpy);
            double exhaustT = temperatureAt(lowPa, exhaustH, PureFluid.HotStream::enthalpy);
            double middle = (feedT + exhaustT) / 2;
            recoveredHeat = Math.min(WATER.hotStreamAtPressure(middle, highPa).enthalpy() - feedH,
                exhaustH - WATER.hotStreamAtPressure(middle, lowPa).enthalpy());
            assertTrue(recoveredHeat > 0);
        }
        return new Cycle(cold.litersPerKg(), compression, shaft, hot.enthalpy() - feedH - recoveredHeat);
    }

    private static PureFluid.HotStream isentropic(double pressure, double entropy) {
        return WATER.hotStreamAtPressure(temperatureAt(pressure, entropy, PureFluid.HotStream::entropy), pressure);
    }

    private static PureFluid.HotStream atEnthalpy(double pressure, double enthalpy) {
        return WATER.hotStreamAtPressure(temperatureAt(pressure, enthalpy, PureFluid.HotStream::enthalpy), pressure);
    }

    private static double temperatureAt(double pressure, double target, ToDoubleFunction<PureFluid.HotStream> property) {
        double lo = 600, hi = 950;
        for (int i = 0; i < 48; i++) {
            double mid = (lo + hi) / 2;
            if (property.applyAsDouble(WATER.hotStreamAtPressure(mid, pressure)) < target) lo = mid;
            else hi = mid;
        }
        double temperature = (lo + hi) / 2;
        assertEquals(target, property.applyAsDouble(WATER.hotStreamAtPressure(temperature, pressure)), 1e-6);
        return temperature;
    }

    private record Cycle(double inletLitersPerKg, double compression, double shaft, double heat) {
        double efficiency() { return (shaft - compression) / heat; }
    }
}
