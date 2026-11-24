package gregtech.api.metatileentity.implementations.integratedfluid;

/** Reservoir lift and electrical performance of the VFN heat pump. */
public final class IFNMachineThermo {

    private static final float MAX_COP = 50.0f;

    private IFNMachineThermo() {}

    public static HeatPumpMetrics computeReservoirHeatPumpMetrics(double inputTemperature, double outputTemperature,
        double reservoirTemperature, boolean heating) {
        // Charge against the largest reservoir lift reached by the processed fluid
        double fluidTemperature = heating ? Math.max(inputTemperature, outputTemperature)
            : Math.min(inputTemperature, outputTemperature);
        double lift = heating ? fluidTemperature - reservoirTemperature : reservoirTemperature - fluidTemperature;
        double carnotCop = lift > 0.0d ? fluidTemperature / lift : MAX_COP;
        float cop = (float) Math.min(MAX_COP, carnotCop);
        float penalty = liftPenalty((float) Math.max(0.0d, lift));
        return new HeatPumpMetrics(cop, penalty, Math.abs(outputTemperature - inputTemperature), cop / penalty);
    }

    private static float liftPenalty(float kelvin) {
        if (kelvin <= 20) return 1;
        if (kelvin <= 80) return 1 + (kelvin - 20) * 0.00333f;
        float excess = kelvin - 80;
        return Math.min(10, 1.2f + excess * excess / 900);
    }

    public static double computeReservoirTargetCopOutputTemperature(double reservoirTemperature, float targetCop,
        boolean heating) {
        return reservoirTemperature * targetCop / (heating ? targetCop - 1.0d : targetCop + 1.0d);
    }

    public record HeatPumpMetrics(float cop, float efficiencyPenalty, double temperatureDelta, float effectiveCop) {}
}
