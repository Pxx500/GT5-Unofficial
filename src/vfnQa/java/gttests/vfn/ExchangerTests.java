package gttests.vfn;

import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;

import gregtech.api.GregTechAPI;
import gregtech.api.enums.Mods;
import gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidInjectorHatch;
import gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidPipe;
import gregtech.api.metatileentity.implementations.integratedfluid.state.IFNCanonicalState;

@GameTestHolder(value = Mods.ModIDs.GREG_TECH, templatePrefix = "vfn")
public class ExchangerTests {

    record Bench(MTEIntegratedFluidInjectorHatch hot, MTEIntegratedFluidInjectorHatch cold, MTEIntegratedFluidPipe source, MTEIntegratedFluidPipe outlet) {
        void ready(GameTestHelper helper) {
            helper.assertTrue(hot.getNetwork() != null && cold.getNetwork() != null && hot.getNetwork() != cold.getNetwork()
                && ((source.getNetwork() == hot.getNetwork() && outlet.getNetwork() == cold.getNetwork())
                    || (source.getNetwork() == cold.getNetwork() && outlet.getNetwork() == hot.getNetwork())), "Independent pipe inventories");
        }

        void charge(GameTestHelper helper) {
            helper.assertEquals(6, hot.fill(FluidRegistry.getFluidStack("steam", 6), true), "Steam admitted");
            helper.assertEquals(1, cold.fill(new FluidStack(FluidRegistry.WATER, 1), true), "Feedwater admitted");
        }
    }

    static Bench place(GameTestHelper helper, boolean reverse) {
        return place(helper, reverse, (short) 5775);
    }

    static Bench place(GameTestHelper helper, boolean reverse, short machineId) {
        return place(helper, RemoteMachineSite.at(helper, "source"), reverse, machineId);
    }

    static Bench place(GameTestHelper helper, RemoteMachineSite site, boolean reverse, short machineId) {
        helper.assertNotNull(GregTechAPI.METATILEENTITIES[machineId], "Registered VFN machine " + machineId);
        var left = (MTEIntegratedFluidInjectorHatch) site.place(-1, 0, 0, 5773, ForgeDirection.EAST).getMetaTileEntity();
        var a = (MTEIntegratedFluidPipe) site.place(0, 0, 0, 5770, ForgeDirection.EAST).getMetaTileEntity();
        site.place(1, 0, 0, machineId, ForgeDirection.EAST);
        var b = (MTEIntegratedFluidPipe) site.place(2, 0, 0, 5770, ForgeDirection.WEST).getMetaTileEntity();
        var right = (MTEIntegratedFluidInjectorHatch) site.place(3, 0, 0, 5773, ForgeDirection.WEST).getMetaTileEntity();
        a.connect(ForgeDirection.WEST);
        b.connect(ForgeDirection.EAST);
        helper.assertTrue(a.connect(ForgeDirection.EAST) > 0 && b.connect(ForgeDirection.WEST) > 0,
            "Both pipe ends connect to the exchanger");
        return reverse ? new Bench(right, left, a, b) : new Bench(left, right, a, b);
    }

    @GameTest(template = "regulator", timeoutTicks = 180)
    public static void steamHeatsFeedwater(GameTestHelper helper) {
        exercise(helper, false);
    }

    @GameTest(template = "regulator", timeoutTicks = 180)
    public static void hotSideCanBeReversed(GameTestHelper helper) {
        exercise(helper, true);
    }

    private static void exercise(GameTestHelper helper, boolean reverse) {
        var bench = place(helper, reverse);
        var hot = bench.hot();
        var cold = bench.cold();
        var hotMeter = new AmbientTests.AmbientMeter(helper, hot::getNetwork);
        var coldMeter = new AmbientTests.AmbientMeter(helper, cold::getNetwork);
        long[] baseline = new long[2];
        helper.startSequence()
            .thenWaitUntil(() -> bench.ready(helper))
            .thenExecute(() -> {
                bench.outlet().disconnect(ForgeDirection.WEST);
                bench.charge(helper);
                baseline[0] = hotMeter.withoutAmbient(hot.getNetwork());
                baseline[1] = coldMeter.withoutAmbient(cold.getNetwork());
            })
            .thenIdle(25)
            .thenExecute(() -> {
                helper.assertEquals(baseline[0], hotMeter.withoutAmbient(hot.getNetwork()), "No contact across disconnected port");
                helper.assertEquals(baseline[1], coldMeter.withoutAmbient(cold.getNetwork()), "Only ambient heat before connection");
                helper.assertTrue(bench.outlet().connect(ForgeDirection.WEST) > 0, "Reconnect exchanger");
            })
            .thenWaitUntil(() -> helper.assertTrue(coldMeter.withoutAmbient(cold.getNetwork()) - baseline[1] > 1_000_000_000L,
                "Feedwater receives more than 1000 EU through the exchanger"))
            .thenExecute(() -> {
                long received = coldMeter.withoutAmbient(cold.getNetwork()) - baseline[1];
                helper.assertEquals(received, baseline[0] - hotMeter.withoutAmbient(hot.getNetwork()), "Equal and opposite contact heat");
                helper.assertEquals(37_500L, hot.getNetwork().getAmountQ(), "All steam-side mass stays on its side");
                helper.assertEquals(1_000_000L, cold.getNetwork().getAmountQ(), "All feedwater stays on its side");
                helper.assertTrue(hot.getNetwork().getTemperature() >= cold.getNetwork().getTemperature(), "No temperature crossover");
            })
            .thenSucceed();
    }

    static void chargeCryogenicExchanger(GameTestHelper helper, Bench bench) {
        // A prepared 100 L cryogenic loop, not a Forge liquid-hydrogen import
        bench.source().getNetwork().replaceCanonicalState(IFNCanonicalState.fromRaw("hydrogen", 250_000, 46_442_750_000L));
        var gas = bench.source().getNetwork().getSolvedState().get();
        helper.assertTrue(gas.temperatureKelvin() < 22 && gas.vaporMassFraction() > 0 && gas.vaporMassFraction() < 1,
            "Two-phase hydrogen charge");
        helper.assertEquals(1, bench.cold().fill(new FluidStack(FluidRegistry.WATER, 1), true), "One external unit of water");
    }

    @GameTest(template = "regulator", timeoutTicks = 1600)
    public static void cryogenicHydrogenFreezesSeparateWaterCircuit(GameTestHelper helper) {
        var bench = place(helper, false);
        var hydrogenHeat = new AmbientTests.AmbientMeter(helper, bench.source()::getNetwork);
        var waterHeat = new AmbientTests.AmbientMeter(helper, bench.outlet()::getNetwork);
        long[] initial = new long[2];
        helper.startSequence().thenWaitUntil(() -> bench.ready(helper)).thenExecute(() -> {
            chargeCryogenicExchanger(helper, bench);
            initial[0] = hydrogenHeat.withoutAmbient(bench.source().getNetwork());
            initial[1] = waterHeat.withoutAmbient(bench.outlet().getNetwork());
            helper.assertEquals(0d, bench.outlet().getNetwork().getSolvedState().get().solidMassFraction(), "Water starts unfrozen");
        }).thenWaitUntil(() -> helper.assertTrue(bench.outlet().getNetwork().getSolvedState().get().solidMassFraction() > 0.02,
            "More than two percent ice on the water side")).thenExecute(() -> {
                var gas = bench.source().getNetwork();
                var water = bench.outlet().getNetwork();
                long heat = hydrogenHeat.withoutAmbient(gas) - initial[0];
                helper.assertTrue(heat > 0, "Hydrogen received heat");
                helper.assertEquals(heat, initial[1] - waterHeat.withoutAmbient(water), "Contact heat balances independently of ambient");
                helper.assertEquals("hydrogen", gas.getFluidName(), "Hydrogen stays on its side");
                helper.assertEquals("water", water.getFluidName(), "Water stays on its side");
                helper.assertEquals(250_000L, gas.getAmountQ(), "Hydrogen mass unchanged");
                helper.assertEquals(1_000_000L, water.getAmountQ(), "Water mass unchanged");
                helper.recordDiagnostic("Cryogenic contact EU=" + heat / 1e6 + ", water solid fraction="
                    + water.getSolvedState().get().solidMassFraction() + ", hydrogen K=" + gas.getTemperature());
            }).thenSucceed();
    }
}
