package gregtech.api.metatileentity.implementations.integratedfluid;

/**
 * Central pressure and transfer policy for Integrated Fluid Network machines.
 *
 * Keep shared thresholds here instead of duplicating them in individual machines. That makes new IFN
 * machines use the same pressure safety model by default.
 */
public final class IFNPressurePolicy {

    public static final float MIN_PRESSURE_BAR = 1.0e-4f;
    public static final float DEFAULT_MAX_PRESSURE_BAR = 10.0f;
    public static final double RUPTURE_PRESSURE_FACTOR = 1.10d;

    public static final float MACHINE_OUTPUT_TO_INPUT_PRESSURE_RATIO = 1.00f;

    static final int FORGE_PRESSURE_MARGIN_PA = 10_000;

    public static final int TRANSFER_SEARCH_ITERATIONS = 35;
    public static final int TARGET_ENTHALPY_CORRECTION_PASSES = 4;

    private IFNPressurePolicy() {}

    public static float clampMinimum(float pressureBar) {
        return Math.max(MIN_PRESSURE_BAR, pressureBar);
    }

    public static double clampMinimum(double pressureBar) {
        return Math.max((double) MIN_PRESSURE_BAR, pressureBar);
    }

    public static boolean exceedsRuptureLimit(double pressureBar, double maxPressureBar) {
        return pressureBar >= maxPressureBar * RUPTURE_PRESSURE_FACTOR;
    }

    public static float injectorCutoffPressureBar() {
        return 1.0f - FORGE_PRESSURE_MARGIN_PA / 100_000.0f;
    }
}
