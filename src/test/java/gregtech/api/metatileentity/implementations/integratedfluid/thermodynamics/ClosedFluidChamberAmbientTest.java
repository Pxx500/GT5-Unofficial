package gregtech.api.metatileentity.implementations.integratedfluid.thermodynamics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class ClosedFluidChamberAmbientTest {

    @Test
    void tinyChargeCannotOvershootHotOrColdReservoir() {
        var vapor = ClosedFluidChamber.of(PureFluid.water(), new BigDecimal("0.001"), 100,
            new BigDecimal("2064.4285"));
        var heated = vapor.exchangeWithAmbient(420, 1_000);
        assertEquals(420, heated.state().temperatureKelvin(), 0.00001);
        var cooled = heated.exchangeWithAmbient(100, 1_000);
        assertEquals(100, cooled.state().temperatureKelvin(), 0.00001);
        assertTrue(cooled.state().solidMassFraction() > 0.99);
        assertEquals(vapor.massKg(), cooled.massKg());
        assertSame(cooled, cooled.exchangeWithAmbient(100, 1_000));
    }

    @Test
    void passiveHeatUsesTickBudgetAndSettlesSignedMicroEu() {
        var vapor = ClosedFluidChamber.of(PureFluid.water(), BigDecimal.ONE, 50_000, new BigDecimal("2064428.5"));
        var cooled = vapor.exchangeWithAmbient(300, 0.5);
        assertEquals(30.0, vapor.totalEnergyEu().subtract(cooled.totalEnergyEu()).doubleValue(), 0.0000011);
        assertSame(vapor, vapor.exchangeWithAmbient(200, 0));
        var empty = ClosedFluidChamber.empty(PureFluid.water(), 100);
        assertSame(empty, empty.exchangeWithAmbient(400, 1));
    }
}
