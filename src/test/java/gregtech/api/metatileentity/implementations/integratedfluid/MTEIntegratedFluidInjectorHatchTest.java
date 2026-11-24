package gregtech.api.metatileentity.implementations.integratedfluid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;

import org.junit.jupiter.api.Test;

import gregtech.api.metatileentity.implementations.integratedfluid.amount.SubstanceAmount;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.integratedfluid.thermodynamics.ClosedFluidChamber;
import gregtech.api.metatileentity.implementations.integratedfluid.thermodynamics.PureFluid;

class MTEIntegratedFluidInjectorHatchTest {

    @Test
    void physicalSteamAdmissionLeavesTheRequiredHeadBelowSupplyPressure() {
        ClosedFluidChamber empty = ClosedFluidChamber.empty(PureFluid.water(), 1000);
        var batch = IFNForgeFluidProfile.STEAM.admit(empty, Integer.MAX_VALUE, 300);
        // Rigid adiabatic filling from saturated vapor at 373.15 K gives u(receiver) = h(supply)
        double gasConstant = 461.5;
        double filledKelvin = 373.15 * 2010.0 / (2010.0 - gasConstant);
        double massAtIntakeCeiling = 90_000.0 * 1.0 / (gasConstant * filledKelvin);
        assertEquals((int) (massAtIntakeCeiling * 160), batch.units());
        var chamber = batch.chamber();
        BigDecimal expectedMass = new BigDecimal("0.00625").multiply(BigDecimal.valueOf(batch.units()));
        assertEquals(0, chamber.massKg().compareTo(expectedMass));
        assertEquals(filledKelvin, chamber.state().temperatureKelvin(), 1e-8);
        assertTrue(chamber.pressurePa() <= 90_000);
        assertEquals(0, IFNForgeFluidProfile.STEAM.admit(chamber, 1, 300).units());
        var partial = IFNForgeFluidProfile.STEAM.admit(empty, batch.units() - 1, 300);
        var last = IFNForgeFluidProfile.STEAM.admit(partial.chamber(), 1, 300);
        assertEquals(1, last.units());
        assertEquals(0, last.chamber().totalEnergyEu().compareTo(chamber.totalEnergyEu()));
        assertEquals(chamber.pressurePa(), last.chamber().pressurePa());
        assertTrue(empty.isEmpty());
    }

    @Test
    void physicalWaterAdmissionCarriesSignedFlowEnergyIntoVacuum() {
        var fill = IFNForgeFluidProfile.WATER.admit(ClosedFluidChamber.empty(PureFluid.water(), 2000), 1000, 300);
        assertEquals(1000, fill.units());
        var chamber = fill.chamber();
        assertEquals(0, chamber.massKg().compareTo(BigDecimal.valueOf(1000)));
        assertEquals(4180.0 * (300.0 - 373.15) * 1000, chamber.totalEnergyEu().doubleValue(), 1e-5);
        assertTrue(chamber.state().liquidMassFraction() > 0.99);
        assertTrue(chamber.pressurePa() > 0 && chamber.pressurePa() < 100_000);
    }

    @Test
    void boilerEquivalentSteamOffersCarryOneWaterUnitAndTheSameHeatInAnyPacketSize() {
        Fluid water = IFNTestSupport.waterFluid();
        Fluid gas = new Fluid("steam");
        IntegratedFluidNetwork network = IFNTestSupport.newNetwork(water, 10_000, 0, 20.0f);
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

        for (int packet = 1; packet <= 160; packet++) {
            long before = network.getAmountQ();
            assertEquals(1, injector.fill(ForgeDirection.WEST, offered, false));
            assertEquals(before, network.getAmountQ());
            assertEquals(1, injector.fill(ForgeDirection.WEST, offered, true));
            assertEquals(packet * SubstanceAmount.SCALE / 160, network.getAmountQ());
        }
        long energy = network.getCanonicalState().totalEnergy().rawUnits();
        assertTrue(energy > 0);
        var receiver = IFNTestSupport.newNetwork(water, 10_000, 0, 20.0f);
        injector.setNetwork(receiver);
        offered.amount = 160;
        assertEquals(160, injector.fill(ForgeDirection.WEST, offered, true));
        assertEquals(SubstanceAmount.SCALE, network.getAmountQ());
        assertEquals(SubstanceAmount.SCALE, receiver.getAmountQ());
        assertEquals(energy, receiver.getCanonicalState().totalEnergy().rawUnits());
    }

    @Test
    void retryAfterPartialFillCannotSpendFluidStillOwnedByTheSource() {
        Fluid fluid = IFNTestSupport.waterFluid();
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

        assertEquals(2, injector.fill(ForgeDirection.WEST, offered, true));
        assertEquals(2L * SubstanceAmount.SCALE, network.getAmountQ());
        assertEquals(10, offered.amount);
        offered.amount = 1;
        assertEquals(0, injector.fill(ForgeDirection.WEST, offered, true));
        var receiver = IFNTestSupport.newNetwork(fluid, 3, 0, 20.0f);
        injector.setNetwork(receiver);
        assertEquals(1, injector.fill(ForgeDirection.WEST, offered, false));
        assertEquals(0L, receiver.getAmountQ());
        assertEquals(1, injector.fill(ForgeDirection.WEST, offered, true));
        assertEquals(SubstanceAmount.SCALE, receiver.getAmountQ());
        assertEquals(2L * SubstanceAmount.SCALE, network.getAmountQ());
    }

    @Test
    void pendingAndFrozenPortsRefuseRealOffers() {
        var water = IFNTestSupport.waterFluid();
        var network = IFNTestSupport.newNetwork(water, 1000, 0, 1);
        var injector = new MTEIntegratedFluidInjectorHatch("test.injector", 1, new String[0], null);
        injector.setNetwork(network);
        FluidStack offer = mock(FluidStack.class);
        when(offer.getFluid()).thenReturn(water);
        offer.amount = 1;
        network.setPending(true);
        assertEquals(0, injector.fill(offer, false));
        assertEquals(0, injector.fill(offer, true));
        network.setPending(false);
        network.freeze("maintenance");
        assertEquals(0, injector.fill(offer, true));
        assertEquals(0, network.getAmountQ());
    }

    @Test
    void registeredGasWithoutAnImportProfileIsRejected() {
        Fluid gas = IFNTestSupport.vaporFluid();
        IntegratedFluidNetwork network = IFNTestSupport.newNetwork(gas, 10_000, 0, 20.0f);
        MTEIntegratedFluidInjectorHatch injector = new MTEIntegratedFluidInjectorHatch(
            "test.injector", 1, new String[0], null);
        injector.setNetwork(network);
        FluidStack offered = mock(FluidStack.class);
        when(offered.getFluid()).thenReturn(gas);
        offered.amount = 160;
        assertEquals(0, injector.fill(ForgeDirection.WEST, offered, true));
        assertEquals(0L, network.getAmountQ());
    }

    @Test
    void oversizedOfferCommitsOnlyThePreviewedWholeSteamUnits() {
        var network = IFNTestSupport.newNetwork(IFNTestSupport.waterFluid(), 1000, 0, 10);
        var injector = new MTEIntegratedFluidInjectorHatch("test.injector", 1, new String[0], null);
        injector.setNetwork(network);
        FluidStack offer = mock(FluidStack.class);
        when(offer.getFluid()).thenReturn(new Fluid("steam"));
        offer.amount = Integer.MAX_VALUE;
        int quoted = injector.fill(offer, false);
        assertTrue(quoted > 0 && quoted < offer.amount);
        assertEquals(0, network.getAmountQ());
        assertEquals(quoted, injector.fill(offer, true));
        assertEquals(6250L * quoted, network.getAmountQ());
        assertTrue(network.getPressure() <= IFNPressurePolicy.injectorCutoffPressureBar());
        assertEquals(0, injector.fill(offer, true));
    }
}
