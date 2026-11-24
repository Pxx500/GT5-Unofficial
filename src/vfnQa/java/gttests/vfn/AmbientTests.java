package gttests.vfn;

import java.util.function.Supplier;

import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidRegistry;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;

import gregtech.api.enums.Mods;
import gregtech.api.metatileentity.implementations.integratedfluid.IntegratedFluidNetwork;
import gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidInjectorHatch;
import gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidPipe;

@GameTestHolder(value = Mods.ModIDs.GREG_TECH, templatePrefix = "vfn")
public class AmbientTests {

    static final class AmbientMeter {
        private long heatMicroEu;
        private IntegratedFluidNetwork observed;
        private long sampledTick = Long.MIN_VALUE;

        AmbientMeter(GameTestHelper helper, Supplier<IntegratedFluidNetwork> network) {
            helper.onEachTick("sample the ambient heat ledger", () -> {
                var chamber = network.get();
                if (chamber != observed) {
                    observed = chamber;
                    sampledTick = chamber == null ? Long.MIN_VALUE : chamber.getAmbientExchangeTick();
                } else if (chamber != null && sampledTick != chamber.getAmbientExchangeTick()) {
                    sampledTick = chamber.getAmbientExchangeTick();
                    heatMicroEu = Math.addExact(heatMicroEu, chamber.getAmbientHeatMicroEu());
                }
            });
        }

        long heatMicroEu() { return heatMicroEu; }

        long withoutAmbient(IntegratedFluidNetwork network) {
            return Math.subtractExact(network.getCanonicalState().totalEnergy().rawUnits(), heatMicroEu);
        }
    }

    record CoolingLine(MTEIntegratedFluidInjectorHatch inlet, MTEIntegratedFluidPipe pipe) {
        void ready(GameTestHelper helper) {
            helper.assertTrue(inlet.getNetwork() != null && inlet.getNetwork() == pipe.getNetwork(), "Pipe connected");
        }

        void charge(GameTestHelper helper) {
            helper.assertEquals(1, inlet.fill(ForgeDirection.EAST, FluidRegistry.getFluidStack("steam", 1), true),
                "One GT steam unit admitted");
            helper.assertEquals(1.0, pipe.getNetwork().getQuality(), "Charge starts entirely as vapor");
        }

        void condensed(GameTestHelper helper) {
            var network = pipe.getNetwork();
            helper.assertTrue(network.getQuality() < 0.99 && network.getQuality() > 0,
                "At least one percent of the steam must condense");
            helper.assertEquals(6250L, network.getAmountQ(), "Condensing preserves the sealed charge");
            helper.assertTrue(network.getTemperature() > 300 && network.getTemperature() < 350,
                "Cooling approaches ambient from above");
        }
    }

    static CoolingLine place(GameTestHelper helper) {
        var inlet = (MTEIntegratedFluidInjectorHatch) InjectorTests.placeHatch(helper, "cold", (short) 5773, ForgeDirection.WEST)
            .getMetaTileEntity();
        var pipe = (MTEIntegratedFluidPipe) InjectorTests.placeHatch(helper, "outlet", (short) 5770, ForgeDirection.EAST)
            .getMetaTileEntity();
        pipe.connect(ForgeDirection.EAST);
        return new CoolingLine(inlet, pipe);
    }

    @GameTest(template = "regulator", timeoutTicks = 1400)
    public static void warmAmbientThawsStationaryCharge(GameTestHelper helper) {
        var line = place(helper);
        var meter = new AmbientMeter(helper, line.pipe()::getNetwork);
        helper.startSequence()
            .thenWaitUntil(() -> line.ready(helper))
            .thenExecute(() -> {
                var network = line.pipe().getNetwork();
                network.replaceCanonicalState(gregtech.api.metatileentity.implementations.integratedfluid.state.IFNCanonicalState
                    .fromRaw("water", 1000L, -300_000_000L));
                helper.assertTrue(network.getSolvedState().orElseThrow().solidMassFraction() > 0.4, "Ice-rich starting charge");
            })
            .thenWaitUntil(() -> {
                var network = line.pipe().getNetwork();
                var phases = network.getSolvedState().orElseThrow();
                helper.assertTrue(phases.solidMassFraction() == 0 && phases.liquidMassFraction() > 0, "Ice melts in place");
                helper.assertTrue(network.getTemperature() >= 273.15 && network.getTemperature() <= 300, "No overshoot");
                helper.assertTrue(network.getAmbientHeatMicroEu() > 0, "Ambient supplies heat");
                helper.assertEquals(1000L, network.getAmountQ(), "No mass leaves the sealed pipe");
                helper.assertEquals(-300_000_000L, meter.withoutAmbient(network), "Thaw paid for entirely by ambient");
            })
            .thenSucceed();
    }

    @GameTest(template = "regulator", timeoutTicks = 1400)
    public static void steamCondensesInSealedPipe(GameTestHelper helper) {
        var line = place(helper);
        var meter = new AmbientMeter(helper, line.pipe()::getNetwork);
        long[] initial = new long[1];
        float[] startKelvin = new float[1];
        helper.startSequence()
            .thenWaitUntil(() -> line.ready(helper))
            .thenExecute(() -> {
                line.charge(helper);
                initial[0] = meter.withoutAmbient(line.pipe().getNetwork());
                startKelvin[0] = line.pipe().getNetwork().getTemperature();
            })
            .thenWaitUntil(() -> helper.assertTrue(line.pipe().getNetwork().getAmbientHeatMicroEu() < 0, "First cooling step"))
            .thenExecute(() -> {
                double firstHeat = line.pipe().getNetwork().getAmbientHeatMicroEu() / 1_000_000d;
                helper.assertTrue(Math.abs(firstHeat - (300 - startKelvin[0])) < 0.00005,
                    "One pipe exchanges 1 EU/K over 20 ticks");
            })
            .thenWaitUntil(() -> line.condensed(helper))
            .thenExecute(() -> helper.assertEquals(initial[0], meter.withoutAmbient(line.pipe().getNetwork()),
                "All chamber energy loss reaches ambient"))
            .thenExecute(() -> helper.assertTrue(line.pipe().getNetwork().getCanonicalState().totalEnergy().rawUnits()
                < initial[0], "Cooling debits chamber energy"))
            .thenSucceed();
    }
}
