# Connecting machines to VFN

VFN models each connected circuit as a chamber. Its inventory is substance identity, mass and total energy. Members provide geometry. Pressure, temperature and phase are derived from that inventory and geometry. Water and steam share the water model. Hydrogen, helium, nitrogen, oxygen, IC2 coolant and super coolant each retain their own substance identity.

## Choose the boundary

- Ordinary GT tanks and pipe covers use the Forge fluid interface of the injector or extractor. Only these ports normalize supported untagged products against local ambient. GT steam uses a fixed saturated-vapor inlet at 373.15 K. Preview must not debit either side.
- A VFN machine uses its input and output hatches to find the connected chambers. Keep the actual charge between intake, processing and discharge. Do not round it to an external fluid stack.
- A two-sided device such as the pressure regulator connects two chambers but does not join them. It meters passive flow from its back face to its front face. It cannot raise pressure.

A machine with a working chamber can use these operations on the server:

```java
var filled = inlet.fillMachineChamber(working, packetLiters);
if (filled != working) {
    working = filled;
    getBaseMetaTileEntity().markDirty();
}
```

After processing, pass the same charge to `outlet.drainMachineChamber(working, packetLiters, pressureDropPa)` and retain the returned chamber. Both calls settle the network side as part of the operation. The original chamber is returned when no transfer is possible. A successful discharge can leave residual mass in the machine. See `MTERadiator.checkProcessing` for a ticked example. Electrical work, thermal contact, and passive flow are separate operations.

## Network lifecycle

Use the per-world `NetworkManager` on the server. A pipe or hatch calls `onMemberAdded` on its first tick. Connection changes go through `onConnectionChanged`. A disconnect, chunk unload and physical block destruction are not interchangeable. Follow the paired `onBlockDestroyed` and `onRemoval` hooks on `MTEIntegratedFluidPipe` so unloading a chunk does not vent its contents.

Do not cache a network reference across a topology change. Read it from the hatch or pipe. A pending network is missing expected members. An operationally frozen network is locked, not necessarily icy. Neither may transfer through a machine port.

## Reading state

```java
var network = hatch.getNetwork();
if (network != null) {
    var inventory = network.getCanonicalState();
    var kilograms = inventory.substanceAmount().kilograms();
    long microEu = inventory.totalEnergy().rawUnits();
    var state = network.getSolvedState();
    if (state.isPresent()) {
        double kelvin = state.get().temperatureKelvin();
        double pressurePa = network.getPressurePa();
    }
}
```

An empty chamber has zero absolute pressure and no fluid temperature. If material remains but its state cannot be solved, report the reading as unavailable rather than as vacuum.

A liter of physical chamber volume is not a fixed mass of steam. Display inventory in kg and geometry in L. Use absolute Pa in transfer calculations and bar for player-facing pressure. For runnable integration examples see `src/vfnQa/java/gttests/vfn/` and [the dedicated server workflow](../FULL_GAME_SERVER_QA.md).

