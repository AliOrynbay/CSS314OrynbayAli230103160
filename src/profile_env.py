#!/usr/bin/env python3
"""Milestone 0: host topology profiling + student-ID salting.

Usage: python3 src/profile_env.py --student-id 12345678
Writes: results/host_profile.json, results/salted_params.json
"""
import argparse, glob, json, os, platform, re, subprocess, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from salt import get_student_params

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RESULTS = os.path.join(ROOT, "results")


def run(cmd):
    try:
        return subprocess.check_output(cmd, shell=True, text=True,
                                       stderr=subprocess.DEVNULL).strip()
    except Exception:
        return None


def parse_size(s):
    """'32K' -> 32768, '1M' -> 1048576, '12288K' -> bytes."""
    m = re.match(r"\s*(\d+)\s*([KMG]?)", s or "", re.I)
    if not m:
        return None
    mult = {"": 1, "K": 1024, "M": 1024 ** 2, "G": 1024 ** 3}[m.group(2).upper()]
    return int(m.group(1)) * mult


# ---------------------------------------------------------------- Linux
def linux_info():
    info = {}
    cpuinfo = open("/proc/cpuinfo").read()
    m = re.search(r"model name\s*:\s*(.+)", cpuinfo)
    info["cpu_model"] = m.group(1).strip() if m else None
    fam = re.search(r"cpu family\s*:\s*(\d+)", cpuinfo)
    mod = re.search(r"^model\s*:\s*(\d+)", cpuinfo, re.M)
    step = re.search(r"stepping\s*:\s*(\d+)", cpuinfo)
    info["microarchitecture"] = (
        f"family {fam.group(1)} model {mod.group(1)} stepping {step.group(1)}"
        if fam and mod and step else platform.machine())

    # Base frequency: prefer sysfs base_frequency, then lscpu max MHz, then /proc "cpu MHz"
    base = None
    try:
        base = int(open("/sys/devices/system/cpu/cpu0/cpufreq/base_frequency").read()) / 1000
    except Exception:
        pass
    if base is None:
        out = run("lscpu")
        if out:
            mm = re.search(r"CPU max MHz:\s*([\d.,]+)", out)
            if mm:
                base = float(mm.group(1).replace(",", "."))
    if base is None:
        mm = re.search(r"cpu MHz\s*:\s*([\d.]+)", cpuinfo)
        base = float(mm.group(1)) if mm else None
    info["base_frequency_mhz"] = base

    # Physical cores = unique SMT sibling groups
    siblings = set()
    for f in glob.glob("/sys/devices/system/cpu/cpu[0-9]*/topology/thread_siblings_list"):
        siblings.add(open(f).read().strip())
    info["physical_cores"] = len(siblings) or None
    info["logical_threads"] = os.cpu_count()

    # Caches from cpu0
    caches = {"l1d": None, "l2": None, "l3": None, "line": None}
    for d in sorted(glob.glob("/sys/devices/system/cpu/cpu0/cache/index*")):
        rd = lambda n: open(os.path.join(d, n)).read().strip()
        try:
            level, ctype, size = int(rd("level")), rd("type"), parse_size(rd("size"))
            caches["line"] = int(rd("coherency_line_size"))
        except Exception:
            continue
        if level == 1 and ctype == "Data":
            caches["l1d"] = size
        elif level == 2:
            caches["l2"] = size
        elif level == 3:
            caches["l3"] = size
    return info, caches


# ---------------------------------------------------------------- macOS
def mac_info():
    sc = lambda k: run(f"sysctl -n {k}")
    info = {
        "cpu_model": sc("machdep.cpu.brand_string") or platform.processor(),
        "microarchitecture": platform.machine(),
        "base_frequency_mhz": (int(sc("hw.cpufrequency")) / 1e6
                               if (sc("hw.cpufrequency") or "").isdigit() else None),
        "physical_cores": int(sc("hw.physicalcpu") or 0) or None,
        "logical_threads": int(sc("hw.logicalcpu") or 0) or None,
    }
    toint = lambda v: int(v) if v and v.isdigit() else None
    caches = {"l1d": toint(sc("hw.l1dcachesize")), "l2": toint(sc("hw.l2cachesize")),
              "l3": toint(sc("hw.l3cachesize")), "line": toint(sc("hw.cachelinesize"))}
    return info, caches


# ---------------------------------------------------------------- Windows / fallback
def generic_info():
    phys = logical = None
    try:
        import psutil
        phys, logical = psutil.cpu_count(logical=False), psutil.cpu_count(logical=True)
    except ImportError:
        logical = os.cpu_count()
    info = {"cpu_model": platform.processor(), "microarchitecture": platform.machine(),
            "base_frequency_mhz": None, "physical_cores": phys, "logical_threads": logical}
    caches = {"l1d": None, "l2": None, "l3": None, "line": 64}
    if sys.platform == "win32":  # best-effort via wmic
        out = run("wmic cpu get L2CacheSize,L3CacheSize,MaxClockSpeed /format:list")
        if out:
            g = lambda k: (re.search(rf"{k}=(\d+)", out) or [None, None])[1]
            if g("L2CacheSize"):
                caches["l2"] = int(g("L2CacheSize")) * 1024
            if g("L3CacheSize"):
                caches["l3"] = int(g("L3CacheSize")) * 1024
            if g("MaxClockSpeed"):
                info["base_frequency_mhz"] = float(g("MaxClockSpeed"))
    return info, caches


def collect():
    if sys.platform.startswith("linux"):
        info, caches = linux_info()
    elif sys.platform == "darwin":
        info, caches = mac_info()
    else:
        info, caches = generic_info()

    phys, logi = info.get("physical_cores"), info.get("logical_threads")
    info["smt_enabled"] = bool(phys and logi and logi > phys)
    info["cache"] = {
        "l1d_bytes": caches["l1d"], "l2_bytes": caches["l2"],
        "l3_bytes": caches["l3"], "cache_line_bytes": caches["line"] or 64,
    }
    info["os"] = {"system": platform.system(), "kernel": platform.release(),
                  "version": platform.version(), "machine": platform.machine()}
    info["python"] = {"implementation": platform.python_implementation(),
                      "version": platform.python_version(),
                      "compiler": platform.python_compiler()}
    try:
        import numpy
        info["python"]["numpy"] = numpy.__version__
    except ImportError:
        pass
    return info


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--student-id", required=True, help="8-digit Student ID")
    args = ap.parse_args()

    os.makedirs(RESULTS, exist_ok=True)
    profile = collect()
    with open(os.path.join(RESULTS, "host_profile.json"), "w") as f:
        json.dump(profile, f, indent=2)
    params = get_student_params(args.student_id)
    with open(os.path.join(RESULTS, "salted_params.json"), "w") as f:
        json.dump(params, f, indent=2)

    print(json.dumps(profile, indent=2))
    print(json.dumps(params, indent=2))
    missing = [k for k, v in profile["cache"].items() if v is None]
    if missing or not profile["physical_cores"]:
        print(f"\n[WARN] Could not detect: {missing or ''} "
              f"{'physical_cores' if not profile['physical_cores'] else ''}. "
              "Fill them in manually from lscpu / your CPU spec sheet.", file=sys.stderr)


if __name__ == "__main__":
    main()
