package gregtech.api.metatileentity.implementations.integratedfluid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IFNMachineThermoTest {

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void reservoirAtTheSameTemperatureRequiresLessWorkThanAnOpposingLift(boolean heating) {
        double start = heating ? 390 : 210;
        double end = heating ? 400 : 200;
        var opposing = IFNMachineThermo.computeReservoirHeatPumpMetrics(start, end, 300, heating);
        var favorable = IFNMachineThermo.computeReservoirHeatPumpMetrics(start, end, end, heating);
        assertTrue(favorable.effectiveCop() > opposing.effectiveCop());
        assertTrue(opposing.effectiveCop() <= end / Math.abs(end - 300));
        assertEquals(end, IFNMachineThermo.computeReservoirTargetCopOutputTemperature(300, opposing.cop(), heating), 1e-6);
    }
}
