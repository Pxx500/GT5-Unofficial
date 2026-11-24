package gregtech.api.metatileentity.implementations.integratedfluid;

import gregtech.api.interfaces.ITexture;
import gregtech.api.metatileentity.implementations.MTEHatch;
import gregtech.api.metatileentity.implementations.integratedfluid.MTEIntegratedFluidPipe.Wall;

/** Pressure wall and storage geometry shared by the two directions of machine port. */
public abstract class MTEIntegratedFluidMachineHatch extends MTEHatch implements IIntegratedFluidMember {

    protected final Wall wall;

    protected MTEIntegratedFluidMachineHatch(int id, String name, String displayName, Wall wall) {
        super(id, name, displayName, wall.casing, 0, new String[] {displayName, "Connect to a VFN pipe",
            wall == Wall.REINFORCED ? "350 bar rigid port: 100 L" : "10 bar port: 10,000 L with a 10,000 L accumulator"});
        this.wall = wall;
    }

    protected MTEIntegratedFluidMachineHatch(String name, Wall wall, String[] description, ITexture[][][] textures) {
        super(name, wall.casing, 0, description, textures);
        this.wall = wall;
    }

    @Override public final int getCapacityContribution() { return wall == Wall.REINFORCED ? 100 : 10_000; }
    @Override public final int getAccumulatorContribution() { return wall == Wall.REINFORCED ? 0 : 10_000; }
    @Override public final float getMaxPressureDifferentialBar() { return wall.pressureBar; }
    @Override public final float getAccumulatorMaxPressureBar() { return wall.pressureBar; }
    @Override public final double getPassiveConductanceEuPerTickKelvin() {
        return IntegratedFluidNetwork.PIPE_CONDUCTANCE_EU_PER_TICK_KELVIN;
    }
}
