package gregtech.api.metatileentity.implementations.integratedfluid.thermodynamics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class ClosedFluidChamberComplianceTest {

    @Test
    void vaporExpansionStoresWorkInsteadOfLeavingItInTheFluid() {
        // 450 K vapor at 200 kPa occupies 1001 L, including 1 L of spring stroke.
        BigDecimal mass = BigDecimal.valueOf(200000.0 * 1001.0 / (1000.0 * 461.5 * 450.0));
        BigDecimal energy = mass.multiply(new BigDecimal("2203793.5"))
            .add(new BigDecimal("150"));
        ClosedFluidChamber chamber = ClosedFluidChamber
            .of(PureFluid.water(), mass, new ChamberVolume(1000.0, 0.00001, 100000.0, 2.0), energy);

        assertEquals(
            450.0,
            chamber.state()
                .temperatureKelvin(),
            1e-7);
        assertEquals(200000.0, chamber.pressurePa(), 1e-5);
        assertEquals(1001.0, chamber.volumeLiters(), 1e-8);
        assertEquals(
            150.0,
            energy.subtract(chamber.internalEnergyEu())
                .doubleValue(),
            1e-7);
    }

    @Test
    void liquidPressureIsContinuousAtActivationAndTheMechanicalStop() {
        ChamberVolume volume = new ChamberVolume(1.043, 0.00001, 100000.0, 2.0);
        double previousPressure = 0.0;
        for (double pressure : new double[] { 99999.0, 100000.0, 100001.0, 299999.0, 300000.0, 300001.0, 400000.0 }) {
            ClosedFluidChamber chamber = liquid(volume, pressure, 350.0);
            assertEquals(pressure, chamber.pressurePa(), 1e-5);
            assertEquals(
                350.0,
                chamber.state()
                    .temperatureKelvin(),
                1e-7);
            assertEquals(
                0.0,
                chamber.state()
                    .vaporMassFraction());
            assertTrue(chamber.pressurePa() > previousPressure);
            previousPressure = chamber.pressurePa();
            assertTrue(chamber.volumeLiters() >= 1.043);
            assertTrue(chamber.volumeLiters() <= 3.043);
            assertEquals(
                0,
                chamber.totalEnergyEu()
                    .compareTo(
                        chamber.internalEnergyEu()
                            .add(chamber.elasticEnergyEu())));
        }
        ClosedFluidChamber full = liquid(volume, 400000.0, 350.0);
        assertEquals(3.043, full.volumeLiters(), 1e-10);
        assertEquals(
            400.0,
            full.elasticEnergyEu()
                .doubleValue(),
            1e-7);
    }

    @Test
    void heatingAndCoolingAMixtureReturnsTheSpringAndFluidToTheirInitialState() {
        ChamberVolume volume = new ChamberVolume(361.565125, 0.01, 50000.0, 1000.0);
        ClosedFluidChamber initial = ClosedFluidChamber
            .of(PureFluid.water(), BigDecimal.ONE, volume, new BigDecimal("1079843.4875"));
        assertEquals(
            373.15,
            initial.state()
                .temperatureKelvin(),
            1e-7);
        assertEquals(
            0.5,
            initial.state()
                .vaporMassFraction(),
            1e-7);
        assertEquals(100000.0, initial.pressurePa(), 1e-5);
        assertEquals(
            37500.0,
            initial.elasticEnergyEu()
                .doubleValue(),
            1e-5);

        BigDecimal heat = new BigDecimal("1300000");
        ClosedFluidChamber heated = initial.withHeat(heat);
        assertEquals(
            1.0,
            heated.state()
                .vaporMassFraction());
        assertTrue(
            heated.elasticEnergyEu()
                .compareTo(initial.elasticEnergyEu()) > 0);
        assertEquals(
            0,
            heat.compareTo(
                heated.totalEnergyEu()
                    .subtract(initial.totalEnergyEu())));
        ClosedFluidChamber restored = heated.withHeat(heat.negate());
        assertEquals(initial.totalEnergyEu(), restored.totalEnergyEu());
        assertEquals(initial.internalEnergyEu(), restored.internalEnergyEu());
        assertEquals(initial.elasticEnergyEu(), restored.elasticEnergyEu());
        assertEquals(initial.state(), restored.state());
    }

    @Test
    void outflowReleasesSpringWorkAndKeepsTheCombinedEnergyLedgerExact() {
        ChamberVolume volume = new ChamberVolume(1.043, 0.00001, 100000.0, 2.0);
        ClosedFluidChamber source = liquid(volume, 200000.0, 350.0);
        ClosedFluidChamber receiver = ClosedFluidChamber.empty(PureFluid.water(), volume);
        ClosedFluidChamber.FluidTransfer result = source.transferTo(receiver, new BigDecimal("0.1"));
        assertEquals(0, new BigDecimal("0.1").compareTo(result.transferredMassKg()));
        assertTrue(
            result.source()
                .elasticEnergyEu()
                .compareTo(source.elasticEnergyEu()) < 0);
        assertEquals(
            350.0,
            result.source()
                .state()
                .temperatureKelvin(),
            1e-7);
        assertEquals(
            0,
            source.massKg()
                .compareTo(
                    result.source()
                        .massKg()
                        .add(
                            result.receiver()
                                .massKg())));
        assertEquals(
            0,
            source.totalEnergyEu()
                .compareTo(
                    result.source()
                        .totalEnergyEu()
                        .add(
                            result.receiver()
                                .totalEnergyEu())));
        assertEquals(
            0,
            result.transportedEnergyEu()
                .compareTo(
                    source.totalEnergyEu()
                        .subtract(
                            result.source()
                                .totalEnergyEu())));

        for (int i = 0; i < 10; i++) {
            ClosedFluidChamber.FluidTransfer step = source.transferTo(receiver, new BigDecimal("0.01"));
            assertEquals(0, new BigDecimal("0.01").compareTo(step.transferredMassKg()));
            source = step.source();
            receiver = step.receiver();
        }
        assertEquals(
            result.source()
                .totalEnergyEu()
                .doubleValue(),
            source.totalEnergyEu()
                .doubleValue(),
            1e-6);
        assertEquals(
            result.source()
                .pressurePa(),
            source.pressurePa(),
            1e-5);
        assertEquals(
            result.receiver()
                .state()
                .temperatureKelvin(),
            receiver.state()
                .temperatureKelvin(),
            1e-7);
    }

    @Test
    void heatExchangeAccountsForElasticEnergyOnBothSides() {
        ChamberVolume volume = new ChamberVolume(1000.0, 0.001, 100000.0, 500.0);
        ClosedFluidChamber hot = ClosedFluidChamber
            .of(PureFluid.water(), BigDecimal.ONE, volume, new BigDecimal("2200000"));
        ClosedFluidChamber cold = ClosedFluidChamber
            .of(PureFluid.water(), BigDecimal.ONE, volume, new BigDecimal("1700000"));
        ClosedFluidChamber.HeatExchange result = hot.exchangeHeat(cold, new BigDecimal("1000000"));
        assertEquals(
            0,
            hot.totalEnergyEu()
                .add(cold.totalEnergyEu())
                .compareTo(
                    result.first()
                        .totalEnergyEu()
                        .add(
                            result.second()
                                .totalEnergyEu())));
        assertTrue(
            result.first()
                .state()
                .temperatureKelvin()
                >= result.second()
                    .state()
                    .temperatureKelvin());
        assertEquals(
            result.first()
                .state()
                .temperatureKelvin(),
            result.second()
                .state()
                .temperatureKelvin(),
            1e-7);
        assertTrue(
            result.first()
                .elasticEnergyEu()
                .compareTo(hot.elasticEnergyEu()) < 0);
        assertTrue(
            result.second()
                .elasticEnergyEu()
                .compareTo(cold.elasticEnergyEu()) > 0);
    }

    @Test
    void vacuumHasNoPreloadEnergyAndGasPressureStillApproachesZero() {
        ChamberVolume volume = new ChamberVolume(1000.0, 0.00001, 100000.0, 2.0);
        ClosedFluidChamber empty = ClosedFluidChamber.empty(PureFluid.water(), volume);
        assertEquals(0.0, empty.pressurePa());
        assertEquals(1000.0, empty.volumeLiters());
        assertEquals(BigDecimal.ZERO, empty.elasticEnergyEu());
        assertEquals(BigDecimal.ZERO, empty.totalEnergyEu());
        assertThrows(IllegalStateException.class, empty::state);
        assertThrows(IllegalArgumentException.class, () -> empty.withHeat(BigDecimal.ONE));
        for (String amount : new String[] { "0.1", "0.000001", "0.000000000001" }) {
            BigDecimal mass = new BigDecimal(amount);
            ClosedFluidChamber gas = ClosedFluidChamber
                .of(PureFluid.water(), mass, volume, mass.multiply(new BigDecimal("2203793.5")));
            assertEquals(207675.0 * mass.doubleValue(), gas.pressurePa(), 1e-10);
            assertEquals(BigDecimal.ZERO, gas.elasticEnergyEu());
            assertEquals(
                450.0,
                gas.state()
                    .temperatureKelvin(),
                1e-7);
        }
    }

    @Test
    void vaporOutflowCanLeaveTheEndStopWithoutDependingOnPacketSize() {
        ChamberVolume volume = new ChamberVolume(1000.0, 0.02, 1000.0, 500.0);
        BigDecimal mass = BigDecimal.valueOf(30000.0 * 1500.0 / (1000.0 * 461.5 * 360.0));
        BigDecimal energy = mass.multiply(new BigDecimal("2064428.5"))
            .add(new BigDecimal("6750"));
        ClosedFluidChamber source = ClosedFluidChamber.of(PureFluid.water(), mass, volume, energy);
        ClosedFluidChamber receiver = ClosedFluidChamber.empty(PureFluid.water(), 50000.0);
        double initialEntropy = source.state()
            .specificEntropyEuPerKgKelvin();
        ClosedFluidChamber.FluidTransfer once = source.transferTo(receiver, new BigDecimal("0.05"));
        assertEquals(0, new BigDecimal("0.05").compareTo(once.transferredMassKg()));
        assertTrue(
            once.source()
                .volumeLiters() < 1500.0);
        assertTrue(
            once.source()
                .volumeLiters() > 1000.0);
        assertEquals(
            initialEntropy,
            once.source()
                .state()
                .specificEntropyEuPerKgKelvin(),
            1e-7);
        for (int i = 0; i < 10; i++) {
            ClosedFluidChamber.FluidTransfer step = source.transferTo(receiver, new BigDecimal("0.005"));
            assertEquals(0, new BigDecimal("0.005").compareTo(step.transferredMassKg()));
            source = step.source();
            receiver = step.receiver();
            assertEquals(
                0,
                energy.compareTo(
                    source.totalEnergyEu()
                        .add(receiver.totalEnergyEu())));
        }
        assertEquals(
            once.source()
                .totalEnergyEu()
                .doubleValue(),
            source.totalEnergyEu()
                .doubleValue(),
            1e-6);
        assertEquals(
            once.source()
                .pressurePa(),
            source.pressurePa(),
            1e-5);
        assertEquals(
            once.receiver()
                .state()
                .temperatureKelvin(),
            receiver.state()
                .temperatureKelvin(),
            1e-7);
    }

    @Test
    void liquidTransferChargesTheReceiverSpringWithoutOvershootingPressure() {
        ChamberVolume volume = new ChamberVolume(1.043, 0.00001, 100000.0, 2.0);
        ClosedFluidChamber source = liquid(volume, 200000.0, 350.0);
        ClosedFluidChamber receiver = liquid(volume, 150000.0, 350.0);
        ClosedFluidChamber.FluidTransfer result = source.transferTo(receiver, BigDecimal.ONE);
        assertTrue(
            result.transferredMassKg()
                .signum() > 0);
        assertTrue(
            result.transferredMassKg()
                .compareTo(BigDecimal.ONE) < 0);
        assertTrue(
            result.source()
                .pressurePa()
                >= result.receiver()
                    .pressurePa());
        assertEquals(
            result.source()
                .pressurePa(),
            result.receiver()
                .pressurePa(),
            1e-5);
        assertTrue(
            result.receiver()
                .elasticEnergyEu()
                .compareTo(receiver.elasticEnergyEu()) > 0);
        assertEquals(
            0,
            source.totalEnergyEu()
                .add(receiver.totalEnergyEu())
                .compareTo(
                    result.source()
                        .totalEnergyEu()
                        .add(
                            result.receiver()
                                .totalEnergyEu())));
    }

    private static ClosedFluidChamber liquid(ChamberVolume volume, double pressure, double temperature) {
        double displacement = Math.min(
            volume.maximumDisplacementLiters(),
            Math.max(0.0, (pressure - volume.activationPressurePa()) * volume.complianceLitersPerPa()));
        double fluidVolume = volume.baseLiters() + displacement;
        double mass = fluidVolume * (2.2e9 + pressure) / (1.043 * 2.2001e9);
        double enthalpy = 4180.0 * (temperature - 373.15)
            + 0.001 * 1.043 * 2.2001e9 * Math.log1p((pressure - 100000.0) / 2.2001e9);
        double springEnergy = 0.001 * displacement
            * (volume.activationPressurePa() + 0.5 * displacement / volume.complianceLitersPerPa());
        double energy = mass * enthalpy - 0.001 * pressure * fluidVolume + springEnergy;
        return ClosedFluidChamber.of(PureFluid.water(), BigDecimal.valueOf(mass), volume, BigDecimal.valueOf(energy));
    }
}
