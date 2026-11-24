package gregtech.api.metatileentity.implementations.integratedfluid.safety;

import java.util.Collection;

import gregtech.api.metatileentity.implementations.integratedfluid.IFNPressurePolicy;
import gregtech.api.metatileentity.implementations.integratedfluid.IIntegratedFluidMember;

public final class IFNOperationalLimits {

    private final float maxPressureBar;
    private final float maxTemperatureKelvin;

    private IFNOperationalLimits(float maxPressureBar, float maxTemperatureKelvin) {
        this.maxPressureBar = maxPressureBar;
        this.maxTemperatureKelvin = maxTemperatureKelvin;
    }

    public static IFNOperationalLimits ofMaxPressureBar(float maxPressureBar) {
        return new IFNOperationalLimits(maxPressureBar, Float.POSITIVE_INFINITY);
    }

    public static IFNOperationalLimits fromMembers(Collection<? extends IIntegratedFluidMember> members) {
        float pressureLimit = members.isEmpty() ? IFNPressurePolicy.DEFAULT_MAX_PRESSURE_BAR : Float.POSITIVE_INFINITY;
        float maxTemperatureKelvin = Float.POSITIVE_INFINITY;
        for (IIntegratedFluidMember member : members) {
            pressureLimit = Math.min(pressureLimit, member.getMaxPressureDifferentialBar());
            maxTemperatureKelvin = Math.min(maxTemperatureKelvin, member.getMaxTemperatureKelvin());
            if (member.getAccumulatorContribution() > 0) {
                pressureLimit = Math.min(pressureLimit, member.getAccumulatorMaxPressureBar());
            }
        }
        return new IFNOperationalLimits(pressureLimit, maxTemperatureKelvin);
    }

    public float maxPressureBar() {
        return maxPressureBar;
    }

    public float maxTemperatureKelvin() {
        return maxTemperatureKelvin;
    }
}
