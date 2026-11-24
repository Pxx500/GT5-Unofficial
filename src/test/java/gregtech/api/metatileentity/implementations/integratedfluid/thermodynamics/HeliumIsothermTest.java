package gregtech.api.metatileentity.implementations.integratedfluid.thermodynamics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HeliumIsothermTest {

    @Test
    void boilingEndpointsSharePressureAndChemicalPotentialButCarryDifferentHeat() {
        var line = CubicIsotherm.Model.HELIUM.at(4.20983);
        var liquid = line.atVolume(line.liquidLitersPerKg());
        var vapor = line.atVolume(line.vaporLitersPerKg());
        var wet = line.atVolume((line.liquidLitersPerKg() + line.vaporLitersPerKg()) / 2);
        assertEquals(8.003575, line.liquidLitersPerKg(), 0.00001);
        assertEquals(100000, liquid.pressurePa(), 0.1);
        assertEquals(liquid.pressurePa(), vapor.pressurePa(), 0.1);
        assertEquals(0, liquid.specificEnthalpyEuPerKg(), 0.001);
        double latent = vapor.specificEnthalpyEuPerKg() - liquid.specificEnthalpyEuPerKg();
        assertEquals(20645, latent, 1500);
        assertEquals(latent, 4.20983 * (vapor.specificEntropyEuPerKgKelvin() - liquid.specificEntropyEuPerKgKelvin()), 0.001);
        assertEquals(0.5, wet.vaporMassFraction(), 1e-12);
        assertEquals((liquid.specificInternalEnergyEuPerKg() + vapor.specificInternalEnergyEuPerKg()) / 2,
            wet.specificInternalEnergyEuPerKg(), 1e-6);
    }

    @Test
    void denseHeatingUsesOneCaloricReferenceAcrossTheCriticalTemperature() {
        double liters = 1000d / 70;
        var wet = CubicIsotherm.Model.HELIUM.at(5).atVolume(liters);
        var dense = CubicIsotherm.Model.HELIUM.at(8).atVolume(liters);
        // NIST helium isochore, 70 kg/m3. Compare energy differences independently of the reference zero
        assertTrue(wet.vaporMassFraction() > 0 && wet.vaporMassFraction() < 1);
        assertEquals(196235, wet.pressurePa(), 5000);
        assertEquals(755434, dense.pressurePa(), 20000);
        assertEquals(PureFluid.DenseRegion.SUPERCRITICAL, dense.denseRegion());
        assertEquals(10625.27, dense.specificInternalEnergyEuPerKg() - wet.specificInternalEnergyEuPerKg(), 1100);
        for (double t : new double[] { Math.nextDown(5.1953), 5.1953, Math.nextUp(5.1953) }) {
            var state = CubicIsotherm.Model.HELIUM.at(t).atVolume(liters);
            assertTrue(Double.isFinite(state.specificInternalEnergyEuPerKg()) && state.pressurePa() > 0);
        }
    }

    @Test
    void inletAtOneBarDoesNotChooseTheWrongBoilingBranch() {
        var boiling = CubicIsotherm.Model.HELIUM.at(4.20983);
        assertEquals(0, boiling.atPressure(100000, false).specificEnthalpyEuPerKg(), 0.001);
        assertTrue(boiling.atPressure(100000, true).specificEnthalpyEuPerKg() > 20000);
        assertEquals(0, CubicIsotherm.Model.HELIUM.at(4.1).atPressure(100000, true).vaporMassFraction());
        var warm = CubicIsotherm.Model.HELIUM.at(300).atPressure(100000, false);
        assertEquals(1, warm.vaporMassFraction());
        assertEquals(100000, warm.pressurePa(), 0.001);
        assertThrows(PureFluid.UnsupportedStateException.class, () -> boiling.atPressure(0, false));
    }

    @Test
    void expansionUsesHeliumEnthalpyAtEqualEntropy() {
        var fluid = PureFluid.forSubstance("helium").get();
        var inlet = CubicIsotherm.Model.HELIUM.at(8).atPressure(500000, true);
        var exit = fluid.idealExpansionOutlet(inlet, 300000, 8);
        assertEquals(300000, exit.pressurePa(), 0.001);
        assertEquals(inlet.specificEntropyEuPerKgKelvin(), exit.specificEntropyEuPerKgKelvin(), 1e-6);
        // NIST at the same inlet and exhaust pressure: 5439 EU/kg isentropic enthalpy drop
        assertEquals(5439, inlet.specificEnthalpyEuPerKg() - exit.specificEnthalpyEuPerKg(), 500);
        var wet = fluid.idealExpansionOutlet(CubicIsotherm.Model.HELIUM.at(5.21).atPressure(250000, true), 100000, 8);
        assertTrue(wet.vaporMassFraction() > 0 && wet.vaporMassFraction() < 1);
        assertEquals(4.20983, wet.temperatureKelvin(), 1e-6);
    }

    @Test
    void warmReferenceAndUnphysicalCompression() {
        var warm = CubicIsotherm.Model.HELIUM.at(300);
        assertEquals(1e6, warm.atVolume(626.133).pressurePa(), 10000);
        assertThrows(PureFluid.UnsupportedStateException.class, () -> warm.atVolume(1));
    }
}
