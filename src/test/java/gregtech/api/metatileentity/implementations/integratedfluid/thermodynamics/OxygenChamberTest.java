package gregtech.api.metatileentity.implementations.integratedfluid.thermodynamics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class OxygenChamberTest {

    // NIST 1 bar isobar. Energies are relative to saturated liquid enthalpy at 90.0621 K
    private static ClosedFluidChamber chamber(double liters, double internalEu) {
        return ClosedFluidChamber.of(PureFluid.oxygen(), BigDecimal.ONE, liters, BigDecimal.valueOf(internalEu));
    }

    @Test
    void roomTemperatureGasMatchesTheMeasuredIsobar() {
        var gas = chamber(765.991, 325100);
        assertEquals(295, gas.state().temperatureKelvin(), 1);
        assertEquals(100000, gas.pressurePa(), 500);
        assertEquals(1, gas.state().vaporMassFraction());
    }

    @Test
    void hotGasEnthalpyRiseStaysWithinSixPercentOfNist() {
        // NIST Shomate ideal-gas enthalpy rise from 298.15 to 700 K, rounded to EU/kg
        var fluid = PureFluid.oxygen();
        double rise = fluid.referenceStreamEnthalpy(700, true) - fluid.referenceStreamEnthalpy(298.15, true);
        assertEquals(390_634, rise, 0.06 * 390_634);
    }

    @Test
    void halfVaporAtOneBarCondensesWhenHeatLeaves() {
        var wet = chamber((0.875813 + 226.580) / 2, (56938.5 - 133670) / 2 + 133582);
        assertEquals(90.0621, wet.state().temperatureKelvin(), 0.5);
        assertEquals(100000, wet.pressurePa(), 4500);
        assertEquals(0.5, wet.state().vaporMassFraction(), 0.02);
        var colder = wet.exchangeWithAmbient(60, 500, 60);
        assertTrue(colder.state().vaporMassFraction() < wet.state().vaporMassFraction());
        assertEquals(wet.massKg(), colder.massKg());
    }

    @Test
    void evacuatedPortTakesItsFirstSubstanceButCannotMixSubstances() {
        var oxygen = chamber(765.991, 325100);
        var empty = ClosedFluidChamber.empty(PureFluid.water(), 5000);
        var move = oxygen.meteredTransferTo(empty, BigDecimal.valueOf(100), Double.MAX_VALUE);
        assertTrue(move.transferredMassKg().signum() > 0);
        assertEquals(0, oxygen.totalEnergyEu().compareTo(move.source().totalEnergyEu().add(move.receiver().totalEnergyEu())));
        var steam = ClosedFluidChamber.of(PureFluid.water(), BigDecimal.ONE, 1000, BigDecimal.valueOf(2242506));
        assertTrue(steam.pressurePa() > move.receiver().pressurePa());
        var refused = steam.meteredTransferTo(move.receiver(), BigDecimal.TEN, Double.MAX_VALUE);
        assertSame(steam, refused.source());
        assertSame(move.receiver(), refused.receiver());
        var split = oxygen.splitChargeTo(empty, ClosedFluidChamber.empty(PureFluid.water(), 5000), 100, 0.25);
        assertTrue(split.first().massKg().signum() > 0);
        assertEquals(0, oxygen.massKg().compareTo(split.source().massKg().add(split.first().massKg()).add(split.second().massKg())));
    }

    @Test
    void denseRoomTemperatureGasIsRejectedOutsideTheProfile() {
        assertThrows(PureFluid.UnsupportedStateException.class, () -> chamber(20, 325100));
    }
}
