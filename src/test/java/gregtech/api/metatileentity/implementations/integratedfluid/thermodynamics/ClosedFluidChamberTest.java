package gregtech.api.metatileentity.implementations.integratedfluid.thermodynamics;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class ClosedFluidChamberTest {

    @Test
    void aLiquidWithNegativeReferenceEnergyBeginsBoilingWhenHeatedInItsFixedVolume() {
        ClosedFluidChamber liquid = ClosedFluidChamber
            .of(PureFluid.water(), BigDecimal.valueOf(1.0), 1.043, new BigDecimal("-96871.3"));
        assertEquals(
            350.0,
            liquid.state()
                .temperatureKelvin(),
            0.000001);
        assertEquals(
            100000.0,
            liquid.state()
                .pressurePa(),
            0.001);
        assertEquals(
            0.0,
            liquid.state()
                .vaporMassFraction());

        ClosedFluidChamber boiling = liquid.withHeat(new BigDecimal("125400"));
        assertTrue(
            boiling.state()
                .temperatureKelvin() > 373.15);
        assertTrue(
            boiling.state()
                .pressurePa() > 100000.0);
        assertTrue(
            boiling.state()
                .vaporMassFraction() > 0.0);
        assertTrue(
            boiling.state()
                .vaporMassFraction() < 0.001);
        assertEquals(1.043, boiling.volumeLiters());
    }

    @Test
    void aVaporStateUsesInternalEnergyAndTheLiterPressureConversion() {
        ClosedFluidChamber chamber = ClosedFluidChamber
            .of(PureFluid.water(), BigDecimal.valueOf(1.0), 1000.0, new BigDecimal("2203793.5"));
        assertEquals(
            450.0,
            chamber.state()
                .temperatureKelvin(),
            0.000001);
        assertEquals(
            207675.0,
            chamber.state()
                .pressurePa(),
            0.001);
        assertEquals(
            1.0,
            chamber.state()
                .vaporMassFraction());
        assertEquals(
            2411468.5,
            chamber.state()
                .specificEnthalpyEuPerKg(),
            0.000001);
    }

    @Test
    void subdividingHeatDoesNotChangeTheFinalPhaseOrEnergy() {
        ClosedFluidChamber initial = ClosedFluidChamber
            .of(PureFluid.water(), BigDecimal.valueOf(1.0), 861.565125, new BigDecimal("1042343.4875"));
        BigDecimal packet = new BigDecimal("11576.565125");
        ClosedFluidChamber stepped = initial;
        for (int i = 0; i < 100; i++) {
            stepped = stepped.withHeat(packet);
        }
        ClosedFluidChamber once = initial.withHeat(packet.multiply(new BigDecimal("100")));
        assertEquals(once.internalEnergyEu(), stepped.internalEnergyEu());
        assertEquals(once.state(), stepped.state());
    }

    @Test
    void smallHeatIncrementsRemainInTheLedgerEvenWhenPropertiesCannotResolveThem() {
        BigDecimal energy = new BigDecimal("1042343487500000000");
        ClosedFluidChamber initial = ClosedFluidChamber
            .of(PureFluid.water(), BigDecimal.valueOf(1e12), 861565125000000.0, energy);
        BigDecimal heat = new BigDecimal("0.125");
        ClosedFluidChamber heated = initial.withHeat(heat);
        assertEquals(0, new BigDecimal("1042343487500000000.125").compareTo(heated.internalEnergyEu()));
        assertEquals(
            0,
            energy.compareTo(
                heated.withHeat(heat.negate())
                    .internalEnergyEu()));
    }

    @Test
    void unsupportedHeatingOrCoolingCannotMutateTheOriginalInventory() {
        BigDecimal energy = new BigDecimal("1042343.4875");
        ClosedFluidChamber initial = ClosedFluidChamber
            .of(PureFluid.water(), BigDecimal.valueOf(1.0), 861.565125, energy);
        PureFluid.State state = initial.state();
        assertThrows(IllegalArgumentException.class, () -> initial.withHeat(new BigDecimal("4000000")));
        assertThrows(IllegalArgumentException.class, () -> initial.withHeat(new BigDecimal("-4000000")));
        assertThrows(
            IllegalArgumentException.class,
            () -> ClosedFluidChamber.of(PureFluid.water(), BigDecimal.valueOf(1.0), 0.1, energy));
        assertEquals(energy, initial.internalEnergyEu());
        assertEquals(state, initial.state());
    }

    @Test
    void entropyChangesAgreeWithHeatAcrossLiquidMixtureAndVaporStates() {
        assertEntropyDuringHeating(1.043, new BigDecimal("-300000"), new BigDecimal("10000"), 40);
        assertEntropyDuringHeating(861.565125, new BigDecimal("1042343.4875"), new BigDecimal("50000"), 24);
        assertEntropyDuringHeating(1000.0, new BigDecimal("2203793.5"), new BigDecimal("10000"), 5);
    }

    private static void assertEntropyDuringHeating(double volume, BigDecimal energy, BigDecimal heat, int steps) {
        ClosedFluidChamber chamber = ClosedFluidChamber.of(PureFluid.water(), BigDecimal.valueOf(1.0), volume, energy);
        for (int i = 0; i < steps; i++) {
            ClosedFluidChamber next = chamber.withHeat(heat);
            double entropyChange = next.state()
                .specificEntropyEuPerKgKelvin()
                - chamber.state()
                    .specificEntropyEuPerKgKelvin();
            double minimumEntropyChange = heat.doubleValue() / next.state()
                .temperatureKelvin();
            double maximumEntropyChange = heat.doubleValue() / chamber.state()
                .temperatureKelvin();
            assertTrue(entropyChange >= minimumEntropyChange - 1e-7);
            assertTrue(entropyChange <= maximumEntropyChange + 1e-7);
            chamber = next;
        }
    }

    @Test
    void heatAndItsRemovalCrossTheVaporBoundaryWithoutChangingInventory() {
        // Equal liquid/vapor masses at the model's 373.15 K, 100 kPa reference.
        ClosedFluidChamber initial = assertDoesNotThrow(
            () -> ClosedFluidChamber
                .of(PureFluid.water(), BigDecimal.valueOf(1.0), 861.565125, new BigDecimal("1042343.4875")));
        assertEquals(
            373.15,
            initial.state()
                .temperatureKelvin(),
            0.000001);
        assertEquals(
            100000.0,
            initial.state()
                .pressurePa(),
            0.001);
        assertEquals(
            0.5,
            initial.state()
                .vaporMassFraction(),
            0.000001);

        BigDecimal heat = new BigDecimal("1157656.5125");
        ClosedFluidChamber heated = initial.withHeat(heat);
        assertEquals(0, new BigDecimal("2200000").compareTo(heated.internalEnergyEu()));
        assertEquals(
            1.0,
            heated.state()
                .vaporMassFraction());
        assertTrue(
            heated.state()
                .temperatureKelvin()
                > initial.state()
                    .temperatureKelvin());
        assertTrue(
            heated.state()
                .pressurePa()
                > initial.state()
                    .pressurePa());
        assertEquals(initial.massKg(), heated.massKg());
        assertEquals(initial.volumeLiters(), heated.volumeLiters());

        ClosedFluidChamber restored = heated.withHeat(heat.negate());
        assertEquals(initial.internalEnergyEu(), restored.internalEnergyEu());
        assertEquals(initial.state(), restored.state());
    }
}
