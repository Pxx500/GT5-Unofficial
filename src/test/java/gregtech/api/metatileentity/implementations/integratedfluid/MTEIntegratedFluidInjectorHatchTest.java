package gregtech.api.metatileentity.implementations.integratedfluid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;

import org.junit.jupiter.api.Test;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

class MTEIntegratedFluidInjectorHatchTest {

    @Test
    void smallGasOffersAreChargedBeforeTheirFractionalSubstanceEntersTheNetwork() {
        Fluid gas = IFNTestSupport.vaporFluid();
        IntegratedFluidNetwork network = IFNTestSupport.newNetwork(gas, 10_000, 0, 20.0f);
        MTEIntegratedFluidInjectorHatch injector = new MTEIntegratedFluidInjectorHatch(
            "test.injector",
            1,
            new String[0],
            null);
        injector.setBaseMetaTileEntity(mock(IGregTechTileEntity.class));
        injector.setNetwork(network);
        FluidStack offered = mock(FluidStack.class);
        when(offered.getFluid()).thenReturn(gas);
        offered.amount = 1;

        for (int packet = 1; packet <= 10; packet++) {
            long before = network.getAmountQ();
            assertEquals(1, injector.fill(ForgeDirection.WEST, offered, false));
            assertEquals(before, network.getAmountQ());
            assertEquals(1, injector.fill(ForgeDirection.WEST, offered, true));
            assertEquals(packet * IntegratedFluidNetwork.AMOUNT_SCALE / 10, network.getAmountQ());
        }
        assertEquals(300L * IntegratedFluidNetwork.ENTHALPY_SCALE, network.getEnthalpyQ());
    }

    @Test
    void retryAfterPartialFillCannotSpendFluidStillOwnedByTheSource() {
        Fluid fluid = IFNTestSupport.liquidFluid();
        IntegratedFluidNetwork network = IFNTestSupport.newNetwork(fluid, 3, 0, 20.0f);
        MTEIntegratedFluidInjectorHatch injector = new MTEIntegratedFluidInjectorHatch(
            "test.injector",
            1,
            new String[0],
            null);
        injector.setBaseMetaTileEntity(mock(IGregTechTileEntity.class));
        injector.setNetwork(network);
        FluidStack offered = mock(FluidStack.class);
        when(offered.getFluid()).thenReturn(fluid);
        offered.amount = 10;

        assertEquals(3, injector.fill(ForgeDirection.WEST, offered, true));
        assertEquals(3L * IntegratedFluidNetwork.AMOUNT_SCALE, network.getAmountQ());
        network.extractProportional(network.getAmountQ(), false);
        offered.amount = 1;

        assertEquals(1, injector.fill(ForgeDirection.WEST, offered, false));
        assertEquals(0L, network.getAmountQ());
        assertEquals(1, injector.fill(ForgeDirection.WEST, offered, true));
        assertEquals(IntegratedFluidNetwork.AMOUNT_SCALE, network.getAmountQ());
    }

    @Test
    void injectorOnlyAcceptsNormalNetworksBelowCutoffPressure() {
        Fluid vapor = IFNTestSupport.vaporFluid();
        IntegratedFluidNetwork network = IFNTestSupport.newNetwork(vapor, 1_000, 0, 1.0f);

        assertTrue(MTEIntegratedFluidInjectorHatch.canInjectIntoNetwork(network));
        assertFalse(MTEIntegratedFluidInjectorHatch.canInjectIntoNetwork(null));

        network.setPressure(IFNPressurePolicy.injectorCutoffPressureBar() + 0.01f);

        assertFalse(MTEIntegratedFluidInjectorHatch.canInjectIntoNetwork(network));

        network.setPressure(1.0f);
        network.setPending(true);

        assertFalse(MTEIntegratedFluidInjectorHatch.canInjectIntoNetwork(network));

        network.setPending(false);

        network.freeze("test freeze");

        assertFalse(MTEIntegratedFluidInjectorHatch.canInjectIntoNetwork(network));
    }

    @Test
    void injectorOnlyAcceptsRegisteredThermalFluids() {
        assertTrue(MTEIntegratedFluidInjectorHatch.canInjectFluid(IFNTestSupport.vaporFluid()));
        assertFalse(MTEIntegratedFluidInjectorHatch.canInjectFluid(new Fluid("unregistered_ifn_test_fluid")));
        assertFalse(MTEIntegratedFluidInjectorHatch.canInjectFluid(null));
    }

    @Test
    void injectorClampKeepsPhysicalGasFillAtOrBelowOneBar() {
        Fluid vapor = IFNTestSupport.vaporFluid();
        IntegratedFluidNetwork network = IFNTestSupport.newNetwork(vapor, 1_000, 0, 10.0f);

        double incomingSpecificEnthalpy = 300.0d;
        long requestedAmountQ = 1_000L * IntegratedFluidNetwork.AMOUNT_SCALE;

        long stateLimitedAmountQ = network.getMaxAddableAmountQ(vapor, incomingSpecificEnthalpy, requestedAmountQ);
        long injectorLimitedAmountQ = MTEIntegratedFluidInjectorHatch
            .clampAcceptedPhysicalAddToInjectorPressure(network, vapor, incomingSpecificEnthalpy, stateLimitedAmountQ);

        assertTrue(injectorLimitedAmountQ > 0L);
        assertTrue(injectorLimitedAmountQ <= stateLimitedAmountQ);

        long injectorEnthalpyQ = IntegratedFluidNetwork
            .toEnthalpyQFromSpecific(incomingSpecificEnthalpy, injectorLimitedAmountQ);
        float predictedPressure = network.predictPressureAfterAdd(vapor, injectorLimitedAmountQ, injectorEnthalpyQ);
        assertTrue(
            predictedPressure <= IFNPressurePolicy.injectorCutoffPressureBar(),
            "predicted injector fill pressure was " + predictedPressure + " bar");

        network.add(vapor, injectorLimitedAmountQ, injectorEnthalpyQ);
        assertTrue(
            network.getPressure() <= IFNPressurePolicy.injectorCutoffPressureBar(),
            "actual injector fill pressure was " + network.getPressure() + " bar");
    }
}
