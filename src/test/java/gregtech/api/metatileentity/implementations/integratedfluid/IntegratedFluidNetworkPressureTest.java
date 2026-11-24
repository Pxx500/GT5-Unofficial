package gregtech.api.metatileentity.implementations.integratedfluid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraftforge.fluids.Fluid;

import org.junit.jupiter.api.Test;

import gregtech.api.metatileentity.implementations.integratedfluid.state.IFNCanonicalState;

class IntegratedFluidNetworkPressureTest {

    @Test
    void idealGasPressureScalesWithSubstanceTemperatureAndContainerVolume() {
        Fluid water = IFNTestSupport.waterFluid();
        var pipe = IFNTestSupport.newNetwork(water, 1000, 0, 20);
        var wider = IFNTestSupport.newNetwork(water, 2000, 0, 20);
        // One kg of dry steam at 400 K. Flow enthalpy minus pV gives the stored internal energy
        long energy = Math.round(1e6 * (2_257_000 + 2010 * (400 - 373.15) - 461.5 * 400));
        var initial = IFNCanonicalState.fromRaw("water", 1_000_000, energy);
        pipe.replaceCanonicalState(initial);
        wider.replaceCanonicalState(initial);
        assertEquals(1.846, pipe.getPressure(), 1e-5);
        assertEquals(pipe.getPressure() / 2, wider.getPressure(), 1e-5);
        pipe.exchangeThroughRadiator(470, 1e6, 450);
        assertEquals(450, pipe.getTemperature(), 0.001);
        assertEquals(2.07675, pipe.getPressure(), 1e-5);
        assertEquals(1_000_000L, pipe.getAmountQ());
        assertEquals(energy + 77_425_000_000L, pipe.getCanonicalState().totalEnergy().rawUnits(), 10L);
    }

    @Test
    void steamPressureAndOccupiedVolumeDescribeTheSameNetworkState() {
        var network = IFNTestSupport.newNetwork(IFNTestSupport.waterFluid(), 10_000, 0, 100);
        IFNTestSupport.restoreDrySteamAt475K(network, 3);
        var state = network.getSolvedState().orElseThrow();
        assertEquals(1, state.vaporMassFraction());
        assertEquals(10_000, network.getOccupiedVolume(), 0.001);
        double kg = network.getAmountQ() / 1e6;
        assertEquals(kg * 461.5 * state.temperatureKelvin(), state.pressurePa() * 0.001 * network.getOccupiedVolume(), 0.01);
    }

    @Test
    void pressureLimitedTransferUsesTheFinalPressureOfBothChambers() {
        var water = IFNTestSupport.waterFluid();
        var source = IFNTestSupport.newNetwork(water, 1000, 0, 20);
        var receiver = IFNTestSupport.newNetwork(water, 1000, 0, 20);
        IFNTestSupport.restoreDrySteamAt475K(source, 3);
        IFNTestSupport.restoreDrySteamAt475K(receiver, 0.49);
        long mass = source.getAmountQ() + receiver.getAmountQ();
        long energy = source.getCanonicalState().totalEnergy().rawUnits() + receiver.getCanonicalState().totalEnergy().rawUnits();
        assertTrue(source.meterTo(receiver, 1_000_000_000L, 50_000) > 0);
        assertEquals(0.5, receiver.getPressure(), 0.001);
        assertTrue(source.getPressure() < 3 && source.getPressure() > receiver.getPressure());
        assertEquals(mass, source.getAmountQ() + receiver.getAmountQ());
        assertEquals(energy, source.getCanonicalState().totalEnergy().rawUnits() + receiver.getCanonicalState().totalEnergy().rawUnits());
    }

    @Test
    void pressureModelFlagsLiquidRuptureWhenCompleteNetworkExceedsLimit() {
        var network = IFNTestSupport.newNetwork(IFNTestSupport.waterFluid(), 100, 100, 1.5f);
        var volume = network.getTopologySnapshot().chamberVolume().orElseThrow();
        double pascals = 400_000;
        // Invert the liquid bulk modulus at 4 bar, including the stroke of this actual hydrophore
        long mass = Math.round(1e6 * volume.litersAtPressure(pascals) * (2.2e9 + pascals) / (1.043 * 2.2001e9));
        long energy = Math.round(mass * 4180.0 * (300 - 373.15) + 1e6 * volume.elasticEnergyEu(pascals));
        network.replaceCanonicalState(IFNCanonicalState.fromRaw("water", mass, energy));
        assertEquals(1, network.getSolvedState().orElseThrow().liquidMassFraction());
        assertEquals(4, network.getPressure(), 0.001);
        assertTrue(network.getPressureLimitEvaluation().isRuptureRequired());
    }

    @Test
    void networkPressureUpdateDoesNotRuptureImmediately() {
        var network = IFNTestSupport.newNetwork(IFNTestSupport.waterFluid(), 100, 100, 1.5f);
        IFNTestSupport.restoreDrySteamAt475K(network, 4);

        assertTrue(
            !network.getCanonicalState()
                .isEmpty());
        assertTrue(
            network.getPressureLimitEvaluation()
                .isRuptureRequired());
    }
}
