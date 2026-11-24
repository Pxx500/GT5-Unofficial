package gregtech.api.metatileentity.implementations.integratedfluid.topology;

import java.util.Collection;
import java.util.Optional;

import gregtech.api.metatileentity.implementations.integratedfluid.IIntegratedFluidMember;
import gregtech.api.metatileentity.implementations.integratedfluid.amount.VolumeAmount;
import gregtech.api.metatileentity.implementations.integratedfluid.thermodynamics.ChamberVolume;

public final class IFNTopologySnapshot {

    private static final double HYDROPHORE_PRELOAD_PA = 100_000.0;
    private static final double HYDROPHORE_FULL_STROKE_PA = 1_000_000.0;

    private final int memberCount;
    private final VolumeAmount baseVolume;
    private final VolumeAmount accumulatorVolume;
    private final double conductanceEuPerTickKelvin;
    private final boolean incomplete;
    private final Optional<ChamberVolume> chamberVolume;

    private IFNTopologySnapshot(int memberCount, VolumeAmount baseVolume, VolumeAmount accumulatorVolume,
        boolean incomplete, double conductanceEuPerTickKelvin) {
        this.memberCount = memberCount;
        this.conductanceEuPerTickKelvin = conductanceEuPerTickKelvin;
        this.baseVolume = baseVolume;
        this.accumulatorVolume = accumulatorVolume;
        this.incomplete = incomplete;
        long base = baseVolume.toWholeLiters();
        long stroke = accumulatorVolume.toWholeLiters();
        chamberVolume = incomplete ? Optional.empty() : chamberVolume(base, stroke);
    }

    public static Optional<ChamberVolume> chamberVolume(long base, long stroke) {
        return base == 0 ? Optional.empty()
            : Optional.of(stroke == 0 ? ChamberVolume.rigid(base)
                : new ChamberVolume(base, stroke / (HYDROPHORE_FULL_STROKE_PA - HYDROPHORE_PRELOAD_PA),
                    HYDROPHORE_PRELOAD_PA, stroke));
    }

    public static IFNTopologySnapshot fromMembers(Collection<? extends IIntegratedFluidMember> members,
        int expectedMemberCount) {
        long baseLiters = 0L;
        long accumulatorLiters = 0L;
        int count = 0;
        double conductance = 0;

        if (members != null) {
            for (IIntegratedFluidMember member : members) {
                if (member == null) {
                    continue;
                }
                count++;
                conductance += Math.max(0, member.getPassiveConductanceEuPerTickKelvin());
                baseLiters = Math.addExact(baseLiters, Math.max(0, member.getCapacityContribution()));
                accumulatorLiters = Math.addExact(accumulatorLiters, Math.max(0, member.getAccumulatorContribution()));
            }
        }

        boolean incomplete = expectedMemberCount > 0 && count < expectedMemberCount;
        return new IFNTopologySnapshot(
            count,
            VolumeAmount.fromLiters(baseLiters),
            VolumeAmount.fromLiters(accumulatorLiters),
            incomplete, conductance
        );
    }

    public double passiveConductanceEuPerTickKelvin() {
        return conductanceEuPerTickKelvin;
    }

    public int memberCount() {
        return memberCount;
    }

    public VolumeAmount baseVolume() {
        return baseVolume;
    }

    public VolumeAmount accumulatorVolume() {
        return accumulatorVolume;
    }

    public VolumeAmount totalVolume() {
        return VolumeAmount.fromRawUnits(Math.addExact(baseVolume.rawUnits(), accumulatorVolume.rawUnits()));
    }

    public boolean incomplete() {
        return incomplete;
    }

    public Optional<ChamberVolume> chamberVolume() {
        return chamberVolume;
    }
}
