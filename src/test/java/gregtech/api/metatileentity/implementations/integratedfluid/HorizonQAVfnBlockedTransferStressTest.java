package gregtech.api.metatileentity.implementations.integratedfluid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import gregtech.api.metatileentity.implementations.integratedfluid.HorizonQAVfnScenario.ScenarioMember;

class HorizonQAVfnBlockedTransferStressTest {

    @Test
    void highPressureInjectorBranchStaysBlockedThroughTransferSequenceAndTopologyRebuild() {
        HorizonQAVfnScenario scenario = HorizonQAVfnScenario.create();
        ScenarioMember injector = scenario.injectorHatch("pressure-injector");
        ScenarioMember pipe = scenario.pipe("pressure-pipe");
        ScenarioMember extractor = scenario.extractorHatch("pressure-extractor");
        List<ScenarioMember> members = scenario.members(injector, pipe, extractor);

        scenario.connectChain(injector, pipe, extractor);
        scenario.addNetworkFrom(injector);
        scenario.assertSingleNetwork(members, 3);
        IntegratedFluidNetwork network = injector.getNetwork();
        IFNTestSupport.restoreDrySteamAt475K(network, 1.9);
        long initialMass = network.getAmountQ();
        assertFalse(IFNNetworkTransferGate.canInjectFromGtPipe(network));
        assertTrue(IFNNetworkTransferGate.canExtractToGtPipe(network));

        scenario.disconnect(pipe, extractor);
        scenario.changedAt(pipe);
        scenario.assertNetworkGroups(members, 2);
        IntegratedFluidNetwork injectorSide = injector.getNetwork();
        IntegratedFluidNetwork extractorSide = extractor.getNetwork();
        assertSame(injectorSide, pipe.getNetwork());
        assertEquals(initialMass, injectorSide.getAmountQ());
        assertEquals(0L, extractorSide.getAmountQ(), "Detached zero-volume hatch holds no steam");
        assertFalse(IFNNetworkTransferGate.canInjectFromGtPipe(injectorSide));
        assertFalse(IFNNetworkTransferGate.canInjectFromGtPipe(extractorSide));

        scenario.connect(pipe, extractor);
        scenario.changedAt(pipe);
        scenario.assertSingleNetwork(members, 3);
        assertEquals(initialMass, injector.getNetwork().getAmountQ());
        assertFalse(IFNNetworkTransferGate.canInjectFromGtPipe(injector.getNetwork()));
    }

}
