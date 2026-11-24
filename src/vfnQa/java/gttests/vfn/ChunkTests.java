package gttests.vfn;

import java.util.UUID;

import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidRegistry;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;

import gregtech.api.enums.Mods;
import gregtech.api.metatileentity.implementations.integratedfluid.state.IFNCanonicalState;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidInjectorHatch;
import gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidPipe;

@GameTestHolder(value = Mods.ModIDs.GREG_TECH, templatePrefix = "vfn")
public class ChunkTests {

    @GameTest(template = "injector", timeoutTicks = 1600)
    public static void remotePipeUnloadAndBreak(GameTestHelper helper) {
        exerciseReload(helper, false);
    }

    @GameTest(template = "injector", timeoutTicks = 1600)
    public static void splitWhileFarPipeIsUnloaded(GameTestHelper helper) {
        exerciseReload(helper, true);
    }

    private static void exerciseReload(GameTestHelper helper, boolean split) {
        var world = (WorldServer) helper.getWorld();
        var provider = world.theChunkProviderServer;
        var origin = helper.absolute("injector");
        // Outside spawn retention and the scenario's own loaded chunks
        int east = ((origin.x() >> 4) + (split ? 96 : 64)) << 4;
        int inletX = split ? east - 3 : east - 1;
        int initialPipes = split ? 4 : 3;
        int y = 240;
        int z = (((origin.z() >> 4) + 64) << 4) + 8;
        int cx = east >> 4;
        int cz = z >> 4;
        provider.loadChunk(cx - 1, cz);
        provider.loadChunk(cx, cz);
        for (int x = east + 1 - initialPipes; x <= east; x++) helper.assertTrue(world.isAirBlock(x, y, z), "Fixture space is free");
        helper.assertTrue(world.isAirBlock(inletX, y, z - 1), "Inlet space is free");
        helper.afterTest(() -> {
            provider.loadChunk(cx - 1, cz);
            provider.loadChunk(cx, cz);
            world.setBlockToAir(inletX, y, z - 1);
            for (int x = east + 1 - initialPipes; x <= east; x++) world.setBlockToAir(x, y, z);
        });
        var inlet = (MTEIntegratedFluidInjectorHatch) InjectorTests
            .placeAt(world, inletX, y, z - 1, (short) 5773, ForgeDirection.SOUTH).getMetaTileEntity();
        InjectorTests.placeAt(world, east - 2, y, z, (short) 5770, ForgeDirection.EAST);
        var middle = (MTEIntegratedFluidPipe) InjectorTests
            .placeAt(world, east - 1, y, z, (short) 5770, ForgeDirection.WEST).getMetaTileEntity();
        InjectorTests.placeAt(world, east, y, z, (short) 5770, ForgeDirection.WEST);
        if (split) {
            var west = (MTEIntegratedFluidPipe) InjectorTests
                .placeAt(world, inletX, y, z, (short) 5770, ForgeDirection.EAST).getMetaTileEntity();
            west.connect(ForgeDirection.NORTH);
            west.connect(ForgeDirection.EAST);
        } else {
            middle.connect(ForgeDirection.NORTH);
        }
        middle.connect(ForgeDirection.WEST);
        middle.connect(ForgeDirection.EAST);
        long[] initial = new long[1];
        var id = new UUID[1];
        float[] pressure = new float[1];
        Runnable unload = () -> provider.unloadChunksIfNotNearSpawn(cx, cz);
        Runnable pending = () -> {
            helper.assertTrue(!provider.chunkExists(cx, cz), "VFN must not reload the missing chunk");
            helper.assertTrue(inlet.getNetwork().isPending(), "Partial network is pending");
            helper.assertEquals(0, inlet.fill(ForgeDirection.NORTH, FluidRegistry.getFluidStack("steam", 1), true),
                "No intake into partial geometry");
        };
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(inlet.getNetwork() != null && inlet.getNetwork().getMemberCount() == initialPipes + 1,
                "Network spans two chunks"))
            .thenExecute(() -> {
                // A saved 300 K vapor charge keeps ambient heat from masking chunk-lifecycle energy errors
                inlet.getNetwork().replaceCanonicalState(IFNCanonicalState.fromRaw("water", 1_000L * initialPipes,
                    1_971_518_500L * initialPipes));
                helper.assertTrue(Math.abs(inlet.getNetwork().getTemperature() - 300) < 0.0001, "At ambient equilibrium");
                initial[0] = inlet.getNetwork().getCanonicalState().totalEnergy().rawUnits();
                id[0] = inlet.getNetworkId();
                pressure[0] = inlet.getNetwork().getPressure();
                unload.run();
            })
            .thenWaitUntil(() -> helper.assertTrue(!provider.chunkExists(cx, cz) && provider.chunkExists(cx - 1, cz),
                "Only the far chunk unloaded"))
            .thenIdle(25)
            .thenExecute(pending)
            .thenExecute(() -> provider.loadChunk(cx, cz))
            .thenWaitUntil(() -> helper.assertTrue(!inlet.getNetwork().isPending() && inlet.getNetwork().getMemberCount() == initialPipes + 1,
                "Original network restored"))
            .thenExecute(() -> {
                var eastTile = (IGregTechTileEntity) world.getTileEntity(east, y, z);
                var far = (MTEIntegratedFluidPipe) eastTile.getMetaTileEntity();
                helper.assertTrue(far.getNetwork() == inlet.getNetwork(), "Reloaded tile belongs to restored chamber");
                helper.assertEquals(id[0], inlet.getNetworkId(), "Original network identity");
                helper.assertEquals(1_000L * initialPipes, inlet.getNetwork().getAmountQ(), "Unload is not a spill");
                helper.assertEquals(initial[0], inlet.getNetwork().getCanonicalState().totalEnergy().rawUnits(), "Unload conserves energy");
                helper.assertEquals(pressure[0], inlet.getNetwork().getPressure(), "Unload conserves pressure");
                unload.run();
            })
            .thenIdle(25)
            .thenExecute(() -> {
                pending.run();
                world.setBlockToAir(east - 2, y, z);
                pending.run();
                InjectorTests.assertDiskInventory(helper, world, inlet.getNetwork());
                provider.loadChunk(cx, cz);
            })
            .thenWaitUntil(() -> helper.assertTrue(!inlet.getNetwork().isPending() && inlet.getNetwork().getMemberCount() == (split ? 2 : 3),
                "Survivors restored"))
            .thenExecute(() -> {
                var eastTile = (IGregTechTileEntity) world.getTileEntity(east, y, z);
                var far = ((MTEIntegratedFluidPipe) eastTile.getMetaTileEntity()).getNetwork();
                helper.assertTrue(far != null && !far.isPending() && (far != inlet.getNetwork()) == split, "Far-side membership");
                helper.assertEquals(2_000L, far.getAmountQ(), "Two surviving pipes on the far side");
                helper.assertEquals(initial[0] / initialPipes * 2, far.getCanonicalState().totalEnergy().rawUnits(), "Far-side energy");
                helper.assertEquals(split ? 1_000L : 2_000L, inlet.getNetwork().getAmountQ(), "Only the broken pipe vents steam");
                helper.assertEquals(initial[0] / initialPipes * (split ? 1 : 2), inlet.getNetwork().getCanonicalState().totalEnergy().rawUnits(),
                    "Only the broken pipe loses energy");
                helper.assertEquals(pressure[0], inlet.getNetwork().getPressure(), "Break did not compress remote pipe");
            })
            .thenSucceed();
    }
}
