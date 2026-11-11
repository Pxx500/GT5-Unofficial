package gregtech.api.metatileentity.implementations.integratedfluid.thermodynamics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class ClosedFluidChamberEnvironmentTest {

    @Test
    void electricityPaysForBothFluidHeatingAndAmbientLosses() {
        ClosedFluidChamber initial = liquid300();
        var result = initial.exchangeHeat(new ThermalBoundary(280.0, 320.0, 100.0, 10.0, 10000.0));
        assertTrue(
            result.heatToChamberEu()
                .signum() > 0);
        assertEquals(
            320.0,
            result.contacts()
                .get(0)
                .jacketKelvin(),
            1e-7);
        assertEquals(
            400.0,
            result.heatToAmbientEu()
                .doubleValue(),
            1e-6);
        assertEquals(
            0,
            result.electricityEu()
                .compareTo(
                    result.heatToChamberEu()
                        .add(result.heatToAmbientEu())));
        assertEquals(
            0,
            initial.totalEnergyEu()
                .add(result.heatToChamberEu())
                .compareTo(
                    result.chamber()
                        .totalEnergyEu()));
        assertTrue(
            result.electricityEu()
                .doubleValue() <= 10000.0);
    }

    private static ClosedFluidChamber liquid300() {
        return ClosedFluidChamber.of(PureFluid.water(), BigDecimal.ONE, 1.043, new BigDecimal("-305871.3"));
    }

    @Test
    void anUnpoweredJacketHasOnlyTheSeriesConductanceOfAnOrdinaryPipe() {
        var result = liquid300().exchangeHeat(new ThermalBoundary(280.0, 400.0, 100.0, 10.0, 0.0));
        double conductance = 100.0 * 10.0 / 110.0;
        double expected = (4180.0 * 300.0 + conductance * 280.0) / (4180.0 + conductance);
        assertEquals(
            expected,
            result.chamber()
                .state()
                .temperatureKelvin(),
            1e-8);
        assertEquals(
            0,
            result.electricityEu()
                .signum());
        assertEquals(
            0,
            result.heatToChamberEu()
                .add(result.heatToAmbientEu())
                .signum());
    }

    @Test
    void insufficientPowerCannotGuaranteeTheSetpointAndTheHeaterCannotRefrigerate() {
        var weak = liquid300().exchangeHeat(new ThermalBoundary(200.0, 350.0, 100.0, 10.0, 100.0));
        assertEquals(
            100.0,
            weak.electricityEu()
                .doubleValue());
        assertTrue(
            weak.contacts()
                .get(0)
                .jacketKelvin() < 350.0);
        assertTrue(
            weak.chamber()
                .state()
                .temperatureKelvin() < 300.0);
        var off = liquid300().exchangeHeat(new ThermalBoundary(310.0, 280.0, 100.0, 10.0, 10000.0));
        assertEquals(
            0,
            off.electricityEu()
                .signum());
        assertTrue(
            off.chamber()
                .state()
                .temperatureKelvin() > 300.0);
        assertTrue(
            off.contacts()
                .get(0)
                .jacketKelvin() > 280.0);
    }

    @Test
    void poweredHeatingThawsFrozenMaterialAndAccountsForEveryTick() {
        ClosedFluidChamber chamber = ClosedFluidChamber
            .of(PureFluid.water(), BigDecimal.ONE, 1.043, new BigDecimal("-800000"));
        BigDecimal initialEnergy = chamber.totalEnergyEu();
        BigDecimal electricity = BigDecimal.ZERO;
        BigDecimal losses = BigDecimal.ZERO;
        ThermalBoundary boundary = new ThermalBoundary(200.0, 300.0, 1000.0, 5.0, 10000.0);
        assertEquals(
            1.0,
            chamber.state()
                .solidMassFraction());
        for (int tick = 0; tick < 120; tick++) {
            var step = chamber.exchangeHeat(boundary);
            assertTrue(
                step.electricityEu()
                    .doubleValue() >= 0.0);
            assertTrue(
                step.electricityEu()
                    .doubleValue() <= 10000.0);
            assertTrue(
                step.chamber()
                    .state()
                    .temperatureKelvin() <= 300.0 + 1e-7);
            electricity = electricity.add(step.electricityEu());
            losses = losses.add(step.heatToAmbientEu());
            chamber = step.chamber();
        }
        assertEquals(
            0.0,
            chamber.state()
                .solidMassFraction());
        assertTrue(
            chamber.state()
                .temperatureKelvin() > 273.16);
        assertEquals(BigDecimal.ONE, chamber.massKg());
        assertEquals(
            0,
            initialEnergy.add(electricity)
                .subtract(losses)
                .compareTo(chamber.totalEnergyEu()));
    }

    @Test
    void strongPassiveContactDoesNotOvershootAndWarmAmbientCanMeltIce() {
        var result = liquid300().exchangeHeat(new ThermalBoundary(280.0, 0.0, 1e6, 1e6, 0.0));
        assertTrue(
            result.chamber()
                .state()
                .temperatureKelvin() >= 280.0);
        assertTrue(
            result.chamber()
                .state()
                .temperatureKelvin() < 300.0);
        ClosedFluidChamber frozen = ClosedFluidChamber
            .of(PureFluid.water(), BigDecimal.ONE, 1.043, new BigDecimal("-800000"));
        var thawed = frozen.exchangeHeat(new ThermalBoundary(300.0, 0.0, 1e6, 1e6, 0.0));
        assertEquals(
            0.0,
            thawed.chamber()
                .state()
                .solidMassFraction());
        assertEquals(
            0,
            thawed.electricityEu()
                .signum());
        assertTrue(
            thawed.heatToAmbientEu()
                .signum() < 0);
    }

    @Test
    void anEmptyPipeCanLoseElectricHeatButCannotStoreItInNonexistentFluid() {
        ClosedFluidChamber empty = ClosedFluidChamber.empty(PureFluid.water(), 1.043);
        var result = empty.exchangeHeat(new ThermalBoundary(200.0, 300.0, 1000.0, 5.0, 10000.0));
        assertTrue(
            result.chamber()
                .isEmpty());
        assertEquals(
            0,
            result.chamber()
                .totalEnergyEu()
                .signum());
        assertEquals(
            500.0,
            result.electricityEu()
                .doubleValue());
        assertEquals(result.electricityEu(), result.heatToAmbientEu());
        assertThrows(
            IllegalArgumentException.class,
            () -> liquid300().exchangeHeat(new ThermalBoundary(1000.0, 1000.0, 1e9, 1e9, 0.0)));
    }
}
