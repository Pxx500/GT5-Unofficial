package gregtech.common.tileentities.machines.multi.radiator;

public final class RadiatorThermo {

    public static final float AMBIENT_TEMPERATURE = 300.0f;
    public static final float PRESSURE_DROP_PER_SEGMENT_BAR = 0.01f;
    private static final double CONDUCTION_FIN_BONUS_FACTOR = 0.35d;

    private RadiatorThermo() {}

    public static double computeEffectiveConductance(RadiatorLoopSnapshot snapshot, double meanTemperatureK) {
        if (snapshot == null || !snapshot.valid) {
            return 0.0d;
        }

        double total = 0.0d;
        for (RadiatorLoopSnapshot.ThermalBranch branch : snapshot.surfaceBranches) {
            total += computeBranchConductance(branch, meanTemperatureK);
        }
        return total;
    }

    private static double computeBranchConductance(RadiatorLoopSnapshot.ThermalBranch branch, double meanTemperatureK) {
        if (branch == null) {
            return 0.0d;
        }

        if (branch.exchangerLeaf) {
            return branch.conductiveCoefficient + branch.radiativeCoefficient * Math.pow(meanTemperatureK, 3.0d);
        }

        double childConductance = 0.0d;
        for (RadiatorLoopSnapshot.ThermalBranch child : branch.children) {
            childConductance += computeBranchConductance(child, meanTemperatureK);
        }
        if (childConductance <= 0.0d || branch.conductiveCoefficient <= 0.0d) {
            return 0.0d;
        }

        // Conduction modules act like fin extenders: they may add diminishing-return transfer
        // capability, but should never reduce the effectiveness of the exchanger subtree they carry.
        double transmittedConductance = (branch.conductiveCoefficient * childConductance)
            / (branch.conductiveCoefficient + childConductance);
        return childConductance + transmittedConductance * CONDUCTION_FIN_BONUS_FACTOR;
    }
}
