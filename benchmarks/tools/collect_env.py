#!/usr/bin/env python3
"""Snapshot the hardware, software and Minecraft setup a benchmark run used, as JSON.

Read-only: it runs query commands and reads files, and changes nothing. Commands that are missing are
recorded as missing rather than failing the snapshot. Home-directory paths are written as "~" and the
host name is not recorded, so snapshots can be published.

Standard library only.

Usage:
  collect_env.py --out env.json [--instance DIR] [--java PATH] [--jvm-args "..."] [--repo DIR]
                 [--pid PID] [--note TEXT]

  --instance  Minecraft game directory (mods/, options.txt, config/): records mod jar hashes and settings
  --java      the java binary the game runs with (version is recorded)
  --pid       the running game process: records its command line, CPU affinity and thread count
"""
from __future__ import annotations

import argparse
import datetime as dt
import glob
import hashlib
import json
import os
import platform
import shutil
import subprocess
import sys
from pathlib import Path

HOME = str(Path.home())

# options.txt keys that change rendering cost or particle counts.
OPTIONS_KEYS = ("renderDistance", "simulationDistance", "maxFps", "enableVsync", "graphicsMode", "particles",
                "fullscreen", "fullscreenResolution", "overrideWidth", "overrideHeight", "guiScale", "ao",
                "biomeBlendRadius", "entityDistanceScaling", "entityShadows", "cloudStatus", "prioritizeChunkUpdates",
                "fov", "mipmapLevels", "menuBackgroundBlurriness", "resourcePacks")


def redact(value):
    if isinstance(value, str):
        return value.replace(HOME, "~")
    if isinstance(value, list):
        return [redact(v) for v in value]
    if isinstance(value, dict):
        return {k: redact(v) for k, v in value.items()}
    return value


def run(cmd: list[str], timeout: float = 10.0) -> str | None:
    if shutil.which(cmd[0]) is None:
        return None
    try:
        out = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired) as e:
        return f"<error: {e}>"
    return (out.stdout + out.stderr).strip()


def read(path: str) -> str | None:
    try:
        return Path(path).read_text().strip()
    except OSError:
        return None


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def cpu_info() -> dict:
    model = None
    for line in (read("/proc/cpuinfo") or "").splitlines():
        if line.startswith("model name"):
            model = line.split(":", 1)[1].strip()
            break
    cpus = sorted(glob.glob("/sys/devices/system/cpu/cpu[0-9]*"), key=lambda p: int(p.rsplit("cpu", 1)[1]))
    max_freqs = {Path(c).name: read(f"{c}/cpufreq/cpuinfo_max_freq") for c in cpus}
    return {
        "model": model,
        "logical_cpus": os.cpu_count(),
        "performance_cores": read("/sys/devices/cpu_core/cpus"),   # Intel hybrid: P-cores
        "efficiency_cores": read("/sys/devices/cpu_atom/cpus"),    # Intel hybrid: E-cores
        "max_freq_khz_by_cpu": max_freqs,
        "governor": read("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor"),
        "energy_performance_preference": read("/sys/devices/system/cpu/cpu0/cpufreq/energy_performance_preference"),
        "intel_no_turbo": read("/sys/devices/system/cpu/intel_pstate/no_turbo"),
        "platform_profile": read("/sys/firmware/acpi/platform_profile"),
        "power_profile": run(["powerprofilesctl", "get"]),
        "rapl_limits_w": rapl_limits(),
    }


def rapl_limits() -> dict:
    """CPU package power limits (watts) from every RAPL interface; they cap sustained CPU-bound performance."""
    out = {}
    for zone in sorted(glob.glob("/sys/class/powercap/intel-rapl*:[0-9]")):
        limits = {}
        for name_file in sorted(glob.glob(f"{zone}/constraint_*_name")):
            uw = read(name_file.replace("_name", "_power_limit_uw"))
            if uw is not None and uw.isdigit():
                limits[read(name_file)] = int(uw) / 1e6
        if limits:
            out[Path(zone).name] = limits
    return out


def power_info() -> dict:
    supplies = {}
    for d in glob.glob("/sys/class/power_supply/*"):
        name = Path(d).name
        kind = read(f"{d}/type")
        if kind in ("Mains", "Battery"):
            supplies[name] = {"type": kind, "online": read(f"{d}/online"), "status": read(f"{d}/status"),
                              "capacity": read(f"{d}/capacity")}
    return supplies


def gpu_info() -> dict:
    q = "name,driver_version,pstate,clocks.gr,clocks.mem,clocks.max.gr,power.draw,power.limit,temperature.gpu,utilization.gpu"
    return {
        "pci": run(["sh", "-c", "lspci | grep -iE 'vga|3d controller'"]),
        "nvidia_smi": run(["nvidia-smi", f"--query-gpu={q}", "--format=csv"]),
        "drm_cards": {Path(c).name: {"driver": Path(os.path.realpath(f"{c}/device/driver")).name,
                                     "vendor": read(f"{c}/device/vendor")}
                      for c in glob.glob("/sys/class/drm/card[0-9]")},
        "connected_outputs": {Path(c).name: "connected" for c in glob.glob("/sys/class/drm/card*-*")
                              if read(f"{c}/status") == "connected"},
    }


def display_info() -> dict:
    monitors = run(["hyprctl", "monitors", "-j"])
    try:
        mons = json.loads(monitors) if monitors else None
        # make/model rather than description, which ends in the monitor's serial number
        mons = [{k: m.get(k) for k in ("name", "make", "model", "width", "height", "refreshRate", "x", "y", "scale",
                                       "vrr", "activelyTearing", "directScanoutTo", "focused",
                                       "activeWorkspace")} for m in mons] if mons else None
    except json.JSONDecodeError:
        mons = monitors
    return {
        "session_type": os.environ.get("XDG_SESSION_TYPE"),
        "hyprland": (run(["hyprctl", "version"]) or "").splitlines()[:1],
        "monitors": mons,
    }


def software_info(java: str | None) -> dict:
    os_release = {}
    for line in (read("/etc/os-release") or "").splitlines():
        if "=" in line:
            k, v = line.split("=", 1)
            os_release[k] = v.strip('"')
    env_keys = [k for k in os.environ if k.startswith(("__NV", "__GLX", "__VK", "MANGOHUD", "DRI_PRIME", "MESA_"))]
    return {
        "kernel": platform.release(),
        "os": {k: os_release.get(k) for k in ("NAME", "ID", "BUILD_ID", "VERSION_ID")},
        "mangohud": run(["mangohud", "--version"]),
        "mangohud_package": run(["pacman", "-Q", "mangohud"]),
        "java": run([java, "-version"]) if java else None,
        "python": sys.version.split()[0],
        "gpu_env": {k: os.environ[k] for k in sorted(env_keys)},
    }


def load_info() -> dict:
    top = run(["ps", "-eo", "pcpu,pmem,comm", "--sort=-pcpu"])
    return {
        "loadavg": read("/proc/loadavg"),
        "cpu_pressure": read("/proc/pressure/cpu"),
        "memory": {l.split(":")[0]: l.split(":")[1].strip() for l in (read("/proc/meminfo") or "").splitlines()
                   if l.startswith(("MemTotal", "MemAvailable", "SwapTotal", "SwapFree"))},
        "top_processes": (top or "").splitlines()[:12],
        "thermal": run(["sensors", "-j"], timeout=5),
    }


def instance_info(instance: Path) -> dict:
    mods_dir = instance / "mods"
    mods = []
    if mods_dir.is_dir():
        for jar in sorted(mods_dir.iterdir()):
            if jar.suffix in (".jar", ".disabled") and jar.is_file():
                mods.append({"file": jar.name, "sha256": sha256(jar), "enabled": jar.suffix == ".jar"})
    options = {}
    for line in (read(str(instance / "options.txt")) or "").splitlines():
        if ":" in line:
            k, v = line.split(":", 1)
            if k in OPTIONS_KEYS:
                options[k] = v
    configs = {}
    for name in ("particlefilter.json", "particle_core_config.toml", "sodium-options.json", "sodium-extra-options.json"):
        p = instance / "config" / name
        if p.exists():
            configs[name] = {"sha256": sha256(p), "content": p.read_text()[:20000]}
    return {"path": str(instance), "mods": mods, "mod_count": len(mods), "options": options, "configs": configs}


def process_info(pid: int) -> dict:
    base = f"/proc/{pid}"
    cmdline = (read(f"{base}/cmdline") or "").replace("\0", " ").strip()
    try:
        affinity = sorted(os.sched_getaffinity(pid))
    except OSError:
        affinity = None
    tasks = glob.glob(f"{base}/task/*")
    return {"pid": pid, "cmdline": cmdline[:8000], "cpu_affinity": affinity, "threads": len(tasks),
            "limits_rttime": [l for l in (read(f"{base}/limits") or "").splitlines() if "realtime timeout" in l]}


def repo_info(repo: Path) -> dict:
    def git(*args):
        return run(["git", "-C", str(repo), *args])
    return {"commit": git("rev-parse", "HEAD"), "branch": git("rev-parse", "--abbrev-ref", "HEAD"),
            "dirty": bool(git("status", "--porcelain", "--untracked-files=no"))}


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--out", required=True)
    p.add_argument("--instance", type=Path)
    p.add_argument("--java")
    p.add_argument("--jvm-args")
    p.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[2])
    p.add_argument("--pid", type=int)
    p.add_argument("--note")
    args = p.parse_args(argv)

    snapshot = {
        "schema": 1,
        "collected_at": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
        "note": args.note,
        "cpu": cpu_info(),
        "power": power_info(),
        "gpu": gpu_info(),
        "display": display_info(),
        "software": software_info(args.java),
        "load": load_info(),
        "particle_filter_repo": repo_info(args.repo),
        "jvm_args": args.jvm_args,
        "instance": instance_info(args.instance) if args.instance else None,
        "process": process_info(args.pid) if args.pid else None,
    }
    Path(args.out).write_text(json.dumps(redact(snapshot), indent=2) + "\n")
    print(f"wrote {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
