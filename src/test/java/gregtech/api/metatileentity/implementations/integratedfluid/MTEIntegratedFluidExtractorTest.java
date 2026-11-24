package gregtech.api.metatileentity.implementations.integratedfluid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.IChatComponent;
import net.minecraftforge.fluids.Fluid;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.integratedfluid.state.IFNCanonicalState;

class MTEIntegratedFluidExtractorTest {

    @Test
    void extractorOnlyDrainsNormalNetworks() {
        Fluid liquid = IFNTestSupport.liquidFluid();
        IntegratedFluidNetwork network = IFNTestSupport.newNetwork(liquid, 1_000, 0, 1.0f);

        assertTrue(MTEIntegratedFluidExtractor.canExtractFromNetwork(network));
        assertFalse(MTEIntegratedFluidExtractor.canExtractFromNetwork(null));

        network.setPending(true);

        assertFalse(MTEIntegratedFluidExtractor.canExtractFromNetwork(network));

        network.setPending(false);
        network.freeze("test freeze");

        assertFalse(MTEIntegratedFluidExtractor.canExtractFromNetwork(network));
    }

    @Test
    void inspectingOneSteamUnitReportsItsMassRatherThanAnEmptyTank() {
        var network = IFNTestSupport.newNetwork(IFNTestSupport.waterFluid(), 100, 0, 10);
        var extractor = new MTEIntegratedFluidExtractor("extractor", 0, new String[0], null);
        extractor.setNetwork(network);
        var tile = mock(IGregTechTileEntity.class);
        var player = mock(EntityPlayer.class);
        var messages = ArgumentCaptor.forClass(IChatComponent.class);
        extractor.onRightclick(tile, player);
        verify(player).addChatMessage(messages.capture());
        assertTrue(messages.getValue().getUnformattedText().contains("Empty"));
        clearInvocations(player);
        var steam = IFNCanonicalState.fromRaw(
            "water", 6250, 14_129_490_625L);
        network.replaceCanonicalState(steam);
        extractor.onRightclick(tile, player);
        verify(player).addChatMessage(messages.capture());
        String reading = messages.getValue().getUnformattedText();
        assertTrue(reading.contains("0.00625 kg"), reading);
        assertTrue(reading.contains("100 L"), reading);
        assertFalse(reading.contains("Empty"), reading);
        assertEquals(6250, network.getAmountQ());
        assertEquals(14_129_490_625L, network.getCanonicalState().totalEnergy().rawUnits());
    }

    @Test
    void frozenNetworkDrainReturnsNullWithoutChangingState() {
        Fluid liquid = IFNTestSupport.liquidFluid();
        IntegratedFluidNetwork network = IFNTestSupport.newNetwork(liquid, 1_000, 0, 1.0f);
        MTEIntegratedFluidExtractor extractor = new MTEIntegratedFluidExtractor("extractor", 0, new String[0], null);

        extractor.setNetwork(network);
        network.freeze("test freeze");

        long beforeAmountQ = network.getAmountQ();

        assertNull(extractor.drain(100, true));
        assertEquals(beforeAmountQ, network.getAmountQ());
    }
}
