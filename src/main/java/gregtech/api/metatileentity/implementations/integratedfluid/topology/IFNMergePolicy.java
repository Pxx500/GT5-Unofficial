package gregtech.api.metatileentity.implementations.integratedfluid.topology;

import java.math.BigInteger;

import gregtech.api.metatileentity.implementations.integratedfluid.state.IFNCanonicalState;

public final class IFNMergePolicy {

    private IFNMergePolicy() {}

    public static boolean canMerge(IFNCanonicalState first, IFNCanonicalState second) {
        IFNCanonicalState a = normalize(first);
        IFNCanonicalState b = normalize(second);

        if (a.isEmpty() || b.isEmpty()) {
            return true;
        }
        if (!a.fluidId().equals(b.fluidId())) return false;
        try {
            Math.addExact(a.substanceAmount().rawUnits(), b.substanceAmount().rawUnits());
            Math.addExact(a.totalEnergy().rawUnits(), b.totalEnergy().rawUnits());
            return true;
        } catch (ArithmeticException overflow) {
            return false;
        }
    }

    public static IFNCanonicalState merge(Iterable<IFNCanonicalState> inventories) {
        String substance = null;
        long mass = 0;
        BigInteger energy = BigInteger.ZERO;
        for (var inventory : inventories) {
            if (inventory.isEmpty()) continue;
            String id = inventory.fluidId().get();
            if (substance != null && !substance.equals(id)) throw new IllegalArgumentException("fluid conflict");
            substance = id;
            mass = Math.addExact(mass, inventory.substanceAmount().rawUnits());
            // Cold and hot chambers can cancel even when a partial sum exceeds the ledger
            energy = energy.add(BigInteger.valueOf(inventory.totalEnergy().rawUnits()));
        }
        return IFNCanonicalState.fromRaw(substance, mass, energy.longValueExact());
    }

    private static IFNCanonicalState normalize(IFNCanonicalState state) {
        return state == null ? IFNCanonicalState.empty() : state;
    }
}
