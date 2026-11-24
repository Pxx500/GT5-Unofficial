# VFN dedicated server QA

Run the VFN gameplay gate from the repository root with the Horizon QA supervisor. It starts an isolated Minecraft dedicated server on the `:runServer25` runtime, ticks the machines, verifies the report and stops the server. No client is required.

## Run the suite

Use JDK 25, Python 3.9 or later, a Horizon QA tools checkout or distribution, and its matching development mod JAR. Set `HORIZON_QA_TOOLS` to the tools root containing `scripts/client-tests/build.gradle`. Set `HORIZON_QA_DEV_JAR` to the absolute path of the development JAR. The supplied development JAR enables the separate VFN scenario JAR on the development server and client. The consumer runtime must support isolated run directories. The offline example assumes its dependencies are cached. Pass `--accept-eula` only if you accept the Minecraft EULA.

```powershell
python misc/vfn_server_qa.py --accept-eula --offline
```

Each holder includes its server scenarios. To run one case, pass `--tests` with its exact ID, for example `gregtech:InjectorTests.hydrophoreFeedsGtTank`. Add new server holders to `HOLDERS` in the launcher. It forwards the selected group or test IDs to the official supervisor and returns its exit code. Client scenarios are in `InjectorViewTests` and run separately with `runClientTests` and `:runClient25`.

The dedicated CI runtime does not tick secondary dimensions. Run `gregtech:InjectorViewTests.injectorUsesItsOwnWorldExceptForSteam` on the client runtime to compare identical GT inputs in the Overworld and Nether. `gregtech:InjectorViewTests.inspectAmbientCoolant` captures the synchronized coolant inventory and temperature.

`gregtech:InjectorViewTests.compareSteamLoopsAcrossDimensions` runs identical steam loops in both worlds. It seeds matching chamber inventories so the Forge intake temperature does not bias the comparison. The sealed exhaust retains 5 g in each world for 1000 ticks. The test then meters both plants for 2400 powered ticks. Its diagnostics separate heaters, condensate pump, shaft output, delivered MV packets and environmental heat. This compares finite runs in two whole-plant environments, not steady-state efficiency or the effect of changing only a condenser.

The closed-loop case `gregtech:VaporOutletTests.pumpedCondensateReturnsToTheBoiler` starts with one kilogram of GT water. It meters the powered boiler, inlet reheater, turbine, load and return pump. It isolates a return stroke to measure mass and work against backpressure, then resumes generation and checks repeated returns without adding water. The test meters environmental heat separately. `gregtech:InjectorViewTests.configureCondensateReturn` opens the installed pump and submits its flow limit from the client.

## Restart a working machine

```powershell
python misc/vfn_server_qa.py --accept-eula --offline --restart
python misc/vfn_server_qa.py --accept-eula --offline --restart oxygen
```

This separate gate runs two dedicated server processes. The first stops with a partly paid cooling batch and records its measured charge outside the world. The second starts a copy of that saved world in an empty run directory. It checks the inlet identity, retained mass and energy, the remaining electrical bill, and that the outlet receives no duplicate material. The original world is not modified by the second launch.

The default fluid is water. The oxygen variant fills the inlet through the Forge injector and checks that the restarted outlet still holds oxygen. Its low-pressure discharge cannot feed an external tank. The ordinary server suite checks positive export from a separate pressurized fixture in `gregtech:InjectorTests.compressedOxygenFeedsGtTank`.

The launcher checks both exit reports and the named case in each JUnit XML. Use `--report-dir <prefix>` to name the two fresh report directories `<prefix>-before` and `<prefix>-after`. By default both live under `build/horizonqa/`. The first runtime is retained under the supervisor's ordinary retention policy.

## What passes

Require a successful supervisor exit, a completed `launch-result.json`, and all selected cases in `TEST-horizonqa.xml` with no failures, errors or skips. Reports are under `build/horizonqa/`. A compilation or an ordinary JUnit run does not establish gameplay behavior.

The tests place actual VFN and GT blocks. They cover creative water tank and Super Tank transfers, normalization at the injector and extractor, pressure regulation, pipe rupture and chunk changes, passive heat transfer, boilers, turbines, and the heat pump in single-stream, split and four-port modes. They measure mass, energy, pressure and paid work. The end-to-end radiator case isolates and rejoins a filled line. Partial discharges and in-flight batches are checked across chunk reload, which is not the same as a server process restart.

Use the fixtures in `src/vfnQa/java/gttests/vfn/` and the templates in `src/vfnQa/resources/assets/gregtech/horizonqastructures/vfn/`. Assert on ticked game state. For a blocked operation, prove that no mass or energy moved and include a comparable case that succeeds.

The `MaintenanceTests` holder replaces the automatic maintenance hatch with a manual one. Both controllers shut down with an unfinished batch when all six maintenance flags fail. The tests repair through the hatch with duct tape and restart the controller with its inlet still isolated. The heat pump must not repeat paid work. The radiator must finish the charge already in its loop.

The turbine cases compare otherwise identical dry exhaust pipes after a 430 K and a 495 K inlet charge. They distinguish damage due to wet expansion from the phase measured in the receiving pipe. Separate cases check the last powered stroke before rotor breakage, passive flow without a usable rotor, and a full dynamo that holds the charge without damaging the rotor.

`PressureLineTests` checks the registered assembler recipe and two three-pipe lines primed with identical supercritical water. The reinforced line retains the charge and accounts for the boiler's paid heat and ambient exchange. In the mixed line the ordinary middle pipe ruptures and both reinforced ends survive. The primed inventory tests containment, not startup or net power.

## Other tasks

`horizonsQA` runs a selected JVM test suite without Minecraft. The ordinary GT functional JUnit mod is independent of VFN. Use the server command above for gameplay verification.
