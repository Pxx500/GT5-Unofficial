package gregtech.api.metatileentity.implementations.integratedfluid;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import gregtech.api.interfaces.tileentity.IBasicEnergyContainer;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.integratedfluid.amount.VolumeAmount;
import gregtech.api.metatileentity.implementations.integratedfluid.safety.IFNFailureCandidateSelector;
import gregtech.api.metatileentity.implementations.integratedfluid.safety.IFNLimitWarningTracker;
import gregtech.api.metatileentity.implementations.integratedfluid.safety.IFNOperationalFailurePolicy;
import gregtech.api.metatileentity.implementations.integratedfluid.safety.IFNOperationalLimits;
import gregtech.api.metatileentity.implementations.integratedfluid.safety.IFNOperationalSafetyEvaluation;
import gregtech.api.metatileentity.implementations.integratedfluid.safety.IFNPressureLimitEvaluation;
import gregtech.api.metatileentity.implementations.integratedfluid.safety.IFNPressureWarningTracker;
import gregtech.api.metatileentity.implementations.integratedfluid.safety.IFNTemperatureLimitEvaluation;
import gregtech.api.metatileentity.implementations.integratedfluid.safety.IFNTemperatureLimitStatus;
import gregtech.api.metatileentity.implementations.integratedfluid.state.IFNCanonicalState;
import gregtech.api.metatileentity.implementations.integratedfluid.state.IFNNetworkStatus;
import gregtech.api.metatileentity.implementations.integratedfluid.thermodynamics.ChamberVolume;
import gregtech.api.metatileentity.implementations.integratedfluid.thermodynamics.ClosedFluidChamber;
import gregtech.api.metatileentity.implementations.integratedfluid.thermodynamics.PureFluid;
import gregtech.api.metatileentity.implementations.integratedfluid.topology.IFNTopologySnapshot;

/**
 * Manages a network of connected integrated fluid hatches and pipes.
 * All hatches connected by the same segment of pipes share fluid data.
 * The network capacity is dynamic and depends on the number and type of members.
 */
public class IntegratedFluidNetwork {

    /**
     * Default temperature for machine controls without a fluid reading.
     */
    public static final float DEFAULT_TEMPERATURE = 300.0f;

    /**
     * Ambient temperature for heat loss calculations (in Kelvin).
     */
    public static final float AMBIENT_TEMPERATURE = 300.0f;

    /** Passive pipe conductance in EU per tick per kelvin of temperature difference. */
    public static final double PIPE_CONDUCTANCE_EU_PER_TICK_KELVIN = 0.05;
    private long ambientExchangeTick = Long.MIN_VALUE;
    private long ambientHeatMicroEu;

    private IFNCanonicalState inventory = IFNCanonicalState.empty();

    /**
     * All members (pipes and hatches) connected to this network.
     */
    private final Set<IIntegratedFluidMember> members = new HashSet<>();

    /**
     * Current pressure of the network in bar.
     */
    private float pressure;
    private ClosedFluidChamber chamber;
    private IFNTopologySnapshot topologySnapshot;

    private final UUID networkId;
    private IntegratedFluidNetworkSavedData savedData;
    private boolean pending = false;
    private int expectedMemberCount = 0;
    private long chamberBaseLiters;
    private long chamberStrokeLiters;
    private String frozenReason;
    private final IFNPressureWarningTracker pressureWarningTracker = new IFNPressureWarningTracker();
    private final IFNLimitWarningTracker temperatureWarningTracker = new IFNLimitWarningTracker();

    public IntegratedFluidNetwork(UUID networkId) {
        this.networkId = networkId != null ? networkId : UUID.randomUUID();
    }

    public IntegratedFluidNetwork() {
        this(UUID.randomUUID());
    }

    void setSavedData(IntegratedFluidNetworkSavedData savedData) {
        this.savedData = savedData;
    }

    private void persistState() {
        if (savedData != null) {
            savedData.upsertState(networkId, this);
        }
    }

    /**
     * Adds a member to this network.
     */
    public void addMember(IIntegratedFluidMember member) {
        if (members.add(member)) topologySnapshot = null;
        member.setNetwork(this);
        member.setNetworkId(networkId);
        updatePressure();
    }

    /**
     * Removes a member from this network.
     */
    public void removeMember(IIntegratedFluidMember member) {
        removeMember(member, inventory);
    }

    void removeMember(IIntegratedFluidMember member, IFNCanonicalState remaining) {
        inventory = remaining;
        if (members.remove(member)) topologySnapshot = null;
        if (member.getNetwork() == this) {
            member.setNetwork(null);
        }
        updatePressure();
    }

    /**
     * Gets the maximum geometric capacity of this network in volume units.
     * Capacity is calculated dynamically based on members:
     * - Each pipe adds 100 volume units
     * - Each output hatch adds 10,000 volume units
     * - Each input hatch adds 10,000 volume units
     * - Injector hatches add 0 volume units
     */
    public int getMaxCapacity() {
        return toCapacityInt(getTopologySnapshot().baseVolume());
    }

    public int getAccumulatorCapacity() {
        return toCapacityInt(getTopologySnapshot().accumulatorVolume());
    }

    public IFNOperationalLimits getOperationalLimits() {
        return IFNOperationalLimits.fromMembers(members);
    }

    public IFNPressureLimitEvaluation getPressureLimitEvaluation() {
        return IFNPressureLimitEvaluation.evaluate(pressure, getOperationalLimits(), isIncompleteNetwork());
    }

    public IFNTemperatureLimitEvaluation getTemperatureLimitEvaluation() {
        return IFNTemperatureLimitEvaluation
            .evaluate(getTemperature(), getOperationalLimits().maxTemperatureKelvin(), isIncompleteNetwork());
    }

    public IFNPressureLimitEvaluation tickPressureSafety() {
        IFNPressureLimitEvaluation evaluation = getPressureLimitEvaluation();
        pressureWarningTracker.update(evaluation);
        return evaluation;
    }

    public int getPressureWarningTicks() {
        return pressureWarningTracker.warningTicks();
    }

    public boolean shouldRollPressureFailureThisTick() {
        return pressureWarningTracker.shouldRollFailureThisTick();
    }

    public IFNTemperatureLimitEvaluation tickTemperatureSafety() {
        IFNTemperatureLimitEvaluation evaluation = getTemperatureLimitEvaluation();
        temperatureWarningTracker.update(evaluation.status() == IFNTemperatureLimitStatus.WARNING);
        return evaluation;
    }

    public int getTemperatureWarningTicks() {
        return temperatureWarningTracker.warningTicks();
    }

    public boolean shouldRollTemperatureFailureThisTick() {
        return temperatureWarningTracker.shouldRollFailureThisTick();
    }

    public IFNOperationalSafetyEvaluation tickOperationalSafety() {
        IFNPressureLimitEvaluation pressureEvaluation = tickPressureSafety();
        IFNTemperatureLimitEvaluation temperatureEvaluation = tickTemperatureSafety();
        return new IFNOperationalSafetyEvaluation(
            pressureEvaluation,
            temperatureEvaluation,
            shouldRollPressureFailureThisTick(),
            shouldRollTemperatureFailureThisTick());
    }

    public Optional<IIntegratedFluidMember> selectFailureCandidate(IFNOperationalSafetyEvaluation evaluation) {
        if (evaluation.pressure()
            .isOverLimit()) {
            Optional<IIntegratedFluidMember> pressureCandidate = IFNFailureCandidateSelector
                .selectWeakestPressureCandidate(members);
            if (pressureCandidate.isPresent()) {
                return pressureCandidate;
            }
        }
        if (evaluation.temperature()
            .isOverLimit()) {
            return IFNFailureCandidateSelector.selectWeakestTemperatureCandidate(members);
        }
        return Optional.empty();
    }

    public Optional<IIntegratedFluidMember> applyOperationalFailure(Random random) {
        IFNOperationalSafetyEvaluation evaluation = tickOperationalSafety();
        if (!IFNOperationalFailurePolicy.shouldFailNow(evaluation, random)) {
            return Optional.empty();
        }
        Optional<IIntegratedFluidMember> failureCandidate = selectFailureCandidate(evaluation);
        if (failureCandidate.isPresent()) {
            clearFluid();
        }
        return failureCandidate;
    }

    public int getBaseCapacity() {
        return getMaxCapacity();
    }

    public int getTotalCapacity() {
        return toCapacityInt(getTopologySnapshot().totalVolume());
    }

    private static int toCapacityInt(VolumeAmount volume) {
        long liters = volume.toWholeLiters();
        return liters > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) liters;
    }

    /**
     * Gets the available space in the network.
     */
    public int getAvailableSpace() {
        double used = getOccupiedVolume();
        double available = getTotalCapacity() - used;
        if (available <= 0.0d) {
            return 0;
        }
        if (available > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) available;
    }

    public Fluid getFluid() {
        return inventory.isEmpty() || !hasSolvedFluid() ? null : FluidRegistry.getFluid(getFluidName());
    }

    public String getFluidName() {
        return inventory.fluidId().orElse(null);
    }

    public IFNCanonicalState getCanonicalState() {
        return inventory;
    }

    public Optional<PureFluid.State> getSolvedState() {
        return hasSolvedFluid() ? Optional.of(chamber.state()) : Optional.empty();
    }

    public boolean supportsFluidTemperature(double kelvin) {
        return hasSolvedFluid() && chamber.supportsTemperature(kelvin);
    }

    public void replaceCanonicalState(IFNCanonicalState state) {
        IFNCanonicalState next = state == null ? IFNCanonicalState.empty() : state;
        if (next.isEmpty()) {
            clearFluid();
            return;
        }

        inventory = next;
        updatePressure();
    }

    public IFNTopologySnapshot getTopologySnapshot() {
        if (topologySnapshot == null) topologySnapshot = IFNTopologySnapshot.fromMembers(members, expectedMemberCount);
        return topologySnapshot;
    }

    public long getAmountQ() {
        return inventory.substanceAmount().rawUnits();
    }

    public double getSpecificEnthalpy() {
        return hasSolvedFluid() ? chamber.state().specificEnthalpyEuPerKg() : 0;
    }

    public float getDerivedTemperature() {
        return hasSolvedFluid() ? (float) chamber.state().temperatureKelvin() : DEFAULT_TEMPERATURE;
    }

    public double getSpecificVolume() {
        return hasSolvedFluid() ? chamber.volumeLiters() / chamber.massKg().doubleValue() : 0;
    }

    public double getQuality() {
        return hasSolvedFluid() ? chamber.state().vaporMassFraction() : 0;
    }

    public double getOccupiedVolume() {
        return hasSolvedFluid() ? chamber.volumeLiters() : 0;
    }

    /**
     * Gets the count of members in this network.
     */
    public int getMemberCount() {
        return members.size();
    }

    /** Absolute pressure for comparisons with physical machine chambers. */
    public double getPressurePa() {
        return chamber == null ? 0 : chamber.pressurePa();
    }

    /** Current pressure in bar. */
    public float getPressure() {
        return pressure;
    }

    /**
     * Gets the current temperature in Kelvin.
     */
    public float getTemperature() {
        return getDerivedTemperature();
    }

    public UUID getNetworkId() {
        return networkId;
    }

    public boolean isPending() {
        return pending;
    }

    public void setPending(boolean pending) {
        this.pending = pending;
    }

    public boolean isFrozen() {
        return frozenReason != null;
    }

    public void freeze(String reason) {
        this.frozenReason = reason == null || reason.trim()
            .isEmpty() ? "frozen" : reason;
        persistState();
    }

    public void clearFrozen() {
        this.frozenReason = null;
        updatePressure();
    }

    public String getFrozenReason() {
        return frozenReason;
    }

    public IFNNetworkStatus getNetworkStatus() {
        if (isFrozen()) {
            return IFNNetworkStatus.FROZEN;
        }
        if (isIncompleteNetwork()) {
            return IFNNetworkStatus.PENDING;
        }
        return IFNNetworkStatus.NORMAL;
    }

    public int getExpectedMemberCount() {
        return expectedMemberCount;
    }

    public void setExpectedMemberCount(int expectedMemberCount) {
        if (this.expectedMemberCount != expectedMemberCount) topologySnapshot = null;
        this.expectedMemberCount = expectedMemberCount;
        updatePressure();
    }

    void loadInventory(IFNCanonicalState inventory, int expectedMembers, long baseLiters, long strokeLiters, String frozenReason) {
        this.inventory = inventory;
        this.expectedMemberCount = expectedMembers;
        chamberBaseLiters = baseLiters;
        chamberStrokeLiters = strokeLiters;
        pending = members.size() < expectedMembers;
        topologySnapshot = null;
        this.frozenReason = frozenReason == null || frozenReason.trim()
            .isEmpty() ? null : frozenReason;
        updatePressure();
    }

    /**
     * Clears all fluid from the network.
     * Used during network splits to prevent duplication.
     */
    public void clearFluid() {
        inventory = IFNCanonicalState.empty();
        pressure = 0.0f;
        updatePressure();
    }

    /**
     * Gets all members in this network.
     */
    public Set<IIntegratedFluidMember> getMembers() {
        return new HashSet<>(members);
    }

    /**
     * Counts the number of pipes in this network.
     * Pipes are members that contribute capacity but are not hatches.
     */
    public int getPipeCount() {
        int pipeCount = 0;
        for (IIntegratedFluidMember member : members) {
            // Pipes typically contribute 100 mB capacity
            // Hatches contribute 10,000 mB or 0 mB (injectors)
            int contribution = member.getCapacityContribution();
            if (contribution > 0 && contribution < 1000) {
                pipeCount++;
            }
        }
        return pipeCount;
    }

    void exchangeAmbientHeat(float ambientKelvin, long worldTick, int elapsedTicks) {
        if (ambientExchangeTick == worldTick) return;
        ambientExchangeTick = worldTick;
        ambientHeatMicroEu = 0;
        if (isIncompleteNetwork() || !hasSolvedFluid() || elapsedTicks <= 0 || !Float.isFinite(ambientKelvin)
            || ambientKelvin < 0) return;
        double budget = getTopologySnapshot().passiveConductanceEuPerTickKelvin() * elapsedTicks;
        ambientHeatMicroEu = applyThermalContact(ambientKelvin, budget, ambientKelvin);
    }

    /** Exposes a shared machine circuit to the reservoir without debiting a second fluid inventory. */
    public void exchangeThroughRadiator(double ambientKelvin, double conductanceEuPerTickKelvin, double stopKelvin) {
        if (IFNNetworkTransferGate.isOperational(this) && hasSolvedFluid()) {
            applyThermalContact(ambientKelvin, conductanceEuPerTickKelvin, stopKelvin);
        }
    }

    private long applyThermalContact(double ambientKelvin, double conductanceBudget, double stopKelvin) {
        var next = chamber.exchangeWithAmbient(ambientKelvin, conductanceBudget, stopKelvin);
        if (next == chamber) return 0;
        long heat;
        IFNCanonicalState ledger;
        try {
            ledger = IFNCanonicalState.fromChamber(next);
            heat = Math.subtractExact(ledger.totalEnergy().rawUnits(), inventory.totalEnergy().rawUnits());
        } catch (ArithmeticException outsideLedger) {
            return 0;
        }
        installChamber(next, ledger);
        persistState();
        return heat;
    }

    public long getAmbientExchangeTick() {
        return ambientExchangeTick;
    }

    public long getAmbientHeatMicroEu() {
        return ambientHeatMicroEu;
    }

    /**
     * Moves members only after the combined inventory has been validated.
     */
    void absorb(Set<IntegratedFluidNetwork> participants, IFNCanonicalState combined) {
        for (var donor : participants) {
            if (donor == this) continue;
            for (var member : donor.members) {
                members.add(member);
                member.setNetwork(this);
                member.setNetworkId(networkId);
            }
            donor.members.clear();
            donor.topologySnapshot = null;
            donor.clearFluid();
        }
        // Solve once against the final volume, never against a half-moved chamber
        topologySnapshot = null;
        replaceCanonicalState(combined);
    }

    IFNCanonicalState inventoryIn(IFNTopologySnapshot part) {
        return inventoryIn(part.chamberVolume());
    }

    private IFNCanonicalState inventoryIn(Optional<ChamberVolume> geometry) {
        if (!hasSolvedFluid() || geometry.isEmpty()) return IFNCanonicalState.empty();
        double pressurePa = chamber.pressurePa();
        // Fluid follows occupied volume. Each hydrophore keeps its own stored mechanical energy
        long microKg = BigDecimal.valueOf(getAmountQ()).multiply(BigDecimal.valueOf(geometry.get().litersAtPressure(pressurePa)))
            .divide(BigDecimal.valueOf(chamber.volumeLiters()), 0, RoundingMode.DOWN).longValueExact();
        if (microKg == 0) return IFNCanonicalState.empty();
        BigDecimal springEu = BigDecimal.valueOf(geometry.get().elasticEnergyEu(pressurePa));
        long microEu = chamber.internalEnergyEu().movePointRight(6).multiply(BigDecimal.valueOf(microKg))
            .divide(BigDecimal.valueOf(getAmountQ()), MathContext.DECIMAL128)
            .add(springEu.movePointRight(6)).setScale(0, RoundingMode.HALF_EVEN).longValueExact();
        return IFNCanonicalState.fromRaw(chamber.substance().id(), microKg, microEu);
    }

    boolean hasSolvedFluid() {
        return chamber != null && !chamber.isEmpty();
    }

    int admit(FluidStack stack, boolean commit, double ambientKelvin) {
        if (!IFNNetworkTransferGate.canInjectFromGtPipe(this) || chamber == null) return 0;
        IFNForgeFluidProfile profile = IFNForgeFluidProfile.of(stack);
        if (profile == null || stack.amount <= 0) return 0;
        return settleExternal(profile.admit(chamber, stack.amount, ambientKelvin), commit);
    }

    FluidStack export(FluidStack requested, int maximumUnits, boolean commit, double ambientKelvin) {
        if (!IFNNetworkTransferGate.canExtractToGtPipe(this) || !hasSolvedFluid() || maximumUnits <= 0) return null;
        var profile = IFNForgeFluidProfile.outlet(chamber);
        Fluid output = profile == null ? null : profile.forgeFluid();
        if (output == null || requested != null && (requested.tag != null || requested.getFluid() != output)) return null;
        int units = settleExternal(profile.discharge(chamber, maximumUnits, ambientKelvin), commit);
        return units == 0 ? null : new FluidStack(output, units);
    }

    private int settleExternal(ClosedFluidChamber.StreamExchange exchange, boolean commit) {
        if (exchange.units() == 0) return 0;
        var next = exchange.chamber();
        try {
            var ledger = IFNCanonicalState.fromChamber(next);
            if (commit) {
                installChamber(next, ledger);
                persistState();
            }
        } catch (ArithmeticException outOfRange) {
            return 0;
        }
        return exchange.units();
    }

    long meterTo(IntegratedFluidNetwork outlet, long packetMicroLiters, double maximumOutletPa) {
        if (!canTransferTo(outlet)) return 0;
        return commitMovedMass(outlet, chamber.meteredTransferTo(outlet.chamber, BigDecimal.valueOf(packetMicroLiters, 6), maximumOutletPa));
    }

    /** Fills a machine's physical chamber from this network, without crossing the Forge fluid boundary. */
    public ClosedFluidChamber fillMachineChamber(ClosedFluidChamber machine, int packetLiters) {
        if (!IFNNetworkTransferGate.canProvideToMachine(this) || !hasSolvedFluid()) return machine;
        var move = chamber.meteredTransferTo(machine, BigDecimal.valueOf(packetLiters), Double.MAX_VALUE);
        return settleMachinePort(machine, move, true);
    }

    public boolean intakeStrokeTooSmall(double liters, double primaryShare) {
        return IFNNetworkTransferGate.canProvideToMachine(this) && chamber != null && chamber.intakeStrokeTooSmall(liters, primaryShare);
    }

    OptionalInt minimumBulkStrokeLiters() {
        return chamber != null && IFNNetworkTransferGate.canProvideToMachine(this)
            ? chamber.minimumBulkStrokeLiters() : OptionalInt.empty();
    }

    public record MachineDischarge(ClosedFluidChamber remaining, boolean finished) {}

    /** Completing an outlet stroke may leave the entire sub-portion charge in the machine. */
    public MachineDischarge drainMachineChamber(ClosedFluidChamber machine, int packetLiters, double pressureDropPa) {
        if (!IFNNetworkTransferGate.canReceiveFromMachine(this) || chamber == null || packetLiters <= 0)
            return new MachineDischarge(machine, false);
        var move = machine.meteredTransferWithLossTo(chamber, BigDecimal.valueOf(packetLiters), pressureDropPa);
        var remaining = settleMachinePort(machine, move, false);
        return new MachineDischarge(remaining, remaining != machine || machine.isUndrainableHeelAt(chamber, pressureDropPa));
    }

    public ClosedFluidChamber.SplitCharge fillSplitChambers(ClosedFluidChamber primary, ClosedFluidChamber partner,
        int liters, double primaryShare) {
        if (!IFNNetworkTransferGate.canProvideToMachine(this) || !hasSolvedFluid()) return null;
        var next = chamber.splitChargeTo(primary, partner, liters, primaryShare);
        if (next.source() == chamber) return null;
        IFNCanonicalState ledger;
        try {
            IFNCanonicalState.fromChamber(next.first());
            IFNCanonicalState.fromChamber(next.second());
            ledger = IFNCanonicalState.fromChamber(next.source());
        } catch (ArithmeticException outsideRange) {
            return null;
        }
        installChamber(next.source(), ledger);
        persistState();
        return next;
    }

    public record WorkingPair(ClosedFluidChamber primary, ClosedFluidChamber partner) {}

    /** Each inlet meters its own source liters. Install both debits only after both charges fit. */
    public WorkingPair fillIndependentChambers(IntegratedFluidNetwork other, ClosedFluidChamber primary,
        ClosedFluidChamber partner, int liters) {
        if (other == null || other == this || !IFNNetworkTransferGate.canProvideToMachine(this)
            || !IFNNetworkTransferGate.canProvideToMachine(other) || !hasSolvedFluid() || !other.hasSolvedFluid()) return null;
        var limit = BigDecimal.valueOf(liters);
        var first = chamber.meteredTransferTo(primary, limit, Double.MAX_VALUE);
        if (first.transferredMassKg().signum() == 0) return null;
        var second = other.chamber.meteredTransferTo(partner, limit, Double.MAX_VALUE);
        if (second.transferredMassKg().signum() == 0) return null;
        try {
            IFNCanonicalState.fromChamber(first.receiver());
            IFNCanonicalState.fromChamber(second.receiver());
        } catch (ArithmeticException outsideRange) {
            return null;
        }
        return commitPair(other, first.source(), second.source()) ? new WorkingPair(first.receiver(), second.receiver()) : null;
    }

    private ClosedFluidChamber settleMachinePort(ClosedFluidChamber previous, ClosedFluidChamber.FluidTransfer move,
        boolean fillingMachine) {
        if (move.transferredMassKg().signum() == 0) return previous;
        var retained = fillingMachine ? move.receiver() : move.source();
        var network = fillingMachine ? move.source() : move.receiver();
        IFNCanonicalState ledger;
        try {
            IFNCanonicalState.fromChamber(retained);
            ledger = IFNCanonicalState.fromChamber(network);
        } catch (ArithmeticException outsideRange) {
            return previous;
        }
        installChamber(network, ledger);
        persistState();
        return retained;
    }

    long drawVaporTo(IntegratedFluidNetwork outlet, BigDecimal maximumLiters) {
        if (!canTransferTo(outlet)) return 0;
        return commitMovedMass(outlet, chamber.meteredVaporTo(outlet.chamber, maximumLiters));
    }

    private boolean canTransferTo(IntegratedFluidNetwork outlet) {
        return outlet != this && IFNNetworkTransferGate.isOperational(this) && IFNNetworkTransferGate.isOperational(outlet)
            && chamber != null && outlet.chamber != null;
    }

    private long commitMovedMass(IntegratedFluidNetwork outlet, ClosedFluidChamber.FluidTransfer move) {
        if (move.transferredMassKg().signum() == 0) return 0;
        return commitPair(outlet, move.source(), move.receiver()) ? move.transferredMassKg().movePointRight(6).longValueExact() : 0;
    }

    long heatFromElectricity(IGregTechTileEntity supply, long maximumEu) {
        long eu = Math.min(maximumEu, supply.getStoredEU());
        if (eu <= 0 || !IFNNetworkTransferGate.isOperational(this) || !hasSolvedFluid()) return 0;
        var next = chamber.heatByUpTo(eu);
        if (next == chamber) return 0;
        IFNCanonicalState ledger;
        long accepted = next.totalEnergyEu().subtract(chamber.totalEnergyEu()).longValueExact();
        try {
            ledger = IFNCanonicalState.fromChamber(next);
        } catch (ArithmeticException outsideRange) {
            return 0;
        }
        if (!supply.decreaseStoredEnergyUnits(accepted, false)) return 0;
        installChamber(next, ledger);
        persistState();
        return accepted;
    }

    long pumpTo(IntegratedFluidNetwork outlet, IGregTechTileEntity supply, BigDecimal strokeLiters,
        double outletPa, double availableRisePa, long maximumEu, double minimumInletPa, boolean gasIntake) {
        if (!canTransferTo(outlet)) return 0;
        var moved = chamber.pumpTo(outlet.chamber, strokeLiters, outletPa, availableRisePa,
            Math.min(maximumEu, supply.getStoredEU()), minimumInletPa, gasIntake);
        long cost = -moved.shaftWorkEu();
        return cost > 0 && commitPair(outlet, moved.source(), moved.receiver(),
            () -> supply.decreaseStoredEnergyUnits(cost, false)) ? cost : 0;
    }

    ClosedFluidChamber.FluidTransfer expandTo(IntegratedFluidNetwork outlet, BigDecimal maximumLiters, ClosedFluidChamber.ExpansionStage stage,
        IBasicEnergyContainer dynamo, long maximumWorkEu) {
        if (!canTransferTo(outlet) || !hasExpandableWater()) return null;
        long budget = Math.min(maximumWorkEu, dynamo.getEUCapacity() - dynamo.getStoredEU());
        if (budget <= 0) return null;
        var move = chamber.expandTo(outlet.chamber, maximumLiters, stage, budget);
        long work = move.shaftWorkEu();
        return work > 0 && commitPair(outlet, move.source(), move.receiver(), () -> dynamo.increaseStoredEnergyUnits(work, false))
            ? move : null;
    }

    boolean containsWater() {
        return hasSolvedFluid() && chamber.substance() == PureFluid.water();
    }

    boolean hasExpandableWater() {
        return containsWater() && chamber.state().canExpand();
    }

    long bypassSteamTo(IntegratedFluidNetwork outlet, BigDecimal liters) {
        if (canTransferTo(outlet) && containsWater()
            && (chamber.state().vaporMassFraction() > 0 || chamber.state().denseRegion() != PureFluid.DenseRegion.NONE))
            return commitMovedMass(outlet, chamber.meteredTransferTo(outlet.chamber, liters, Double.MAX_VALUE));
        return 0;
    }

    long conductHeatTo(IntegratedFluidNetwork other, double conductanceBudgetEuPerKelvin) {
        if (other == this || !IFNNetworkTransferGate.isOperational(this) || !IFNNetworkTransferGate.isOperational(other)
            || !hasSolvedFluid() || !other.hasSolvedFluid()) return 0;
        var exchange = chamber.conductHeat(other.chamber, conductanceBudgetEuPerKelvin);
        if (exchange.heatToFirstEu().signum() == 0) return 0;
        long toOther = exchange.heatToFirstEu().negate().movePointRight(6).longValueExact();
        return commitPair(other, exchange.first(), exchange.second()) ? toOther : 0;
    }

    private boolean commitPair(IntegratedFluidNetwork outlet, ClosedFluidChamber sourceChamber, ClosedFluidChamber outletChamber) {
        return commitPair(outlet, sourceChamber, outletChamber, () -> true);
    }

    private boolean commitPair(IntegratedFluidNetwork outlet, ClosedFluidChamber sourceChamber, ClosedFluidChamber outletChamber,
        java.util.function.BooleanSupplier settleWork) {
        IFNCanonicalState sourceAfter;
        IFNCanonicalState outletAfter;
        try {
            sourceAfter = IFNCanonicalState.fromChamber(sourceChamber);
            outletAfter = IFNCanonicalState.fromChamber(outletChamber);
        } catch (ArithmeticException outOfRange) {
            return false;
        }
        // Both ledgers must be representable before either network changes
        if (!settleWork.getAsBoolean()) return false;
        installChamber(sourceChamber, sourceAfter);
        outlet.installChamber(outletChamber, outletAfter);
        persistState();
        outlet.persistState();
        return true;
    }


    private void installChamber(ClosedFluidChamber next, IFNCanonicalState ledger) {
        inventory = ledger;
        chamber = next;
        pressure = (float) (next.pressurePa() / 100_000);
    }

    long chamberBaseLiters() {
        return chamberBaseLiters;
    }

    long chamberStrokeLiters() {
        return chamberStrokeLiters;
    }

    void suspendMember(IIntegratedFluidMember member) {
        if (!members.remove(member)) return;
        expectedMemberCount = Math.max(expectedMemberCount, members.size() + 1);
        pending = true;
        topologySnapshot = null;
        member.setNetwork(null);
        // Unloading changes visibility, not mass, energy or physical geometry
        persistState();
    }

    void resumeMember(IIntegratedFluidMember member) {
        if (!members.add(member)) return;
        topologySnapshot = null;
        member.setNetwork(this);
        pending = members.size() < expectedMemberCount;
        if (!pending) updatePressure();
    }

    void destroyWhileIncomplete(IIntegratedFluidMember member) {
        long base = chamberBaseLiters - member.getCapacityContribution();
        long stroke = chamberStrokeLiters - member.getAccumulatorContribution();
        if (base < 0 || stroke < 0) throw new IllegalStateException("Removed VFN volume exceeds saved geometry");
        var remaining = base == chamberBaseLiters && stroke == chamberStrokeLiters ? inventory
            : inventoryIn(IFNTopologySnapshot.chamberVolume(base, stroke));
        chamberBaseLiters = base;
        chamberStrokeLiters = stroke;
        expectedMemberCount--;
        removeMember(member, remaining);
        member.setNetworkId(null);
    }

    private void updatePressure() {
        var topology = getTopologySnapshot();
        if (!topology.incomplete()) {
            chamberBaseLiters = topology.baseVolume().toWholeLiters();
            chamberStrokeLiters = topology.accumulatorVolume().toWholeLiters();
        }
        var geometry = IFNTopologySnapshot.chamberVolume(chamberBaseLiters, chamberStrokeLiters);
        chamber = null;
        pressure = 0;
        var model = inventory.isEmpty() ? Optional.of(PureFluid.water()) : PureFluid.forSubstance(getFluidName());
        if (model.isEmpty()) {
            if (frozenReason == null) frozenReason = "Unsupported VFN substance: " + getFluidName();
        } else if (geometry.isPresent()) {
            try {
                chamber = ClosedFluidChamber.of(model.get(), inventory.substanceAmount().kilograms(),
                    geometry.get(), BigDecimal.valueOf(inventory.totalEnergy().rawUnits(), 6));
                pressure = (float) (chamber.pressurePa() / 100_000);
            } catch (IllegalArgumentException invalid) {
                frozenReason = "Inventory outside supported chamber geometry";
            }
        }
        persistState();
    }


    private boolean isIncompleteNetwork() {
        return pending || (expectedMemberCount > 0 && members.size() < expectedMemberCount);
    }

    /**
     * Clears all members from this network (for cleanup).
     */
    public void clear() {
        for (IIntegratedFluidMember member : new HashSet<>(members)) {
            removeMember(member);
        }
        clearFluid();
    }
}
