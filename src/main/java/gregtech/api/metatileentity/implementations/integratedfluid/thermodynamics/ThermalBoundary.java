package gregtech.api.metatileentity.implementations.integratedfluid.thermodynamics;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** A massless pipe jacket with conductances in EU/(tick K) and available electric power in EU/tick. */
public record ThermalBoundary(double ambientKelvin, double targetKelvin, double innerConductance,
    double outerConductance, double maximumPowerEuPerTick) {

    public ThermalBoundary {
        if (!Double.isFinite(ambientKelvin) || ambientKelvin < 0.0
            || !Double.isFinite(targetKelvin)
            || targetKelvin < 0.0
            || !Double.isFinite(innerConductance)
            || innerConductance <= 0.0
            || !Double.isFinite(outerConductance)
            || outerConductance < 0.0
            || !Double.isFinite(maximumPowerEuPerTick)
            || maximumPowerEuPerTick < 0.0
            || !Double.isFinite(innerConductance + outerConductance)) {
            throw new IllegalArgumentException("Thermal boundary values must be finite and physically nonnegative");
        }
    }

    Flux flux(double fluidKelvin, boolean empty) {
        double inside = empty ? 0.0 : innerConductance;
        double total = inside + outerConductance;
        if (total == 0.0) {
            return new Flux(0.0, 0.0, targetKelvin);
        }
        double passive = (inside / total) * fluidKelvin + (outerConductance / total) * ambientKelvin;
        double power = Math.min(maximumPowerEuPerTick, Math.max(0.0, total * (targetKelvin - passive)));
        double jacket = passive + power / total;
        return new Flux(power, inside * (jacket - fluidKelvin), jacket);
    }

    record Flux(double powerEu, double heatEu, double jacketKelvin) {}

    /** Compiles an immutable snapshot. Rebuild it when any contact or available power limit changes. */
    public static Profile compile(List<ThermalBoundary> boundaries) {
        return new Profile(boundaries);
    }

    /** Piecewise-linear combined heat response, reusable across chambers and simulation ticks. */
    public static final class Profile {

        private final List<ThermalBoundary> boundaries;
        private final double[] transitions;
        private final BigDecimal[] slopes;
        private final BigDecimal[] intercepts;

        private Profile(List<ThermalBoundary> inputs) {
            boundaries = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(inputs, "boundaries")));
            Map<Double, Line> changes = new TreeMap<>();
            Line initial = new Line(BigDecimal.ZERO, BigDecimal.ZERO);
            for (ThermalBoundary boundary : boundaries) {
                Objects.requireNonNull(boundary, "boundary");
                BigDecimal inside = BigDecimal.valueOf(boundary.innerConductance);
                BigDecimal outside = BigDecimal.valueOf(boundary.outerConductance);
                BigDecimal ambient = BigDecimal.valueOf(boundary.ambientKelvin);
                BigDecimal target = BigDecimal.valueOf(boundary.targetKelvin);
                BigDecimal power = BigDecimal.valueOf(boundary.maximumPowerEuPerTick);
                BigDecimal fraction = inside.divide(inside.add(outside), MathContext.DECIMAL128);
                BigDecimal passiveSlope = fraction.multiply(outside);
                Line passive = new Line(passiveSlope, passiveSlope.multiply(ambient));
                Line limited = new Line(passiveSlope, passive.intercept.add(fraction.multiply(power)));
                initial = initial.add(limited);
                if (power.signum() > 0) {
                    Line regulated = new Line(inside, inside.multiply(target));
                    BigDecimal switchOff = target.add(
                        outside.multiply(target.subtract(ambient))
                            .divide(inside, MathContext.DECIMAL128));
                    BigDecimal switchOn = switchOff.subtract(power.divide(inside, MathContext.DECIMAL128));
                    changes.merge(switchOn.doubleValue(), regulated.subtract(limited), Line::add);
                    changes.merge(switchOff.doubleValue(), passive.subtract(regulated), Line::add);
                }
            }
            transitions = new double[changes.size()];
            slopes = new BigDecimal[changes.size() + 1];
            intercepts = new BigDecimal[changes.size() + 1];
            slopes[0] = initial.slope;
            intercepts[0] = initial.intercept;
            int index = 0;
            for (Map.Entry<Double, Line> change : changes.entrySet()) {
                transitions[index] = change.getKey();
                initial = initial.add(change.getValue());
                slopes[++index] = initial.slope;
                intercepts[index] = initial.intercept;
            }
        }

        List<ThermalBoundary> boundaries() {
            return boundaries;
        }

        BigDecimal heatAtTemperature(double temperature) {
            int index = Arrays.binarySearch(transitions, temperature);
            int segment = index >= 0 ? index + 1 : -index - 1;
            return intercepts[segment].subtract(slopes[segment].multiply(BigDecimal.valueOf(temperature)));
        }

        private record Line(BigDecimal slope, BigDecimal intercept) {

            private Line add(Line other) {
                return new Line(slope.add(other.slope), intercept.add(other.intercept));
            }

            private Line subtract(Line other) {
                return new Line(slope.subtract(other.slope), intercept.subtract(other.intercept));
            }
        }
    }
}
