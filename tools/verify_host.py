"""Build the native parser, run behavior tests, retain a machine-readable result."""
import argparse
import json
import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--compiler", default="g++")
    parser.add_argument("--output", type=Path, default=ROOT / "evidence/host")
    args = parser.parse_args()
    build = ROOT / "build"
    build.mkdir(exist_ok=True)
    exe = build / ("wave_cli.exe" if os.name == "nt" else "wave_cli")
    flags = ["-std=c++17", "-O2", "-Wall", "-Wextra", "-Wpedantic", "-Werror"]
    compiler = subprocess.run([args.compiler, *flags, "native/wave.cpp", "native/wave_cli.cpp", "-o", str(exe.relative_to(ROOT))], cwd=ROOT, capture_output=True, timeout=60)
    if compiler.returncode:
        raise RuntimeError(compiler.stderr.decode(errors="replace"))
    env = os.environ.copy()
    env["WAVE_CLI"] = str(exe)
    env["PYTHONIOENCODING"] = "utf-8"
    if Path(args.compiler).is_absolute():
        env["PATH"] = str(Path(args.compiler).parent) + os.pathsep + env.get("PATH", "")
    result = subprocess.run([sys.executable, "-m", "unittest", "discover", "-s", "tests", "-v"], cwd=ROOT, env=env, capture_output=True, timeout=60)
    args.output.mkdir(parents=True, exist_ok=True)
    log = (result.stdout + result.stderr).decode("utf-8").replace("\r\n", "\n")
    (args.output / "tests.log").write_text(log, encoding="utf-8")
    if result.returncode or "Ran 38 tests" not in log:
        raise RuntimeError(log)
    report = {"unitTests": 38, "passed": True, "compilerFlags": flags,
              "scope": "native PCM16 WAV parser and structural MP4 walker; not a codec test"}
    (args.output / "summary.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__": main()
