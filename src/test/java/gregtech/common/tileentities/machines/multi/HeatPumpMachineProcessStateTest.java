package gregtech.common.tileentities.machines.multi;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.jupiter.api.Test;

import gregtech.api.metatileentity.implementations.integratedfluid.IFNTestSupport;
import gregtech.api.metatileentity.implementations.integratedfluid.state.IFNCanonicalState;
import gregtech.common.tileentities.machines.multi.heatpump.HeatPumpMachineProcessState;

class HeatPumpMachineProcessStateTest {

    @Test
    void interruptedBatchResumesWithThePaidHeatAndOriginalTarget() {
        var running = batch(470);
        assertEquals(128, running.advance(300, 128, work -> true));
        assertFalse(running.ready());
        var resumed = new HeatPumpMachineProcessState();
        resumed.load(saved(running));
        assertEquals(running.temperature(), resumed.temperature());
        assertEquals(running.advance(300, 100_000, work -> true), resumed.advance(300, 100_000, work -> true));
        assertTrue(resumed.ready());
        assertEquals(470, resumed.temperature(), 1e-6);
        assertEquals(saved(running), saved(resumed));
        assertEquals(0, resumed.advance(300, 100_000, work -> fail("Finished batch billed again")));
    }

    @Test
    void thermostatUsesInletSubstanceBeforeTakingTheFirstCharge() {
        var source = IFNTestSupport.newNetwork(IFNTestSupport.waterFluid(), 10000, 0, 20);
        var pump = new HeatPumpMachineProcessState();
        var intake = new HeatPumpMachineProcessState.Intake(source, null, 1000, 1, false);
        source.replaceCanonicalState(IFNCanonicalState.fromRaw("oxygen", 10_000_000, 3_251_000_000_000L));
        assertFalse(pump.fill(intake, 800, 0, 0));
        assertEquals(10_000_000, source.getAmountQ());
        source.replaceCanonicalState(IFNCanonicalState.fromRaw("water", 10_000_000, 10_423_434_875_000L));
        assertTrue(pump.fill(intake, 800, 0, 0));
        assertTrue(pump.advance(300, 10_000_000, eu -> true) > 0);
        assertTrue(pump.ready());
        assertEquals(800, pump.temperature(), 0.001);
    }

    @Test
    void decliningTheElectricalDebitLeavesAllFluidAndWorkUnchanged() {
        var state = batch(470);
        var before = saved(state);
        assertEquals(0, state.advance(300, 128, work -> false));
        assertEquals(before, saved(state));
        assertEquals(0, state.advance(300, 0, work -> fail("No power to draw")));
        assertEquals(before, saved(state));
    }

    @Test
    void coolingBelowReservoirSpendsElectricityAndRemovesHeat() {
        var state = batch(280);
        long before = saved(state).getLong("pumpMicroEu");
        long cost = state.advance(300, 1_000_000, work -> true);
        assertTrue(state.ready());
        assertTrue(cost > 0);
        assertEquals(280, state.temperature(), 1e-5);
        long removed = before - saved(state).getLong("pumpMicroEu");
        assertTrue(removed > 0 && removed <= 14_000_000L * cost);
    }

    @Test
    void secondInletWithoutPressureCannotDebitTheFirstInlet() {
        var water = IFNTestSupport.waterFluid();
        var red = IFNTestSupport.newNetwork(water, 10000, 0, 20);
        var blue = IFNTestSupport.newNetwork(water, 10000, 0, 20);
        red.replaceCanonicalState(IFNCanonicalState.fromRaw("water", 10_000_000, 10_423_434_875_000L));
        IFNTestSupport.restoreDrySteamAt475K(blue, 0.5);
        var pump = new HeatPumpMachineProcessState();
        var heels = new NBTTagCompound();
        // A 1 kg steam heel at 475 K in the blue working chamber provides about 2.19 bar
        heels.setString("pumpPartnerSubstance", "water");
        heels.setLong("pumpPartnerMicroKg", 1_000_000);
        heels.setLong("pumpPartnerMicroEu", 2_242_506_000_000L);
        pump.load(heels);
        var before = saved(pump);
        var redBefore = red.getCanonicalState();
        var blueBefore = blue.getCanonicalState();
        var inlets = new HeatPumpMachineProcessState.Intake(red, blue, 100, 1, false);
        assertFalse(pump.fillForPower(inlets, 128, true));
        assertEquals(before, saved(pump));
        assertEquals(redBefore.substanceAmount().rawUnits(), red.getAmountQ());
        assertEquals(redBefore.totalEnergy().rawUnits(), red.getCanonicalState().totalEnergy().rawUnits());
        assertEquals(blueBefore.substanceAmount().rawUnits(), blue.getAmountQ());
        assertEquals(blueBefore.totalEnergy().rawUnits(), blue.getCanonicalState().totalEnergy().rawUnits());

        IFNTestSupport.restoreDrySteamAt475K(blue, 4);
        blueBefore = blue.getCanonicalState();
        assertTrue(pump.fillForPower(inlets, 128, true));
        var charged = saved(pump);
        assertTrue(redBefore.substanceAmount().rawUnits() > red.getAmountQ());
        assertTrue(blueBefore.substanceAmount().rawUnits() > blue.getAmountQ());
        assertEquals(redBefore.substanceAmount().rawUnits(), red.getAmountQ() + charged.getLong("pumpMicroKg"));
        assertEquals(blueBefore.substanceAmount().rawUnits() + 1_000_000, blue.getAmountQ() + charged.getLong("pumpPartnerMicroKg"));
        assertEquals(redBefore.totalEnergy().rawUnits(), red.getCanonicalState().totalEnergy().rawUnits() + charged.getLong("pumpMicroEu"));
        assertEquals(blueBefore.totalEnergy().rawUnits() + heels.getLong("pumpPartnerMicroEu"),
            blue.getCanonicalState().totalEnergy().rawUnits() + charged.getLong("pumpPartnerMicroEu"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(HeatPumpMachineProcessState.Layout.class)
    void admittedFluidWithinToleranceMovesWithoutElectricity(HeatPumpMachineProcessState.Layout layout) {
        var water = IFNTestSupport.waterFluid();
        var red = IFNTestSupport.newNetwork(water, 10000, 0, 20);
        var blue = IFNTestSupport.newNetwork(water, 10000, 0, 20);
        var out = IFNTestSupport.newNetwork(water, 10000, 0, 20);
        var out2 = IFNTestSupport.newNetwork(water, 10000, 0, 20);
        var wet = IFNCanonicalState.fromRaw("water", 10_000_000, 10_423_434_875_000L);
        red.replaceCanonicalState(wet);
        boolean separate = layout == HeatPumpMachineProcessState.Layout.EXCHANGER;
        if (separate) blue.replaceCanonicalState(wet);
        var pump = new HeatPumpMachineProcessState();
        var intake = new HeatPumpMachineProcessState.Intake(red, separate ? blue : null, 100,
            layout == HeatPumpMachineProcessState.Layout.SPLIT ? 0.5 : 1, false);
        assertTrue(pump.fill(intake, 350, 150, 150));
        assertTrue(pump.ready());
        assertEquals(0, pump.advance(300, 128, eu -> fail("No conditioning requested")));
        assertTrue(pump.discharge(out, out2));
        assertFalse(pump.active());
        assertTrue(out.getAmountQ() > 0);
        assertEquals(layout != HeatPumpMachineProcessState.Layout.SINGLE, out2.getAmountQ() > 0);
        var heel = saved(pump);
        long mass = heel.getLong("pumpMicroKg") + heel.getLong("pumpPartnerMicroKg");
        long energy = heel.getLong("pumpMicroEu") + heel.getLong("pumpPartnerMicroEu");
        for (var network : java.util.List.of(red, blue, out, out2)) {
            mass += network.getAmountQ();
            energy += network.getCanonicalState().totalEnergy().rawUnits();
        }
        assertEquals((separate ? 2 : 1) * wet.substanceAmount().rawUnits(), mass);
        assertEquals((separate ? 2 : 1) * wet.totalEnergy().rawUnits(), energy);
    }

    @Test
    void exchangerCannotDrawTwoIndependentStreamsFromOneChamber() {
        var source = IFNTestSupport.newNetwork(IFNTestSupport.waterFluid(), 10000, 0, 20);
        var wet = IFNCanonicalState.fromRaw("water", 10_000_000, 10_423_434_875_000L);
        source.replaceCanonicalState(wet);
        var pump = new HeatPumpMachineProcessState();
        assertFalse(pump.fill(new HeatPumpMachineProcessState.Intake(source, source, 100, 1, false), 400, 0, 0));
        assertFalse(pump.active());
        assertEquals(wet.substanceAmount().rawUnits(), source.getAmountQ());
        assertEquals(wet.totalEnergy().rawUnits(), source.getCanonicalState().totalEnergy().rawUnits());
    }

    @Test
    void backpressureUsesThePhysicalPortColorOfTheSavedBatch() {
        var tag = saved(batch(470));
        tag.setBoolean("pumpExchanger", true);
        tag.setBoolean("pumpOutputReady", true);
        tag.setBoolean("pumpBluePrimary", true);
        tag.setString("pumpPartnerSubstance", "water");
        tag.setLong("pumpPartnerMicroKg", tag.getLong("pumpMicroKg"));
        tag.setLong("pumpPartnerMicroEu", tag.getLong("pumpMicroEu"));
        var pump = new HeatPumpMachineProcessState();
        pump.load(tag);
        var water = IFNTestSupport.waterFluid();
        var high = IFNTestSupport.newNetwork(water, 1000, 0, 20);
        IFNTestSupport.restoreDrySteamAt475K(high, 3);
        var low = IFNTestSupport.newNetwork(water, 1000, 0, 20);
        var secondHigh = IFNTestSupport.newNetwork(water, 1000, 0, 20);
        IFNTestSupport.restoreDrySteamAt475K(secondHigh, 3);
        assertEquals(HeatPumpMachineProcessState.PressureBlock.BOTH, pump.blockedOutlet(high, secondHigh));
        assertEquals(HeatPumpMachineProcessState.PressureBlock.BLUE, pump.blockedOutlet(high, low));
        assertEquals(HeatPumpMachineProcessState.PressureBlock.RED, pump.blockedOutlet(low, high));
        secondHigh.clearFluid();
        assertEquals(HeatPumpMachineProcessState.PressureBlock.NONE, pump.blockedOutlet(low, secondHigh));
    }

    private static HeatPumpMachineProcessState batch(int target) {
        var tag = new NBTTagCompound();
        tag.setString("pumpSubstance", "water");
        tag.setLong("pumpMicroKg", 100_000);
        tag.setLong("pumpMicroEu", 220_379_350_000L);
        tag.setBoolean("pumpBatchActive", true);
        tag.setDouble("pumpBatchTargetK", target);
        var state = new HeatPumpMachineProcessState();
        state.load(tag);
        return state;
    }

    private static NBTTagCompound saved(HeatPumpMachineProcessState state) {
        var tag = new NBTTagCompound();
        state.save(tag);
        return tag;
    }
}
