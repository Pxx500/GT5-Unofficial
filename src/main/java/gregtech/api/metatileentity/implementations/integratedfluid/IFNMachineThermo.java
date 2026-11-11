package gregtech.api.metatileentity.implementations.integratedfluid;

import net.minecraftforge.fluids.Fluid;

/**
 * Shared thermodynamic helpers for IFN-powered machines.
 *
 * This layer sits above raw network physics. It helps machines compute target
 * process states without duplicating pressure-aware state-add logic.
 */
public final class IFNMachineThermo {

    private static final int ENERGY_BUDGET_SEARCH_ITERATIONS = 32;

    private IFNMachineThermo() {}

    public static double computeTargetSpecificEnthalpyForStateAdd(IntegratedFluidNetwork network, Fluid fluid,
        double targetTemperature, long addAmountQ) {
        if (network == null || fluid == null) {
            return 0.0d;
        }

        if (addAmountQ < IntegratedFluidNetwork.AMOUNT_SCALE) {
            float initialPressure = IFNPressurePolicy.clampMinimum(network.getPressure());
            return FluidThermalProperties.getSpecificEnthalpyFromPT(fluid, initialPressure, targetTemperature);
        }

        float pressureGuess = IFNPressurePolicy.clampMinimum(network.getPressure());
        double specificEnthalpy = FluidThermalProperties
            .getSpecificEnthalpyFromPT(fluid, pressureGuess, targetTemperature);

        for (int pass = 0; pass < IFNPressurePolicy.TARGET_ENTHALPY_CORRECTION_PASSES; pass++) {
            long enthalpyQ = IntegratedFluidNetwork.toEnthalpyQFromSpecific(specificEnthalpy, addAmountQ);
            float predictedPressure = network.predictPressureAfterStateAdd(fluid, addAmountQ, enthalpyQ);
            if (Float.isNaN(predictedPressure) || Float.isInfinite(predictedPressure) || predictedPressure <= 0.0f) {
                return specificEnthalpy;
            }

            double correctedSpecificEnthalpy = FluidThermalProperties
                .getSpecificEnthalpyFromPT(fluid, predictedPressure, targetTemperature);
            if (Math.abs(correctedSpecificEnthalpy - specificEnthalpy) < 1.0e-6d) {
                return correctedSpecificEnthalpy;
            }
            specificEnthalpy = correctedSpecificEnthalpy;
        }

        return specificEnthalpy;
    }

    public static long computeHeatPumpEnergyCost(double inputSpecificEnthalpy, double outputSpecificEnthalpy,
        long amountQ, float cop, float efficiencyPenalty) {
        if (amountQ <= 0L) {
            return 0L;
        }

        double desiredDh = Math.abs(outputSpecificEnthalpy - inputSpecificEnthalpy);
        double amount = (double) amountQ / (double) IntegratedFluidNetwork.AMOUNT_SCALE;
        double desiredQ = desiredDh * amount;
        return (long) Math.ceil(desiredQ / cop * efficiencyPenalty);
    }

    public static long scaleEnergyCost(long originalEnergyCost, long originalAmountQ, long actualAmountQ) {
        if (originalEnergyCost <= 0L || originalAmountQ <= 0L || actualAmountQ <= 0L) {
            return 0L;
        }
        double ratio = actualAmountQ / (double) originalAmountQ;
        return (long) Math.ceil(originalEnergyCost * ratio);
    }

    public static HeatPumpMetrics computeHeatPumpMetrics(double firstTemperature, double secondTemperature) {
        double delta = Math.abs(firstTemperature - secondTemperature);
        float cold = (float) Math.min(firstTemperature, secondTemperature);
        float hot = (float) Math.max(firstTemperature, secondTemperature);
        float cop = FluidThermalProperties.calculateHeatPumpCOP(cold, hot);
        float penalty = FluidThermalProperties.calculateTemperaturePenalty((float) delta);
        return new HeatPumpMetrics(cop, penalty, delta, cop / penalty);
    }

    public static HeatPumpMetrics heatPumpMetrics(float cop, float efficiencyPenalty, double temperatureDelta,
        float effectiveCop) {
        return new HeatPumpMetrics(cop, efficiencyPenalty, temperatureDelta, effectiveCop);
    }

    public static HeatPumpMetrics computeReservoirHeatPumpMetrics(double inputTemperature, double outputTemperature,
        double reservoirTemperature, boolean heating) {
        // Charge against the largest reservoir lift reached by the processed fluid.
        double fluidTemperature = heating ? Math.max(inputTemperature, outputTemperature)
            : Math.min(inputTemperature, outputTemperature);
        double lift = heating ? fluidTemperature - reservoirTemperature : reservoirTemperature - fluidTemperature;
        double carnotCop = lift > 0.0d ? fluidTemperature / lift : FluidThermalProperties.MAX_HEAT_PUMP_COP;
        float cop = (float) Math.min(FluidThermalProperties.MAX_HEAT_PUMP_COP, carnotCop);
        float penalty = FluidThermalProperties.calculateTemperaturePenalty((float) Math.max(0.0d, lift));
        return new HeatPumpMetrics(cop, penalty, Math.abs(outputTemperature - inputTemperature), cop / penalty);
    }

    public static double computeReservoirTargetCopOutputTemperature(double reservoirTemperature, float targetCop,
        boolean heating) {
        return reservoirTemperature * targetCop / (heating ? targetCop - 1.0d : targetCop + 1.0d);
    }

    public static TargetEnergyState computeReservoirTargetEnergyOutputState(IntegratedFluidNetwork outputNetwork,
        Fluid fluid, double inputTemperature, double inputSpecificEnthalpy, long amountQ, long energyBudget,
        double reservoirTemperature, boolean heating) {
        double amount = amountQ / (double) IntegratedFluidNetwork.AMOUNT_SCALE;
        double lowHeat = 0.0d;
        double highHeat = energyBudget * (double) FluidThermalProperties.MAX_HEAT_PUMP_COP;
        TargetEnergyState best = new TargetEnergyState(
            inputSpecificEnthalpy,
            inputTemperature,
            computeReservoirHeatPumpMetrics(inputTemperature, inputTemperature, reservoirTemperature, heating));

        // Search transported enthalpy, not temperature, so latent heat stays inside the same budget.
        for (int iteration = 0; iteration < ENERGY_BUDGET_SEARCH_ITERATIONS; iteration++) {
            double heat = lowHeat + (highHeat - lowHeat) / 2.0d;
            long enthalpyQ = IntegratedFluidNetwork
                .toEnthalpyQFromSpecific(inputSpecificEnthalpy + (heating ? heat : -heat) / amount, amountQ);
            double specificEnthalpy = IntegratedFluidNetwork.toSpecificEnthalpy(enthalpyQ, amountQ);
            double pressure = outputNetwork.predictPressureAfterStateAdd(fluid, amountQ, enthalpyQ);
            double temperature = FluidThermalProperties.getTemperatureFromPH(fluid, pressure, specificEnthalpy);
            HeatPumpMetrics metrics = computeReservoirHeatPumpMetrics(
                inputTemperature,
                temperature,
                reservoirTemperature,
                heating);
            double movedHeat = Math.abs(specificEnthalpy - inputSpecificEnthalpy) * amount;
            double cost = movedHeat / metrics.effectiveCop();
            if (Double.isFinite(temperature) && temperature > 0.0d && Double.isFinite(cost) && cost <= energyBudget) {
                lowHeat = heat;
                best = new TargetEnergyState(specificEnthalpy, temperature, metrics);
            } else {
                highHeat = heat;
            }
        }
        return best;
    }

    public static double computeTargetCopOutputTemperature(double inputTemperature, float targetCop, boolean heating) {
        float effectiveCop = targetCop <= 1.0f ? 1.1f : targetCop;
        double outputTemperature = heating ? (effectiveCop * inputTemperature) / (effectiveCop - 1.0f)
            : inputTemperature * (effectiveCop - 1.0f) / effectiveCop;
        double delta = Math.abs(outputTemperature - inputTemperature);
        if (delta < 0.1d) {
            outputTemperature = inputTemperature + (heating ? 0.1d : -0.1d);
        }
        return outputTemperature;
    }

    public static double computeHeatExchangerTargetCopOutputTemperature(double redInputTemperature,
        double blueInputTemperature, float targetCop, boolean configureRed) {
        float effectiveCop = targetCop <= 1.0f ? 1.1f : targetCop;
        double targetInputTemperature = configureRed ? redInputTemperature : blueInputTemperature;
        double outputTemperature = configureRed ? (effectiveCop * blueInputTemperature) / (effectiveCop - 1.0f)
            : redInputTemperature * (effectiveCop - 1.0f) / effectiveCop;
        double delta = Math.abs(outputTemperature - targetInputTemperature);
        if (delta < 0.1d) {
            outputTemperature = targetInputTemperature + (configureRed ? 0.1d : -0.1d);
        }
        return outputTemperature;
    }

    public static HeatPumpMetrics computeHeatExchangerMetrics(double redInputTemperature, double blueInputTemperature,
        boolean configureRed, double targetInputTemperature, double targetOutputTemperature) {
        double targetTemperatureForCop = configureRed ? targetOutputTemperature : targetInputTemperature;
        float coldForCop = (float) Math.min(blueInputTemperature, targetTemperatureForCop);
        float hotForCop = (float) Math.max(redInputTemperature, targetTemperatureForCop);
        double delta = Math.abs(targetOutputTemperature - targetInputTemperature);
        float cop = FluidThermalProperties.calculateHeatPumpCOP(coldForCop, hotForCop);
        float penalty = FluidThermalProperties.calculateTemperaturePenalty((float) delta);
        return new HeatPumpMetrics(cop, penalty, delta, cop / penalty);
    }

    public static TargetEnergyState computeHeatExchangerTargetEnergyOutputState(Fluid fluid, float outputPressure,
        double redInputTemperature, double blueInputTemperature, boolean configureRed, double targetInputTemperature,
        double targetInputSpecificEnthalpy, long amountQ, long targetTotalEnergy, boolean heating) {
        HeatPumpMetrics metrics = computeHeatExchangerMetrics(
            redInputTemperature,
            blueInputTemperature,
            configureRed,
            targetInputTemperature,
            targetInputTemperature);
        if (fluid == null || amountQ <= 0L || targetTotalEnergy <= 0L) {
            return new TargetEnergyState(targetInputSpecificEnthalpy, targetInputTemperature, metrics);
        }

        double amount = amountQ / (double) IntegratedFluidNetwork.AMOUNT_SCALE;
        double tempEstimate = targetInputTemperature;
        double outputSpecificEnthalpy = targetInputSpecificEnthalpy;

        for (int i = 0; i < 10; i++) {
            double lastTempEstimate = tempEstimate;
            metrics = computeHeatExchangerMetrics(
                redInputTemperature,
                blueInputTemperature,
                configureRed,
                targetInputTemperature,
                tempEstimate);
            double transferredHeat = metrics.effectiveCop() * targetTotalEnergy;
            outputSpecificEnthalpy = targetInputSpecificEnthalpy
                + (heating ? transferredHeat : -transferredHeat) / amount;
            tempEstimate = FluidThermalProperties.getTemperatureFromPH(fluid, outputPressure, outputSpecificEnthalpy);

            if (Double.isNaN(tempEstimate) || Double.isInfinite(tempEstimate)) {
                tempEstimate = lastTempEstimate;
                break;
            }
            if (Math.abs(tempEstimate - lastTempEstimate) < 1e-3) {
                break;
            }
        }

        metrics = computeHeatExchangerMetrics(
            redInputTemperature,
            blueInputTemperature,
            configureRed,
            targetInputTemperature,
            tempEstimate);
        return new TargetEnergyState(outputSpecificEnthalpy, tempEstimate, metrics);
    }

    public static TargetEnergyState computeTargetEnergyOutputState(Fluid fluid, float outputPressure,
        double inputTemperature, double inputSpecificEnthalpy, long amountQ, long targetTotalEnergy, boolean heating) {
        if (fluid == null || amountQ <= 0L || targetTotalEnergy <= 0L) {
            HeatPumpMetrics metrics = new HeatPumpMetrics(1.0f, 1.0f, 0.0d, 1.0f);
            return new TargetEnergyState(inputSpecificEnthalpy, inputTemperature, metrics);
        }

        double amount = amountQ / (double) IntegratedFluidNetwork.AMOUNT_SCALE;
        double tempEstimate = inputTemperature;
        double outputSpecificEnthalpy = inputSpecificEnthalpy;
        HeatPumpMetrics metrics = new HeatPumpMetrics(1.0f, 1.0f, 0.0d, 1.0f);

        for (int i = 0; i < 10; i++) {
            double lastTempEstimate = tempEstimate;
            metrics = computeHeatPumpMetrics(tempEstimate, inputTemperature);
            double transferredHeat = metrics.effectiveCop() * targetTotalEnergy;
            outputSpecificEnthalpy = inputSpecificEnthalpy + (heating ? transferredHeat : -transferredHeat) / amount;
            tempEstimate = FluidThermalProperties.getTemperatureFromPH(fluid, outputPressure, outputSpecificEnthalpy);

            if (Double.isNaN(tempEstimate) || Double.isInfinite(tempEstimate)) {
                tempEstimate = lastTempEstimate;
                break;
            }
            if (Math.abs(tempEstimate - lastTempEstimate) < 1e-3) {
                break;
            }
        }

        return new TargetEnergyState(outputSpecificEnthalpy, tempEstimate, metrics);
    }

    public static final class HeatPumpMetrics {

        private final float cop;
        private final float efficiencyPenalty;
        private final double temperatureDelta;
        private final float effectiveCop;

        private HeatPumpMetrics(float cop, float efficiencyPenalty, double temperatureDelta, float effectiveCop) {
            this.cop = cop;
            this.efficiencyPenalty = efficiencyPenalty;
            this.temperatureDelta = temperatureDelta;
            this.effectiveCop = effectiveCop;
        }

        public float cop() {
            return cop;
        }

        public float efficiencyPenalty() {
            return efficiencyPenalty;
        }

        public double temperatureDelta() {
            return temperatureDelta;
        }

        public float effectiveCop() {
            return effectiveCop;
        }
    }

    public static final class TargetEnergyState {

        private final double specificEnthalpy;
        private final double temperature;
        private final HeatPumpMetrics metrics;

        private TargetEnergyState(double specificEnthalpy, double temperature, HeatPumpMetrics metrics) {
            this.specificEnthalpy = specificEnthalpy;
            this.temperature = temperature;
            this.metrics = metrics;
        }

        public double specificEnthalpy() {
            return specificEnthalpy;
        }

        public double temperature() {
            return temperature;
        }

        public HeatPumpMetrics metrics() {
            return metrics;
        }
    }
}
