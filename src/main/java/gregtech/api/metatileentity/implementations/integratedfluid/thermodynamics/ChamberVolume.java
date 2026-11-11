package gregtech.api.metatileentity.implementations.integratedfluid.thermodynamics;

/** Fixed pipe volume with optional linear mechanical compliance and a finite stroke. */
public record ChamberVolume(double baseLiters, double complianceLitersPerPa, double activationPressurePa,
    double maximumDisplacementLiters) {

    public ChamberVolume {
        if (!Double.isFinite(baseLiters) || baseLiters <= 0.0
            || !Double.isFinite(complianceLitersPerPa)
            || complianceLitersPerPa < 0.0
            || !Double.isFinite(activationPressurePa)
            || activationPressurePa < 0.0
            || !Double.isFinite(maximumDisplacementLiters)
            || maximumDisplacementLiters < 0.0
            || (complianceLitersPerPa == 0.0) != (maximumDisplacementLiters == 0.0)
            || !Double.isFinite(baseLiters + maximumDisplacementLiters)
            || (complianceLitersPerPa > 0.0
                && !Double.isFinite(activationPressurePa + maximumDisplacementLiters / complianceLitersPerPa))) {
            throw new IllegalArgumentException("Chamber volume and compliance must have finite physical bounds");
        }
    }

    public static ChamberVolume rigid(double liters) {
        return new ChamberVolume(liters, 0.0, 0.0, 0.0);
    }

    double displacementAtPressure(double pressurePa) {
        return Math
            .min(maximumDisplacementLiters, complianceLitersPerPa * Math.max(0.0, pressurePa - activationPressurePa));
    }

    double litersAtPressure(double pressurePa) {
        return baseLiters + displacementAtPressure(pressurePa);
    }

    double elasticEnergyEu(double pressurePa) {
        return energyAtDisplacement(displacementAtPressure(pressurePa));
    }

    double energyAtDisplacement(double displacement) {
        return complianceLitersPerPa == 0.0 ? 0.0
            : 0.001 * displacement * (activationPressurePa + 0.5 * displacement / complianceLitersPerPa);
    }

    /** Solves (pressure + offset) * volume(pressure) = product without an iterative pressure solve. */
    double pressureForVolumeProduct(double product, double offset) {
        double rigidPressure = product / baseLiters - offset;
        if (complianceLitersPerPa == 0.0 || rigidPressure <= activationPressurePa) {
            return rigidPressure;
        }
        double fullPressure = product / (baseLiters + maximumDisplacementLiters) - offset;
        if (fullPressure >= activationPressurePa + maximumDisplacementLiters / complianceLitersPerPa) {
            return fullPressure;
        }
        double b = baseLiters + complianceLitersPerPa * (offset + activationPressurePa);
        double d = complianceLitersPerPa * baseLiters * (rigidPressure - activationPressurePa);
        // Rationalizing the positive quadratic root avoids cancellation near activation.
        double displacement = 2.0 * d / (b + Math.sqrt(b * b + 4.0 * d));
        return activationPressurePa + displacement / complianceLitersPerPa;
    }
}
