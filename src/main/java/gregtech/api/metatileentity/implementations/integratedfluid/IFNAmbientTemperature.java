package gregtech.api.metatileentity.implementations.integratedfluid;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.world.World;
import gregtech.api.enums.Mods;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import micdoodle8.mods.galacticraft.api.world.IGalacticraftWorldProvider;

public final class IFNAmbientTemperature {

    private static final Map<Integer, Float> DIMENSION_TEMPERATURES = new HashMap<>();

    private IFNAmbientTemperature() {
    }

    static {
        registerDimensionTemperature(0, 300.0f);
        registerDimensionTemperature(-1, 400.0f);
        registerDimensionTemperature(1, 100.0f);
    }

    public static void registerDimensionTemperature(int dimensionId, float temperatureK) {
        if (!(temperatureK >= 0) || !Float.isFinite(temperatureK)) throw new IllegalArgumentException("Invalid dimension temperature");
        DIMENSION_TEMPERATURES.put(dimensionId, temperatureK);
    }

    static float atPort(IGregTechTileEntity tile) {
        return getAmbientTemperature(tile == null ? null : tile.getWorld());
    }

    public static float getAmbientTemperature(World world) {
        if (world == null || world.provider == null) {
            return IntegratedFluidNetwork.AMBIENT_TEMPERATURE;
        }
        Integer dimId = world.provider.dimensionId;
        Float temperature = DIMENSION_TEMPERATURES.get(dimId);
        if (temperature != null) return temperature;
        if (Mods.GalacticraftCore.isModLoaded() && world.provider instanceof IGalacticraftWorldProvider planet) {
            // Galacticraft exposes thermal severity, not kelvin. These are VFN gameplay temperatures
            return Math.max(10f, 260f + 35f * planet.getThermalLevelModifier());
        }
        return IntegratedFluidNetwork.AMBIENT_TEMPERATURE;
    }
}
