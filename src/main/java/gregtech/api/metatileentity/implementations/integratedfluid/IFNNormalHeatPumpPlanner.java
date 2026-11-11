package gregtech.api.metatileentity.implementations.integratedfluid;

import net.minecraftforge.fluids.Fluid;

/**
 * Pure thermodynamic planner for single-stream IFN heat pump mode.
 *
 * This class decides the requested output state and energy cost. It does not mutate networks and does not perform
 * pressure acceptance; IFNSingleOutputProcess owns those transactional details.
 */
public final class IFNNormalHeatPumpPlanner {

    private IFNNormalHeatPumpPlanner() {}

    public static Plan plan(Request request) {
        if (request == null || !request.isValid()) {
            return Plan.invalid();
        }

        long amountQ = request.inputBatch.amountQ();
        double inputSpecificEnthalpy = request.inputBatch.specificEnthalpy();
        double inputTemperature = request.inputBatch.temperature();
        if (!Double.isFinite(inputTemperature) || inputTemperature <= 0.0d) {
            return Plan.invalid();
        }

        double outputTemperature = inputTemperature;
        double outputSpecificEnthalpy = inputSpecificEnthalpy;
        long energyCost = 0L;
        boolean passthroughMode = false;
        boolean heatingDirection = true;
        boolean targetOutputState = false;
        IFNMachineThermo.HeatPumpMetrics metrics = zeroMetrics();

        switch (request.mode) {
            case TARGET_TEMPERATURE:
                outputTemperature = request.targetTemperature;
                heatingDirection = outputTemperature - inputTemperature >= 0.0d;
                if (inputTemperature >= request.targetTemperature - request.lowerTemperatureTolerance
                    && inputTemperature <= request.targetTemperature + request.upperTemperatureTolerance) {
                    passthroughMode = true;
                    outputTemperature = inputTemperature;
                    outputSpecificEnthalpy = inputSpecificEnthalpy;
                    energyCost = 0L;
                    metrics = zeroMetrics();
                } else {
                    metrics = IFNMachineThermo.computeReservoirHeatPumpMetrics(
                        inputTemperature,
                        outputTemperature,
                        request.reservoirTemperature,
                        heatingDirection);
                    outputSpecificEnthalpy = IFNMachineThermo.computeTargetSpecificEnthalpyForStateAdd(
                        request.outputNetwork,
                        request.fluid,
                        outputTemperature,
                        amountQ);
                    energyCost = IFNMachineThermo.computeHeatPumpEnergyCost(
                        inputSpecificEnthalpy,
                        outputSpecificEnthalpy,
                        amountQ,
                        metrics.cop(),
                        metrics.efficiencyPenalty());
                    targetOutputState = true;
                }
                break;
            case TARGET_COP:
                outputTemperature = IFNMachineThermo.computeReservoirTargetCopOutputTemperature(
                    request.reservoirTemperature,
                    request.targetCOP,
                    request.targetHeating);
                heatingDirection = request.targetHeating;
                metrics = IFNMachineThermo.computeReservoirHeatPumpMetrics(
                    inputTemperature,
                    outputTemperature,
                    request.reservoirTemperature,
                    heatingDirection);
                outputSpecificEnthalpy = IFNMachineThermo.computeTargetSpecificEnthalpyForStateAdd(
                    request.outputNetwork,
                    request.fluid,
                    outputTemperature,
                    amountQ);
                energyCost = IFNMachineThermo.computeHeatPumpEnergyCost(
                    inputSpecificEnthalpy,
                    outputSpecificEnthalpy,
                    amountQ,
                    metrics.cop(),
                    metrics.efficiencyPenalty());
                targetOutputState = true;
                break;
            case TARGET_ENERGY:
                long targetTotalEnergy = (long) request.targetEnergyPerTick * 20L;
                if (targetTotalEnergy <= 0L) {
                    return Plan.invalid();
                }
                IFNMachineThermo.TargetEnergyState targetEnergyState = IFNMachineThermo
                    .computeReservoirTargetEnergyOutputState(
                        request.outputNetwork,
                        request.fluid,
                        inputTemperature,
                        inputSpecificEnthalpy,
                        amountQ,
                        targetTotalEnergy,
                        request.reservoirTemperature,
                        request.targetHeating);
                outputSpecificEnthalpy = targetEnergyState.specificEnthalpy();
                outputTemperature = targetEnergyState.temperature();
                heatingDirection = request.targetHeating;
                energyCost = targetTotalEnergy;
                metrics = targetEnergyState.metrics();
                break;
            default:
                return Plan.invalid();
        }

        if (heatingDirection) {
            if (outputSpecificEnthalpy < inputSpecificEnthalpy - 1e-6d) {
                return Plan.invalid();
            }
        } else if (outputSpecificEnthalpy > inputSpecificEnthalpy + 1e-6d) {
            return Plan.invalid();
        }

        return new Plan(
            passthroughMode ? IFNHeatPumpPlanStatus.PASSTHROUGH : IFNHeatPumpPlanStatus.READY,
            amountQ,
            outputSpecificEnthalpy,
            outputTemperature,
            energyCost,
            metrics,
            passthroughMode,
            targetOutputState);
    }

    private static IFNMachineThermo.HeatPumpMetrics zeroMetrics() {
        return IFNMachineThermo.heatPumpMetrics(0.0f, 1.0f, 0.0d, 0.0f);
    }

    public static final class Request {

        private final IFNHeatPumpMode mode;
        private final IntegratedFluidNetwork outputNetwork;
        private final Fluid fluid;
        private final IFNMachineBatchPlanner.BatchPlan inputBatch;
        private final boolean targetHeating;
        private final float targetTemperature;
        private final float targetCOP;
        private final int targetEnergyPerTick;
        private final float lowerTemperatureTolerance;
        private final float upperTemperatureTolerance;
        private final float reservoirTemperature;

        private Request(IFNHeatPumpMode mode, IntegratedFluidNetwork outputNetwork, Fluid fluid,
            IFNMachineBatchPlanner.BatchPlan inputBatch, boolean targetHeating, float targetTemperature,
            float targetCOP, int targetEnergyPerTick, float lowerTemperatureTolerance, float upperTemperatureTolerance,
            float reservoirTemperature) {
            this.mode = mode;
            this.outputNetwork = outputNetwork;
            this.fluid = fluid;
            this.inputBatch = inputBatch;
            this.targetHeating = targetHeating;
            this.targetTemperature = targetTemperature;
            this.targetCOP = targetCOP;
            this.targetEnergyPerTick = targetEnergyPerTick;
            this.lowerTemperatureTolerance = lowerTemperatureTolerance;
            this.upperTemperatureTolerance = upperTemperatureTolerance;
            this.reservoirTemperature = reservoirTemperature;
        }

        public static Request of(IFNHeatPumpMode mode, IntegratedFluidNetwork outputNetwork, Fluid fluid,
            IFNMachineBatchPlanner.BatchPlan inputBatch, boolean targetHeating, float targetTemperature,
            float targetCOP, int targetEnergyPerTick, float lowerTemperatureTolerance, float upperTemperatureTolerance,
            float reservoirTemperature) {
            return new Request(
                mode,
                outputNetwork,
                fluid,
                inputBatch,
                targetHeating,
                targetTemperature,
                targetCOP,
                targetEnergyPerTick,
                lowerTemperatureTolerance,
                upperTemperatureTolerance,
                reservoirTemperature);
        }

        private boolean isValid() {
            return mode != null && outputNetwork != null
                && fluid != null
                && inputBatch != null
                && inputBatch.isValid()
                && lowerTemperatureTolerance >= 0.0f
                && upperTemperatureTolerance >= 0.0f
                && Float.isFinite(reservoirTemperature)
                && reservoirTemperature > 0.0f
                && (mode != IFNHeatPumpMode.TARGET_TEMPERATURE
                    || (Float.isFinite(targetTemperature) && targetTemperature > 0.0f))
                && (mode != IFNHeatPumpMode.TARGET_COP || (Float.isFinite(targetCOP) && targetCOP >= 1.1f))
                && (mode != IFNHeatPumpMode.TARGET_ENERGY || targetEnergyPerTick > 0);
        }
    }

    public static final class Plan {

        private final IFNHeatPumpPlanStatus status;
        private final long amountQ;
        private final double outputSpecificEnthalpy;
        private final double outputTemperature;
        private final long energyCostEu;
        private final IFNMachineThermo.HeatPumpMetrics metrics;
        private final boolean passthrough;
        private final boolean targetOutputState;

        private Plan(IFNHeatPumpPlanStatus status, long amountQ, double outputSpecificEnthalpy,
            double outputTemperature, long energyCostEu, IFNMachineThermo.HeatPumpMetrics metrics, boolean passthrough,
            boolean targetOutputState) {
            this.status = status;
            this.amountQ = amountQ;
            this.outputSpecificEnthalpy = outputSpecificEnthalpy;
            this.outputTemperature = outputTemperature;
            this.energyCostEu = energyCostEu;
            this.metrics = metrics;
            this.passthrough = passthrough;
            this.targetOutputState = targetOutputState;
        }

        private static Plan invalid() {
            return new Plan(
                IFNHeatPumpPlanStatus.INVALID_CONFIGURATION,
                0L,
                0.0d,
                0.0d,
                0L,
                zeroMetrics(),
                false,
                false);
        }

        public IFNHeatPumpPlanStatus getStatus() {
            return status;
        }

        public long getAmountQ() {
            return amountQ;
        }

        public double getOutputSpecificEnthalpy() {
            return outputSpecificEnthalpy;
        }

        public double getOutputTemperature() {
            return outputTemperature;
        }

        public long getEnergyCostEu() {
            return energyCostEu;
        }

        public IFNMachineThermo.HeatPumpMetrics getMetrics() {
            return metrics;
        }

        public boolean isPassthrough() {
            return passthrough;
        }

        public boolean isTargetOutputState() {
            return targetOutputState;
        }
    }
}
