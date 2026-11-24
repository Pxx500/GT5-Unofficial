package gregtech.api.metatileentity.implementations.integratedfluid;


import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import gregtech.api.metatileentity.implementations.integratedfluid.state.IFNCanonicalState;
import gregtech.api.metatileentity.implementations.integratedfluid.state.IFNNetworkStatus;

class NetworkManagerMergeFreezeTest {

    @ParameterizedTest
    @CsvSource({ "true,true", "true,false", "false,true", "false,false" })
    void refusedJunctionKeepsBothOwnersAndInventories(boolean addMember, boolean sameSubstance) {
        var scenario = HorizonQAVfnScenario.create();
        var a = scenario.pipe("a", 1_000_000_000);
        var b = scenario.pipe("b", 1_000_000_000);
        scenario.addNetworkFrom(a);
        scenario.addNetworkFrom(b);
        var first = a.getNetwork();
        var second = b.getNetwork();
        var left = IFNCanonicalState.fromRaw("water", 2_500_000_000_000L, 5_509_483_750_000_000_000L);
        var right = sameSubstance ? left : IFNCanonicalState.fromRaw("oxygen", 3_000_000L, 1_000_000L);
        first.replaceCanonicalState(left);
        second.replaceCanonicalState(right);
        assertEquals(IFNNetworkStatus.NORMAL, first.getNetworkStatus());
        assertTrue(first.getPressurePa() > 0);
        if (sameSubstance) assertEquals(IFNNetworkStatus.NORMAL, second.getNetworkStatus());
        scenario.connect(a, b);
        if (addMember) scenario.addNetworkFrom(a);
        else scenario.changedAt(a);
        assertSame(first, a.getNetwork());
        assertSame(second, b.getNetwork());
        assertEquals(IFNNetworkStatus.FROZEN, first.getNetworkStatus());
        assertEquals(IFNNetworkStatus.FROZEN, second.getNetworkStatus());
        assertEquals(left.substanceAmount().rawUnits(), first.getAmountQ());
        assertEquals(right.substanceAmount().rawUnits(), second.getAmountQ());
        assertEquals(left.totalEnergy().rawUnits(), first.getCanonicalState().totalEnergy().rawUnits());
        assertEquals(right.totalEnergy().rawUnits(), second.getCanonicalState().totalEnergy().rawUnits());
    }
}
