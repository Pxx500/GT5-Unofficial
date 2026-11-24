package gregtech.api.metatileentity.implementations.integratedfluid;

import java.util.UUID;

import net.minecraftforge.fluids.Fluid;

public final class IFNTestSupport {

    private static final Fluid LIQUID = new Fluid("ifn_test_liquid");
    private static final Fluid VAPOR = new Fluid("ifn_test_vapor");

    private IFNTestSupport() {}

    public static Fluid liquidFluid() {
        return LIQUID;
    }

    public static Fluid vaporFluid() {
        return VAPOR;
    }

    public static Fluid waterFluid() {
        return new Fluid("water");
    }

    public static IntegratedFluidNetwork newNetwork(Fluid fluid, int baseCapacity, int accumulatorCapacity,
        float maxPressureBar) {
        IntegratedFluidNetwork network = new TestNetwork(UUID.randomUUID(), fluid);
        network.addMember(new TestMember(baseCapacity, accumulatorCapacity, maxPressureBar));
        return network;
    }

    /** Restores dry steam at 475 K. The ledger includes the work stored in this network's hydrophores. */
    public static void restoreDrySteamAt475K(IntegratedFluidNetwork network, double bar) {
        var geometry = network.getTopologySnapshot().chamberVolume().orElseThrow();
        double pa = bar * 100_000;
        long microKg = Math.round(1000 * pa * geometry.litersAtPressure(pa) / (461.5 * 475));
        long microEu = Math.round(microKg * 2_242_506d + 1_000_000 * geometry.elasticEnergyEu(pa));
        network.replaceCanonicalState(gregtech.api.metatileentity.implementations.integratedfluid.state.IFNCanonicalState
            .fromRaw("water", microKg, microEu));
    }

    private static final class TestMember implements IIntegratedFluidMember {

        private final int baseCapacity;
        private final int accumulatorCapacity;
        private final float maxPressureBar;
        private IntegratedFluidNetwork network;
        private UUID networkId;

        private TestMember(int baseCapacity, int accumulatorCapacity, float maxPressureBar) {
            this.baseCapacity = baseCapacity;
            this.accumulatorCapacity = accumulatorCapacity;
            this.maxPressureBar = maxPressureBar;
        }

        @Override
        public IntegratedFluidNetwork getNetwork() {
            return network;
        }

        @Override
        public void setNetwork(IntegratedFluidNetwork network) {
            this.network = network;
        }

        @Override
        public UUID getNetworkId() {
            return networkId;
        }

        @Override
        public void setNetworkId(UUID id) {
            this.networkId = id;
        }

        @Override
        public void onNetworkUpdate() {}

        @Override
        public int getCapacityContribution() {
            return baseCapacity;
        }

        @Override
        public int getAccumulatorContribution() {
            return accumulatorCapacity;
        }

        @Override
        public float getAccumulatorMaxPressureBar() {
            return maxPressureBar;
        }
    }

    private static final class TestNetwork extends IntegratedFluidNetwork {

        private final Fluid fluid;

        private TestNetwork(UUID networkId, Fluid fluid) {
            super(networkId);
            this.fluid = fluid;
        }

        @Override
        public Fluid getFluid() {
            return getAmountQ() > 0L ? fluid : null;
        }
    }
}
