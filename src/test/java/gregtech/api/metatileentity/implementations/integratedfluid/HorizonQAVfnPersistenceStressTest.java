package gregtech.api.metatileentity.implementations.integratedfluid;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import gregtech.api.metatileentity.implementations.integratedfluid.HorizonQAVfnScenario.ScenarioMember;
import gregtech.api.metatileentity.implementations.integratedfluid.state.IFNNetworkStatus;

class HorizonQAVfnPersistenceStressTest {

    @ParameterizedTest(name = "reload and transfer, frozen={0}")
    @ValueSource(booleans = { false, true })
    void isolatedBranchesRetainTheirOwnInventoryThroughSaveAndMerge(boolean frozen) {
        var water = IFNTestSupport.waterFluid();
        var scenario = HorizonQAVfnScenario.create(water);
        var first = scenario.pipe("left", 2000);
        var link = scenario.pipe("link", 1000);
        var last = scenario.pipe("right", 3000);
        var members = scenario.members(first, link, last);
        scenario.connectChain(first, link, last);
        scenario.addNetworkFrom(first);
        var source = IFNTestSupport.newNetwork(water, 4000, 0, 20);
        var sink = IFNTestSupport.newNetwork(water, 4000, 0, 20);
        IFNTestSupport.restoreDrySteamAt475K(source, 3);
        IFNTestSupport.restoreDrySteamAt475K(first.getNetwork(), 0.9);
        IFNTestSupport.restoreDrySteamAt475K(sink, 0.3);
        requireTransfer(source, first.getNetwork(), true);
        if (frozen) first.getNetwork().freeze("isolated by operator");

        for (int cycle = 0; cycle < 3; cycle++) {
            long mass = first.getNetwork().getAmountQ(), energy = energy(first.getNetwork());
            var cut = cycle % 2 == 0 ? link : first;
            var other = cut == link ? last : link;
            scenario.disconnect(cut, other);
            scenario.changedAt(cut);
            scenario.assertNetworkGroups(members, 2);
            var left = first.getNetwork();
            var right = last.getNetwork();
            long lostMass = mass - left.getAmountQ() - right.getAmountQ();
            long lostEnergy = energy - energy(left) - energy(right);
            assertTrue(lostMass == 0 || lostMass == 1);
            assertEquals((double) energy / mass * lostMass, lostEnergy, 1.0);
            long leftMass = left.getAmountQ(), rightMass = right.getAmountQ(), leftEnergy = energy(left), rightEnergy = energy(right);
            reload(scenario, members);
            scenario.assertNetworkGroups(members, 2);
            left = first.getNetwork();
            right = last.getNetwork();
            assertEquals(leftMass, left.getAmountQ());
            assertEquals(leftEnergy, energy(left));
            assertEquals(rightMass, right.getAmountQ());
            assertEquals(rightEnergy, energy(right));
            if (frozen) {
                for (var net : List.of(left, right)) {
                    assertEquals(IFNNetworkStatus.FROZEN, net.getNetworkStatus());
                    assertEquals("isolated by operator", net.getFrozenReason());
                }
            }

            requireTransfer(source, left, !frozen);
            assertEquals(rightMass, right.getAmountQ());
            assertEquals(rightEnergy, energy(right));
            leftMass = left.getAmountQ();
            leftEnergy = energy(left);
            requireTransfer(right, sink, !frozen);
            assertEquals(leftMass, left.getAmountQ());
            assertEquals(leftEnergy, energy(left));
            long mergedMass = leftMass + right.getAmountQ(), mergedEnergy = leftEnergy + energy(right);
            scenario.connect(cut, other);
            scenario.changedAt(cut);
            reload(scenario, members);
            scenario.assertSingleNetwork(members, 3);
            assertEquals(mergedMass, first.getNetwork().getAmountQ());
            assertEquals(mergedEnergy, energy(first.getNetwork()));
            requireTransfer(first.getNetwork(), sink, !frozen);
        }
        if (frozen) {
            first.getNetwork().clearFrozen();
            requireTransfer(first.getNetwork(), sink, true);
        }
    }

    private static void reload(HorizonQAVfnScenario scenario, List<ScenarioMember> members) {
        scenario.roundTripSavedDataAndReloadFrom(members.get(0), members);
        for (var member : members) if (member.getNetwork() == null) scenario.addNetworkFrom(member);
    }

    private static void requireTransfer(IntegratedFluidNetwork source, IntegratedFluidNetwork sink, boolean accepted) {
        long initialMass = source.getAmountQ(), received = sink.getAmountQ();
        long sourceEnergy = energy(source), sinkEnergy = energy(sink);
        long moved = source.meterTo(sink, 50_000_000L, Double.MAX_VALUE);
        assertEquals(accepted, moved > 0);
        assertEquals(initialMass - moved, source.getAmountQ());
        assertEquals(received + moved, sink.getAmountQ());
        assertEquals(sourceEnergy - energy(source), energy(sink) - sinkEnergy);
        if (!accepted) assertEquals(sourceEnergy, energy(source));
    }

    private static long energy(IntegratedFluidNetwork network) {
        return network.getCanonicalState().totalEnergy().rawUnits();
    }
}
