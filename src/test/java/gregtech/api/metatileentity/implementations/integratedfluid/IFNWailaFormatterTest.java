package gregtech.api.metatileentity.implementations.integratedfluid;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.jupiter.api.Test;

import gregtech.api.metatileentity.implementations.integratedfluid.amount.SubstanceAmount;

class IFNWailaFormatterTest {

    @Test
    void frozenStatusIncludesReason() {
        NBTTagCompound tag = new NBTTagCompound();
        List<String> tooltip = new ArrayList<>();

        tag.setBoolean("hasNetwork", true);
        tag.setString("networkStatus", "FROZEN");
        tag.setString("frozenReason", "fluid conflict");

        IFNWailaFormatter.addNetworkStatus(tag, tooltip);

        assertTrue(tooltip.stream().anyMatch(line -> line.contains("Status:") && line.contains("Locked")));
        assertTrue(tooltip.stream().anyMatch(line -> line.contains("fluid conflict")));
    }

    @Test
    void pendingStatusIsVisible() {
        NBTTagCompound tag = new NBTTagCompound();
        List<String> tooltip = new ArrayList<>();

        tag.setBoolean("hasNetwork", true);
        tag.setString("networkStatus", "PENDING");

        IFNWailaFormatter.addNetworkStatus(tag, tooltip);

        assertTrue(tooltip.stream().anyMatch(line -> line.contains("Status:") && line.contains("Pending")));
    }

    @Test
    void overLimitStatusIsVisible() {
        NBTTagCompound tag = new NBTTagCompound();
        List<String> tooltip = new ArrayList<>();

        tag.setBoolean("hasNetwork", true);
        tag.setString("networkStatus", "NORMAL");
        tag.setString("pressureLimitStatus", "WARNING");
        tag.setString("temperatureLimitStatus", "RUPTURE");

        IFNWailaFormatter.addNetworkStatus(tag, tooltip);

        assertTrue(tooltip.stream().anyMatch(line -> line.contains("Pressure") && line.contains("Over Limit")));
        assertTrue(tooltip.stream().anyMatch(line -> line.contains("Temperature") && line.contains("Rupture Risk")));
    }

    @Test
    void vacuumAndUnsolvedContentsDoNotAdvertiseAFluidTemperatureOrPhase() {
        var network = IFNTestSupport.newNetwork(IFNTestSupport.waterFluid(), 1000, 0, 20);
        var vacuum = new NBTTagCompound();
        IFNWailaFormatter.writeNetworkStatus(network, vacuum);
        assertTrue(vacuum.hasKey("pressure") && vacuum.getFloat("pressure") == 0);
        assertFalse(vacuum.hasKey("temperature"));
        assertFalse(vacuum.hasKey("phase"));
        network.replaceCanonicalState(gregtech.api.metatileentity.implementations.integratedfluid.state.IFNCanonicalState
            .fromRaw("water", 1_000_000, Long.MAX_VALUE));
        var invalid = new NBTTagCompound();
        IFNWailaFormatter.writeNetworkStatus(network, invalid);
        assertTrue(network.isFrozen() && invalid.getLong("substanceAmountQ") == 1_000_000);
        assertFalse(invalid.hasKey("temperature") || invalid.hasKey("pressure") || invalid.hasKey("phase"));
    }

    @Test
    void oneExternalSteamUnitShowsFractionalWaterMass() {
        net.minecraftforge.fluids.Fluid water = IFNTestSupport.waterFluid();
        IntegratedFluidNetwork network = IFNTestSupport.newNetwork(water, 1000, 0, 20.0f);
        network.replaceCanonicalState(gregtech.api.metatileentity.implementations.integratedfluid.state.IFNCanonicalState
            .fromRaw("water", 6250L, 14_129_490_625L));
        NBTTagCompound tag = new NBTTagCompound();
        tag.setBoolean("hasNetwork", true);
        IFNWailaFormatter.writeNetworkStatus(network, tag);
        List<String> tooltip = new ArrayList<>();
        IFNWailaFormatter.addNetworkStatus(tag, tooltip);
        IFNWailaFormatter.addFluidStorageSummary(tag, tooltip, "Fluid", true);
        assertTrue(tooltip.stream().anyMatch(line -> line.contains("0.00625 kg")), tooltip.toString());
        assertFalse(tooltip.stream().anyMatch(line -> line.contains("Empty")), tooltip.toString());
    }

    @Test
    void massIsAlwaysInKilograms() {
        NBTTagCompound tag = new NBTTagCompound();
        List<String> tooltip = new ArrayList<>();

        tag.setBoolean("hasNetwork", true);
        tag.setString("networkStatus", "NORMAL");
        tag.setLong("substanceAmountQ", 12L * SubstanceAmount.SCALE);

        IFNWailaFormatter.addNetworkStatus(tag, tooltip);

        assertTrue(tooltip.stream().anyMatch(line -> line.contains("Mass:") && line.contains("12 kg")));
    }

    @Test
    void networkLimitsShowRawMaximumValues() {
        NBTTagCompound tag = new NBTTagCompound();
        List<String> tooltip = new ArrayList<>();

        tag.setBoolean("hasNetwork", true);
        tag.setString("networkStatus", "NORMAL");
        tag.setFloat("maxPressureBar", 32.0f);
        tag.setFloat("maxTemperatureKelvin", 1200.0f);

        IFNWailaFormatter.addNetworkStatus(tag, tooltip);

        assertTrue(
            tooltip.stream().anyMatch(line -> line.contains("Max Pressure:") && line.contains("32.00 bar")),
            tooltip.toString());
        assertTrue(
            tooltip.stream().anyMatch(line -> line.contains("Max Temperature:") && line.contains("1200.00 K")),
            tooltip.toString());
    }

    @Test
    void twoPhaseMixtureIsVisibleInNetworkStatus() {
        NBTTagCompound tag = new NBTTagCompound();
        List<String> tooltip = new ArrayList<>();

        tag.setBoolean("hasNetwork", true);
        tag.setString("networkStatus", "NORMAL");
        tag.setString("phase", "TWO_PHASE");
        tag.setLong("substanceAmountQ", 42_400_000);

        IFNWailaFormatter.addNetworkStatus(tag, tooltip);

        assertTrue(tooltip.stream().anyMatch(line -> line.contains("Phase:") && line.contains("Two Phase")),
            tooltip.toString());
    }

    @Test
    void vaporPhaseDisplayIsNotDowngradedToGas() {
        NBTTagCompound tag = new NBTTagCompound();
        List<String> tooltip = new ArrayList<>();

        tag.setBoolean("hasNetwork", true);
        tag.setString("networkStatus", "NORMAL");
        tag.setString("phase", "VAPOR");
        tag.setLong("substanceAmountQ", 42_400_000);

        IFNWailaFormatter.addNetworkStatus(tag, tooltip);

        assertTrue(tooltip.stream().anyMatch(line -> line.contains("Phase:") && line.contains("Vapor")),
            tooltip.toString());
        assertTrue(tooltip.stream().noneMatch(line -> line.contains("Phase:") && line.contains("Gas")),
            tooltip.toString());
    }

    @Test
    void fluidStorageSummaryUsesOccupiedVolumeInsteadOfFluidStackAmount() {
        NBTTagCompound tag = new NBTTagCompound();
        List<String> tooltip = new ArrayList<>();

        tag.setLong("maxCapacity", 3_000_000_000L);
        tag.setLong("totalCapacity", 3_000_000_000L);
        tag.setDouble("occupiedVolume", 2_900_000_000d);
        tag.setString("substanceId", "water");
        tag.setLong("substanceAmountQ", 200_000_000);

        IFNWailaFormatter.addFluidStorageSummary(tag, tooltip, "Network Fluid", false);

        assertTrue(tooltip.stream().anyMatch(line -> line.contains("Network Fluid:")));
        assertTrue(tooltip.stream().anyMatch(line -> line.contains("Occupied:") && line.replaceAll("[^0-9/]", "").equals("2900000000/3000000000")));
        assertTrue(tooltip.stream().noneMatch(line -> line.contains("Amount:") || line.contains("Std Amount")));
    }
}
