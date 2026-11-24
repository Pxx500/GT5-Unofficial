package gttests.vfn;

import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;

import gregtech.api.enums.Materials;
import gregtech.api.enums.MetaTileEntityIDs;
import gregtech.api.enums.ItemList;
import gregtech.api.enums.Mods;
import gregtech.api.enums.OrePrefixes;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidLiquidPump;
import gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidInputHatch;
import gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidOutputHatch;
import gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidPipe;
import gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidPressureRegulator;
import gregtech.api.metatileentity.implementations.integratedfluid.thermodynamics.PureFluid;
import gregtech.api.metatileentity.implementations.integratedfluid.state.IFNCanonicalState;
import gregtech.api.recipe.RecipeMaps;
import gregtech.api.util.GTOreDictUnificator;

@GameTestHolder(value = Mods.ModIDs.GREG_TECH, templatePrefix = "vfn")
public class PressureLineTests {

    static final long MICRO_KG = 54_803_720;
    static final long HELIUM_MICRO_KG_PER_SECTION = 9_748_972L;
    private static final long MICRO_EU = 110_578_686_132_945L;

    record Expansion(MTEIntegratedFluidPipe inlet, MTEIntegratedFluidPipe exhaust, HeatPumpTests.SteelShell shell,
        IGregTechTileEntity ivEv, IGregTechTileEntity evHv, IGregTechTileEntity hvMv, IGregTechTileEntity load) {
        void ready(GameTestHelper h) {
            h.assertTrue(inlet.getNetwork() != null && exhaust.getNetwork() != null && inlet.getNetwork() != exhaust.getNetwork()
                && shell.input().getNetwork() == inlet.getNetwork() && shell.output().getNetwork() == exhaust.getNetwork(),
                "Rated pipes are separate pressure vessels");
            var controller = (gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidSteamTurbine)
                shell.controller().getMetaTileEntity();
            h.assertTrue(controller.checkStructure(true, shell.controller()), "Steel turbine formed with reinforced hatches and IV dynamo");
            controller.fixAllIssues();
        }

        void charge(GameTestHelper h) {
            chargePair(inlet, exhaust);
            h.assertTrue(Math.abs(inlet.getNetwork().getPressure() - 331.5) < .01 && Math.abs(exhaust.getNetwork().getPressure() - 221) < .01,
                "Hot feed and cooler exhaust both start supercritical");
            shell.controller().enableWorking();
        }

        long shaftWork(GameTestHelper h) {
            long credited = load.getStoredEU();
            for (var tile : new IGregTechTileEntity[] {hvMv, evHv, ivEv, shell.supply()}) {
                long voltage = tile.getOutputVoltage();
                h.assertTrue(voltage > 0 && credited % voltage == 0, "Whole GT packets downstream at " + voltage + " V");
                long lossPerPacket = 1L << Math.max(0, gregtech.api.util.GTUtility.getTier(voltage) - 1);
                credited += credited / voltage * lossPerPacket + tile.getStoredEU();
            }
            return credited;
        }
    }

    private static void chargePair(MTEIntegratedFluidPipe high, MTEIntegratedFluidPipe low) {
        long a = rigidReferenceVolumes(high), b = rigidReferenceVolumes(low);
        high.getNetwork().replaceCanonicalState(IFNCanonicalState.fromRaw("water", a * 12_894_426L, a * 35_530_474_686_141L));
        low.getNetwork().replaceCanonicalState(IFNCanonicalState.fromRaw("water", b * 21_301_564L, b * 37_551_663_742_696L));
    }

    static long rigidReferenceVolumes(MTEIntegratedFluidPipe pipe) {
        var topology = pipe.getNetwork().getTopologySnapshot();
        long liters = topology.baseVolume().toWholeLiters();
        if (liters <= 0 || liters % 100 != 0 || topology.accumulatorVolume().toWholeLiters() != 0)
            throw new AssertionError("Dense reference charge requires rigid 100 L sections");
        return liters / 100;
    }

    static void chargeDenseHelium(MTEIntegratedFluidPipe pipe) {
        long sections = rigidReferenceVolumes(pipe);
        pipe.getNetwork().replaceCanonicalState(IFNCanonicalState.fromRaw("helium",
            sections * HELIUM_MICRO_KG_PER_SECTION, sections * 135_047_718_204L));
    }

    @GameTest(template = "regulator", timeoutTicks = 240)
    public static void ordinaryPortLimitsReinforcedLine(GameTestHelper h) {
        var site = RemoteMachineSite.at(h, "source");
        var pipe = (MTEIntegratedFluidPipe) site.place(0, 0, 0, MTEIntegratedFluidPipe.REINFORCED_ID, ForgeDirection.EAST).getMetaTileEntity();
        site.place(1, 0, 0, MTEIntegratedFluidInputHatch.REINFORCED_ID, ForgeDirection.WEST);
        site.place(-1, 0, 0, 5772, ForgeDirection.EAST);
        h.assertTrue(pipe.connect(ForgeDirection.EAST) > 0 && pipe.connect(ForgeDirection.WEST) > 0, "Both hatches attached");
        h.startSequence().thenWaitUntil(() -> h.assertTrue(pipe.getNetwork() != null && pipe.getNetwork().getMemberCount() == 3,
            "Both ports belong to the pipeline")).thenExecute(() -> {
                var n = pipe.getNetwork();
                h.assertEquals(10f, n.getOperationalLimits().maxPressureBar(), "Ordinary hatch sets the weakest rating");
                h.assertEquals(10200L, n.getTopologySnapshot().baseVolume().toWholeLiters(), "Both port volumes counted");
                h.assertEquals(10000L, n.getTopologySnapshot().accumulatorVolume().toWholeLiters(), "Only ordinary port adds compliance");
                site.world().setBlockToAir(site.x()-1, site.y(), site.z());
            }).thenWaitUntil(() -> h.assertTrue(pipe.getNetwork() != null && pipe.getNetwork().getMemberCount() == 2,
                "Only reinforced parts remain")).thenExecute(() -> {
                    var n = pipe.getNetwork();
                    h.assertEquals(350f, n.getOperationalLimits().maxPressureBar(), "Rated pressure restored");
                    h.assertEquals(200L, n.getTopologySnapshot().baseVolume().toWholeLiters(), "Remaining rigid volume");
                    h.assertEquals(0L, n.getTopologySnapshot().accumulatorVolume().toWholeLiters(), "Ordinary accumulator removed");
                }).thenSucceed();
    }

    record DensePump(HeatPumpTests.SteelShell rig, MTEIntegratedFluidPipe low, MTEIntegratedFluidPipe high) {
        void ready(GameTestHelper h) {
            h.assertTrue(low.getNetwork() != null && high.getNetwork() != null && low.getNetwork() == rig.input().getNetwork()
                && high.getNetwork() == rig.output().getNetwork() && low.getNetwork() != high.getNetwork(), "Two separate rated vessels");
            var pump = (MTEIntegratedFluidLiquidPump) rig.controller().getMetaTileEntity();
            h.assertTrue(pump.checkStructure(true, rig.controller()), "Formed EV feed pump");
        }

        void start() {
            var pump = (MTEIntegratedFluidLiquidPump) rig.controller().getMetaTileEntity();
            pump.fixAllIssues();
            pump.setTarget(35000);
            pump.setInterval(3);
            chargePair(high, low);
            rig.controller().enableWorking();
        }
    }

    static DensePump placeDensePump(GameTestHelper h) {
        short inputId = MTEIntegratedFluidInputHatch.REINFORCED_ID, outputId = MTEIntegratedFluidOutputHatch.REINFORCED_ID;
        for (short id : new short[] { inputId, outputId }) {
            h.assertNotNull(gregtech.api.GregTechAPI.METATILEENTITIES[id], "Registered reinforced machine port " + id);
            var recipe = RecipeMaps.assemblerRecipes.findRecipeQuery().items(
                (id == inputId ? ItemList.Hatch_Input_EV : ItemList.Hatch_Output_EV).get(1),
                gregtech.api.GregTechAPI.METATILEENTITIES[MTEIntegratedFluidPipe.REINFORCED_ID].getStackForm(1)).voltage(2048).find();
            h.assertTrue(recipe != null && recipe.getOutput(0).isItemEqual(gregtech.api.GregTechAPI.METATILEENTITIES[id].getStackForm(1)),
                "Directional EV assembler recipe for port " + id);
        }
        var site = RemoteMachineSite.at(h, "controller");
        var rig = HeatPumpTests.placeSteelShell(site.world(), site.x(), site.y(), site.z(), MTEIntegratedFluidLiquidPump.HIGH_PRESSURE_ID,
            (short) gregtech.api.enums.MetaTileEntityIDs.ENERGY_HATCH_LuV.ID, inputId, outputId);
        var low = (MTEIntegratedFluidPipe)site.place(-2, 0, 1, MTEIntegratedFluidPipe.REINFORCED_ID, ForgeDirection.EAST).getMetaTileEntity();
        var high = (MTEIntegratedFluidPipe)site.place(2, 0, 1, MTEIntegratedFluidPipe.REINFORCED_ID, ForgeDirection.WEST).getMetaTileEntity();
        h.assertTrue(low.connect(ForgeDirection.EAST) > 0 && high.connect(ForgeDirection.WEST) > 0, "Rated hatches connected");
        return new DensePump(rig, low, high);
    }

    @GameTest(template = "radiator", timeoutTicks = 650)
    public static void sealedNitrogenLoopCrossesCriticalPressure(GameTestHelper h) {
        cycleCriticalPressure(h, "nitrogen", 3395800, 777778, 50000000L, 9822162470000L, 65000000L, 12316046164500L);
    }

    @GameTest(template = "radiator", timeoutTicks = 650)
    public static void sealedHydrogenLoopCrossesCriticalPressure(GameTestHelper h) {
        cycleCriticalPressure(h, "hydrogen", 1296357.6, 55556, 4000000L, 1702192340389L, 6500000L, 2546004259566L);
    }

    @GameTest(template = "radiator", timeoutTicks = 650)
    public static void sealedOxygenLoopCrossesCriticalPressure(GameTestHelper h) {
        cycleCriticalPressure(h, "oxygen", 5043000, 888888, 75000000L, 15870487029784L, 92500000L, 19004553488232L);
    }

    private static void cycleCriticalPressure(GameTestHelper h, String substance, double criticalPa, int portion,
        long suctionMass, long suctionEnergy, long deliveryMass, long deliveryEnergy) {
        var plant = placeDensePump(h);
        var site = RemoteMachineSite.at(h, "controller");
        var valve = (MTEIntegratedFluidPressureRegulator) site.place(0, 0, 3, 5774, ForgeDirection.WEST).getMetaTileEntity();
        for (int side : new int[] { -1, 1 }) {
            var towardValve = side < 0 ? ForgeDirection.EAST : ForgeDirection.WEST;
            for (int z = 2; z <= 3; z++) {
                var pipe = (MTEIntegratedFluidPipe) site.place(side * 2, 0, z, MTEIntegratedFluidPipe.REINFORCED_ID,
                    ForgeDirection.NORTH).getMetaTileEntity();
                h.assertTrue(pipe.connect(ForgeDirection.NORTH) > 0, "Return line joins the pump port");
            }
            var end = (MTEIntegratedFluidPipe) site.place(side, 0, 3, MTEIntegratedFluidPipe.REINFORCED_ID, towardValve).getMetaTileEntity();
            h.assertTrue(end.connect(towardValve.getOpposite()) > 0 && end.connect(towardValve) > 0, "Return line joins the valve");
        }
        var rig = plant.rig();
        rig.controller().disableWorking();
        var pump = (MTEIntegratedFluidLiquidPump) rig.controller().getMetaTileEntity();
        var a = new AmbientTests.AmbientMeter(h, plant.low()::getNetwork);
        var b = new AmbientTests.AmbientMeter(h, plant.high()::getNetwork);
        long[] supplied = { 0 }, initial = { 0 }, paid = { 0 }, peak = { 0 };
        java.util.function.LongSupplier energy = () -> a.withoutAmbient(plant.low().getNetwork()) + b.withoutAmbient(plant.high().getNetwork());
        h.onEachTick("charge the pump supply", () -> {
            if (supplied[0] < 65536) supplied[0] += 8192 * rig.supply().injectEnergyUnits(ForgeDirection.NORTH, 8192, 1);
        });
        h.startSequence().thenWaitUntil(() -> {
            plant.ready(h);
            h.assertEquals(65536L, rig.supply().getStoredEU(), "Metered work ready");
            for (var p : new MTEIntegratedFluidPipe[] { plant.low(), plant.high() })
                h.assertEquals(500L, p.getNetwork().getTopologySnapshot().baseVolume().toWholeLiters(), "Sealed 500 L header");
        }).thenExecute(() -> {
            valve.setSetpointPressureBar(1);
            valve.setMaxPacketAmountL(50);
            // Both headers start above the critical temperature but below the critical pressure
            plant.low().getNetwork().replaceCanonicalState(IFNCanonicalState.fromRaw(substance, suctionMass, suctionEnergy));
            plant.high().getNetwork().replaceCanonicalState(IFNCanonicalState.fromRaw(substance, deliveryMass, deliveryEnergy));
            var high = plant.high().getNetwork();
            h.assertTrue(high.getPressurePa() < criticalPa && high.getPressurePa() > plant.low().getNetwork().getPressurePa(), "Adverse head");
            h.assertEquals(1d, high.getSolvedState().get().vaporMassFraction(), "Dry gas before pumping");
            initial[0] = energy.getAsLong();
            pump.setGasIntake(true);
            pump.setMode(2);
            pump.setTarget(30);
            pump.setInterval(3);
            pump.fixAllIssues();
            rig.controller().enableWorking();
        }).thenWaitUntil(() -> h.assertEquals(PureFluid.DenseRegion.SUPERCRITICAL,
            plant.high().getNetwork().getSolvedState().get().denseRegion(), "Paid stroke takes receiver across critical pressure"))
            .thenExecute(() -> {
                var high = plant.high().getNetwork();
                peak[0] = high.getAmountQ();
                paid[0] = supplied[0] - rig.supply().getStoredEU();
                h.assertTrue(paid[0] > 0 && peak[0] > deliveryMass && (peak[0] - deliveryMass) % portion == 0, "Whole portions compressed");
                h.assertEquals(suctionMass + deliveryMass, peak[0] + plant.low().getNetwork().getAmountQ(), "Both port masses balance");
                h.assertEquals(initial[0] + paid[0] * 1000000L, energy.getAsLong(), "Electrical work reaches the fluid");
                h.recordDiagnostic(substance + " after pump: " + high.getPressure() + " bar, " + high.getTemperature() + " K, " + paid[0] + " EU");
                rig.controller().disableWorking();
                valve.setSetpointPressureBar((float) (criticalPa / 100000));
            }).thenWaitUntil(() -> h.assertEquals(PureFluid.DenseRegion.NONE,
                plant.high().getNetwork().getSolvedState().get().denseRegion(), "Regulator unloads the dense header"))
            .thenExecute(() -> {
                var high = plant.high().getNetwork();
                h.assertTrue(high.getAmountQ() < peak[0] && high.getPressurePa() < criticalPa, "Real return flow to the suction line");
                h.assertEquals(suctionMass + deliveryMass, high.getAmountQ() + plant.low().getNetwork().getAmountQ(), "Sealed return retained all fluid");
                h.assertEquals(paid[0], supplied[0] - rig.supply().getStoredEU(), "No second debit during passive expansion");
                h.assertEquals(initial[0] + paid[0] * 1000000L, energy.getAsLong(), "Valve creates no energy");
                h.recordDiagnostic(substance + " after valve: " + high.getPressure() + " bar, " + high.getTemperature() + " K");
            }).thenSucceed();
    }

    @GameTest(template = "radiator", timeoutTicks = 650)
    public static void denseFeedPumpReturnsWaterAgainstPressure(GameTestHelper h) {
        var plant = placeDensePump(h);
        var rig = plant.rig();
        var low = plant.low();
        var high = plant.high();
        var pump = (MTEIntegratedFluidLiquidPump) rig.controller().getMetaTileEntity();
        var a = new AmbientTests.AmbientMeter(h, low::getNetwork);
        var b = new AmbientTests.AmbientMeter(h, high::getNetwork);
        long[] initial = new long[1], supplied = new long[1];
        boolean[] charging = new boolean[1];
        h.onEachTick("refill the GT energy hatch after the unfunded stroke", () -> {
            if (charging[0] && supplied[0] < 122880)
                supplied[0] += 2048 * rig.supply().injectEnergyUnits(ForgeDirection.NORTH, 2048, 1);
        });
        h.startSequence().thenWaitUntil(() -> plant.ready(h))
            .thenExecute(() -> {
                for (var pipe : new MTEIntegratedFluidPipe[]{low, high}) {
                    var n = pipe.getNetwork();
                    h.assertEquals(350f, n.getPressureLimitEvaluation().maxPressureBar(), "Port retains pipe rating");
                    h.assertEquals(200L, n.getTopologySnapshot().baseVolume().toWholeLiters(), "Port and pipe form a 200 L vessel");
                    h.assertEquals(0L, n.getTopologySnapshot().accumulatorVolume().toWholeLiters(), "No elastic charge");
                }
            }).thenWaitUntil(() -> {
                supplied[0] += 2048 * rig.supply().injectEnergyUnits(ForgeDirection.NORTH, 2048, 1);
                h.assertEquals(2048L, supplied[0], "First packet accepted at the hatch");
            }).thenExecute(() -> {
                plant.start();
                initial[0] = a.withoutAmbient(low.getNetwork()) + b.withoutAmbient(high.getNetwork());
            }).thenIdle(120).thenExecute(() -> {
                h.assertEquals(25_788_852L, high.getNetwork().getAmountQ(), "No fractional return on one EV packet");
                h.assertEquals(supplied[0], rig.supply().getStoredEU(), "No charge for a rejected stroke");
                charging[0] = true;
            }).thenWaitUntil(() -> h.assertTrue(high.getNetwork().getAmountQ() > 25_788_852L,
                "Paid dense return: " + pump.status() + ", stored " + rig.supply().getStoredEU() + " EU"))
            .thenExecute(() -> {
                long paid = supplied[0] - rig.supply().getStoredEU();
                h.assertEquals(26_788_852L, high.getNetwork().getAmountQ(), "One whole kilogram delivered");
                h.assertEquals(41_603_128L, low.getNetwork().getAmountQ(), "One whole kilogram debited");
                double head = high.getNetwork().getPressurePa() - low.getNetwork().getPressurePa();
                h.assertTrue(head > 1e7 && head <= 1.2e7 && paid > 40960 && paid <= 122880, "Rated head and stroke work");
                h.assertEquals(initial[0] + paid * 1_000_000L, a.withoutAmbient(low.getNetwork()) + b.withoutAmbient(high.getNetwork()),
                    "Both fluid chambers received exactly the hatch debit");
                h.recordDiagnostic("Dense return: 1 kg, work=" + paid + " EU, suction=" + low.getNetwork().getPressurePa()
                    + " Pa, discharge=" + high.getNetwork().getPressurePa() + " Pa");
            }).thenSucceed();
    }

    record DenseLoop(HeatPumpTests.SteelShell turbine, IGregTechTileEntity pump, IGregTechTileEntity power,
        MTEIntegratedFluidPipe hot, MTEIntegratedFluidPipe cold, MTEIntegratedFluidInputHatch suction, MTEIntegratedFluidOutputHatch discharge) {
        void startExpansion() {
            chargePair(hot, cold);
            var p = (MTEIntegratedFluidLiquidPump)pump.getMetaTileEntity();
            p.setMode(2);
            p.setTarget(5);
            p.setInterval(3);
            turbine.controller().enableWorking();
        }

        void ready(GameTestHelper h) {
            h.assertTrue(hot.getNetwork() != null && cold.getNetwork() != null && hot.getNetwork() != cold.getNetwork()
                && turbine.input().getNetwork() == hot.getNetwork() && turbine.output().getNetwork() == cold.getNetwork()
                && suction.getNetwork() == cold.getNetwork() && discharge.getNetwork() == hot.getNetwork(), "Separate pressure vessels");
            var t = (gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidSteamTurbine)turbine.controller().getMetaTileEntity();
            var p = (MTEIntegratedFluidLiquidPump)pump.getMetaTileEntity();
            h.assertTrue(t.checkStructure(true, turbine.controller()) && p.checkStructure(true, pump), "Turbine and return pump formed");
            t.fixAllIssues();
            p.fixAllIssues();
        }
    }

    static DenseLoop denseLoop(GameTestHelper h) {
        return denseLoop(h, 0, false);
    }

    static DenseLoop denseLoop(GameTestHelper h, int gap, boolean controlsOnHatches) {
        var site = RemoteMachineSite.at(h, "controller");
        short input = MTEIntegratedFluidInputHatch.REINFORCED_ID, output = MTEIntegratedFluidOutputHatch.REINFORCED_ID;
        int portRow = controlsOnHatches ? 2 : 1;
        var turbine = HeatPumpTests.placeSteelShell(site.world(), site.x(), site.y(), site.z(), (short) 5777,
            (short) MetaTileEntityIDs.DYNAMO_HATCH_LuV.ID, input, output, ForgeDirection.SOUTH, portRow, controlsOnHatches ? 1 : 0);
        int portZ = -2-gap;
        HeatPumpTests.placeSteelCasings(site.world(), site.x(), site.y(), site.z()+portZ-1);
        var pump = site.place(0, 0, portZ-1, MTEIntegratedFluidLiquidPump.HIGH_PRESSURE_ID, ForgeDirection.NORTH);
        var power = site.place(-1, controlsOnHatches ? 1 : 0, portZ+1, MetaTileEntityIDs.ENERGY_HATCH_LuV.ID, ForgeDirection.SOUTH);
        site.place(1, 0, portZ+1, 15497, ForgeDirection.SOUTH);
        if (controlsOnHatches) portZ--;
        var suction = (MTEIntegratedFluidInputHatch)site.place(1, 0, portZ, input, ForgeDirection.EAST).getMetaTileEntity();
        var discharge = (MTEIntegratedFluidOutputHatch)site.place(-1, 0, portZ, output, ForgeDirection.WEST).getMetaTileEntity();
        for (int x : new int[]{-2, 2}) {
            var face = x < 0 ? ForgeDirection.EAST : ForgeDirection.WEST;
            for (int z = portZ; z <= portRow; z++) {
                var pipe = (MTEIntegratedFluidPipe)site.place(x, 0, z, MTEIntegratedFluidPipe.REINFORCED_ID, face).getMetaTileEntity();
                if (z > portZ) h.assertTrue(pipe.connect(ForgeDirection.NORTH) > 0, "Pressure header connected");
                if (z == portZ || z == portRow) h.assertTrue(pipe.connect(face) > 0, "Machine port connected");
            }
        }
        TurbineTests.installSteelRotor(turbine.controller());
        turbine.controller().disableWorking();
        pump.disableWorking();
        var hot = (MTEIntegratedFluidPipe)site.tile(-2, 0, -2).getMetaTileEntity();
        var cold = (MTEIntegratedFluidPipe)site.tile(2, 0, -2).getMetaTileEntity();
        return new DenseLoop(turbine, pump, power, hot, cold, suction, discharge);
    }

    @GameTest(template = "radiator", timeoutTicks = 700)
    public static void turbineElectricityDrivesItsOwnDenseReturn(GameTestHelper h) {
        var loop = denseLoop(h);
        var dynamo = loop.turbine.supply();
        var a = new AmbientTests.AmbientMeter(h, loop.hot::getNetwork);
        var b = new AmbientTests.AmbientMeter(h, loop.cold::getNetwork);
        java.util.function.LongSupplier energy = () -> a.withoutAmbient(loop.hot.getNetwork()) + b.withoutAmbient(loop.cold.getNetwork());
        long[] initial = new long[2], afterExpansion = new long[3];
        h.startSequence().thenWaitUntil(() -> loop.ready(h)).thenExecute(() -> {
            loop.startExpansion();
            initial[0] = energy.getAsLong();
            initial[1] = loop.hot.getNetwork().getAmountQ();
            h.assertEquals(0L, dynamo.getStoredEU() + loop.power.getStoredEU(), "No external starting electricity");
        }).thenWaitUntil(() -> h.assertTrue(loop.power.getStoredEU() >= 3 * 32768, "Waiting for three LuV packets: pump="
            + loop.power.getStoredEU() + ", dynamo=" + dynamo.getStoredEU() + ", hot microkg=" + loop.hot.getNetwork().getAmountQ()))
            .thenExecute(loop.turbine.controller()::disableWorking).thenIdle(60).thenExecute(() -> {
                afterExpansion[0] = loop.hot.getNetwork().getAmountQ();
                afterExpansion[1] = loop.power.getStoredEU();
                afterExpansion[2] = dynamo.getStoredEU();
                long expanded = initial[1] - afterExpansion[0];
                h.assertTrue(expanded >= 2_000_000 && expanded % 1_000_000 == 0, "The turbine generated power from whole portions");
                h.assertEquals(0L, afterExpansion[1] % 32768, "Complete LuV packets to the pump");
                long losses = afterExpansion[1] / 32768 * 32;
                h.assertEquals(initial[0] - (afterExpansion[1] + afterExpansion[2] + losses) * 1_000_000L, energy.getAsLong(),
                    "Shaft electricity accounted before starting the return pump");
                loop.pump.enableWorking();
            }).thenWaitUntil(() -> h.assertTrue(loop.hot.getNetwork().getAmountQ() > afterExpansion[0], "Uphill return on turbine electricity"))
            .thenExecute(() -> {
                long paid = afterExpansion[1] - loop.power.getStoredEU();
                h.assertTrue(paid > 0 && loop.hot.getNetwork().getPressurePa() > loop.cold.getNetwork().getPressurePa(),
                    "Return against positive head paid from the energy hatch");
                h.assertEquals(afterExpansion[2], dynamo.getStoredEU(), "No new dynamo work");
                h.assertEquals(1_000_000L, loop.hot.getNetwork().getAmountQ() - afterExpansion[0], "One whole kilogram returned");
                h.assertEquals(6 * 34_195_990L, loop.hot.getNetwork().getAmountQ() + loop.cold.getNetwork().getAmountQ(), "Closed mass");
                long remaining = loop.power.getStoredEU() + dynamo.getStoredEU() + afterExpansion[1] / 32768 * 32;
                h.assertEquals(initial[0] - remaining * 1_000_000L, energy.getAsLong(), "No outside power or unaccounted fluid work");
                h.recordDiagnostic("Dense self-powered return: expanded=" + (initial[1] - afterExpansion[0]) + " microkg, delivered="
                    + afterExpansion[1] + " EU, returned=1 kg, pump=" + paid + " EU, remaining at pump=" + loop.power.getStoredEU() + " EU");
            }).thenSucceed();
    }

    static Expansion turbine(GameTestHelper h) {
        var site = RemoteMachineSite.at(h, "controller");
        var shell = HeatPumpTests.placeSteelShell(site.world(), site.x(), site.y(), site.z(), (short) 5777,
            (short) MetaTileEntityIDs.DYNAMO_HATCH_IV.ID, MTEIntegratedFluidInputHatch.REINFORCED_ID, MTEIntegratedFluidOutputHatch.REINFORCED_ID);
        var inlet = (MTEIntegratedFluidPipe) site.place(-2, 0, 1, MTEIntegratedFluidPipe.REINFORCED_ID, ForgeDirection.EAST).getMetaTileEntity();
        var exhaust = (MTEIntegratedFluidPipe) site.place(2, 0, 1, MTEIntegratedFluidPipe.REINFORCED_ID, ForgeDirection.WEST).getMetaTileEntity();
        h.assertTrue(inlet.connect(ForgeDirection.EAST) > 0 && exhaust.connect(ForgeDirection.WEST) > 0, "Both pressure ports connected");
        TurbineTests.installSteelRotor(shell.controller());
        shell.controller().disableWorking();
        var ivEv = site.place(-1, 0, -1, MetaTileEntityIDs.transformer_IV_EV.ID, ForgeDirection.SOUTH);
        var evHv = site.place(-1, 0, -2, MetaTileEntityIDs.transformer_EV_HV.ID, ForgeDirection.SOUTH);
        var hvMv = site.place(-1, 0, -3, MetaTileEntityIDs.transformer_HV_MV.ID, ForgeDirection.SOUTH);
        var load = site.place(0, 0, -3, MetaTileEntityIDs.ENERGY_HATCH_MV.ID, ForgeDirection.WEST);
        return new Expansion(inlet, exhaust, shell, ivEv, evHv, hvMv, load);
    }

    @GameTest(template = "radiator", timeoutTicks = 500)
    public static void denseExpansionSuppliesAnMvLoad(GameTestHelper h) {
        var rig = turbine(h);
        var source = new AmbientTests.AmbientMeter(h, rig.inlet::getNetwork);
        var outlet = new AmbientTests.AmbientMeter(h, rig.exhaust::getNetwork);
        long[] before = new long[1];
        h.startSequence().thenWaitUntil(() -> rig.ready(h)).thenExecute(() -> {
            rig.charge(h);
            before[0] = source.withoutAmbient(rig.inlet.getNetwork()) + outlet.withoutAmbient(rig.exhaust.getNetwork());
        }).thenWaitUntil(() -> h.assertTrue(rig.load.getStoredEU() >= 1024, "Waiting for eight MV packets: "
            + rig.shell.supply().getStoredEU() + " EU in dynamo, inlet " + rig.inlet.getNetwork().getAmountQ()))
            .thenExecute(() -> {
                var a = rig.inlet.getNetwork();
                var b = rig.exhaust.getNetwork();
                long moved = 25_788_852L - a.getAmountQ(), shaft = rig.shaftWork(h);
                h.assertTrue(moved > 0 && moved % 1_000_000 == 0, "Whole kilograms of dense water expanded");
                h.assertEquals(moved, b.getAmountQ() - 42_603_128L, "All turbine discharge reached the outlet");
                h.assertEquals(shaft * 1_000_000L, before[0] - source.withoutAmbient(a) - outlet.withoutAmbient(b),
                    "Fluid energy pays for the IV dynamo, three transformer buffers, packet losses and MV load");
                h.recordDiagnostic("Dense expansion: delivered=" + rig.load.getStoredEU() + " EU, shaft=" + shaft
                    + " EU, transferred=" + moved + " microkg");
            }).thenSucceed();
    }

    record Line(MTEIntegratedFluidPipe first, MTEIntegratedFluidPipe middle, MTEIntegratedFluidPipe last) {
        void ready(GameTestHelper h) {
            var n = first.getNetwork();
            h.assertTrue(n != null && middle.getNetwork() == n && last.getNetwork() == n && n.getMemberCount() == 3,
                "Three connected pipes");
        }

        void charge(GameTestHelper h) {
            var n = first.getNetwork();
            n.replaceCanonicalState(IFNCanonicalState.fromRaw("water", MICRO_KG, MICRO_EU));
            h.assertTrue(Math.abs(n.getPressure() - 250) < 0.01 && Math.abs(n.getTemperature() - 680) < 0.01,
                "Prepared 250 bar, 680 K inventory");
        }
    }

    static Line place(GameTestHelper h, boolean weakMiddle) {
        short rated = MTEIntegratedFluidPipe.REINFORCED_ID;
        var first = (MTEIntegratedFluidPipe) InjectorTests.placeHatch(h, "source", rated, ForgeDirection.EAST).getMetaTileEntity();
        var middle = (MTEIntegratedFluidPipe) InjectorTests.placeHatch(h, "valve", weakMiddle ? (short) 5770 : rated, ForgeDirection.EAST).getMetaTileEntity();
        var last = (MTEIntegratedFluidPipe) InjectorTests.placeHatch(h, "outlet", rated, ForgeDirection.WEST).getMetaTileEntity();
        first.connect(ForgeDirection.EAST);
        middle.connect(ForgeDirection.EAST);
        return new Line(first, middle, last);
    }

    @GameTest(template = "regulator", timeoutTicks = 400)
    public static void ratedPipeContainsElectricallyHeatedDenseWater(GameTestHelper h) {
        var recipe = RecipeMaps.assemblerRecipes.findRecipeQuery()
            .items(GTOreDictUnificator.get(OrePrefixes.pipeMedium, Materials.TungstenSteel, 1),
                GTOreDictUnificator.get(OrePrefixes.plateDense, Materials.TungstenSteel, 1)).voltage(2048).find();
        h.assertTrue(recipe != null && recipe.getOutput(0).isItemEqual(
            gregtech.api.GregTechAPI.METATILEENTITIES[MTEIntegratedFluidPipe.REINFORCED_ID].getStackForm(1)),
            "The EV assembler can make the installed pressure pipe");
        var line = place(h, false);
        var pos = h.absolute("outlet");
        var heater = InjectorTests.placeAt(h.getWorld(), pos.x() + 1, pos.y(), pos.z(), (short) 5776, ForgeDirection.EAST);
        line.last.connect(ForgeDirection.EAST);
        var ambient = new AmbientTests.AmbientMeter(h, line.first::getNetwork);
        long[] baseline = new long[1];
        h.startSequence().thenWaitUntil(() -> line.ready(h))
            .thenWaitUntil(() -> h.assertEquals(1L, heater.injectEnergyUnits(ForgeDirection.EAST, 128, 1), "MV energy accepted"))
            .thenExecute(() -> {
                line.charge(h);
                baseline[0] = ambient.withoutAmbient(line.first.getNetwork());
            }).thenIdle(100).thenExecute(() -> {
                line.ready(h);
                var n = line.first.getNetwork();
                h.assertTrue(n.getPressure() > 220.64 && n.getTemperature() > 647.096, "Sealed line remains supercritical");
                h.assertEquals(350f, n.getPressureLimitEvaluation().maxPressureBar(), "Installed wall rating");
                h.assertEquals(0L, heater.getStoredEU(), "Boiler spent its packet");
                h.assertEquals(baseline[0] + 128_000_000, ambient.withoutAmbient(n), "Stored heat equals paid EU plus ambient exchange");
                h.assertEquals(MICRO_KG, n.getAmountQ(), "Entire charge retained");
            }).thenSucceed();
    }

    @GameTest(template = "regulator", timeoutTicks = 240)
    public static void ordinaryMiddlePipeIsTheRupturePoint(GameTestHelper h) {
        var line = place(h, true);
        var pos = h.absolute("valve");
        h.startSequence().thenWaitUntil(() -> line.ready(h)).thenExecute(() -> {
            line.charge(h);
            h.assertEquals(10f, line.first.getNetwork().getPressureLimitEvaluation().maxPressureBar(), "Ordinary section limits the line");
        }).thenWaitUntil(() -> h.assertTrue(h.getWorld().isAirBlock(pos.x(), pos.y(), pos.z()), "Only the weak section ruptures"))
            .thenIdle(20).thenExecute(() -> {
                for (String label : new String[] { "source", "outlet" }) {
                    var end = h.absolute(label);
                    h.assertTrue(!h.getWorld().isAirBlock(end.x(), end.y(), end.z()), "Reinforced " + label + " survived");
                }
            }).thenSucceed();
    }
}
