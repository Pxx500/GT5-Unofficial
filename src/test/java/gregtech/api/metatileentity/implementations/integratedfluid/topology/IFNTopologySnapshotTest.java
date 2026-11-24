package gregtech.api.metatileentity.implementations.integratedfluid.topology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import gregtech.api.metatileentity.implementations.integratedfluid.IIntegratedFluidMember;
import gregtech.api.metatileentity.implementations.integratedfluid.IntegratedFluidNetwork;

class IFNTopologySnapshotTest {

    @Test
    void sumsBaseAndAccumulatorVolumes() {
        IFNTopologySnapshot snapshot = IFNTopologySnapshot.fromMembers(
            Arrays.asList(member(100, 0), member(10000, 5000)),
            0
        );

        assertEquals(2, snapshot.memberCount());
        assertEquals(10100, snapshot.baseVolume().toWholeLiters());
        assertEquals(5000, snapshot.accumulatorVolume().toWholeLiters());
        assertEquals(15100, snapshot.totalVolume().toWholeLiters());
        var chamber = snapshot.chamberVolume().orElseThrow();
        assertEquals(10100, chamber.litersAtPressure(0));
        assertEquals(10100, chamber.litersAtPressure(100_000));
        assertEquals(12600, chamber.litersAtPressure(550_000));
        assertEquals(15100, chamber.litersAtPressure(2_000_000));
    }

    @Test
    void separatesPipeHatchAndHydrophoreContributions() {
        IFNTopologySnapshot snapshot = IFNTopologySnapshot.fromMembers(
            Arrays.asList(
                member(100, 0),
                member(10000, 0),
                member(0, 5000)),
            0
        );

        assertEquals(3, snapshot.memberCount());
        assertEquals(10100, snapshot.baseVolume().toWholeLiters());
        assertEquals(5000, snapshot.accumulatorVolume().toWholeLiters());
        assertEquals(15100, snapshot.totalVolume().toWholeLiters());
    }

    @Test
    void expectedMemberCountMarksIncompleteTopology() {
        IFNTopologySnapshot snapshot = IFNTopologySnapshot.fromMembers(
            Arrays.asList(member(100, 0), member(100, 0)),
            3
        );

        assertTrue(snapshot.incomplete());
        assertTrue(snapshot.chamberVolume().isEmpty());
    }

    @Test
    void injectorAloneDoesNotCreatePhysicalSpace() {
        assertTrue(IFNTopologySnapshot.fromMembers(Arrays.asList(member(0, 0)), 1).chamberVolume().isEmpty());
        var pipe = IFNTopologySnapshot.fromMembers(Arrays.asList(member(100, 0)), 1).chamberVolume().orElseThrow();
        assertEquals(100, pipe.litersAtPressure(0));
        assertEquals(100, pipe.litersAtPressure(1_000_000));
    }

    private static IIntegratedFluidMember member(int capacity, int accumulator) {
        return new IIntegratedFluidMember() {

            @Override
            public IntegratedFluidNetwork getNetwork() {
                return null;
            }

            @Override
            public void setNetwork(IntegratedFluidNetwork network) {}

            @Override
            public java.util.UUID getNetworkId() {
                return null;
            }

            @Override
            public void setNetworkId(java.util.UUID id) {}

            @Override
            public void onNetworkUpdate() {}

            @Override
            public int getCapacityContribution() {
                return capacity;
            }

            @Override
            public int getAccumulatorContribution() {
                return accumulator;
            }
        };
    }
}
