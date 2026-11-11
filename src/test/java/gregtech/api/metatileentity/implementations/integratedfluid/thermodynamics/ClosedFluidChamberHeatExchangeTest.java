package gregtech.api.metatileentity.implementations.integratedfluid.thermodynamics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class ClosedFluidChamberHeatExchangeTest {

    @Test
    void veryUnequalMassesDoNotAmplifyTheEquilibriumMargin() {
        for (double hotMass : new double[] { 1e-9, 1e12 }) {
            double coldMass = hotMass == 1e-9 ? 1e12 : 1e-9;
            ClosedFluidChamber hot = ClosedFluidChamber.of(
                PureFluid.water(),
                BigDecimal.valueOf(hotMass),
                1000.0 * hotMass,
                new BigDecimal("2203793.5").multiply(BigDecimal.valueOf(hotMass)));
            ClosedFluidChamber cold = ClosedFluidChamber.of(
                PureFluid.water(),
                BigDecimal.valueOf(coldMass),
                1000.0 * coldMass,
                new BigDecimal("2126368.5").multiply(BigDecimal.valueOf(coldMass)));
            ClosedFluidChamber.HeatExchange exchange = hot.exchangeHeat(cold, new BigDecimal("1E100"));
            double expectedTemperature = hotMass > coldMass ? 450.0 : 400.0;
            assertEquals(
                expectedTemperature,
                exchange.first()
                    .state()
                    .temperatureKelvin(),
                0.000001);
            assertEquals(
                expectedTemperature,
                exchange.second()
                    .state()
                    .temperatureKelvin(),
                0.000001);
            assertTrue(
                exchange.first()
                    .state()
                    .temperatureKelvin()
                    >= exchange.second()
                        .state()
                        .temperatureKelvin());
            assertEquals(
                0.000077425,
                exchange.heatToFirstEu()
                    .abs()
                    .doubleValue(),
                1e-15);
            assertEquals(
                0,
                hot.internalEnergyEu()
                    .add(cold.internalEnergyEu())
                    .compareTo(
                        exchange.first()
                            .internalEnergyEu()
                            .add(
                                exchange.second()
                                    .internalEnergyEu())));
        }
    }

    @Test
    void bothLedgersKeepSubUlpHeatAtLargeInventorySizes() {
        ClosedFluidChamber hot = ClosedFluidChamber
            .of(PureFluid.water(), BigDecimal.valueOf(1e12), 1e15, new BigDecimal("2203793500000000000"));
        ClosedFluidChamber cold = ClosedFluidChamber
            .of(PureFluid.water(), BigDecimal.valueOf(1e12), 1e15, new BigDecimal("2126368500000000000"));
        ClosedFluidChamber.HeatExchange exchange = hot.exchangeHeat(cold, new BigDecimal("0.125"));
        assertEquals(new BigDecimal("-0.125"), exchange.heatToFirstEu());
        assertEquals(
            new BigDecimal("2203793499999999999.875"),
            exchange.first()
                .internalEnergyEu());
        assertEquals(
            new BigDecimal("2126368500000000000.125"),
            exchange.second()
                .internalEnergyEu());
    }

    @Test
    void unequalVaporMassesReachTheHeatCapacityWeightedTemperature() {
        ClosedFluidChamber hot = ClosedFluidChamber
            .of(PureFluid.water(), BigDecimal.valueOf(1.0), 1000.0, new BigDecimal("2203793.5"));
        ClosedFluidChamber cold = ClosedFluidChamber
            .of(PureFluid.water(), BigDecimal.valueOf(2.0), 2000.0, new BigDecimal("4252737"));
        ClosedFluidChamber.HeatExchange exchange = hot.exchangeHeat(cold, new BigDecimal("1E100"));

        assertEquals(
            416.6666666667,
            exchange.first()
                .state()
                .temperatureKelvin(),
            0.000001);
        assertEquals(
            416.6666666667,
            exchange.second()
                .state()
                .temperatureKelvin(),
            0.000001);
        assertEquals(
            -51616.6666666667,
            exchange.heatToFirstEu()
                .doubleValue(),
            0.000001);
        assertTrue(
            exchange.first()
                .state()
                .temperatureKelvin()
                >= exchange.second()
                    .state()
                    .temperatureKelvin());
    }

    @Test
    void aLimitedPacketIsDebitedExactlyAndDoesNotDependOnArgumentOrder() {
        ClosedFluidChamber hot = ClosedFluidChamber
            .of(PureFluid.water(), BigDecimal.valueOf(1.0), 1000.0, new BigDecimal("2203793.5"));
        ClosedFluidChamber cold = ClosedFluidChamber
            .of(PureFluid.water(), BigDecimal.valueOf(2.0), 2000.0, new BigDecimal("4252737"));
        BigDecimal budget = new BigDecimal("10000.125");
        ClosedFluidChamber.HeatExchange forward = hot.exchangeHeat(cold, budget);
        ClosedFluidChamber.HeatExchange reverse = cold.exchangeHeat(hot, budget);

        assertEquals(budget.negate(), forward.heatToFirstEu());
        assertEquals(budget, reverse.heatToFirstEu());
        assertEquals(
            new BigDecimal("2193793.375"),
            forward.first()
                .internalEnergyEu());
        assertEquals(
            new BigDecimal("4262737.125"),
            forward.second()
                .internalEnergyEu());
        assertEquals(
            forward.first()
                .internalEnergyEu(),
            reverse.second()
                .internalEnergyEu());
        assertEquals(
            forward.second()
                .internalEnergyEu(),
            reverse.first()
                .internalEnergyEu());
        assertEquals(
            forward.first()
                .state(),
            reverse.second()
                .state());
        assertEquals(
            forward.second()
                .state(),
            reverse.first()
                .state());
    }

    @Test
    void repeatedPacketsConvergeWithoutCreatingEnergyOrReducingTotalEntropy() {
        ClosedFluidChamber hot = ClosedFluidChamber
            .of(PureFluid.water(), BigDecimal.valueOf(2.0), 2000.0, new BigDecimal("4407587"));
        ClosedFluidChamber cold = ClosedFluidChamber
            .of(PureFluid.water(), BigDecimal.valueOf(1.0), 861.565125, new BigDecimal("1042343.4875"));
        BigDecimal initialEnergy = hot.internalEnergyEu()
            .add(cold.internalEnergyEu());
        ClosedFluidChamber.HeatExchange once = hot.exchangeHeat(cold, new BigDecimal("1000000000"));
        BigDecimal budget = new BigDecimal("5000");
        for (int i = 0; i < 200; i++) {
            double initialEntropy = hot.massKg()
                .doubleValue()
                * hot.state()
                    .specificEntropyEuPerKgKelvin()
                + cold.massKg()
                    .doubleValue()
                    * cold.state()
                        .specificEntropyEuPerKgKelvin();
            ClosedFluidChamber.HeatExchange next = hot.exchangeHeat(cold, budget);
            assertTrue(
                next.heatToFirstEu()
                    .abs()
                    .compareTo(budget) <= 0);
            hot = next.first();
            cold = next.second();
            assertEquals(
                0,
                initialEnergy.compareTo(
                    hot.internalEnergyEu()
                        .add(cold.internalEnergyEu())));
            assertTrue(
                hot.state()
                    .temperatureKelvin()
                    >= cold.state()
                        .temperatureKelvin());
            double finalEntropy = hot.massKg()
                .doubleValue()
                * hot.state()
                    .specificEntropyEuPerKgKelvin()
                + cold.massKg()
                    .doubleValue()
                    * cold.state()
                        .specificEntropyEuPerKgKelvin();
            assertTrue(finalEntropy >= initialEntropy - 0.00000001);
        }
        assertEquals(
            once.first()
                .state()
                .temperatureKelvin(),
            hot.state()
                .temperatureKelvin(),
            0.000001);
        assertEquals(
            once.second()
                .state()
                .temperatureKelvin(),
            cold.state()
                .temperatureKelvin(),
            0.000001);
    }

    @Test
    void zeroBudgetAndEqualTemperaturesLeaveBothInventoriesUntouched() {
        ClosedFluidChamber first = ClosedFluidChamber
            .of(PureFluid.water(), BigDecimal.valueOf(1.0), 1000.0, new BigDecimal("2203793.5"));
        ClosedFluidChamber second = ClosedFluidChamber
            .of(PureFluid.water(), BigDecimal.valueOf(2.0), 2000.0, new BigDecimal("4407587"));
        ClosedFluidChamber.HeatExchange equal = first.exchangeHeat(second, new BigDecimal("1000"));
        assertEquals(BigDecimal.ZERO, equal.heatToFirstEu());
        assertEquals(
            first.internalEnergyEu(),
            equal.first()
                .internalEnergyEu());
        assertEquals(
            second.internalEnergyEu(),
            equal.second()
                .internalEnergyEu());
        assertEquals(
            first.state(),
            equal.first()
                .state());
        assertEquals(
            second.state(),
            equal.second()
                .state());

        ClosedFluidChamber cold = first.withHeat(new BigDecimal("-10000"));
        ClosedFluidChamber.HeatExchange zero = first.exchangeHeat(cold, BigDecimal.ZERO);
        assertEquals(BigDecimal.ZERO, zero.heatToFirstEu());
        assertEquals(
            first.internalEnergyEu(),
            zero.first()
                .internalEnergyEu());
        assertEquals(
            cold.internalEnergyEu(),
            zero.second()
                .internalEnergyEu());
        assertEquals(
            first.state(),
            zero.first()
                .state());
        assertEquals(
            cold.state(),
            zero.second()
                .state());
        assertThrows(IllegalArgumentException.class, () -> first.exchangeHeat(cold, new BigDecimal("-1")));
    }

    @Test
    void anOversizedBudgetStaysInsideTheModelAtExtremeSupportedTemperatures() {
        ClosedFluidChamber hot = ClosedFluidChamber
            .of(PureFluid.water(), BigDecimal.valueOf(1.0), 1000.0, new BigDecimal("2281218.5"));
        ClosedFluidChamber cold = ClosedFluidChamber
            .of(PureFluid.water(), BigDecimal.valueOf(1.0), 1.043, new BigDecimal("-414551.3"));
        ClosedFluidChamber.HeatExchange exchange = hot.exchangeHeat(cold, new BigDecimal("1E100"));
        assertTrue(
            exchange.heatToFirstEu()
                .signum() < 0);
        assertTrue(
            exchange.first()
                .state()
                .temperatureKelvin() <= 500.0);
        assertTrue(
            exchange.second()
                .state()
                .temperatureKelvin() >= 274.0 - 0.000001);
        assertTrue(
            exchange.first()
                .state()
                .temperatureKelvin()
                >= exchange.second()
                    .state()
                    .temperatureKelvin());
        assertEquals(
            0,
            new BigDecimal("1866667.2").compareTo(
                exchange.first()
                    .internalEnergyEu()
                    .add(
                        exchange.second()
                            .internalEnergyEu())));
    }

    @Test
    void passiveExchangeCondensesVaporAndWarmsAMixtureWithoutCrossingEquilibrium() {
        ClosedFluidChamber hot = ClosedFluidChamber
            .of(PureFluid.water(), BigDecimal.valueOf(2.0), 2000.0, new BigDecimal("4407587"));
        ClosedFluidChamber cold = ClosedFluidChamber
            .of(PureFluid.water(), BigDecimal.valueOf(1.0), 861.565125, new BigDecimal("1042343.4875"));
        BigDecimal totalEnergy = hot.internalEnergyEu()
            .add(cold.internalEnergyEu());

        ClosedFluidChamber.HeatExchange exchange = hot.exchangeHeat(cold, new BigDecimal("1000000000"));

        assertTrue(
            exchange.heatToFirstEu()
                .signum() < 0);
        assertEquals(
            0,
            totalEnergy.compareTo(
                exchange.first()
                    .internalEnergyEu()
                    .add(
                        exchange.second()
                            .internalEnergyEu())));
        assertEquals(
            0,
            exchange.heatToFirstEu()
                .compareTo(
                    exchange.first()
                        .internalEnergyEu()
                        .subtract(hot.internalEnergyEu())));
        assertEquals(
            0,
            exchange.heatToFirstEu()
                .negate()
                .compareTo(
                    exchange.second()
                        .internalEnergyEu()
                        .subtract(cold.internalEnergyEu())));
        assertTrue(
            exchange.first()
                .state()
                .vaporMassFraction() < 1.0);
        assertTrue(
            exchange.second()
                .state()
                .vaporMassFraction() > 0.5);
        double remainingTemperatureDifference = exchange.first()
            .state()
            .temperatureKelvin()
            - exchange.second()
                .state()
                .temperatureKelvin();
        assertTrue(remainingTemperatureDifference >= 0.0);
        assertTrue(remainingTemperatureDifference < 0.000001);
        assertEquals(
            hot.massKg(),
            exchange.first()
                .massKg());
        assertEquals(
            hot.volumeLiters(),
            exchange.first()
                .volumeLiters());
        assertEquals(
            cold.massKg(),
            exchange.second()
                .massKg());
        assertEquals(
            cold.volumeLiters(),
            exchange.second()
                .volumeLiters());
        assertEquals(new BigDecimal("4407587"), hot.internalEnergyEu());
        assertEquals(new BigDecimal("1042343.4875"), cold.internalEnergyEu());
    }
}
