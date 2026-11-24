package gregtech.api.metatileentity.implementations.integratedfluid.thermodynamics;

import java.util.function.ToDoubleFunction;

import gregtech.api.enums.GTValues;

/**
 * Equilibrium of solid, liquid and ideal vapor with constant heat capacities.
 * The condensed phases share a volume law. Hot water uses a smooth dense-fluid crossover.
 */
public final class PureFluid {

    private static final double PRESSURE_VOLUME_TO_EU = 0.001;
    private static final int TEMPERATURE_ITERATIONS = 96;
    private static final int SATURATION_ITERATIONS = 16;

    private static final PureFluid WATER = new PureFluid("water", 1_000_000,
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
        1000.0,
        50e6,
        647.096);

    private static final PureFluid OXYGEN = new PureFluid("oxygen", 888_888,
        90.0621, 100000, 0.875813, 1699, 919, 259.84, 213177, 5.12e8,
        54.36, 1300, 13890, 50, 700, 2e6, 154.58);

    // Normal hydrogen. Effective vapor cp matches the 1 bar enthalpy rise from boiling to 295 K
    private static final PureFluid HYDROGEN = new PureFluid("hydrogen", 55_556,
        20.3244, 100000, 14.1046, 9748, 12518, 4124.5, 448915, 5.16e7,
        13.957, 2200, 58200, 10, 700, 1e6, 33.1443);

    private static final PureFluid NITROGEN = new PureFluid("nitrogen", 777_778,
        77.2435, 100000, 1.23979, 2041, 1051, 296.8, 199320, 3.116e8,
        63.151, 1450, 25700, 50, 700, 2e6, 126.192);

    // Normal helium-4 above the superfluid region. There is no fusion transition in this pressure range
    private static final PureFluid HELIUM = new PureFluid("helium", 111_111,
        4.20983, 100000, 8.003575, 5120, 5200, 2077.26, 20645, 1.89e6,
        2.3, 5120, 0, 2.3, 1000, 2e5, 5.1953);

    // Gameplay working fluids, not measured pure substances. Super coolant carries more sensible heat per kg
    private static final PureFluid COOLANT = new PureFluid("ic2coolant", 1_000_000,
        400, 100000, 1, 4000, 2500, 450, 2100000, 2e9,
        250, 2000, 250000, 50, 600, 10e6, 650);
    private static final PureFluid SUPER_COOLANT = new PureFluid("supercoolant", 1_000_000,
        450, 100000, 1, 6000, 3500, 450, 2600000, 2e9,
        150, 2400, 220000, 50, 600, 10e6, 680);

    private static final PureFluid[] SUBSTANCES = { WATER, OXYGEN, HYDROGEN, NITROGEN, HELIUM, COOLANT, SUPER_COOLANT };

    private final String id;
    private final long referenceUnitMicroKg;
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
    private final double criticalTemperature;

    private PureFluid(String id, long referenceUnitMicroKg, double referenceTemperature, double referencePressure, double liquidReferenceVolume,
        double liquidHeatCapacity, double vaporHeatCapacity, double gasConstant, double referenceLatentHeat,
        double liquidBulkModulus, double meltingTemperature, double solidHeatCapacity, double fusionEnthalpy,
        double minTemperature, double maxTemperature, double maxPressure, double criticalTemperature) {
        this.id = id;
        this.referenceUnitMicroKg = referenceUnitMicroKg;
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
        this.criticalTemperature = criticalTemperature;
    }

    /** Game approximation of water from 50 to 1000 K and up to 50 MPa. */
    public static PureFluid water() {
        return WATER;
    }

    /** Low-pressure oxygen, with a single effective solid phase and no dense supercritical states. */
    public static PureFluid oxygen() {
        return OXYGEN;
    }

    public static PureFluid hydrogen() {
        return HYDROGEN;
    }

    public static PureFluid nitrogen() {
        return NITROGEN;
    }

    public String id() {
        return id;
    }

    public long unitMicroKg(boolean vapor) {
        return this == WATER && vapor ? referenceUnitMicroKg / GTValues.STEAM_PER_WATER : referenceUnitMicroKg;
    }

    long packetMicroKg(double vaporMassFraction) {
        if (this != WATER || vaporMassFraction == 0) return referenceUnitMicroKg;
        // Both phase contributions count toward one external unit. Never round its mass down
        return (long) Math.ceil(referenceUnitMicroKg / (1 + (GTValues.STEAM_PER_WATER - 1) * vaporMassFraction));
    }

    public static java.util.Optional<PureFluid> forSubstance(String id) {
        for (var fluid : SUBSTANCES) if (fluid.id.equals(id)) return java.util.Optional.of(fluid);
        return java.util.Optional.empty();
    }

    double referencePressurePa() {
        return referencePressure;
    }

    /** Equilibrated supply enthalpy at reference pressure. The declared phase resolves exact boiling. */
    double referenceStreamEnthalpy(double temperature, boolean vaporAtBoiling) {
        if (!supportsTemperature(temperature)) throw new UnsupportedStateException("Unsupported reference temperature");
        if (temperature < meltingTemperature) return liquidHeatCapacity * (meltingTemperature - referenceTemperature)
            - fusionEnthalpy + solidHeatCapacity * (temperature - meltingTemperature);
        return referenceVaporFraction(temperature, vaporAtBoiling) == 1 ? vaporEnthalpy(temperature)
            : liquidHeatCapacity * (temperature - referenceTemperature);
    }

    double referenceVaporFraction(double temperature, boolean vaporAtBoiling) {
        return temperature > referenceTemperature || temperature == referenceTemperature && vaporAtBoiling ? 1 : 0;
    }

    boolean supportsTemperature(double temperature) {
        return temperature >= minTemperature && temperature <= maxTemperature;
    }

    private double vaporEnthalpy(double temperature) {
        return referenceLatentHeat + vaporHeatCapacity * (temperature - referenceTemperature);
    }

    /** Single homogeneous stream in the hot-water model. Volumes in L/kg, h in EU/kg and s in EU/(kg K). */
    record HotWater(double litersPerKg, double enthalpy, double entropy) {}

    HotWater hotWaterAtPressure(double temperature, double pressure) {
        if (this != WATER || !(temperature > 500 && temperature <= 1000) || !(pressure > 0 && pressure <= maxPressure))
            throw new UnsupportedStateException("Outside hot-water property range");
        var line = new HotIsotherm(temperature);
        return line.stream(pressure, line.weight(pressure));
    }

    private final class HotIsotherm {
        final double t, logT, hl0, hv, sl, width, slope;

        HotIsotherm(double temperature) {
            t = temperature;
            logT = Math.log(t / referenceTemperature);
            hl0 = liquidHeatCapacity * (t - referenceTemperature);
            hv = vaporEnthalpy(t);
            sl = liquidHeatCapacity * logT;
            double d = t - 500;
            width = 2000 * d * d / (d + 30);
            slope = 2000 * d * (d + 60) / ((d + 30) * (d + 30));
        }

        double weight(double p) {
            // Minimize g = (1-x)gl + x*gv - width*x*(1-x). This is not a separable vapor fraction
            double delta = hv - hl0 - liquidPressureEnergy(p) - t * (vaporEntropy(logT, p) - sl);
            return Math.max(0, Math.min(1, 0.5 * (1 - delta / width)));
        }

        HotWater stream(double p, double x) {
            double blend = x * (1 - x), vl = liquidVolume(p), hl = hl0 + liquidPressureEnergy(p);
            return new HotWater(vl + x * (vaporSpecificVolume(t, p) - vl),
                hl + x * (hv - hl) + (t * slope - width) * blend, sl + x * (vaporEntropy(logT, p) - sl) + slope * blend);
        }

        State equilibrium(ChamberVolume volume, double mass) {
            double pv = volume.pressureForVolumeProduct(mass * gasConstant * t / PRESSURE_VOLUME_TO_EU, 0);
            if (weight(pv) == 1) return state(pv, 1);
            double pl = volume.pressureForVolumeProduct(mass * liquidReferenceVolume * (liquidBulkModulus + referencePressure), liquidBulkModulus);
            if (pl > 0 && weight(pl) == 0) return state(pl, 0);
            double lo = Math.max(0, Math.min(pl, pv)), hi = Math.max(pl, pv), p = (lo + hi) / 2;
            for (int i = 0; i < 64; i++) {
                double x = weight(p), vl = liquidVolume(p), vg = vaporSpecificVolume(t, p);
                double displacement = volume.displacementAtPressure(p);
                double v = (volume.baseLiters() + displacement) / mass;
                double residual = vl + x * (vg - vl) - v;
                if (Math.abs(residual) <= 1e-13 * v) return state(p, x);
                if (residual > 0) lo = p;
                else hi = p;
                if (hi - lo <= 1e-13 * hi) {
                    // Near 500 K the pressure interval collapses before the volume weight loses its precision
                    return state(p, Math.max(0, Math.min(1, (v - vl) / (vg - vl))));
                }
                double derivative = -(1 - x) * vl / (liquidBulkModulus + p) - x * vg / p;
                if (x > 0 && x < 1) derivative -= PRESSURE_VOLUME_TO_EU * (vg - vl) * (vg - vl) / (2 * width);
                if (displacement > 0 && displacement < volume.maximumDisplacementLiters()) derivative -= volume.complianceLitersPerPa() / mass;
                double next = p - residual / derivative;
                p = next > lo && next < hi && i % 3 != 2 ? next : (lo + hi) / 2;
            }
            throw new UnsupportedStateException("Hot water volume did not converge");
        }

        State state(double p, double x) {
            var fluid = stream(p, x);
            var region = t >= criticalTemperature && p >= 22.064e6 ? DenseRegion.SUPERCRITICAL
                : x > 0 && x < 1 ? DenseRegion.CROSSOVER : DenseRegion.NONE;
            return new State(t, p, region == DenseRegion.NONE && x == 1 ? 1 : 0, 0,
                fluid.enthalpy - PRESSURE_VOLUME_TO_EU * p * fluid.litersPerKg, fluid.enthalpy, fluid.entropy, region);
        }
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
        return candidate.denseRegion == DenseRegion.NONE && candidate.vaporMassFraction() == quality && candidate.solidMassFraction() == solidFraction
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
        double upper = target < frozenValue ? meltingTemperature : this == WATER ? 500 : maxTemperature;
        State lowerState = atTemperature(volume, mass, lower);
        State upperState = target < frozenValue ? frozenAtMelting : atTemperature(volume, mass, upper);
        double lowerValue = property.applyAsDouble(lowerState);
        double upperValue = property.applyAsDouble(upperState);
        if (this == WATER && upper == 500 && target > upperValue) {
            lower = upper;
            lowerValue = upperValue;
            upper = maxTemperature;
            upperState = atTemperature(volume, mass, upper);
            upperValue = property.applyAsDouble(upperState);
        }
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
        // A collapsed bracket across the dense-to-ideal switch need not contain a physical root
        if (this != WATER && lower < criticalTemperature && upper >= criticalTemperature
            && atTemperature(volume, mass, lower).vaporMassFraction() < 1) {
            throw new UnsupportedStateException("Dense critical transition is outside the supported range");
        }
        return checkPressure(atTemperature(volume, mass, lower + (upper - lower) * 0.5));
    }

    private State checkPressure(State result) {
        if (!Double.isFinite(result.pressurePa()) || result.pressurePa() <= 0.0 || result.pressurePa() > maxPressure) {
            throw new UnsupportedStateException("Equilibrium pressure is outside the supported range");
        }
        return result;
    }

    /** Upper backpressure for a finite packet before any shaft work is removed from it. */
    double exhaustPressureBound(ChamberVolume volume, double mass, double specificEnergy) {
        State ceiling = atTemperature(volume, mass, maxTemperature);
        if (specificEnergy >= specificTotalEnergy(volume, mass, ceiling)) return ceiling.pressurePa();
        return solve(volume, mass, specificEnergy).pressurePa();
    }

    /** Enthalpy drop along the inlet isentrope. A wet endpoint can stop short of the requested pressure. */
    State idealExpansionOutlet(State inlet, double exhaustPa, double maximumRatio) {
        if (!inlet.canExpand() || inlet.pressurePa() <= exhaustPa) return null;
        double ratio = Math.min(maximumRatio, inlet.pressurePa() / exhaustPa);
        if (this == WATER && inlet.temperatureKelvin() > 500) {
            return waterAtPressureAndEntropy(inlet.pressurePa() / ratio, inlet.specificEntropyEuPerKgKelvin());
        }
        double specificVolume = vaporSpecificVolume(inlet.temperatureKelvin(), inlet.pressurePa());
        double expandedVolume = specificVolume * Math.pow(ratio, 1 - gasConstant / vaporHeatCapacity);
        State expanded = solveAtEntropy(ChamberVolume.rigid(expandedVolume), 1, inlet.specificEntropyEuPerKgKelvin());
        return expanded.solidMassFraction() > 0 || expanded.pressurePa() < exhaustPa * (1 - 1e-12) ? null : expanded;
    }

    boolean wetStageExit(State idealOutlet, double actualEnthalpy) {
        // Inefficiency can keep a packet dry even if its isentropic endpoint is wet
        return idealOutlet.denseRegion() == DenseRegion.NONE && idealOutlet.vaporMassFraction() < 1
            && actualEnthalpy < vaporEnthalpy(idealOutlet.temperatureKelvin());
    }

    private State waterAtPressureAndEntropy(double p, double entropy) {
        double lo = Math.nextUp(500.0), hi = maxTemperature;
        var line = new HotIsotherm(lo);
        if (entropy >= line.state(p, line.weight(p)).specificEntropyEuPerKgKelvin()) {
            line = new HotIsotherm(hi);
            if (entropy > line.state(p, line.weight(p)).specificEntropyEuPerKgKelvin()) return null;
            for (int i = 0; i < 48; i++) {
                double t = (lo + hi) / 2;
                line = new HotIsotherm(t);
                State point = line.state(p, line.weight(p));
                double error = point.specificEntropyEuPerKgKelvin() - entropy;
                if (Math.abs(error) < 1e-10) return point;
                if (error < 0) lo = t;
                else hi = t;
            }
            return line.state(p, line.weight(p));
        }

        // Outside coexistence, the cold branches have analytic entropy inverses
        double tl = referenceTemperature * Math.exp(entropy / liquidHeatCapacity);
        if (tl >= meltingTemperature && tl <= 500 && p >= saturationPressure(tl))
            return atTemperature(ChamberVolume.rigid(liquidVolume(p)), 1, tl);
        double tv = referenceTemperature * Math.exp((entropy - referenceLatentHeat / referenceTemperature
            + gasConstant * Math.log(p / referencePressure)) / vaporHeatCapacity);
        if (tv >= meltingTemperature && tv <= 500 && p <= saturationPressure(tv))
            return atTemperature(ChamberVolume.rigid(vaporSpecificVolume(tv, p)), 1, tv);
        lo = meltingTemperature;
        hi = 500;
        if (p < saturationPressure(lo) || p > saturationPressure(hi)) return null;
        for (int i = 0; i < 45; i++) {
            double t = (lo + hi) / 2;
            if (saturationPressure(t) < p) lo = t;
            else hi = t;
        }
        double t = (lo + hi) / 2, logT = Math.log(t / referenceTemperature);
        double sl = liquidHeatCapacity * logT;
        double quality = (entropy - sl) / (vaporEntropy(logT, p) - sl);
        if (quality < 0 || quality > 1) return null;
        return atTemperature(ChamberVolume.rigid(liquidVolume(p) + quality * (vaporSpecificVolume(t, p) - liquidVolume(p))), 1, t);
    }

    double vaporSpecificVolume(double temperature, double pressure) {
        return gasConstant * temperature / (PRESSURE_VOLUME_TO_EU * pressure);
    }

    private double vaporEntropy(double logTemperature, double pressure) {
        return referenceLatentHeat / referenceTemperature + vaporHeatCapacity * logTemperature
            - gasConstant * Math.log(pressure / referencePressure);
    }

    double averageOutflowEnthalpy(State initial, ChamberVolume volume, double mass, double removedMassFraction,
        ClosedFluidChamber.Draw draw) {
        if (!(removedMassFraction > 0.0 && removedMassFraction < 1.0)) {
            throw new UnsupportedStateException("Finite passive outflow requires a nonempty source");
        }
        double entropy = initial.specificEntropyEuPerKgKelvin();
        double logT = Math.log(initial.temperatureKelvin() / referenceTemperature);
        double entropyGap = switch (draw) {
            case METERED_VAPOR -> entropy - vaporEntropy(logT, initial.pressurePa());
            case METERED_LIQUID -> entropy - liquidHeatCapacity * logT;
            default -> 0;
        };
        if (removedMassFraction < 1e-7) {
            // A midpoint integral avoids subtracting nearly identical bulk energies for tiny packets
            double half = 0.5 * removedMassFraction;
            State midpoint = solveAtEntropy(volume, mass * (1 - half), entropy + entropyGap * half / (1 - half));
            return switch (draw) {
                case METERED_VAPOR -> vaporEnthalpy(midpoint.temperatureKelvin());
                case METERED_LIQUID -> liquidHeatCapacity * (midpoint.temperatureKelvin() - referenceTemperature)
                    + liquidPressureEnergy(midpoint.pressurePa());
                default -> midpoint.specificEnthalpyEuPerKg();
            };
        }
        double remainingMass = mass * (1.0 - removedMassFraction);
        // The selected packet carries its phase entropy. Only a homogeneous draw leaves specific entropy unchanged
        State remaining = solveAtEntropy(volume, remainingMass, entropy + entropyGap * removedMassFraction / (1 - removedMassFraction));
        double remainingEnergy = specificTotalEnergy(volume, remainingMass, remaining);
        return remainingEnergy + (specificTotalEnergy(volume, mass, initial) - remainingEnergy) / removedMassFraction;
    }

    static final class UnsupportedStateException extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;

        private UnsupportedStateException(String message) {
            super(message);
        }
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

    private State atTemperature(ChamberVolume volume, double mass, double temperature) {
        return atTemperature(volume, mass, temperature, temperature < meltingTemperature);
    }

    private State atTemperature(ChamberVolume volume, double mass, double temperature, boolean solidCondensate) {
        solidCondensate &= fusionEnthalpy > 0;
        if (this == WATER && temperature > 500) return new HotIsotherm(temperature).equilibrium(volume, mass);
        if (temperature >= criticalTemperature) {
            double p = volume.pressureForVolumeProduct(mass * gasConstant * temperature / PRESSURE_VOLUME_TO_EU, 0);
            double h = vaporEnthalpy(temperature);
            return new State(temperature, p, 1, 0, h - gasConstant * temperature, h,
                vaporEntropy(Math.log(temperature / referenceTemperature), p));
        }
        double pressure = saturationPressure(temperature);
        double specificVolume = volume.litersAtPressure(pressure) / mass;
        double liquidVolume = liquidVolume(pressure);
        double vaporVolume = vaporSpecificVolume(temperature, pressure);
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
        double vaporEnthalpy = vaporEnthalpy(temperature);
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
        double vaporEntropy = vaporEntropy(logTemperature, pressure);
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

    double liquidVolume(double pressure) {
        return liquidReferenceVolume * (liquidBulkModulus + referencePressure) / (liquidBulkModulus + pressure);
    }

    public enum DenseRegion { NONE, CROSSOVER, SUPERCRITICAL }

    public record State(double temperatureKelvin, double pressurePa, double vaporMassFraction, double solidMassFraction,
        double specificInternalEnergyEuPerKg, double specificEnthalpyEuPerKg, double specificEntropyEuPerKgKelvin,
        DenseRegion denseRegion) {

        State(double t, double p, double vapor, double solid, double u, double h, double s) {
            this(t, p, vapor, solid, u, h, s, DenseRegion.NONE);
        }

        public double liquidMassFraction() {
            return denseRegion == DenseRegion.NONE ? Math.max(0.0, 1.0 - vaporMassFraction - solidMassFraction) : 0;
        }

        public boolean isPureStream(boolean vapor) {
            return (vapor ? vaporMassFraction : liquidMassFraction()) == 1;
        }

        boolean canExpand() {
            return vaporMassFraction == 1 || denseRegion != DenseRegion.NONE;
        }
    }
}
