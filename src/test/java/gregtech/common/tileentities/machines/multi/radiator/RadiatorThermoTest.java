package gregtech.common.tileentities.machines.multi.radiator;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;

import org.junit.jupiter.api.Test;

class RadiatorThermoTest {

    @Test
    void conductionBranchNeverReducesEffectiveConductance() {
        RadiatorLoopSnapshot plainSnapshot = RadiatorLoopSnapshot.valid(
            1,
            0,
            1,
            0.01f,
            Collections.singletonList(RadiatorLoopSnapshot.ThermalBranch.exchanger(10.0d, 0.0d))
        );

        RadiatorLoopSnapshot conductionSnapshot = RadiatorLoopSnapshot.valid(
            1,
            1,
            1,
            0.01f,
            Collections.singletonList(
                RadiatorLoopSnapshot.ThermalBranch.conduction(
                    5.0d,
                    Collections.singletonList(RadiatorLoopSnapshot.ThermalBranch.exchanger(10.0d, 0.0d))
                ))
        );

        double plainConductance = RadiatorThermo.computeEffectiveConductance(plainSnapshot, 300.0d);
        double conductionConductance = RadiatorThermo.computeEffectiveConductance(conductionSnapshot, 300.0d);

        assertTrue(conductionConductance >= plainConductance);
    }

}
