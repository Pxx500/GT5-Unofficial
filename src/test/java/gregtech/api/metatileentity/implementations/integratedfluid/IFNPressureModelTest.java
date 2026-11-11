package gregtech.api.metatileentity.implementations.integratedfluid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraftforge.fluids.Fluid;

import org.junit.jupiter.api.Test;

class IFNPressureModelTest {

    @Test
    void idealGasPressureScalesWithSubstanceTemperatureAndContainerVolume() {
        Fluid gas = IFNTestSupport.vaporFluid();
        IntegratedFluidNetwork network = IFNTestSupport.newNetwork(gas, 1_000, 0, 20.0f);
        IntegratedFluidNetwork larger = IFNTestSupport.newNetwork(gas, 2_000, 0, 20.0f);
        long amountQ = 100L * IntegratedFluidNetwork.AMOUNT_SCALE;
        long enthalpyQ = 30_000L * IntegratedFluidNetwork.ENTHALPY_SCALE;
        assertTrue(network.addState(gas, amountQ, enthalpyQ));
        assertTrue(larger.addState(gas, amountQ, enthalpyQ));

        assertEquals(1.0f, network.getPressure(), 0.0001f);
        assertEquals(0.5f, larger.getPressure(), 0.0001f);
        network.setTemperature(600.0f);
        assertEquals(2.0f, network.getPressure(), 0.0001f);
        assertTrue(network.addState(gas, amountQ, 60_000L * IntegratedFluidNetwork.ENTHALPY_SCALE));
        assertEquals(4.0f, network.getPressure(), 0.0001f);
    }

    @Test
    void steamPressureAndOccupiedVolumeDescribeTheSameNetworkState() {
        Fluid water = IFNTestSupport.waterFluid();
        IntegratedFluidNetwork network = IFNTestSupport.newNetwork(water, 10_000, 0, 100.0f);
        long amountQ = 5L * IntegratedFluidNetwork.AMOUNT_SCALE;
        long enthalpyQ = 20_000L * IntegratedFluidNetwork.ENTHALPY_SCALE;

        assertTrue(network.addState(water, amountQ, enthalpyQ));

        assertEquals(IntegratedFluidThermoModel.Phase.GAS, network.getPhase());
        assertEquals(
            10_000.0d,
            network.getSolvedState()
                .occupiedVolumeLiters(),
            0.1d);
        assertEquals(amountQ, network.getAmountQ());
        assertEquals(enthalpyQ, network.getEnthalpyQ());
    }

    @Test
    void pressureModelMatchesNetworkGasStateAddPrediction() {
        Fluid vapor = IFNTestSupport.vaporFluid();
        IntegratedFluidNetwork network = IFNTestSupport.newNetwork(vapor, 1_000, 100, 10.0f);
        long initialAmountQ = 100L * IntegratedFluidNetwork.AMOUNT_SCALE;
        long initialEnthalpyQ = IntegratedFluidNetwork.toEnthalpyQFromSpecific(350.0d, initialAmountQ);
        network.addState(vapor, initialAmountQ, initialEnthalpyQ);

        long addAmountQ = 50L * IntegratedFluidNetwork.AMOUNT_SCALE;
        long addEnthalpyQ = IntegratedFluidNetwork.toEnthalpyQFromSpecific(450.0d, addAmountQ);
        IFNFluidState predictedState = network.snapshotState()
            .withAddedState(vapor, addAmountQ, addEnthalpyQ, network.getPressure());
        IFNPressureModel.NetworkLimits limits = IFNPressureModel.NetworkLimits.of(
            network.getBaseCapacity(),
            network.getAccumulatorCapacity(),
            network.getTotalCapacity(),
            network.getAccumulatorMaxPressureBar(),
            false);

        IFNPressureModel.PressureResult result = IFNPressureModel.compute(vapor, predictedState, limits);

        assertEquals(
            network.predictPressureAfterStateAdd(vapor, addAmountQ, addEnthalpyQ),
            result.getPressureBar(),
            0.0001f);
    }

    @Test
    void pressureModelFlagsLiquidRuptureWhenCompleteNetworkExceedsLimit() {
        Fluid liquid = IFNTestSupport.liquidFluid();
        long amountQ = 250L * IntegratedFluidNetwork.AMOUNT_SCALE;
        long enthalpyQ = IntegratedFluidNetwork.toEnthalpyQFromSpecific(300.0d, amountQ);
        IFNFluidState state = new IFNFluidState(liquid.getName(), amountQ, enthalpyQ, 1.0f);
        IFNPressureModel.NetworkLimits limits = IFNPressureModel.NetworkLimits.of(100, 100, 200, 1.5f, false);

        IFNPressureModel.PressureResult result = IFNPressureModel.compute(liquid, state, limits);

        assertTrue(result.isRuptureRequired());
    }

    @Test
    void networkPressureUpdateDoesNotRuptureImmediately() {
        Fluid liquid = IFNTestSupport.liquidFluid();
        IntegratedFluidNetwork network = IFNTestSupport.newNetwork(liquid, 100, 100, 1.5f);
        long amountQ = 250L * IntegratedFluidNetwork.AMOUNT_SCALE;
        long enthalpyQ = IntegratedFluidNetwork.toEnthalpyQFromSpecific(300.0d, amountQ);

        network.addState(liquid, amountQ, enthalpyQ);

        assertTrue(
            !network.getCanonicalState()
                .isEmpty());
        assertTrue(
            network.getPressureLimitEvaluation()
                .isRuptureRequired());
    }
}
