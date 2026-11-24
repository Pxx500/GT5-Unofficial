package gttests.vfn;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.gtnewhorizons.horizonqa.api.client.ClientTest;
import com.gtnewhorizons.horizonqa.api.client.ClientTarget;
import com.gtnewhorizons.horizonqa.api.client.ClickTarget;
import com.cleanroommc.modularui.screen.GuiContainerWrapper;
import com.cleanroommc.modularui.widget.WidgetTree;
import org.lwjgl.input.Keyboard;

import gregtech.common.tileentities.machines.multi.HeatPumpMode;
import gregtech.common.tileentities.machines.multi.MTEHeatPump;
import gregtech.api.enums.Mods;
import gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidPipe;
import gregtech.api.metatileentity.implementations.integratedfluid.state.IFNCanonicalState;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidInjectorHatch;
import gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidInputHatch;

@GameTestHolder(value = Mods.ModIDs.GREG_TECH, templatePrefix = "vfn", clientOnly = true)
public final class InjectorViewTests {

    @GameTest(template = "radiator", timeoutTicks = 650)
    public static void inspectDiluteOxygenIntake(GameTestHelper h) {
        var rig = HeatPumpTests.placeDiluteOxygen(h);
        var tile = rig.pump().getBaseMetaTileEntity();
        var player = (EntityPlayerMP) h.getWorld().playerEntities.get(0);
        var p = h.absolute("controller");
        var reason = gregtech.api.recipe.check.SimpleCheckRecipeResult.ofFailure("vfn_inlet_portion");
        ClientTest.scenario(h).awaitServer("inlet and power hatch ready", () -> h.assertTrue(tile.getTimer() >= 20
            && rig.input().getNetwork() != null && rig.supply().inputEnergyFrom(ForgeDirection.NORTH), "Both GT inputs active"))
            .server("run the dilute feed", () -> {
                player.playerNetServerHandler.setPlayerLocation(p.x()+0.5, p.y()-1, p.z()-2.5, 0, 0);
                tile.setOwnerUuid(player.getUniqueID());
                tile.setOwnerName(player.getCommandSenderName());
                HeatPumpTests.startDiluteOxygen(h, rig);
            }).withinTicks(320).awaitServer("refused metered stroke", () -> h.assertEquals(reason, rig.pump().getCheckRecipeResult(), "Not enough gas"))
            .server("inspect the controller", () -> rig.pump().onRightclick(tile, player))
            .awaitClient("synchronized intake warning", c -> {
                var mc = Minecraft.getMinecraft();
                if (!(mc.currentScreen instanceof net.minecraft.client.gui.inventory.GuiContainer)
                    || !(mc.theWorld.getTileEntity(p.x(),p.y(),p.z()) instanceof IGregTechTileEntity client)
                    || !(client.getMetaTileEntity() instanceof MTEHeatPump pump) || !reason.equals(pump.getCheckRecipeResult())
                    || pump.getOperatingMode() != HeatPumpMode.TARGET_ENERGY || pump.getUniversalValue() != 128
                    || !net.minecraft.util.StatCollector.canTranslate("GT5U.gui.text.recipe_result.vfn_inlet_portion"))
                    throw new AssertionError("Waiting for the intake warning");
            }).capture("insufficient-oxygen-intake").server("close the panel", player::closeScreen).succeed();
    }

    @GameTest(template = "water_line", timeoutTicks = 650)
    public static void inspectCopperRadiatorFin(GameTestHelper h) {
        var machines = RadiatorTests.placeMatched(h);
        var p = h.absolute("controller");
        var player = (EntityPlayerMP) h.getWorld().playerEntities.get(0);
        ClientTest.scenario(h).awaitServer("both inlets connected", () -> h.assertTrue(machines.bare().inlet().getNetwork() != null
            && machines.finned().inlet().getNetwork() != null, "Separate test charges ready"))
            .server("run matched radiator batches", () -> {
                machines.start(h);
                h.getWorld().setBlock(p.x()+4, p.y()+2, p.z()-5, net.minecraft.init.Blocks.stonebrick);
                player.playerNetServerHandler.setPlayerLocation(p.x()+4.5, p.y()+3, p.z()-4.5, 30, 24);
            }).withinTicks(400).awaitServer("extra heat removed by copper fin", () -> machines.checkFinished(h))
            .awaitClient("visible copper exchanger", c -> {
                var world = Minecraft.getMinecraft().theWorld;
                if (!(world.getBlock(p.x()+4, p.y()+2, p.z()+1) instanceof gregtech.common.blocks.BlockRadiatorModule))
                    throw new AssertionError("Waiting for the radiator modules");
            }).capture("matched-radiators-copper-fin").succeed();
    }

    @GameTest(template = "return_plant", timeoutTicks = 650)
    public static void inspectCondensateReturnPlant(GameTestHelper h) {
        var loop = VaporOutletTests.placeReturnLoop(h);
        var rig = loop.plant().rig();
        var p = h.absolute("source");
        var player = (EntityPlayerMP) h.getWorld().playerEntities.get(0);
        boolean[] started = new boolean[1];
        h.onEachTick("power the operating loop", () -> { if (started[0]) loop.powerHeaters(); });
        ClientTest.scenario(h).awaitServer("three connected circuits", () -> loop.ready(h))
            .server("start the wet boiler at temperature", () -> {
                loop.primeWarm();
                started[0] = true;
                h.getWorld().setBlock(p.x() + 5, p.y() + 1, p.z() - 3, net.minecraft.init.Blocks.stonebrick);
                player.playerNetServerHandler.setPlayerLocation(p.x() + 5.5, p.y() + 2, p.z() - 2.5, 49, 17);
            }).withinTicks(360).awaitServer("steam delivered electrical work", () -> h.assertTrue(rig.load().getStoredEU() >= 128, "MV load powered"))
            .awaitClient("pump and turbine rendered in the same installation", c -> {
                var world = Minecraft.getMinecraft().theWorld;
                if (!(world.getTileEntity(p.x()+1, p.y()+3, p.z()) instanceof IGregTechTileEntity pump) || pump.getMetaTileID() != 5779
                    || !(world.getTileEntity(p.x()+1, p.y(), p.z()) instanceof IGregTechTileEntity turbine) || turbine.getMetaTileID() != 5777)
                    throw new AssertionError("Waiting for both controllers");
            }).capture("steam-plant-return-pump").succeed();
    }

    @GameTest(template = "condensing_plant", timeoutTicks = 9900)
    public static void configureColdCondensateReturn(GameTestHelper h) {
        var installation = VaporOutletTests.placeCondensingPlant(h);
        var loop = installation.loop();
        var controller = (gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidLiquidPump)loop.pump().getMetaTileEntity();
        var player = (EntityPlayerMP) h.getWorld().playerEntities.get(0);
        var p = h.absolute("source");
        boolean[] started = new boolean[1];
        h.onEachTick("power the plant after commissioning", () -> {
            if (!started[0]) return;
            installation.powerBoiler();
            if (loop.power().getEUCapacity() - loop.power().getStoredEU() >= 128) loop.power().injectEnergyUnits(ForgeDirection.NORTH, 128, 1);
        });
        var setup = ClientTest.scenario(h).awaitServer("condensing plant formed", () -> installation.ready(h))
            .server("stand beside the return pump", () -> {
                installation.fill(h);
                h.getWorld().setBlock(p.x()+2, p.y()+2, p.z()-3, net.minecraft.init.Blocks.stonebrick);
                player.playerNetServerHandler.setPlayerLocation(p.x()+2.5, p.y()+3, p.z()-2.5, 22, 23);
                loop.pump().setOwnerUuid(player.getUniqueID());
                loop.pump().setOwnerName(player.getCommandSenderName());
            }).awaitClient("return pump visible", c -> {
                if (!(Minecraft.getMinecraft().theWorld.getTileEntity(p.x()+1, p.y()+3, p.z()) instanceof IGregTechTileEntity t)
                    || t.getMetaTileID() != 5779) throw new AssertionError("Waiting for return pump");
            }).server("open return pump", () -> controller.onRightclick(loop.pump(), player))
            .click(panelControl(null, "pumpSettingsButton"))
            .click(panelControl("pumpSettings", "pumpMode"))
            .awaitServer("differential mode", () -> h.assertEquals(1, controller.mode().ordinal(), "Pressure rise selected"))
            .click(panelControl("pumpSettings", "pumpMode"))
            .awaitServer("flow mode", () -> h.assertEquals(2, controller.mode().ordinal(), "Volume selected"))
            .click(panelControl("pumpSettings", "pumpTarget")).key(Keyboard.KEY_END, '\0');
        for (int i = 0; i < 5; i++) setup.key(Keyboard.KEY_BACK, '\b');
        setup.key(Keyboard.KEY_2, '2').key(Keyboard.KEY_RETURN, '\r')
            .awaitServer("two-liter stroke", () -> h.assertEquals(2, controller.target(), "2 L per stroke"))
            .capture("condensate-pump-setting")
            .server("close settings and energize plant", () -> {
                player.closeScreen();
                int x = p.x()+15, y = p.y()+10, z = p.z()+15;
                h.assertTrue(h.getWorld().isAirBlock(x, y, z), "Free overview position");
                h.afterTest(() -> h.getWorld().setBlockToAir(x, y, z));
                h.getWorld().setBlock(x, y, z, net.minecraft.init.Blocks.stonebrick);
                player.playerNetServerHandler.setPlayerLocation(x+0.5, y+1, z+0.5, 119, 33);
                started[0] = true;
            }).awaitClient("both ends of the condenser rendered", c -> {
                var mc = Minecraft.getMinecraft();
                if (Math.abs(mc.thePlayer.posX - (p.x()+15.5)) > 0.1 || mc.currentScreen != null
                    || !(mc.theWorld.getTileEntity(p.x()+3,p.y(),p.z()+20) instanceof IGregTechTileEntity last) || last.getMetaTileID() != 5775)
                    throw new AssertionError("Waiting for condenser and overview");
            }).capture("condensing-plant")
            .withinTicks(9000).awaitServer("EV delivery after GUI setup", () -> h.assertTrue(loop.plant().rig().load().getStoredEU() >= 2048,
                "First electricity delivered after startup")).succeed();
    }

    @GameTest(template = "radiator", timeoutTicks = 4000)
    public static void compareSteamLoopsAcrossDimensions(GameTestHelper helper) {
        if (DimensionManager.getWorld(-1) == null) DimensionManager.initDimension(-1);
        var worlds = new WorldServer[] { (WorldServer) helper.getWorld(), DimensionManager.getWorld(-1) };
        var trials = new VaporOutletTests.ReservoirTrial[2];
        var sites = new RemoteMachineSite[2];
        for (int i = 0; i < worlds.length; i++) {
            var site = sites[i] = RemoteMachineSite.reserve(helper, worlds[i], 49, 4);
            site.reloadForObservation(helper);
            trials[i] = new VaporOutletTests.ReservoirTrial(helper, site);
        }
        var view = sites[0];
        var player = (EntityPlayerMP) worlds[0].playerEntities.get(0);
        double x = player.posX, y = player.posY, z = player.posZ;
        float yaw = player.rotationYaw, pitch = player.rotationPitch;
        helper.assertTrue(view.world().isAirBlock(view.x() + 1, view.y() - 1, view.z() - 3), "Clear camera platform");
        view.world().setBlock(view.x() + 1, view.y() - 1, view.z() - 3, net.minecraft.init.Blocks.stonebrick);
        helper.afterTest(() -> {
            player.playerNetServerHandler.setPlayerLocation(x, y, z, yaw, pitch);
            view.world().setBlockToAir(view.x() + 1, view.y() - 1, view.z() - 3);
        });
        player.playerNetServerHandler.setPlayerLocation(view.x() + 1.5, view.y(), view.z() - 2.5, -18.4f, 19);
        long[] primedAt = new long[1];
        ClientTest.scenario(helper).awaitServer("both loops connected", () -> {
            for (var trial : trials) trial.loop.ready(helper);
        }).server("seal identical charges", () -> {
            for (var trial : trials) trial.prime();
            primedAt[0] = trials[0].loop.pump().getTimer();
        }).withinTicks(1000).awaitClient("sealed Overworld exhaust shows condensate", c -> {
            requirePipeMass(view.x() + 2, view.y(), view.z(), 5000);
            var tag = mcp.mobius.waila.api.impl.DataAccessorCommon.instance.getNBTData();
            if (!(tag.getDouble("quality") > 0 && tag.getDouble("quality") < 0.99 && tag.getDouble("ambientEuPerTick") < 0))
                throw new AssertionError("Waiting for the measured two-phase exhaust");
        }).capture("overworld-exhaust-condensing")
            .withinTicks(1100).awaitServer("matched sealed interval", () -> helper.assertTrue(
                trials[0].loop.pump().getTimer() - primedAt[0] >= 1000, "1000 sealed ticks"))
            .server("start the two installations", () -> {
            helper.assertTrue(trials[0].exhaust().getQuality() < 0.999 && trials[1].exhaust().getQuality() == 1,
                "The same sealed exhaust condenses only in the cooler dimension");
            for (var trial : trials) trial.start(helper);
        }).withinTicks(2450).awaitServer("matched powered interval", () -> helper.assertTrue(trials[0].elapsed() >= 2400, "2400 powered ticks"))
            .server("settle the two energy balances", () -> {
            for (var trial : trials) trial.finish(helper);
            helper.assertTrue(trials[1].ambientHeat() > trials[0].ambientHeat(), "Hotter external reservoir provides more net heat");
            helper.assertEquals(trials[0].elapsed(), trials[1].elapsed(), "Both machines experienced the same number of powered ticks");
        }).succeed();
    }

    @GameTest(template = "radiator", timeoutTicks = 240)
    public static void injectorUsesItsOwnWorldExceptForSteam(GameTestHelper helper) {
        var overworld = (WorldServer) helper.getWorld();
        helper.assertEquals(0, overworld.provider.dimensionId, "Overworld test cell");
        if (DimensionManager.getWorld(-1) == null) DimensionManager.initDimension(-1);
        var nether = DimensionManager.getWorld(-1);
        helper.assertNotNull(nether, "A separate Nether world");
        var sites = new RemoteMachineSite[] { RemoteMachineSite.reserve(helper, overworld, 39, 0),
            RemoteMachineSite.reserve(helper, nether, 39, 0) };
        for (var site : sites) {
            site.reloadForObservation(helper);
            for (int dz : new int[] { 0, 2 }) {
                InjectorTests.placeAt(site.world(), site.x(), site.y(), site.z() + dz, (short) 5773, ForgeDirection.EAST);
                InjectorTests.placeAt(site.world(), site.x() + 1, site.y(), site.z() + dz, (short) 5771, ForgeDirection.WEST);
            }
        }
        helper.startSequence().thenWaitUntil(() -> {
            for (var site : sites) for (int dz : new int[] { 0, 2 }) {
                var inlet = site.tile(0, 0, dz);
                var injector = (MTEIntegratedFluidInjectorHatch) inlet.getMetaTileEntity();
                var store = (MTEIntegratedFluidInputHatch) site.tile(1, 0, dz).getMetaTileEntity();
                helper.assertTrue(inlet.getTimer() > 5 && store.getNetwork() != null && store.getNetwork() == injector.getNetwork(),
                    "Connected Forge port in dimension " + site.world().provider.dimensionId + ", age " + inlet.getTimer());
            }
        }).thenExecute(() -> {
            long[] coolantEnergy = new long[2];
            for (int i = 0; i < sites.length; i++) {
                var site = sites[i];
                var inlet = site.tile(0, 0, 0);
                var store = (MTEIntegratedFluidInputHatch) site.tile(1, 0, 0).getMetaTileEntity();
                var steamStore = (MTEIntegratedFluidInputHatch) site.tile(1, 0, 2).getMetaTileEntity();
                helper.assertEquals(9000, inlet.fill(ForgeDirection.WEST,
                    gregtech.api.enums.Materials.SuperCoolant.getFluid(9000), true), "Batch admitted in dimension " + site.world().provider.dimensionId);
                coolantEnergy[i] = store.getNetwork().getCanonicalState().totalEnergy().rawUnits();
                helper.assertTrue(Math.abs(store.getNetwork().getTemperature() - (i == 0 ? 300 : 400)) < 1,
                    "Coolant nearly fills the local chamber at ambient");
                helper.assertEquals(160, site.tile(0, 0, 2).fill(ForgeDirection.WEST, FluidRegistry.getFluidStack("steam", 160), true),
                    "One water-equivalent kg of steam");
                helper.assertEquals(2_257_000_000_000L, steamStore.getNetwork().getCanonicalState().totalEnergy().rawUnits(),
                    "Saturated-vapor inlet enthalpy independent of planet");
            }
            helper.assertEquals(9000L * 6000 * 100 * 1_000_000, coolantEnergy[1] - coolantEnergy[0],
                "Only the 100 K reservoir difference changes the supplied coolant energy");
        }).thenSucceed();
    }

    @GameTest(template = "radiator", timeoutTicks = 1600)
    public static void heatPumpUnderPower(GameTestHelper helper) {
        inspectHeatPump(helper, HeatPumpMode.TARGET_TEMPERATURE);
    }

    @GameTest(template = "radiator", timeoutTicks = 1000)
    public static void meteredPowerDisplay(GameTestHelper helper) {
        inspectHeatPump(helper, HeatPumpMode.TARGET_ENERGY);
    }

    @GameTest(template = "radiator", timeoutTicks = 1200)
    public static void splitFlowOutlets(GameTestHelper helper) {
        var rig = HeatPumpTests.place(helper);
        var blue = HeatPumpTests.addBlueOutlet(helper, rig);
        var p = helper.absolute("controller");
        var w = helper.getWorld();
        int x = p.x(), y = p.y(), z = p.z();
        var redPipe = pipeEnd(w, x + 2, y, z + 1, ForgeDirection.WEST);
        var bluePipe = pipeEnd(w, x + 1, y + 2, z + 1, ForgeDirection.DOWN);
        boolean[] enabled = { false };
        long[] initial = new long[2];
        helper.onEachTick("supply the split-flow machine", () -> {
            if (enabled[0]) rig.supply().injectEnergyUnits(ForgeDirection.NORTH, 128, 1);
        });
        ClientTest.scenario(helper)
            .server("look at the colored outlets", () -> {
                w.setBlock(x + 4, y + 1, z - 2, net.minecraft.init.Blocks.stonebrick);
                var player = (EntityPlayerMP) w.playerEntities.get(0);
                player.playerNetServerHandler.setPlayerLocation(x + 4.5, y + 2, z - 1.5, 46, 32);
                var tile = rig.pump().getBaseMetaTileEntity();
                tile.setOwnerUuid(player.getUniqueID());
                tile.setOwnerName(player.getCommandSenderName());
            })
            .awaitServer("both outlet pipes connected", () -> helper.assertTrue(redPipe.getNetwork() != null
                && bluePipe.getNetwork() != null && bluePipe.getNetwork() != redPipe.getNetwork()
                && redPipe.getNetwork() == rig.output().getNetwork() && bluePipe.getNetwork() == blue.getNetwork(), "Two separate outlets"))
            .server("cool the primary stream at 128 EU/t", () -> {
                rig.charge(helper, 310);
                rig.pump().setSplitFlowMode(true);
                rig.pump().setSplitRatio(0.25f);
                rig.pump().setOperatingMode(HeatPumpMode.TARGET_ENERGY);
                rig.pump().setTargetEnergy(128);
                rig.pump().setTargetHeating(false);
                bluePipe.getNetwork().replaceCanonicalState(IFNCanonicalState.fromRaw("water", 10_000_000, 22_037_935_000_000L));
            }).withinTicks(500)
            .awaitServer("charge is split", () -> helper.assertTrue(rig.inventory().getLong("pumpPartnerMicroKg") > 0,
                "Both working portions admitted"))
            .server("connect MV", () -> {
                var saved = rig.inventory();
                initial[0] = saved.getLong("pumpMicroEu");
                initial[1] = saved.getLong("pumpPartnerMicroEu");
                enabled[0] = true;
            })
            .awaitServer("red cools and blue warms", () -> {
                var saved = rig.inventory();
                helper.assertTrue(saved.getLong("pumpMicroEu") < initial[0] && saved.getLong("pumpPartnerMicroEu") > initial[1],
                    "Cooling moves heat into the second stream");
            })
            .awaitServer("only red could discharge", () -> {
                helper.assertTrue(redPipe.getNetwork().getAmountQ() > 0 && bluePipe.getNetwork().getAmountQ() == 10_000_000,
                    "First delivery reaches only its own outlet");
                helper.assertEquals(gregtech.api.recipe.check.SimpleCheckRecipeResult.ofFailure("vfn_blue_outlet_head"),
                    rig.pump().getCheckRecipeResult(), "Terminal identifies backpressure");
            })
            .server("inspect the blocked split outlet", () -> rig.pump().onRightclick(rig.pump().getBaseMetaTileEntity(),
                (EntityPlayerMP) w.playerEntities.get(0)))
            .awaitClient("read the outlet pressure result", c -> {
                var mc = Minecraft.getMinecraft();
                if (!(mc.currentScreen instanceof net.minecraft.client.gui.inventory.GuiContainer)
                    || !(mc.theWorld.getTileEntity(x, y, z) instanceof IGregTechTileEntity tile)
                    || !(tile.getMetaTileEntity() instanceof MTEHeatPump pump)
                    || !pump.isSplitFlowMode() || pump.getOperatingMode() != HeatPumpMode.TARGET_ENERGY || pump.getUniversalValue() != 128
                    || !pump.getCheckRecipeResult().equals(gregtech.api.recipe.check.SimpleCheckRecipeResult.ofFailure("vfn_blue_outlet_head"))
                    || !net.minecraft.util.StatCollector.canTranslate("GT5U.gui.text.recipe_result.vfn_blue_outlet_head"))
                    throw new AssertionError("Waiting for the synchronized outlet-pressure result");
            }).capture("blocked-split-terminal")
            .server("open the blue receiver", () -> {
                ((EntityPlayerMP) w.playerEntities.get(0)).closeScreen();
                bluePipe.getNetwork().clearFluid();
            })
            .awaitServer("both pipes received fluid", () -> helper.assertTrue(redPipe.getNetwork().getAmountQ() > 0
                && bluePipe.getNetwork().getAmountQ() > 0, "Paid split discharged to both live pipes"))
            .awaitClient("colored hatches visible", c -> {
                var client = Minecraft.getMinecraft().theWorld;
                if (!(client.getTileEntity(x + 1, y, z + 1) instanceof IGregTechTileEntity red)
                    || !(client.getTileEntity(x + 1, y + 1, z + 1) instanceof IGregTechTileEntity cold)
                    || red.getColorization() != 1 || cold.getColorization() != 4) throw new AssertionError("Waiting for the two hatch colors");
            })
            .capture("split-flow-outlets")
            .succeed();
    }

    @GameTest(template = "radiator", timeoutTicks = 1200)
    public static void reviseTemperatureOfHeldStreams(GameTestHelper helper) {
        var line = HeatPumpTests.limitedExchanger(helper);
        var rig = line.ports().red();
        var p = helper.absolute("controller");
        var world = helper.getWorld();
        long[] remainingEu = new long[1];
        ClientTest.scenario(helper)
            .awaitServer("all exchanger circuits attached", () -> helper.assertTrue(rig.output().getNetwork() != null
                && line.ports().blueOutput().getNetwork() != null, "Both outlets ready"))
            .server("resume cooling with both charges held", () -> {
                var player = (EntityPlayerMP) world.playerEntities.get(0);
                player.playerNetServerHandler.setPlayerLocation(p.x() + 0.5, p.y() - 1, p.z() - 2.5, 0, 0);
                var tile = rig.pump().getBaseMetaTileEntity();
                tile.setOwnerUuid(player.getUniqueID());
                tile.setOwnerName(player.getCommandSenderName());
                line.start(helper);
            })
            .awaitServer("power is available", () -> line.supply(helper))
            .awaitServer("cold target is unattainable", () -> line.requireBlocked(helper))
            .server("inspect the held charge", () -> {
                remainingEu[0] = rig.supply().getStoredEU();
                rig.pump().onRightclick(rig.pump().getBaseMetaTileEntity(), (EntityPlayerMP) world.playerEntities.get(0));
            })
            .awaitClient("terminal received the failed target", c -> {
                var mc = Minecraft.getMinecraft();
                if (!(mc.currentScreen instanceof com.cleanroommc.modularui.screen.GuiContainerWrapper)
                    || !(mc.theWorld.getTileEntity(p.x(), p.y(), p.z()) instanceof IGregTechTileEntity tile)
                    || !(tile.getMetaTileEntity() instanceof MTEHeatPump pump) || !pump.isHeatExchangerMode()
                    || pump.isConfiguringHotStream() || pump.getUniversalValue() != 480
                    || !pump.getCheckRecipeResult().equals(gregtech.api.recipe.check.SimpleCheckRecipeResult.ofFailure("vfn_paired_temperature_limit"))
                    || !net.minecraft.util.StatCollector.canTranslate("GT5U.gui.text.recipe_result.vfn_paired_temperature_limit"))
                    throw new AssertionError("Waiting for the synchronized cold-side target and thermal limit");
            }).capture("held-cold-stream")
            .click(panelControl(null, "heatPumpSettings"))
            .click(panelControl("hxModeSettings", "heatPumpSetpoint"))
            .key(Keyboard.KEY_END, '\0')
            .key(Keyboard.KEY_BACK, '\b').key(Keyboard.KEY_BACK, '\b').key(Keyboard.KEY_BACK, '\b')
            .key(Keyboard.KEY_BACK, '\b').key(Keyboard.KEY_BACK, '\b').key(Keyboard.KEY_BACK, '\b')
            .key(Keyboard.KEY_4, '4').key(Keyboard.KEY_9, '9').key(Keyboard.KEY_9, '9')
            .key(Keyboard.KEY_RETURN, '\r')
            .awaitServer("revised target reached server and released the charge", () -> {
                helper.assertEquals(499, (int) rig.pump().getUniversalValue(), "Cold target updated");
                line.requireDelivered(helper, remainingEu[0]);
            }).awaitClient("clear the held-charge warning", c -> {
                var tile = (IGregTechTileEntity) Minecraft.getMinecraft().theWorld.getTileEntity(p.x(), p.y(), p.z());
                var pump = (MTEHeatPump) tile.getMetaTileEntity();
                if (pump.getUniversalValue() != 499 || pump.getCheckRecipeResult().equals(
                    gregtech.api.recipe.check.SimpleCheckRecipeResult.ofFailure("vfn_paired_temperature_limit")))
                    throw new AssertionError("Waiting for the post-discharge terminal state");
            }).capture("revised-exchanger-setting")
            .click(panelControl("hxModeSettings", "heatPumpStream"))
            .click(panelControl("hxModeSettings", "heatPumpPowerMode"))
            .awaitServer("next charge uses the selected stream and power control", () -> helper.assertTrue(
                rig.pump().isConfiguringHotStream() && rig.pump().getOperatingMode() == HeatPumpMode.TARGET_ENERGY,
                "Both button changes reached the server"))
            .server("close the terminal", () -> ((EntityPlayerMP) world.playerEntities.get(0)).closeScreen()).succeed();
    }

    private static ClientTarget panelControl(String panelName, String name) {
        return panelControl(panelName, name, false);
    }

    private static ClientTarget panelControl(String panelName, String name, boolean firstChild) {
        return ClientTarget.of(name, window -> {
            if (!(Minecraft.getMinecraft().currentScreen instanceof GuiContainerWrapper gui)) return null;
            var panels = gui.getScreen().getPanelManager();
            var panel = panelName == null ? panels.getMainPanel() : panels.getOpenPanel(panelName);
            if (panel == null) return null;
            var named = WidgetTree.findFirstWithNameNullable(panel, name);
            if (named == null) return null;
            var widget = firstChild ? named.getChildren().get(0) : named;
            var area = widget.getArea();
            var frame = panel.getArea();
            var bounds = new java.awt.Rectangle(area.x, area.y, area.width, area.height)
                .intersection(new java.awt.Rectangle(frame.x, frame.y, frame.width, frame.height));
            return new ClickTarget(widget, bounds, () -> panels.isBelowMouseInTopPanel(widget));
        });
    }

    @GameTest(template = "radiator", timeoutTicks = 1500)
    public static void exchangerControlsRedTemperature(GameTestHelper helper) {
        var line = HeatPumpTests.coloredRig(helper);
        var rig = line.red();
        var p = helper.absolute("controller");
        var w = helper.getWorld();
        int x = p.x(), y = p.y(), z = p.z();
        var redIn = pipeEnd(w, x - 2, y, z + 1, ForgeDirection.EAST);
        var redOut = pipeEnd(w, x + 2, y, z + 1, ForgeDirection.WEST);
        var blueIn = pipeEnd(w, x - 1, y + 2, z + 1, ForgeDirection.DOWN);
        var blueOut = pipeEnd(w, x + 1, y + 2, z + 1, ForgeDirection.DOWN);
        boolean[] enabled = { false };
        helper.onEachTick("deliver MV", () -> {
            if (enabled[0]) rig.supply().injectEnergyUnits(ForgeDirection.NORTH, 128, 2);
        });
        ClientTest.scenario(helper)
            .server("view both inlet and outlet pairs", () -> {
                w.setBlock(x, y + 2, z - 3, net.minecraft.init.Blocks.stonebrick);
                var player = (EntityPlayerMP) w.playerEntities.get(0);
                player.playerNetServerHandler.setPlayerLocation(x + 0.5, y + 3, z - 2.5, 0, 37);
            })
            .awaitServer("four live colored ports", () -> helper.assertTrue(redIn.getNetwork() != null && redOut.getNetwork() != null
                && blueIn.getNetwork() != null && blueOut.getNetwork() != null && redIn.getNetwork() == rig.input().getNetwork()
                && redOut.getNetwork() == rig.output().getNetwork() && blueIn.getNetwork() == line.blueInput().getNetwork()
                && blueOut.getNetwork() == line.blueOutput().getNetwork(), "Four pipes join their respective hatches"))
            .server("heat red batches to 370 K", () -> {
                rig.charge(helper, 370);
                rig.pump().setHeatExchangerMode(true);
                rig.pump().setConfiguringHotStream(true);
                blueIn.getNetwork().replaceCanonicalState(IFNCanonicalState
                    .fromRaw("water", 20_000_000L, 20_846_869_750_000L));
                enabled[0] = true;
            })
            .withinTicks(1000)
            .awaitServer("conditioned fluid reached both pipes", () -> helper.assertTrue(redOut.getNetwork().getAmountQ() > 0
                && blueOut.getNetwork().getAmountQ() > 0 && Math.abs(rig.pump().getCurrentOutputTemperature() - 370) < 0.001,
                "Two separate outputs after conditioning the red stream"))
            .awaitClient("both top pipes rendered", c -> {
                var client = Minecraft.getMinecraft().theWorld;
                if (!(client.getTileEntity(x - 1, y + 2, z + 1) instanceof gregtech.api.metatileentity.BaseMetaPipeEntity a)
                    || !(client.getTileEntity(x + 1, y + 2, z + 1) instanceof gregtech.api.metatileentity.BaseMetaPipeEntity b)
                    || (a.mConnections & b.mConnections & ForgeDirection.DOWN.flag) == 0)
                    throw new AssertionError("Waiting for both visible blue connections");
            })
            .capture("four-port-exchanger")
            .succeed();
    }

    private static MTEIntegratedFluidPipe pipeEnd(World world, int x, int y, int z, ForgeDirection side) {
        var pipe = (MTEIntegratedFluidPipe) InjectorTests.placeAt(world, x, y, z, (short) 5770, side).getMetaTileEntity();
        pipe.connect(side);
        return pipe;
    }

    @GameTest(template = "water_line", timeoutTicks = 1200)
    public static void creativeWaterLine(GameTestHelper helper) {
        var line = WaterLineTests.place(helper);
        var p = helper.absolute("controller");
        var w = helper.getWorld();
        boolean[] enabled = { false };
        helper.onEachTick("supply the EV heat pump", () -> {
            if (enabled[0]) line.rig().supply().injectEnergyUnits(ForgeDirection.NORTH, 2048, 2);
        });
        ClientTest.scenario(helper)
            .server("look across the complete water installation", () -> {
                w.setBlock(p.x(), p.y() + 3, p.z() - 6, net.minecraft.init.Blocks.stonebrick);
                var player = (EntityPlayerMP) w.playerEntities.get(0);
                player.playerNetServerHandler.setPlayerLocation(p.x() + 0.5, p.y() + 4, p.z() - 5.5, 0, 34);
            })
            .awaitServer("source tank fills from GT++", () -> helper.assertTrue(
                line.sourceTank().drain(ForgeDirection.UNKNOWN, 1, false) != null && line.input().getNetwork() != null
                && line.output().getNetwork() != null, "Cover-fed tank and VFN lines ready"))
            .server("start injection and paid heating", () -> {
                WaterLineTests.attachPump(line.sourceTank(), ForgeDirection.EAST, gregtech.common.covers.modes.TransferMode.EXPORT);
                line.start(helper);
                enabled[0] = true;
            })
            .withinTicks(1000)
            .awaitServer("heated charge reaches the destination line", () -> helper.assertTrue(line.output().getNetwork().getAmountQ() > 0
                && Math.abs(line.rig().pump().getCurrentOutputTemperature() - 310) < 0.001, "Water traveled through the full installation"))
            .awaitClient("both Super Tanks and outlet connection are visible", c -> {
                var world = Minecraft.getMinecraft().theWorld;
                if (!(world.getTileEntity(p.x() - 4, p.y(), p.z() + 1) instanceof IGregTechTileEntity)
                    || !(world.getTileEntity(p.x() + 4, p.y(), p.z() + 1) instanceof IGregTechTileEntity)
                    || !(world.getTileEntity(p.x()+2, p.y(), p.z()+1) instanceof gregtech.api.metatileentity.BaseMetaPipeEntity pipe)
                    || (pipe.mConnections & ForgeDirection.EAST.flag) == 0) throw new AssertionError("Waiting for the two tanks and outlet pipe");
            })
            .capture("creative-water-line")
            .succeed();
    }

    @GameTest(template = "water_line", timeoutTicks = 1400)
    public static void primedLineFillsSuperTank(GameTestHelper helper) {
        var setup = WaterLineTests.commissioning(helper);
        var line = setup.line();
        var p = helper.absolute("controller");
        var w = helper.getWorld();
        boolean[] run = { false };
        helper.onEachTick("power the 400 K batch", () -> {
            var hatch = line.rig().supply();
            if (run[0] && hatch.getEUCapacity() - hatch.getStoredEU() >= 524288) hatch.injectEnergyUnits(ForgeDirection.NORTH, 524288, 1);
        });
        ClientTest.scenario(helper)
            .server("look at the receiving Super Tank", () -> {
                var player = (EntityPlayerMP) w.playerEntities.get(0);
                w.setBlock(p.x() + 6, p.y(), p.z() + 4, net.minecraft.init.Blocks.stonebrick);
                player.playerNetServerHandler.setPlayerLocation(p.x() + 6.2, p.y() + 1, p.z() + 4.5, 150.5f, 31.5f);
                var controller = line.rig().pump().getBaseMetaTileEntity();
                controller.setOwnerUuid(player.getUniqueID());
                controller.setOwnerName(player.getCommandSenderName());
            })
            .awaitServer("single source primed both sides", () -> setup.requireFilled(helper))
            .server("remove the seven temporary pipes", () -> setup.removeBridge(helper))
            .awaitServer("independent circuits", () -> setup.requireSeparated(helper))
            .server("start heating", () -> {
                helper.assertTrue(line.outputTank().drain(ForgeDirection.UNKNOWN, 1, false) == null, "Destination still empty after priming");
                line.start(helper, 400, 250);
                run[0] = true;
            })
            .withinTicks(1000)
            .awaitServer("external water delivered", () -> {
                var water = line.outputTank().drain(ForgeDirection.UNKNOWN, Integer.MAX_VALUE, false);
                helper.assertTrue(water != null && water.getFluid() == FluidRegistry.WATER && water.amount >= 100,
                    "GT tank holds exported liquid water");
            }).awaitClient("read actual stored water", c -> {
                var hit = Minecraft.getMinecraft().objectMouseOver;
                if (hit == null || hit.blockX != p.x() + 4 || hit.blockY != p.y() || hit.blockZ != p.z() + 1)
                    throw new AssertionError("Waiting for the receiving tank under the crosshair");
                var tag = mcp.mobius.waila.api.impl.DataAccessorCommon.instance.getNBTData();
                var water = FluidStack.loadFluidStackFromNBT(tag.getCompoundTag("mFluid"));
                if (water == null || water.getFluid() != FluidRegistry.WATER || water.amount < 100)
                    throw new AssertionError("Waiting for the client tank reading");
            }).capture("exported-liquid-water")
            .awaitServer("residual pressure blocks the next refill", () -> helper.assertEquals(
                gregtech.api.recipe.check.SimpleCheckRecipeResult.ofFailure("vfn_inlet_head"),
                line.rig().pump().getCheckRecipeResult(), "Controller identified the inlet head"))
            .server("open the stalled heat pump", () -> {
                var player = (EntityPlayerMP) w.playerEntities.get(0);
                player.playerNetServerHandler.setPlayerLocation(p.x()+0.5, p.y()-1, p.z()-2.5, 0, 0);
                line.rig().pump().onRightclick(line.rig().pump().getBaseMetaTileEntity(), player);
            })
            .awaitClient("translated pressure result synchronized to the terminal", c -> {
                var mc = Minecraft.getMinecraft();
                if (!(mc.currentScreen instanceof net.minecraft.client.gui.inventory.GuiContainer)
                    || !(mc.theWorld.getTileEntity(p.x(), p.y(), p.z()) instanceof IGregTechTileEntity tile)
                    || !(tile.getMetaTileEntity() instanceof MTEHeatPump pump)
                    || !pump.getCheckRecipeResult().equals(gregtech.api.recipe.check.SimpleCheckRecipeResult.ofFailure("vfn_inlet_head"))
                    || !net.minecraft.util.StatCollector.canTranslate("GT5U.gui.text.recipe_result.vfn_inlet_head"))
                    throw new AssertionError("Waiting for the localized inlet-pressure result");
            }).capture("inlet-pressure-terminal")
            .server("close the terminal", () -> ((EntityPlayerMP) w.playerEntities.get(0)).closeScreen())
            .succeed();
    }

    @GameTest(template = "water_line", timeoutTicks = 2600)
    public static void secondStrokeWithFeedStage(GameTestHelper helper) {
        var stages = WaterLineTests.placeFeedStage(helper);
        var setup = stages.setup();
        var line = setup.line();
        var p = helper.absolute("controller");
        var w = helper.getWorld();
        boolean[] main = { false }, auxiliary = { false };
        int[] firstTank = { 0 };
        long[] firstCharge = { 0 };
        helper.onEachTick("power the installed stages", () -> {
            if (main[0]) WaterLineTests.feedUV(line.rig().supply());
            if (auxiliary[0]) WaterLineTests.feedUV(stages.auxiliary().supply());
        });
        ClientTest.scenario(helper)
            .server("view both controllers from the receiving end", () -> {
                w.setBlock(p.x() + 5, p.y() + 3, p.z() + 3, net.minecraft.init.Blocks.stonebrick);
                var player = (EntityPlayerMP) w.playerEntities.get(0);
                player.playerNetServerHandler.setPlayerLocation(p.x() + 5.5, p.y() + 4, p.z() + 3.5, 134, 36);
            })
            .awaitServer("both circuits primed", () -> setup.requireFilled(helper))
            .server("disconnect the startup bridge", () -> setup.removeBridge(helper))
            .awaitServer("feed and delivery lines separated", () -> setup.requireSeparated(helper, 31200))
            .server("enable the main controller", () -> {
                line.start(helper, 400, 250);
                main[0] = true;
            })
            .withinTicks(800)
            .awaitServer("all exportable water from the first charge reached the tank", () -> helper.assertTrue(stages.settledExport()
                && line.rig().pump().getCheckRecipeResult().equals(gregtech.api.recipe.check.SimpleCheckRecipeResult.ofFailure("vfn_inlet_head")),
                "First export complete and intake stalled"))
            .server("enable the feed heater", () -> {
                firstTank[0] = stages.storedWater();
                firstCharge[0] = line.rig().inventory().getLong("pumpMicroKg");
                stages.startAuxiliary(helper);
                auxiliary[0] = true;
            })
            .withinTicks(1800)
            .awaitServer("main machine admitted new feed", () -> helper.assertTrue(line.rig().inventory().getLong("pumpMicroKg") > firstCharge[0],
                "Main charge grew beyond the retained heel"))
            .awaitServer("second export finished", () -> helper.assertTrue(stages.settledExport() && stages.storedWater() > firstTank[0],
                "Separate second tank delivery"))
            .awaitClient("both machines rendered", c -> {
                var client = Minecraft.getMinecraft().theWorld;
                if (!(client.getTileEntity(p.x(), p.y(), p.z()) instanceof IGregTechTileEntity)
                    || !(client.getTileEntity(p.x(), p.y(), p.z() - 5) instanceof IGregTechTileEntity))
                    throw new AssertionError("Waiting for the two physical controllers");
            }).capture("two-paid-stages")
            .server("inspect the receiving tank", () -> {
                w.setBlock(p.x() + 6, p.y(), p.z() + 4, net.minecraft.init.Blocks.stonebrick);
                var player = (EntityPlayerMP) w.playerEntities.get(0);
                player.playerNetServerHandler.setPlayerLocation(p.x() + 6.2, p.y() + 1, p.z() + 4.5, 150.5f, 31.5f);
            })
            .awaitClient("second delivery visible in WAILA", c -> {
                var hit = Minecraft.getMinecraft().objectMouseOver;
                if (hit == null || hit.blockX != p.x() + 4 || hit.blockY != p.y() || hit.blockZ != p.z() + 1)
                    throw new AssertionError("Waiting for the output tank");
                var tag = mcp.mobius.waila.api.impl.DataAccessorCommon.instance.getNBTData();
                var water = FluidStack.loadFluidStackFromNBT(tag.getCompoundTag("mFluid"));
                if (water == null || water.getFluid() != FluidRegistry.WATER || water.amount <= firstTank[0])
                    throw new AssertionError("Waiting for the second water delivery");
            }).capture("second-tank-delivery")
            .succeed();
    }

    private static void inspectHeatPump(GameTestHelper helper, HeatPumpMode mode) {
        boolean metered = mode == HeatPumpMode.TARGET_ENERGY;
        var rig = HeatPumpTests.place(helper, (short) (metered ? 44 : 42));
        var p = helper.absolute("controller");
        var world = helper.getWorld();
        boolean[] power = { false };
        helper.onEachTick("supply the energy hatch", () -> {
            if (power[0]) rig.supply().injectEnergyUnits(ForgeDirection.NORTH, metered ? 2048 : 128, 1);
        });
        ClientTest.scenario(helper)
            .server("inspect the heat pump", () -> {
                var player = (EntityPlayerMP) world.playerEntities.get(0);
                rig.pump().getBaseMetaTileEntity().setOwnerUuid(player.getUniqueID());
                rig.pump().getBaseMetaTileEntity().setOwnerName(player.getCommandSenderName());
                player.playerNetServerHandler.setPlayerLocation(p.x() + 0.5, p.y() - 1, p.z() - 2.5, 0, 0);
            })
            .awaitServer("hatches are connected", () -> helper.assertTrue(rig.input().getNetwork() != null
                && rig.output().getNetwork() != null, "Input and output available"))
            .server("start heating the charge", () -> {
                rig.charge(helper, 400);
                if (metered) {
                    rig.pump().setOperatingMode(mode);
                    rig.pump().setTargetEnergy(2000);
                    rig.pump().setFluidAmountPerOperation(1000);
                }
                power[0] = true;
            })
            .withinTicks(500)
            .awaitServer("pump is spending EU", () -> helper.assertTrue(rig.pump().getTotalEnergyCost() > 0, "Paid working tick"))
            .server("open the working controller", () -> rig.pump().onRightclick(rig.pump().getBaseMetaTileEntity(),
                (EntityPlayerMP) world.playerEntities.get(0)))
            .awaitClient("normal-mode controller screen", c -> {
                var mc = Minecraft.getMinecraft();
                if (!(mc.currentScreen instanceof net.minecraft.client.gui.inventory.GuiContainer))
                    throw new AssertionError("Waiting for the heat pump GUI");
                if (!(mc.theWorld.getTileEntity(p.x(), p.y(), p.z()) instanceof IGregTechTileEntity tile)
                    || !(tile.getMetaTileEntity() instanceof MTEHeatPump pump)
                    || pump.getOperatingMode() != mode || pump.getUniversalValue() != (metered ? 2000 : 400))
                    throw new AssertionError("Waiting for the server's operating mode and setpoint");
            })
            .capture(metered ? "power-mode-gui" : "heat-pump-gui")
            .server("close the controller", () -> ((EntityPlayerMP) world.playerEntities.get(0)).closeScreen())
            .withinTicks(1000)
            .awaitServer("paid output arrives", () -> helper.assertTrue(rig.output().getNetwork().getAmountQ() > 0, "Heat pump discharged"))
            .succeed();
    }

    @GameTest(template = "radiator", timeoutTicks = 1500)
    public static void warmedWaterInSuperTank(GameTestHelper helper) {
        var line = RadiatorTests.placeWaterReturn(helper);
        var p = helper.absolute("controller");
        var world = helper.getWorld();
        world.setBlock(p.x() + 3, p.y() + 1, p.z() + 7, net.minecraft.init.Blocks.stonebrick);
        long[] baseline = new long[1];
        ClientTest.scenario(helper)
            .server("look across the tank toward the radiator", () -> {
                var player = (EntityPlayerMP) world.playerEntities.get(0);
                player.playerNetServerHandler.setPlayerLocation(p.x() + 3.5, p.y() + 2, p.z() + 7.2, 119.5f, 42.2f);
            })
            .awaitServer("radiator and injector circuits connected", () -> {
                var inlet = line.rig().inlet().getNetwork();
                helper.assertTrue(inlet != null && inlet == line.rig().outlet().getNetwork() && line.receiver().getNetwork() != null,
                    "Shared radiator circuit and separate return pipe");
            })
            .server("restore the last part of the heating cycle", () -> baseline[0] = line.charge(helper))
            .withinTicks(1000)
            .awaitServer("water warmed enough for export", () -> line.requireWarmWater(helper, baseline[0]))
            .server("install the first pump", line::pumpIntoTank)
            .awaitServer("the GT tank contains water", () -> line.requireTank(helper))
            .awaitClient("inspect the filled Super Tank", c -> {
                var hit = Minecraft.getMinecraft().objectMouseOver;
                if (hit == null || hit.blockX != p.x() || hit.blockY != p.y() || hit.blockZ != p.z() + 5)
                    throw new AssertionError("Waiting for the Super Tank under the crosshair");
                var tag = mcp.mobius.waila.api.impl.DataAccessorCommon.instance.getNBTData();
                var visible = FluidStack.loadFluidStackFromNBT(tag.getCompoundTag("mFluid"));
                if (visible == null || visible.getFluid() != FluidRegistry.WATER || visible.amount != 1)
                    throw new AssertionError("Waiting for the tank's synchronized water reading");
            })
            .capture("tank-reading")
            .server("show the return circuit", () -> {
                world.setBlock(p.x() + 3, p.y() + 3, p.z() + 7, net.minecraft.init.Blocks.stonebrick);
                var player = (EntityPlayerMP) world.playerEntities.get(0);
                player.playerNetServerHandler.setPlayerLocation(p.x() + 3.5, p.y() + 4, p.z() + 7.4, 146, 43);
            })
            .awaitClient("view the complete radiator installation", c -> {
                if (Minecraft.getMinecraft().thePlayer.posY < p.y() + 3.5) throw new AssertionError("Waiting for the overview");
            })
            .capture("warmed-water")
            .server("fit the return pump", line::pumpIntoNetwork)
            .awaitServer("water returned to VFN", () -> helper.assertEquals(1_000_000L, line.receiver().getNetwork().getAmountQ(), "One kg reimported"))
            .succeed();
    }

    @GameTest(template = "radiator", timeoutTicks = 1500)
    public static void radiatorTimedCycleFromSettings(GameTestHelper helper) {
        inspectRadiatorSettings(helper, false);
    }

    @GameTest(template = "radiator", timeoutTicks = 1700)
    public static void radiatorTargetCycleFromSettings(GameTestHelper helper) {
        inspectRadiatorSettings(helper, true);
    }

    private static void inspectRadiatorSettings(GameTestHelper helper, boolean thermostat) {
        var rig = RadiatorTests.place(helper);
        var p = helper.absolute("controller");
        var world = helper.getWorld();
        long[] start = { -1 }, end = { -1 }, discharged = { 0 };
        int[] firstRemaining = { 0 };
        helper.onEachTick("observe the first radiator cycle", () -> {
            if (end[0] >= 0) return;
            var saved = rig.inventory();
            if (saved.getLong("loopMicroKg") == 0) return;
            if (saved.getBoolean("loopOutputReady")) end[0] = world.getTotalWorldTime();
            else if (start[0] < 0) {
                start[0] = world.getTotalWorldTime();
                firstRemaining[0] = saved.getInteger("loopTicks");
            }
        });
        var controls = ClientTest.scenario(helper)
            .awaitServer("separate circuits ready", () -> helper.assertTrue(rig.inlet().getNetwork() != null && rig.outlet().getNetwork() != null,
                "Both ports attached"))
            .server("inspect the uncharged radiator", () -> {
                var player = (EntityPlayerMP) world.playerEntities.get(0);
                player.playerNetServerHandler.setPlayerLocation(p.x() + 0.5, p.y() - 1, p.z() - 2.5, 0, 0);
                var tile = rig.machine().getBaseMetaTileEntity();
                tile.setOwnerUuid(player.getUniqueID());
                tile.setOwnerName(player.getCommandSenderName());
            })
            .awaitClient("radiator controller visible", c -> {
                if (!(Minecraft.getMinecraft().theWorld.getTileEntity(p.x(), p.y(), p.z()) instanceof IGregTechTileEntity tile)
                    || !(tile.getMetaTileEntity() instanceof gregtech.common.tileentities.machines.multi.MTERadiator))
                    throw new AssertionError("Waiting for the radiator tile on the client");
            })
            .server("open radiator terminal", () -> rig.machine().onRightclick(rig.machine().getBaseMetaTileEntity(),
                (EntityPlayerMP) world.playerEntities.get(0)))
            .click(panelControl(null, "radiatorSettingsButton"));
        if (!thermostat) controls.click(panelControl("radiatorSettings", "radiatorTarget"))
            .awaitServer("first switch to thermostat", () -> helper.assertEquals(1, rig.machine().getMachineMode(), "Target mode selected"));
        controls.click(panelControl("radiatorSettings", thermostat ? "radiatorTarget" : "radiatorTimed"))
            .click(panelControl("radiatorSettings", thermostat ? "radiatorTemperature" : "radiatorTicks"))
            .key(Keyboard.KEY_END, '\0');
        for (int i = 0; i < 6; i++) controls.key(Keyboard.KEY_BACK, '\b');
        if (thermostat) controls.key(Keyboard.KEY_3, '3').key(Keyboard.KEY_5, '5').key(Keyboard.KEY_0, '0');
        else controls.key(Keyboard.KEY_8, '8').key(Keyboard.KEY_0, '0');
        controls.key(Keyboard.KEY_RETURN, '\r')
            .awaitServer("settings reached the controller", () -> {
                helper.assertEquals(thermostat ? 1 : 0, rig.machine().getMachineMode(), "Selected mode");
                if (thermostat) helper.assertEquals(350, (int) rig.machine().getTargetTemperatureSetting(), "Submitted 350 K");
                else helper.assertEquals(80, rig.machine().getConstantOperationTicks(), "Submitted 80 ticks");
            })
            .server("charge the real loop with the outlet held under pressure", () -> {
                rig.outlet().getNetwork().replaceCanonicalState(IFNCanonicalState.fromRaw("water", 10_000_000, 22_037_935_000_000L));
                rig.charge(helper);
            })
            .withinTicks(1200)
            .awaitServer("hold the processed batch", () -> {
                helper.assertTrue(start[0] >= 0 && end[0] > start[0], "First batch completed with a real residence time");
                if (thermostat) {
                    helper.assertEquals(-1, firstRemaining[0], "Thermostat governs the cycle");
                    helper.assertTrue(Math.abs(rig.machine().getOutputTemperature() - 350) < 0.01, "Held charge reached 350 K");
                } else {
                    helper.assertTrue(firstRemaining[0] >= 79 && firstRemaining[0] <= 80, "First observed tick of 80");
                    helper.assertEquals((long) firstRemaining[0], end[0] - start[0], "One countdown per server tick");
                }
                helper.assertEquals(10_000_000L, rig.outlet().getNetwork().getAmountQ(), "Backpressure still holds the batch");
            }).capture(thermostat ? "radiator-thermostat-settings" : "radiator-timed-settings")
            .server("release outlet pressure", () -> rig.outlet().getNetwork().clearFluid())
            .awaitServer("discharge the measured cycle", () -> {
                helper.assertTrue(rig.outlet().getNetwork().getAmountQ() > 0, "Cooled fluid arrived");
                helper.assertEquals(10_000_000L, rig.inlet().getNetwork().getAmountQ() + rig.heldMass()
                    + rig.outlet().getNetwork().getAmountQ(), "No mass lost or duplicated");
            })
            .server("hold the delivered reading", () -> {
                rig.machine().stopMachine(gregtech.api.util.shutdown.ShutDownReasonRegistry.NONE);
                discharged[0] = rig.outlet().getNetwork().getAmountQ();
                helper.assertTrue(discharged[0] > 0 && discharged[0] < 1_000_000, "A fractional kilogram reached the receiver");
                helper.recordDiagnostic("Receiver: " + discharged[0] + " microkg in a 10000 L base chamber");
            }).awaitClient("read fractional outlet mass alongside pipe volume", c -> {
                var screen = ((GuiContainerWrapper) Minecraft.getMinecraft().currentScreen).getScreen();
                var values = screen.getSyncManager().getPanelSyncManager(screen.getMainPanel().getName());
                var mass = values.findSyncHandler("outputMicroKg", com.cleanroommc.modularui.value.sync.LongSyncValue.class);
                var volume = values.findSyncHandler("outputBaseLiters", com.cleanroommc.modularui.value.sync.LongSyncValue.class);
                if (mass.getLongValue() != discharged[0] || volume.getLongValue() != 10000)
                    throw new AssertionError("Waiting for the exact outlet mass and rigid volume");
            })
            .server("close radiator settings", () -> ((EntityPlayerMP) world.playerEntities.get(0)).closeScreen()).succeed();
    }

    @GameTest(template = "water_line", timeoutTicks = 1500)
    public static void heatPumpFeedsRadiator(GameTestHelper helper) {
        var line = RadiatorTests.placeHeatedLine(helper);
        var p = helper.absolute("controller");
        var world = helper.getWorld();
        world.setBlock(p.x() + 2, p.y() + 2, p.z() - 5, net.minecraft.init.Blocks.stonebrick);
        helper.onEachTick("power the heater", () -> WaterLineTests.feedUV(line.heater().supply()));
        ClientTest.scenario(helper)
            .server("inspect both multiblocks", () -> {
                var player = (EntityPlayerMP) world.playerEntities.get(0);
                player.playerNetServerHandler.setPlayerLocation(p.x() + 2.5, p.y() + 3, p.z() - 4.5, 22, 33);
            })
            .awaitServer("shared hot line connected", () -> helper.assertTrue(line.radiator().outlet().getNetwork() != null
                && line.bridge().getNetwork() != null && line.bridge().getNetwork() == line.radiator().inlet().getNetwork()
                && line.bridge().getNetwork() == line.heater().output().getNetwork(), "Bridge between machines"))
            .server("heat the first charge", () -> line.start(helper, 1000, 350))
            .withinTicks(1000)
            .awaitServer("the hot charge arrived", () -> helper.assertTrue(line.radiator().inlet().getNetwork().getAmountQ() > 0,
                "Paid discharge reached the second machine"))
            .server("isolate the heated charge", () -> line.splitAfterDelivery(helper, 350))
            .awaitServer("pipe separated", () -> helper.assertTrue(line.radiator().inlet().getNetwork() != line.heater().output().getNetwork(),
                "Radiator owns its share"))
            .server("open the radiator inlet", () -> line.radiator().machine().getBaseMetaTileEntity().enableWorking())
            .withinTicks(850)
            .awaitServer("radiator discharged", () -> helper.assertTrue(line.radiator().outlet().getNetwork().getAmountQ() > 0,
                "Heated fluid passed through the real loop"))
            .server("restore the pipe connection", () -> line.bridge().connect(ForgeDirection.EAST))
            .awaitServer("joined pipe", () -> helper.assertTrue(line.heater().output().getNetwork() == line.radiator().inlet().getNetwork(),
                "Network restored before capture"))
            .awaitClient("both controllers rendered", c -> {
                var client = Minecraft.getMinecraft().theWorld;
                if (!(client.getTileEntity(p.x() - 2, p.y(), p.z()) instanceof IGregTechTileEntity)
                    || !(client.getTileEntity(p.x() + 2, p.y(), p.z()) instanceof IGregTechTileEntity)) {
                    throw new AssertionError("Waiting for both controllers");
                }
            }).capture("heated-line").succeed();
    }

    @GameTest(template = "radiator", timeoutTicks = 1200)
    public static void workingRadiator(GameTestHelper helper) {
        var rig = RadiatorTests.place(helper);
        var p = helper.absolute("controller");
        var world = helper.getWorld();
        world.setBlock(p.x() + 3, p.y() + 1, p.z() - 3, net.minecraft.init.Blocks.stonebrick);
        ClientTest.scenario(helper)
            .server("inspect the radiator and its roof loop", () -> {
                var player = (EntityPlayerMP) world.playerEntities.get(0);
                player.playerNetServerHandler.setPlayerLocation(p.x() + 3.5, p.y() + 2, p.z() - 2.5, 45, 35);
            })
            .awaitServer("both fluid hatches connected", () -> helper.assertTrue(rig.inlet().getNetwork() != null
                && rig.outlet().getNetwork() != null, "Connected networks"))
            .server("charge the radiator", () -> rig.charge(helper))
            .withinTicks(800)
            .awaitServer("radiator discharged", () -> helper.assertTrue(rig.outlet().getNetwork().getAmountQ() > 0, "Fluid delivered"))
            .awaitClient("radiator is visible", c -> {
                var hit = Minecraft.getMinecraft().objectMouseOver;
                if (hit == null || Math.abs(hit.blockX - p.x()) > 1 || Math.abs(hit.blockY - p.y()) > 1
                    || hit.blockZ != p.z()) throw new AssertionError("Waiting for radiator shell");
            })
            .capture("working-radiator")
            .succeed();
    }

    @GameTest(template = "turbine", timeoutTicks = 10000)
    public static void vaporOutletUnderLoad(GameTestHelper helper) {
        var start = VaporOutletTests.coldStart(helper);
        var plant = start.plant();
        var powered = new boolean[1];
        var p = helper.absolute("source");
        helper.getWorld().setBlock(p.x() - 3, p.y() - 1, p.z() + 1, net.minecraft.init.Blocks.stonebrick);
        helper.getWorld().setBlock(p.x() + 5, p.y() + 2, p.z() - 6, net.minecraft.init.Blocks.stonebrick);
        helper.onEachTick("power the drum and steam line", () -> {
            if (powered[0]) {
                start.powerDrum();
                start.powerReheater();
            }
        });
        ClientTest.scenario(helper)
            .server("inspect the vapor outlet beside the feedwater line", () -> {
                var viewer = (EntityPlayerMP) helper.getWorld().playerEntities.get(0);
                viewer.playerNetServerHandler.setPlayerLocation(p.x() - 2.5, p.y(), p.z() + 1.5, -90f, 20.5f);
            })
            .awaitServer("three chambers formed", () -> plant.ready(helper))
            .server("charge the cold drum and enable heat", () -> {
                start.fill(helper);
                powered[0] = true;
            })
            .withinTicks(9300)
            .awaitServer("HV generated while water is still boiling", () ->
                helper.assertTrue(plant.rig().load().getStoredEU() >= 512 && plant.feed().getNetwork().getQuality() < 0.1,
                    "Liquid remains upstream of a working turbine"))
            .withinTicks(350)
            .awaitClient("read the vapor draw", c -> {
                var hit = Minecraft.getMinecraft().objectMouseOver;
                var tag = mcp.mobius.waila.api.impl.DataAccessorCommon.instance.getNBTData();
                if (hit == null || hit.blockX != p.x() || hit.blockY != p.y() || hit.blockZ != p.z() + 1
                    || tag.getDouble("vaporGramsPerTick") <= 0) throw new AssertionError("Waiting for measured vapor flow");
            })
            .capture("vapor-outlet")
            .server("view the boiler and turbine together", () -> {
                var viewer = (EntityPlayerMP) helper.getWorld().playerEntities.get(0);
                viewer.playerNetServerHandler.setPlayerLocation(p.x() + 5.5, p.y() + 3, p.z() - 5.5, 45f, 28f);
            })
            .awaitClient("overview position received", c -> {
                var viewer = Minecraft.getMinecraft().thePlayer;
                if (Math.abs(viewer.posX - (p.x() + 5.5)) > 0.1) throw new AssertionError("Waiting for overview");
            })
            .capture("cold-water-plant")
            .succeed();
    }

    @GameTest(template = "turbine", timeoutTicks = 600)
    public static void steamTurbineUnderLoad(GameTestHelper helper) {
        var rig = TurbineTests.place(helper);
        TurbineTests.installDiluteSteamRotor(rig.turbine());
        var tile = rig.turbine();
        ClientTest.scenario(helper)
            .server("inspect the loaded turbine", () -> {
                var player = (EntityPlayerMP) helper.getWorld().playerEntities.get(0);
                int x = tile.getXCoord(), y = tile.getYCoord(), z = tile.getZCoord();
                helper.getWorld().setBlock(x + 1, y, z - 3, net.minecraft.init.Blocks.stonebrick);
                player.playerNetServerHandler.setPlayerLocation(x + 1.5, y + 1, z - 2.5, 18.4f, 34);
            })
            .awaitServer("isolated steam and exhaust pipes", () -> rig.steam().ready(helper))
            .server("charge both sides for the MV dynamo", () -> {
                helper.assertEquals(200, rig.steam().cold().fill(FluidRegistry.getFluidStack("steam", 200), true), "Exhaust primed");
                helper.assertEquals(600, rig.steam().hot().fill(FluidRegistry.getFluidStack("steam", 600), true), "Steam admitted");
            })
            .withinTicks(300)
            .awaitServer("energy hatch received power", () -> helper.assertTrue(rig.load().getStoredEU() >= 128, "MV delivered: "
                + ((gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidSteamTurbine) tile.getMetaTileEntity()).getCheckRecipeResult().getDisplayString()
                + ", dynamo " + rig.dynamo().getStoredEU() + " EU"))
            .awaitClient("read the working turbine", c -> {
                var hit = Minecraft.getMinecraft().objectMouseOver;
                var tag = mcp.mobius.waila.api.impl.DataAccessorCommon.instance.getNBTData();
                if (hit == null || hit.blockX != tile.getXCoord() || hit.blockY != tile.getYCoord() || hit.blockZ != tile.getZCoord()
                    || tag.getDouble("shaftEuPerTick") <= 0 || !tag.hasKey("vfnLastWetExpansion"))
                    throw new AssertionError("Waiting for shaft power and measured expansion");
            })
            .capture("steam-turbine")
            .succeed();
    }

    @GameTest(template = "radiator", timeoutTicks = 700)
    public static void inspectReinforcedPumpDischarge(GameTestHelper h) {
        var plant = PressureLineTests.placeDensePump(h);
        var p = h.absolute("controller");
        var player = (EntityPlayerMP) h.getWorld().playerEntities.get(0);
        boolean[] running = new boolean[1];
        h.onEachTick("power the dense return", () -> {
            if (running[0] && plant.rig().supply().getStoredEU() < 122880)
                plant.rig().supply().injectEnergyUnits(ForgeDirection.NORTH, 2048, 1);
        });
        ClientTest.scenario(h).awaitServer("rated pump formed", () -> plant.ready(h))
            .server("start the dense return and view the discharge hatch", () -> {
                plant.start();
                running[0] = true;
                h.getWorld().setBlock(p.x()+3, p.y(), p.z(), net.minecraft.init.Blocks.stonebrick);
                player.playerNetServerHandler.setPlayerLocation(p.x()+3.5, p.y()+1, p.z()+0.5, 68.2f, 47.6f);
            }).withinTicks(500).awaitServer("one kilogram reached the high side", () ->
                h.assertEquals(26_788_852L, plant.high().getNetwork().getAmountQ(), "Whole dense return"))
            .awaitClient("read the operating pressure and wall rating", c -> {
                var hit = Minecraft.getMinecraft().objectMouseOver;
                var tag = mcp.mobius.waila.api.impl.DataAccessorCommon.instance.getNBTData();
                if (hit == null || hit.blockX != p.x()+1 || hit.blockY != p.y() || hit.blockZ != p.z()+1
                    || tag.getFloat("maxPressureBar") != 350 || tag.getFloat("pressure") <= 330)
                    throw new AssertionError("Waiting for the reinforced discharge hatch");
            }).capture("reinforced-discharge").succeed();
    }

    @GameTest(template = "radiator", timeoutTicks = 500)
    public static void inspectDenseTurbineUnderLoad(GameTestHelper h) {
        var rig = PressureLineTests.turbine(h);
        var target = h.absolute("controller");
        ClientTest.scenario(h).awaitServer("two separate rated pipes", () -> rig.ready(h))
            .server("face the turbine controller", () -> {
                var player = (EntityPlayerMP) h.getWorld().playerEntities.get(0);
                h.getWorld().setBlock(target.x()+2, target.y()+1, target.z()-3, net.minecraft.init.Blocks.stonebrick);
                player.playerNetServerHandler.setPlayerLocation(target.x()+2.5, target.y()+2, target.z()-2.5, 38.6f, 44.3f);
            }).awaitClient("turbine controller in view", c -> {
                var hit = Minecraft.getMinecraft().objectMouseOver;
                if (hit == null || hit.blockX != target.x() || hit.blockY != target.y() || hit.blockZ != target.z())
                    throw new AssertionError("Waiting for the front face");
            }).server("start dense expansion", () -> rig.charge(h))
            .withinTicks(360).awaitClient("read operating rotor", c -> {
                var mc = Minecraft.getMinecraft();
                var hit = mc.objectMouseOver;
                var data = mcp.mobius.waila.api.impl.DataAccessorCommon.instance.getNBTData();
                if (data == null || hit == null || hit.blockX != target.x() || hit.blockY != target.y() || hit.blockZ != target.z()
                    || data.getDouble("shaftEuPerTick") <= 0 || !data.hasKey("vfnLastWetExpansion") || data.getBoolean("vfnLastWetExpansion"))
                    throw new AssertionError("Waiting for dry expansion under load");
                var pipe = mc.theWorld.getTileEntity(target.x() - 2, target.y(), target.z()+1);
                if (!(pipe instanceof gregtech.api.metatileentity.BaseMetaPipeEntity gt) || (gt.mConnections & ForgeDirection.EAST.flag) == 0)
                    throw new AssertionError("Waiting for the pressure pipe connection");
            }).capture("dense-expansion-under-load")
            .awaitServer("load powered through three transformers", () -> h.assertTrue(rig.load().getStoredEU() >= 1024, "Delivered MV power"))
            .succeed();
    }

    @GameTest(template = "water_line", timeoutTicks = 700)
    public static void inspectTurbinePoweredFeedPump(GameTestHelper h) {
        var loop = PressureLineTests.denseLoop(h);
        var p = h.absolute("controller");
        long[] hotMass = new long[1];
        ClientTest.scenario(h).awaitServer("both multiblocks formed", () -> loop.ready(h))
            .server("start loaded turbine without external electricity", () -> {
                h.getWorld().setBlock(p.x()+5, p.y()+3, p.z()+4, net.minecraft.init.Blocks.stonebrick);
                var player = (EntityPlayerMP)h.getWorld().playerEntities.get(0);
                player.playerNetServerHandler.setPlayerLocation(p.x()+5.5, p.y()+4, p.z()+4.5, 132, 36);
                loop.startExpansion();
            }).withinTicks(480).awaitServer("pump receives turbine electricity", () -> h.assertTrue(loop.power().getStoredEU() >= 98304,
                "At least three complete LuV packets"))
            .server("return dense water on stored shaft power", () -> {
                loop.turbine().controller().disableWorking();
                hotMass[0] = loop.hot().getNetwork().getAmountQ();
                loop.pump().enableWorking();
            }).awaitServer("dense return completed", () -> h.assertEquals(hotMass[0] + 1_000_000, loop.hot().getNetwork().getAmountQ(),
                "One kilogram back in the hot vessel"))
            .awaitClient("linked machines visible", c -> {
                var mc = Minecraft.getMinecraft();
                if (Math.abs(mc.thePlayer.posX - (p.x()+5.5)) > 0.1
                    || !(mc.theWorld.getTileEntity(p.x(),p.y(),p.z()+2) instanceof IGregTechTileEntity a) || a.getMetaTileID() != 5777
                    || !(mc.theWorld.getTileEntity(p.x(),p.y(),p.z()-3) instanceof IGregTechTileEntity b)
                    || b.getMetaTileID() != gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidLiquidPump.HIGH_PRESSURE_ID)
                    throw new AssertionError("Waiting for turbine and return pump");
            }).capture("turbine-powered-dense-return").succeed();
    }

    @GameTest(template = "water_line", timeoutTicks = 7200)
    public static void inspectDenseGenerationPlant(GameTestHelper h) {
        var rig = new DensePlantTests.GovernedPlant(h);
        var plant = rig.plant;
        var p = h.absolute("controller");
        ClientTest.scenario(h).withinTicks(240).awaitServer("two primed circuits", () -> plant.ready(h))
            .server("start the plant and frame both loops", () -> {
                h.getWorld().setBlock(p.x() + 5, p.y() + 3, p.z() + 4, net.minecraft.init.Blocks.stonebrick);
                var player = (EntityPlayerMP) h.getWorld().playerEntities.get(0);
                player.playerNetServerHandler.setPlayerLocation(p.x() + 5.5, p.y() + 4, p.z() + 4.5, 132, 35);
                plant.start(h);
            }).withinTicks(6800).awaitServer("external power after pump reserve recovery", () -> h.assertTrue(rig.reopened
                && plant.loadEu() >= rig.loadAtReopen + 2048 && plant.pumpStrokes >= 2, "Reserve restored, external export resumed"))
            .awaitClient("pump, turbine and pressure lines are visible", c -> {
                var mc = Minecraft.getMinecraft();
                if (mc.thePlayer.posY < p.y() + 3.5 || !(mc.theWorld.getTileEntity(p.x() - 2, p.y(), p.z()) instanceof gregtech.api.metatileentity.BaseMetaPipeEntity pipe)
                    || (pipe.mConnections & ForgeDirection.NORTH.flag) == 0) throw new AssertionError("Waiting for connected plant");
            }).capture("dense-generation-plant").succeed();
    }

    @GameTest(template = "regulator", timeoutTicks = 500)
    public static void electricallyHeatedPipe(GameTestHelper helper) {
        var line = AmbientTests.place(helper);
        InjectorTests.placeHatch(helper, "valve", (short) 5776, ForgeDirection.WEST);
        helper.assertTrue(line.pipe().connect(ForgeDirection.WEST) > 0, "Rear thermal connection");
        var transformer = InjectorTests.placeHatch(helper, "source",
            (short) gregtech.api.enums.MetaTileEntityIDs.transformer_HV_MV.ID, ForgeDirection.WEST);
        var viewer = helper.absolute("viewer");
        var boiler = helper.absolute("valve");
        var pipe = helper.absolute("outlet");
        double[] initialVapor = new double[1];
        helper.onEachTick("supply the transformer's HV input", () -> transformer.injectEnergyUnits(ForgeDirection.WEST, 512, 1));
        ClientTest.scenario(helper)
            .server("look at the MV boiler", () -> {
                var player = (EntityPlayerMP) helper.getWorld().playerEntities.get(0);
                player.playerNetServerHandler.setPlayerLocation(viewer.x() - 1.0, viewer.y(), viewer.z() + 0.5, -26.5f, 18);
            })
            .awaitServer("water line connected", () -> line.ready(helper))
            .server("inject one kilogram of feedwater", () -> {
                helper.assertEquals(1, line.inlet().fill(new net.minecraftforge.fluids.FluidStack(
                    net.minecraftforge.fluids.FluidRegistry.WATER, 1), true), "Water accepted");
                initialVapor[0] = line.pipe().getNetwork().getQuality();
            })
            .awaitClient("transformer delivers rated heat", c -> {
                var hit = Minecraft.getMinecraft().objectMouseOver;
                var tag = mcp.mobius.waila.api.impl.DataAccessorCommon.instance.getNBTData();
                if (hit == null || hit.blockX != boiler.x() || hit.blockY != boiler.y() || hit.blockZ != boiler.z()
                    || tag.getDouble("boilerHeatEuPerTick") != 128) throw new AssertionError("Waiting for 128 EU/t");
            })
            .capture("powered-boiler")
            .server("inspect the heated pipe", () -> {
                var player = (EntityPlayerMP) helper.getWorld().playerEntities.get(0);
                player.playerNetServerHandler.setPlayerLocation(viewer.x() + 0.5, viewer.y(), viewer.z() + 0.5, -18.4f, 19);
            })
            .awaitClient("water boils without a change in mass", c -> {
                requirePipeMass(pipe.x(), pipe.y(), pipe.z(), 1_000_000L);
                var tag = mcp.mobius.waila.api.impl.DataAccessorCommon.instance.getNBTData();
                if (!"TWO_PHASE".equals(tag.getString("phase")) || tag.getDouble("quality") <= initialVapor[0]) {
                    throw new AssertionError("Waiting for more vapor in the water line");
                }
            })
            .capture("boiling-water")
            .succeed();
    }

    @GameTest(template = "regulator", timeoutTicks = 400)
    public static void passiveHeatExchanger(GameTestHelper helper) {
        var bench = ExchangerTests.place(helper, false);
        var viewer = helper.absolute("viewer");
        var target = helper.absolute("valve");
        ClientTest.scenario(helper)
            .server("look at the heat exchanger", () -> {
                var player = (EntityPlayerMP) helper.getWorld().playerEntities.get(0);
                player.playerNetServerHandler.setPlayerLocation(viewer.x() - 1.0, viewer.y(), viewer.z() + 0.5, -26.5f, 18);
            })
            .awaitServer("separate inventories connected", () -> bench.ready(helper))
            .server("charge steam and feedwater", () -> bench.charge(helper))
            .awaitClient("inspect heat delivered to the feedwater circuit", c -> {
                var mc = Minecraft.getMinecraft();
                var hit = mc.objectMouseOver;
                var tag = mcp.mobius.waila.api.impl.DataAccessorCommon.instance.getNBTData();
                if (hit == null || hit.blockX != target.x() || hit.blockY != target.y() || hit.blockZ != target.z()
                    || tag.getDouble("heatToFrontEuPerTick") <= 1) {
                    throw new AssertionError("Waiting for measured heat flow through the exchanger");
                }
            })
            .capture("operating-exchanger")
            .succeed();
    }

    @GameTest(template = "regulator", timeoutTicks = 1800)
    public static void hydrogenCooledWater(GameTestHelper helper) {
        var bench = ExchangerTests.place(helper, false);
        var viewer = helper.absolute("viewer");
        var target = helper.absolute("outlet");
        ClientTest.scenario(helper).server("look at the water side of the exchanger", () -> {
            var player = (EntityPlayerMP) helper.getWorld().playerEntities.get(0);
            player.playerNetServerHandler.setPlayerLocation(viewer.x() + 0.5, viewer.y(), viewer.z() + 0.5, -18.4f, 19);
        }).awaitServer("separate loops connected", () -> bench.ready(helper))
            .server("charge the hydrogen and water circuits", () -> ExchangerTests.chargeCryogenicExchanger(helper, bench))
            .withinTicks(1500).awaitClient("water-side ice visible on the pipe", c -> {
                requirePipeMass(target.x(), target.y(), target.z(), 1_000_000L);
                var tag = mcp.mobius.waila.api.impl.DataAccessorCommon.instance.getNBTData();
                if (!"water".equals(tag.getString("substanceId")) || tag.getDouble("solidFraction") <= 0.02)
                    throw new AssertionError("Waiting for water to freeze");
            }).capture("hydrogen-cooled-water").succeed();
    }

    @GameTest(template = "regulator", timeoutTicks = 1600)
    public static void coolingSteam(GameTestHelper helper) {
        var line = AmbientTests.place(helper);
        var viewer = helper.absolute("viewer");
        var target = helper.absolute("outlet");
        ClientTest.scenario(helper)
            .server("look at the cooling pipe", () -> {
                var player = (EntityPlayerMP) helper.getWorld().playerEntities.get(0);
                player.playerNetServerHandler.setPlayerLocation(viewer.x() + 0.5, viewer.y(), viewer.z() + 0.5, -18.4f, 19);
            })
            .awaitClient("render the pipe connection", c -> {
                var tile = Minecraft.getMinecraft().theWorld.getTileEntity(target.x(), target.y(), target.z());
                if (!(tile instanceof gregtech.api.metatileentity.BaseMetaPipeEntity pipe)
                    || (pipe.mConnections & ForgeDirection.EAST.flag) == 0) throw new AssertionError("Pipe not connected yet");
            })
            .awaitServer("pipe joined", () -> line.ready(helper))
            .server("inject steam", () -> line.charge(helper))
            .awaitClient("show hot vapor and measured heat loss", c -> {
                requirePipeMass(target.x(), target.y(), target.z(), 6250);
                var tag = mcp.mobius.waila.api.impl.DataAccessorCommon.instance.getNBTData();
                if (tag.getDouble("quality") != 1 || tag.getDouble("ambientEuPerTick") >= 0 || tag.getFloat("temperature") < 390) {
                    throw new AssertionError("Waiting for warm vapor cooling to ambient");
                }
            })
            .capture("hot-vapor")
            .withinTicks(1300)
            .awaitServer("condensation started", () -> line.condensed(helper))
            .awaitClient("show partial condensate", c -> {
                requirePipeMass(target.x(), target.y(), target.z(), 6250);
                var tag = mcp.mobius.waila.api.impl.DataAccessorCommon.instance.getNBTData();
                if (!"TWO_PHASE".equals(tag.getString("phase")) || tag.getDouble("quality") >= 0.99
                    || tag.getDouble("ambientEuPerTick") >= 0) {
                    throw new AssertionError("Waiting for the two-phase reading");
                }
            })
            .capture("partial-condensate")
            .succeed();
    }

    @GameTest(template = "radiator", timeoutTicks = 900)
    public static void exchangeTurbineRotorInGui(GameTestHelper helper) {
        var p = helper.absolute("controller");
        var rig = HeatPumpTests.placeSteelShell(helper.getWorld(), p.x(), p.y(), p.z(), (short) 5777, (short) 33);
        var feed = (MTEIntegratedFluidInjectorHatch) InjectorTests.placeAt(helper.getWorld(), p.x() - 2, p.y(), p.z() + 1,
            (short) 5773, ForgeDirection.EAST).getMetaTileEntity();
        var tile = rig.controller();
        var turbine = (gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidSteamTurbine) tile.getMetaTileEntity();
        TurbineTests.installDiluteSteamRotor(tile);
        var player = (EntityPlayerMP) helper.getWorld().playerEntities.get(0);
        var installed = turbine.getControllerSlot().copy();
        int[] rotorSlot = { -1 };
        var slot = ClientTarget.of("installed turbine rotor", window -> {
            if (!(Minecraft.getMinecraft().currentScreen instanceof GuiContainerWrapper gui)) return null;
            var panels = gui.getScreen().getPanelManager();
            var widgets = new java.util.ArrayDeque<com.cleanroommc.modularui.api.widget.IWidget>();
            widgets.add(panels.getMainPanel());
            while (!widgets.isEmpty()) {
                var w = widgets.remove();
                if (w instanceof com.cleanroommc.modularui.widgets.slot.ItemSlot item) {
                    var gtSlot = item.getSlot();
                    if (rotorSlot[0] < 0 && net.minecraft.item.ItemStack.areItemStacksEqual(gtSlot.getStack(), installed))
                        rotorSlot[0] = gtSlot.slotNumber;
                    if (rotorSlot[0] >= 0 && gtSlot.slotNumber == rotorSlot[0]) {
                        var a = item.getArea();
                        return new ClickTarget(item, new java.awt.Rectangle(a.x, a.y, a.width, a.height), () -> panels.isBelowMouseInTopPanel(item));
                    }
                }
                widgets.addAll(w.getChildren());
            }
            return null;
        });
        ClientTest.scenario(helper).server("stand beside the rotor housing", () -> {
            helper.getWorld().setBlock(p.x() + 3, p.y() + 1, p.z() - 4, net.minecraft.init.Blocks.stonebrick);
            player.playerNetServerHandler.setPlayerLocation(p.x() + 3.5, p.y() + 2, p.z() - 3.5, 38, 29);
            player.inventory.currentItem = 8;
            tile.setOwnerUuid(player.getUniqueID());
            tile.setOwnerName(player.getCommandSenderName());
        }).awaitServer("turbine connected", () -> {
            helper.assertTrue(turbine.checkStructure(true, tile), "Turbine structure formed");
            helper.assertTrue(tile.getTimer() > 5, "GT tile initialized");
        }).awaitClient("rotor housing present on client", c -> {
            var found = Minecraft.getMinecraft().theWorld.getTileEntity(p.x(), p.y(), p.z());
            if (!(found instanceof IGregTechTileEntity gt) || gt.getMetaTileID() != 5777 || gt.getMetaTileEntity() == null)
                throw new AssertionError("Waiting for the turbine tile");
        })
            .capture("formed-steam-turbine")
            .server("open the rotor slot", () -> helper.assertTrue(((gregtech.api.metatileentity.BaseMetaTileEntity) tile)
                .onRightclick(player, ForgeDirection.NORTH, 0.5f, 0.5f, 0), "Rotor housing opens without a tool"))
            .click(slot).awaitServer("rotor removed by player", () -> helper.assertTrue(turbine.getControllerSlot() == null
                && net.minecraft.item.ItemStack.areItemStacksEqual(installed, player.inventory.getItemStack()), "Rotor on cursor"))
            .awaitClient("player holds the removed rotor", c -> {
                var held = Minecraft.getMinecraft().thePlayer.inventory.getItemStack();
                if (!net.minecraft.item.ItemStack.areItemStacksEqual(installed, held)) throw new AssertionError("Rotor not on client cursor");
            }).rightClick(slot).awaitServer("rotor reinstalled", () -> helper.assertTrue(player.inventory.getItemStack() == null
                && net.minecraft.item.ItemStack.areItemStacksEqual(installed, turbine.getControllerSlot()),
                "Same rotor and durability: slot=" + turbine.getControllerSlot() + ", cursor=" + player.inventory.getItemStack()))
            .click(panelControl(null, "turbineSettingsButton"))
            .click(panelControl("turbineSettings", "admission")).key(Keyboard.KEY_END, '\0')
            .key(Keyboard.KEY_BACK, '\b').key(Keyboard.KEY_BACK, '\b').key(Keyboard.KEY_BACK, '\b')
            .key(Keyboard.KEY_7, '7').key(Keyboard.KEY_5, '5').key(Keyboard.KEY_RETURN, '\r')
            .click(panelControl("turbineSettings", "inletFloor")).key(Keyboard.KEY_END, '\0').key(Keyboard.KEY_BACK, '\b')
            .key(Keyboard.KEY_5, '5').key(Keyboard.KEY_0, '0').key(Keyboard.KEY_RETURN, '\r')
            .awaitServer("controls submitted", () -> {
                helper.assertEquals(75, turbine.admissionPercent(), "75 percent admission");
                helper.assertEquals(50, turbine.minimumInletCbar(), "Half bar inlet start");
            }).server("charge the inlet at the selected opening", () -> {
                turbine.fixAllIssues();
                helper.assertEquals(600, feed.fill(FluidRegistry.getFluidStack("steam", 600), true), "Live GT steam inlet");
                tile.enableWorking();
            }).withinTicks(300).awaitServer("operating limit under the selected throttle", () -> {
                helper.assertEquals(gregtech.api.recipe.check.SimpleCheckRecipeResult.ofFailure("vfn_turbine_small_intake"), turbine.getCheckRecipeResult(), "75% stroke cannot hold one portion");
                helper.assertTrue(turbine.inletBar() > 0.8f && turbine.outletBar() == 0, "Separate pressure readings");
            }).awaitClient("terminal reports the admission limit", c -> {
                var clientTile = (IGregTechTileEntity) Minecraft.getMinecraft().theWorld.getTileEntity(p.x(), p.y(), p.z());
                var clientMulti = (gregtech.api.metatileentity.implementations.MTEMultiBlockBase) clientTile.getMetaTileEntity();
                if (!gregtech.api.recipe.check.SimpleCheckRecipeResult.ofFailure("vfn_turbine_small_intake").equals(clientMulti.getCheckRecipeResult()))
                    throw new AssertionError("Waiting for the server's intake diagnosis");
                var opening = net.minecraft.util.StatCollector.translateToLocalFormatted("GT5U.vfn.turbine.opening");
                if (opening.startsWith("Format error") || opening.equals("GT5U.vfn.turbine.opening"))
                    throw new AssertionError("Missing admission label: " + opening);
            }).capture("turbine-admission").server("close turbine panel", player::closeScreen).succeed();
    }

    @GameTest(template = "radiator", timeoutTicks = 650)
    public static void configureCondensateReturn(GameTestHelper h) {
        var rig = LiquidPumpTests.place(h);
        var tile = rig.controller();
        var pump = (gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidLiquidPump) tile.getMetaTileEntity();
        var p = h.absolute("controller");
        var player = (EntityPlayerMP) h.getWorld().playerEntities.get(0);
        var controls = ClientTest.scenario(h)
            .awaitServer("pump formed", () -> h.assertTrue(pump.checkStructure(true, tile), "All four hatches installed"))
            .server("view the pump", () -> {
                h.getWorld().setBlock(p.x() + 3, p.y() + 1, p.z() - 4, net.minecraft.init.Blocks.stonebrick);
                player.playerNetServerHandler.setPlayerLocation(p.x() + 3.5, p.y() + 2, p.z() - 3.5, 38, 29);
                tile.setOwnerUuid(player.getUniqueID());
                tile.setOwnerName(player.getCommandSenderName());
            }).awaitClient("controller visible", c -> {
                if (!(Minecraft.getMinecraft().theWorld.getTileEntity(p.x(), p.y(), p.z()) instanceof IGregTechTileEntity t)
                    || t.getMetaTileID() != 5779) throw new AssertionError("Waiting for the pump controller");
            }).capture("liquid-pump-structure")
            .server("open pump", () -> pump.onRightclick(tile, player))
            .click(panelControl(null, "pumpSettingsButton"))
            .click(panelControl("pumpSettings", "pumpTarget")).key(Keyboard.KEY_END, '\0');
        for (int i = 0; i < 5; i++) controls.key(Keyboard.KEY_BACK, '\b');
        controls.key(Keyboard.KEY_2, '2').key(Keyboard.KEY_5, '5').key(Keyboard.KEY_0, '0').key(Keyboard.KEY_RETURN, '\r')
            .awaitServer("outlet setpoint", () -> h.assertEquals(250, pump.target(), "2.50 bar"))
            .click(panelControl("pumpSettings", "pumpFloor")).key(Keyboard.KEY_END, '\0').key(Keyboard.KEY_BACK, '\b')
            .key(Keyboard.KEY_5, '5').key(Keyboard.KEY_0, '0').key(Keyboard.KEY_RETURN, '\r')
            .awaitServer("inlet protection", () -> h.assertEquals(50, pump.inletFloor(), "0.50 bar"))
            .click(panelControl("pumpSettings", "pumpInterval")).key(Keyboard.KEY_END, '\0').key(Keyboard.KEY_BACK, '\b')
            .key(Keyboard.KEY_3, '3').key(Keyboard.KEY_RETURN, '\r')
            .awaitServer("pressure stroke period", () -> h.assertEquals(3, pump.interval(), "60 ticks at the pressure setpoint"))
            .capture("pump-pressure-settings")
            .click(panelControl("pumpSettings", "pumpMode"))
            .awaitServer("pressure rise", () -> h.assertEquals(1, pump.mode().ordinal(), "Differential control"))
            .click(panelControl("pumpSettings", "pumpMode"))
            .awaitServer("delivery mode", () -> h.assertEquals(2, pump.mode().ordinal(), "Volume control"))
            .click(panelControl("pumpSettings", "pumpIntake"))
            .awaitServer("gas intake", () -> h.assertTrue(pump.gasIntake(), "Controller selects the vapor port"))
            .capture("pump-gas-intake")
            .click(panelControl("pumpSettings", "pumpIntake"))
            .awaitServer("condensate intake", () -> h.assertTrue(!pump.gasIntake(), "Liquid intake restored"))
            .server("close the panel", player::closeScreen).succeed();
    }

    @GameTest(template = "regulator", timeoutTicks = 600)
    public static void configureRegulatorByGui(GameTestHelper helper) {
        var bench = InjectorTests.placeRegulator(helper);
        var valve = bench.valve();
        var pos = helper.absolute("valve");
        var world = helper.getWorld();
        var controls = ClientTest.scenario(helper)
            .awaitServer("regulator attached", () -> bench.requireNetworks(helper))
            .server("stand next to the regulator", () -> {
                var player = (EntityPlayerMP) world.playerEntities.get(0);
                player.playerNetServerHandler.setPlayerLocation(pos.x() + 0.5, pos.y(), pos.z() - 1.5, 0, 28);
                var tile = valve.getBaseMetaTileEntity();
                tile.setOwnerUuid(player.getUniqueID());
                tile.setOwnerName(player.getCommandSenderName());
            }).awaitClient("regulator visible to the player", c -> {
                var tile = Minecraft.getMinecraft().theWorld.getTileEntity(pos.x(), pos.y(), pos.z());
                if (!(tile instanceof IGregTechTileEntity gt) || gt.getMetaTileEntity() == null)
                    throw new AssertionError("Waiting for client tile");
            }).server("right-click the valve without a tool", () -> {
                var player = (EntityPlayerMP) world.playerEntities.get(0);
                player.inventory.currentItem = 8;
                var tile = (gregtech.api.metatileentity.BaseMetaTileEntity) valve.getBaseMetaTileEntity();
                helper.assertTrue(tile.onRightclick(player, ForgeDirection.NORTH, 0.5f, 0.5f, 0),
                    "Right-click on the regulator opens its controls");
            }).awaitClient("initial valve settings arrived", c -> {
                if (!(Minecraft.getMinecraft().currentScreen instanceof com.gtnewhorizons.modularui.common.internal.wrapper.ModularGui gui))
                    throw new AssertionError("Waiting for the valve panel");
                var values = new java.util.ArrayList<String>();
                for (var widget : gui.getContext().getMainWindow().getChildren()) {
                    if (widget instanceof com.gtnewhorizons.modularui.common.widget.textfield.TextFieldWidget field)
                        values.add(field.getText());
                }
                if (!values.equals(java.util.Arrays.asList("8", "0", "100"))) throw new AssertionError("Valve settings: " + values);
            });
        int[][] digits = { { 2, 0, 0 }, { 5 }, { 5, 0 } };
        for (int index = 0; index < digits.length; index++) {
            final int field = index;
            controls.rightClick(ClientTarget.of("regulator field " + field, window -> {
                if (!(Minecraft.getMinecraft().currentScreen instanceof com.gtnewhorizons.modularui.common.internal.wrapper.ModularGui gui)) return null;
                int seen = 0;
                for (var widget : gui.getContext().getMainWindow().getChildren()) {
                    if (widget instanceof com.gtnewhorizons.modularui.common.widget.textfield.TextFieldWidget && seen++ == field) {
                        return new ClickTarget(widget, widget.getRenderAbsoluteRectangle(), widget::isHovering);
                    }
                }
                return null;
            }));
            for (int digit : digits[index]) {
                controls.key(digit == 0 ? Keyboard.KEY_0 : Keyboard.KEY_1 + digit - 1, (char) ('0' + digit));
            }
            controls.key(Keyboard.KEY_RETURN, '\r');
        }
        controls.awaitServer("all three edits reach the valve", () -> {
            helper.assertEquals(200, valve.getSetpointPressureCentibar(), "2 bar");
            helper.assertEquals(5, valve.getOvershootToleranceCentibar(), "0.05 bar tolerance");
            helper.assertEquals(50, valve.getMaxPacketAmountL(), "50 L per contact");
        }).capture("two-bar-regulator-settings")
            .server("close the valve", () -> ((EntityPlayerMP) world.playerEntities.get(0)).closeScreen()).succeed();
    }

    @GameTest(template = "regulator", timeoutTicks = 700)
    public static void pressureCoverSettingsDriveRedstone(GameTestHelper helper) {
        var world = helper.getWorld();
        var pos = helper.absolute("outlet");
        var tile = InjectorTests.placeHatch(helper, "outlet", (short) 5770, ForgeDirection.NORTH);
        var cover = (gregtech.common.covers.CoverIFNPressureDetector) gregtech.api.covers.CoverRegistry.buildCover(
            gregtech.api.enums.ItemList.Cover_IFNPressureDetector.get(1), ForgeDirection.NORTH, tile);
        tile.attachCover(cover);
        java.util.function.IntSupplier signal = () -> world.getBlock(pos.x(), pos.y(), pos.z())
            .isProvidingWeakPower(world, pos.x(), pos.y(), pos.z(), ForgeDirection.SOUTH.ordinal());
        var player = (EntityPlayerMP) world.playerEntities.get(0);
        var controls = ClientTest.scenario(helper)
            .server("stand in front of the pressure cover", () -> {
                player.playerNetServerHandler.setPlayerLocation(pos.x() + 0.5, pos.y(), pos.z() - 1.5, 0, 28);
                tile.setOwnerUuid(player.getUniqueID());
                tile.setOwnerName(player.getCommandSenderName());
            }).awaitServer("binary vacuum reading", () -> helper.assertEquals(15, signal.getAsInt(), "Vacuum is inside 0 to 1 bar"))
            .awaitClient("covered pipe synchronized", c -> {
                var gt = Minecraft.getMinecraft().theWorld.getTileEntity(pos.x(), pos.y(), pos.z());
                if (!(gt instanceof IGregTechTileEntity pipe) || !pipe.hasCoverAtSide(ForgeDirection.NORTH))
                    throw new AssertionError("Waiting for the installed cover");
            }).server("sneak right-click the covered face", () -> {
                player.inventory.currentItem = 8;
                player.setSneaking(true);
                try {
                    helper.assertTrue(((gregtech.api.metatileentity.BaseMetaPipeEntity) tile)
                        .onRightclick(player, ForgeDirection.NORTH, 0.5f, 0.5f, 0), "Detector opens from its installed face");
                } finally {
                    player.setSneaking(false);
                }
            });
        for (var field : new String[] { "ifn_detector_min", "ifn_detector_max" }) {
            controls.click(panelControl(null, field, true)).key(Keyboard.KEY_END, '\0');
            for (int i = 0; i < 6; i++) controls.key(Keyboard.KEY_BACK, '\b');
            if ("ifn_detector_min".equals(field)) controls.key(Keyboard.KEY_MINUS, '-');
            controls.key(Keyboard.KEY_2, '2').key(Keyboard.KEY_RETURN, '\r');
        }
        controls.awaitServer("signed range stored on the pipe", () -> {
            helper.assertEquals(-2.0, cover.getMinValue(), "Lower bound in bar");
            helper.assertEquals(2.0, cover.getMaxValue(), "Upper bound in bar");
        }).click(panelControl(null, "ifn_detector_mode", true))
            .awaitServer("linear absolute signal", () -> helper.assertEquals(8, signal.getAsInt(), "0 bar midway between -2 and 2"))
            .click(panelControl(null, "ifn_detector_source_mode", true))
            .awaitServer("linear differential signal", () -> helper.assertEquals(4, signal.getAsInt(), "Vacuum is one bar below reference"))
            .capture("pressure-cover-settings")
            .server("close detector", player::closeScreen).succeed();
    }

    public static final class ExtractorChat {
        private String reading = "";

        @cpw.mods.fml.common.eventhandler.SubscribeEvent
        public void received(net.minecraftforge.client.event.ClientChatReceivedEvent event) {
            String text = event.message.getUnformattedText();
            if (text.startsWith("Network: ")) reading = text;
        }
    }

    @GameTest(template = "regulator", timeoutTicks = 400)
    public static void inspectFractionalSteam(GameTestHelper helper) {
        inspectMeteredFluid(helper, "steam", "water", 1, 6250, false, "fractional-steam-inventory");
    }

    @GameTest(template = "regulator", timeoutTicks = 400)
    public static void inspectSteamRotorFeed(GameTestHelper helper) {
        inspectMeteredFluid(helper, "steam", "water", 6, 37500, false, "steam-rotor-feed");
    }

    @GameTest(template = "regulator", timeoutTicks = 400)
    public static void inspectOxygen(GameTestHelper helper) {
        inspectMeteredFluid(helper, "oxygen", "oxygen", 1, 888_888, true, "oxygen-inventory");
    }

    @GameTest(template = "regulator", timeoutTicks = 400)
    public static void inspectAmbientCoolant(GameTestHelper helper) {
        inspectMeteredFluid(helper, "supercoolant", "supercoolant", 9000, 9_000_000_000L, true, "ambient-supercoolant");
    }

    @GameTest(template = "regulator", timeoutTicks = 400)
    public static void inspectHelium(GameTestHelper helper) {
        inspectMeteredFluid(helper, "helium", "helium", 1, 111_111, true, "helium-inventory");
    }

    @GameTest(template = "regulator", timeoutTicks = 400)
    public static void inspectSupercriticalPressureLine(GameTestHelper h) {
        inspectRatedCharge(h, "water", PressureLineTests.MICRO_KG, 220.64, "reinforced-supercritical-line", line -> line.charge(h));
    }

    @GameTest(template = "regulator", timeoutTicks = 400)
    public static void inspectDenseHydrogen(GameTestHelper h) {
        // Prepared 30 kg/m3 normal hydrogen isochore with the 40 K reference energy
        inspectRatedCharge(h, "hydrogen", 9000000L, 25, "reinforced-hydrogen-line", line ->
            line.first().getNetwork().replaceCanonicalState(IFNCanonicalState.fromRaw("hydrogen", 9000000L, 2841264270000L)));
    }

    @GameTest(template = "regulator", timeoutTicks = 400)
    public static void inspectDenseOxygen(GameTestHelper h) {
        // Prepared 300 kg/m3 oxygen isochore, with the same reference energy as the chamber cycle
        inspectRatedCharge(h, "oxygen", 90000000L, 60, "reinforced-oxygen-line", line ->
            line.first().getNetwork().replaceCanonicalState(IFNCanonicalState.fromRaw("oxygen", 90000000L, 17116884900000L)));
    }

    @GameTest(template = "regulator", timeoutTicks = 400)
    public static void inspectDenseNitrogen(GameTestHelper h) {
        // A prepared 200 kg/m3 isochore from the wet-to-supercritical nitrogen reference test
        inspectRatedCharge(h, "nitrogen", 60000000L, 40, "reinforced-nitrogen-line", line ->
            line.first().getNetwork().replaceCanonicalState(IFNCanonicalState.fromRaw("nitrogen", 60000000L, 10739130000000L)));
    }

    @GameTest(template = "regulator", timeoutTicks = 400)
    public static void inspectDenseHelium(GameTestHelper h) {
        inspectRatedCharge(h, "helium", 3 * PressureLineTests.HELIUM_MICRO_KG_PER_SECTION, 11.9,
            "reinforced-helium-line", line -> PressureLineTests.chargeDenseHelium(line.first()));
    }

    @GameTest(template = "radiator", timeoutTicks = 1500)
    public static void inspectHeliumLiquefier(GameTestHelper h) {
        var rig = HeatPumpTests.placeLiquefier(h);
        var p = h.absolute("controller");
        ClientTest.scenario(h).awaitServer("heat pump and both manifolds formed", () -> rig.ready(h))
            .server("view the controller and receiver", () -> {
                h.getWorld().setBlock(p.x() + 2, p.y() + 3, p.z() - 3, net.minecraft.init.Blocks.stonebrick);
                var player = (EntityPlayerMP) h.getWorld().playerEntities.get(0);
                player.playerNetServerHandler.setPlayerLocation(p.x() + 2.5, p.y() + 4, p.z() - 2.5, 18, 34);
            }).awaitClient("controller and both pipe banks rendered", c -> {
                var mc = Minecraft.getMinecraft();
                if (Math.abs(mc.thePlayer.posX - (p.x() + 2.5)) > 0.1
                    || !(mc.theWorld.getTileEntity(p.x() - 2, p.y(), p.z() + 1) instanceof gregtech.api.metatileentity.BaseMetaPipeEntity feed)
                    || (feed.mConnections & ForgeDirection.EAST.flag) == 0
                    || !(mc.theWorld.getTileEntity(p.x() + 2, p.y(), p.z() + 1) instanceof gregtech.api.metatileentity.BaseMetaPipeEntity receiver)
                    || (receiver.mConnections & ForgeDirection.WEST.flag) == 0)
                    throw new AssertionError("Waiting for the connected manifolds");
            }).server("start paid cooling from warm gas", () -> rig.start(h))
            .withinTicks(1200).awaitServer("liquid present in the receiver", () -> rig.checkLiquid(h))
            .capture("helium-liquefier").succeed();
    }

    private static void inspectRatedCharge(GameTestHelper h, String substance, long mass, double minimumBar, String capture,
        java.util.function.Consumer<PressureLineTests.Line> charge) {
        var line = PressureLineTests.place(h, false);
        var viewer = h.absolute("viewer");
        var outlet = h.absolute("outlet");
        var middle = h.absolute("valve");
        ClientTest.scenario(h).awaitServer("reinforced line connected", () -> line.ready(h))
            .server("charge and inspect the pressure line", () -> {
                charge.accept(line);
                var player = (EntityPlayerMP) h.getWorld().playerEntities.get(0);
                player.playerNetServerHandler.setPlayerLocation(viewer.x() + 0.5, viewer.y(), viewer.z() + 0.5, -18.4f, 19);
            }).awaitClient("read the rated line", c -> {
                requirePipeMass(outlet.x(), outlet.y(), outlet.z(), mass);
                var joined = Minecraft.getMinecraft().theWorld.getTileEntity(middle.x(), middle.y(), middle.z());
                int ends = ForgeDirection.WEST.flag | ForgeDirection.EAST.flag;
                if (!(joined instanceof gregtech.api.metatileentity.BaseMetaPipeEntity pipe) || (pipe.mConnections & ends) != ends)
                    throw new AssertionError("Waiting for the connected pipe geometry");
                var tag = mcp.mobius.waila.api.impl.DataAccessorCommon.instance.getNBTData();
                if (!substance.equals(tag.getString("substanceId")) || !"SUPERCRITICAL".equals(tag.getString("phase"))
                    || tag.getFloat("maxPressureBar") != 350 || tag.getFloat("pressure") < minimumBar)
                    throw new AssertionError("Waiting for supercritical " + substance + " and installed rating");
            }).capture(capture).succeed();
    }

    private static void inspectMeteredFluid(GameTestHelper helper, String forgeName, String substance, int units,
        long microKg, boolean storage, String capture) {
        var world = helper.getWorld();
        var viewer = helper.absolute("viewer");
        var target = helper.absolute("outlet");
        var injector = (MTEIntegratedFluidInjectorHatch) InjectorTests.placeHatch(helper, "valve", (short) 5773, ForgeDirection.EAST)
            .getMetaTileEntity();
        var pipe = (MTEIntegratedFluidPipe) InjectorTests.placeHatch(helper, "outlet", (short) 5770, ForgeDirection.EAST).getMetaTileEntity();
        var extractor = (gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidExtractor)
            InjectorTests.placeHatch(helper, "cold", (short) 32032, ForgeDirection.WEST).getMetaTileEntity();
        pipe.connect(ForgeDirection.WEST);
        pipe.connect(ForgeDirection.EAST);
        if (storage) {
            InjectorTests.placeAt(world, target.x(), target.y(), target.z() + 1, (short) 5771, ForgeDirection.NORTH);
            pipe.connect(ForgeDirection.SOUTH);
        }
        long capacity = storage ? 20_100 : 100;
        var chat = new ExtractorChat();
        ClientTest.scenario(helper)
            .afterTest(c -> net.minecraftforge.common.MinecraftForge.EVENT_BUS.unregister(chat))
            .client("listen for the extractor reading", c -> net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(chat))
            .awaitServer("meter attached to the pipe", () -> helper.assertTrue(pipe.getNetwork() != null
                && pipe.getNetwork() == injector.getNetwork() && pipe.getNetwork() == extractor.getNetwork(), "Same chamber"))
            .server("inspect the empty circuit", () -> {
                var player = (EntityPlayerMP) world.playerEntities.get(0);
                player.playerNetServerHandler.setPlayerLocation(viewer.x() + 0.5, viewer.y(), viewer.z() + 0.5, -18.4f, 19);
                extractor.onRightclick(extractor.getBaseMetaTileEntity(), player);
            }).awaitClient("vacuum reads empty", c -> {
                if (!chat.reading.contains("Empty") || !chat.reading.contains(capacity + " L")) throw new AssertionError(chat.reading);
            }).server("inject and inspect the metered batch", () -> {
                helper.assertEquals(units, injector.fill(ForgeDirection.WEST, FluidRegistry.getFluidStack(forgeName, units), true), "External units");
                extractor.onRightclick(extractor.getBaseMetaTileEntity(), (EntityPlayerMP) world.playerEntities.get(0));
            }).awaitClient("chat and WAILA agree on the stored mass", c -> {
                requirePipeMass(target.x(), target.y(), target.z(), microKg);
                var tag = mcp.mobius.waila.api.impl.DataAccessorCommon.instance.getNBTData();
                String kg = java.math.BigDecimal.valueOf(microKg, 6).stripTrailingZeros().toPlainString();
                if (!substance.equals(tag.getString("substanceId")) || tag.getLong("totalCapacity") != capacity
                    || !chat.reading.contains(kg + " kg " + substance)
                    || !chat.reading.contains(capacity + " L")) throw new AssertionError(chat.reading);
                if (forgeName.equals("supercoolant") && !(Math.abs(tag.getFloat("temperature") - 300) < 1))
                    throw new AssertionError("Expected local coolant temperature: " + tag.getFloat("temperature"));
                if (forgeName.equals("steam") && tag.getInteger("bulkPortionStrokeL") != (capacity + units - 1) / units)
                    throw new AssertionError("Waiting for the steam intake size in whole liters");
            }).capture(capture).succeed();
    }

    @GameTest(template = "regulator", timeoutTicks = 400)
    public static void regulatedOutlet(GameTestHelper helper) {
        var bench = InjectorTests.placeRegulator(helper);
        var serverPressure = new java.util.concurrent.atomic.AtomicReference<Float>();
        var viewer = helper.absolute("viewer");
        var target = helper.absolute("outlet");
        ClientTest.scenario(helper)
            .server("look at the regulator outlet", () -> {
                var player = (EntityPlayerMP) helper.getWorld().playerEntities.get(0);
                player.playerNetServerHandler.setPlayerLocation(viewer.x() + 0.5, viewer.y(), viewer.z() + 0.5, -18.4f, 19);
            })
            .awaitServer("two separate chambers", () -> bench.requireNetworks(helper))
            .server("charge steam inlet and feedwater outlet", () -> bench.charge(helper))
            .awaitServer("whole steam portions reach the outlet", () -> {
                bench.requireSteamDelivery(helper);
                serverPressure.set(bench.outlet().getNetwork().getPressure());
            })
            .awaitClient("aim at the outlet pipe", c -> {
                var hit = Minecraft.getMinecraft().objectMouseOver;
                if (hit == null || hit.blockX != target.x() || hit.blockY != target.y() || hit.blockZ != target.z()) {
                    throw new AssertionError("Outlet pipe must be under the crosshair");
                }
                float pressure = mcp.mobius.waila.api.impl.DataAccessorCommon.instance.getNBTData().getFloat("pressure");
                if (!(Math.abs(pressure - serverPressure.get()) <= 0.001f && pressure <= 0.08f)) {
                    throw new AssertionError("Waiting for the regulated pressure in WAILA: " + pressure);
                }
            })
            .capture("regulated-outlet")
            .succeed();
    }

    @GameTest(template = "regulator", timeoutTicks = 400)
    public static void brokenSteamLine(GameTestHelper helper) {
        var run = InjectorTests.placePipeRun(helper);
        var viewer = helper.absolute("viewer");
        var target = helper.absolute("outlet");
        var cut = helper.absolute("valve");
        ClientTest.scenario(helper)
            .server("look along the charged line", () -> {
                var player = (EntityPlayerMP) helper.getWorld().playerEntities.get(0);
                player.playerNetServerHandler.setPlayerLocation(viewer.x() + 0.5, viewer.y(), viewer.z() + 0.5, -18.4f, 19);
            })
            .awaitServer("three connected pipes", () -> run.requireConnected(helper))
            .server("fill the line with steam", () -> run.fillSteam(helper))
            .awaitClient("display 75 g in the connected pipe run", c -> {
                requirePipeMass(target.x(), target.y(), target.z(), 75_000);
                var tile = Minecraft.getMinecraft().theWorld.getTileEntity(cut.x(), cut.y(), cut.z());
                int ends = ForgeDirection.WEST.flag | ForgeDirection.EAST.flag;
                if (!(tile instanceof gregtech.api.metatileentity.BaseMetaPipeEntity pipe)
                    || (pipe.mConnections & ends) != ends) {
                    throw new AssertionError("Middle pipe connection geometry has not reached the client");
                }
            })
            .capture("charged-line")
            .server("break the middle pipe", () -> helper.getWorld().setBlockToAir(cut.x(), cut.y(), cut.z()))
            .awaitClient("display 25 g in the detached pipe", c -> {
                if (!Minecraft.getMinecraft().theWorld.isAirBlock(cut.x(), cut.y(), cut.z())) {
                    throw new AssertionError("The gap must reach the client");
                }
                requirePipeMass(target.x(), target.y(), target.z(), 25_000);
            })
            .capture("broken-line")
            .succeed();
    }

    private static void requirePipeMass(int x, int y, int z, long microKg) {
        var hit = Minecraft.getMinecraft().objectMouseOver;
        var tag = mcp.mobius.waila.api.impl.DataAccessorCommon.instance.getNBTData();
        if (hit == null || hit.blockX != x || hit.blockY != y || hit.blockZ != z
            || tag.getLong("substanceAmountQ") != microKg) {
            throw new AssertionError("Waiting for outlet inventory: " + microKg + " microkg");
        }
    }

    @GameTest(template = "injector_bench", timeoutTicks = 400)
    public static void waterIntake(GameTestHelper helper) {
        var viewer = helper.absolute("viewer");
        var inlet = helper.absolute("injector");
        var store = helper.absolute("storage");
        ClientTest.scenario(helper)
            .server("place the water inlet and storage hatch", () -> {
                InjectorTests.placeHatch(helper, "injector", (short) 5773, ForgeDirection.EAST);
                InjectorTests.placeHatch(helper, "storage", (short) 5771, ForgeDirection.WEST);
                var player = (EntityPlayerMP) helper.getWorld().playerEntities.get(0);
                player.playerNetServerHandler.setPlayerLocation(viewer.x() + 0.5, viewer.y(), viewer.z() + 0.5, -26.5f, 18);
            })
            .awaitServer("hatches share an inventory", () -> {
                var a = (IGregTechTileEntity) helper.assertTileEntityPresent(helper.pos("injector"));
                var b = (IGregTechTileEntity) helper.assertTileEntityPresent(helper.pos("storage"));
                var injector = (MTEIntegratedFluidInjectorHatch) a.getMetaTileEntity();
                var storage = (MTEIntegratedFluidInputHatch) b.getMetaTileEntity();
                helper.assertTrue(injector.getNetwork() != null && injector.getNetwork() == storage.getNetwork(),
                    "Both hatches must join the same VFN");
            })
            .server("accept one bucket", () -> {
                var tile = (IGregTechTileEntity) helper.assertTileEntityPresent(helper.pos("injector"));
                var injector = (MTEIntegratedFluidInjectorHatch) tile.getMetaTileEntity();
                helper.assertEquals(1000, injector.fill(ForgeDirection.WEST, new FluidStack(FluidRegistry.WATER, 1000), true),
                    "Injector must accept the full bucket");
                float pressure = injector.getNetwork().getPressure();
                helper.assertTrue(pressure > 0.035f && pressure < 0.037f, "Live inlet must report chamber pressure");
            })
            .awaitClient("synchronize both hatches and camera", c -> {
                var mc = Minecraft.getMinecraft();
                if (!(mc.theWorld.getTileEntity(inlet.x(), inlet.y(), inlet.z()) instanceof IGregTechTileEntity a)
                    || a.getMetaTileID() != 5773
                    || !(mc.theWorld.getTileEntity(store.x(), store.y(), store.z()) instanceof IGregTechTileEntity b)
                    || b.getMetaTileID() != 5771) {
                    throw new AssertionError("Both VFN hatch IDs must reach the client");
                }
                var target = mc.objectMouseOver;
                if (target == null || target.blockX != inlet.x() || target.blockY != inlet.y() || target.blockZ != inlet.z()) {
                    throw new AssertionError("The injector must be under the crosshair");
                }
            })
            .capture("water-intake")
            .succeed();
    }
}
