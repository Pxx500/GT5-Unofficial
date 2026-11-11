package gregtech.api.metatileentity.implementations.integratedfluid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.Fluid;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import gregtech.api.metatileentity.implementations.integratedfluid.fluid.IFNFluidRegistry;
import gregtech.api.metatileentity.implementations.integratedfluid.state.IFNCanonicalState;
import gregtech.api.metatileentity.implementations.integratedfluid.topology.IFNStateDistributor;

class IFNConservationTest {

    @ParameterizedTest
    @ValueSource(ints = { 1, 10, 100 })
    void closedGasCirculationCannotCreateEnergyRegardlessOfPacketSize(int packetLiters) {
        Fluid gas = IFNTestSupport.vaporFluid();
        IntegratedFluidNetwork first = IFNTestSupport.newNetwork(gas, 10_000, 0, 20.0f);
        IntegratedFluidNetwork second = IFNTestSupport.newNetwork(gas, 10_000, 0, 20.0f);
        long totalAmountQ = 100L * IntegratedFluidNetwork.AMOUNT_SCALE;
        long totalEnthalpyQ = 30_000L * IntegratedFluidNetwork.ENTHALPY_SCALE;
        assertTrue(first.addState(gas, totalAmountQ, totalEnthalpyQ));

        for (int cycle = 0; cycle < 10; cycle++) {
            for (int direction = 0; direction < 2; direction++) {
                IntegratedFluidNetwork source = direction == 0 ? first : second;
                IntegratedFluidNetwork destination = direction == 0 ? second : first;
                while (source.getAmountQ() > 0L) {
                    IntegratedFluidNetwork.ExtractedPayload payload = source
                        .extractProportional(packetLiters * IntegratedFluidNetwork.AMOUNT_SCALE, false);
                    assertTrue(destination.add(gas, payload.amountQ, payload.enthalpyQ));
                    assertEquals(totalAmountQ, first.getAmountQ() + second.getAmountQ());
                    assertEquals(totalEnthalpyQ, first.getEnthalpyQ() + second.getEnthalpyQ());
                }
            }
            assertEquals(300.0d, first.getDerivedTemperature(), 0.001d);
        }
    }

    @Test
    void extractionKeepsFractionalSubstanceUntilItIsTransferred() {
        Fluid fluid = IFNTestSupport.liquidFluid();
        IntegratedFluidNetwork network = IFNTestSupport.newNetwork(fluid, 100, 0, 20.0f);
        long halfLiterQ = IntegratedFluidNetwork.AMOUNT_SCALE / 2;
        long amountQ = 5 * halfLiterQ;
        assertTrue(network.addState(fluid, amountQ, IntegratedFluidNetwork.toEnthalpyQFromSpecific(300.0d, amountQ)));

        IntegratedFluidNetwork.ExtractedPayload drained = network.extractProportional(4 * halfLiterQ, false);
        assertEquals(4 * halfLiterQ, drained.amountQ);
        assertEquals(halfLiterQ, network.getAmountQ());
        assertEquals(150L * IntegratedFluidNetwork.ENTHALPY_SCALE, network.getEnthalpyQ());
        IntegratedFluidNetwork.ExtractedPayload preview = network.extractProportional(halfLiterQ, true);
        assertEquals(halfLiterQ, preview.amountQ);
        assertEquals(halfLiterQ, network.getAmountQ());

        assertTrue(network.addState(fluid, halfLiterQ, 150L * IntegratedFluidNetwork.ENTHALPY_SCALE));
        IntegratedFluidNetwork.ExtractedPayload last = network.extractProportional(2 * halfLiterQ, false);
        assertEquals(2 * halfLiterQ, last.amountQ);
        assertEquals(300L * IntegratedFluidNetwork.ENTHALPY_SCALE, last.enthalpyQ);
        assertEquals(0L, network.getAmountQ());
        assertEquals(0L, network.getEnthalpyQ());
    }

    @Test
    void liquidNitrogenRetainsTemperatureThroughNetworkSnapshot() {
        IFNFluidRegistry.init();
        Fluid nitrogen = new Fluid("nitrogen");
        IntegratedFluidNetwork original = IFNTestSupport.newNetwork(nitrogen, 100, 0, 20.0f);
        IntegratedFluidNetwork restored = IFNTestSupport.newNetwork(nitrogen, 100, 0, 20.0f);
        long amountQ = 10L * IntegratedFluidNetwork.AMOUNT_SCALE;
        // Registered nitrogen: h = 2 * (70 K - 273.15 K), for ten reference liters.
        long enthalpyQ = -4063L * IntegratedFluidNetwork.ENTHALPY_SCALE;
        assertTrue(original.addState(nitrogen, amountQ, enthalpyQ));

        restored.restoreSnapshot(original.snapshotState());

        assertEquals(amountQ, restored.getAmountQ());
        assertEquals(enthalpyQ, restored.getEnthalpyQ());
        assertEquals(70.0d, restored.getDerivedTemperature(), 0.001d);
    }

    @Test
    void coldStateSurvivesTopologySplitMergeAndPersistenceWithoutCreatingEnergy() {
        IFNFluidRegistry.init();
        Fluid nitrogen = new Fluid("nitrogen");
        IntegratedFluidNetwork original = IFNTestSupport.newNetwork(nitrogen, 100, 0, 20.0f);
        long amountQ = 10L * IntegratedFluidNetwork.AMOUNT_SCALE;
        long enthalpyQ = -4063L * IntegratedFluidNetwork.ENTHALPY_SCALE - 1L;
        assertTrue(original.addState(nitrogen, amountQ, enthalpyQ));

        IFNCanonicalState[] parts = IFNStateDistributor
            .splitStateByWeights(original.getCanonicalState(), new long[] { 1L, 2L });
        IntegratedFluidNetwork first = IFNTestSupport.newNetwork(nitrogen, 100, 0, 20.0f);
        IntegratedFluidNetwork second = IFNTestSupport.newNetwork(nitrogen, 100, 0, 20.0f);
        first.replaceCanonicalState(parts[0]);
        second.replaceCanonicalState(parts[1]);
        first.merge(second);

        UUID id = new UUID(10L, 20L);
        IntegratedFluidNetworkSavedData saved = new IntegratedFluidNetworkSavedData();
        saved.upsertState(id, first);
        NBTTagCompound tag = new NBTTagCompound();
        saved.writeToNBT(tag);
        IntegratedFluidNetworkSavedData loaded = new IntegratedFluidNetworkSavedData();
        loaded.readFromNBT(tag);
        IntegratedFluidNetworkSavedData.NetworkState state = loaded.getState(id);
        IntegratedFluidNetwork restored = IFNTestSupport.newNetwork(nitrogen, 100, 0, 20.0f);
        restored.loadState(state.fluidName, state.amountQ, state.enthalpyQ, 1.0f, state.expectedMemberCount);

        assertEquals(amountQ, restored.getAmountQ());
        assertEquals(enthalpyQ, restored.getEnthalpyQ());
        assertEquals(70.0d, restored.getDerivedTemperature(), 0.001d);
    }

    @Test
    void repeatedRejectedColdTransfersDoNotHeatTheInput() {
        IFNFluidRegistry.init();
        Fluid nitrogen = new Fluid("nitrogen");
        IntegratedFluidNetwork input = IFNTestSupport.newNetwork(nitrogen, 100, 0, 20.0f);
        IntegratedFluidNetwork output = IFNTestSupport.newNetwork(nitrogen, 100, 0, 20.0f);
        output.freeze("blocked output");
        long amountQ = 10L * IntegratedFluidNetwork.AMOUNT_SCALE;
        long enthalpyQ = -4063L * IntegratedFluidNetwork.ENTHALPY_SCALE;
        assertTrue(input.addState(nitrogen, amountQ, enthalpyQ));

        for (int attempt = 0; attempt < 16; attempt++) {
            IntegratedFluidNetwork.ExtractedPayload extracted = input.extractProportional(amountQ, false);
            assertFalse(
                IFNStateMutationApplier.addOutputOrRestoreInput(
                    input,
                    output,
                    nitrogen,
                    IntegratedFluidNetwork.ExtractedPayload.of(extracted.amountQ, extracted.enthalpyQ),
                    extracted.enthalpyQ));
            assertEquals(amountQ, input.getAmountQ());
            assertEquals(enthalpyQ, input.getEnthalpyQ());
            assertEquals(0L, output.getAmountQ());
        }
        assertEquals(70.0d, input.getDerivedTemperature(), 0.001d);
    }

    @Test
    void oneGtLiterInputIsOneReferenceLiter() {
        long oneRefLiterQ = IntegratedFluidNetwork.AMOUNT_SCALE;

        assertEquals(oneRefLiterQ, IntegratedFluidNetwork.toAmountQ(1));
        assertEquals(1, IntegratedFluidNetwork.toAmountMb(oneRefLiterQ));
    }

    @Test
    void changingThermalEnergyDoesNotChangeExtractableSubstance() {
        Fluid fluid = IFNTestSupport.vaporFluid();
        IntegratedFluidNetwork network = IFNTestSupport.newNetwork(fluid, 100_000, 0, 50.0f);
        long amountQ = 100L * IntegratedFluidNetwork.AMOUNT_SCALE;

        assertTrue(network.addState(fluid, amountQ, enthalpyFor(fluid, 2.0d, 300.0d, amountQ)));
        long originalAmountQ = network.getAmountQ();

        network.clearFluid();
        assertTrue(network.addState(fluid, amountQ, enthalpyFor(fluid, 2.0d, 600.0d, amountQ)));

        assertEquals(originalAmountQ, network.getAmountQ());
    }

    @Test
    void networkMergeSumsSubstanceAndEnergy() {
        Fluid fluid = IFNTestSupport.liquidFluid();
        IntegratedFluidNetwork first = IFNTestSupport.newNetwork(fluid, 10_000, 0, 20.0f);
        IntegratedFluidNetwork second = IFNTestSupport.newNetwork(fluid, 10_000, 0, 20.0f);
        long firstAmountQ = 10L * IntegratedFluidNetwork.AMOUNT_SCALE;
        long secondAmountQ = 5L * IntegratedFluidNetwork.AMOUNT_SCALE;
        long firstEnthalpyQ = enthalpyFor(fluid, 1.0d, 300.0d, firstAmountQ);
        long secondEnthalpyQ = enthalpyFor(fluid, 1.0d, 360.0d, secondAmountQ);

        assertTrue(first.addState(fluid, firstAmountQ, firstEnthalpyQ));
        assertTrue(second.addState(fluid, secondAmountQ, secondEnthalpyQ));

        first.merge(second);

        assertEquals(firstAmountQ + secondAmountQ, first.getAmountQ());
        assertEquals(firstEnthalpyQ + secondEnthalpyQ, first.getEnthalpyQ());
        assertEquals(0L, second.getAmountQ());
    }

    private static long enthalpyFor(Fluid fluid, double pressureBar, double temperatureK, long amountQ) {
        double specificEnthalpy = FluidThermalProperties.getSpecificEnthalpyFromPT(fluid, pressureBar, temperatureK);
        return IntegratedFluidNetwork.toEnthalpyQFromSpecific(specificEnthalpy, amountQ);
    }
}
