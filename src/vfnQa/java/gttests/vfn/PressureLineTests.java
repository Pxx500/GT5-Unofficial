package gttests.vfn;

import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;

import gregtech.api.enums.Materials;
import gregtech.api.enums.Mods;
import gregtech.api.enums.OrePrefixes;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidLiquidPump;
import gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidPipe;
import gregtech.api.metatileentity.implementations.integratedfluid.state.IFNCanonicalState;
import gregtech.api.recipe.RecipeMaps;
import gregtech.api.util.GTOreDictUnificator;

@GameTestHolder(value = Mods.ModIDs.GREG_TECH, templatePrefix = "vfn")
public class PressureLineTests {

    static final long MICRO_KG = 54_803_720;
    private static final long MICRO_EU = 110_578_686_132_945L;

    record Expansion(MTEIntegratedFluidPipe inlet, MTEIntegratedFluidPipe exhaust,
        gregtech.api.interfaces.tileentity.IGregTechTileEntity turbine, gregtech.api.interfaces.tileentity.IGregTechTileEntity load) {
        void ready(GameTestHelper h) {
            h.assertTrue(inlet.getNetwork() != null && exhaust.getNetwork() != null && inlet.getNetwork() != exhaust.getNetwork(),
                "Rated pipes are separate pressure vessels");
        }

        void charge(GameTestHelper h) {
            chargePair(inlet, exhaust);
            h.assertTrue(Math.abs(inlet.getNetwork().getPressure() - 331.5) < .01 && Math.abs(exhaust.getNetwork().getPressure() - 221) < .01,
                "Hot feed and cooler exhaust both start supercritical");
            turbine.enableWorking();
        }
    }

    private static void chargePair(MTEIntegratedFluidPipe high, MTEIntegratedFluidPipe low) {
        int a = high.getNetwork().getMemberCount(), b = low.getNetwork().getMemberCount();
        high.getNetwork().replaceCanonicalState(IFNCanonicalState.fromRaw("water", a * 12_894_426L, a * 35_530_474_686_141L));
        low.getNetwork().replaceCanonicalState(IFNCanonicalState.fromRaw("water", b * 21_301_564L, b * 37_551_663_742_696L));
    }

    private static void setHighPressureTarget(IGregTechTileEntity pump) {
        var settings = new net.minecraft.nbt.NBTTagCompound();
        pump.getMetaTileEntity().saveNBTData(settings);
        settings.setInteger("outletCbar", 35000);
        pump.getMetaTileEntity().loadNBTData(settings);
    }

    @GameTest(template = "regulator", timeoutTicks = 240)
    public static void denseFeedPumpReturnsWaterAgainstPressure(GameTestHelper h) {
        var site = RemoteMachineSite.at(h, "source");
        short pipe = MTEIntegratedFluidPipe.REINFORCED_ID;
        var high = (MTEIntegratedFluidPipe) site.place(0, 0, 0, pipe, ForgeDirection.EAST).getMetaTileEntity();
        var pump = site.place(1, 0, 0, MTEIntegratedFluidLiquidPump.HIGH_PRESSURE_ID, ForgeDirection.WEST);
        var low = (MTEIntegratedFluidPipe) site.place(2, 0, 0, pipe, ForgeDirection.WEST).getMetaTileEntity();
        setHighPressureTarget(pump);
        high.connect(ForgeDirection.EAST);
        low.connect(ForgeDirection.WEST);
        var a = new AmbientTests.AmbientMeter(h, high::getNetwork);
        var b = new AmbientTests.AmbientMeter(h, low::getNetwork);
        long[] before = new long[1];
        h.startSequence().thenWaitUntil(() -> h.assertTrue(high.getNetwork() != null && low.getNetwork() != null
            && high.getNetwork() != low.getNetwork(), "Both pipes formed separate vessels"))
            .thenExecute(() -> chargePair(high, low)).thenIdle(22).thenExecute(() -> {
                h.assertEquals(12_894_426L, high.getNetwork().getAmountQ(), "No compression without electricity");
                before[0] = a.withoutAmbient(high.getNetwork()) + b.withoutAmbient(low.getNetwork());
                h.assertEquals(1L, pump.injectEnergyUnits(ForgeDirection.UP, 2048, 1), "One EV packet accepted");
            }).thenWaitUntil(() -> h.assertTrue(high.getNetwork().getAmountQ() > 12_894_426, "Water returned to the high side"))
            .thenExecute(() -> {
                long paid = 2048 - pump.getStoredEU();
                h.assertTrue(paid > 0 && high.getNetwork().getPressure() > low.getNetwork().getPressure() + 100,
                    "A powered stroke lifted the charge by more than 100 bar");
                h.assertEquals(34_195_990L, high.getNetwork().getAmountQ() + low.getNetwork().getAmountQ(), "No mass lost");
                h.assertEquals(paid * 1_000_000, a.withoutAmbient(high.getNetwork()) + b.withoutAmbient(low.getNetwork()) - before[0],
                    "The increase in stored energy equals exactly the electricity spent");
                h.recordDiagnostic("Dense feed: paid=" + paid + " EU, returned=" + (high.getNetwork().getAmountQ() - 12_894_426) + " mg");
            }).thenSucceed();
    }

    @GameTest(template = "regulator", timeoutTicks = 320)
    public static void turbineElectricityDrivesItsOwnDenseReturn(GameTestHelper h) {
        var site = RemoteMachineSite.at(h, "source");
        short pipe = MTEIntegratedFluidPipe.REINFORCED_ID;
        var high = (MTEIntegratedFluidPipe) site.place(0, 0, 0, pipe, ForgeDirection.EAST).getMetaTileEntity();
        var low = (MTEIntegratedFluidPipe) site.place(2, 0, 0, pipe, ForgeDirection.WEST).getMetaTileEntity();
        var turbine = site.place(1, 0, 0, 5777, ForgeDirection.EAST);
        var feed = (MTEIntegratedFluidPipe) site.place(0, 0, 1, pipe, ForgeDirection.EAST).getMetaTileEntity();
        var drain = (MTEIntegratedFluidPipe) site.place(2, 0, 1, pipe, ForgeDirection.WEST).getMetaTileEntity();
        var pump = site.place(1, 0, 1, MTEIntegratedFluidLiquidPump.HIGH_PRESSURE_ID, ForgeDirection.WEST);
        setHighPressureTarget(pump);
        TurbineTests.installSteelRotor(turbine);
        turbine.disableWorking();
        pump.disableWorking();
        high.connect(ForgeDirection.EAST);
        low.connect(ForgeDirection.WEST);
        high.connect(ForgeDirection.SOUTH);
        low.connect(ForgeDirection.SOUTH);
        feed.connect(ForgeDirection.EAST);
        drain.connect(ForgeDirection.WEST);
        var a = new AmbientTests.AmbientMeter(h, high::getNetwork);
        var b = new AmbientTests.AmbientMeter(h, low::getNetwork);
        long[] ledger = new long[3];
        h.startSequence().thenWaitUntil(() -> h.assertTrue(high.getNetwork() != null && low.getNetwork() != null
            && high.getNetwork() != low.getNetwork() && high.getNetwork() == feed.getNetwork() && low.getNetwork() == drain.getNetwork()
            && high.getNetwork().getMemberCount() == 2 && low.getNetwork().getMemberCount() == 2, "Two 200 L pressure vessels"))
            .thenExecute(() -> {
                chargePair(high, low);
                ledger[0] = a.withoutAmbient(high.getNetwork()) + b.withoutAmbient(low.getNetwork());
                turbine.enableWorking();
            }).thenWaitUntil(() -> h.assertTrue(pump.getStoredEU() >= 128, "The turbine energized the idle return pump"))
            .thenExecute(turbine::disableWorking)
            .thenWaitUntil(() -> h.assertTrue(turbine.getStoredEU() < 128, "Generated packets reached the pump"))
            .thenExecute(() -> {
                ledger[1] = high.getNetwork().getAmountQ();
                ledger[2] = pump.getStoredEU();
                h.assertTrue(ledger[1] < 2 * 12_894_426L, "The turbine displaced part of the hot charge");
                pump.enableWorking();
            }).thenWaitUntil(() -> h.assertTrue(high.getNetwork().getAmountQ() > ledger[1], "Shaft output powered return flow"))
            .thenExecute(() -> {
                long balance = pump.getStoredEU() + turbine.getStoredEU() + 2 * (ledger[2] / 128);
                long stored = a.withoutAmbient(high.getNetwork()) + b.withoutAmbient(low.getNetwork());
                h.assertEquals(ledger[0] - stored, balance * 1_000_000, "No external electricity or hidden work");
                h.assertEquals(2 * 34_195_990L, high.getNetwork().getAmountQ() + low.getNetwork().getAmountQ(), "Closed fluid inventory");
                h.recordDiagnostic("Self-powered return: delivered=" + ledger[2] + " EU, paid=" + (ledger[2] - pump.getStoredEU())
                    + " EU, expanded=" + (2 * 12_894_426L - ledger[1]) + " mg, returned=" + (high.getNetwork().getAmountQ() - ledger[1]) + " mg");
            }).thenSucceed();
    }

    static Expansion turbine(GameTestHelper h) {
        var site = RemoteMachineSite.at(h, "source");
        short pipe = MTEIntegratedFluidPipe.REINFORCED_ID;
        var inlet = (MTEIntegratedFluidPipe) site.place(0, 0, 0, pipe, ForgeDirection.EAST).getMetaTileEntity();
        var turbine = site.place(1, 0, 0, 5777, ForgeDirection.EAST);
        var exhaust = (MTEIntegratedFluidPipe) site.place(2, 0, 0, pipe, ForgeDirection.WEST).getMetaTileEntity();
        var load = site.place(1, 1, 0, gregtech.api.enums.MetaTileEntityIDs.ENERGY_HATCH_MV.ID, ForgeDirection.DOWN);
        TurbineTests.installSteelRotor(turbine);
        turbine.disableWorking();
        h.assertTrue(inlet.connect(ForgeDirection.EAST) > 0 && exhaust.connect(ForgeDirection.WEST) > 0, "Both turbine faces connected");
        return new Expansion(inlet, exhaust, turbine, load);
    }

    @GameTest(template = "regulator", timeoutTicks = 240)
    public static void denseExpansionSuppliesAnMvLoad(GameTestHelper h) {
        var rig = turbine(h);
        var source = new AmbientTests.AmbientMeter(h, rig.inlet::getNetwork);
        var outlet = new AmbientTests.AmbientMeter(h, rig.exhaust::getNetwork);
        long[] before = new long[1];
        h.startSequence().thenWaitUntil(() -> rig.ready(h)).thenExecute(() -> {
            rig.charge(h);
            before[0] = source.withoutAmbient(rig.inlet.getNetwork()) + outlet.withoutAmbient(rig.exhaust.getNetwork());
        }).thenWaitUntil(() -> h.assertTrue(rig.load.getStoredEU() >= 1024, "At least eight MV packets delivered"))
            .thenExecute(() -> {
                var a = rig.inlet.getNetwork();
                var b = rig.exhaust.getNetwork();
                long delivered = rig.load.getStoredEU(), shaft = rig.turbine.getStoredEU() + delivered + 2 * (delivered / 128);
                h.assertTrue(a.getAmountQ() < 12_894_426 && b.getAmountQ() > 21_301_564, "A homogeneous charge reached the outlet");
                h.assertEquals(34_195_990L, a.getAmountQ() + b.getAmountQ(), "Both lines conserve mass");
                h.assertEquals(shaft * 1_000_000, before[0] - source.withoutAmbient(a) - outlet.withoutAmbient(b),
                    "Inventory energy pays for the dynamo, load and packet losses");
                h.recordDiagnostic("Dense expansion: delivered=" + delivered + " EU, shaft=" + shaft
                    + " EU, transferred=" + (b.getAmountQ() - 21_301_564) + " mg");
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
