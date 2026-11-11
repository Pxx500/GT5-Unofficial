package gregtech.api.metatileentity.implementations.integratedfluid.thermodynamics;

import java.util.function.ToDoubleFunction;

/**
 * Equilibrium of solid, liquid and ideal vapor with constant heat capacities.
 * The condensed phases share a volume law. Expansion on freezing and the critical region are excluded.
 */
public final class PureFluid {

    private static final double PRESSURE_VOLUME_TO_EU = 0.001;
    private static final int TEMPERATURE_ITERATIONS = 96;
    private static final int SATURATION_ITERATIONS = 16;

    private static final PureFluid WATER = new PureFluid(
        373.15,
        100000.0,
        1.043,
        4180.0,
        2010.0,
        461.5,
        2257000.0,
        2.2e9,
        273.16,
        2100.0,
        333500.0,
        50.0,
        500.0,
        50e6);

    private final double referenceTemperature;
    private final double referencePressure;
    private final double liquidReferenceVolume;
    private final double liquidHeatCapacity;
    private final double vaporHeatCapacity;
    private final double gasConstant;
    private final double referenceLatentHeat;
    private final double liquidBulkModulus;
    private final double meltingTemperature;
    private final double solidHeatCapacity;
    private final double fusionEnthalpy;
    private final double minTemperature;
    private final double maxTemperature;
    private final double maxPressure;

    private PureFluid(double referenceTemperature, double referencePressure, double liquidReferenceVolume,
        double liquidHeatCapacity, double vaporHeatCapacity, double gasConstant, double referenceLatentHeat,
        double liquidBulkModulus, double meltingTemperature, double solidHeatCapacity, double fusionEnthalpy,
        double minTemperature, double maxTemperature, double maxPressure) {
        this.referenceTemperature = referenceTemperature;
        this.referencePressure = referencePressure;
        this.liquidReferenceVolume = liquidReferenceVolume;
        this.liquidHeatCapacity = liquidHeatCapacity;
        this.vaporHeatCapacity = vaporHeatCapacity;
        this.gasConstant = gasConstant;
        this.referenceLatentHeat = referenceLatentHeat;
        this.liquidBulkModulus = liquidBulkModulus;
        this.meltingTemperature = meltingTemperature;
        this.solidHeatCapacity = solidHeatCapacity;
        this.fusionEnthalpy = fusionEnthalpy;
        this.minTemperature = minTemperature;
        this.maxTemperature = maxTemperature;
        this.maxPressure = maxPressure;
    }

    /** Game approximation of water from 50 to 500 K and up to 50 MPa, without condensed-phase expansion. */
    public static PureFluid water() {
        return WATER;
    }

    State solve(ChamberVolume volume, double mass, double specificTotalEnergy) {
        double pressure = volume.pressureForVolumeProduct(
            mass * liquidReferenceVolume * (liquidBulkModulus + referencePressure),
            liquidBulkModulus);
        if (pressure > 0.0) {
            double liquidTemperature = referenceTemperature
                + (specificTotalEnergy - volume.elasticEnergyEu(pressure) / mass
                    - liquidPressureEnergy(pressure)
                    + PRESSURE_VOLUME_TO_EU * pressure * volume.litersAtPressure(pressure) / mass) / liquidHeatCapacity;
            State liquid = singlePhaseAtTemperature(volume, mass, liquidTemperature, 0.0, 0.0);
            if (liquid != null) {
                return liquid;
            }
            double solidTemperature = meltingTemperature
                + liquidHeatCapacity * (liquidTemperature - meltingTemperature) / solidHeatCapacity
                + fusionEnthalpy / solidHeatCapacity;
            State solid = singlePhaseAtTemperature(volume, mass, solidTemperature, 0.0, 1.0);
            if (solid != null) {
                return solid;
            }
        }
        double vaporTemperature = vaporTemperatureAtEnergy(volume, mass, specificTotalEnergy);
        State vapor = singlePhaseAtTemperature(volume, mass, vaporTemperature, 1.0, 0.0);
        if (vapor != null) {
            return vapor;
        }
        return solve(volume, mass, specificTotalEnergy, state -> specificTotalEnergy(volume, mass, state));
    }

    private double vaporTemperatureAtEnergy(ChamberVolume volume, double mass, double energy) {
        double cv = vaporHeatCapacity - gasConstant;
        double thermalEnergy = mass * (energy - referenceLatentHeat + vaporHeatCapacity * referenceTemperature);
        double displacement = 0.0;
        if (volume.complianceLitersPerPa() > 0.0) {
            double a = PRESSURE_VOLUME_TO_EU * cv / gasConstant;
            double excess = thermalEnergy - a * volume.activationPressurePa() * volume.baseLiters();
            if (excess > 0.0) {
                double stiffness = 1.0 / volume.complianceLitersPerPa();
                double b = a * (volume.activationPressurePa() + stiffness * volume.baseLiters())
                    + PRESSURE_VOLUME_TO_EU * volume.activationPressurePa();
                double quadratic = (a + 0.5 * PRESSURE_VOLUME_TO_EU) * stiffness;
                displacement = Math.min(
                    volume.maximumDisplacementLiters(),
                    2.0 * excess / (b + Math.sqrt(b * b + 4.0 * quadratic * excess)));
            }
        }
        return (thermalEnergy - volume.energyAtDisplacement(displacement)) / (mass * cv);
    }

    private State solveAtEntropy(ChamberVolume volume, double mass, double specificEntropy) {
        double liquidTemperature = referenceTemperature * Math.exp(specificEntropy / liquidHeatCapacity);
        State liquid = singlePhaseAtTemperature(volume, mass, liquidTemperature, 0.0, 0.0);
        if (liquid != null) {
            return liquid;
        }
        double solidEntropyAtMelting = liquidHeatCapacity * Math.log(meltingTemperature / referenceTemperature)
            - fusionEnthalpy / meltingTemperature;
        if (specificEntropy < solidEntropyAtMelting) {
            double solidTemperature = meltingTemperature
                * Math.exp((specificEntropy - solidEntropyAtMelting) / solidHeatCapacity);
            State solid = singlePhaseAtTemperature(volume, mass, solidTemperature, 0.0, 1.0);
            if (solid != null) {
                return solid;
            }
        }
        State vapor = vaporAtEntropy(volume, mass, volume.baseLiters(), specificEntropy);
        if (vapor == null && volume.maximumDisplacementLiters() > 0.0) {
            vapor = vaporAtEntropy(
                volume,
                mass,
                volume.baseLiters() + volume.maximumDisplacementLiters(),
                specificEntropy);
        }
        return vapor != null ? vapor : solve(volume, mass, specificEntropy, State::specificEntropyEuPerKgKelvin);
    }

    private State vaporAtEntropy(ChamberVolume volume, double mass, double fixedVolume, double specificEntropy) {
        double specificVolume = fixedVolume / mass;
        double referenceVaporEntropy = referenceLatentHeat / referenceTemperature - gasConstant * Math
            .log(gasConstant * referenceTemperature / (PRESSURE_VOLUME_TO_EU * specificVolume * referencePressure));
        double vaporTemperature = referenceTemperature
            * Math.exp((specificEntropy - referenceVaporEntropy) / (vaporHeatCapacity - gasConstant));
        State vapor = singlePhaseAtTemperature(volume, mass, vaporTemperature, 1.0, 0.0);
        return vapor != null && volume.litersAtPressure(vapor.pressurePa()) == fixedVolume ? vapor : null;
    }

    private State singlePhaseAtTemperature(ChamberVolume volume, double mass, double temperature, double quality,
        double solidFraction) {
        if (!Double.isFinite(temperature) || temperature < minTemperature || temperature > maxTemperature) {
            return null;
        }
        State candidate = atTemperature(volume, mass, temperature);
        return candidate.vaporMassFraction() == quality && candidate.solidMassFraction() == solidFraction
            ? checkPressure(candidate)
            : null;
    }

    private State solve(ChamberVolume volume, double mass, double target, ToDoubleFunction<State> property) {
        if (!Double.isFinite(mass) || mass <= 0.0 || !Double.isFinite(target)) {
            throw new IllegalArgumentException("Mass and state target must be finite, with positive mass");
        }
        State frozenAtMelting = atTemperature(volume, mass, meltingTemperature, true);
        State liquidAtMelting = atTemperature(volume, mass, meltingTemperature, false);
        double frozenValue = property.applyAsDouble(frozenAtMelting);
        double liquidValue = property.applyAsDouble(liquidAtMelting);
        if (target >= frozenValue && target <= liquidValue) {
            double liquidFraction = liquidValue == frozenValue ? 1.0
                : (target - frozenValue) / (liquidValue - frozenValue);
            return checkPressure(
                new State(
                    meltingTemperature,
                    liquidAtMelting.pressurePa(),
                    liquidAtMelting.vaporMassFraction(),
                    frozenAtMelting.solidMassFraction() * (1.0 - liquidFraction),
                    frozenAtMelting.specificInternalEnergyEuPerKg()
                        + liquidFraction * (liquidAtMelting.specificInternalEnergyEuPerKg()
                            - frozenAtMelting.specificInternalEnergyEuPerKg()),
                    frozenAtMelting.specificEnthalpyEuPerKg() + liquidFraction
                        * (liquidAtMelting.specificEnthalpyEuPerKg() - frozenAtMelting.specificEnthalpyEuPerKg()),
                    frozenAtMelting.specificEntropyEuPerKgKelvin()
                        + liquidFraction * (liquidAtMelting.specificEntropyEuPerKgKelvin()
                            - frozenAtMelting.specificEntropyEuPerKgKelvin())));
        }
        double lower = target < frozenValue ? minTemperature : meltingTemperature;
        double upper = target < frozenValue ? meltingTemperature : maxTemperature;
        State lowerState = atTemperature(volume, mass, lower);
        State upperState = target < frozenValue ? frozenAtMelting : atTemperature(volume, mass, upper);
        double lowerValue = property.applyAsDouble(lowerState);
        double upperValue = property.applyAsDouble(upperState);
        if (target < lowerValue || target > upperValue) {
            throw new UnsupportedStateException("State is outside the supported temperature range");
        }
        if (target == lowerValue) {
            return checkPressure(lowerState);
        }
        if (target == upperValue) {
            return checkPressure(upperState);
        }

        // Alternate interpolation with bisection, retaining 48 guaranteed bracket halvings in the worst case.
        for (int i = 0; i < TEMPERATURE_ITERATIONS; i++) {
            double fraction = i % 2 == 0
                ? Math.max(0.05, Math.min(0.95, (target - lowerValue) / (upperValue - lowerValue)))
                : 0.5;
            double middle = lower + (upper - lower) * fraction;
            if (middle == lower || middle == upper) {
                break;
            }
            State candidate = atTemperature(volume, mass, middle);
            double value = property.applyAsDouble(candidate);
            if (value == target) {
                return checkPressure(candidate);
            }
            if (value < target) {
                lower = middle;
                lowerValue = value;
            } else {
                upper = middle;
                upperValue = value;
            }
        }
        return checkPressure(atTemperature(volume, mass, lower + (upper - lower) * 0.5));
    }

    private State checkPressure(State result) {
        if (!Double.isFinite(result.pressurePa()) || result.pressurePa() <= 0.0 || result.pressurePa() > maxPressure) {
            throw new UnsupportedStateException("Equilibrium pressure is outside the supported range");
        }
        return result;
    }

    double averageOutflowEnthalpy(State initial, ChamberVolume volume, double mass, double removedMassFraction) {
        if (!(removedMassFraction > 0.0 && removedMassFraction < 1.0)) {
            throw new UnsupportedStateException("Finite passive outflow requires a nonempty source");
        }
        double entropy = initial.specificEntropyEuPerKgKelvin();
        if (removedMassFraction < 1e-7) {
            // A midpoint integral avoids subtracting nearly identical bulk energies for tiny packets.
            return solveAtEntropy(volume, mass * (1.0 - 0.5 * removedMassFraction), entropy).specificEnthalpyEuPerKg();
        }
        double remainingMass = mass * (1.0 - removedMassFraction);
        State remaining = solveAtEntropy(volume, remainingMass, entropy);
        double remainingEnergy = specificTotalEnergy(volume, remainingMass, remaining);
        return remainingEnergy + (specificTotalEnergy(volume, mass, initial) - remainingEnergy) / removedMassFraction;
    }

    static final class UnsupportedStateException extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;

        private UnsupportedStateException(String message) {
            super(message);
        }
    }

    EnergyRange energyRange(ChamberVolume volume, double mass) {
        return new EnergyRange(
            specificTotalEnergy(volume, mass, atTemperature(volume, mass, minTemperature)),
            specificTotalEnergy(volume, mass, atTemperature(volume, mass, maxTemperature)));
    }

    double minimumTemperatureKelvin() {
        return minTemperature;
    }

    double maximumTemperatureKelvin() {
        return maxTemperature;
    }

    double specificTotalEnergyAtTemperature(ChamberVolume volume, double mass, double temperatureKelvin) {
        if (!Double.isFinite(temperatureKelvin) || temperatureKelvin < minTemperature
            || temperatureKelvin > maxTemperature) {
            throw new IllegalArgumentException("Temperature is outside the supported range");
        }
        return specificTotalEnergy(volume, mass, atTemperature(volume, mass, temperatureKelvin));
    }

    private double specificTotalEnergy(ChamberVolume volume, double mass, State state) {
        return state.specificInternalEnergyEuPerKg() + volume.elasticEnergyEu(state.pressurePa()) / mass;
    }

    record EnergyRange(double minimumEuPerKg, double maximumEuPerKg) {}

    private State atTemperature(ChamberVolume volume, double mass, double temperature) {
        return atTemperature(volume, mass, temperature, temperature < meltingTemperature);
    }

    private State atTemperature(ChamberVolume volume, double mass, double temperature, boolean solidCondensate) {
        double pressure = saturationPressure(temperature);
        double specificVolume = volume.litersAtPressure(pressure) / mass;
        double liquidVolume = liquidVolume(pressure);
        double vaporVolume = gasConstant * temperature / (PRESSURE_VOLUME_TO_EU * pressure);
        double quality;
        if (specificVolume <= liquidVolume) {
            pressure = volume.pressureForVolumeProduct(
                mass * liquidReferenceVolume * (liquidBulkModulus + referencePressure),
                liquidBulkModulus);
            quality = 0.0;
        } else if (specificVolume >= vaporVolume) {
            pressure = volume.pressureForVolumeProduct(mass * gasConstant * temperature / PRESSURE_VOLUME_TO_EU, 0.0);
            quality = 1.0;
        } else {
            quality = (specificVolume - liquidVolume) / (vaporVolume - liquidVolume);
        }

        specificVolume = volume.litersAtPressure(pressure) / mass;

        double logTemperature = Math.log(temperature / referenceTemperature);
        double condensedEnthalpy = liquidHeatCapacity * (temperature - referenceTemperature)
            + liquidPressureEnergy(pressure);
        double vaporEnthalpy = referenceLatentHeat + vaporHeatCapacity * (temperature - referenceTemperature);
        double condensedEntropy = liquidHeatCapacity * logTemperature;
        if (solidCondensate) {
            condensedEnthalpy = liquidHeatCapacity * (meltingTemperature - referenceTemperature) - fusionEnthalpy
                + solidHeatCapacity * (temperature - meltingTemperature)
                + liquidPressureEnergy(pressure);
            condensedEntropy = liquidHeatCapacity * Math.log(meltingTemperature / referenceTemperature)
                - fusionEnthalpy / meltingTemperature
                + solidHeatCapacity * Math.log(temperature / meltingTemperature);
        }
        double enthalpy = (1.0 - quality) * condensedEnthalpy + quality * vaporEnthalpy;
        double vaporEntropy = referenceLatentHeat / referenceTemperature + vaporHeatCapacity * logTemperature
            - gasConstant * Math.log(pressure / referencePressure);
        return new State(
            temperature,
            pressure,
            quality,
            solidCondensate ? 1.0 - quality : 0.0,
            enthalpy - PRESSURE_VOLUME_TO_EU * pressure * specificVolume,
            enthalpy,
            (1.0 - quality) * condensedEntropy + quality * vaporEntropy);
    }

    private double saturationPressure(double temperature) {
        double thermalGibbs = temperature - referenceTemperature
            - temperature * Math.log(temperature / referenceTemperature);
        double gibbsDifference = (liquidHeatCapacity - vaporHeatCapacity) * thermalGibbs
            - referenceLatentHeat * (1.0 - temperature / referenceTemperature);
        if (temperature < meltingTemperature) {
            gibbsDifference += (solidHeatCapacity - liquidHeatCapacity)
                * (temperature - meltingTemperature - temperature * Math.log(temperature / meltingTemperature))
                - fusionEnthalpy * (1.0 - temperature / meltingTemperature);
        }
        double pressure = referencePressure * Math.exp(gibbsDifference / (gasConstant * temperature));
        // Solve g_condensed = g_vapor. The pressure correction contracts well below the critical region.
        for (int i = 0; i < SATURATION_ITERATIONS; i++) {
            double next = referencePressure
                * Math.exp((gibbsDifference + liquidPressureEnergy(pressure)) / (gasConstant * temperature));
            if (Math.abs(next - pressure) <= 1e-11 * pressure) {
                return next;
            }
            pressure = next;
        }
        throw new IllegalStateException("Saturation pressure did not converge");
    }

    private double liquidPressureEnergy(double pressure) {
        return PRESSURE_VOLUME_TO_EU * liquidReferenceVolume
            * (liquidBulkModulus + referencePressure)
            * Math.log1p((pressure - referencePressure) / (liquidBulkModulus + referencePressure));
    }

    private double liquidVolume(double pressure) {
        return liquidReferenceVolume * (liquidBulkModulus + referencePressure) / (liquidBulkModulus + pressure);
    }

    public record State(double temperatureKelvin, double pressurePa, double vaporMassFraction, double solidMassFraction,
        double specificInternalEnergyEuPerKg, double specificEnthalpyEuPerKg, double specificEntropyEuPerKgKelvin) {

        public double liquidMassFraction() {
            return Math.max(0.0, 1.0 - vaporMassFraction - solidMassFraction);
        }
    }
}
