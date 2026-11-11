package gregtech.api.metatileentity.implementations.integratedfluid.thermodynamics;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** A fluid inventory whose energy includes both fluid internal energy and passive mechanical compliance. */
public final class ClosedFluidChamber {

    private static final int HEAT_EXCHANGE_ITERATIONS = 48;
    private static final int MASS_TRANSFER_ITERATIONS = 48;
    private static final double EQUILIBRIUM_MARGIN_KELVIN = 1e-10;

    private final PureFluid fluid;
    private final BigDecimal massKg;
    private final ChamberVolume volume;
    private final BigDecimal totalEnergyEu;
    private final BigDecimal elasticEnergyEu;
    private final PureFluid.State state;

    private ClosedFluidChamber(PureFluid fluid, BigDecimal massKg, ChamberVolume volume, BigDecimal totalEnergyEu) {
        this.fluid = Objects.requireNonNull(fluid, "fluid");
        this.totalEnergyEu = Objects.requireNonNull(totalEnergyEu, "totalEnergyEu");
        this.volume = Objects.requireNonNull(volume, "volume");
        Objects.requireNonNull(massKg, "massKg");
        if (massKg.signum() < 0) {
            throw new IllegalArgumentException("A fluid inventory requires nonnegative mass");
        }
        this.massKg = massKg;
        if (isEmpty()) {
            if (totalEnergyEu.signum() != 0) {
                throw new IllegalArgumentException("An empty fluid inventory cannot store energy");
            }
            this.state = null;
            this.elasticEnergyEu = BigDecimal.ZERO;
            return;
        }
        double specificEnergy = totalEnergyEu.divide(massKg, MathContext.DECIMAL128)
            .doubleValue();
        this.state = fluid.solve(volume, massKg.doubleValue(), specificEnergy);
        double elasticEnergy = volume.elasticEnergyEu(state.pressurePa());
        this.elasticEnergyEu = elasticEnergy == 0.0 ? BigDecimal.ZERO : BigDecimal.valueOf(elasticEnergy);
    }

    public static ClosedFluidChamber of(PureFluid fluid, BigDecimal massKg, double volumeLiters,
        BigDecimal internalEnergyEu) {
        return of(fluid, massKg, ChamberVolume.rigid(volumeLiters), internalEnergyEu);
    }

    public static ClosedFluidChamber empty(PureFluid fluid, double volumeLiters) {
        return of(fluid, BigDecimal.ZERO, volumeLiters, BigDecimal.ZERO);
    }

    /** Restores a complete chamber from mass, geometry and combined fluid-plus-spring energy. */
    public static ClosedFluidChamber of(PureFluid fluid, BigDecimal massKg, ChamberVolume volume,
        BigDecimal totalEnergyEu) {
        return new ClosedFluidChamber(fluid, massKg, volume, totalEnergyEu);
    }

    public static ClosedFluidChamber empty(PureFluid fluid, ChamberVolume volume) {
        return of(fluid, BigDecimal.ZERO, volume, BigDecimal.ZERO);
    }

    /** Transfers a homogeneous portion through a passive port, checking endpoint pressure and state support. */
    public FluidTransfer transferTo(ClosedFluidChamber receiver, BigDecimal maximumMassKg) {
        return transferTo(receiver, maximumMassKg, 1.0);
    }

    /** Applies a linear flow penalty up to the caller's solid-mass blocking threshold. */
    public FluidTransfer transferTo(ClosedFluidChamber receiver, BigDecimal maximumMassKg,
        double blockingSolidFraction) {
        Objects.requireNonNull(receiver, "receiver");
        Objects.requireNonNull(maximumMassKg, "maximumMassKg");
        if (maximumMassKg.signum() < 0) {
            throw new IllegalArgumentException("The mass-transfer budget cannot be negative");
        }
        if (!Double.isFinite(blockingSolidFraction) || blockingSolidFraction <= 0.0 || blockingSolidFraction > 1.0) {
            throw new IllegalArgumentException("The solid blocking fraction must be positive and at most one");
        }
        FluidTransfer unchanged = new FluidTransfer(this, receiver, BigDecimal.ZERO, BigDecimal.ZERO);
        if (isEmpty() || maximumMassKg.signum() == 0
            || fluid != receiver.fluid
            || pressurePa() <= receiver.pressurePa()) {
            return unchanged;
        }
        double solidFraction = Math
            .max(state.solidMassFraction(), receiver.isEmpty() ? 0.0 : receiver.state.solidMassFraction());
        double flowMultiplier = Math.max(0.0, 1.0 - solidFraction / blockingSolidFraction);
        if (flowMultiplier == 0.0) {
            return unchanged;
        }
        BigDecimal upper = (flowMultiplier == 1.0 ? maximumMassKg
            : maximumMassKg.multiply(BigDecimal.valueOf(flowMultiplier))).min(massKg);
        BigDecimal initialEntropy = entropyEuPerKelvin().add(receiver.entropyEuPerKelvin());
        FluidTransfer candidate = tryTransfer(receiver, upper, initialEntropy);
        if (candidate != null) {
            return candidate;
        }
        BigDecimal lower = BigDecimal.ZERO;
        FluidTransfer accepted = unchanged;
        for (int i = 0; i < MASS_TRANSFER_ITERATIONS; i++) {
            BigDecimal middle = lower.add(upper)
                .multiply(new BigDecimal("0.5"));
            candidate = tryTransfer(receiver, middle, initialEntropy);
            if (candidate == null) {
                upper = middle;
            } else {
                lower = middle;
                accepted = candidate;
            }
        }
        return accepted;
    }

    private FluidTransfer tryTransfer(ClosedFluidChamber receiver, BigDecimal transferredMass,
        BigDecimal initialEntropy) {
        if (transferredMass.signum() == 0 || transferredMass.compareTo(massKg) >= 0) {
            return null;
        }
        try {
            double fraction = transferredMass.divide(massKg, MathContext.DECIMAL128)
                .doubleValue();
            double averageEnthalpy = fluid.averageOutflowEnthalpy(state, volume, massKg.doubleValue(), fraction);
            BigDecimal flowEnergy = transferredMass.multiply(BigDecimal.valueOf(averageEnthalpy));
            ClosedFluidChamber sourceAfter = of(
                fluid,
                massKg.subtract(transferredMass),
                volume,
                totalEnergyEu.subtract(flowEnergy));
            ClosedFluidChamber receiverAfter = of(
                fluid,
                receiver.massKg.add(transferredMass),
                receiver.volume,
                receiver.totalEnergyEu.add(flowEnergy));
            if (sourceAfter.pressurePa() < receiverAfter.pressurePa() || sourceAfter.entropyEuPerKelvin()
                .add(receiverAfter.entropyEuPerKelvin())
                .compareTo(initialEntropy) < 0) {
                return null;
            }
            return new FluidTransfer(sourceAfter, receiverAfter, transferredMass, flowEnergy);
        } catch (PureFluid.UnsupportedStateException unsupported) {
            return null;
        }
    }

    private BigDecimal entropyEuPerKelvin() {
        return isEmpty() ? BigDecimal.ZERO : massKg.multiply(BigDecimal.valueOf(state.specificEntropyEuPerKgKelvin()));
    }

    public record FluidTransfer(ClosedFluidChamber source, ClosedFluidChamber receiver, BigDecimal transferredMassKg,
        BigDecimal transportedEnergyEu) {}

    /** Returns the complete heated state, or rejects unsupported states without modifying this inventory. */
    public ClosedFluidChamber withHeat(BigDecimal heatEu) {
        Objects.requireNonNull(heatEu, "heatEu");
        return heatEu.signum() == 0 ? this : of(fluid, massKg, volume, totalEnergyEu.add(heatEu));
    }

    /**
     * Transfers at most the supplied heat budget from hot to cold, without crossing equilibrium.
     * The result keeps argument order. Positive heatToFirstEu means this chamber received heat.
     * The budget is energy, not conductance or elapsed time.
     */
    public HeatExchange exchangeHeat(ClosedFluidChamber other, BigDecimal maximumHeatEu) {
        Objects.requireNonNull(other, "other");
        Objects.requireNonNull(maximumHeatEu, "maximumHeatEu");
        if (maximumHeatEu.signum() < 0) {
            throw new IllegalArgumentException("The passive heat budget cannot be negative");
        }
        HeatExchange unchanged = new HeatExchange(this, other, BigDecimal.ZERO);
        if (isEmpty() || other.isEmpty()
            || maximumHeatEu.signum() == 0
            || Math.abs(state.temperatureKelvin() - other.state.temperatureKelvin()) <= EQUILIBRIUM_MARGIN_KELVIN) {
            return unchanged;
        }
        if (state.temperatureKelvin() < other.state.temperatureKelvin()) {
            HeatExchange reversed = other.exchangeHeat(this, maximumHeatEu);
            return new HeatExchange(
                reversed.second(),
                reversed.first(),
                reversed.heatToFirstEu()
                    .negate());
        }

        BigDecimal minimumEnergy = BigDecimal.valueOf(
            fluid.energyRange(volume, massKg.doubleValue())
                .minimumEuPerKg())
            .multiply(massKg);
        BigDecimal otherMaximumEnergy = BigDecimal
            .valueOf(
                other.fluid.energyRange(other.volume, other.massKg.doubleValue())
                    .maximumEuPerKg())
            .multiply(other.massKg);
        BigDecimal upper = maximumHeatEu.min(totalEnergyEu.subtract(minimumEnergy))
            .min(otherMaximumEnergy.subtract(other.totalEnergyEu))
            .max(BigDecimal.ZERO);
        if (upper.signum() == 0) {
            return unchanged;
        }

        HeatExchange candidate = transferHeatTo(other, upper);
        if (candidate.first().state.temperatureKelvin() >= candidate.second().state.temperatureKelvin()) {
            return candidate;
        }
        double lowerTemperature = Math
            .max(other.state.temperatureKelvin(), candidate.first().state.temperatureKelvin());
        double upperTemperature = Math.min(state.temperatureKelvin(), candidate.second().state.temperatureKelvin());
        BigDecimal totalEnergy = totalEnergyEu.add(other.totalEnergyEu);
        // Crossing temperatures bracket a common equilibrium supported by both inventories.
        for (int i = 0; i < HEAT_EXCHANGE_ITERATIONS; i++) {
            double middle = lowerTemperature + (upperTemperature - lowerTemperature) * 0.5;
            BigDecimal equilibriumEnergy = energyAtTemperature(middle).add(other.energyAtTemperature(middle));
            if (equilibriumEnergy.compareTo(totalEnergy) <= 0) {
                lowerTemperature = middle;
            } else {
                upperTemperature = middle;
            }
        }

        // Both estimates under-transfer. Taking the larger avoids magnifying the margin for unequal masses.
        double coldTarget = Math.max(other.state.temperatureKelvin(), lowerTemperature - EQUILIBRIUM_MARGIN_KELVIN);
        double hotTarget = Math.min(state.temperatureKelvin(), upperTemperature + EQUILIBRIUM_MARGIN_KELVIN);
        BigDecimal coldEstimate = other.energyAtTemperature(coldTarget)
            .subtract(other.totalEnergyEu);
        BigDecimal hotEstimate = totalEnergyEu.subtract(energyAtTemperature(hotTarget));
        BigDecimal heat = coldEstimate.max(hotEstimate)
            .max(BigDecimal.ZERO)
            .min(upper);
        candidate = transferHeatTo(other, heat);
        if (candidate.first().state.temperatureKelvin() < candidate.second().state.temperatureKelvin()) {
            throw new IllegalStateException("Passive heat exchange crossed thermal equilibrium");
        }
        return candidate;
    }

    private BigDecimal energyAtTemperature(double temperatureKelvin) {
        return BigDecimal
            .valueOf(fluid.specificTotalEnergyAtTemperature(volume, massKg.doubleValue(), temperatureKelvin))
            .multiply(massKg);
    }

    /** Advances all contacts simultaneously by one tick. Result contacts retain input order. */
    public EnvironmentExchange exchangeHeat(List<ThermalBoundary> boundaries) {
        return exchangeHeat(ThermalBoundary.compile(boundaries));
    }

    /** Reuses the prepared response without regrouping or recompiling contacts during the tick. */
    public EnvironmentExchange exchangeHeat(ThermalBoundary.Profile profile) {
        List<ThermalBoundary> inputs = Objects.requireNonNull(profile, "profile")
            .boundaries();
        double lower = fluid.minimumTemperatureKelvin();
        double upper = fluid.maximumTemperatureKelvin();
        if (!isEmpty() && !inputs.isEmpty()) {
            if (environmentResidual(profile, lower).signum() > 0 || environmentResidual(profile, upper).signum() < 0) {
                throw new IllegalArgumentException("Thermal step would leave the supported fluid model");
            }
            // Search temperature directly, including latent-energy jumps, without nesting state inversions.
            for (int i = 0; i < HEAT_EXCHANGE_ITERATIONS; i++) {
                double middle = lower + (upper - lower) * 0.5;
                if (environmentResidual(profile, middle).signum() < 0) {
                    lower = middle;
                } else {
                    upper = middle;
                }
            }
        }
        List<ContactHeat> contacts = new ArrayList<>();
        BigDecimal heat = BigDecimal.ZERO;
        BigDecimal power = BigDecimal.ZERO;
        for (ThermalBoundary boundary : inputs) {
            ThermalBoundary.Flux flux = boundary
                .flux(isEmpty() ? boundary.ambientKelvin() : lower + (upper - lower) * 0.5, isEmpty());
            BigDecimal contactHeat = BigDecimal.valueOf(flux.heatEu());
            BigDecimal contactPower = BigDecimal.valueOf(flux.powerEu());
            contacts.add(
                new ContactHeat(contactPower, contactHeat, contactPower.subtract(contactHeat), flux.jacketKelvin()));
            heat = heat.add(contactHeat);
            power = power.add(contactPower);
        }
        return new EnvironmentExchange(
            withHeat(heat),
            power,
            heat,
            power.subtract(heat),
            Collections.unmodifiableList(contacts));
    }

    public EnvironmentExchange exchangeHeat(ThermalBoundary boundary) {
        return exchangeHeat(Collections.singletonList(boundary));
    }

    private BigDecimal environmentResidual(ThermalBoundary.Profile profile, double temperature) {
        return energyAtTemperature(temperature).subtract(totalEnergyEu)
            .subtract(profile.heatAtTemperature(temperature));
    }

    public record EnvironmentExchange(ClosedFluidChamber chamber, BigDecimal electricityEu, BigDecimal heatToChamberEu,
        BigDecimal heatToAmbientEu, List<ContactHeat> contacts) {}

    public record ContactHeat(BigDecimal electricityEu, BigDecimal heatToChamberEu, BigDecimal heatToAmbientEu,
        double jacketKelvin) {}

    private HeatExchange transferHeatTo(ClosedFluidChamber other, BigDecimal heatEu) {
        return new HeatExchange(withHeat(heatEu.negate()), other.withHeat(heatEu), heatEu.negate());
    }

    public record HeatExchange(ClosedFluidChamber first, ClosedFluidChamber second, BigDecimal heatToFirstEu) {}

    public BigDecimal internalEnergyEu() {
        return totalEnergyEu.subtract(elasticEnergyEu);
    }

    /** Conserved chamber energy, including fluid internal energy and recoverable spring work. */
    public BigDecimal totalEnergyEu() {
        return totalEnergyEu;
    }

    public BigDecimal elasticEnergyEu() {
        return elasticEnergyEu;
    }

    public BigDecimal massKg() {
        return massKg;
    }

    public double volumeLiters() {
        return volume.litersAtPressure(pressurePa());
    }

    public PureFluid.State state() {
        if (isEmpty()) {
            throw new IllegalStateException("An empty fluid inventory has no temperature or phase");
        }
        return state;
    }

    public boolean isEmpty() {
        return massKg.signum() == 0;
    }

    public double pressurePa() {
        return isEmpty() ? 0.0 : state.pressurePa();
    }
}
