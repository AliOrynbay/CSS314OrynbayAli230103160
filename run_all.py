import platform
import subprocess
import sys
import os
from pathlib import Path

ROOT = Path(__file__).parent
OUT = ROOT / "results_auto.txt"

def run(script):
    print("\n" + "=" * 70)
    print(f"RUNNING {script}")
    print("=" * 70)
    result = subprocess.run(
        [sys.executable, str(ROOT / script)],
        capture_output=True,
        text=True
    )
    print(result.stdout)
    if result.stderr:
        print("STDERR:")
        print(result.stderr)
    return result.stdout

def main():
    lines = []
    lines.append("ADVANCED PARALLEL PROGRAMMING - EMPIRICAL RESULTS")
    lines.append("=" * 70)
    lines.append(f"Python: {platform.python_version()}")
    lines.append(f"OS: {platform.platform()}")
    lines.append(f"Processor: {platform.processor()}")
    lines.append(f"Logical CPUs: {os.cpu_count()}")
    lines.append("")

    for script in [
        "task1_amdahl.py",
        "task2_falsesharing.py",
        "task3_sync.py",
        "task4_roofline.py",
    ]:
        output = run(script)
        lines.append("=" * 70)
        lines.append(script)
        lines.append("=" * 70)
        lines.append(output)

    OUT.write_text("\n".join(lines), encoding="utf-8")
    print(f"\nSaved complete raw results to: {OUT}")

if __name__ == "__main__":
    main()