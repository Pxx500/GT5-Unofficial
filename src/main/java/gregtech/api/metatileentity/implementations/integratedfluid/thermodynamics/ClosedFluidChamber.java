package gregtech.api.metatileentity.implementations.integratedfluid.thermodynamics;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/** A fluid inventory whose energy includes both fluid internal energy and passive mechanical compliance. */
public final class ClosedFluidChamber {

    private static final int HEAT_EXCHANGE_ITERATIONS = 48;
    private static final int MASS_TRANSFER_ITERATIONS = 48;
    private static final double PUMP_EFFICIENCY = 0.8;
    private static final double PUMP_FRICTION_PA = 1000;
    // Allows a thermostat to reach its nominal stream despite rounding. The energy debit remains exact
    private static final double STREAM_TEMPERATURE_RESOLUTION_K = 0.001;
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

    public PureFluid substance() {
        return fluid;
    }

    private boolean accepts(PureFluid incoming) {
        return isEmpty() || fluid == incoming;
    }

    /** A machine can retain its last portion without exporting it or treating a closed outlet as delivered. */
    public boolean isUndrainableHeelAt(ClosedFluidChamber outlet, double pressureDropPa) {
        return !isEmpty() && outlet != this && outlet.accepts(fluid)
            && pressurePa() > outlet.pressurePa() + pressureDropPa
            && state.solidMassFraction() < 1 && (outlet.isEmpty() || outlet.state.solidMassFraction() < 1)
            && massKg.compareTo(BigDecimal.valueOf(fluid.packetMicroKg(state.vaporMassFraction()), 6)) <= 0;
    }

    /** Admits whole metered units from a constant-temperature supply at the fluid's reference pressure. */
    public StreamExchange admitReferenceStream(PureFluid supply, double temperatureKelvin, boolean vaporAtBoiling, BigDecimal unitMassKg,
        int offeredUnits, double pressureMarginPa) {
        double ceiling = supply.referencePressurePa() - checkedMargin(pressureMarginPa);
        if (!accepts(supply) || pressurePa() >= ceiling || !supply.supportsTemperature(temperatureKelvin)) return new StreamExchange(this, 0);
        BigDecimal unitEnergy = referenceUnitEnergy(supply, temperatureKelvin, vaporAtBoiling, unitMassKg);
        return wholeUnits(offeredUnits, units -> {
            var next = of(supply, massKg.add(unitMassKg.multiply(units)), volume, totalEnergyEu.add(unitEnergy.multiply(units)));
            return next.pressurePa() <= ceiling ? next : null;
        });
    }

    /** Discharges a single phase without raising its temperature or pressure to meet the external reference. */
    public StreamExchange dischargeReferenceStream(double temperatureKelvin, boolean vaporProduct, BigDecimal unitMassKg, int maximumUnits, double pressureMarginPa) {
        double floor = fluid.referencePressurePa() + checkedMargin(pressureMarginPa);
        if (maximumUnits < 0) throw new IllegalArgumentException("Negative unit limit");
        if (isEmpty() || !fluid.supportsTemperature(temperatureKelvin) || pressurePa() <= floor || state.temperatureKelvin() < temperatureKelvin - STREAM_TEMPERATURE_RESOLUTION_K
            || !state.isPureStream(vaporProduct)) return new StreamExchange(this, 0);
        BigDecimal unitEnergy = referenceUnitEnergy(fluid, temperatureKelvin, vaporProduct, unitMassKg);
        int upper = massKg.divideToIntegralValue(unitMassKg).min(BigDecimal.valueOf(maximumUnits)).intValueExact();
        return wholeUnits(upper, units -> {
            var mass = unitMassKg.multiply(units);
            if (mass.compareTo(massKg) >= 0) return null;
            var debit = outflowEnergy(mass, Draw.METERED_BULK);
            if (debit.compareTo(unitEnergy.multiply(units)) < 0) return null;
            var next = of(fluid, massKg.subtract(mass), volume, totalEnergyEu.subtract(debit));
            return next.pressurePa() >= floor && next.state.temperatureKelvin() >= temperatureKelvin - STREAM_TEMPERATURE_RESOLUTION_K
                && next.state.isPureStream(vaporProduct) ? next : null;
        });
    }

    private static double checkedMargin(double pressureMarginPa) {
        if (!Double.isFinite(pressureMarginPa) || pressureMarginPa < 0) throw new IllegalArgumentException("Invalid pressure margin");
        return pressureMarginPa;
    }

    private static BigDecimal referenceUnitEnergy(PureFluid fluid, double temperatureKelvin, boolean vaporAtBoiling, BigDecimal unitMassKg) {
        if (unitMassKg.signum() <= 0) throw new IllegalArgumentException("Nonpositive unit mass");
        return unitMassKg.multiply(BigDecimal.valueOf(fluid.referenceStreamEnthalpy(temperatureKelvin, vaporAtBoiling)))
            .setScale(6, RoundingMode.HALF_EVEN);
    }

    private StreamExchange wholeUnits(int upper, java.util.function.Function<BigDecimal, ClosedFluidChamber> candidate) {
        if (upper < 0) throw new IllegalArgumentException("Negative unit limit");
        StreamExchange accepted = new StreamExchange(this, 0);
        int trial = upper;
        while (trial > accepted.units()) {
            ClosedFluidChamber next = null;
            try {
                next = candidate.apply(BigDecimal.valueOf(trial));
            } catch (PureFluid.UnsupportedStateException unsupported) {
                // A smaller packet may remain inside the calibrated range
            }
            if (next != null) accepted = new StreamExchange(next, trial);
            else upper = trial - 1;
            trial = (int) (((long) accepted.units() + upper + 1) / 2);
        }
        return accepted;
    }

    public record StreamExchange(ClosedFluidChamber chamber, int units) {}

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
            || !receiver.accepts(fluid)
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
        FluidTransfer candidate = tryTransfer(receiver, upper, initialEntropy, Port.UNMETERED);
        if (candidate != null) {
            return candidate;
        }
        BigDecimal lower = BigDecimal.ZERO;
        FluidTransfer accepted = unchanged;
        for (int i = 0; i < MASS_TRANSFER_ITERATIONS; i++) {
            BigDecimal middle = lower.add(upper)
                .multiply(new BigDecimal("0.5"));
            candidate = tryTransfer(receiver, middle, initialEntropy, Port.UNMETERED);
            if (candidate == null) {
                upper = middle;
            } else {
                lower = middle;
                accepted = candidate;
            }
        }
        return accepted;
    }

    /** Meters a passive port in source liters, settling both ledgers in microkg and microEU. */
    public FluidTransfer meteredTransferTo(ClosedFluidChamber receiver, BigDecimal maximumLiters,
        double maximumOutletPa) {
        return meterPort(receiver, maximumLiters, new Port(Draw.METERED_BULK, maximumOutletPa, 0, null, 0));
    }

    public FluidTransfer meteredTransferWithLossTo(ClosedFluidChamber receiver, BigDecimal maximumLiters, double pressureDropPa) {
        if (!Double.isFinite(pressureDropPa) || pressureDropPa < 0) throw new IllegalArgumentException("Invalid pressure drop");
        return meterPort(receiver, maximumLiters, new Port(Draw.METERED_BULK, Double.MAX_VALUE, pressureDropPa, null, 0));
    }

    /** Draws vapor already present on the inlet side, metered in liters of vapor at inlet conditions. */
    public FluidTransfer meteredVaporTo(ClosedFluidChamber receiver, BigDecimal maximumLiters) {
        return meterPort(receiver, maximumLiters, Port.VAPOR_OUTLET);
    }

    enum Draw { BULK, METERED_BULK, METERED_VAPOR, METERED_LIQUID }

    /** Work enters the liquid stream. Negative shaft work denotes electricity consumed by the stroke. */
    public FluidTransfer pumpLiquidTo(ClosedFluidChamber receiver, BigDecimal maximumLiters,
        double maximumOutletPa, double pressureRisePa, long availableWorkEu) {
        return pumpTo(receiver, maximumLiters, maximumOutletPa, pressureRisePa, availableWorkEu, 0, false);
    }

    public FluidTransfer pumpTo(ClosedFluidChamber receiver, BigDecimal maximumLiters,
        double maximumOutletPa, double pressureRisePa, long availableWorkEu, double minimumInletPa, boolean gasIntake) {
        if (!Double.isFinite(pressureRisePa) || pressureRisePa < 0) throw new IllegalArgumentException("Invalid pump head");
        if (!Double.isFinite(minimumInletPa) || minimumInletPa < 0) throw new IllegalArgumentException("Invalid inlet floor");
        if (availableWorkEu <= 0 || pressurePa() < minimumInletPa) return new FluidTransfer(this, receiver, BigDecimal.ZERO, BigDecimal.ZERO);
        Draw draw = !isEmpty() && state.denseRegion() != PureFluid.DenseRegion.NONE ? Draw.METERED_BULK
            : gasIntake ? Draw.METERED_VAPOR : Draw.METERED_LIQUID;
        return meterPort(receiver, maximumLiters, new Port(draw, maximumOutletPa, -pressureRisePa, null, availableWorkEu), minimumInletPa);
    }

    private record Port(Draw draw, double maximumOutletPa, double requiredInletHeadPa, ExpansionStage stage, long workBudgetEu) {
        static final Port UNMETERED = new Port(Draw.BULK, Double.POSITIVE_INFINITY, 0, null, 0);
        static final Port VAPOR_OUTLET = new Port(Draw.METERED_VAPOR, Double.MAX_VALUE, 0, null, 0);
    }

    public record ExpansionStage(double maximumPressureRatio, double efficiency) {
        public ExpansionStage {
            if (!Double.isFinite(maximumPressureRatio) || maximumPressureRatio <= 1
                || !(efficiency > 0 && efficiency <= 1)) throw new IllegalArgumentException("Invalid expansion stage");
        }
    }

    public FluidTransfer expandTo(ClosedFluidChamber receiver, BigDecimal maximumLiters, ExpansionStage stage, long workBudgetEu) {
        Objects.requireNonNull(stage);
        if (workBudgetEu <= 0 || isEmpty() || !state.canExpand()) {
            return new FluidTransfer(this, receiver, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        return meterPort(receiver, maximumLiters, new Port(Draw.METERED_BULK, Double.MAX_VALUE, 0, stage, workBudgetEu));
    }

    private FluidTransfer meterPort(ClosedFluidChamber receiver, BigDecimal maximumLiters, Port port) {
        return meterPort(receiver, maximumLiters, port, 0);
    }

    private FluidTransfer meterPort(ClosedFluidChamber receiver, BigDecimal maximumLiters, Port port, double minimumInletPa) {
        FluidTransfer unchanged = new FluidTransfer(this, receiver, BigDecimal.ZERO, BigDecimal.ZERO);
        if (isEmpty() || this == receiver || !receiver.accepts(fluid) || maximumLiters.signum() <= 0
            || !Double.isFinite(port.maximumOutletPa()) || receiver.pressurePa() >= port.maximumOutletPa()
            || receiver.pressurePa() + port.requiredInletHeadPa() >= pressurePa()) return unchanged;

        double solid = Math.max(state.solidMassFraction(), receiver.isEmpty() ? 0 : receiver.state.solidMassFraction());
        long upper;
        if (port.draw() == Draw.METERED_VAPOR || port.draw() == Draw.METERED_LIQUID) {
            boolean vapor = port.draw() == Draw.METERED_VAPOR;
            double fraction = vapor ? state.vaporMassFraction() : state.liquidMassFraction();
            if (fraction <= 0 || solid >= 1 || vapor && solid > 0) return unchanged;
            BigDecimal available = massKg.multiply(BigDecimal.valueOf(fraction));
            BigDecimal perKg = BigDecimal.valueOf(fluid.phaseLitersPerKg(state, vapor));
            if (solid > 0) maximumLiters = maximumLiters.multiply(BigDecimal.valueOf(1 - solid));
            upper = maximumLiters.divide(perKg, 6, RoundingMode.DOWN).min(available)
                .movePointRight(6).setScale(0, RoundingMode.DOWN).longValueExact();
        } else {
            upper = meteredBulkMicroKg(maximumLiters, solid);
        }
        long packet = switch (port.draw()) {
            case METERED_LIQUID -> fluid.unitMicroKg(false);
            case METERED_VAPOR -> fluid.unitMicroKg(true);
            default -> fluid.packetMicroKg(state.vaporMassFraction());
        };
        upper /= packet;
        if (upper == 0) return unchanged;
        BigDecimal entropy = entropyEuPerKelvin().add(receiver.entropyEuPerKelvin());
        long low = 0;
        FluidTransfer best = unchanged;
        long trial = upper;
        while (low < upper) {
            var candidate = tryTransfer(receiver, BigDecimal.valueOf(trial * packet, 6), entropy, port);
            if (candidate == null || candidate.source().pressurePa() < minimumInletPa) {
                upper = trial - 1;
            } else {
                best = candidate;
                low = trial;
            }
            trial = upper - (upper - low) / 2;
        }
        return best;
    }

    public record SplitCharge(ClosedFluidChamber source, ClosedFluidChamber first, ClosedFluidChamber second) {}

    /** Meters one inlet charge across two ports. Neither branch can be debited alone. */
    public SplitCharge splitChargeTo(ClosedFluidChamber first, ClosedFluidChamber second, int liters, double firstShare) {
        var unchanged = new SplitCharge(this, first, second);
        if (isEmpty() || this == first || this == second || first == second || !first.accepts(fluid) || !second.accepts(fluid)
            || liters <= 0 || !(firstShare > 0 && firstShare < 1)
            || pressurePa() <= Math.max(first.pressurePa(), second.pressurePa())) return unchanged;
        double solid = Math.max(state.solidMassFraction(), Math.max(first.isEmpty() ? 0 : first.state.solidMassFraction(),
            second.isEmpty() ? 0 : second.state.solidMassFraction()));
        long packet = fluid.packetMicroKg(state.vaporMassFraction());
        long upper = meteredBulkMicroKg(BigDecimal.valueOf(liters), solid) / packet;
        var share = BigDecimal.valueOf(firstShare);
        long minimum = minimumBulkPortions(firstShare);
        long low = minimum - 1;
        var entropy = entropyEuPerKelvin().add(first.entropyEuPerKelvin()).add(second.entropyEuPerKelvin());
        var best = unchanged;
        for (long trial = upper; low < upper; trial = upper - (upper - low) / 2) {
            var mass = BigDecimal.valueOf(trial * packet, 6);
            long firstPackets = share.multiply(BigDecimal.valueOf(trial)).longValue();
            var firstMass = BigDecimal.valueOf(firstPackets * packet, 6);
            var secondMass = mass.subtract(firstMass);
            if (mass.compareTo(massKg) < 0 && firstMass.signum() > 0 && secondMass.signum() > 0) {
                try {
                    var energy = outflowEnergy(mass, Draw.METERED_BULK);
                    var firstEnergy = energy.multiply(firstMass).divide(mass, 6, RoundingMode.HALF_EVEN);
                    var sourceAfter = of(fluid, massKg.subtract(mass), volume, totalEnergyEu.subtract(energy));
                    var a = of(fluid, first.massKg.add(firstMass), first.volume, first.totalEnergyEu.add(firstEnergy));
                    var b = of(fluid, second.massKg.add(secondMass), second.volume, second.totalEnergyEu.add(energy.subtract(firstEnergy)));
                    if (sourceAfter.pressurePa() >= Math.max(a.pressurePa(), b.pressurePa())
                        && sourceAfter.entropyEuPerKelvin().add(a.entropyEuPerKelvin()).add(b.entropyEuPerKelvin()).compareTo(entropy) >= 0) {
                        best = new SplitCharge(sourceAfter, a, b);
                        low = trial;
                        continue;
                    }
                } catch (PureFluid.UnsupportedStateException rejected) {
                    // Retry a smaller common packet
                }
            }
            upper = trial - 1;
        }
        return best;
    }

    /** Checks inlet inventory only. An empty or fully frozen chamber is not an undersized stroke. */
    public boolean intakeStrokeTooSmall(double liters, double firstShare) {
        if (!Double.isFinite(liters) || liters <= 0 || !(firstShare > 0 && firstShare <= 1) || isEmpty() || state.solidMassFraction() >= 1) return false;
        long swept = meteredBulkMicroKg(BigDecimal.valueOf(liters), state.solidMassFraction());
        return swept / fluid.packetMicroKg(state.vaporMassFraction()) < minimumBulkPortions(firstShare);
    }

    private static long minimumBulkPortions(double firstShare) {
        return firstShare == 1 ? 1 : (long) Math.ceil(Math.max(1 / firstShare, 1 / (1 - firstShare)));
    }

    private long meteredBulkMicroKg(BigDecimal maximumLiters, double solidFraction) {
        var liters = BigDecimal.valueOf(volumeLiters());
        return maximumLiters.min(liters).multiply(mobileMicroKg(solidFraction)).divide(liters, 0, RoundingMode.DOWN).longValueExact();
    }

    private BigDecimal mobileMicroKg(double solidFraction) {
        return massKg.movePointRight(6).multiply(BigDecimal.valueOf(1 - solidFraction));
    }

    /** Whole-liter inlet sample containing one bulk portion, before any downstream restrictions. */
    public OptionalInt minimumBulkStrokeLiters() {
        if (isEmpty()) return OptionalInt.empty();
        var mobile = mobileMicroKg(state.solidMassFraction());
        var portion = BigDecimal.valueOf(fluid.packetMicroKg(state.vaporMassFraction()));
        if (mobile.compareTo(portion) < 0) return OptionalInt.empty();
        var liters = portion.multiply(BigDecimal.valueOf(volumeLiters())).divide(mobile, 0, RoundingMode.CEILING);
        return liters.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0 ? OptionalInt.empty() : OptionalInt.of(liters.intValueExact());
    }

    private BigDecimal outflowEnergy(BigDecimal transferredMass, Draw draw) {
        double fraction = transferredMass.divide(massKg, MathContext.DECIMAL128)
            .doubleValue();
        double averageEnthalpy = fluid.averageOutflowEnthalpy(state, volume, massKg.doubleValue(), fraction, draw);
        BigDecimal flowEnergy = transferredMass.multiply(BigDecimal.valueOf(averageEnthalpy));
        if (draw != Draw.BULK) flowEnergy = flowEnergy.setScale(6, RoundingMode.HALF_EVEN);
        return flowEnergy;
    }

    private FluidTransfer tryTransfer(ClosedFluidChamber receiver, BigDecimal transferredMass,
        BigDecimal initialEntropy, Port port) {
        if (transferredMass.signum() == 0 || transferredMass.compareTo(massKg) >= 0) {
            return null;
        }
        try {
            BigDecimal flowEnergy = outflowEnergy(transferredMass, port.draw());
            ClosedFluidChamber sourceAfter = of(
                fluid,
                massKg.subtract(transferredMass),
                volume,
                totalEnergyEu.subtract(flowEnergy));
            BigDecimal exhaustMass = receiver.massKg.add(transferredMass);
            BigDecimal exhaustEnergy = receiver.totalEnergyEu.add(flowEnergy);
            long workEu = 0;
            boolean wetStageExit = false;
            double pumpEuPerPa = 0;
            double inletFloorPa = Math.min(pressurePa(), sourceAfter.pressurePa());
            var stage = port.stage();
            if (stage == null && port.workBudgetEu() > 0) {
                // Estimate against the filled outlet, then verify the paid lift with the final heated state
                double backpressure = Math.max(receiver.pressurePa(), fluid.exhaustPressureBound(receiver.volume,
                    exhaustMass.doubleValue(), exhaustEnergy.divide(exhaustMass, MathContext.DECIMAL128).doubleValue()));
                double inletVolumePerKg;
                if (port.draw() == Draw.METERED_BULK) {
                    inletVolumePerKg = Math.max(volume.litersAtPressure(pressurePa()) / massKg.doubleValue(),
                        volume.litersAtPressure(sourceAfter.pressurePa()) / sourceAfter.massKg.doubleValue());
                } else {
                    boolean vapor = port.draw() == Draw.METERED_VAPOR;
                    inletVolumePerKg = fluid.phaseLitersPerKg(state, vapor);
                    if ((vapor ? sourceAfter.state.vaporMassFraction() : sourceAfter.state.liquidMassFraction()) > 0)
                        inletVolumePerKg = Math.max(inletVolumePerKg, fluid.phaseLitersPerKg(sourceAfter.state, vapor));
                }
                pumpEuPerPa = 0.001 * transferredMass.doubleValue() * inletVolumePerKg;
                double rise = Math.max(0, backpressure - inletFloorPa);
                var cost = BigDecimal.valueOf(pumpEuPerPa * (rise + PUMP_FRICTION_PA) / PUMP_EFFICIENCY)
                    .setScale(0, RoundingMode.CEILING).max(BigDecimal.ONE);
                if (cost.compareTo(BigDecimal.valueOf(port.workBudgetEu())) > 0) return null;
                workEu = -cost.longValueExact();
                exhaustEnergy = exhaustEnergy.add(cost);
            } else if (stage != null) {
                double backpressure = Math.max(receiver.pressurePa(), fluid.exhaustPressureBound(receiver.volume,
                    exhaustMass.doubleValue(), exhaustEnergy.divide(exhaustMass, MathContext.DECIMAL128).doubleValue()));
                var ideal = fluid.idealExpansionOutlet(sourceAfter.state(), backpressure, stage.maximumPressureRatio());
                if (ideal == null) return null;
                double head = Math.max(0, sourceAfter.state().specificEnthalpyEuPerKg() - ideal.specificEnthalpyEuPerKg());
                var work = transferredMass.multiply(BigDecimal.valueOf(head * stage.efficiency())).setScale(0, RoundingMode.DOWN);
                if (work.compareTo(BigDecimal.valueOf(port.workBudgetEu())) > 0) return null;
                if (work.signum() == 0) {
                    // A larger packet may produce a whole EU. An oversized or wet packet must instead be reduced
                    return head > 0 ? new FluidTransfer(this, receiver, BigDecimal.ZERO, BigDecimal.ZERO) : null;
                }
                workEu = work.longValueExact();
                exhaustEnergy = exhaustEnergy.subtract(work);
                wetStageExit = fluid.wetStageExit(ideal, flowEnergy.subtract(work).divide(transferredMass, MathContext.DECIMAL128).doubleValue());
            }
            ClosedFluidChamber receiverAfter = of(fluid, exhaustMass, receiver.volume, exhaustEnergy);
            if (workEu < 0 && pumpEuPerPa * (receiverAfter.pressurePa() - inletFloorPa) > -workEu) return null;
            if (receiverAfter.pressurePa() > port.maximumOutletPa() || sourceAfter.pressurePa() < receiverAfter.pressurePa() + port.requiredInletHeadPa() || sourceAfter.entropyEuPerKelvin()
                .add(receiverAfter.entropyEuPerKelvin())
                .compareTo(initialEntropy) < 0) {
                return null;
            }
            return new FluidTransfer(sourceAfter, receiverAfter, transferredMass, flowEnergy, workEu, wetStageExit);
        } catch (PureFluid.UnsupportedStateException unsupported) {
            return null;
        }
    }

    private BigDecimal entropyEuPerKelvin() {
        return isEmpty() ? BigDecimal.ZERO : massKg.multiply(BigDecimal.valueOf(state.specificEntropyEuPerKgKelvin()));
    }

    public record FluidTransfer(ClosedFluidChamber source, ClosedFluidChamber receiver, BigDecimal transferredMassKg,
        BigDecimal transportedEnergyEu, long shaftWorkEu, boolean wetStageExit) {
        private FluidTransfer(ClosedFluidChamber source, ClosedFluidChamber receiver, BigDecimal mass, BigDecimal energy) {
            this(source, receiver, mass, energy, 0, false);
        }
    }

    /** Returns the complete heated state, or rejects unsupported states without modifying this inventory. */
    public ClosedFluidChamber withHeat(BigDecimal heatEu) {
        Objects.requireNonNull(heatEu, "heatEu");
        return heatEu.signum() == 0 ? this : of(fluid, massKg, volume, totalEnergyEu.add(heatEu));
    }

    public double temperatureLimit(boolean heating) {
        return heating ? fluid.maximumTemperatureKelvin() : fluid.minimumTemperatureKelvin();
    }

    public boolean supportsTemperature(double kelvin) {
        return fluid.supportsTemperature(kelvin);
    }

    /** A proposed thermal step. The caller must pay the work before installing the returned chamber. */
    public PoweredStep conditionToward(double targetKelvin, double effectiveCop, long availableWorkEu) {
        if (!supportsTemperature(targetKelvin) || !(effectiveCop > 0) || !Double.isFinite(effectiveCop) || availableWorkEu < 0)
            throw new IllegalArgumentException("Invalid powered thermal contact");
        if (isEmpty()) return new PoweredStep(this, 0, true);
        BigDecimal needed = energyAtTemperature(targetKelvin).subtract(totalEnergyEu).setScale(6, RoundingMode.DOWN);
        if (needed.signum() == 0) return new PoweredStep(this, 0, true);
        var cop = BigDecimal.valueOf(effectiveCop);
        var moved = cop.multiply(BigDecimal.valueOf(availableWorkEu)).setScale(6, RoundingMode.DOWN).min(needed.abs());
        long cost = moved.divide(cop, 0, RoundingMode.CEILING).longValueExact();
        return new PoweredStep(withHeat(needed.signum() > 0 ? moved : moved.negate()), cost, moved.compareTo(needed.abs()) == 0);
    }

    public record PairedStep(ClosedFluidChamber primary, ClosedFluidChamber partner, long workEu, boolean complete) {}

    /** Both streams receive the electrical work between them. No ambient reservoir participates. */
    public PairedStep conditionWithPartner(ClosedFluidChamber partner, double target, double cop, long workBudget) {
        if (partner == this || partner.isEmpty() || isEmpty() || workBudget < 0) throw new IllegalArgumentException("Invalid working pair");
        var entropy = entropyEuPerKelvin().add(partner.entropyEuPerKelvin());
        long low = 0, high = workBudget;
        var best = new PairedStep(this, partner, 0, false);
        for (long trial = high; low < high; trial = high - (high - low) / 2) {
            try {
                var step = conditionToward(target, cop, trial);
                var primaryHeat = step.chamber().totalEnergyEu.subtract(totalEnergyEu);
                var other = partner.withHeat(BigDecimal.valueOf(step.workEu()).subtract(primaryHeat));
                if (step.chamber().entropyEuPerKelvin().add(other.entropyEuPerKelvin()).compareTo(entropy) >= 0) {
                    best = new PairedStep(step.chamber(), other, step.workEu(), step.complete());
                    low = trial;
                    continue;
                }
            } catch (PureFluid.UnsupportedStateException rejected) {
                // A smaller work packet may fit both calibrated states
            }
            high = trial - 1;
        }
        return best;
    }

    public record PoweredStep(ClosedFluidChamber chamber, long workEu, boolean complete) {}

    /** Accept whole EU up to the calibrated temperature ceiling, leaving an unsupported charge unchanged. */
    public ClosedFluidChamber heatByUpTo(long offeredEu) {
        if (offeredEu < 0) throw new IllegalArgumentException("Negative heat offer");
        if (offeredEu == 0 || isEmpty()) return this;
        BigDecimal headroom = energyAtTemperature(fluid.maximumTemperatureKelvin()).subtract(totalEnergyEu);
        BigDecimal accepted = headroom.min(BigDecimal.valueOf(offeredEu)).setScale(0, RoundingMode.DOWN);
        if (accepted.signum() <= 0) return this;
        try {
            return withHeat(accepted);
        } catch (PureFluid.UnsupportedStateException unsupported) {
            return this;
        }
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

        BigDecimal minimumEnergy = energyAtTemperature(Math.max(fluid.minimumTemperatureKelvin(), other.state.temperatureKelvin()));
        BigDecimal otherMaximumEnergy = other.energyAtTemperature(Math.min(other.fluid.maximumTemperatureKelvin(), state.temperatureKelvin()));
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

    /** Conducts heat through a passive contact, settling one signed microEU amount on both inventories. */
    public HeatExchange conductHeat(ClosedFluidChamber other, double conductanceBudgetEuPerKelvin) {
        if (!Double.isFinite(conductanceBudgetEuPerKelvin) || conductanceBudgetEuPerKelvin < 0) {
            throw new IllegalArgumentException("Invalid thermal conductance budget");
        }
        var unchanged = new HeatExchange(this, other, BigDecimal.ZERO);
        if (other == this || isEmpty() || other.isEmpty()) return unchanged;
        double difference = Math.abs(state.temperatureKelvin() - other.state.temperatureKelvin());
        var budget = BigDecimal.valueOf(conductanceBudgetEuPerKelvin).multiply(BigDecimal.valueOf(difference))
            .setScale(6, RoundingMode.DOWN);
        if (budget.signum() == 0) return unchanged;
        try {
            var proposal = exchangeHeat(other, budget);
            var settled = proposal.heatToFirstEu().setScale(6, RoundingMode.DOWN);
            if (settled.signum() == 0) return unchanged;
            if (settled.compareTo(proposal.heatToFirstEu()) == 0) return proposal;
            return transferHeatTo(other, settled.negate());
        } catch (PureFluid.UnsupportedStateException unsupported) {
            return unchanged;
        }
    }

    private BigDecimal energyAtTemperature(double temperatureKelvin) {
        return BigDecimal
            .valueOf(fluid.specificTotalEnergyAtTemperature(volume, massKg.doubleValue(), temperatureKelvin))
            .multiply(massKg);
    }

    /** Passive contact with a uniform reservoir. The conductance budget includes the elapsed ticks. */
    public ClosedFluidChamber exchangeWithAmbient(double ambientKelvin, double conductanceBudgetEuPerKelvin) {
        return exchangeWithAmbient(ambientKelvin, conductanceBudgetEuPerKelvin, ambientKelvin);
    }

    /** A thermostat may stop passive contact before equilibrium but cannot reverse its direction. */
    public ClosedFluidChamber exchangeWithAmbient(double ambientKelvin, double conductanceBudgetEuPerKelvin, double stopKelvin) {
        if (!Double.isFinite(stopKelvin)) throw new IllegalArgumentException("Invalid contact setpoint");
        if (!Double.isFinite(ambientKelvin) || ambientKelvin < 0 || !Double.isFinite(conductanceBudgetEuPerKelvin)
            || conductanceBudgetEuPerKelvin < 0) throw new IllegalArgumentException("Invalid passive thermal contact");
        if (isEmpty() || conductanceBudgetEuPerKelvin == 0) return this;
        double difference = ambientKelvin - state.temperatureKelvin();
        if (Math.abs(difference) <= EQUILIBRIUM_MARGIN_KELVIN
            || Math.abs(difference) * conductanceBudgetEuPerKelvin < 0.000001) return this;
        double limit = difference > 0 ? Math.min(stopKelvin, ambientKelvin) : Math.max(stopKelvin, ambientKelvin);
        double target = Math.max(fluid.minimumTemperatureKelvin(), Math.min(fluid.maximumTemperatureKelvin(), limit));
        BigDecimal available = energyAtTemperature(target).subtract(totalEnergyEu);
        if (available.signum() != (difference > 0 ? 1 : -1)) return this;
        BigDecimal heat = BigDecimal.valueOf(conductanceBudgetEuPerKelvin).multiply(BigDecimal.valueOf(Math.abs(difference)))
            .min(available.abs()).setScale(6, RoundingMode.DOWN);
        try {
            return withHeat(difference > 0 ? heat : heat.negate());
        } catch (PureFluid.UnsupportedStateException unsupported) {
            return this;
        }
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
