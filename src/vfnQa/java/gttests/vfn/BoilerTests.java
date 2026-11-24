package gttests.vfn;

import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;

import gregtech.api.GregTechAPI;
import gregtech.api.enums.Mods;

@GameTestHolder(value = Mods.ModIDs.GREG_TECH, templatePrefix = "vfn")
public class BoilerTests {

    @GameTest(template = "regulator", timeoutTicks = 200)
    public static void smallSteamChargeAcceptsPartialPower(GameTestHelper helper) {
        var line = AmbientTests.place(helper);
        var boiler = InjectorTests.placeHatch(helper, "valve", (short) 5776, ForgeDirection.WEST);
        helper.assertTrue(line.pipe().connect(ForgeDirection.WEST) > 0, "Rear thermal port connected");
        var meter = new AmbientTests.AmbientMeter(helper, line.pipe()::getNetwork);
        long[] start = new long[1];
        helper.onEachTick("precharge the idle boiler", () -> {
            if (line.pipe().getNetwork() != null && line.pipe().getNetwork().getAmountQ() == 0 && boiler.getStoredEU() < 2560) {
                boiler.injectEnergyUnits(ForgeDirection.WEST, 128, 1);
            }
        });
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertEquals(2560L, boiler.getStoredEU(), "One nominal heating packet buffered"))
            .thenExecute(() -> {
                // Even from ambient, one gram of dilute steam cannot take a full 2560 EU heating packet
                line.pipe().getNetwork().replaceCanonicalState(
                    gregtech.api.metatileentity.implementations.integratedfluid.state.IFNCanonicalState.fromRaw(
                        "water", 1000, 2_203_793_500L));
                start[0] = meter.withoutAmbient(line.pipe().getNetwork());
            })
            .thenWaitUntil(() -> helper.assertTrue(boiler.getStoredEU() < 2560,
                "Boiler spent EU to heat the small vapor charge"))
            .thenExecute(() -> {
                long paid = 2560 - boiler.getStoredEU();
                var network = line.pipe().getNetwork();
                helper.assertTrue(paid > 0 && paid < 2560, "Only the supported heat was paid for");
                helper.assertTrue(network.getTemperature() <= 1000, "Water stays within the calibrated range");
                helper.assertEquals(start[0] + paid * 1_000_000L, meter.withoutAmbient(network), "Partial packet balances");
                helper.assertEquals(1000L, network.getAmountQ(), "No vapor lost");
            })
            .thenSucceed();
    }

    @GameTest(template = "regulator", timeoutTicks = 500)
    public static void boilerWarmsSeparateCircuit(GameTestHelper helper) {
        var bench = ExchangerTests.place(helper, true);
        var pos = helper.absolute("outlet");
        var boiler = InjectorTests.placeAt(helper.getWorld(), pos.x(), pos.y(), pos.z() - 1, (short) 5776, ForgeDirection.NORTH);
        helper.assertTrue(bench.outlet().connect(ForgeDirection.NORTH) > 0, "Boiler connected to first circuit only");
        var hotMeter = new AmbientTests.AmbientMeter(helper, bench.hot()::getNetwork);
        var coldMeter = new AmbientTests.AmbientMeter(helper, bench.cold()::getNetwork);
        long[] start = new long[2];
        long[] inputEu = new long[1];
        boolean[] power = new boolean[1];
        helper.onEachTick("feed the primary circuit boiler", () -> {
            if (power[0]) inputEu[0] += boiler.injectEnergyUnits(ForgeDirection.NORTH, 128, 1) * 128;
        });
        helper.startSequence()
            .thenWaitUntil(() -> bench.ready(helper))
            .thenExecute(() -> {
                helper.assertEquals(1, bench.hot().fill(new FluidStack(FluidRegistry.WATER, 1), true), "Primary feedwater");
                helper.assertEquals(1, bench.cold().fill(new FluidStack(FluidRegistry.WATER, 1), true), "Secondary feedwater");
                start[0] = hotMeter.withoutAmbient(bench.hot().getNetwork());
                start[1] = coldMeter.withoutAmbient(bench.cold().getNetwork());
                power[0] = true;
            })
            .thenWaitUntil(() -> helper.assertTrue(coldMeter.withoutAmbient(bench.cold().getNetwork()) - start[1] > 50_000_000L,
                "Unpowered secondary circuit gained at least 50 EU from the primary"))
            .thenExecute(() -> {
                power[0] = false;
                var hot = bench.hot().getNetwork();
                var cold = bench.cold().getNetwork();
                long primary = hotMeter.withoutAmbient(hot) - start[0];
                long secondary = coldMeter.withoutAmbient(cold) - start[1];
                helper.assertEquals((inputEu[0] - boiler.getStoredEU()) * 1_000_000L, primary + secondary,
                    "Both circuits share only the heat paid for at the boiler");
                helper.assertTrue(hot.getTemperature() > cold.getTemperature(), "Temperature drives the exchange");
                helper.assertEquals(1_000_000L, hot.getAmountQ(), "Primary mass unchanged");
                helper.assertEquals(1_000_000L, cold.getAmountQ(), "Secondary mass unchanged");
            })
            .thenSucceed();
    }

    @GameTest(template = "regulator", timeoutTicks = 400)
    public static void paidHeatBoilsWater(GameTestHelper helper) {
        helper.assertNotNull(GregTechAPI.METATILEENTITIES[5776], "Registered VFN electric boiler");
        var line = AmbientTests.place(helper);
        var heater = InjectorTests.placeHatch(helper, "valve", (short) 5776, ForgeDirection.WEST);
        helper.assertTrue(line.pipe().connect(ForgeDirection.WEST) > 0, "Rear thermal port connected");
        var meter = new AmbientTests.AmbientMeter(helper, line.pipe()::getNetwork);
        long[] initial = new long[1];
        double[] vapor = new double[1];
        long[] paid = new long[1];
        boolean[] powered = new boolean[1];
        helper.onEachTick("supply a single MV packet", () -> {
            if (powered[0]) paid[0] += 128 * heater.injectEnergyUnits(ForgeDirection.WEST, 128, 1);
        });
        helper.startSequence()
            .thenWaitUntil(() -> line.ready(helper))
            .thenWaitUntil(() -> helper.assertEquals(1L, heater.injectEnergyUnits(ForgeDirection.WEST, 128, 1), "MV input online"))
            .thenIdle(25)
            .thenExecute(() -> {
                helper.assertEquals(128L, heater.getStoredEU(), "Dry boiler retains its charge");
                helper.assertEquals(0L, heater.injectEnergyUnits(ForgeDirection.EAST, 128, 1), "No EU input through thermal port");
                helper.assertEquals(1, line.inlet().fill(new FluidStack(FluidRegistry.WATER, 1), true), "Water enters sealed pipe");
                initial[0] = meter.withoutAmbient(line.pipe().getNetwork());
                vapor[0] = line.pipe().getNetwork().getQuality();
                paid[0] = 128;
                powered[0] = true;
            })
            .thenWaitUntil(() -> helper.assertTrue(paid[0] >= 8192 && line.pipe().getNetwork().getQuality() > vapor[0],
                "Electrical heat evaporates some of the liquid"))
            .thenExecute(() -> powered[0] = false)
            .thenWaitUntil(() -> helper.assertEquals(0L, heater.getStoredEU(), "Last paid packet consumed"))
            .thenIdle(40)
            .thenExecute(() -> {
                var network = line.pipe().getNetwork();
                helper.assertEquals(initial[0] + paid[0] * 1_000_000L, meter.withoutAmbient(network),
                    "Exactly the supplied EU became heat, including after power cutoff");
                helper.assertEquals(1_000_000L, network.getAmountQ(), "Evaporation preserves the injected kilogram");
                helper.assertTrue(!heater.isActive(), "Boiler idle without power");
            })
            .thenSucceed();
    }
}
