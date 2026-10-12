"""Run only on an explicitly selected emulator; never auto-select a user's phone."""
import argparse
import json
import subprocess
import time
from pathlib import Path
from mp4_boxes import inspect

PACKAGE = "org.medialab.codec"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--runs", type=int, default=3)
    args = parser.parse_args()
    if not args.serial.startswith("emulator-") or not 1 <= args.runs <= 10:
        parser.error("explicit emulator serial and 1..10 runs required")

    def adb(*command, allow_error=False):
        result = subprocess.run([args.adb, "-s", args.serial, *command], capture_output=True, timeout=20)
        if result.returncode and not allow_error:
            raise RuntimeError(result.stderr.decode(errors="replace"))
        return result

    if adb("shell", "getprop", "ro.kernel.qemu").stdout.strip() != b"1":
        raise RuntimeError("target is not an emulator")
    args.output.mkdir(parents=True, exist_ok=True)
    adb("install", "-r", str(args.apk))
    reports = []
    for run in range(args.runs):
        adb("shell", "am", "force-stop", PACKAGE)
        # Clear only this demo's generated state so an old PASS cannot be accepted.
        adb("shell", "pm", "clear", PACKAGE)
        adb("shell", "am", "start", "-n", PACKAGE + "/.MainActivity")
        end = time.monotonic() + 90
        report = None
        while time.monotonic() < end:
            result = adb("exec-out", "run-as", PACKAGE, "cat", "files/report.json", allow_error=True)
            # exec-out may return exit 0 even when the remote cat reports ENOENT.
            if result.returncode == 0 and result.stdout.lstrip().startswith(b"{"):
                report = json.loads(result.stdout)
                break
            time.sleep(1)
        if report is None:
            raise RuntimeError("report timeout")
        (args.output / f"run_{run+1}.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
        if report.get("status") != "PASS" or len(report.get("cases", [])) != 6:
            raise RuntimeError("Android suite failed: " + json.dumps(report))
        reports.append(report)
    containers = {}
    for case in reports[-1]["cases"]:
        if "inputFrames" not in case:
            continue
        name = case["name"] + ".mp4"
        target = args.output / name
        target.write_bytes(adb("exec-out", "run-as", PACKAGE, "cat", "files/" + name).stdout)
        containers[name] = inspect(target)
    (args.output / "mp4_structure.json").write_text(json.dumps(containers, indent=2), encoding="utf-8")
    for name, command in {"processes.txt": ("shell", "ps", "-A"), "services.txt": ("shell", "service", "list")}.items():
        (args.output / name).write_bytes(adb(*command).stdout)
    summary = {"runs": args.runs, "casesPerRun": 6, "passed": args.runs * 6,
               "mp4FilesInspected": len(containers), "sdk": reports[0]["sdk"],
               "fingerprint": reports[0]["fingerprint"], "scope": "synthetic audio on Android emulator only"}
    (args.output / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__": main()
