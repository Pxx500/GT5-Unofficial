package gregtech.api.metatileentity.implementations.integratedfluid.thermodynamics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CryogenicFluidReferenceTest {

    // CoolProp reference isobars. Each fluid uses its own saturated liquid enthalpy at 1 bar as zero
    private static ClosedFluidChamber chamber(String fluid, double liters, double internalEu) {
        return ClosedFluidChamber.of(PureFluid.forSubstance(fluid).get(), BigDecimal.ONE, liters, BigDecimal.valueOf(internalEu));
    }

    @ParameterizedTest
    @CsvSource({ "hydrogen,12174.4,2669760,295", "nitrogen,875.376,340699,295", "helium,6130.89,924337.7,295",
        "helium,18697.82,2809478.9,900" })
    void warmGasMatchesTheOneBarReference(String fluid, double liters, double internalEu, double kelvin) {
        var gas = chamber(fluid, liters, internalEu);
        assertEquals(kelvin, gas.state().temperatureKelvin(), 2);
        assertEquals(100000, gas.pressurePa(), 1000);
        assertEquals(1, gas.state().vaporMassFraction());
    }

    @ParameterizedTest
    @CsvSource({ "hydrogen,386.86,185771,20.3244,0.6,15000,15", "nitrogen,110.354,88624,77.2435,1,8000,65" })
    void removingHeatCondensesWetGas(String fluid, double liters, double internalEu, double boilingK,
        double temperatureTolerance, double pressureTolerance, double sinkK) {
        var wet = chamber(fluid, liters, internalEu);
        assertEquals(boilingK, wet.state().temperatureKelvin(), temperatureTolerance);
        // Near boiling the ideal-vapor approximation overpredicts volume, particularly for hydrogen
        assertEquals(100000, wet.pressurePa(), pressureTolerance);
        assertEquals(0.5, wet.state().vaporMassFraction(), 0.06);
        var colder = wet.exchangeWithAmbient(sinkK, 5000, sinkK);
        assertTrue(colder.state().vaporMassFraction() < wet.state().vaporMassFraction());
        assertEquals(wet.massKg(), colder.massKg());
    }

    @Test
    void aDenseHeliumChargeCannotClaimEnergyAcrossTheCriticalModelGap() {
        assertThrows(PureFluid.UnsupportedStateException.class, () -> chamber("helium", 58, 14000));
        for (int energy : new int[] { 13000, 15500 }) {
            var state = chamber("helium", 58, energy).state();
            assertEquals(energy, state.specificInternalEnergyEuPerKg(), 0.000001);
        }
    }

    @Test
    void heliumCondensesWithoutInventingASolidAtTheLowTemperatureLimit() {
        // Saturated liquid helium at 1 bar, with the same enthalpy reference as the warm isobar
        var liquid = chamber("helium", 8.003575, -800.3575);
        assertEquals(4.20983, liquid.state().temperatureKelvin(), 0.001);
        assertEquals(100000, liquid.pressurePa(), 20);
        var wet = chamber("helium", 33.99343, 6923.229);
        assertTrue(wet.state().vaporMassFraction() > 0.25 && wet.state().vaporMassFraction() < 0.65);
        var condensed = wet.exchangeWithAmbient(3, 100000);
        assertTrue(condensed.state().vaporMassFraction() < wet.state().vaporMassFraction());
        assertEquals(wet.massKg(), condensed.massKg());
        var cold = liquid.exchangeWithAmbient(1, 100000);
        assertEquals(2.3, cold.state().temperatureKelvin(), 0.00001);
        assertEquals(0, cold.state().solidMassFraction());
        assertEquals(1, cold.state().liquidMassFraction(), 0.001);
    }
}
