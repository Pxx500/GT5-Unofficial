package gttests.vfn;

import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;

import gregtech.GTMod;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

/** A single remote chunk for testing real machine unloads outside HorizonQA's pinned template. */
record RemoteMachineSite(WorldServer world, int x, int y, int z) {

    static RemoteMachineSite at(GameTestHelper helper, String anchor) {
        var p = helper.absolute(anchor);
        return new RemoteMachineSite((WorldServer) helper.getWorld(), p.x(), p.y(), p.z());
    }

    IGregTechTileEntity place(int dx, int dy, int dz, int id, ForgeDirection facing) {
        return InjectorTests.placeAt(world, x + dx, y + dy, z + dz, (short) id, facing);
    }

    static RemoteMachineSite reserve(GameTestHelper helper, int offset, int topDy) {
        return reserve(helper, (WorldServer) helper.getWorld(), offset, topDy);
    }

    static RemoteMachineSite reserve(GameTestHelper helper, WorldServer world, int offset, int topDy) {
        var origin = helper.absolute("controller");
        int cx = (origin.x() >> 4) + offset, cz = (origin.z() >> 4) + offset;
        var site = new RemoteMachineSite(world, (cx << 4) + 8, 240, (cz << 4) + 8);
        world.theChunkProviderServer.loadChunk(cx, cz);
        for (int dx = -1; dx <= 3; dx++) for (int dy = -1; dy <= topDy; dy++) for (int dz = -3; dz <= 2; dz++)
            helper.assertTrue(world.isAirBlock(site.x + dx, site.y + dy, site.z + dz), "Remote machine site is empty");
        helper.afterTest(() -> {
            world.theChunkProviderServer.loadChunk(cx, cz);
            for (int dx = -1; dx <= 3; dx++) for (int dy = -1; dy <= topDy; dy++) for (int dz = -3; dz <= 2; dz++)
                world.setBlockToAir(site.x + dx, site.y + dy, site.z + dz);
        });
        return site;
    }

    IGregTechTileEntity tile(int dx, int dy, int dz) {
        return (IGregTechTileEntity) world.getTileEntity(x + dx, y + dy, z + dz);
    }

    void assignOwner() {
        assignOwner(tile(0, 0, 0));
    }

    void assignOwner(IGregTechTileEntity controller) {
        var owner = net.minecraftforge.common.util.FakePlayerFactory.getMinecraft(world);
        controller.setOwnerUuid(owner.getUniqueID());
        controller.setOwnerName(owner.getCommandSenderName());
    }

    void requestUnload() {
        world.theChunkProviderServer.unloadChunksIfNotNearSpawn(x >> 4, z >> 4);
    }

    boolean absent() {
        return !world.theChunkProviderServer.chunkExists(x >> 4, z >> 4);
    }

    void reloadForObservation(GameTestHelper helper) {
        var ticket = ForgeChunkManager.requestTicket(GTMod.GT, world, ForgeChunkManager.Type.NORMAL);
        helper.assertTrue(ticket != null, "Ticket for the reloaded machine");
        helper.afterTest(() -> ForgeChunkManager.releaseTicket(ticket));
        ForgeChunkManager.forceChunk(ticket, new ChunkCoordIntPair(x >> 4, z >> 4));
        world.theChunkProviderServer.loadChunk(x >> 4, z >> 4);
    }
}
