package gregtech.api.metatileentity.implementations.integratedfluid.thermodynamics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class ClosedFluidChamberTransferTest {

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
            .of(PureFluid.water(), BigDecimal.ONE, 50000.0, new BigDecimal("2203793.5"));
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
