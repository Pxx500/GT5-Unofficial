package gregtech.api.metatileentity.implementations.integratedfluid;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Predicate;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.MetaPipeEntity;
import gregtech.api.metatileentity.implementations.integratedfluid.safety.IFNNetworkSafetyTicker;
import gregtech.api.metatileentity.implementations.integratedfluid.state.IFNCanonicalState;
import gregtech.api.metatileentity.implementations.integratedfluid.topology.IFNMergePolicy;
import gregtech.api.metatileentity.implementations.integratedfluid.topology.IFNStateDistributor;
import gregtech.api.metatileentity.implementations.integratedfluid.topology.IFNTopologyRebuilder;
import gregtech.api.metatileentity.implementations.integratedfluid.topology.IFNTopologySnapshot;

/**
 * Centralized network manager for integrated fluid networks.
 * Uses a graph-based approach with flood-fill to properly handle
 * network connections, disconnections, and merges.
 */
public class NetworkManager {

    private static final Map<World, NetworkManager> INSTANCES = new WeakHashMap<>();

    private final World world;
    private final Map<NetworkNode, Set<IntegratedFluidNetwork>> nodeToNetworks = new HashMap<>();
    private final Set<IntegratedFluidNetwork> allNetworks = new HashSet<>();
    private final IntegratedFluidNetworkSavedData savedData;
    private final IFNNetworkSafetyTicker safetyTicker;
    private final Random safetyRandom;

    // Track last tick time for heat loss application
    static final int AMBIENT_STEP_TICKS = 20;
    private long lastHeatLossTick = 0;

    private NetworkManager(World world) {
        this(world, IntegratedFluidNetworkSavedData.get(world));
    }

    NetworkManager(World world, IntegratedFluidNetworkSavedData savedData) {
        this.world = world;
        this.savedData = savedData;
        this.safetyTicker = new IFNNetworkSafetyTicker();
        this.safetyRandom = new Random();
    }

    /**
     * Gets or creates the network manager for a world.
     */
    public static NetworkManager getInstance(World world) {
        return INSTANCES.computeIfAbsent(world, NetworkManager::new);
    }

    public static void clearInstance(World world) {
        if (world == null) {
            return;
        }
        NetworkManager manager = INSTANCES.remove(world);
        if (manager != null) {
            manager.cleanup();
        }
    }

    /**
     * Gets the network for a member, or null if not networked.
     */
    public IntegratedFluidNetwork getNetwork(IIntegratedFluidMember member) {
        if (member == null) return null;
        return member.getNetwork();
    }

    /**
     * Called when a new pipe or hatch is added to the world.
     * This will connect it to adjacent networks or create a new one.
     */
    public void onMemberAdded(IIntegratedFluidMember member) {
        if (member == null) return;
        UUID savedId = member.getNetworkId();
        if (savedId != null && savedData.getState(savedId) != null) {
            var returning = getOrCreateNetwork(savedId);
            if (returning.isPending()) {
                returning.resumeMember(member);
                if (!returning.isPending()) {
                    var components = findConnectedComponents(new ArrayList<>(returning.getMembers()));
                    if (components.size() > 1) rebuildComponents(java.util.Collections.singleton(returning), components);
                }
                return;
            }
        }

        Set<IIntegratedFluidMember> component = new HashSet<>();
        floodFill(member, component, new HashSet<IIntegratedFluidMember>()::add);
        if (component.isEmpty()) {
            component.add(member);
        }

        for (var candidate : component) {
            if (candidate.getNetwork() != null && candidate.getNetwork().isPending()) return;
        }
        UUID targetId = chooseNetworkId(member, component);
        IntegratedFluidNetwork mainNetwork = getOrCreateNetwork(targetId);

        Set<IntegratedFluidNetwork> networksToMerge = new HashSet<>();
        for (IIntegratedFluidMember componentMember : component) {
            IntegratedFluidNetwork existing = componentMember.getNetwork();
            if (existing != null && existing != mainNetwork) {
                networksToMerge.add(existing);
            }
        }

        if (!networksToMerge.isEmpty() && !mergeIntoNetwork(mainNetwork, networksToMerge)) return;

        for (IIntegratedFluidMember componentMember : component) {
            addToNetwork(componentMember, mainNetwork);
        }

        ensureExpectedCount(mainNetwork);
        updatePending(mainNetwork);
        persistNetwork(mainNetwork);

        for (IIntegratedFluidMember componentMember : component) {
            componentMember.onNetworkUpdate();
        }
    }

    /**
     * Called when a pipe or hatch is removed from the world.
     * This will split the network if necessary.
     */
    public void onMemberRemoved(IIntegratedFluidMember member) {
        detach(member, false);
    }

    public static void onChunkUnload(net.minecraft.world.chunk.Chunk chunk) {
        NetworkManager manager = INSTANCES.get(chunk.worldObj);
        if (manager == null || chunk.worldObj.isRemote) return;
        for (Object tile : chunk.chunkTileEntityMap.values()) {
            if (tile instanceof IGregTechTileEntity gt && gt.getMetaTileEntity() instanceof IIntegratedFluidMember member
                && member.getNetwork() != null) {
                var network = member.getNetwork();
                network.suspendMember(member);
                if (network.getMemberCount() == 0) {
                    manager.allNetworks.remove(network);
                    network.setSavedData(null);
                }
            }
        }
    }

    static void onBlockDestroyed(IIntegratedFluidMember member, IGregTechTileEntity tile) {
        if (tile != null && tile.isServerSide() && member.getNetwork() != null) {
            getInstance(tile.getWorld()).detach(member, true);
        }
    }

    private void detach(IIntegratedFluidMember member, boolean destroyed) {
        if (member == null) return;

        IntegratedFluidNetwork oldNetwork = member.getNetwork();
        if (oldNetwork == null) return;
        if (destroyed && oldNetwork.isPending()) {
            oldNetwork.destroyWhileIncomplete(member);
            return;
        }

        List<IIntegratedFluidMember> remainingMembers = new ArrayList<>(oldNetwork.getMembers());
        if (!remainingMembers.remove(member)) return;
        if (remainingMembers.isEmpty()) {
            oldNetwork.removeMember(member, IFNCanonicalState.empty());
            member.setNetworkId(null);
            removeNetwork(oldNetwork);
            return;
        }

        // During breakBlock the destroyed tile can still be found through the world
        List<Set<IIntegratedFluidMember>> components = findConnectedComponents(remainingMembers);
        IFNCanonicalState[] splitStates;
        if (destroyed && oldNetwork.hasSolvedFluid()) {
            splitStates = new IFNCanonicalState[components.size()];
            for (int i = 0; i < components.size(); i++) {
                splitStates[i] = oldNetwork.inventoryIn(IFNTopologySnapshot.fromMembers(components.get(i), 0));
            }
        } else if (components.size() == 1) {
            splitStates = new IFNCanonicalState[] { oldNetwork.getCanonicalState() };
        } else {
            splitStates = IFNStateDistributor.splitStateByWeights(oldNetwork.getCanonicalState(), componentCapacityWeights(components));
        }
        oldNetwork.removeMember(member, components.size() == 1 ? splitStates[0] : IFNCanonicalState.empty());
        member.setNetworkId(null);
        if (components.size() == 1) {
            oldNetwork.setExpectedMemberCount(oldNetwork.getMemberCount());
            oldNetwork.setPending(false);
            persistNetwork(oldNetwork);
            for (IIntegratedFluidMember remainingMember : remainingMembers) {
                remainingMember.onNetworkUpdate();
            }
            return;
        }

        for (IIntegratedFluidMember remainingMember : remainingMembers) {
            oldNetwork.removeMember(remainingMember);
            remainingMember.setNetwork(null);
        }

        UUID oldId = oldNetwork.getNetworkId();
        removeNetwork(oldNetwork);

        Set<IIntegratedFluidMember> primaryComponent = components.stream()
            .max(Comparator.comparingInt(Set::size))
            .orElse(null);

        for (int componentIndex = 0; componentIndex < components.size(); componentIndex++) {
            Set<IIntegratedFluidMember> component = components.get(componentIndex);
            UUID newId = component == primaryComponent ? oldId : UUID.randomUUID();
            IntegratedFluidNetwork newNetwork = createEmptyNetwork(newId);

            for (IIntegratedFluidMember componentMember : component) {
                newNetwork.addMember(componentMember);
                componentMember.setNetwork(newNetwork);
            }

            applyState(newNetwork, splitStates[componentIndex]);

            newNetwork.setExpectedMemberCount(newNetwork.getMemberCount());
            newNetwork.setPending(false);
            persistNetwork(newNetwork);

            for (IIntegratedFluidMember componentMember : component) {
                componentMember.onNetworkUpdate();
                List<IIntegratedFluidMember> neighbors = findConnectedNeighbors(componentMember);
                for (IIntegratedFluidMember neighbor : neighbors) {
                    if (neighbor != null && !component.contains(neighbor)) {
                        neighbor.onNetworkUpdate();
                    }
                }
            }
        }
    }

    /**
     * Called when a connection changes (cable connected/disconnected).
     * Triggers a full network rebuild for affected members.
     */
    public void onConnectionChanged(IIntegratedFluidMember member) {
        if (member == null) return;

        Set<IIntegratedFluidMember> allAffectedMembers = new HashSet<>();
        Set<IntegratedFluidNetwork> affectedNetworks = new HashSet<>();

        if (member.getNetwork() != null) {
            affectedNetworks.add(member.getNetwork());
            allAffectedMembers.addAll(member.getNetwork().getMembers());
        } else {
            allAffectedMembers.add(member);
        }

        List<IIntegratedFluidMember> neighbors = findConnectedNeighbors(member);
        for (IIntegratedFluidMember neighbor : neighbors) {
            if (neighbor.getNetwork() != null) {
                affectedNetworks.add(neighbor.getNetwork());
                allAffectedMembers.addAll(neighbor.getNetwork().getMembers());
            } else {
                allAffectedMembers.add(neighbor);
            }
        }

        for (var network : affectedNetworks) {
            if (network.isPending()) return;
        }
        rebuildComponents(affectedNetworks, findConnectedComponents(new ArrayList<>(allAffectedMembers)));
    }

    private void rebuildComponents(Set<IntegratedFluidNetwork> affectedNetworks, List<Set<IIntegratedFluidMember>> components) {
        var combinedState = combineOrFreeze(affectedNetworks);
        if (combinedState == null) return;

        String inheritedFrozenReason = frozenReasonOf(affectedNetworks);
        UUID splitPrimaryId = null;
        if (affectedNetworks.size() == 1) {
            IntegratedFluidNetwork only = affectedNetworks.iterator().next();
            splitPrimaryId = only != null ? only.getNetworkId() : null;
        }

        IFNCanonicalState[] splitStates = null;
        if (affectedNetworks.size() == 1 && components.size() > 1) {
            var original = affectedNetworks.iterator().next();
            if (original.hasSolvedFluid()) {
                splitStates = new IFNCanonicalState[components.size()];
                for (int i = 0; i < components.size(); i++) {
                    splitStates[i] = original.inventoryIn(IFNTopologySnapshot.fromMembers(components.get(i), 0));
                }
            }
        }
        if (splitStates == null) {
            splitStates = IFNStateDistributor.splitStateByWeights(combinedState, componentCapacityWeights(components));
        }
        for (IntegratedFluidNetwork net : affectedNetworks) {
            for (IIntegratedFluidMember m : new ArrayList<>(net.getMembers())) {
                net.removeMember(m);
                m.setNetwork(null);
            }
            removeNetwork(net);
        }

        boolean isMerge = components.size() == 1 && affectedNetworks.size() > 1;
        Set<IIntegratedFluidMember> primaryComponent = null;
        if (!isMerge && components.size() > 1) {
            primaryComponent = components.stream()
                .max(Comparator.comparingInt(Set::size))
                .orElse(null);
        }

        for (int componentIndex = 0; componentIndex < components.size(); componentIndex++) {
            Set<IIntegratedFluidMember> component = components.get(componentIndex);
            if (component.isEmpty()) continue;
            IIntegratedFluidMember seed = component.iterator().next();
            UUID targetId;
            if (primaryComponent != null) {
                if (component == primaryComponent) {
                    targetId = splitPrimaryId != null ? splitPrimaryId : chooseNetworkId(seed, component);
                } else {
                    targetId = UUID.randomUUID();
                }
            } else {
                targetId = chooseNetworkId(seed, component);
            }
            IntegratedFluidNetwork newNetwork = createEmptyNetwork(targetId);

            for (IIntegratedFluidMember componentMember : component) {
                newNetwork.addMember(componentMember);
                componentMember.setNetwork(newNetwork);
            }

            applyState(newNetwork, isMerge ? combinedState : splitStates[componentIndex]);

            newNetwork.setExpectedMemberCount(newNetwork.getMemberCount());
            newNetwork.setPending(false);
            if (inheritedFrozenReason != null) {
                newNetwork.freeze(inheritedFrozenReason);
            }
            persistNetwork(newNetwork);

            for (IIntegratedFluidMember componentMember : component) {
                componentMember.onNetworkUpdate();
            }
        }
    }

    private String frozenReasonOf(Set<IntegratedFluidNetwork> networks) {
        if (networks == null) {
            return null;
        }
        for (IntegratedFluidNetwork network : networks) {
            if (network != null && network.isFrozen()) {
                String reason = network.getFrozenReason();
                return reason == null || reason.trim().isEmpty() ? "frozen" : reason;
            }
        }
        return null;
    }

    /**
     * Called every tick to handle network updates.
     * Applies heat loss every second (20 ticks).
     */
    public void onWorldTick(long worldTick) {
        tickOperationalSafety();

        // Apply heat loss every 20 ticks (1 second)
        if (worldTick - lastHeatLossTick >= AMBIENT_STEP_TICKS) {
            lastHeatLossTick = worldTick;

            // Apply heat loss to all networks
            float ambientKelvin = IFNAmbientTemperature.getAmbientTemperature(world);
            for (IntegratedFluidNetwork network : new HashSet<>(allNetworks)) {
                if (network != null && !network.isPending()) {
                    network.exchangeAmbientHeat(ambientKelvin, worldTick, AMBIENT_STEP_TICKS);
                }
            }
        }
    }

    private void tickOperationalSafety() {
        safetyTicker.tick(new HashSet<>(allNetworks), safetyRandom, this::ruptureFailureCandidate);
    }

    private void ruptureFailureCandidate(IIntegratedFluidMember member) {
        if (member == null) {
            return;
        }

        if (member instanceof MetaPipeEntity pipe) {
            IGregTechTileEntity baseTile = pipe.getBaseMetaTileEntity();
            if (baseTile != null) {
                World pipeWorld = baseTile.getWorld();
                if (pipeWorld != null && !pipeWorld.isRemote) {
                    pipeWorld.setBlockToAir(baseTile.getXCoord(), baseTile.getYCoord(), baseTile.getZCoord());
                    return;
                }
            }
        }

        onMemberRemoved(member);
    }

    /**
     * Finds all connected neighbors of a member.
     * Public so members can check their neighbors in onPostTick.
     */
    public List<IIntegratedFluidMember> findConnectedNeighbors(IIntegratedFluidMember member) {
        List<IIntegratedFluidMember> neighbors = new ArrayList<>();

        // Check if member is a pipe
        if (member instanceof MetaPipeEntity pipe) {
            IGregTechTileEntity baseTile = pipe.getBaseMetaTileEntity();
            if (baseTile != null) {
                for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
                    if (pipe.isConnectedAtSide(side)) {
                        TileEntity neighbor = baseTile.getTileEntityAtSide(side);
                        if (neighbor instanceof IGregTechTileEntity gtNeighbor) {
                            IMetaTileEntity mte = gtNeighbor.getMetaTileEntity();
                            if (mte instanceof IIntegratedFluidMember neighborMember) {
                                // If neighbor is a hatch (not a pipe), check if we're connecting through its front facing
                                if (!(mte instanceof MetaPipeEntity)) {
                                    // This is a hatch - check if we're connecting through its dot side
                                    IGregTechTileEntity neighborBaseTile = mte.getBaseMetaTileEntity();
                                    if (neighborBaseTile != null) {
                                        ForgeDirection hatchFrontFacing = neighborBaseTile.getFrontFacing();
                                        // Only connect if the hatch's front facing points towards us
                                        if (hatchFrontFacing == side.getOpposite()) {
                                            neighbors.add(neighborMember);
                                        }
                                        // Otherwise, ignore - hatch doesn't accept connections from this side
                                    }
                                } else {
                                    // Neighbor is another pipe
                                    neighbors.add(neighborMember);
                                }
                            }
                        }
                    }
                }
            }
        }
        // Also check if member is a hatch or other tile entity
        else if (member instanceof IMetaTileEntity mte) {
            IGregTechTileEntity baseTile = mte.getBaseMetaTileEntity();
            if (baseTile != null) {
                // For hatches: ONLY connect through the front facing (the dot side)
                ForgeDirection allowedSide = baseTile.getFrontFacing();

                TileEntity neighbor = baseTile.getTileEntityAtSide(allowedSide);
                if (neighbor instanceof IGregTechTileEntity gtNeighbor) {
                    IMetaTileEntity neighborMTE = gtNeighbor.getMetaTileEntity();
                    if (neighborMTE instanceof IIntegratedFluidMember neighborMember) {
                        // Check if neighbor is a pipe
                        if (neighborMTE instanceof MetaPipeEntity neighborPipe) {
                            // Check if pipe is connected to us on the opposite side
                            if (neighborPipe.isConnectedAtSide(allowedSide.getOpposite())) {
                                neighbors.add(neighborMember);
                            }
                        }
                        // Or if neighbor is another hatch (not a pipe)
                        else {
                            // neighborMTE is IIntegratedFluidMember but not MetaPipeEntity = must be a hatch
                            IGregTechTileEntity neighborBaseTile = neighborMTE.getBaseMetaTileEntity();
                            if (neighborBaseTile != null) {
                                // Check if neighbor hatch's front facing is pointing back at us
                                ForgeDirection neighborFacing = neighborBaseTile.getFrontFacing();
                                if (neighborFacing == allowedSide.getOpposite()) {
                                    // Both hatches are facing each other - they can connect!
                                    neighbors.add(neighborMember);
                                }
                            }
                        }
                    }
                }
            }
        }

        return neighbors;
    }

    private long[] componentCapacityWeights(List<Set<IIntegratedFluidMember>> components) {
        long[] weights = new long[components == null ? 0 : components.size()];
        if (components == null) {
            return weights;
        }
        for (int i = 0; i < components.size(); i++) {
            long capacity = 0L;
            for (IIntegratedFluidMember member : components.get(i)) {
                if (member != null) {
                    capacity += Math.max(0, member.getCapacityContribution());
                    capacity += Math.max(0, member.getAccumulatorContribution());
                }
            }
            weights[i] = capacity;
        }
        return weights;
    }

    private void applyState(IntegratedFluidNetwork network, IFNCanonicalState state) {
        if (network != null && state != null && !state.isEmpty()) {
            network.replaceCanonicalState(state);
        }
    }

    /**
     * Finds all connected components using flood-fill algorithm.
     */
    private List<Set<IIntegratedFluidMember>> findConnectedComponents(List<IIntegratedFluidMember> members) {
        Set<IIntegratedFluidMember> remaining = new HashSet<>(members);
        List<Set<IIntegratedFluidMember>> components = new ArrayList<>();

        for (IIntegratedFluidMember member : members) {
            if (!remaining.contains(member)) continue;

            Set<IIntegratedFluidMember> component = new HashSet<>();
            floodFill(member, component, remaining::remove);

            if (!component.isEmpty()) {
                components.add(component);
            }
        }

        return components;
    }

    /**
     * Flood-fill algorithm to find all connected members.
     */
    private void floodFill(IIntegratedFluidMember start, Set<IIntegratedFluidMember> component,
        Predicate<IIntegratedFluidMember> visit) {
        Queue<IIntegratedFluidMember> queue = new LinkedList<>();
        queue.add(start);
        visit.test(start);

        while (!queue.isEmpty()) {
            IIntegratedFluidMember current = queue.poll();
            component.add(current);

            List<IIntegratedFluidMember> neighbors = findConnectedNeighbors(current);
            for (IIntegratedFluidMember neighbor : neighbors) {
                if (visit.test(neighbor)) {
                    queue.add(neighbor);
                }
            }
        }
    }

    /**
     * Creates a new isolated network with a single member.
     */
    private IntegratedFluidNetwork createEmptyNetwork(UUID networkId) {
        IntegratedFluidNetwork network = newNetwork(networkId);
        network.setSavedData(savedData);
        allNetworks.add(network);
        return network;
    }

    protected IntegratedFluidNetwork newNetwork(UUID networkId) {
        return new IntegratedFluidNetwork(networkId);
    }

    private IntegratedFluidNetwork createNewNetwork(UUID networkId) {
        IntegratedFluidNetwork network = createEmptyNetwork(networkId);
        savedData.restoreInto(network);
        return network;
    }

    private IntegratedFluidNetwork getOrCreateNetwork(UUID networkId) {
        if (networkId == null) {
            networkId = UUID.randomUUID();
        }
        for (IntegratedFluidNetwork network : allNetworks) {
            if (network != null && networkId.equals(network.getNetworkId())) {
                return network;
            }
        }
        return createNewNetwork(networkId);
    }

    private void persistNetwork(IntegratedFluidNetwork network) {
        if (network == null) return;
        savedData.upsertState(network.getNetworkId(), network);
    }

    private void removeNetwork(IntegratedFluidNetwork network) {
        if (network == null) return;
        allNetworks.remove(network);
        network.setSavedData(null);
        savedData.removeState(network.getNetworkId());
    }

    private void ensureExpectedCount(IntegratedFluidNetwork network) {
        if (network == null) return;
        if (network.getExpectedMemberCount() <= 0) {
            network.setExpectedMemberCount(network.getMemberCount());
        }
    }

    private void updatePending(IntegratedFluidNetwork network) {
        if (network == null) return;
        IFNTopologyRebuilder.Result rebuild =
            IFNTopologyRebuilder.rebuild(network.getMembers(), network.getExpectedMemberCount());
        network.setExpectedMemberCount(rebuild.expectedMemberCount());
        network.setPending(rebuild.pending());
    }

    private UUID chooseNetworkId(IIntegratedFluidMember member, Set<IIntegratedFluidMember> component) {
        Map<UUID, Integer> counts = new HashMap<>();
        UUID memberId = member.getNetworkId();
        if (memberId != null) {
            counts.put(memberId, 1);
        }
        for (IIntegratedFluidMember componentMember : component) {
            UUID id = componentMember.getNetworkId();
            if (id != null) {
                counts.merge(id, 1, Integer::sum);
            }
        }
        UUID best = null;
        int bestCount = 0;
        for (Map.Entry<UUID, Integer> entry : counts.entrySet()) {
            UUID id = entry.getKey();
            int count = entry.getValue();
            if (count > bestCount) {
                best = id;
                bestCount = count;
            } else if (count == bestCount && best != null && id.toString().compareTo(best.toString()) < 0) {
                best = id;
            }
        }
        if (best == null) {
            best = UUID.randomUUID();
        }
        return best;
    }


    /**
     * Adds a member to an existing network.
     */
    private void addToNetwork(IIntegratedFluidMember member, IntegratedFluidNetwork network) {
        if (member == null) return;
        if (network == null) {
            network = getOrCreateNetwork(member.getNetworkId());
        }
        IntegratedFluidNetwork current = member.getNetwork();
        if (current != null && current != network) {
            current.removeMember(member);
        }
        if (member.getNetwork() != network) {
            network.addMember(member);
            member.setNetwork(network);
        }
    }

    private IFNCanonicalState combineOrFreeze(Set<IntegratedFluidNetwork> networks) {
        var inventories = new ArrayList<IFNCanonicalState>(networks.size());
        for (var network : networks) inventories.add(network.getCanonicalState());
        try {
            return IFNMergePolicy.merge(inventories);
        } catch (ArithmeticException overflow) {
            freezeNetworks(networks, "Combined inventory exceeds ledger range");
        } catch (IllegalArgumentException conflict) {
            freezeNetworks(networks, "fluid conflict");
        }
        return null;
    }

    private void freezeNetworks(Set<IntegratedFluidNetwork> networks, String reason) {
        for (IntegratedFluidNetwork network : networks) {
            if (network == null) {
                continue;
            }
            network.freeze(reason);
            persistNetwork(network);
            for (IIntegratedFluidMember member : network.getMembers()) {
                member.onNetworkUpdate();
            }
        }
    }

    /**
     * Merges multiple networks into the target network.
     */
    boolean mergeIntoNetwork(IntegratedFluidNetwork mainNetwork, Set<IntegratedFluidNetwork> donors) {
        var participants = new HashSet<>(donors);
        participants.add(mainNetwork);
        var inventory = combineOrFreeze(participants);
        if (inventory == null) return false;
        var frozen = frozenReasonOf(participants);
        mainNetwork.absorb(participants, inventory);
        for (var donor : participants) if (donor != mainNetwork) removeNetwork(donor);
        if (frozen != null) mainNetwork.freeze(frozen);

        ensureExpectedCount(mainNetwork);
        updatePending(mainNetwork);
        persistNetwork(mainNetwork);

        for (IIntegratedFluidMember member : mainNetwork.getMembers()) {
            member.onNetworkUpdate();
        }
        return true;
    }

    /**
     * Cleans up all networks (for world unload).
     */
    public void cleanup() {
        for (IntegratedFluidNetwork network : new HashSet<>(allNetworks)) {
            network.setSavedData(null);
            network.clear();
        }
        allNetworks.clear();
        nodeToNetworks.clear();
    }

    /**
     * Internal class representing a network node.
     */
    private static class NetworkNode {
        private final IIntegratedFluidMember member;

        NetworkNode(IIntegratedFluidMember member) {
            this.member = member;
        }

        public IIntegratedFluidMember getMember() {
            return member;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            NetworkNode that = (NetworkNode) o;
            return Objects.equals(member, that.member);
        }

        @Override
        public int hashCode() {
            return Objects.hash(member);
        }
    }
}
