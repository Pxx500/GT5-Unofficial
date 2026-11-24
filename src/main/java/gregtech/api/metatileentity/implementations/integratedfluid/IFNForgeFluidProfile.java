package gregtech.api.metatileentity.implementations.integratedfluid;

import java.math.BigDecimal;

import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import gregtech.api.metatileentity.implementations.integratedfluid.thermodynamics.ClosedFluidChamber;
import gregtech.api.metatileentity.implementations.integratedfluid.thermodynamics.PureFluid;

/** Nominal material and flow energy carried by one untagged external fluid unit. */
enum IFNForgeFluidProfile {
    WATER("water", false),
    STEAM("steam", true),
    OXYGEN("oxygen", true),
    HYDROGEN("hydrogen", true),
    NITROGEN("nitrogen", true),
    HELIUM("helium", true),
    COOLANT("ic2coolant", false),
    SUPER_COOLANT("supercoolant", false);

    private static final IFNForgeFluidProfile[] PROFILES = values();

    private final String forgeId;
    private final PureFluid model;
    final long amountQ;
    private final boolean vapor;

    IFNForgeFluidProfile(String forgeId, boolean vapor) {
        this.forgeId = forgeId;
        this.model = PureFluid.forSubstance("steam".equals(forgeId) ? "water" : forgeId).orElseThrow();
        this.amountQ = model.unitMicroKg(vapor);
        this.vapor = vapor;
    }

    ClosedFluidChamber.StreamExchange admit(ClosedFluidChamber receiver, int offeredUnits, double ambientKelvin) {
        return receiver.admitReferenceStream(model, this == STEAM ? 373.15 : ambientKelvin, vapor,
            BigDecimal.valueOf(amountQ, 6), offeredUnits, IFNPressurePolicy.FORGE_PRESSURE_MARGIN_PA);
    }

    ClosedFluidChamber.StreamExchange discharge(ClosedFluidChamber source, int maximumUnits, double ambientKelvin) {
        return source.dischargeReferenceStream(this == STEAM ? 373.15 : ambientKelvin, vapor,
            BigDecimal.valueOf(amountQ, 6), maximumUnits, IFNPressurePolicy.FORGE_PRESSURE_MARGIN_PA);
    }

    static IFNForgeFluidProfile of(FluidStack stack) {
        if (stack == null || stack.tag != null || stack.getFluid() == null) return null;
        String id = stack.getFluid().getName();
        for (var profile : PROFILES) if (profile.forgeId.equals(id)) return profile;
        return null;
    }

    static IFNForgeFluidProfile outlet(ClosedFluidChamber chamber) {
        for (var profile : PROFILES) {
            if (chamber.substance() == profile.model
                && chamber.state().isPureStream(profile.vapor)) return profile;
        }
        return null;
    }

    net.minecraftforge.fluids.Fluid forgeFluid() {
        return FluidRegistry.getFluid(forgeId);
    }
}
