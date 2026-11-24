"""Run the VFN gameplay suite with the Horizon QA dedicated-server supervisor."""

import argparse
import json
import os
from pathlib import Path
import subprocess
import uuid
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
HOLDERS = (
    "AmbientTests", "BoilerTests", "ChunkTests", "ExchangerTests", "HeatPumpTests",
    "DensePlantTests", "InjectorTests", "LiquidPumpTests", "MaintenanceTests", "PressureLineTests", "RadiatorTests", "TurbineTests", "VaporOutletTests", "WaterLineTests",
)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--accept-eula", action="store_true", required=True,
                        help="confirm acceptance of the Minecraft EULA for this server")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--tests", help="Horizon QA selector (default: all VFN server groups)")
    mode.add_argument("--restart", nargs="?", const="water", choices=("water", "oxygen", "radiator"),
                      help="restart a dedicated server with an unfinished heat pump or radiator batch")
    parser.add_argument("--offline", action="store_true", help="use cached dependencies for both Gradle builds")
    parser.add_argument("--report-dir", type=Path, help="new report directory (default: assigned by Horizon QA)")
    args = parser.parse_args()

    tools = os.environ.get("HORIZON_QA_TOOLS")
    jar = os.environ.get("HORIZON_QA_DEV_JAR")
    if not tools or not (Path(tools) / "scripts/client-tests/build.gradle").is_file():
        parser.error("set HORIZON_QA_TOOLS to the Horizon QA tools checkout or distribution")
    if not jar or not Path(jar).is_file():
        parser.error("set HORIZON_QA_DEV_JAR to the matching development mod JAR")

    java_home = os.environ.get("JAVA_HOME")
    java = str(Path(java_home) / "bin" / "java") if java_home else "java"
    command = [
        java, "-cp", str(ROOT / "gradle/wrapper/gradle-wrapper.jar"), "org.gradle.wrapper.GradleWrapperMain",
        "-p", str(Path(tools).resolve() / "scripts/client-tests"), "runServerTests",
        "--project-root", str(ROOT), "--server-task", ":runServer25",
        "--horizon-qa-jar", str(Path(jar).resolve()), "--shutdown-seconds", "60", "--accept-eula",
    ]
    if args.offline:
        command.extend(["--offline", "--gradle-argument=--offline"])
    if args.restart:
        base = (args.report_dir or ROOT / "build" / "horizonqa" / ("restart-" + str(uuid.uuid4()))).resolve()
        first = base.with_name(base.name + "-before")
        second = base.with_name(base.name + "-after")
        if first.exists() or second.exists():
            parser.error("restart reports must be new directories")
        checkpoint = first / "expected.properties"
        command.append("--gradle-argument=--mcJvmArgs=-Dvfn.qa.restartCheckpoint=" + str(checkpoint))
        before, after = {
            "water": ("stopWithPartlyPaidCharge", "finishPartlyPaidCharge"),
            "oxygen": ("stopWithOxygenCharge", "finishOxygenCharge"),
            "radiator": ("stopWithRadiatorCharge", "finishRadiatorCharge"),
        }[args.restart]
        run_restart_stage(command, first, before, ["--keep-runtime-files"])
        worlds = list((first / "game").glob("*/level.dat"))
        if len(worlds) != 1 or not checkpoint.is_file():
            raise RuntimeError("First server did not leave one saved world and an independent checkpoint")
        run_restart_stage(command, second, after,
                          ["--gradle-argument=-PvfnQaRestartWorld=" + str(worlds[0].parent)])
        return 0
    command.extend(["--tests", args.tests or ",".join(HOLDERS)])
    if args.report_dir:
        command.extend(["--report-dir", str(args.report_dir.resolve())])
    return subprocess.run(command, cwd=ROOT).returncode


def run_restart_stage(command, report, scenario, extra):
    selector = "gregtech:RestartTests." + scenario
    subprocess.run([*command, "--report-dir", str(report), "--tests", selector, *extra], cwd=ROOT, check=True)
    launch = json.loads((report / "launch-result.json").read_text(encoding="utf-8"))
    suite = ET.parse(report / "TEST-horizonqa.xml").getroot()
    cases = suite.findall("testcase")
    if (launch.get("exitCode") != 0 or launch.get("processExitCode") != 0 or launch.get("forcedTermination")
            or launch.get("timedOut") or len(cases) != 1 or cases[0].get("name") != scenario
            or any(suite.get(key) != "0" for key in ("failures", "errors", "skipped"))):
        raise RuntimeError(f"Incomplete restart stage: {report}")


if __name__ == "__main__":
    raise SystemExit(main())
