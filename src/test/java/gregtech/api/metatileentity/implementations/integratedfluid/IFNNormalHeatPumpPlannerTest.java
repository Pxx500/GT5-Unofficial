package gregtech.api.metatileentity.implementations.integratedfluid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraftforge.fluids.Fluid;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class IFNNormalHeatPumpPlannerTest {

    @Test
    void targetEnergyPaysForLatentHeatWithoutRequiringATemperatureChange() {
        Fluid water = IFNTestSupport.waterFluid();
        IntegratedFluidNetwork output = IFNTestSupport.newNetwork(water, 10_000, 0, 50.0f);
        IFNMachineBatchPlanner.BatchPlan input = batch(water, 1.0f, 373.15d, 1_000);
        IFNNormalHeatPumpPlanner.Plan plan = IFNNormalHeatPumpPlanner.plan(
            IFNNormalHeatPumpPlanner.Request
                .of(IFNHeatPumpMode.TARGET_ENERGY, output, water, input, true, 400.0f, 4.0f, 100, 0.5f, 0.5f, 300.0f));

        assertEquals(IFNHeatPumpPlanStatus.READY, plan.getStatus());
        assertEquals(373.15d, plan.getOutputTemperature(), 0.001d);
        assertTrue(plan.getOutputSpecificEnthalpy() > input.specificEnthalpy());
        FluidThermalProperties.PhaseResult phase = FluidThermalProperties
            .getPhaseFromPH(water, 1.0d, plan.getOutputSpecificEnthalpy());
        assertEquals(FluidThermalProperties.Phase.TWO_PHASE, phase.phase);
        assertTrue(phase.quality > 0.0d && phase.quality < 1.0d);
        double heat = (plan.getOutputSpecificEnthalpy() - input.specificEnthalpy()) * 1_000.0d;
        double work = heat / plan.getMetrics()
            .effectiveCop();
        assertTrue(work <= 2_000.0d + 1e-6d);
        assertTrue(work >= 1_999.0d);
    }

    @ParameterizedTest
    @CsvSource({ "true,600,610,500", "false,100,90,150" })
    void smallerReservoirLiftReducesWork(boolean heating, double inputTemperature, float targetTemperature,
        float easierReservoir) {
        Fluid fluid = IFNTestSupport.liquidFluid();
        IntegratedFluidNetwork output = IFNTestSupport.newNetwork(fluid, 10_000, 0, 50.0f);
        IFNMachineBatchPlanner.BatchPlan input = batch(fluid, 1.0f, inputTemperature, 1_000);
        IFNNormalHeatPumpPlanner.Plan harder = IFNNormalHeatPumpPlanner.plan(
            IFNNormalHeatPumpPlanner.Request.of(
                IFNHeatPumpMode.TARGET_TEMPERATURE,
                output,
                fluid,
                input,
                heating,
                targetTemperature,
                4.0f,
                100,
                0.5f,
                0.5f,
                300.0f));
        IFNNormalHeatPumpPlanner.Plan easier = IFNNormalHeatPumpPlanner.plan(
            IFNNormalHeatPumpPlanner.Request.of(
                IFNHeatPumpMode.TARGET_TEMPERATURE,
                output,
                fluid,
                input,
                heating,
                targetTemperature,
                4.0f,
                100,
                0.5f,
                0.5f,
                easierReservoir));

        assertEquals(IFNHeatPumpPlanStatus.READY, harder.getStatus());
        assertEquals(IFNHeatPumpPlanStatus.READY, easier.getStatus());
        assertEquals(harder.getOutputSpecificEnthalpy(), easier.getOutputSpecificEnthalpy());
        assertTrue(easier.getEnergyCostEu() < harder.getEnergyCostEu());
        if (!heating) {
            assertTrue(
                harder.getMetrics()
                    .cop() < 1.0f);
        }
    }

    @ParameterizedTest
    @CsvSource({ "true,600", "false,100" })
    void targetEnergyCannotExceedTheWorkBudgetAgainstTheReservoir(boolean heating, double inputTemperature) {
        Fluid fluid = IFNTestSupport.liquidFluid();
        IntegratedFluidNetwork output = IFNTestSupport.newNetwork(fluid, 10_000, 10_000, 50.0f);
        IFNMachineBatchPlanner.BatchPlan input = batch(fluid, 1.0f, inputTemperature, 1_000);
        IFNNormalHeatPumpPlanner.Plan plan = IFNNormalHeatPumpPlanner.plan(
            IFNNormalHeatPumpPlanner.Request.of(
                IFNHeatPumpMode.TARGET_ENERGY,
                output,
                fluid,
                input,
                heating,
                300.0f,
                4.0f,
                100,
                0.5f,
                0.5f,
                300.0f));

        assertEquals(IFNHeatPumpPlanStatus.READY, plan.getStatus());
        assertEquals(2_000L, plan.getEnergyCostEu());
        double movedHeat = Math.abs(plan.getOutputSpecificEnthalpy() - input.specificEnthalpy()) * 1_000.0d;
        double minimumWorkPerHeat = heating ? 1.0d - 300.0d / inputTemperature : 300.0d / inputTemperature - 1.0d;
        assertTrue(movedHeat > 0.0d);
        assertTrue(movedHeat * minimumWorkPerHeat <= 2_000.0d + 1e-6d);
        assertTrue(plan.getOutputTemperature() > 0.0d);
        assertTrue(
            movedHeat / plan.getMetrics()
                .effectiveCop() <= 2_000.0d + 1e-6d);
    }

    @ParameterizedTest
    @CsvSource({ "true,310,330,400", "false,280,290,240" })
    void targetCopUsesTheReservoirAndTheCorrectHeatingOrCoolingRatio(boolean heating, double firstInput,
        double secondInput, double expectedOutput) {
        Fluid fluid = IFNTestSupport.liquidFluid();
        IntegratedFluidNetwork output = IFNTestSupport.newNetwork(fluid, 10_000, 10_000, 50.0f);
        for (double inputTemperature : new double[] { firstInput, secondInput }) {
            IFNNormalHeatPumpPlanner.Plan plan = IFNNormalHeatPumpPlanner.plan(
                IFNNormalHeatPumpPlanner.Request.of(
                    IFNHeatPumpMode.TARGET_COP,
                    output,
                    fluid,
                    batch(fluid, 1.0f, inputTemperature, 100),
                    heating,
                    300.0f,
                    4.0f,
                    100,
                    0.5f,
                    0.5f,
                    300.0f));

            assertEquals(IFNHeatPumpPlanStatus.READY, plan.getStatus());
            assertEquals(expectedOutput, plan.getOutputTemperature(), 0.001d);
            assertEquals(
                4.0f,
                plan.getMetrics()
                    .cop(),
                0.001f);
        }
    }

    @Test
    void heatingHotFluidCannotBypassReservoirLiftWithASmallTemperatureStep() {
        Fluid fluid = IFNTestSupport.liquidFluid();
        IntegratedFluidNetwork output = IFNTestSupport.newNetwork(fluid, 10_000, 10_000, 50.0f);
        IFNMachineBatchPlanner.BatchPlan input = batch(fluid, 1.0f, 600.0d, 1_000);

        IFNNormalHeatPumpPlanner.Plan plan = IFNNormalHeatPumpPlanner.plan(
            IFNNormalHeatPumpPlanner.Request.of(
                IFNHeatPumpMode.TARGET_TEMPERATURE,
                output,
                fluid,
                input,
                true,
                610.0f,
                5.0f,
                100,
                0.5f,
                0.5f,
                300.0f));

        assertEquals(IFNHeatPumpPlanStatus.READY, plan.getStatus());
        double addedHeat = (plan.getOutputSpecificEnthalpy() - input.specificEnthalpy()) * 1_000.0d;
        // Even an ideal reversible pump needs at least this much work over 600-610 K.
        assertTrue(plan.getEnergyCostEu() >= addedHeat * (1.0d - 300.0d / 600.0d));
        assertTrue(
            plan.getMetrics()
                .cop() <= 610.0d / (610.0d - 300.0d) + 1e-6d);
    }

    @Test
    void targetTemperatureHeatingPlansOutputStateAndEnergy() {
        Fluid fluid = IFNTestSupport.liquidFluid();
        IntegratedFluidNetwork outputNetwork = IFNTestSupport.newNetwork(fluid, 10_000, 10_000, 50.0f);
        IFNMachineBatchPlanner.BatchPlan inputBatch = batch(fluid, 1.0f, 300.0d, 5);

        IFNNormalHeatPumpPlanner.Plan plan = IFNNormalHeatPumpPlanner.plan(
            IFNNormalHeatPumpPlanner.Request.of(
                IFNHeatPumpMode.TARGET_TEMPERATURE,
                outputNetwork,
                fluid,
                inputBatch,
                true,
                360.0f,
                5.0f,
                100,
                0.5f,
                0.5f,
                300.0f));

        assertEquals(IFNHeatPumpPlanStatus.READY, plan.getStatus());
        assertFalse(plan.isPassthrough());
        assertTrue(plan.isTargetOutputState());
        assertEquals(5L * IntegratedFluidNetwork.AMOUNT_SCALE, plan.getAmountQ());
        assertTrue(plan.getOutputSpecificEnthalpy() > inputBatch.specificEnthalpy());
        assertEquals(360.0d, plan.getOutputTemperature(), 0.001d);
        assertTrue(plan.getEnergyCostEu() > 0L);
        assertTrue(
            plan.getMetrics()
                .effectiveCop() > 0.0f);
    }

    @Test
    void targetTemperatureWithinToleranceBecomesPassthrough() {
        Fluid fluid = IFNTestSupport.liquidFluid();
        IntegratedFluidNetwork outputNetwork = IFNTestSupport.newNetwork(fluid, 10_000, 10_000, 50.0f);
        IFNMachineBatchPlanner.BatchPlan inputBatch = batch(fluid, 1.0f, 300.0d, 5);

        IFNNormalHeatPumpPlanner.Plan plan = IFNNormalHeatPumpPlanner.plan(
            IFNNormalHeatPumpPlanner.Request.of(
                IFNHeatPumpMode.TARGET_TEMPERATURE,
                outputNetwork,
                fluid,
                inputBatch,
                true,
                300.25f,
                5.0f,
                100,
                0.5f,
                0.5f,
                300.0f));

        assertEquals(IFNHeatPumpPlanStatus.PASSTHROUGH, plan.getStatus());
        assertTrue(plan.isPassthrough());
        assertFalse(plan.isTargetOutputState());
        assertEquals(inputBatch.specificEnthalpy(), plan.getOutputSpecificEnthalpy(), 0.001d);
        assertEquals(300.0d, plan.getOutputTemperature(), 0.001d);
        assertEquals(0L, plan.getEnergyCostEu());
        assertEquals(
            0.0f,
            plan.getMetrics()
                .effectiveCop(),
            0.001f);
    }

    @Test
    void targetCopUsesConfiguredCopForDisplayMetrics() {
        Fluid fluid = IFNTestSupport.liquidFluid();
        IntegratedFluidNetwork outputNetwork = IFNTestSupport.newNetwork(fluid, 10_000, 10_000, 50.0f);
        IFNMachineBatchPlanner.BatchPlan inputBatch = batch(fluid, 1.0f, 300.0d, 5);

        IFNNormalHeatPumpPlanner.Plan plan = IFNNormalHeatPumpPlanner.plan(
            IFNNormalHeatPumpPlanner.Request.of(
                IFNHeatPumpMode.TARGET_COP,
                outputNetwork,
                fluid,
                inputBatch,
                true,
                360.0f,
                4.0f,
                100,
                0.5f,
                0.5f,
                300.0f));

        assertEquals(IFNHeatPumpPlanStatus.READY, plan.getStatus());
        assertTrue(plan.getOutputSpecificEnthalpy() > inputBatch.specificEnthalpy());
        assertEquals(
            4.0f,
            plan.getMetrics()
                .cop(),
            0.001f);
        assertEquals(
            4.0f / plan.getMetrics()
                .efficiencyPenalty(),
            plan.getMetrics()
                .effectiveCop(),
            0.001f);
    }

    @Test
    void targetEnergyMovesOutputInConfiguredDirection() {
        Fluid fluid = IFNTestSupport.liquidFluid();
        IntegratedFluidNetwork outputNetwork = IFNTestSupport.newNetwork(fluid, 10_000, 10_000, 50.0f);
        IFNMachineBatchPlanner.BatchPlan inputBatch = batch(fluid, 1.0f, 360.0d, 5);

        IFNNormalHeatPumpPlanner.Plan plan = IFNNormalHeatPumpPlanner.plan(
            IFNNormalHeatPumpPlanner.Request.of(
                IFNHeatPumpMode.TARGET_ENERGY,
                outputNetwork,
                fluid,
                inputBatch,
                false,
                300.0f,
                5.0f,
                100,
                0.5f,
                0.5f,
                300.0f));

        assertEquals(IFNHeatPumpPlanStatus.READY, plan.getStatus());
        assertFalse(plan.isTargetOutputState());
        assertEquals(2_000L, plan.getEnergyCostEu());
        assertTrue(plan.getOutputSpecificEnthalpy() < inputBatch.specificEnthalpy());
        assertTrue(plan.getOutputTemperature() < inputBatch.temperature());
    }

    private static IFNMachineBatchPlanner.BatchPlan batch(Fluid fluid, float pressure, double temperature, int refL) {
        double specificEnthalpy = FluidThermalProperties.getSpecificEnthalpyFromPT(fluid, pressure, temperature);
        long amountQ = refL * IntegratedFluidNetwork.AMOUNT_SCALE;
        return IFNMachineBatchPlanner.planInputBatch(fluid, pressure, specificEnthalpy, amountQ, 1_000.0d);
    }
}
