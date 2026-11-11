package gregtech.api.metatileentity.implementations.integratedfluid.thermodynamics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;

import org.junit.jupiter.api.Test;

class ThermalProfileTest {

    @Test
    void preparedResponseSatisfiesTheHeatLawAcrossHeaterSwitchingPoints() {
        for (ThermalBoundary boundary : new ThermalBoundary[] { new ThermalBoundary(200, 300, 100, 10, 1000),
            new ThermalBoundary(200, 300, 100, 10, 0), new ThermalBoundary(200, 300, 100, 0, 1000) }) {
            var profile = ThermalBoundary.compile(Collections.singletonList(boundary));
            for (double initialTemperature : new double[] { 280, 299, 300, 301, 309, 310, 311, 340 }) {
                var initial = liquid(initialTemperature);
                var result = initial.exchangeHeat(profile);
                double temperature = result.chamber()
                    .state()
                    .temperatureKelvin();
                double power = Math.min(
                    boundary.maximumPowerEuPerTick(),
                    Math.max(
                        0,
                        boundary.outerConductance() * (boundary.targetKelvin() - boundary.ambientKelvin())
                            + boundary.innerConductance() * (boundary.targetKelvin() - temperature)));
                double jacket = (power + boundary.outerConductance() * boundary.ambientKelvin()
                    + boundary.innerConductance() * temperature)
                    / (boundary.innerConductance() + boundary.outerConductance());
                double heat = boundary.innerConductance() * (jacket - temperature);
                assertEquals(
                    power,
                    result.electricityEu()
                        .doubleValue(),
                    1e-6);
                assertEquals(
                    heat,
                    result.chamber()
                        .totalEnergyEu()
                        .subtract(initial.totalEnergyEu())
                        .doubleValue(),
                    1e-6);
                assertEquals(
                    0,
                    result.electricityEu()
                        .compareTo(
                            result.heatToChamberEu()
                                .add(result.heatToAmbientEu())));
            }
        }
    }

    @Test
    void aProfileIsReusableAndPowerChangesRequireANewSnapshot() {
        var inputs = new ArrayList<>(Collections.singletonList(new ThermalBoundary(200, 320, 100, 10, 10000)));
        var prepared = ThermalBoundary.compile(inputs);
        var initial = liquid(300);
        var first = initial.exchangeHeat(prepared);
        inputs.set(0, new ThermalBoundary(200, 320, 100, 10, 0));
        liquid(280).exchangeHeat(prepared);
        var repeated = initial.exchangeHeat(prepared);
        assertEquals(
            first.chamber()
                .state(),
            repeated.chamber()
                .state());
        assertEquals(
            first.chamber()
                .totalEnergyEu(),
            repeated.chamber()
                .totalEnergyEu());
        assertEquals(first.contacts(), repeated.contacts());
        assertTrue(
            first.heatToChamberEu()
                .signum() > 0);
        var unpowered = initial.exchangeHeat(ThermalBoundary.compile(inputs));
        assertTrue(
            unpowered.heatToChamberEu()
                .signum() < 0);
        assertEquals(
            0,
            unpowered.electricityEu()
                .signum());
    }

    private static ClosedFluidChamber liquid(double temperature) {
        return ClosedFluidChamber
            .of(PureFluid.water(), BigDecimal.ONE, 1.043, BigDecimal.valueOf(4180 * (temperature - 373.15) - 104.3));
    }
}
