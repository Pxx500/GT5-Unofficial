package gttests.vfn;

import java.math.BigDecimal;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraft.world.storage.MapStorage;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;

import gregtech.api.GregTechAPI;
import gregtech.api.enums.GTValues;
import gregtech.api.enums.Mods;
import gregtech.api.recipe.RecipeMaps;
import gregtech.api.util.GTModHandler;
import gregtech.api.util.GTUtility;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.integratedfluid.IFNWailaFormatter;
import gregtech.api.metatileentity.implementations.integratedfluid.IntegratedFluidNetwork;
import gregtech.api.metatileentity.implementations.integratedfluid.IntegratedFluidNetworkSavedData;
import gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidInjectorHatch;
import gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidInputHatch;
import gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidPipe;
import gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidPressureRegulator;
import gregtech.api.metatileentity.implementations.integratedfluid.state.IFNCanonicalState;
import gregtech.api.metatileentity.implementations.integratedfluid.thermodynamics.ClosedFluidChamber;
import gregtech.api.metatileentity.implementations.integratedfluid.thermodynamics.PureFluid;

@GameTestHolder(value = Mods.ModIDs.GREG_TECH, templatePrefix = "vfn")
public class InjectorTests {

    record RegulatorBench(MTEIntegratedFluidInjectorHatch injector, MTEIntegratedFluidPipe source,
        MTEIntegratedFluidPressureRegulator valve, MTEIntegratedFluidPipe outlet, MTEIntegratedFluidInjectorHatch cold) {

        void requireNetworks(GameTestHelper helper) {
            helper.assertTrue(injector.getNetwork() != null && injector.getNetwork() == source.getNetwork()
                && outlet.getNetwork() != null && outlet.getNetwork() == cold.getNetwork()
                && source.getNetwork() != outlet.getNetwork(), "Two separate chambers");
        }

        void charge(GameTestHelper helper) {
            helper.assertTrue(injector.fill(ForgeDirection.WEST, FluidRegistry.getFluidStack("steam", 6), true) > 0,
                "Steam source must charge");
            helper.assertEquals(1, cold.fill(ForgeDirection.EAST, new FluidStack(FluidRegistry.WATER, 1), true), "Feedwater");
        }

        void requireMeteredPressure(GameTestHelper helper) {
            float pressure = outlet.getNetwork().getPressure();
            helper.assertTrue(pressure >= 0.079f && pressure <= 0.08f, "Regulated pressure in bar: " + pressure);
        }
    }

    static RegulatorBench placeRegulator(GameTestHelper helper) {
        var injector = (MTEIntegratedFluidInjectorHatch) placeHatch(helper, "injector", (short) 5773, ForgeDirection.EAST)
            .getMetaTileEntity();
        var source = (MTEIntegratedFluidPipe) placeHatch(helper, "source", (short) 5770, ForgeDirection.EAST)
            .getMetaTileEntity();
        var valve = (MTEIntegratedFluidPressureRegulator) placeHatch(helper, "valve", (short) 5774, ForgeDirection.EAST)
            .getMetaTileEntity();
        var outlet = (MTEIntegratedFluidPipe) placeHatch(helper, "outlet", (short) 5770, ForgeDirection.WEST)
            .getMetaTileEntity();
        var cold = (MTEIntegratedFluidInjectorHatch) placeHatch(helper, "cold", (short) 5773, ForgeDirection.WEST)
            .getMetaTileEntity();
        outlet.connect(ForgeDirection.EAST);
        source.connect(ForgeDirection.WEST);
        source.connect(ForgeDirection.EAST);
        outlet.connect(ForgeDirection.WEST);
        valve.setSetpointPressureBar(0.08f);
        valve.setOvershootToleranceBar(0);
        return new RegulatorBench(injector, source, valve, outlet, cold);
    }

    @GameTest(template = "regulator", timeoutTicks = 240)
    public static void meteredSteam(GameTestHelper helper) {
        var bench = placeRegulator(helper);
        var source = bench.source();
        var outlet = bench.outlet();
        var valve = bench.valve();
        long[] initial = new long[2];
        long[] coldInventory = new long[2];
        var coldHeat = new AmbientTests.AmbientMeter(helper, outlet::getNetwork);
        var hotHeat = new AmbientTests.AmbientMeter(helper, source::getNetwork);
        helper.startSequence()
            .thenWaitUntil(() -> bench.requireNetworks(helper))
            .thenExecute(() -> {
                outlet.disconnect(ForgeDirection.WEST);
                bench.charge(helper);
                coldInventory[0] = outlet.getNetwork().getAmountQ();
                coldInventory[1] = coldHeat.withoutAmbient(outlet.getNetwork());
                initial[0] = source.getNetwork().getAmountQ() + outlet.getNetwork().getAmountQ();
                initial[1] = hotHeat.withoutAmbient(source.getNetwork())
                    + coldHeat.withoutAmbient(outlet.getNetwork());
                helper.assertTrue(source.getNetwork().getPressure() > 0.16f, "Enough head to reach the setpoint");
            })
            .thenIdle(25)
            .thenExecute(() -> {
                helper.assertEquals(coldInventory[0], outlet.getNetwork().getAmountQ(), "Disconnected outlet gains no mass");
                helper.assertEquals(coldInventory[1], coldHeat.withoutAmbient(outlet.getNetwork()),
                    "Disconnected outlet gains no energy");
                helper.assertTrue(outlet.connect(ForgeDirection.WEST) > 0, "Pipe connects to the regulator face");
            })
            .thenWaitUntil(() -> bench.requireMeteredPressure(helper))
            .thenExecute(() -> {
                var a = source.getNetwork();
                var b = outlet.getNetwork();
                helper.assertTrue(b.getPressure() <= 0.08f && a.getPressure() >= b.getPressure(), "No pressure overshoot");
                helper.assertEquals(initial[0], Math.addExact(a.getAmountQ(), b.getAmountQ()), "Mass ledger");
                helper.assertEquals(initial[1], Math.addExact(hotHeat.withoutAmbient(a), coldHeat.withoutAmbient(b)), "Energy ledger");
                valve.getBaseMetaTileEntity().setFrontFacing(ForgeDirection.WEST);
                initial[0] = b.getAmountQ();
                initial[1] = coldHeat.withoutAmbient(b);
                valve.setSetpointPressureBar(1);
            })
            .thenIdle(25)
            .thenExecute(() -> {
                helper.assertEquals(initial[0], outlet.getNetwork().getAmountQ(), "Rotated valve cannot drive reverse flow");
                helper.assertEquals(initial[1], coldHeat.withoutAmbient(outlet.getNetwork()),
                    "Rejected reverse flow leaves energy untouched");
            })
            .thenSucceed();
    }

    @GameTest(template = "regulator", timeoutTicks = 160)
    public static void detectorsReadVacuumAndChargedPipe(GameTestHelper helper) {
        var hatch = (MTEIntegratedFluidInputHatch) placeHatch(helper, "valve", (short) 5771, ForgeDirection.EAST).getMetaTileEntity();
        var tile = placeHatch(helper, "outlet", (short) 5770, ForgeDirection.WEST);
        var pipe = (MTEIntegratedFluidPipe) tile.getMetaTileEntity();
        pipe.connect(ForgeDirection.WEST);
        var p = helper.absolute("outlet");
        var world = helper.getWorld();
        var block = world.getBlock(p.x(), p.y(), p.z());
        var pressure = (gregtech.common.covers.CoverIFNPressureDetector) gregtech.api.covers.CoverRegistry.buildCover(
            gregtech.api.enums.ItemList.Cover_IFNPressureDetector.get(1), ForgeDirection.NORTH, tile);
        var temperature = (gregtech.common.covers.CoverIFNTemperatureDetector) gregtech.api.covers.CoverRegistry.buildCover(
            gregtech.api.enums.ItemList.Cover_IFNTemperatureDetector.get(1), ForgeDirection.UP, tile);
        pressure.setRange(0, 0);
        temperature.setRange(250, 350);
        tile.attachCover(pressure);
        tile.attachCover(temperature);
        java.util.function.IntSupplier pressureSignal = () -> block.isProvidingWeakPower(world, p.x(), p.y(), p.z(), ForgeDirection.SOUTH.ordinal());
        java.util.function.IntSupplier temperatureSignal = () -> block.isProvidingWeakPower(world, p.x(), p.y(), p.z(), ForgeDirection.DOWN.ordinal());
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(pipe.getNetwork() != null && pipe.getNetwork() == hatch.getNetwork(),
            "Covered pipe shares the hydrophore")).thenIdle(15).thenExecute(() -> {
            helper.assertEquals(15, pressureSignal.getAsInt(), "Vacuum is exactly zero bar");
            helper.assertEquals(0, temperatureSignal.getAsInt(), "An empty pipe has no material temperature");
            pipe.getNetwork().replaceCanonicalState(IFNCanonicalState.fromRaw("water", 11_000_000_000L, -2_200_000_000_000_000L));
        }).thenWaitUntil(() -> {
            helper.assertEquals(0, pressureSignal.getAsInt(), "Pressurized pipe no longer signals vacuum");
            helper.assertEquals(15, temperatureSignal.getAsInt(), "Water is inside the absolute temperature range");
        }).thenExecute(() -> {
            pressure.setDeltaSourceMode(true).setLinearMode(true).setRange(0, 3);
            temperature.setDeltaSourceMode(true).setLinearMode(true).setRange(0, 50);
        }).thenWaitUntil(() -> {
            helper.assertEquals(6, pressureSignal.getAsInt(), "Pressure above the 1 bar reference on the north face");
            helper.assertEquals(8, temperatureSignal.getAsInt(), "Temperature lift above ambient on the top face");
        }).thenExecute(() -> {
            pressure.setDeltaSourceMode(false).setLinearMode(false).setRange(0, 0);
            pipe.getNetwork().replaceCanonicalState(IFNCanonicalState.fromRaw("water", 1_000_000, Long.MAX_VALUE));
            helper.assertTrue(pipe.getNetwork().isFrozen(), "Out-of-range inventory retained under lock");
        }).thenIdle(10).thenExecute(() -> {
            helper.assertEquals(0, pressureSignal.getAsInt(), "Unknown pressure must not signal vacuum");
            helper.assertEquals(0, temperatureSignal.getAsInt(), "Unknown temperature gives no control signal");
        }).thenSucceed();
    }

    @GameTest(template = "regulator", timeoutTicks = 240)
    public static void twoBarLiquidRegulator(GameTestHelper helper) {
        var inlet = (MTEIntegratedFluidInputHatch) placeHatch(helper, "injector", (short) 5771, ForgeDirection.EAST).getMetaTileEntity();
        var source = (MTEIntegratedFluidPipe) placeHatch(helper, "source", (short) 5770, ForgeDirection.EAST).getMetaTileEntity();
        var valve = (MTEIntegratedFluidPressureRegulator) placeHatch(helper, "valve", (short) 5774, ForgeDirection.EAST).getMetaTileEntity();
        var outlet = (MTEIntegratedFluidPipe) placeHatch(helper, "outlet", (short) 5770, ForgeDirection.WEST).getMetaTileEntity();
        var receiver = (MTEIntegratedFluidInputHatch) placeHatch(helper, "cold", (short) 5771, ForgeDirection.WEST).getMetaTileEntity();
        for (var pipe : new MTEIntegratedFluidPipe[] { source, outlet }) {
            pipe.connect(ForgeDirection.WEST);
            pipe.connect(ForgeDirection.EAST);
        }
        var sourceHeat = new AmbientTests.AmbientMeter(helper, source::getNetwork);
        var receiverHeat = new AmbientTests.AmbientMeter(helper, outlet::getNetwork);
        long supply = 15_000_000_000L, charge = 10_700_000_000L;
        long[] stoppedMass = { 0 }, initialEnergy = { 0 };
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(source.getNetwork() != null && outlet.getNetwork() != null
            && source.getNetwork() != outlet.getNetwork() && inlet.getNetwork() == source.getNetwork()
            && receiver.getNetwork() == outlet.getNetwork(), "Two separate hydrophores"))
            .thenExecute(() -> {
                valve.setSetpointPressureCentibar(200);
                valve.setOvershootToleranceCentibar(5);
                valve.setMaxPacketAmountL(50);
                source.getNetwork().replaceCanonicalState(IFNCanonicalState.fromRaw("water", supply, -200_000 * supply));
                outlet.getNetwork().replaceCanonicalState(IFNCanonicalState.fromRaw("water", charge, -200_000 * charge));
                helper.assertTrue(source.getNetwork().getPressure() > 4 && outlet.getNetwork().getPressure() < 2,
                    "Compressed supply can drive the receiver through the setpoint");
                initialEnergy[0] = sourceHeat.withoutAmbient(source.getNetwork()) + receiverHeat.withoutAmbient(outlet.getNetwork());
            }).thenWaitUntil(() -> helper.assertTrue(outlet.getNetwork().getPressure() >= 2, "Receiver reached 2 bar"))
            .thenExecute(() -> {
                stoppedMass[0] = outlet.getNetwork().getAmountQ();
                helper.assertTrue(stoppedMass[0] > charge && outlet.getNetwork().getPressurePa() <= 205000.01, "50 L strokes respect tolerance");
                helper.recordDiagnostic("Closed at " + outlet.getNetwork().getPressurePa() + " Pa after " + (stoppedMass[0] - charge) + " microkg");
            }).thenIdle(30).thenExecute(() -> {
                helper.assertTrue(source.getNetwork().getPressure() > 4, "Source still has head");
                helper.assertEquals(stoppedMass[0], outlet.getNetwork().getAmountQ(), "Closed valve does not keep feeding");
                valve.setMaxPacketAmountL(5000);
                valve.setSetpointPressureCentibar(210);
            }).thenWaitUntil(() -> helper.assertTrue(outlet.getNetwork().getAmountQ() > stoppedMass[0], "Higher setpoint reopens the valve"))
            .thenExecute(() -> {
                var a = source.getNetwork();
                var b = outlet.getNetwork();
                helper.assertTrue(b.getPressurePa() > 214999 && b.getPressurePa() <= 215000.01,
                    "Oversized stroke is trimmed to the pressure ceiling: " + b.getPressurePa() + " Pa");
                helper.assertEquals(supply + charge, a.getAmountQ() + b.getAmountQ(), "Metered liquid mass");
                helper.assertEquals(initialEnergy[0], sourceHeat.withoutAmbient(a) + receiverHeat.withoutAmbient(b), "Fluid and spring energy");
                helper.recordDiagnostic("Large stroke ended at " + b.getPressurePa() + " Pa");
            }).thenSucceed();
    }

    @GameTest(template = "regulator", timeoutTicks = 240)
    public static void lockedInletResumesFlowWithoutRebuilding(GameTestHelper helper) {
        var bench = placeRegulator(helper);
        var hot = new AmbientTests.AmbientMeter(helper, bench.source()::getNetwork);
        var cold = new AmbientTests.AmbientMeter(helper, bench.outlet()::getNetwork);
        long[] start = new long[4];
        helper.startSequence().thenWaitUntil(() -> bench.requireNetworks(helper)).thenExecute(() -> {
            helper.assertEquals(3, bench.injector().fill(ForgeDirection.WEST, FluidRegistry.getFluidStack("steam", 3), true),
                "Charge the inlet below the pressure cutoff");
            helper.assertEquals(1, bench.cold().fill(ForgeDirection.EAST, new FluidStack(FluidRegistry.WATER, 1), true), "Feedwater");
            var a = bench.source().getNetwork();
            var b = bench.outlet().getNetwork();
            var steam = FluidRegistry.getFluidStack("steam", 1);
            helper.assertEquals(1, bench.injector().fill(ForgeDirection.WEST, steam, false), "Pressure admits another unit before locking");
            start[0] = a.getAmountQ();
            start[1] = hot.withoutAmbient(a);
            start[2] = b.getAmountQ();
            start[3] = cold.withoutAmbient(b);
            a.freeze("isolated for maintenance");
            helper.assertTrue(a.isFrozen(), "Source reports the operational lock");
            helper.assertEquals(0, bench.injector().fill(ForgeDirection.WEST, steam, true), "Locked injector refuses the same offer");
        }).thenIdle(30).thenExecute(() -> {
            var a = bench.source().getNetwork();
            var b = bench.outlet().getNetwork();
            helper.assertEquals(start[0], a.getAmountQ(), "No debit while locked");
            helper.assertEquals(start[1], hot.withoutAmbient(a), "No flow energy left the inlet");
            helper.assertEquals(start[2], b.getAmountQ(), "Regulator did not deliver while locked");
            helper.assertEquals(start[3], cold.withoutAmbient(b), "No flow energy reached the receiver");
            a.clearFrozen();
            helper.assertTrue(a.getNetworkStatus() == gregtech.api.metatileentity.implementations.integratedfluid.state.IFNNetworkStatus.NORMAL,
                "Source reports normal after release");
            helper.assertEquals(1, bench.injector().fill(ForgeDirection.WEST, FluidRegistry.getFluidStack("steam", 1), true),
                "Same injector resumes without disconnecting");
            helper.assertTrue(a.getPressurePa() > b.getPressurePa() && b.getPressure() < bench.valve().getSetpointPressureBar(),
                "Unlocked inlet can drive the valve");
        }).thenWaitUntil(() -> helper.assertTrue(bench.outlet().getNetwork().getAmountQ() > start[2], "Actual regulator output resumed"))
            .thenExecute(() -> {
                var a = bench.source().getNetwork();
                var b = bench.outlet().getNetwork();
                helper.recordDiagnostic("After restart: inlet Pa=" + a.getPressurePa() + ", outlet Pa=" + b.getPressurePa()
                    + ", transferred microkg=" + (b.getAmountQ() - start[2]));
                helper.assertEquals(start[0] + start[2] + 6250, a.getAmountQ() + b.getAmountQ(), "Exactly one external steam unit added");
                helper.assertEquals(start[1] + start[3] + 2_257_000L * 6250, hot.withoutAmbient(a) + cold.withoutAmbient(b),
                    "Only the new steam unit added flow energy");
            }).thenSucceed();
    }

    @GameTest(template = "regulator", timeoutTicks = 160)
    public static void overpressureRupturesOnlyTheChargedPipe(GameTestHelper helper) {
        var failingPort = placeHatch(helper, "injector", (short) 5773, ForgeDirection.EAST);
        var pipe = (MTEIntegratedFluidPipe) placeHatch(helper, "source", (short) 5770, ForgeDirection.WEST).getMetaTileEntity();
        var other = (MTEIntegratedFluidPipe) placeHatch(helper, "outlet", (short) 5770, ForgeDirection.EAST).getMetaTileEntity();
        placeHatch(helper, "cold", (short) 5773, ForgeDirection.WEST);
        var broken = helper.absolute("source");
        var neighbor = helper.absolute("outlet");
        pipe.connect(ForgeDirection.WEST);
        other.connect(ForgeDirection.EAST);
        helper.startSequence().thenIdle(20).thenExecute(() -> {
            var high = pipe.getNetwork();
            var safe = other.getNetwork();
            helper.assertTrue(high != null && safe != null && high != safe, "Two independent 100 L pipe inventories");
            // At 475 K, 0.55 kg of vapor in 100 L exceeds the 11 bar rupture threshold. 0.4 kg does not
            high.replaceCanonicalState(IFNCanonicalState.fromRaw("water", 550_000, 1_233_378_300_000L));
            safe.replaceCanonicalState(IFNCanonicalState.fromRaw("water", 400_000, 897_002_400_000L));
            helper.assertTrue(high.getPressure() > 11 && safe.getPressure() < 10, "Mass and energy establish both pressure readings");
        }).thenWaitUntil(() -> helper.assertTrue(helper.getWorld().isAirBlock(broken.x(), broken.y(), broken.z()), "Overpressurized pipe ruptured"))
            .thenIdle(20).thenExecute(() -> {
                helper.assertTrue(!helper.getWorld().isAirBlock(neighbor.x(), neighbor.y(), neighbor.z()), "Independent pipe is intact");
                var survivor = other.getNetwork();
                helper.assertEquals(400_000L, survivor.getAmountQ(), "Neighbor kept all its steam");
                var inlet = ((gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidInjectorHatch)
                    failingPort.getMetaTileEntity()).getNetwork();
                helper.assertTrue(inlet == null || inlet.getAmountQ() == 0, "No ruptured charge survives on the inlet");
            }).thenSucceed();
    }

    @GameTest(template = "regulator", timeoutTicks = 160)
    public static void breakingEndPipeVentsSteam(GameTestHelper helper) {
        breakChargedPipe(helper, false);
    }

    @GameTest(template = "regulator", timeoutTicks = 240)
    public static void supercriticalChargeCannotEscapeAnUnderratedPipeAsForgeWater(GameTestHelper helper) {
        var port = placeHatch(helper, "injector", (short) 32032, ForgeDirection.EAST);
        var pipe = (MTEIntegratedFluidPipe) placeHatch(helper, "source", (short) 5770, ForgeDirection.WEST).getMetaTileEntity();
        var pos = helper.absolute("source");
        pipe.connect(ForgeDirection.WEST);
        var extractor = (gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidExtractor) port.getMetaTileEntity();
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(pipe.getNetwork() != null && extractor.getNetwork() == pipe.getNetwork(),
            "Extractor attached to the charged pipe"))
            .thenExecute(() -> {
                var chamber = pipe.getNetwork();
                chamber.replaceCanonicalState(IFNCanonicalState.fromRaw("water", 21_301_564L, 37_551_663_742_696L));
                helper.assertTrue(Math.abs(chamber.getTemperature() - 650) < 0.01 && Math.abs(chamber.getPressure() - 221) < 0.01,
                    "100 L pipe recovered the prepared dense charge");
                var display = new net.minecraft.nbt.NBTTagCompound();
                gregtech.api.metatileentity.implementations.integratedfluid.IFNWailaFormatter.writeNetworkStatus(chamber, display);
                helper.assertEquals("SUPERCRITICAL", display.getString("phase"), "Single fluid, no separable water or vapor");
                helper.assertTrue(port.drain(ForgeDirection.WEST, 1, true) == null, "Forge cannot drain the dense charge");
                helper.assertEquals(21_301_564L, chamber.getAmountQ(), "Rejected export retained all mass");
            }).thenWaitUntil(() -> helper.assertTrue(helper.getWorld().isAirBlock(pos.x(), pos.y(), pos.z()),
                "Ordinary pipe ruptured under supercritical pressure")).thenSucceed();
    }

    @GameTest(template = "regulator", timeoutTicks = 160)
    public static void breakingMiddlePipeVentsSteam(GameTestHelper helper) {
        breakChargedPipe(helper, true);
    }

    @GameTest(template = "regulator", timeoutTicks = 160)
    public static void lossySteamExport(GameTestHelper helper) {
        var extractor = placeHatch(helper, "injector", (short) 32032, ForgeDirection.EAST);
        var source = (MTEIntegratedFluidPipe) placeHatch(helper, "source", (short) 5770, ForgeDirection.WEST).getMetaTileEntity();
        var receiver = (MTEIntegratedFluidPipe) placeHatch(helper, "outlet", (short) 5770, ForgeDirection.EAST).getMetaTileEntity();
        var injector = placeHatch(helper, "cold", (short) 5773, ForgeDirection.WEST);
        source.connect(ForgeDirection.WEST);
        receiver.connect(ForgeDirection.EAST);
        helper.startSequence().thenIdle(20).thenExecute(() -> {
            var hot = source.getNetwork();
            var cold = receiver.getNetwork();
            var output = ForgeDirection.WEST;
            var steam = FluidRegistry.getFluidStack("steam", 2);
            hot.replaceCanonicalState(IFNCanonicalState.fromRaw("water", 100_000, 220_379_350_000L));
            long before = hot.getCanonicalState().totalEnergy().rawUnits();
            var preview = extractor.drain(output, steam, false);
            helper.assertTrue(preview != null && preview.isFluidStackIdentical(steam), "Preview two ordinary steam units");
            helper.assertEquals(100_000L, hot.getAmountQ(), "Preview preserves source mass");
            var tagged = steam.copy();
            tagged.tag = new NBTTagCompound();
            helper.assertTrue(extractor.drain(output, tagged, true) == null
                && extractor.drain(output, new FluidStack(FluidRegistry.WATER, 1), true) == null, "Reject tags and wrong phase");
            helper.assertEquals(before, hot.getCanonicalState().totalEnergy().rawUnits(), "Preview and refusals preserve energy");
            tagged.tag.setString("externalState", "not a nominal steam offer");
            helper.assertEquals(0, injector.fill(ForgeDirection.EAST, tagged, false), "Do not preview unrecognized external state");
            helper.assertEquals(0, injector.fill(ForgeDirection.EAST, tagged, true), "Do not silently strip steam metadata");
            helper.assertEquals(0L, cold.getAmountQ(), "Rejected metadata did not enter VFN");
            helper.assertEquals("not a nominal steam offer", tagged.tag.getString("externalState"), "Refusal preserves the source stack");
            var packet = extractor.drain(output, 2, true);
            helper.assertTrue(packet != null && packet.isFluidStackIdentical(steam), "Untyped commit matches typed preview");
            helper.assertEquals(2, injector.fill(ForgeDirection.EAST, packet, true), "Second network accepts the packet");
            helper.assertEquals(12_500L, cold.getAmountQ(), "Two steam units carry 0.0125 kg");
            helper.assertEquals(100_000L, hot.getAmountQ() + cold.getAmountQ(), "Round trip conserves mass");
            helper.assertTrue(hot.getCanonicalState().totalEnergy().rawUnits() + cold.getCanonicalState().totalEnergy().rawUnits()
                < before, "Superheat is discarded at the boundary");
            helper.assertTrue(extractor.drain(output, Integer.MAX_VALUE, true) != null && hot.getPressure() >= 1,
                "A large request cannot draw below one bar");
            helper.assertTrue(extractor.drain(output, 1, true) == null, "The last unit requires compression");
        }).thenSucceed();
    }

    @GameTest(template = "regulator", timeoutTicks = 160)
    public static void hydrophoreFeedsGtTank(GameTestHelper helper) {
        var extractor = placeHatch(helper, "injector", (short) 32032, ForgeDirection.EAST);
        var pipe = (MTEIntegratedFluidPipe) placeHatch(helper, "source", (short) 5770, ForgeDirection.WEST).getMetaTileEntity();
        var storage = (MTEIntegratedFluidInputHatch) placeHatch(helper, "valve", (short) 5771, ForgeDirection.WEST).getMetaTileEntity();
        var tank = placeHatch(helper, "cold", (short) 130, ForgeDirection.WEST);
        pipe.connect(ForgeDirection.WEST);
        pipe.connect(ForgeDirection.EAST);
        helper.startSequence().thenIdle(20).thenExecute(() -> {
            var network = pipe.getNetwork();
            helper.assertTrue(network != null && network == storage.getNetwork(), "Hydrophore connected");
            long mass = 11_000_000_000L;
            var water = new FluidStack(FluidRegistry.WATER, 100);
            network.replaceCanonicalState(IFNCanonicalState.fromRaw("water", mass, -350_000L * mass));
            helper.assertTrue(network.getPressurePa() > 110_000 && network.getTemperature() < 300, "Cold water above the extractor pressure floor");
            long coldEnergy = network.getCanonicalState().totalEnergy().rawUnits();
            float coldPressure = network.getPressure();
            helper.assertTrue(extractor.drain(ForgeDirection.WEST, water, false) == null, "No export offered below nominal enthalpy");
            helper.assertTrue(extractor.drain(ForgeDirection.WEST, water, true) == null, "Pressure cannot replace missing heat");
            helper.assertEquals(coldEnergy, network.getCanonicalState().totalEnergy().rawUnits(), "Refusal neither heats nor cools the water");
            helper.assertEquals(coldPressure, network.getPressure(), "Refusal does not unload the spring");
            helper.assertEquals(mass, network.getAmountQ(), "Refused water remains in the pipe");
            network.replaceCanonicalState(IFNCanonicalState.fromRaw("water", mass, -100_000L * mass));
            float before = network.getPressure();
            long warmEnergy = network.getCanonicalState().totalEnergy().rawUnits();
            var preview = extractor.drain(ForgeDirection.WEST, water, false);
            helper.assertTrue(preview != null && preview.isFluidStackIdentical(water), "Water preview");
            helper.assertEquals(mass, network.getAmountQ(), "Preview does not take water");
            helper.assertEquals(warmEnergy, network.getCanonicalState().totalEnergy().rawUnits(), "Preview does not consume energy");
            helper.assertEquals(100, tank.fill(ForgeDirection.UNKNOWN, preview, false), "GT tank has room");
            var exported = extractor.drain(ForgeDirection.WEST, water, true);
            helper.assertEquals(100, tank.fill(ForgeDirection.UNKNOWN, exported, true), "GT tank accepts the export");
            helper.assertTrue(tank.drain(ForgeDirection.UNKNOWN, water, false).isFluidStackIdentical(water), "Ordinary water in GT storage");
            helper.assertEquals(mass - 100_000_000L, network.getAmountQ(), "100 GT water units cost 100 kg");
            helper.assertTrue(network.getPressure() < before && network.getPressurePa() >= 110_000, "Hydrophore retains the exterior pressure margin");
        }).thenSucceed();
    }

    @GameTest(template = "regulator", timeoutTicks = 160)
    public static void coolantPortsFeedGtTanks(GameTestHelper helper) {
        var origin = helper.absolute("source");
        String[] fluids = { "ic2coolant", "supercoolant" };
        var extractors = new IGregTechTileEntity[2];
        var tanks = new IGregTechTileEntity[2];
        var receivers = new MTEIntegratedFluidInputHatch[2];
        var inlets = new IGregTechTileEntity[2];
        for (int i = 0; i < fluids.length; i++) {
            int z = origin.z() + i * 2;
            var world = helper.getWorld();
            extractors[i] = placeAt(world, origin.x(), origin.y(), z, (short) 32032, ForgeDirection.EAST);
            receivers[i] = (MTEIntegratedFluidInputHatch) placeAt(world, origin.x() + 1, origin.y(), z, (short) 5771,
                ForgeDirection.WEST).getMetaTileEntity();
            tanks[i] = placeAt(world, origin.x() - 1, origin.y(), z, (short) 130, ForgeDirection.EAST);
            inlets[i] = placeAt(world, origin.x() - 2, origin.y(), z, (short) 5773, ForgeDirection.WEST);
            placeAt(world, origin.x() - 3, origin.y(), z, (short) 5771, ForgeDirection.EAST);
        }
        helper.startSequence().thenIdle(20).thenExecute(() -> {
            for (int i = 0; i < fluids.length; i++) {
                String id = fluids[i];
                var network = receivers[i].getNetwork();
                long mass = 11_000_000_000L;
                // Warm liquid and a charged hydrophore provide the heat and pressure needed to export
                long energyPerKg = i == 0 ? (315 - 400) * 4000L : (315 - 450) * 6000L;
                network.replaceCanonicalState(IFNCanonicalState.fromRaw(id, mass, mass * energyPerKg));
                var offer = FluidRegistry.getFluidStack(id, 100);
                helper.assertNotNull(offer, "Pack coolant " + id);
                var out = extractors[i].drain(ForgeDirection.WEST, offer, true);
                helper.assertTrue(out != null && offer.isFluidStackIdentical(out), "Unchanged coolant product: " + id);
                helper.assertEquals(100, tanks[i].fill(ForgeDirection.UNKNOWN, out, true), "Coolant reached GT storage");
                helper.assertEquals(mass - 100_000_000L, network.getAmountQ(), "Exactly 100 kg left the source");
                var fromTank = tanks[i].drain(ForgeDirection.UNKNOWN, offer, true);
                helper.assertEquals(100, inlets[i].fill(ForgeDirection.EAST, fromTank, true), "Tank product re-enters the other circuit");
                var destination = ((MTEIntegratedFluidInjectorHatch) inlets[i].getMetaTileEntity()).getNetwork();
                helper.assertTrue(destination != network, "The GT tank separates the circuits");
                helper.assertEquals(id, destination.getFluidName(), "Coolant identity survives the GT boundary");
                helper.assertEquals(100_000_000L, destination.getAmountQ(), "The same mass arrived");
                long ambientEnthalpy = i == 0 ? (300 - 400) * 4000L : (300 - 450) * 6000L;
                helper.assertEquals(100_000_000L * ambientEnthalpy, destination.getCanonicalState().totalEnergy().rawUnits(),
                    "Reimported coolant carries local enthalpy, not the registry temperature");
            }
        }).thenSucceed();
    }

    @GameTest(template = "regulator", timeoutTicks = 160)
    public static void electrolysisAndRecombinationBalance(GameTestHelper helper) {
        var p = helper.absolute("source");
        String[] fluids = { "water", "oxygen", "hydrogen" };
        var ports = new IGregTechTileEntity[fluids.length];
        for (int i = 0; i < ports.length; i++) {
            int z = p.z() - 2 * i;
            ports[i] = placeAt(helper.getWorld(), p.x(), p.y(), z, (short) 5773, ForgeDirection.EAST);
            placeAt(helper.getWorld(), p.x() + 1, p.y(), z, (short) 5771, ForgeDirection.WEST);
        }
        helper.startSequence().thenIdle(20).thenExecute(() -> {
            long[] mass = new long[3];
            for (int i = 0; i < ports.length; i++) {
                var stack = FluidRegistry.getFluidStack(fluids[i], 1);
                helper.assertEquals(1, ports[i].fill(ForgeDirection.WEST, stack, true), "Meter one unit of " + fluids[i]);
                var network = ((MTEIntegratedFluidInjectorHatch) ports[i].getMetaTileEntity()).getNetwork();
                helper.assertEquals(fluids[i], network.getFluidName(), "Correct retained substance");
                mass[i] = network.getAmountQ();
            }
            helper.assertEquals(0, ports[2].fill(ForgeDirection.WEST, FluidRegistry.getFluidStack("oxygen", 1), true),
                "An occupied hydrogen circuit rejects oxygen");
            var hydrogen = FluidRegistry.getFluidStack("hydrogen", 1);
            var oxygen = FluidRegistry.getFluidStack("oxygen", 1);
            var electrolysis = RecipeMaps.electrolyzerRecipes.getAllRecipes().stream()
                .filter(r -> r.mFluidInputs.length == 1 && r.mFluidInputs[0].getFluid() == FluidRegistry.WATER
                    && r.mFluidOutputs.length == 1 && hydrogen.isFluidEqual(r.mFluidOutputs[0])
                    && r.mOutputs.length == 1 && oxygen.isFluidEqual(GTUtility.getFluidForFilledItem(r.mOutputs[0], true)))
                .findFirst().orElseThrow(() -> new AssertionError("Loaded GT water electrolysis recipe"));
            var cell = electrolysis.mOutputs[0];
            long cellUnits = (long) cell.stackSize * GTUtility.getFluidForFilledItem(cell, true).amount;
            helper.assertEquals(electrolysis.mFluidInputs[0].amount * mass[0],
                electrolysis.mFluidOutputs[0].amount * mass[2] + cellUnits * mass[1], "Electrolysis conserves metered mass");
            var synthesis = RecipeMaps.multiblockChemicalReactorRecipes.getAllRecipes().stream()
                .filter(r -> r.mFluidInputs.length == 2 && hydrogen.isFluidEqual(r.mFluidInputs[0]) && oxygen.isFluidEqual(r.mFluidInputs[1])
                    && r.mFluidOutputs.length == 1 && GTModHandler.getDistilledWater(1).isFluidEqual(r.mFluidOutputs[0]))
                .findFirst().orElseThrow(() -> new AssertionError("Loaded GT water synthesis recipe"));
            helper.assertEquals(synthesis.mFluidInputs[0].amount * mass[2] + synthesis.mFluidInputs[1].amount * mass[1],
                synthesis.mFluidOutputs[0].amount * mass[0], "Distilled water output has the water-equivalent mass");
            helper.recordDiagnostic("Measured microkg per external unit: water=" + mass[0] + ", oxygen=" + mass[1] + ", hydrogen=" + mass[2]);
        }).thenSucceed();
    }

    @GameTest(template = "regulator", timeoutTicks = 160)
    public static void compressedOxygenFeedsGtTank(GameTestHelper helper) {
        compressedGasFeedsTank(helper, "oxygen", 24_000_000L, 365_000L, 888_888L);
    }

    @GameTest(template = "regulator", timeoutTicks = 160)
    public static void compressedHydrogenFeedsGtTank(GameTestHelper helper) {
        compressedGasFeedsTank(helper, "hydrogen", 1_600_000L, 3_000_000L, 55_556L);
    }

    @GameTest(template = "regulator", timeoutTicks = 160)
    public static void compressedNitrogenFeedsGtTank(GameTestHelper helper) {
        compressedGasFeedsTank(helper, "nitrogen", 24_000_000L, 385_000L, 777_778L);
    }

    @GameTest(template = "regulator", timeoutTicks = 160)
    public static void compressedHeliumFeedsGtTank(GameTestHelper helper) {
        compressedGasFeedsTank(helper, "helium", 2_500_000L, 1_100_000L, 111_111L);
    }

    @GameTest(template = "regulator", timeoutTicks = 240)
    public static void nitrogenRetainsIdentityAcrossValveAndDisk(GameTestHelper helper) {
        regulatedGasRetainsIdentity(helper, "nitrogen", 777_778L);
    }

    @GameTest(template = "regulator", timeoutTicks = 240)
    public static void heliumRetainsIdentityAcrossValveAndDisk(GameTestHelper helper) {
        regulatedGasRetainsIdentity(helper, "helium", 111_111L);
    }

    private static void regulatedGasRetainsIdentity(GameTestHelper helper, String gas, long unitMass) {
        var bench = placeRegulator(helper);
        var source = helper.absolute("source");
        placeAt(helper.getWorld(), source.x(), source.y(), source.z() + 1, (short) 5771, ForgeDirection.NORTH);
        bench.source().connect(ForgeDirection.SOUTH);
        var sourceHeat = new AmbientTests.AmbientMeter(helper, bench.source()::getNetwork);
        var outletHeat = new AmbientTests.AmbientMeter(helper, bench.outlet()::getNetwork);
        long[] initial = new long[1];
        helper.startSequence().thenWaitUntil(() -> bench.requireNetworks(helper)).thenExecute(() -> {
            helper.assertEquals(3, bench.injector().fill(ForgeDirection.WEST, FluidRegistry.getFluidStack(gas, 3), true),
                "GT gas fills the inlet: " + gas);
            initial[0] = sourceHeat.withoutAmbient(bench.source().getNetwork()) + outletHeat.withoutAmbient(bench.outlet().getNetwork());
        }).thenWaitUntil(() -> helper.assertTrue(bench.outlet().getNetwork().getAmountQ() > 0, "Gas crossed the physical valve"))
            .thenIdle(25).thenExecute(() -> {
            var a = bench.source().getNetwork();
            var b = bench.outlet().getNetwork();
            helper.assertEquals(gas, b.getFluidName(), "Downstream receives the same material");
            helper.assertTrue(b.getPressure() > 0 && b.getPressure() <= bench.valve().getSetpointPressureBar()
                && a.getPressure() > b.getPressure(), "Valve limits downstream pressure with head still available");
            helper.assertEquals(3 * unitMass, a.getAmountQ() + b.getAmountQ(), "No mass crosses the external boundary twice");
            helper.assertEquals(initial[0], sourceHeat.withoutAmbient(a) + outletHeat.withoutAmbient(b), "Valve conserves transported energy");
            assertDiskInventory(helper, helper.getWorld(), b);
            helper.recordDiagnostic("Retained " + gas + " microkg=" + b.getAmountQ() + ", outlet Pa=" + b.getPressurePa());
        }).thenSucceed();
    }

    private static void compressedGasFeedsTank(GameTestHelper helper, String name, long mass, long specificEnergy, long unitMass) {
        var extractor = placeHatch(helper, "injector", (short) 32032, ForgeDirection.EAST);
        var storage = (MTEIntegratedFluidInputHatch) placeHatch(helper, "source", (short) 5771, ForgeDirection.WEST).getMetaTileEntity();
        var tank = placeHatch(helper, "cold", (short) 130, ForgeDirection.NORTH);
        helper.startSequence().thenIdle(20).thenExecute(() -> {
            var network = storage.getNetwork();
            network.replaceCanonicalState(IFNCanonicalState.fromRaw(name, mass, mass * specificEnergy));
            var side = ForgeDirection.WEST;
            var gas = FluidRegistry.getFluidStack(name, 2);
            helper.assertTrue(network.getPressurePa() > 110_000 && network.getTemperature() > 295, "Warm compressed " + name);
            helper.assertTrue(extractor.drain(side, FluidRegistry.getFluidStack("steam", 2), true) == null, "No gas exported as steam");
            var preview = extractor.drain(side, gas, false);
            helper.assertTrue(preview != null && preview.isFluidStackIdentical(gas), "Two GT gas units offered");
            helper.assertEquals(mass, network.getAmountQ(), "Preview and wrong material do not consume gas");
            var packet = extractor.drain(side, 2, true);
            helper.assertTrue(packet != null && packet.isFluidStackIdentical(gas), "Untyped drain returns the offered gas");
            helper.assertEquals(2, tank.fill(ForgeDirection.UNKNOWN, packet, true), "Super Tank receives the gas");
            helper.assertTrue(tank.drain(ForgeDirection.UNKNOWN, 2, false).isFluidStackIdentical(gas), "GT tank holds " + name);
            helper.assertEquals(mass - 2 * unitMass, network.getAmountQ(), "External units debit their calibrated mass");
            helper.assertTrue(network.getCanonicalState().totalEnergy().rawUnits() < mass * specificEnergy, "Exhaust carries energy out");
        }).thenSucceed();
    }

    @GameTest(template = "regulator", timeoutTicks = 160)
    public static void unknownChargeCannotBeExportedAsSteam(GameTestHelper helper) {
        var extractor = placeHatch(helper, "injector", (short) 32032, ForgeDirection.EAST);
        var supply = (MTEIntegratedFluidInputHatch) placeHatch(helper, "source", (short) 5771, ForgeDirection.WEST).getMetaTileEntity();
        var mass = 7_000_000L;
        var energy = 15_426_554_500_000L;
        helper.startSequence().thenIdle(20).thenExecute(() -> {
            var network = supply.getNetwork();
            network.replaceCanonicalState(IFNCanonicalState.fromRaw("water", mass, energy));
            var preview = extractor.drain(ForgeDirection.WEST, 1, false);
            helper.assertTrue(preview != null && preview.getFluid() == FluidRegistry.getFluid("steam"), "Water can discharge steam here");
            network.replaceCanonicalState(IFNCanonicalState.fromRaw("unknown_test_substance", mass, energy));
            network.clearFrozen();
        }).thenIdle(20).thenExecute(() -> {
            var network = supply.getNetwork();
            helper.assertTrue(network.isFrozen() && network.getSolvedState().isEmpty(), "Unknown charge has no water state");
            helper.assertTrue(extractor.drain(ForgeDirection.WEST, 1, true) == null, "No steam export");
            helper.assertEquals("unknown_test_substance", network.getFluidName(), "Substance unchanged");
            helper.assertEquals(mass, network.getAmountQ(), "Mass unchanged");
            helper.assertEquals(energy, network.getCanonicalState().totalEnergy().rawUnits(), "Energy unchanged after ambient ticks");
        }).thenSucceed();
    }

    @GameTest(template = "regulator", timeoutTicks = 160)
    public static void externalTankCannotBypassPressureMargins(GameTestHelper helper) {
        var extractor = placeHatch(helper, "injector", (short) 32032, ForgeDirection.EAST);
        var supply = (MTEIntegratedFluidInputHatch) placeHatch(helper, "source", (short) 5771, ForgeDirection.WEST).getMetaTileEntity();
        var receiver = (MTEIntegratedFluidInputHatch) placeHatch(helper, "outlet", (short) 5771, ForgeDirection.EAST).getMetaTileEntity();
        var injector = placeHatch(helper, "cold", (short) 5773, ForgeDirection.WEST);
        helper.startSequence().thenIdle(20).thenExecute(() -> {
            var hot = supply.getNetwork();
            var empty = receiver.getNetwork();
            hot.replaceCanonicalState(IFNCanonicalState.fromRaw("water", 7_000_000, 15_426_554_500_000L));
            var take = extractor.drain(ForgeDirection.WEST, Integer.MAX_VALUE, false);
            helper.assertTrue(take != null && take.amount > 1, "Hot steam available beyond the outlet margin");
            var packet = extractor.drain(ForgeDirection.WEST, Integer.MAX_VALUE, true);
            helper.assertTrue(packet.isFluidStackIdentical(take), "Export preview and commit agree");
            helper.assertTrue(hot.getPressure() >= 1.1f && hot.getPressure() < 1.105f,
                "Large export ends at the 1.1 bar floor: " + hot.getPressure());
            helper.assertTrue(extractor.drain(ForgeDirection.WEST, 1, true) == null, "Small requests cannot bypass the floor");
            var steam = FluidRegistry.getFluidStack("steam", Integer.MAX_VALUE);
            int canFill = injector.fill(ForgeDirection.EAST, steam, false);
            helper.assertTrue(canFill > 0 && canFill == injector.fill(ForgeDirection.EAST, steam, true), "Same finite intake on commit");
            helper.assertTrue(empty.getPressure() > 0.895f && empty.getPressure() <= 0.9f,
                "Large import ends at the 0.9 bar ceiling: " + empty.getPressure());
            helper.assertEquals(0, injector.fill(ForgeDirection.EAST, FluidRegistry.getFluidStack("steam", 1), true),
                "Small requests cannot bypass the ceiling");
        }).thenSucceed();
    }

    record PipeRun(MTEIntegratedFluidInjectorHatch inlet, MTEIntegratedFluidPipe last) {
        void requireConnected(GameTestHelper helper) {
            helper.assertTrue(inlet.getNetwork() != null && inlet.getNetwork().getMemberCount() == 4
                && inlet.getNetwork() == last.getNetwork(), "Three connected pipes");
        }

        void fillSteam(GameTestHelper helper) {
            helper.assertEquals(12, inlet.fill(ForgeDirection.WEST, FluidRegistry.getFluidStack("steam", 12), true),
                "Charge three equal volumes");
        }
    }

    static PipeRun placePipeRun(GameTestHelper helper) {
        var inlet = (MTEIntegratedFluidInjectorHatch) placeHatch(helper, "injector", (short) 5773, ForgeDirection.EAST)
            .getMetaTileEntity();
        var first = (MTEIntegratedFluidPipe) placeHatch(helper, "source", (short) 5770, ForgeDirection.EAST)
            .getMetaTileEntity();
        var middle = (MTEIntegratedFluidPipe) placeHatch(helper, "valve", (short) 5770, ForgeDirection.EAST)
            .getMetaTileEntity();
        var last = (MTEIntegratedFluidPipe) placeHatch(helper, "outlet", (short) 5770, ForgeDirection.WEST)
            .getMetaTileEntity();
        first.connect(ForgeDirection.WEST);
        first.connect(ForgeDirection.EAST);
        middle.connect(ForgeDirection.EAST);
        return new PipeRun(inlet, last);
    }

    private static void breakChargedPipe(GameTestHelper helper, boolean split) {
        var run = placePipeRun(helper);
        var inlet = run.inlet();
        var last = run.last();
        long[] initial = new long[2];
        float[] pressure = new float[1];
        helper.startSequence()
            .thenWaitUntil(() -> run.requireConnected(helper))
            .thenExecute(() -> {
                run.fillSteam(helper);
                initial[0] = inlet.getNetwork().getAmountQ();
                initial[1] = inlet.getNetwork().getCanonicalState().totalEnergy().rawUnits();
                pressure[0] = inlet.getNetwork().getPressure();
                var cut = helper.absolute(split ? "valve" : "outlet");
                helper.getWorld().setBlockToAir(cut.x(), cut.y(), cut.z());
            })
            .thenWaitUntil(() -> {
                helper.assertEquals(split ? 2 : 3, inlet.getNetwork().getMemberCount(), "Survivor topology");
                if (split) helper.assertTrue(last.getNetwork() != null && last.getNetwork() != inlet.getNetwork(),
                    "Detached pipe must join a separate chamber");
            })
            .thenExecute(() -> {
                var survivor = inlet.getNetwork();
                int pipes = split ? 1 : 2;
                helper.assertEquals(initial[0] / 3 * pipes, survivor.getAmountQ(), "Remaining steam mass");
                helper.assertEquals(initial[1] / 3 * pipes, survivor.getCanonicalState().totalEnergy().rawUnits(),
                    "Remaining chamber energy");
                helper.assertTrue(Math.abs(pressure[0] - survivor.getPressure()) < 0.00001f,
                    "Breaking a pipe cannot compress the remaining steam");
                if (split) {
                    helper.assertEquals(initial[0] / 3, last.getNetwork().getAmountQ(), "Detached steam mass");
                    helper.assertEquals(initial[1] / 3, last.getNetwork().getCanonicalState().totalEnergy().rawUnits(),
                        "Detached chamber energy");
                }
            })
            .thenSucceed();
    }

    @GameTest(template = "injector", timeoutTicks = 120)
    public static void breakingChargedHydrophoreRemovesSpringWork(GameTestHelper helper) {
        var pipe = (MTEIntegratedFluidPipe) placeHatch(helper, "injector", (short) 5770, ForgeDirection.EAST)
            .getMetaTileEntity();
        var vessel = (MTEIntegratedFluidInputHatch) placeHatch(helper, "storage", (short) 5771, ForgeDirection.WEST)
            .getMetaTileEntity();
        pipe.connect(ForgeDirection.EAST);
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(pipe.getNetwork() != null && pipe.getNetwork() == vessel.getNetwork(),
                "Pipe and hydrophore connected"))
            .thenExecute(() -> {
                // Saved operating point: 450 K steam at 207675 Pa has a density of 1 kg per cubic meter
                double strokeLiters = 10_000.0 * 107_675 / 900_000;
                long massMicroKg = Math.round((10_100 + strokeLiters) * 1000);
                double springEu = 0.001 * strokeLiters * (100_000 + 207_675) / 2;
                long energyMicroEu = Math.round(massMicroKg * 2_203_793.5 + springEu * 1_000_000);
                pipe.getNetwork().replaceCanonicalState(IFNCanonicalState.fromRaw("water", massMicroKg, energyMicroEu));
                helper.assertTrue(Math.abs(pipe.getNetwork().getPressure() - 2.07675) < 0.00001, "Charged hydrophore pressure");
                var cut = helper.absolute("storage");
                helper.getWorld().setBlockToAir(cut.x(), cut.y(), cut.z());
            })
            .thenWaitUntil(() -> helper.assertEquals(1, pipe.getNetwork().getMemberCount(), "Only the rigid pipe remains"))
            .thenExecute(() -> {
                var network = pipe.getNetwork();
                helper.assertTrue(Math.abs(network.getAmountQ() - 100_000) <= 1, "100 L of remaining steam");
                helper.assertTrue(Math.abs(network.getCanonicalState().totalEnergy().rawUnits()
                    / (double) network.getAmountQ() - 2_203_793.5) < 1, "Rigid pipe must retain fluid energy only");
                helper.assertTrue(Math.abs(network.getTemperature() - 450) < 0.01, "No heating from the removed spring");
                helper.assertTrue(Math.abs(network.getPressure() - 2.07675) < 0.0001, "No compression from removal");
            })
            .thenSucceed();
    }

    @GameTest(template = "injector", timeoutTicks = 100)
    public static void addingStorageCreatesChamberVolume(GameTestHelper helper) {
        var inlet = placeHatch(helper, "injector", (short) 5773, ForgeDirection.EAST);
        var injector = (MTEIntegratedFluidInjectorHatch) inlet.getMetaTileEntity();
        var bucket = new FluidStack(FluidRegistry.WATER, 1000);
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertNotNull(injector.getNetwork(), "Isolated injector must join a network"))
            .thenExecute(() -> {
                helper.assertEquals(0, injector.fill(bucket, false), "No storage to preview");
                helper.assertEquals(0, injector.fill(bucket, true), "Isolated injector must not consume water");
                placeHatch(helper, "storage", (short) 5771, ForgeDirection.WEST);
            })
            .thenWaitUntil(() -> helper.assertEquals(2, injector.getNetwork().getMemberCount(), "Storage connected"))
            .thenExecute(() -> {
                var geometry = injector.getNetwork().getTopologySnapshot().chamberVolume().orElseThrow();
                var fluid = PureFluid.water();
                var empty = ClosedFluidChamber.empty(fluid, geometry);
                helper.assertEquals(10_000.0d, empty.volumeLiters(), "Vacuum excludes the hydrophore stroke");
                var admitted = empty.admitReferenceStream(fluid, 300, false, BigDecimal.ONE, bucket.amount, 10_000);
                helper.assertEquals(bucket.amount, admitted.units(), "Physical reference accepts the bucket");
                var chamber = admitted.chamber();
                helper.assertTrue(chamber.pressurePa() > 2_000 && chamber.pressurePa() < 4_000,
                    "Partially filled reference chamber should be near water vapor pressure");
                helper.assertEquals(10_000.0d, chamber.volumeLiters(), "Spring must stay at rest below preload");
                helper.assertEquals(bucket.amount, injector.fill(bucket, true), "Live injector sees added capacity");
                helper.assertTrue(Math.abs(injector.getNetwork().getPressure() * 100_000 - chamber.pressurePa()) < 1,
                    "Live pressure must match the water chamber to one pascal");
            })
            .thenSucceed();
    }

    @GameTest(template = "injector", timeoutTicks = 100)
    public static void superCoolantStartsAtLocalAmbient(GameTestHelper helper) {
        var inlet = placeHatch(helper, "injector", (short) 5773, ForgeDirection.EAST);
        var hatch = (MTEIntegratedFluidInputHatch) placeHatch(helper, "storage", (short) 5771, ForgeDirection.WEST).getMetaTileEntity();
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(inlet.getTimer() > 5 && hatch.getNetwork() != null
            && hatch.getNetwork() == ((MTEIntegratedFluidInjectorHatch) inlet.getMetaTileEntity()).getNetwork(), "Connected receiver"))
            .thenExecute(() -> {
                var network = hatch.getNetwork();
                var coolant = gregtech.api.enums.Materials.SuperCoolant.getFluid(9000);
                var hot = FluidRegistry.getFluidStack("hot super coolant", 1);
                helper.assertNotNull(hot, "Distinct hot product registered");
                helper.assertEquals(0, inlet.fill(ForgeDirection.WEST, hot, true), "No normalization of spent coolant");
                helper.assertEquals(coolant.amount, inlet.fill(ForgeDirection.WEST, coolant, false), "Fresh coolant preview");
                helper.assertEquals(0L, network.getAmountQ(), "Preview does not chill the receiver");
                helper.assertEquals(coolant.amount, inlet.fill(ForgeDirection.WEST, coolant, true), "Fresh coolant admitted");
                helper.assertEquals("supercoolant", network.getFluidName(), "Retain actual GT identity");
                helper.assertEquals(9_000_000_000L, network.getAmountQ(), "External liquid mass");
                double ambient = gregtech.api.metatileentity.implementations.integratedfluid.IFNAmbientTemperature.getAmbientTemperature(inlet.getWorld());
                helper.assertTrue(Math.abs(network.getTemperature() - ambient) < 1, "Nearly full chamber tracks local ambient: " + network.getTemperature());
                helper.recordDiagnostic("Super coolant: inlet ambient K=" + ambient + ", chamber K=" + network.getTemperature());
            }).thenSucceed();
    }

    @GameTest(template = "injector", timeoutTicks = 100)
    public static void boilerSteamAndWater(GameTestHelper helper) {
        IGregTechTileEntity inlet = placeHatch(helper, "injector", (short) 5773, ForgeDirection.EAST);
        IGregTechTileEntity store = placeHatch(helper, "storage", (short) 5771, ForgeDirection.WEST);
        MTEIntegratedFluidInjectorHatch injector = (MTEIntegratedFluidInjectorHatch) inlet.getMetaTileEntity();
        MTEIntegratedFluidInputHatch storage = (MTEIntegratedFluidInputHatch) store.getMetaTileEntity();
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(injector.getNetwork() != null
                && injector.getNetwork() == storage.getNetwork(), "Hatches must join the same network"))
            .thenExecute(() -> {
                IntegratedFluidNetwork network = injector.getNetwork();
                assertVacuum(helper, network);
                FluidStack water = new FluidStack(FluidRegistry.WATER, 1000);
                FluidStack steam = FluidRegistry.getFluidStack("steam", GTValues.STEAM_PER_WATER);
                FluidStack coolant = FluidRegistry.getFluidStack("ic2hotcoolant", 1000);
                helper.assertEquals(0, injector.fill(ForgeDirection.WEST, new FluidStack(FluidRegistry.LAVA, 1000), false), "Unsupported lava preview");
                helper.assertNotNull(coolant, "Spent IC2 coolant must be registered for this boundary test");
                helper.assertEquals(0, injector.fill(ForgeDirection.WEST, coolant, false), "Unsupported coolant preview");
                helper.assertEquals(0, injector.fill(ForgeDirection.WEST, coolant, true), "Unsupported coolant intake");
                helper.assertEquals(0L, network.getAmountQ(), "Rejected coolant leaves the network empty");
                helper.assertEquals(1000, injector.fill(ForgeDirection.WEST, water, true), "Water bucket intake");
                assertDisplayedMass(helper, network, "1000");
                assertDiskInventory(helper, store.getWorld(), network);
                long waterEnergy = network.getCanonicalState().totalEnergy().rawUnits();
                helper.assertEquals(steam.amount, injector.fill(ForgeDirection.WEST, steam, false), "Steam preview");
                assertDisplayedMass(helper, network, "1000");
                steam.amount = 1;
                helper.assertEquals(1, injector.fill(ForgeDirection.WEST, steam, true), "Single unit of steam");
                assertDisplayedMass(helper, network, "1000.00625");
                steam.amount = GTValues.STEAM_PER_WATER - 1;
                helper.assertEquals(steam.amount, injector.fill(ForgeDirection.WEST, steam, true), "Remaining steam");
                assertDisplayedMass(helper, network, "1001");
                helper.assertEquals(2_257_000_000_000L, network.getCanonicalState().totalEnergy().rawUnits() - waterEnergy,
                    "One kg of regular steam carries its nominal flow energy");

                assertDiskInventory(helper, store.getWorld(), network);
            })
            .thenSucceed();
    }

    private static void assertVacuum(GameTestHelper helper, IntegratedFluidNetwork network) {
        helper.assertEquals(0L, network.getAmountQ(), "Empty network inventory");
        helper.assertEquals(0.0f, network.getPressure(), "Empty network pressure in bar");
        helper.assertTrue(!network.getSolvedState().isPresent(), "Vacuum has no material phase or temperature");
    }

    static void assertDiskInventory(GameTestHelper helper, World world, IntegratedFluidNetwork network) {
        IntegratedFluidNetworkSavedData data = IntegratedFluidNetworkSavedData.get(world);
        NBTTagCompound pending = new NBTTagCompound();
        data.writeToNBT(pending);
        assertInventory(helper, network, pending);
        world.mapStorage.saveAllData();
        world.getSaveHandler().flush();
        MapStorage disk = new MapStorage(world.getSaveHandler());
        IntegratedFluidNetworkSavedData loaded =
            (IntegratedFluidNetworkSavedData) disk.loadData(IntegratedFluidNetworkSavedData.class, data.mapName);
        helper.assertNotNull(loaded, "World storage must write VFN data to disk");
        var geometry = network.getTopologySnapshot();
        var restored = new IntegratedFluidNetwork(network.getNetworkId()) {
            @Override
            public gregtech.api.metatileentity.implementations.integratedfluid.topology.IFNTopologySnapshot getTopologySnapshot() {
                return geometry;
            }
        };
        loaded.restoreInto(restored);
        helper.assertEquals(network.getPressure(), restored.getPressure(), "Pressure after disk reload");
        helper.assertEquals(network.getTemperature(), restored.getTemperature(), "Temperature after disk reload");
        NBTTagCompound saved = new NBTTagCompound();
        loaded.writeToNBT(saved);
        assertInventory(helper, network, saved);
    }

    private static void assertInventory(GameTestHelper helper, IntegratedFluidNetwork network, NBTTagCompound saved) {
        NBTTagList entries = saved.getTagList("Networks", 10);
        for (int i = 0; i < entries.tagCount(); i++) {
            NBTTagCompound entry = entries.getCompoundTagAt(i);
            if (entry.getLong("IdMost") == network.getNetworkId().getMostSignificantBits()
                && entry.getLong("IdLeast") == network.getNetworkId().getLeastSignificantBits()) {
                helper.assertEquals(network.getAmountQ(), entry.getLong("MassMicroKg"), "Mass in world save after transfer");
                helper.assertEquals(network.getCanonicalState().totalEnergy().rawUnits(), entry.getLong("TotalMicroEu"),
                    "Combined chamber energy on disk after transfer");
                helper.assertEquals(network.getFluidName(), entry.getString("FluidName"), "Substance on disk after transfer");
                return;
            }
        }
        helper.assertTrue(false, "Transferred network missing from disk");
    }

    private static void assertDisplayedMass(GameTestHelper helper, IntegratedFluidNetwork network, String kilograms) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setBoolean("hasNetwork", true);
        IFNWailaFormatter.writeNetworkStatus(network, tag);
        java.util.List<String> lines = new java.util.ArrayList<>();
        IFNWailaFormatter.addNetworkStatus(tag, lines);
        helper.assertTrue(lines.stream().anyMatch(line -> line.contains(kilograms + " kg")), "Expected " + kilograms + " kg");
    }

    static IGregTechTileEntity placeHatch(GameTestHelper helper, String label, short id, ForgeDirection face) {
        var pos = helper.absolute(label);
        return placeAt(helper.getWorld(), pos.x(), pos.y(), pos.z(), id, face);
    }

    static IGregTechTileEntity placeAt(World world, int x, int y, int z, short id, ForgeDirection face) {
        world.setBlock(x, y, z, GregTechAPI.sBlockMachines, GregTechAPI.METATILEENTITIES[id].getTileEntityBaseType(), 3);
        IGregTechTileEntity tile = (IGregTechTileEntity) world.getTileEntity(x, y, z);
        tile.setInitialValuesAsNBT(null, id);
        tile.setFrontFacing(face);
        return tile;
    }
}
