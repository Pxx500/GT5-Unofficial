package gregtech.api.metatileentity.implementations.integratedfluid.thermodynamics;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @ParameterizedTest
    @CsvSource({ "nitrogen,5,-55000,15000,20000,63.151", "oxygen,3.3333333333333335,-72000,8200,11000,54.36",
        "hydrogen,15.384615384615385,-134000,31500,35000,13.957" })
    void cryogenMeltsAtFixedTemperatureAndRefreezesOnTheSameLedger(String substance, double liters, double initial,
        BigDecimal partialHeat, BigDecimal remainingHeat, double meltingKelvin) {
        var frozen = chamber(substance, liters, initial);
        assertTrue(frozen.state().temperatureKelvin() < meltingKelvin && frozen.state().solidMassFraction() > 0.99);
        var melting = frozen.withHeat(partialHeat);
        assertEquals(meltingKelvin, melting.state().temperatureKelvin(), 1e-8);
        assertTrue(melting.state().solidMassFraction() > 0.4 && melting.state().liquidMassFraction() > 0.4);
        var thawed = melting.withHeat(remainingHeat);
        assertTrue(thawed.state().temperatureKelvin() > meltingKelvin && thawed.state().solidMassFraction() == 0);
        var returned = thawed.withHeat(partialHeat.add(remainingHeat).negate());
        assertEquals(frozen.state().solidMassFraction(), returned.state().solidMassFraction(), 1e-9);
        assertEquals(frozen.state().specificEntropyEuPerKgKelvin(), returned.state().specificEntropyEuPerKgKelvin(), 1e-6);
    }

    @ParameterizedTest
    @CsvSource({ "nitrogen,5,98125.9,80859.6,140,4640095", "oxygen,3.3333333333333335,93296.08,96891.53,170,6940365",
        "hydrogen,33.333333333333336,109445.18,161315.13,34,1452385" })
    void cryogenCanBeHeatedThroughTheCriticalRegionAndReturnToWetGas(String fluid, double liters, double initialEu,
        BigDecimal heat, double kelvin, double pascals) {
        // Reference isochores at 200 kg/m3 for nitrogen, 300 for oxygen and 30 for normal hydrogen
        var wet = chamber(fluid, liters, initialEu);
        assertTrue(wet.state().liquidMassFraction() > 0 && wet.state().vaporMassFraction() > 0);
        var hot = wet.withHeat(heat);
        assertEquals(PureFluid.DenseRegion.SUPERCRITICAL, hot.state().denseRegion());
        assertEquals(kelvin, hot.state().temperatureKelvin(), 6);
        assertEquals(pascals, hot.pressurePa(), 500000);
        var returned = hot.withHeat(heat.negate());
        assertEquals(wet.massKg(), returned.massKg());
        assertEquals(wet.state().vaporMassFraction(), returned.state().vaporMassFraction(), 1e-9);
    }

    @Test
    void diluteHeliumCanBeHeatedAndCooledAcrossTwoBarInASealedVessel() {
        // NIST helium at 300 K and 10 bar occupies 626.133 L/kg
        var gas = chamber("helium", 626.133, 924337.7);
        var cold = gas.exchangeWithAmbient(50, 1e8, 50);
        var warm = cold.exchangeWithAmbient(300, 1e8, 300);
        assertTrue(cold.pressurePa() < 200000);
        assertEquals(300, warm.state().temperatureKelvin(), 0.001);
        assertEquals(1e6, warm.pressurePa(), 10000);
        var returned = warm.exchangeWithAmbient(50, 1e8, 50);
        assertEquals(cold.massKg(), returned.massKg());
        assertEquals(cold.state().specificInternalEnergyEuPerKg(), returned.state().specificInternalEnergyEuPerKg(), 0.001);
    }

    @Test
    void denseHeliumKeepsTheRequestedEnergyAcrossTheCriticalRegion() {
        for (int energy : new int[] { 13000, 14000, 15500 }) {
            var state = chamber("helium", 58, energy).state();
            assertEquals(energy, state.specificInternalEnergyEuPerKg(), 0.000001);
        }
    }

    @Test
    void denseHeliumSharesPaidEnergyWithTheAccumulator() {
        double liters = 1000d / 70;
        var hot = CubicIsotherm.Model.HELIUM.at(8).atVolume(liters);
        var volume = new ChamberVolume(liters - 2, 2 / (hot.pressurePa() - 100000), 100000, 3);
        var energy = BigDecimal.valueOf(hot.specificInternalEnergyEuPerKg() + volume.elasticEnergyEu(hot.pressurePa()));
        var gas = ClosedFluidChamber.of(PureFluid.forSubstance("helium").get(), BigDecimal.ONE, volume, energy);
        assertEquals(8, gas.state().temperatureKelvin(), 1e-7);
        assertEquals(2, volume.displacementAtPressure(gas.pressurePa()), 1e-7);
        assertEquals(PureFluid.DenseRegion.SUPERCRITICAL, gas.state().denseRegion());
        var cold = gas.withHeat(BigDecimal.valueOf(-12000));
        assertTrue(cold.state().vaporMassFraction() > 0 && cold.state().vaporMassFraction() < 1);
        assertEquals(gas.pressurePa(), cold.withHeat(BigDecimal.valueOf(12000)).pressurePa(), 1e-6);
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
        // NIST helium at 2.3 K and 8.003575 L/kg retains a small vapor space as liquid contracts
        assertEquals(0.001731733, cold.state().vaporMassFraction(), 0.0002);
    }
}
