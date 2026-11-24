package gregtech.common.tileentities.machines.multi.heatpump;

import java.util.function.LongPredicate;

import net.minecraft.nbt.NBTTagCompound;

import gregtech.api.metatileentity.implementations.integratedfluid.IFNMachineThermo;
import gregtech.api.metatileentity.implementations.integratedfluid.IFNNetworkTransferGate;
import gregtech.api.metatileentity.implementations.integratedfluid.IntegratedFluidNetwork;
import gregtech.api.metatileentity.implementations.integratedfluid.thermodynamics.ClosedFluidChamber;
import gregtech.api.metatileentity.implementations.integratedfluid.state.IFNCanonicalState;
import gregtech.api.metatileentity.implementations.integratedfluid.thermodynamics.PureFluid;

/** Physical working inventory. An unfinished batch cannot reach the outlet. */
public final class HeatPumpMachineProcessState {

    private static final int VOLUME_L = 1000;
    private static final int POWER_CYCLE_TICKS = 20;
    private ClosedFluidChamber chamber = ClosedFluidChamber.empty(PureFluid.water(), VOLUME_L);
    private ClosedFluidChamber partner = ClosedFluidChamber.empty(PureFluid.water(), VOLUME_L);
    public enum Layout { SINGLE, SPLIT, EXCHANGER }

    public record Intake(IntegratedFluidNetwork primary, IntegratedFluidNetwork other, int liters, double primaryShare,
        boolean bluePrimary) {
        Layout layout() {
            return other != null ? Layout.EXCHANGER : primaryShare == 1 ? Layout.SINGLE : Layout.SPLIT;
        }
    }

    private Layout layout = Layout.SINGLE;
    private boolean bluePrimary, primaryDelivered, partnerDelivered;
    private double signedPairCop;
    private int signedWorkPerTick;
    private int ticksLeft;
    private IFNMachineThermo.HeatPumpMetrics metrics;
    private boolean active;
    private boolean ready;
    private boolean awaitingTemperatureChange;
    private double targetKelvin;

    public boolean active() {
        return active;
    }

    public boolean ready() {
        return active && ready;
    }

    public boolean bluePrimary() {
        return bluePrimary;
    }

    public Layout layout() {
        return layout;
    }

    public boolean fill(Intake intake, double target, double lowerTolerance, double upperTolerance) {
        if (!intake.primary().supportsFluidTemperature(target) || !admit(intake)) return false;
        signedWorkPerTick = 0;
        ticksLeft = 0;
        setBatchTarget(target, lowerTolerance, upperTolerance);
        return true;
    }

    public boolean awaitingTemperatureChange() {
        return awaitingTemperatureChange;
    }

    public boolean retargetStalledCharge(double target, double lowerTolerance, double upperTolerance) {
        if (!awaitingTemperatureChange || target == targetKelvin || !chamber.supportsTemperature(target)) return false;
        awaitingTemperatureChange = false;
        setBatchTarget(target, lowerTolerance, upperTolerance);
        return true;
    }

    private void setBatchTarget(double target, double lowerTolerance, double upperTolerance) {
        targetKelvin = target;
        double t = temperature();
        ready = t >= target - lowerTolerance && t <= target + upperTolerance;
    }

    public boolean fillForPower(Intake intake, int euPerTick, boolean heating) {
        if (euPerTick <= 0 || !admit(intake)) return false;
        signedWorkPerTick = heating ? euPerTick : -euPerTick;
        ticksLeft = POWER_CYCLE_TICKS;
        targetKelvin = chamber.temperatureLimit(heating);
        return true;
    }

    public boolean fillForPairedCop(Intake intake, double cop, boolean heating) {
        if (!(cop > 1) || !Double.isFinite(cop) || !admit(intake)) return false;
        signedPairCop = heating ? cop : -cop;
        signedWorkPerTick = 0;
        ticksLeft = 0;
        targetKelvin = chamber.temperatureLimit(heating);
        ready = reachedPairCop(chamber, partner);
        return true;
    }

    private boolean reachedPairCop(ClosedFluidChamber primary, ClosedFluidChamber other) {
        double t = primary.state().temperatureKelvin();
        double partnerT = other.state().temperatureKelvin();
        return signedPairCop > 0 ? (t - partnerT) * signedPairCop >= t : (partnerT - t) * -signedPairCop >= t;
    }

    private boolean admit(Intake intake) {
        if (active) return false;
        // The physical heels stay on their original colors when the selected control stream changes
        var primaryHeel = bluePrimary == intake.bluePrimary() ? chamber : partner;
        var otherHeel = bluePrimary == intake.bluePrimary() ? partner : chamber;
        switch (intake.layout()) {
            case SINGLE -> {
                var filled = intake.primary().fillMachineChamber(primaryHeel, intake.liters());
                if (filled == primaryHeel) return false;
                chamber = filled;
                partner = otherHeel;
            }
            case SPLIT -> {
                var filled = intake.primary().fillSplitChambers(primaryHeel, otherHeel, intake.liters(), intake.primaryShare());
                if (filled == null) return false;
                chamber = filled.first();
                partner = filled.second();
            }
            case EXCHANGER -> {
                var filled = intake.primary().fillIndependentChambers(intake.other(), primaryHeel, otherHeel, intake.liters());
                if (filled == null) return false;
                chamber = filled.primary();
                partner = filled.partner();
            }
        }
        signedPairCop = 0;
        layout = intake.layout();
        bluePrimary = intake.bluePrimary();
        primaryDelivered = false;
        partnerDelivered = false;
        active = true;
        ready = false;
        return true;
    }

    public boolean inletPressureTooLow(Intake intake) {
        boolean sameSide = bluePrimary == intake.bluePrimary();
        var first = sameSide ? chamber : partner;
        var second = sameSide ? partner : chamber;
        var other = intake.other() == null ? intake.primary() : intake.other();
        return intake.primary().getAmountQ() > 0 && intake.primary().getPressurePa() <= first.pressurePa()
            || intake.layout() != Layout.SINGLE && other.getAmountQ() > 0 && other.getPressurePa() <= second.pressurePa();
    }

    public enum PressureBlock { NONE, SINGLE, RED, BLUE, BOTH }

    public PressureBlock blockedOutlet(IntegratedFluidNetwork outlet, IntegratedFluidNetwork secondOutlet) {
        boolean primary = !primaryDelivered && IFNNetworkTransferGate.canReceiveFromMachine(outlet)
            && outlet.getPressurePa() >= chamber.pressurePa();
        if (layout == Layout.SINGLE) return primary ? PressureBlock.SINGLE : PressureBlock.NONE;
        boolean secondary = !partnerDelivered && IFNNetworkTransferGate.canReceiveFromMachine(secondOutlet)
            && secondOutlet.getPressurePa() >= partner.pressurePa();
        if (primary && secondary) return PressureBlock.BOTH;
        if (!primary && !secondary) return PressureBlock.NONE;
        boolean blueBlocked = bluePrimary ? primary : secondary;
        return blueBlocked ? PressureBlock.BLUE : PressureBlock.RED;
    }

    public boolean discharge(IntegratedFluidNetwork outlet, IntegratedFluidNetwork secondOutlet) {
        if (!ready()) return false;
        boolean progressed = false;
        if (!primaryDelivered && outlet != null) {
            var next = outlet.drainMachineChamber(chamber, VOLUME_L, 0);
            if (next.finished()) {
                primaryDelivered = true;
                chamber = next.remaining();
                progressed = true;
            }
        }
        if (layout != Layout.SINGLE && !partnerDelivered && secondOutlet != null) {
            var next = secondOutlet.drainMachineChamber(partner, VOLUME_L, 0);
            if (next.finished()) {
                partnerDelivered = true;
                partner = next.remaining();
                progressed = true;
            }
        }
        if (primaryDelivered && (layout == Layout.SINGLE || partnerDelivered)) {
            active = false;
            ready = false;
        }
        return progressed;
    }

    public double temperature() {
        return chamber.isEmpty() ? 0 : chamber.state().temperatureKelvin();
    }

    public IFNMachineThermo.HeatPumpMetrics metrics() {
        return metrics;
    }

    public long advance(double ambientKelvin, long availableEu, LongPredicate pay) {
        if (!active || ready || awaitingTemperatureChange || availableEu <= 0 || !(ambientKelvin > 0) || !Double.isFinite(ambientKelvin)) return 0;
        double current = temperature();
        boolean heating = signedWorkPerTick == 0 ? targetKelvin >= current : signedWorkPerTick > 0;
        long budget = signedWorkPerTick == 0 ? availableEu : Math.abs((long) signedWorkPerTick);
        if (layout != Layout.SINGLE) return advancePaired(availableEu, pay, current, heating, budget);
        double end = targetKelvin;
        if (signedWorkPerTick != 0) {
            var initial = IFNMachineThermo.computeReservoirHeatPumpMetrics(current, current, ambientKelvin, heating);
            // The best possible step bounds the temperature lift before any EU is spent
            end = chamber.conditionToward(targetKelvin, initial.effectiveCop(), budget).chamber().state().temperatureKelvin();
        }
        metrics = IFNMachineThermo.computeReservoirHeatPumpMetrics(current, end, ambientKelvin, heating);
        var step = chamber.conditionToward(targetKelvin, metrics.effectiveCop(), budget);
        if (step.workEu() > availableEu || (step.workEu() > 0 && !pay.test(step.workEu()))) return 0;
        chamber = step.chamber();
        if (signedWorkPerTick != 0) ticksLeft--;
        ready = step.complete() || (signedWorkPerTick != 0 && ticksLeft == 0);
        return step.workEu();
    }

    private long advancePaired(long availableEu, LongPredicate pay, double current, boolean heating, long budget) {
        double otherT = partner.state().temperatureKelvin();
        var initial = IFNMachineThermo.computeReservoirHeatPumpMetrics(current, current, otherT, heating);
        double initialCop = heating ? Math.max(1, initial.effectiveCop()) : initial.effectiveCop();
        var forecast = chamber.conditionToward(targetKelvin, initialCop, budget);
        double counterCop = heating ? initialCop - 1 : initialCop + 1;
        double otherEnd = otherT;
        if (counterCop > 0) otherEnd = partner.conditionToward(partner.temperatureLimit(!heating), counterCop,
            forecast.workEu()).chamber().state().temperatureKelvin();
        var estimate = IFNMachineThermo.computeReservoirHeatPumpMetrics(current, forecast.chamber().state().temperatureKelvin(), otherEnd, heating);
        double cop = heating ? Math.max(1, estimate.effectiveCop()) : estimate.effectiveCop();
        var step = chamber.conditionWithPartner(partner, targetKelvin, cop, budget);
        // A small supply or an unpaid offer must never park a charge. Both full-target forecasts must fit the budget
        if (signedWorkPerTick == 0 && signedPairCop == 0 && step.workEu() == 0 && !step.complete() && forecast.complete()
            && chamber.conditionToward(targetKelvin, cop, budget).complete()) {
            awaitingTemperatureChange = true;
            return 0;
        }
        boolean copReached = signedPairCop != 0 && reachedPairCop(step.primary(), step.partner());
        if (copReached) {
            // Stop at the first whole EU which meets the lift between the two actual streams
            long low = 0, high = step.workEu();
            while (high - low > 1) {
                long trial = low + (high - low) / 2;
                var candidate = chamber.conditionWithPartner(partner, targetKelvin, cop, trial);
                if (reachedPairCop(candidate.primary(), candidate.partner())) {
                    high = trial;
                    step = candidate;
                } else low = trial;
            }
        }
        if (step.workEu() > availableEu || (step.workEu() > 0 && !pay.test(step.workEu()))) return 0;
        if (step.workEu() > 0) {
            double primaryT = step.primary().state().temperatureKelvin();
            var finalLift = IFNMachineThermo.computeReservoirHeatPumpMetrics(primaryT, primaryT, step.partner().state().temperatureKelvin(), heating);
            float measuredCop = (float) (step.primary().totalEnergyEu().subtract(chamber.totalEnergyEu()).abs().doubleValue() / step.workEu());
            metrics = new IFNMachineThermo.HeatPumpMetrics(finalLift.cop(), finalLift.efficiencyPenalty(),
                Math.abs(primaryT - step.partner().state().temperatureKelvin()), measuredCop);
        }
        chamber = step.primary();
        partner = step.partner();
        if (signedWorkPerTick != 0 && step.workEu() > 0) ticksLeft--;
        ready = copReached || step.complete() || (signedWorkPerTick != 0 && ticksLeft == 0);
        return step.workEu();
    }

    public void save(NBTTagCompound tag) {
        IFNCanonicalState.writeWorkingChamber(tag, "pumpPartner", partner);
        tag.setDouble("pumpPairCop", signedPairCop);
        tag.setBoolean("pumpSplit", layout == Layout.SPLIT);
        tag.setBoolean("pumpExchanger", layout == Layout.EXCHANGER);
        tag.setBoolean("pumpBluePrimary", bluePrimary);
        tag.setBoolean("pumpPrimaryDelivered", primaryDelivered);
        tag.setBoolean("pumpPartnerDelivered", partnerDelivered);
        IFNCanonicalState.writeWorkingChamber(tag, "pump", chamber);
        tag.setDouble("pumpBatchTargetK", targetKelvin);
        tag.setInteger("pumpWorkPerTick", signedWorkPerTick);
        tag.setInteger("pumpTicksLeft", ticksLeft);
        tag.setBoolean("pumpBatchActive", active);
        tag.setBoolean("pumpOutputReady", ready);
        tag.setBoolean("pumpTemperatureBlocked", awaitingTemperatureChange);
    }

    public void load(NBTTagCompound tag) {
        partner = IFNCanonicalState.readWorkingChamber(tag, "pumpPartner", VOLUME_L);
        signedPairCop = tag.getDouble("pumpPairCop");
        layout = tag.getBoolean("pumpExchanger") ? Layout.EXCHANGER : tag.getBoolean("pumpSplit") ? Layout.SPLIT : Layout.SINGLE;
        bluePrimary = tag.getBoolean("pumpBluePrimary");
        primaryDelivered = tag.getBoolean("pumpPrimaryDelivered");
        partnerDelivered = tag.getBoolean("pumpPartnerDelivered");
        chamber = IFNCanonicalState.readWorkingChamber(tag, "pump", VOLUME_L);
        targetKelvin = tag.getDouble("pumpBatchTargetK");
        signedWorkPerTick = tag.getInteger("pumpWorkPerTick");
        ticksLeft = tag.getInteger("pumpTicksLeft");
        active = !chamber.isEmpty() && tag.getBoolean("pumpBatchActive");
        ready = active && tag.getBoolean("pumpOutputReady");
        awaitingTemperatureChange = active && !ready && tag.getBoolean("pumpTemperatureBlocked");
    }
}
