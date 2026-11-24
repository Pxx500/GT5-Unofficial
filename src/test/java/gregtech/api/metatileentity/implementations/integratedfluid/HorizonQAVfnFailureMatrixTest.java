package gregtech.api.metatileentity.implementations.integratedfluid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;

import net.minecraftforge.fluids.Fluid;

import org.junit.jupiter.api.Test;

import gregtech.api.metatileentity.implementations.integratedfluid.amount.SubstanceAmount;
import gregtech.api.metatileentity.implementations.integratedfluid.HorizonQAVfnScenario.ScenarioMember;
import gregtech.api.metatileentity.implementations.integratedfluid.state.IFNCanonicalState;
import gregtech.api.metatileentity.implementations.integratedfluid.state.IFNNetworkStatus;
import gregtech.api.metatileentity.implementations.integratedfluid.thermodynamics.ClosedFluidChamber;
import gregtech.api.metatileentity.implementations.integratedfluid.thermodynamics.PureFluid;

class HorizonQAVfnFailureMatrixTest {

    @Test
    void incompatibleFluidMergeFreezesBothSidesWithoutMovingMembers() {
        HorizonQAVfnScenario scenario = HorizonQAVfnScenario.create(IFNTestSupport.waterFluid());
        ScenarioMember liquidPipe = scenario.pipe("liquid-pipe");
        ScenarioMember liquidHatch = scenario.inputHatch("liquid-hatch");
        ScenarioMember vaporPipe = scenario.pipe("vapor-pipe");
        ScenarioMember vaporHatch = scenario.outputHatch("vapor-hatch");
        List<ScenarioMember> members = scenario.members(liquidPipe, liquidHatch, vaporPipe, vaporHatch);

        scenario.connect(liquidPipe, liquidHatch);
        scenario.connect(vaporPipe, vaporHatch);
        scenario.addNetworkFrom(liquidPipe);
        scenario.addNetworkFrom(vaporPipe);
        IntegratedFluidNetwork liquidNetwork = liquidPipe.getNetwork();
        IntegratedFluidNetwork vaporNetwork = vaporPipe.getNetwork();
        long mass = 4L * SubstanceAmount.SCALE;
        liquidNetwork.replaceCanonicalState(IFNCanonicalState.fromRaw("water", mass, -1_000_000_000_000L));
        // Exercise the identity conflict directly. The injector does not admit an unknown substance
        vaporNetwork.replaceCanonicalState(IFNCanonicalState.fromRaw("test_foreign_substance", mass, 8_970_024_000_000L));

        scenario.connect(liquidHatch, vaporPipe);
        scenario.changedAt(liquidHatch);

        assertSame(liquidNetwork, liquidPipe.getNetwork());
        assertSame(vaporNetwork, vaporPipe.getNetwork());
        scenario.assertNetworkGroups(members, 2);
        assertEquals(IFNNetworkStatus.FROZEN, liquidNetwork.getNetworkStatus());
        assertEquals(IFNNetworkStatus.FROZEN, vaporNetwork.getNetworkStatus());
        assertEquals("fluid conflict", liquidNetwork.getFrozenReason());
        assertEquals("fluid conflict", vaporNetwork.getFrozenReason());
        assertEquals(mass, liquidNetwork.getAmountQ());
        assertEquals(mass, vaporNetwork.getAmountQ());
        assertAllTransferDirectionsBlocked(liquidNetwork);
        assertAllTransferDirectionsBlocked(vaporNetwork);
    }

    @Test
    void frozenNetworkStaysFrozenAcrossSplitAndMerge() {
        HorizonQAVfnScenario scenario = HorizonQAVfnScenario.create(IFNTestSupport.waterFluid());
        ScenarioMember pipeA = scenario.pipe("pipe-a");
        ScenarioMember pipeB = scenario.pipe("pipe-b");
        ScenarioMember pipeC = scenario.pipe("pipe-c");
        List<ScenarioMember> members = scenario.members(pipeA, pipeB, pipeC);

        scenario.connectChain(pipeA, pipeB, pipeC);
        scenario.addNetworkFrom(pipeA);
        IFNCanonicalState fluid = scenario.chargeWater(pipeA, 9);
        pipeA.getNetwork().freeze("Horizon-QA forced freeze");

        scenario.disconnect(pipeB, pipeC);
        scenario.changedAt(pipeB);

        scenario.assertNetworkGroups(members, 2, fluid);
        for (IntegratedFluidNetwork network : scenario.networksOf(members)) {
            assertEquals(IFNNetworkStatus.FROZEN, network.getNetworkStatus());
            assertEquals("Horizon-QA forced freeze", network.getFrozenReason());
            assertAllTransferDirectionsBlocked(network);
        }

        scenario.connect(pipeB, pipeC);
        scenario.changedAt(pipeB);

        scenario.assertSingleNetwork(members, 3);
        scenario.assertTotals(members, fluid);
        assertEquals(IFNNetworkStatus.FROZEN, pipeA.getNetwork().getNetworkStatus());
        assertEquals("Horizon-QA forced freeze", pipeA.getNetwork().getFrozenReason());
        assertAllTransferDirectionsBlocked(pipeA.getNetwork());
    }

    @Test
    void zeroCapacityNetworkRejectsMachineOutputTransfer() {
        var scenario = HorizonQAVfnScenario.create(IFNTestSupport.waterFluid());
        var injector = scenario.injectorHatch("zero-injector");
        var machine = scenario.machineHatch("zero-machine");
        var extractor = scenario.extractorHatch("zero-extractor");
        var members = scenario.members(injector, machine, extractor);
        var working = ClosedFluidChamber.of(PureFluid.water(), BigDecimal.ONE, 1000, new BigDecimal("1042343.4875"));
        scenario.connectChain(injector, machine, extractor);
        scenario.addNetworkFrom(injector);
        scenario.assertSingleNetwork(members, 3);
        scenario.assertSingleNetworkCapacity(members, 0, 0);

        var blocked = injector.getNetwork().drainMachineChamber(working, 100, 0);
        assertEquals(working.massKg(), blocked.massKg());
        assertEquals(working.totalEnergyEu(), blocked.totalEnergyEu());
        assertEquals(0L, injector.getNetwork().getAmountQ());
        var pipe = scenario.pipe("receiver", 1000);
        scenario.connect(pipe, extractor);
        scenario.addNetworkFrom(pipe);
        var retained = injector.getNetwork().drainMachineChamber(working, 100, 0);
        long delivered = injector.getNetwork().getAmountQ();
        assertTrue(delivered > 0);
        assertEquals(1_000_000L, retained.massKg().movePointRight(6).longValueExact() + delivered);
    }

    @Test
    void highPressureInjectorBranchStaysBlockedAfterSplitAndMerge() {
        Fluid vapor = IFNTestSupport.vaporFluid();
        HorizonQAVfnScenario scenario = HorizonQAVfnScenario.create(vapor);
        ScenarioMember pipe = scenario.pipe("pressure-pipe");
        ScenarioMember injector = scenario.injectorHatch("pressure-injector");
        ScenarioMember extractor = scenario.extractorHatch("pressure-extractor");
        List<ScenarioMember> members = scenario.members(pipe, injector, extractor);

        scenario.connectChain(pipe, injector, extractor);
        scenario.addNetworkFrom(pipe);
        IFNTestSupport.restoreDrySteamAt475K(pipe.getNetwork(), 0.95);

        assertFalse(IFNNetworkTransferGate.canInjectFromGtPipe(pipe.getNetwork()));

        scenario.disconnect(injector, extractor);
        scenario.changedAt(injector);

        scenario.assertNetworkGroups(members, 2);
        assertFalse(IFNNetworkTransferGate.canInjectFromGtPipe(injector.getNetwork()));

        scenario.connect(injector, extractor);
        scenario.changedAt(injector);
        scenario.assertSingleNetwork(members, 3);

        assertFalse(IFNNetworkTransferGate.canInjectFromGtPipe(pipe.getNetwork()));
    }

    private static void assertAllTransferDirectionsBlocked(IntegratedFluidNetwork network) {
        assertFalse(IFNNetworkTransferGate.canReceiveFromMachine(network));
        assertFalse(IFNNetworkTransferGate.canProvideToMachine(network));
        assertFalse(IFNNetworkTransferGate.canInjectFromGtPipe(network));
        assertFalse(IFNNetworkTransferGate.canExtractToGtPipe(network));
    }
}
