package gregtech.api.metatileentity.implementations.integratedfluid;

import static com.gtnewhorizon.gtnhlib.util.numberformatting.NumberFormatUtil.formatNumber;

import java.util.List;
import java.util.Locale;

import gregtech.api.metatileentity.implementations.integratedfluid.amount.SubstanceAmount;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumChatFormatting;

public final class IFNWailaFormatter {

    private IFNWailaFormatter() {}

    public static void writeNetworkStatus(IntegratedFluidNetwork network, NBTTagCompound tag) {
        tag.setBoolean("hasNetwork", network != null);
        if (network == null) return;
        var geometry = network.getTopologySnapshot();
        tag.setInteger("memberCount", geometry.memberCount());
        tag.setLong("maxCapacity", geometry.baseVolume().toWholeLiters());
        tag.setLong("accumulatorCapacity", geometry.accumulatorVolume().toWholeLiters());
        tag.setLong("totalCapacity", geometry.totalVolume().toWholeLiters());
        if (network.getAmountQ() == 0) tag.setFloat("pressure", 0);

        tag.setString("networkStatus", network.getNetworkStatus().name());
        String frozenReason = network.getFrozenReason();
        if (frozenReason != null && !frozenReason.isEmpty()) {
            tag.setString("frozenReason", frozenReason);
        }
        tag.setLong("substanceAmountQ", network.getAmountQ());
        tag.setString("substanceId", network.getCanonicalState().fluidId().orElse(""));
        var state = network.getSolvedState();
        if (state.isPresent()) {
            var phases = state.get();
            tag.setFloat("pressure", (float) (phases.pressurePa() / 100000));
            tag.setFloat("temperature", (float) phases.temperatureKelvin());
            tag.setDouble("occupiedVolume", network.getOccupiedVolume());
            if (!network.isPending() && network.getAmbientExchangeTick() != Long.MIN_VALUE) {
                tag.setDouble("ambientEuPerTick", network.getAmbientHeatMicroEu() / (1_000_000d * NetworkManager.AMBIENT_STEP_TICKS));
            }
            double vapor = phases.vaporMassFraction();
            String phase = vapor == 1 ? "VAPOR" : vapor == 0 ? "LIQUID" : "TWO_PHASE";
            switch (phases.denseRegion()) {
                case SUPERCRITICAL -> phase = "SUPERCRITICAL";
                case CROSSOVER -> phase = "DENSE_FLUID";
                default -> {}
            }
            if (phases.solidMassFraction() > 0) {
                phase = "SOLID";
                if (phases.liquidMassFraction() > 0) phase += "_AND_LIQUID";
                if (vapor > 0) phase += "_AND_VAPOR";
            }
            tag.setString("phase", phase);
            tag.setDouble("quality", vapor);
            tag.setDouble("solidFraction", phases.solidMassFraction());
            tag.setDouble("liquidFraction", phases.liquidMassFraction());
        }
        tag.setFloat("maxPressureBar", network.getOperationalLimits().maxPressureBar());
        tag.setFloat("maxTemperatureKelvin", network.getOperationalLimits().maxTemperatureKelvin());
        tag.setString("pressureLimitStatus", network.getPressureLimitEvaluation().status().name());
        tag.setString("temperatureLimitStatus", network.getTemperatureLimitEvaluation().status().name());
    }

    public static void addNetworkStatus(NBTTagCompound tag, List<String> currenttip) {
        if (tag == null || currenttip == null || !tag.getBoolean("hasNetwork")) {
            return;
        }

        String status = tag.getString("networkStatus");
        if (status == null || status.isEmpty()) {
            return;
        }

        if ("FROZEN".equals(status)) {
            currenttip.add("Status: " + EnumChatFormatting.RED + "Locked" + EnumChatFormatting.RESET);
            String reason = tag.getString("frozenReason");
            if (reason != null && !reason.isEmpty()) {
                currenttip.add(EnumChatFormatting.RED + reason + EnumChatFormatting.RESET);
            }
        } else if ("PENDING".equals(status)) {
            currenttip.add("Status: " + EnumChatFormatting.YELLOW + "Pending" + EnumChatFormatting.RESET);
        } else {
            currenttip.add("Status: " + EnumChatFormatting.GREEN + "Normal" + EnumChatFormatting.RESET);
        }

        addSubstanceAmount(tag, currenttip);
        addPhase(tag, currenttip);
        if (tag.hasKey("ambientEuPerTick")) {
            currenttip.add(String.format(Locale.ROOT, "Ambient heat: %+.3f EU/t", tag.getDouble("ambientEuPerTick")));
        }
        addNetworkLimits(tag, currenttip);
        addLimitStatus(tag.getString("pressureLimitStatus"), "Pressure", currenttip);
        addLimitStatus(tag.getString("temperatureLimitStatus"), "Temperature", currenttip);
    }

    private static void addSubstanceAmount(NBTTagCompound tag, List<String> currenttip) {
        long substanceAmountQ = tag.getLong("substanceAmountQ");
        if (substanceAmountQ <= 0L) {
            return;
        }

        String kilograms = SubstanceAmount.fromRawUnits(substanceAmountQ).formatKilograms();
        currenttip.add("Mass: " + EnumChatFormatting.GRAY + kilograms + " kg" + EnumChatFormatting.RESET);
    }

    private static void addPhase(NBTTagCompound tag, List<String> currenttip) {
        String phase = tag.getString("phase");
        if (phase == null || phase.isEmpty() || tag.getLong("substanceAmountQ") <= 0) {
            return;
        }

        currenttip.add("Phase: " + EnumChatFormatting.GRAY + formatPhaseName(phase) + EnumChatFormatting.RESET);
        double liquid = tag.getDouble("liquidFraction");
        double solid = tag.getDouble("solidFraction");
        if (liquid > 0 && liquid < 1) currenttip.add(String.format(Locale.ROOT, "Liquid mass: %.1f%%", 100 * liquid));
        if (solid > 0 && solid < 1) currenttip.add(String.format(Locale.ROOT, "Solid mass: %.1f%%", 100 * solid));
    }

    private static String formatPhaseName(String phase) {
        String normalized = phase.toLowerCase(Locale.ROOT).replace('_', ' ');
        StringBuilder builder = new StringBuilder(normalized.length());
        boolean startOfWord = true;
        for (int i = 0; i < normalized.length(); i++) {
            char value = normalized.charAt(i);
            if (value == ' ') {
                builder.append(value);
                startOfWord = true;
            } else if (startOfWord) {
                builder.append(Character.toUpperCase(value));
                startOfWord = false;
            } else {
                builder.append(value);
            }
        }
        return builder.toString();
    }

    private static void addNetworkLimits(NBTTagCompound tag, List<String> currenttip) {
        if (tag.hasKey("maxPressureBar")) {
            currenttip.add(
                "Max Pressure: " + EnumChatFormatting.YELLOW
                    + String.format(Locale.ROOT, "%.2f bar", tag.getFloat("maxPressureBar"))
                    + EnumChatFormatting.RESET);
        }
        if (tag.hasKey("maxTemperatureKelvin")) {
            float maxTemperatureKelvin = tag.getFloat("maxTemperatureKelvin");
            if (!Float.isInfinite(maxTemperatureKelvin) && maxTemperatureKelvin < Float.MAX_VALUE) {
                currenttip.add(
                    "Max Temperature: " + EnumChatFormatting.RED
                        + String.format(Locale.ROOT, "%.2f K", maxTemperatureKelvin)
                        + EnumChatFormatting.RESET);
            }
        }
    }

    public static void addFluidStorageSummary(NBTTagCompound tag, List<String> currenttip, String fluidLabel,
        boolean detailedCapacity) {
        if (tag == null || currenttip == null) {
            return;
        }

        long maxCapacity = tag.getLong("maxCapacity");
        long accumulatorCapacity = tag.getLong("accumulatorCapacity");
        long totalCapacity = tag.getLong("totalCapacity");
        if (tag.getLong("substanceAmountQ") == 0) {
            if (detailedCapacity) {
                currenttip.add("Empty (Capacity: " + formatNumber(totalCapacity) + " L)");
                addCapacitySummary(currenttip, maxCapacity, accumulatorCapacity, totalCapacity, true);
            } else {
                currenttip.add("Network: Empty");
            }
            return;
        }

        String substance = tag.getString("substanceId");
        String fluidName = "water".equals(substance) ? net.minecraft.util.StatCollector.translateToLocal("tile.water.name") : substance;
        currenttip.add(fluidLabel + ": " + EnumChatFormatting.AQUA + fluidName + EnumChatFormatting.RESET);
        if (tag.hasKey("occupiedVolume")) {
            long occupiedVolume = Math.round(tag.getDouble("occupiedVolume"));
            currenttip.add(
                "Occupied: " + EnumChatFormatting.GREEN
                    + formatNumber(occupiedVolume)
                    + "/"
                    + formatNumber(totalCapacity)
                    + " L"
                    + EnumChatFormatting.RESET);
        }
        addCapacitySummary(currenttip, maxCapacity, accumulatorCapacity, totalCapacity, detailedCapacity);
    }

    private static void addCapacitySummary(List<String> currenttip, long maxCapacity, long accumulatorCapacity,
        long totalCapacity, boolean detailedCapacity) {
        if (accumulatorCapacity <= 0) {
            return;
        }

        if (detailedCapacity) {
            currenttip.add(
                EnumChatFormatting.GRAY + "Capacity: "
                    + formatNumber(maxCapacity)
                    + " + "
                    + formatNumber(accumulatorCapacity)
                    + " = "
                    + formatNumber(totalCapacity)
                    + " L" + EnumChatFormatting.RESET);
            return;
        }

        currenttip.add(
            EnumChatFormatting.GRAY + "(+"
                + formatNumber(accumulatorCapacity)
                + " Hydrophore capacity)" + EnumChatFormatting.RESET);
    }

    static void addOperatingConditions(NBTTagCompound tag, List<String> lines) {
        if (tag.hasKey("pressure")) lines.add(String.format(Locale.ROOT, "Pressure: %s%.2f bar%s",
            EnumChatFormatting.YELLOW, tag.getFloat("pressure"), EnumChatFormatting.RESET));
        if (tag.hasKey("temperature")) lines.add(String.format(Locale.ROOT, "Temperature: %s%.2f K%s",
            EnumChatFormatting.RED, tag.getFloat("temperature"), EnumChatFormatting.RESET));
    }

    private static void addLimitStatus(String status, String label, List<String> currenttip) {
        if ("RUPTURE".equals(status)) {
            currenttip.add(label + ": " + EnumChatFormatting.RED + "Rupture Risk" + EnumChatFormatting.RESET);
        } else if ("WARNING".equals(status)) {
            currenttip.add(label + ": " + EnumChatFormatting.YELLOW + "Over Limit" + EnumChatFormatting.RESET);
        }
    }
}
