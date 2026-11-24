package gregtech.api.metatileentity.implementations.integratedfluid.thermodynamics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class ClosedFluidChamberTransferTest {

    @Test
    void vaporOutletMovesWholeGtSteamPortionsOrNothing() {
        var drum = ClosedFluidChamber.of(PureFluid.water(), BigDecimal.ONE, 861.565125, new BigDecimal("1042343.4875"));
        var empty = ClosedFluidChamber.empty(PureFluid.water(), 2000);
        var tooSmall = drum.meteredVaporTo(empty, BigDecimal.TEN);
        assertTrue(tooSmall.source() == drum && tooSmall.receiver() == empty);
        var enough = drum.meteredVaporTo(empty, BigDecimal.valueOf(20));
        assertEquals(0, new BigDecimal("0.006250").compareTo(enough.transferredMassKg()));
    }

    @Test
    void denseFeedCannotSpendPowerToPumpAFractionOfOneKg() {
        var low = ClosedFluidChamber.of(PureFluid.water(), new BigDecimal("21.301564"), 100, new BigDecimal("37551663.742696"));
        var high = ClosedFluidChamber.of(PureFluid.water(), new BigDecimal("12.894426"), 100, new BigDecimal("35530474.686141"));
        var stroke = low.pumpLiquidTo(high, BigDecimal.ONE, 35e6, 12e6, 40960);
        assertTrue(stroke.source() == low && stroke.receiver() == high);
        assertEquals(0, stroke.shaftWorkEu());
    }

    @Test
    void pressureAtEitherSplitInletStopsBothBranchesWithoutLosingTheRemainder() {
        var drum = ClosedFluidChamber.of(PureFluid.water(), BigDecimal.ONE, 861.565125, new BigDecimal("1042343.4875"));
        var large = ClosedFluidChamber.empty(PureFluid.water(), 1000);
        var small = ClosedFluidChamber.empty(PureFluid.water(), 12);
        var move = drum.splitChargeTo(large, small, 800, 0.25);
        var drawn = drum.massKg().subtract(move.source().massKg());
        assertTrue(drawn.signum() > 0 && drawn.compareTo(new BigDecimal("0.1")) < 0);
        assertEquals(0, drawn.compareTo(move.first().massKg().add(move.second().massKg())));
        assertEquals(0, drawn.multiply(new BigDecimal("0.25")).setScale(6, java.math.RoundingMode.DOWN).compareTo(move.first().massKg()));
        assertEquals(0, drum.totalEnergyEu().compareTo(move.source().totalEnergyEu()
            .add(move.first().totalEnergyEu()).add(move.second().totalEnergyEu())));
        assertTrue(move.source().pressurePa() >= Math.max(move.first().pressurePa(), move.second().pressurePa()));
        var blocked = ClosedFluidChamber.of(PureFluid.water(), BigDecimal.ONE, 1000, new BigDecimal("2203793.5"));
        var refused = drum.splitChargeTo(large, blocked, 800, 0.25);
        assertTrue(refused.source() == drum && refused.first() == large && refused.second() == blocked);
    }

    @Test
    void vaporOutletMetersSaturatedVaporRatherThanBulkMixture() {
        var drum = ClosedFluidChamber.of(PureFluid.water(), BigDecimal.ONE, 861.565125, new BigDecimal("1042343.4875"));
        var empty = ClosedFluidChamber.empty(PureFluid.water(), 2000);
        assertEquals(0.5, drum.state().vaporMassFraction(), 1e-8);
        var move = drum.meteredVaporTo(empty, BigDecimal.valueOf(20));
        // At 373.15 K and 1 bar, 20 L of saturated vapor contains 11613 microkg, not 20 L of bulk mixture
        long expected = (long) (20 * 100000 / (1000 * 461.5 * 373.15) * 1_000_000);
        assertEquals(expected, move.transferredMassKg().movePointRight(6).longValueExact());
        double enthalpy = move.transportedEnergyEu().doubleValue() / move.transferredMassKg().doubleValue();
        assertTrue(enthalpy > 2.24e6 && enthalpy < 2.27e6, "Vapor, not bulk enthalpy");
        assertEquals(1, move.receiver().state().vaporMassFraction());
        assertTrue(move.source().state().temperatureKelvin() < drum.state().temperatureKelvin());
        assertEquals(0, drum.totalEnergyEu().compareTo(move.source().totalEnergyEu().add(move.receiver().totalEnergyEu())));
        assertTrue(entropy(move.source()).add(entropy(move.receiver())).compareTo(entropy(drum)) >= 0);
        var allAvailable = drum.meteredVaporTo(ClosedFluidChamber.empty(PureFluid.water(), 100_000), BigDecimal.valueOf(50_000));
        assertTrue(allAvailable.transferredMassKg().signum() > 0 && allAvailable.transferredMassKg().doubleValue() <= 0.5);
        assertEquals(0, allAvailable.source().massKg().add(allAvailable.receiver().massKg()).compareTo(BigDecimal.ONE));
    }

    @Test
    void vaporOutletCannotDrainCompressedLiquidOrPushAgainstBackpressure() {
        var water = ClosedFluidChamber.of(PureFluid.water(), BigDecimal.ONE, 1.043, new BigDecimal("-100000"));
        var vacuum = ClosedFluidChamber.empty(PureFluid.water(), 100);
        assertTrue(water.pressurePa() > 0);
        assertEquals(0, water.state().vaporMassFraction());
        assertEquals(0, water.meteredVaporTo(vacuum, BigDecimal.TEN).transferredMassKg().signum());
        var mixture = ClosedFluidChamber.of(PureFluid.water(), BigDecimal.ONE, 861.565125, new BigDecimal("1042343.4875"));
        var highPressure = ClosedFluidChamber.of(PureFluid.water(), BigDecimal.ONE, 1000, new BigDecimal("2203793.5"));
        assertTrue(highPressure.pressurePa() > mixture.pressurePa());
        assertEquals(0, mixture.meteredVaporTo(highPressure, BigDecimal.TEN).transferredMassKg().signum());
    }

    @Test
    void dryStageHeadMatchesTheIsentropicGasDropAndStopsAtItsPressureRatio() {
        var steam = ClosedFluidChamber.of(PureFluid.water(), BigDecimal.ONE, 1000, new BigDecimal("2203793.5"));
        var inlet = steam.state();
        double expected = 2010 * 450 * (1 - Math.pow(0.5, 461.5 / 2010));
        double h = inlet.specificEnthalpyEuPerKg();
        assertEquals(expected, h - PureFluid.water().idealExpansionOutlet(inlet, steam.pressurePa() / 2, 8).specificEnthalpyEuPerKg(), 0.000001);
        assertEquals(expected, h - PureFluid.water().idealExpansionOutlet(inlet, steam.pressurePa() / 4, 2).specificEnthalpyEuPerKg(), 0.000001);
    }

    @Test
    void expanderNeedsDrySteamPressureDifferenceAndRoomForWork() {
        var stage = new ClosedFluidChamber.ExpansionStage(8, 0.7);
        var steam = ClosedFluidChamber.of(PureFluid.water(), BigDecimal.ONE, 1000, new BigDecimal("2203793.5"));
        var equal = ClosedFluidChamber.of(PureFluid.water(), BigDecimal.ONE, 1000, steam.totalEnergyEu());
        var empty = ClosedFluidChamber.empty(PureFluid.water(), 1000);
        var wet = ClosedFluidChamber.of(PureFluid.water(), BigDecimal.ONE, 861.565125, new BigDecimal("1042343.4875"));
        var port = BigDecimal.TEN;
        for (var stopped : new ClosedFluidChamber.FluidTransfer[] {
            steam.expandTo(equal, port, stage, 1000), empty.expandTo(steam, port, stage, 1000),
            wet.expandTo(empty, port, stage, 1000), steam.expandTo(empty, port, stage, 0) }) {
            assertEquals(0, stopped.shaftWorkEu());
            assertEquals(0, stopped.transferredMassKg().signum());
        }
        var move = steam.expandTo(empty, port, stage, 96);
        assertTrue(move.shaftWorkEu() > 0 && move.shaftWorkEu() <= 96);
        assertEquals(0, BigDecimal.ONE.compareTo(move.source().massKg().add(move.receiver().massKg())));
        assertEquals(0, steam.totalEnergyEu().compareTo(move.source().totalEnergyEu()
            .add(move.receiver().totalEnergyEu()).add(BigDecimal.valueOf(move.shaftWorkEu()))));
        assertTrue(entropy(move.source()).add(entropy(move.receiver())).compareTo(entropy(steam)) >= 0);
    }

    @Test
    void homogeneousMixtureOutflowIsConsistentAcrossPacketSizes() {
        ClosedFluidChamber source = ClosedFluidChamber
            .of(PureFluid.water(), BigDecimal.ONE, 861.565125, new BigDecimal("1042343.4875"));
        ClosedFluidChamber receiver = ClosedFluidChamber
            .of(PureFluid.water(), BigDecimal.ONE, 1723.13025, new BigDecimal("1042343.4875"));
        ClosedFluidChamber.FluidTransfer once = source.transferTo(receiver, new BigDecimal("0.1"));
        assertEquals(0, new BigDecimal("0.1").compareTo(once.transferredMassKg()));
        for (int i = 0; i < 100; i++) {
            BigDecimal previousEntropy = entropy(source).add(entropy(receiver));
            ClosedFluidChamber.FluidTransfer step = source.transferTo(receiver, new BigDecimal("0.001"));
            assertEquals(0, new BigDecimal("0.001").compareTo(step.transferredMassKg()));
            source = step.source();
            receiver = step.receiver();
            assertTrue(
                entropy(source).add(entropy(receiver))
                    .compareTo(previousEntropy) >= 0);
            assertEquals(
                0,
                new BigDecimal("2").compareTo(
                    source.massKg()
                        .add(receiver.massKg())));
            assertEquals(
                0,
                new BigDecimal("2084686.975").compareTo(
                    source.internalEnergyEu()
                        .add(receiver.internalEnergyEu())));
        }
        assertTrue(
            source.state()
                .vaporMassFraction() > 0.0);
        assertTrue(
            source.state()
                .vaporMassFraction() < 1.0);
        assertTrue(
            receiver.state()
                .vaporMassFraction() > 0.0);
        assertTrue(
            receiver.state()
                .vaporMassFraction() < 1.0);
        assertEquals(
            once.source()
                .state()
                .temperatureKelvin(),
            source.state()
                .temperatureKelvin(),
            0.000001);
        assertEquals(
            once.receiver()
                .state()
                .temperatureKelvin(),
            receiver.state()
                .temperatureKelvin(),
            0.000001);
        assertEquals(
            once.source()
                .internalEnergyEu()
                .doubleValue(),
            source.internalEnergyEu()
                .doubleValue(),
            0.00001);
    }

    @Test
    void aLargeRequestStopsAtPressureEquilibriumInsteadOfDrainingTheSource() {
        ClosedFluidChamber source = vapor();
        ClosedFluidChamber receiver = ClosedFluidChamber.empty(PureFluid.water(), 50000.0);
        ClosedFluidChamber.FluidTransfer result = source.transferTo(receiver, new BigDecimal("1000000"));
        double expectedRemainingMass = Math.pow(0.5, 1548.5 / 2010.0);
        assertEquals(
            expectedRemainingMass,
            result.source()
                .massKg()
                .doubleValue(),
            0.00000001);
        assertEquals(
            1661.4,
            result.source()
                .pressurePa(),
            0.000001);
        assertEquals(
            1661.4,
            result.receiver()
                .pressurePa(),
            0.000001);
        assertTrue(
            result.source()
                .pressurePa()
                >= result.receiver()
                    .pressurePa());
        assertEquals(
            0,
            BigDecimal.ONE.compareTo(
                result.source()
                    .massKg()
                    .add(
                        result.receiver()
                            .massKg())));
        assertEquals(
            0,
            source.internalEnergyEu()
                .compareTo(
                    result.source()
                        .internalEnergyEu()
                        .add(
                            result.receiver()
                                .internalEnergyEu())));
    }

    @Test
    void liquidCanFlashDuringOutflowAndCarryNegativeReferenceEnergy() {
        ClosedFluidChamber source = ClosedFluidChamber
            .of(PureFluid.water(), BigDecimal.ONE, 1.043, new BigDecimal("-305871.3"));
        ClosedFluidChamber receiver = ClosedFluidChamber.empty(PureFluid.water(), 1.043);
        ClosedFluidChamber.FluidTransfer result = source.transferTo(receiver, new BigDecimal("0.01"));
        assertEquals(0, new BigDecimal("0.01").compareTo(result.transferredMassKg()));
        assertTrue(
            result.transportedEnergyEu()
                .signum() < 0);
        assertTrue(
            result.source()
                .state()
                .vaporMassFraction() > 0.0);
        assertTrue(
            result.source()
                .pressurePa()
                >= result.receiver()
                    .pressurePa());
        assertEquals(
            0,
            source.internalEnergyEu()
                .compareTo(
                    result.source()
                        .internalEnergyEu()
                        .add(
                            result.receiver()
                                .internalEnergyEu())));
        assertTrue(
            entropy(result.source()).add(entropy(result.receiver()))
                .compareTo(entropy(source)) >= 0);
    }

    @Test
    void splittingTheSameGasTransferIntoPacketsDoesNotResetSourceEnergy() {
        ClosedFluidChamber source = vapor();
        ClosedFluidChamber receiver = ClosedFluidChamber.empty(PureFluid.water(), 50000.0);
        ClosedFluidChamber.FluidTransfer once = source.transferTo(receiver, new BigDecimal("0.2"));
        for (int i = 0; i < 100; i++) {
            BigDecimal previousEntropy = entropy(source).add(entropy(receiver));
            ClosedFluidChamber.FluidTransfer step = source.transferTo(receiver, new BigDecimal("0.002"));
            assertEquals(0, new BigDecimal("0.002").compareTo(step.transferredMassKg()));
            source = step.source();
            receiver = step.receiver();
            assertEquals(
                0,
                BigDecimal.ONE.compareTo(
                    source.massKg()
                        .add(receiver.massKg())));
            assertEquals(
                0,
                new BigDecimal("2064428.5").compareTo(
                    source.internalEnergyEu()
                        .add(receiver.internalEnergyEu())));
            assertTrue(
                entropy(source).add(entropy(receiver))
                    .compareTo(previousEntropy) >= 0);
        }
        assertEquals(
            once.source()
                .state()
                .temperatureKelvin(),
            source.state()
                .temperatureKelvin(),
            0.000001);
        assertEquals(
            once.receiver()
                .state()
                .temperatureKelvin(),
            receiver.state()
                .temperatureKelvin(),
            0.000001);
        assertEquals(
            once.source()
                .internalEnergyEu()
                .doubleValue(),
            source.internalEnergyEu()
                .doubleValue(),
            0.00001);
    }

    @Test
    void tinyTransfersAreDebitedFromBothExactLedgers() {
        BigDecimal mass = new BigDecimal("1000000000000");
        BigDecimal energy = new BigDecimal("2064428500000000000");
        ClosedFluidChamber source = ClosedFluidChamber.of(PureFluid.water(), mass, 5e16, energy);
        ClosedFluidChamber receiver = ClosedFluidChamber.of(PureFluid.water(), mass, 1e17, energy);
        ClosedFluidChamber.FluidTransfer result = source.transferTo(receiver, new BigDecimal("0.000000001"));
        assertEquals(
            0,
            new BigDecimal("999999999999.999999999").compareTo(
                result.source()
                    .massKg()));
        assertEquals(
            0,
            new BigDecimal("1000000000000.000000001").compareTo(
                result.receiver()
                    .massKg()));
        assertEquals(
            0.0022305685,
            result.transportedEnergyEu()
                .doubleValue(),
            1e-15);
        assertEquals(
            0,
            result.transportedEnergyEu()
                .compareTo(
                    energy.subtract(
                        result.source()
                            .internalEnergyEu())));
        assertEquals(
            0,
            result.transportedEnergyEu()
                .compareTo(
                    result.receiver()
                        .internalEnergyEu()
                        .subtract(energy)));
    }

    @Test
    void blockedDirectionAndUnsupportedReceiverStatesLeaveInputsUntouched() {
        ClosedFluidChamber highPressure = vapor();
        ClosedFluidChamber lowPressure = ClosedFluidChamber
            .of(PureFluid.water(), BigDecimal.ONE, 100000.0, new BigDecimal("2064428.5"));
        ClosedFluidChamber.FluidTransfer blocked = lowPressure.transferTo(highPressure, BigDecimal.ONE);
        assertEquals(BigDecimal.ZERO, blocked.transferredMassKg());
        assertEquals(
            lowPressure.internalEnergyEu(),
            blocked.source()
                .internalEnergyEu());
        assertEquals(
            highPressure.internalEnergyEu(),
            blocked.receiver()
                .internalEnergyEu());

        ClosedFluidChamber hot = ClosedFluidChamber
            .of(PureFluid.water(), BigDecimal.ONE, 50000.0, new BigDecimal("2978043.5"));
        ClosedFluidChamber empty = ClosedFluidChamber.empty(PureFluid.water(), 50000.0);
        ClosedFluidChamber.FluidTransfer unsupported = hot.transferTo(empty, new BigDecimal("0.1"));
        assertEquals(BigDecimal.ZERO, unsupported.transferredMassKg());
        assertEquals(
            hot.massKg(),
            unsupported.source()
                .massKg());
        assertEquals(
            hot.internalEnergyEu(),
            unsupported.source()
                .internalEnergyEu());
        assertTrue(
            unsupported.receiver()
                .isEmpty());
        assertThrows(IllegalArgumentException.class, () -> hot.transferTo(empty, new BigDecimal("-1")));
    }

    @Test
    void anEmptyChamberHasNoInventedThermalStateOrEnergy() {
        ClosedFluidChamber empty = ClosedFluidChamber.empty(PureFluid.water(), 50000.0);
        assertEquals(0.0, empty.pressurePa());
        assertThrows(IllegalStateException.class, empty::state);
        assertThrows(IllegalArgumentException.class, () -> empty.withHeat(BigDecimal.ONE));
        assertEquals(
            BigDecimal.ZERO,
            empty.transferTo(vapor(), BigDecimal.ONE)
                .transferredMassKg());
        assertEquals(
            BigDecimal.ZERO,
            empty.exchangeHeat(vapor(), BigDecimal.ONE)
                .heatToFirstEu());
        assertEquals(
            BigDecimal.ZERO,
            vapor().transferTo(empty, BigDecimal.ZERO)
                .transferredMassKg());
        assertEquals(BigDecimal.ZERO, empty.massKg());
        assertEquals(BigDecimal.ZERO, empty.internalEnergyEu());
    }

    private static ClosedFluidChamber vapor() {
        return ClosedFluidChamber.of(PureFluid.water(), BigDecimal.ONE, 50000.0, new BigDecimal("2064428.5"));
    }

    private static BigDecimal entropy(ClosedFluidChamber chamber) {
        return chamber.isEmpty() ? BigDecimal.ZERO
            : chamber.massKg()
                .multiply(
                    BigDecimal.valueOf(
                        chamber.state()
                            .specificEntropyEuPerKgKelvin()));
    }

    @Test
    void fillingAnEmptyChamberCarriesFlowEnergyAndCoolsTheFiniteSource() {
        ClosedFluidChamber source = ClosedFluidChamber
            .of(PureFluid.water(), BigDecimal.ONE, 50000.0, new BigDecimal("2064428.5"));
        ClosedFluidChamber receiver = ClosedFluidChamber.empty(PureFluid.water(), 50000.0);
        ClosedFluidChamber.FluidTransfer result = source.transferTo(receiver, new BigDecimal("0.1"));

        assertEquals(0, new BigDecimal("0.1").compareTo(result.transferredMassKg()));
        assertEquals(
            0,
            new BigDecimal("0.9").compareTo(
                result.source()
                    .massKg()));
        assertEquals(
            0,
            new BigDecimal("0.1").compareTo(
                result.receiver()
                    .massKg()));
        assertEquals(
            0,
            source.internalEnergyEu()
                .compareTo(
                    result.source()
                        .internalEnergyEu()
                        .add(
                            result.receiver()
                                .internalEnergyEu())));

        double expectedSourceTemperature = 360.0 * Math.pow(0.9, 461.5 / 1548.5);
        double expectedRemainingEnergy = 0.9 * (1506968.5 + 1548.5 * expectedSourceTemperature);
        double expectedTransportedEnergy = 2064428.5 - expectedRemainingEnergy;
        double expectedReceiverTemperature = (expectedTransportedEnergy / 0.1 - 1506968.5) / 1548.5;
        assertEquals(
            expectedSourceTemperature,
            result.source()
                .state()
                .temperatureKelvin(),
            0.000001);
        assertEquals(
            expectedTransportedEnergy,
            result.transportedEnergyEu()
                .doubleValue(),
            0.000001);
        assertEquals(
            expectedReceiverTemperature,
            result.receiver()
                .state()
                .temperatureKelvin(),
            0.000001);
        assertTrue(
            result.receiver()
                .state()
                .temperatureKelvin() > 360.0);
        assertTrue(
            result.source()
                .pressurePa()
                > result.receiver()
                    .pressurePa());
        assertEquals(BigDecimal.ONE, source.massKg());
        assertTrue(receiver.isEmpty());
    }
}
