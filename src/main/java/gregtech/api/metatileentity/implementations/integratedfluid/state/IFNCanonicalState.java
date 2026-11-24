package gregtech.api.metatileentity.implementations.integratedfluid.state;

import java.util.Locale;
import java.util.Optional;
import java.math.BigDecimal;

import net.minecraft.nbt.NBTTagCompound;

import gregtech.api.metatileentity.implementations.integratedfluid.thermodynamics.ClosedFluidChamber;
import gregtech.api.metatileentity.implementations.integratedfluid.thermodynamics.PureFluid;

import gregtech.api.metatileentity.implementations.integratedfluid.amount.EnergyAmount;
import gregtech.api.metatileentity.implementations.integratedfluid.amount.SubstanceAmount;

public final class IFNCanonicalState {

    private static final IFNCanonicalState EMPTY = new IFNCanonicalState(null, SubstanceAmount.ZERO, EnergyAmount.ZERO);

    private final String fluidId;
    private final SubstanceAmount substanceAmount;
    private final EnergyAmount totalEnergy;

    private IFNCanonicalState(String fluidId, SubstanceAmount substanceAmount, EnergyAmount totalEnergy) {
        this.fluidId = fluidId;
        this.substanceAmount = substanceAmount;
        this.totalEnergy = totalEnergy;
    }

    public static IFNCanonicalState fromChamber(ClosedFluidChamber chamber) {
        return fromRaw(chamber.substance().id(), chamber.massKg().movePointRight(6).longValueExact(),
            chamber.totalEnergyEu().movePointRight(6).longValueExact());
    }

    public static void writeWorkingChamber(NBTTagCompound tag, String prefix, ClosedFluidChamber chamber) {
        var state = fromChamber(chamber);
        tag.setString(prefix + "Substance", state.fluidId().orElse(""));
        tag.setLong(prefix + "MicroKg", state.substanceAmount().rawUnits());
        tag.setLong(prefix + "MicroEu", state.totalEnergy().rawUnits());
    }

    public static ClosedFluidChamber readWorkingChamber(NBTTagCompound tag, String prefix, int liters) {
        long mass = tag.getLong(prefix + "MicroKg");
        long energy = tag.getLong(prefix + "MicroEu");
        if (mass == 0 && energy == 0) return ClosedFluidChamber.empty(PureFluid.water(), liters);
        var fluid = PureFluid.forSubstance(tag.getString(prefix + "Substance"))
            .orElseThrow(() -> new IllegalArgumentException("Unknown substance in " + prefix + " chamber"));
        return ClosedFluidChamber.of(fluid, BigDecimal.valueOf(mass, 6), liters, BigDecimal.valueOf(energy, 6));
    }

    public static IFNCanonicalState empty() {
        return EMPTY;
    }

    public static IFNCanonicalState fromRaw(String fluidId, long amountQ, long energyQ) {
        return of(fluidId, SubstanceAmount.fromRawUnits(amountQ), EnergyAmount.fromRawUnits(energyQ));
    }

    public static IFNCanonicalState of(String fluidId, SubstanceAmount substanceAmount, EnergyAmount totalEnergy) {
        SubstanceAmount normalizedSubstance = substanceAmount == null ? SubstanceAmount.ZERO : substanceAmount;
        if (normalizedSubstance.isZero()) {
            return EMPTY;
        }
        if (fluidId == null || fluidId.trim().isEmpty()) {
            throw new IllegalArgumentException("non-empty IFN state requires a fluid id");
        }
        EnergyAmount normalizedEnergy = totalEnergy == null ? EnergyAmount.ZERO : totalEnergy;
        String id = fluidId.trim().toLowerCase(Locale.ROOT);
        return new IFNCanonicalState("steam".equals(id) ? "water" : id, normalizedSubstance, normalizedEnergy);
    }

    public boolean isEmpty() {
        return substanceAmount.isZero();
    }

    public Optional<String> fluidId() {
        return Optional.ofNullable(fluidId);
    }

    public SubstanceAmount substanceAmount() {
        return substanceAmount;
    }

    public EnergyAmount totalEnergy() {
        return totalEnergy;
    }
}
