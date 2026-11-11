package gregtech.api.metatileentity.implementations.integratedfluid.thermodynamics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class ClosedFluidChamberFreezingTest {

    @Test
    void removingLatentHeatFreezesWaterWithoutLoweringItsMeltingTemperature() {
        ClosedFluidChamber liquid = ClosedFluidChamber
            .of(PureFluid.water(), BigDecimal.ONE, 1.043, new BigDecimal("-414551.3"));
        BigDecimal heat = new BigDecimal("103511.2");
        ClosedFluidChamber freezing = liquid.withHeat(heat.negate());
        assertEquals(
            273.16,
            freezing.state()
                .temperatureKelvin(),
            1e-7);
        assertEquals(
            100000.0 / 333500.0,
            freezing.state()
                .solidMassFraction(),
            1e-9);
        assertEquals(
            0,
            liquid.totalEnergyEu()
                .subtract(heat)
                .compareTo(freezing.totalEnergyEu()));
        ClosedFluidChamber restored = freezing.withHeat(heat);
        assertEquals(liquid.totalEnergyEu(), restored.totalEnergyEu());
        assertEquals(liquid.state(), restored.state());
    }

    @Test
    void fullyFrozenContentsBlockFlowButCanBeThawedUsingHeatAlone() {
        ClosedFluidChamber liquid = ClosedFluidChamber
            .of(PureFluid.water(), BigDecimal.ONE, 1.043, new BigDecimal("-414551.3"));
        BigDecimal removedHeat = new BigDecimal("400000");
        ClosedFluidChamber frozen = liquid.withHeat(removedHeat.negate());
        assertEquals(
            1.0,
            frozen.state()
                .solidMassFraction());
        assertTrue(
            frozen.state()
                .temperatureKelvin() < 273.16);
        ClosedFluidChamber empty = ClosedFluidChamber.empty(PureFluid.water(), 1.043);
        assertEquals(
            BigDecimal.ZERO,
            frozen.transferTo(empty, new BigDecimal("0.01"))
                .transferredMassKg());
        ClosedFluidChamber restored = frozen.withHeat(removedHeat);
        assertEquals(liquid.state(), restored.state());
        assertEquals(liquid.massKg(), restored.massKg());
        assertEquals(liquid.totalEnergyEu(), restored.totalEnergyEu());
        assertTrue(
            restored.transferTo(empty, new BigDecimal("0.01"))
                .transferredMassKg()
                .signum() > 0);
    }

    @Test
    void aWarmChamberCanMeltBlockedContentsWithoutTransferringMaterial() {
        ClosedFluidChamber frozen = ClosedFluidChamber
            .of(PureFluid.water(), BigDecimal.ONE, 1.043, new BigDecimal("-770198.5"));
        ClosedFluidChamber warm = ClosedFluidChamber
            .of(PureFluid.water(), BigDecimal.ONE, 1.043, new BigDecimal("-305871.3"));
        ClosedFluidChamber.HeatExchange result = warm.exchangeHeat(frozen, new BigDecimal("1000000"));
        assertEquals(
            273.16,
            result.first()
                .state()
                .temperatureKelvin(),
            1e-7);
        assertEquals(
            273.16,
            result.second()
                .state()
                .temperatureKelvin(),
            1e-7);
        assertTrue(
            result.second()
                .state()
                .solidMassFraction() < 1.0);
        assertTrue(
            result.second()
                .state()
                .solidMassFraction() > 0.0);
        assertEquals(
            0,
            warm.totalEnergyEu()
                .add(frozen.totalEnergyEu())
                .compareTo(
                    result.first()
                        .totalEnergyEu()
                        .add(
                            result.second()
                                .totalEnergyEu())));
        assertEquals(
            BigDecimal.ONE,
            result.first()
                .massKg());
        assertEquals(
            BigDecimal.ONE,
            result.second()
                .massKg());
    }

    @Test
    void tinyLiquidPacketsCanFormIceAndVaporInVacuumWithoutLosingEnergy() {
        ClosedFluidChamber source = ClosedFluidChamber
            .of(PureFluid.water(), BigDecimal.ONE, 1.043, new BigDecimal("-305871.3"));
        ClosedFluidChamber receiver = ClosedFluidChamber.empty(PureFluid.water(), 1.043);
        ClosedFluidChamber.FluidTransfer transfer = source.transferTo(receiver, new BigDecimal("0.000001"));
        assertEquals(0, new BigDecimal("0.000001").compareTo(transfer.transferredMassKg()));
        assertTrue(
            transfer.receiver()
                .state()
                .solidMassFraction() > 0.0);
        assertTrue(
            transfer.receiver()
                .state()
                .vaporMassFraction() > 0.0);
        assertEquals(
            0.0,
            transfer.receiver()
                .state()
                .liquidMassFraction(),
            1e-12);
        assertTrue(
            transfer.receiver()
                .state()
                .temperatureKelvin() < 273.16);
        assertEquals(
            0,
            source.totalEnergyEu()
                .compareTo(
                    transfer.source()
                        .totalEnergyEu()
                        .add(
                            transfer.receiver()
                                .totalEnergyEu())));
        assertEquals(
            0,
            BigDecimal.ONE.compareTo(
                transfer.source()
                    .massKg()
                    .add(
                        transfer.receiver()
                            .massKg())));
    }

    @Test
    void heatingAcrossMeltingPreservesTheEntropyAndHeatRelationship() {
        ClosedFluidChamber chamber = ClosedFluidChamber
            .of(PureFluid.water(), BigDecimal.ONE, 1.043, new BigDecimal("-800000"));
        BigDecimal heat = new BigDecimal("10000");
        for (int i = 0; i < 45; i++) {
            ClosedFluidChamber next = chamber.withHeat(heat);
            double entropyChange = next.state()
                .specificEntropyEuPerKgKelvin()
                - chamber.state()
                    .specificEntropyEuPerKgKelvin();
            assertTrue(
                entropyChange >= heat.doubleValue() / next.state()
                    .temperatureKelvin() - 1e-8);
            assertTrue(
                entropyChange <= heat.doubleValue() / chamber.state()
                    .temperatureKelvin() + 1e-8);
            chamber = next;
        }
        assertEquals(
            0.0,
            chamber.state()
                .solidMassFraction());
        assertTrue(
            chamber.state()
                .temperatureKelvin() > 273.16);
    }

    @Test
    void solidFractionReducesTheRequestedTransferBudgetLinearly() {
        ClosedFluidChamber source = ClosedFluidChamber
            .of(PureFluid.water(), BigDecimal.ONE, 1.043, new BigDecimal("-518062.5"));
        ClosedFluidChamber receiver = ClosedFluidChamber.empty(PureFluid.water(), 1.043);
        BigDecimal budget = new BigDecimal("0.01");
        ClosedFluidChamber.FluidTransfer result = source.transferTo(receiver, budget);
        assertEquals(
            0.01 * (1.0 - 100000.0 / 333500.0),
            result.transferredMassKg()
                .doubleValue(),
            1e-12);
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
            BigDecimal.ONE.compareTo(
                result.source()
                    .massKg()
                    .add(
                        result.receiver()
                            .massKg())));
        assertEquals(
            BigDecimal.ZERO,
            source.transferTo(receiver, budget, 0.2)
                .transferredMassKg());
        assertEquals(
            0.01 * (1.0 - (100000.0 / 333500.0) / 0.5),
            source.transferTo(receiver, budget, 0.5)
                .transferredMassKg()
                .doubleValue(),
            1e-12);
        assertThrows(IllegalArgumentException.class, () -> source.transferTo(receiver, budget, 0.0));
        assertThrows(IllegalArgumentException.class, () -> source.transferTo(receiver, budget, Double.NaN));
    }

    @Test
    void freezingAndThawingDoNotResetStoredAccumulatorWork() {
        ClosedFluidChamber initial = ClosedFluidChamber.of(
            PureFluid.water(),
            new BigDecimal("2"),
            new ChamberVolume(1.043, 0.00001, 100000.0, 2.0),
            new BigDecimal("-100000"));
        assertTrue(
            initial.elasticEnergyEu()
                .signum() > 0);
        BigDecimal heat = new BigDecimal("1500000");
        ClosedFluidChamber frozen = initial.withHeat(heat.negate());
        assertEquals(
            1.0,
            frozen.state()
                .solidMassFraction());
        assertEquals(initial.elasticEnergyEu(), frozen.elasticEnergyEu());
        assertEquals(initial.volumeLiters(), frozen.volumeLiters());
        assertEquals(
            initial.totalEnergyEu(),
            frozen.withHeat(heat)
                .totalEnergyEu());
        assertEquals(
            initial.state(),
            frozen.withHeat(heat)
                .state());
    }
}
