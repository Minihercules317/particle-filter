#!/usr/bin/env python3
"""Line up a launch's system data with the harness's measurement windows. Standard library only.

Reads what benchmarks/harness/pfbench.py saves next to a launch:

  system/              tools/sample_system.py output (meta.json, system.csv, threads.csv, gpu.csv)
  game/gc.log          the JVM's unified GC log (scenario [jvm] args: -Xlog:gc*:file=gc.log:time,uptime,level,tags)
  env-before.json      tools/collect_env.py snapshots taken before the launch and after the game quit
  env-after.json

and turns them into per-window summaries for tools/ingest_harness.py:

- **Clock alignment.** The harness writes every t_ns relative to System.nanoTime() at its origin and records that
  origin in harness.json "clock" (harness 0.3.0+). On Linux System.nanoTime is CLOCK_MONOTONIC, which
  sample_system.py stamps each sample with (mono_ns), so a window is [origin + start_t_ns, origin + end_t_ns] on the
  sampler's clock. nvidia-smi stamps its rows with local wall-clock time; the sampler's (wall, monotonic) clock pair
  converts them. GC-log lines carry JVM uptime; the harness's uptime at origin converts them.
- **Window membership.** A sampler row at mono_ns covers [mono_ns - interval, mono_ns]; only rows whose whole interval
  lies inside the window count. GPU rows are point readings and count when their time is inside the window. A GC
  pause counts when it ends inside the window (the log line is written when the pause ends).
- **CPU-/GPU-bound label** (PLAN.md section 4): GPU-bound if mean NVIDIA utilization >= 95% at stable graphics clocks;
  CPU-bound if the render thread is >= 95% busy and GPU utilization is lower; otherwise "mixed". "unlabelled" when the
  data can't decide (no sampler, no clock alignment, too few samples, or the game doesn't render on the sampled GPU).
  The thresholds are provisional until the variance study (PLAN.md phase 4).

Nothing here judges performance; it only summarizes what the sampler recorded.
"""
from __future__ import annotations

import csv
import hashlib
import json
import re
import statistics
from datetime import datetime
from pathlib import Path

GPU_BOUND_UTIL_PCT = 95.0       # mean NVIDIA utilization at or above this: GPU-bound (with stable clocks)
CPU_BOUND_RENDER_PCT = 95.0     # render thread at least this busy (percent of one CPU) and the GPU below the above
CLOCK_STABLE_SPREAD = 0.05      # graphics clock (max - min) / median at or below this: clocks are stable
MIN_SAMPLES = 3                 # fewer sampler intervals inside a window: unlabelled
RENDER_THREAD = "Render thread"
SERVER_THREAD = "Server thread"
GPU_COLUMNS = ("timestamp", "utilization_gpu", "utilization_memory", "clock_gr_mhz", "clock_mem_mhz", "power_w",
               "temp_c", "pstate", "throttle_reasons")   # sample_system.GPU_QUERY order
NVIDIA_TIME = "%Y/%m/%d %H:%M:%S.%f"
GC_PAUSE = re.compile(r"^\[[^\]]*\]\[(?P<uptime>\d+(?:\.\d+)?)s\]\[\w+\s*\]\[gc\s*\] GC\((?P<id>\d+)\) "
                      r"(?P<kind>Pause .*?)\s(?:\d+[KMG]->\d+[KMG]\(\d+[KMG]\)\s)?(?P<ms>\d+(?:\.\d+)?)ms$")


def _float(v) -> float | None:
    if v is None:
        return None
    v = str(v).strip().split(" ")[0]
    if v in ("", "[N/A]", "N/A", "[Not", "None"):
        return None
    try:
        return float(v)
    except ValueError:
        return None


def _read_csv(path: Path) -> list[dict]:
    if not path.exists():
        return []
    with open(path, newline="") as fh:
        return list(csv.DictReader(fh))


# ---- sampler ----

def load_sampler(directory: Path) -> dict | None:
    """sample_system.py output with every row on the monotonic clock; None if there is no usable sampler data."""
    meta_path = directory / "meta.json"
    if not meta_path.exists():
        return None
    meta = json.loads(meta_path.read_text())
    system = [r for r in _read_csv(directory / "system.csv") if r.get("mono_ns")]
    threads = [r for r in _read_csv(directory / "threads.csv") if r.get("mono_ns")]
    for r in system + threads:
        r["mono_ns"] = int(r["mono_ns"])
    return {"meta": meta, "system": system, "threads": threads, "gpu": load_gpu(directory / "gpu.csv", meta)}


def wall_to_mono(meta: dict):
    """Converter from local wall-clock text (nvidia-smi's timestamp) to CLOCK_MONOTONIC ns, from the sampler's clock pair."""
    pair = meta.get("clock_start") or {}
    if "epoch_ns" not in pair or "mono_ns" not in pair:
        return None
    offset_s = pair.get("utc_offset_s", 0)

    def convert(text: str) -> int | None:
        try:
            naive = datetime.strptime(text.strip(), NVIDIA_TIME)
        except ValueError:
            return None
        epoch_s = (naive - datetime(1970, 1, 1)).total_seconds() - offset_s
        return int(round(epoch_s * 1e9)) - pair["epoch_ns"] + pair["mono_ns"]
    return convert


def load_gpu(path: Path, meta: dict) -> list[dict]:
    if not path.exists():
        return []
    convert = wall_to_mono(meta)
    if convert is None:
        return []
    rows = []
    with open(path, newline="") as fh:
        reader = csv.reader(fh)
        next(reader, None)   # header (names vary between driver versions; columns follow GPU_QUERY's order)
        for raw in reader:
            if len(raw) < len(GPU_COLUMNS):
                continue
            row = dict(zip(GPU_COLUMNS, (c.strip() for c in raw)))
            mono = convert(row["timestamp"])
            if mono is None:
                continue
            rows.append({"mono_ns": mono, "utilization_gpu": _float(row["utilization_gpu"]),
                         "clock_gr_mhz": _float(row["clock_gr_mhz"]), "power_w": _float(row["power_w"]),
                         "temp_c": _float(row["temp_c"]), "pstate": row["pstate"],
                         "throttle_reasons": row["throttle_reasons"]})
    return rows


def window_mono(harness: dict, start_t_ns: int, end_t_ns: int) -> tuple[int, int] | None:
    origin = (harness.get("clock") or {}).get("origin_nano_time")
    if origin is None:
        return None
    return origin + start_t_ns, origin + end_t_ns


def _mean(values: list[float]) -> float | None:
    return statistics.fmean(values) if values else None


def window_system(sampler: dict, start: int, end: int) -> dict:
    """Summary of the sampler rows inside [start, end] (monotonic ns)."""
    interval_ns = int(float(sampler["meta"].get("interval_s", 1.0)) * 1e9)
    inside = lambda r: r["mono_ns"] - interval_ns >= start and r["mono_ns"] <= end  # noqa: E731
    sys_rows = [r for r in sampler["system"] if inside(r)]
    thr_rows = [r for r in sampler["threads"] if inside(r)]
    gpu_rows = [r for r in sampler["gpu"] if start <= r["mono_ns"] <= end]
    pcpus = set(sampler["meta"].get("performance_cpus") or [])

    def thread(name: str) -> dict:
        rows = [r for r in thr_rows if r["name"] == name]
        pct = [float(r["cpu_pct"]) for r in rows]
        on_p = [int(r["last_cpu"]) in pcpus for r in rows] if pcpus else []
        return {"samples": len(rows), "cpu_pct_mean": _mean(pct), "cpu_pct_max": max(pct) if pct else None,
                "on_performance_core_fraction": sum(on_p) / len(on_p) if on_p else None}

    col = lambda rows, k: [v for v in (_float(r.get(k)) for r in rows) if v is not None]  # noqa: E731
    clocks = col(gpu_rows, "clock_gr_mhz")
    util = col(gpu_rows, "utilization_gpu")
    temps = col(sys_rows, "pkg_temp_c")
    reasons = sorted({r["throttle_reasons"] for r in gpu_rows if r["throttle_reasons"]
                      and _hex_nonzero(r["throttle_reasons"])})
    return {
        "samples": len(sys_rows),
        "interval_s": interval_ns / 1e9,
        "render_thread": thread(RENDER_THREAD),
        "server_thread": thread(SERVER_THREAD),
        "cpu_busy_pct_mean": _mean(col(sys_rows, "cpu_busy_pct")),
        "pcore_mhz_mean": _mean(col(sys_rows, "pcore_mhz_mean")),
        "ecore_mhz_mean": _mean(col(sys_rows, "ecore_mhz_mean")),
        "pkg_temp_c_max": max(temps) if temps else None,
        "gpu": {"samples": len(gpu_rows), "utilization_pct_mean": _mean(util),
                "clock_gr_mhz_min": min(clocks) if clocks else None,
                "clock_gr_mhz_max": max(clocks) if clocks else None,
                "clock_gr_mhz_median": statistics.median(clocks) if clocks else None,
                "power_w_mean": _mean(col(gpu_rows, "power_w")),
                "temp_c_max": max(col(gpu_rows, "temp_c"), default=None),
                "pstates": sorted({r["pstate"] for r in gpu_rows if r["pstate"]}),
                "throttle_reasons_active": reasons},
    }


def _hex_nonzero(text: str) -> bool:
    try:
        return int(text, 16) != 0
    except ValueError:
        return True   # unknown text: keep it visible


def bound_label(summary: dict | None, renders_on_sampled_gpu: bool) -> tuple[str, str]:
    """(label, reason). Labels: gpu-bound, cpu-bound, mixed, unlabelled."""
    if summary is None:
        return "unlabelled", "no system-sampler data aligned with this window"
    if summary["samples"] < MIN_SAMPLES:
        return "unlabelled", f"{summary['samples']} sampler intervals inside the window (< {MIN_SAMPLES})"
    gpu = summary["gpu"]
    util = gpu["utilization_pct_mean"]
    render = summary["render_thread"]["cpu_pct_mean"]
    if not renders_on_sampled_gpu:
        return "unlabelled", "the game doesn't render on the GPU nvidia-smi samples"
    if util is None or gpu["samples"] < MIN_SAMPLES:
        return "unlabelled", "no GPU utilization samples inside the window"
    if render is None:
        return "unlabelled", "render thread not sampled"
    median = gpu["clock_gr_mhz_median"]
    stable = bool(median) and (gpu["clock_gr_mhz_max"] - gpu["clock_gr_mhz_min"]) / median <= CLOCK_STABLE_SPREAD
    detail = f"GPU {util:.1f}% (clocks {'stable' if stable else 'unstable'}), render thread {render:.1f}%"
    if util >= GPU_BOUND_UTIL_PCT and stable:
        return "gpu-bound", detail
    if render >= CPU_BOUND_RENDER_PCT and util < GPU_BOUND_UTIL_PCT:
        return "cpu-bound", detail
    return "mixed", detail


# ---- GC log ----

def parse_gc_log(path: Path) -> list[dict]:
    """Stop-the-world pauses: {"id", "kind", "end_uptime_ms", "pause_ms"}. Concurrent phases are not pauses."""
    if not path.exists():
        return []
    out = []
    with open(path, errors="replace") as fh:
        for line in fh:
            m = GC_PAUSE.match(line.rstrip("\n"))
            if m:
                out.append({"id": int(m["id"]), "kind": m["kind"].strip(), "end_uptime_ms": float(m["uptime"]) * 1000,
                            "pause_ms": float(m["ms"])})
    return out


def window_gc(pauses: list[dict], harness: dict, start_t_ns: int, end_t_ns: int) -> dict | None:
    """GC pauses that ended inside the window (JVM uptime has millisecond resolution in the log)."""
    up0 = (harness.get("clock") or {}).get("origin_jvm_uptime_ms")
    if up0 is None:
        return None
    lo, hi = up0 + start_t_ns / 1e6, up0 + end_t_ns / 1e6
    inside = [p for p in pauses if lo <= p["end_uptime_ms"] <= hi]
    return {"pauses": len(inside), "pause_ms_total": sum(p["pause_ms"] for p in inside),
            "pause_ms_max": max((p["pause_ms"] for p in inside), default=0.0),
            "kinds": sorted({p["kind"] for p in inside})}


# ---- environment snapshots ----

def power_state(env: dict) -> dict | None:
    """The settings from a collect_env.py snapshot that cap or shape performance (recorded, never changed)."""
    if not env:
        return None
    cpu, power = env.get("cpu") or {}, env.get("power") or {}
    return {
        "ac_online": sorted(n for n, s in power.items() if s.get("type") == "Mains" and s.get("online") == "1"),
        "platform_profile": cpu.get("platform_profile"),
        "power_profile": cpu.get("power_profile"),
        "governor": cpu.get("governor"),
        "energy_performance_preference": cpu.get("energy_performance_preference"),
        "intel_no_turbo": cpu.get("intel_no_turbo"),
        "rapl_limits_w": cpu.get("rapl_limits_w"),
    }


def power_state_sha256(state: dict | None) -> str | None:
    if state is None:
        return None
    return hashlib.sha256(json.dumps(state, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
