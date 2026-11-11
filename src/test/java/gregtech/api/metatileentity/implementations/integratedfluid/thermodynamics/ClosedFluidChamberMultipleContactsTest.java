package gregtech.api.metatileentity.implementations.integratedfluid.thermodynamics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;

class ClosedFluidChamberMultipleContactsTest {

    @Test
    void oppositeAmbientContactsBalanceSimultaneouslyRegardlessOfOrder() {
        var chamber = ClosedFluidChamber.of(PureFluid.water(), BigDecimal.ONE, 1.043, new BigDecimal("-305871.3"));
        var cold = new ThermalBoundary(280.0, 0.0, 100.0, 10.0, 0.0);
        var hot = new ThermalBoundary(320.0, 0.0, 100.0, 10.0, 0.0);
        var forward = chamber.exchangeHeat(Arrays.asList(cold, hot));
        var reverse = chamber.exchangeHeat(Arrays.asList(hot, cold));
        assertEquals(
            300.0,
            forward.chamber()
                .state()
                .temperatureKelvin(),
            1e-8);
        assertEquals(
            forward.chamber()
                .state(),
            reverse.chamber()
                .state());
        assertEquals(
            forward.chamber()
                .totalEnergyEu(),
            reverse.chamber()
                .totalEnergyEu());
    }

    @Test
    void distinctHeatersKeepIndividualPowerLimitsAndAnExactCombinedLedger() {
        var chamber = ClosedFluidChamber.of(PureFluid.water(), BigDecimal.ONE, 1.043, new BigDecimal("-800000"));
        var contacts = Arrays.asList(
            new ThermalBoundary(200, 300, 1000, 5, 10000),
            new ThermalBoundary(250, 320, 700, 3, 100),
            new ThermalBoundary(180, 0, 100, 10, 0));
        var result = chamber.exchangeHeat(contacts);
        BigDecimal power = BigDecimal.ZERO;
        BigDecimal heat = BigDecimal.ZERO;
        BigDecimal losses = BigDecimal.ZERO;
        for (int i = 0; i < contacts.size(); i++) {
            var item = result.contacts()
                .get(i);
            assertTrue(
                item.electricityEu()
                    .doubleValue()
                    <= contacts.get(i)
                        .maximumPowerEuPerTick());
            assertEquals(
                0,
                item.electricityEu()
                    .compareTo(
                        item.heatToChamberEu()
                            .add(item.heatToAmbientEu())));
            power = power.add(item.electricityEu());
            heat = heat.add(item.heatToChamberEu());
            losses = losses.add(item.heatToAmbientEu());
        }
        assertEquals(power, result.electricityEu());
        assertEquals(heat, result.heatToChamberEu());
        assertEquals(0, losses.compareTo(result.heatToAmbientEu()));
        assertEquals(
            0,
            chamber.totalEnergyEu()
                .add(power)
                .subtract(losses)
                .compareTo(
                    result.chamber()
                        .totalEnergyEu()));
        var reversed = new ArrayList<>(contacts);
        Collections.reverse(reversed);
        var again = chamber.exchangeHeat(reversed);
        assertEquals(
            result.chamber()
                .state(),
            again.chamber()
                .state());
        assertEquals(
            result.chamber()
                .totalEnergyEu(),
            again.chamber()
                .totalEnergyEu());
        assertEquals(
            result.contacts()
                .get(0),
            again.contacts()
                .get(2));
    }

    @Test
    void repeatedIdenticalContactsMatchOneProportionallyLargerContact() {
        var chamber = ClosedFluidChamber.of(PureFluid.water(), BigDecimal.ONE, 1.043, new BigDecimal("-600000"));
        var unit = new ThermalBoundary(200, 300, 10, 2, 30);
        var repeated = chamber.exchangeHeat(Collections.nCopies(1000, unit));
        var combined = chamber.exchangeHeat(new ThermalBoundary(200, 300, 10000, 2000, 30000));
        assertEquals(
            1000,
            repeated.contacts()
                .size());
        assertEquals(
            combined.chamber()
                .state()
                .temperatureKelvin(),
            repeated.chamber()
                .state()
                .temperatureKelvin(),
            1e-7);
        assertEquals(
            combined.chamber()
                .totalEnergyEu()
                .doubleValue(),
            repeated.chamber()
                .totalEnergyEu()
                .doubleValue(),
            1e-6);
        assertEquals(
            combined.electricityEu()
                .doubleValue(),
            repeated.electricityEu()
                .doubleValue(),
            1e-6);
    }

    @Test
    void emptyAndAbsentContactsCannotInventAFluidHeatStore() {
        var chamber = ClosedFluidChamber.empty(PureFluid.water(), 1000);
        var boundaries = new ArrayList<>(
            Arrays.asList(new ThermalBoundary(200, 300, 10, 2, 500), new ThermalBoundary(250, 350, 10, 3, 100)));
        var result = chamber.exchangeHeat(boundaries);
        boundaries.clear();
        assertEquals(
            2,
            result.contacts()
                .size());
        assertTrue(
            result.chamber()
                .isEmpty());
        assertEquals(
            300.0,
            result.electricityEu()
                .doubleValue());
        assertEquals(
            0,
            result.heatToChamberEu()
                .signum());
        assertEquals(result.electricityEu(), result.heatToAmbientEu());
        assertThrows(
            UnsupportedOperationException.class,
            () -> result.contacts()
                .clear());
        assertSame(
            chamber,
            chamber.exchangeHeat(Collections.<ThermalBoundary>emptyList())
                .chamber());
        assertThrows(NullPointerException.class, () -> chamber.exchangeHeat(Arrays.asList((ThermalBoundary) null)));
    }
}
