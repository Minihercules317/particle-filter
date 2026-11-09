#!/usr/bin/env python3
"""Parse raw per-frame logs and compute frame-time metrics.

Standard library only. Reads:
  * MangoHud CSV logs (with or without `log_versioning` headers), where `frametime` is in milliseconds
    and `elapsed` is nanoseconds since the log started;
  * generic CSVs with a `frametime_ms` or `frame_ns` column (for example from a future benchmark harness).
Files ending in .gz are decompressed transparently.

Every metric is defined in benchmarks/PLAN.md ("Metric definitions"); the code below is the reference
implementation of those definitions. Summary values are never taken from MangoHud's own summary files.

Usage:
  frametimes.py summarize LOG.csv [--skip-seconds S] [--duration-seconds D] [--cap-fps N ...] [--json OUT]
  frametimes.py per-second LOG.csv          # FPS per 1-second bucket, for spotting drift by eye
"""
from __future__ import annotations

import argparse
import csv
import gzip
import io
import json
import math
import statistics
import sys
from dataclasses import dataclass, field
from pathlib import Path

SCHEMA_VERSION = 1

# Thresholds for the automatic sanity checks. They flag a run for a human to look at; they do not
# silently drop data.
MIN_FRAMES = 1000                 # fewer frames than this makes 1%/0.1% lows meaningless
HITCH_MS = 50.0                   # a frame this long is a visible hitch at any frame rate
FREEZE_MS = 1000.0                # a frame this long almost certainly is a pause, alt-tab or load
ELAPSED_MISMATCH = 0.02           # sum(frametime) vs. elapsed span: >2% means frames were not all logged
CAP_TOLERANCE = 0.01              # mean FPS within 1% of a known cap means the run is frame-limited
DRIFT_LIMIT = 0.03                # >3% FPS change from first to last third of the window: drift/thermal

# Columns averaged into the summary when present (MangoHud names).
AUX_COLUMNS = ("cpu_load", "gpu_load", "cpu_temp", "gpu_temp", "gpu_core_clock", "gpu_mem_clock",
               "gpu_power", "cpu_power", "gpu_vram_used", "ram_used", "swap_used", "process_rss")


@dataclass
class FrameLog:
    """Frame times in milliseconds plus whatever else the log carried."""
    frametimes_ms: list[float]
    elapsed_ns: list[int] | None = None          # time since log start at each frame, if logged
    aux: dict[str, list[float]] = field(default_factory=dict)
    system_info: dict[str, str] = field(default_factory=dict)
    source_format: str = "unknown"
    rows_skipped: int = 0                        # unparsable rows (reported, never hidden)


def _open_text(path: Path) -> io.TextIOBase:
    if path.suffix == ".gz":
        return io.TextIOWrapper(gzip.open(path, "rb"), encoding="utf-8", errors="replace")
    return open(path, encoding="utf-8", errors="replace")


def _to_float(value: str) -> float | None:
    try:
        f = float(value)
    except (TypeError, ValueError):
        return None
    return f if math.isfinite(f) else None


def read_log(path: str | Path) -> FrameLog:
    """Reads a MangoHud or generic frame log. Raises ValueError if no frame-time column is found."""
    path = Path(path)
    with _open_text(path) as fh:
        lines = fh.read().splitlines()

    system_info: dict[str, str] = {}
    header_index = None
    for i, line in enumerate(lines):
        fields = [f.strip() for f in line.split(",")]
        if fields and fields[0] == "os" and "cpu" in fields and i + 1 < len(lines):
            values = next(csv.reader([lines[i + 1]]))
            system_info.update({k: v.strip() for k, v in zip(fields, values)})
        if any(name in fields for name in ("frametime", "frametime_ms", "frame_ns")):
            header_index = i
            break
    if header_index is None:
        raise ValueError(f"{path}: no 'frametime', 'frametime_ms' or 'frame_ns' column found")

    reader = csv.DictReader(lines[header_index:])
    columns = reader.fieldnames or []
    if "frametime" in columns:
        fmt, ft_col, scale = "mangohud", "frametime", 1.0
    elif "frametime_ms" in columns:
        fmt, ft_col, scale = "generic-ms", "frametime_ms", 1.0
    else:
        fmt, ft_col, scale = "generic-ns", "frame_ns", 1e-6
    elapsed_col = "elapsed" if "elapsed" in columns else ("t_ns" if "t_ns" in columns else None)
    aux_cols = [c for c in AUX_COLUMNS if c in columns]

    frametimes: list[float] = []
    elapsed: list[int] = []
    aux: dict[str, list[float]] = {c: [] for c in aux_cols}
    skipped = 0
    for row in reader:
        ft = _to_float(row.get(ft_col, ""))
        if ft is None:
            skipped += 1
            continue
        frametimes.append(ft * scale)
        if elapsed_col:
            e = _to_float(row.get(elapsed_col, ""))
            elapsed.append(int(e) if e is not None else -1)
        for c in aux_cols:
            v = _to_float(row.get(c, ""))
            if v is not None:
                aux[c].append(v)
    return FrameLog(frametimes, elapsed if elapsed_col else None, aux, system_info, fmt, skipped)


def select_window(log: FrameLog, skip_s: float = 0.0, duration_s: float | None = None) -> FrameLog:
    """Keeps frames that end after `skip_s` seconds and no later than `skip_s + duration_s`.

    Time is the running sum of frame times, which is what the frame times themselves describe. (MangoHud's
    `elapsed` column is checked against it in sanity_checks but not used to cut, so cutting works the same
    for logs without it.)
    """
    start_ms = skip_s * 1000.0
    end_ms = math.inf if duration_s is None else start_ms + duration_s * 1000.0
    keep: list[int] = []
    t = 0.0
    for i, ft in enumerate(log.frametimes_ms):
        t += ft
        if t <= start_ms:
            continue
        if t > end_ms:
            break
        keep.append(i)
    elapsed = [log.elapsed_ns[i] for i in keep] if log.elapsed_ns is not None else None
    # Aux columns may have gaps (unparsable cells), so they are summarized over the whole log, not the window.
    return FrameLog([log.frametimes_ms[i] for i in keep], elapsed, log.aux, log.system_info,
                    log.source_format, log.rows_skipped)


def percentile_nearest_rank(sorted_values: list[float], p: float) -> float:
    """Nearest-rank percentile: the smallest value with at least p% of values at or below it."""
    if not sorted_values:
        raise ValueError("no values")
    if not 0 < p <= 100:
        raise ValueError("p must be in (0, 100]")
    rank = math.ceil(p / 100.0 * len(sorted_values))
    return sorted_values[max(rank, 1) - 1]


def worst_fraction_mean(sorted_values: list[float], fraction: float) -> float:
    """Mean of the slowest `fraction` of frame times (at least one frame)."""
    k = max(1, math.ceil(fraction * len(sorted_values)))
    return statistics.fmean(sorted_values[-k:])


def compute_metrics(frametimes_ms: list[float]) -> dict:
    if not frametimes_ms:
        raise ValueError("no frames in window")
    ft = sorted(frametimes_ms)
    n = len(ft)
    total_ms = math.fsum(frametimes_ms)
    mean_ft = total_ms / n
    p99 = percentile_nearest_rank(ft, 99)
    return {
        "frames": n,
        "duration_s": total_ms / 1000.0,
        # Time-weighted average: frames divided by time. Not the mean of per-frame FPS values.
        "avg_fps": 1000.0 * n / total_ms,
        "mean_frametime_ms": mean_ft,
        "median_frametime_ms": statistics.median(ft),
        "stdev_frametime_ms": statistics.stdev(ft) if n > 1 else 0.0,
        "p90_frametime_ms": percentile_nearest_rank(ft, 90),
        "p95_frametime_ms": percentile_nearest_rank(ft, 95),
        "p99_frametime_ms": p99,
        "p99_9_frametime_ms": percentile_nearest_rank(ft, 99.9),
        "max_frametime_ms": ft[-1],
        "min_frametime_ms": ft[0],
        # "1% low": average frame rate over the slowest 1% of frames (see PLAN.md for why this definition).
        "low_1pct_fps": 1000.0 / worst_fraction_mean(ft, 0.01),
        "low_0_1pct_fps": 1000.0 / worst_fraction_mean(ft, 0.001),
        # The 99th-percentile frame time expressed as a frame rate; some tools call this "1% low".
        "p99_as_fps": 1000.0 / p99,
        "hitches_over_50ms": sum(1 for x in frametimes_ms if x > HITCH_MS),
    }


def per_second_fps(frametimes_ms: list[float]) -> list[float]:
    """Frame rate of each complete 1-second bucket of the window (time from the running sum)."""
    out: list[float] = []
    t = 0.0
    count = 0
    bucket_end = 1000.0
    bucket_time = 0.0
    for ft in frametimes_ms:
        t += ft
        count += 1
        bucket_time += ft
        if t >= bucket_end:
            out.append(1000.0 * count / bucket_time)
            count = 0
            bucket_time = 0.0
            bucket_end += 1000.0
    return out


def sanity_checks(window: FrameLog, metrics: dict, cap_fps: list[float]) -> list[dict]:
    """Returns a list of {level, code, message}. `error` means the run must not be used as is."""
    issues: list[dict] = []

    def add(level: str, code: str, message: str) -> None:
        issues.append({"level": level, "code": code, "message": message})

    ft = window.frametimes_ms
    if window.rows_skipped:
        add("warning", "unparsable_rows", f"{window.rows_skipped} rows had no usable frame time")
    if metrics["frames"] < MIN_FRAMES:
        add("error", "too_few_frames", f"{metrics['frames']} frames (< {MIN_FRAMES}); lows are unreliable")
    bad = sum(1 for x in ft if x <= 0)
    if bad:
        add("error", "nonpositive_frametime", f"{bad} frames with frame time <= 0")
    freezes = sum(1 for x in ft if x > FREEZE_MS)
    if freezes:
        add("error", "freeze", f"{freezes} frames longer than {FREEZE_MS:.0f} ms (pause, load or focus loss?)")
    if metrics["hitches_over_50ms"]:
        add("info", "hitches", f"{metrics['hitches_over_50ms']} frames longer than {HITCH_MS:.0f} ms")

    if window.elapsed_ns and len(window.elapsed_ns) > 1:
        e = window.elapsed_ns
        if any(x < 0 for x in e):
            add("warning", "elapsed_missing", "some rows have no elapsed timestamp")
        elif any(b < a for a, b in zip(e, e[1:])):
            add("error", "elapsed_not_monotonic", "elapsed timestamps go backwards")
        else:
            span_ms = (e[-1] - e[0]) / 1e6
            summed_ms = math.fsum(ft[1:])  # the first frame's time precedes the first timestamp
            if span_ms > 0 and abs(summed_ms - span_ms) / span_ms > ELAPSED_MISMATCH:
                add("error", "frames_not_all_logged",
                    f"sum of frame times {summed_ms:.0f} ms vs elapsed span {span_ms:.0f} ms: the log is not "
                    "per-frame (check log_interval=0)")

    for cap in cap_fps:
        if cap > 0 and abs(metrics["avg_fps"] - cap) / cap <= CAP_TOLERANCE:
            add("warning", "frame_limited",
                f"average {metrics['avg_fps']:.1f} FPS is within {CAP_TOLERANCE:.0%} of the {cap:g} FPS cap: the run "
                "measures the limiter, not CPU/GPU cost")

    buckets = per_second_fps(ft)
    if len(buckets) >= 6:
        third = len(buckets) // 3
        first = statistics.fmean(buckets[:third])
        last = statistics.fmean(buckets[-third:])
        change = (last - first) / first
        if abs(change) > DRIFT_LIMIT:
            add("warning", "drift",
                f"FPS changed {change:+.1%} from the first to the last third of the window "
                "(thermal throttling, background load or unfinished warmup?)")
    return issues


def summarize(path: str | Path, skip_s: float = 0.0, duration_s: float | None = None,
              cap_fps: list[float] | None = None) -> dict:
    log = read_log(path)
    window = select_window(log, skip_s, duration_s)
    if not window.frametimes_ms:
        raise ValueError(f"{path}: window is empty (log has {len(log.frametimes_ms)} frames, "
                         f"{math.fsum(log.frametimes_ms) / 1000:.1f} s)")
    metrics = compute_metrics(window.frametimes_ms)
    if duration_s is not None and metrics["duration_s"] < 0.98 * duration_s:
        short = True
    else:
        short = False
    issues = sanity_checks(window, metrics, cap_fps or [])
    if short:
        issues.append({"level": "error", "code": "window_short",
                       "message": f"window has {metrics['duration_s']:.1f} s of frames, wanted {duration_s:g} s"})
    aux = {c: statistics.fmean(v) for c, v in log.aux.items() if v}
    return {
        "schema": SCHEMA_VERSION,
        "source": str(path).replace(str(Path.home()), "~"),  # no home directory in published summaries
        "source_format": log.source_format,
        "log_frames_total": len(log.frametimes_ms),
        "window": {"skip_s": skip_s, "duration_s": duration_s},
        "metrics": metrics,
        "aux_means_whole_log": aux,
        "system_info": log.system_info,
        "issues": issues,
        "usable": not any(i["level"] == "error" for i in issues),
    }


def _print_summary(s: dict) -> None:
    m = s["metrics"]
    print(f"{s['source']}  ({s['source_format']}, {m['frames']} frames, {m['duration_s']:.1f} s)")
    print(f"  avg FPS {m['avg_fps']:.1f} | mean {m['mean_frametime_ms']:.3f} ms | median {m['median_frametime_ms']:.3f} ms"
          f" | p99 {m['p99_frametime_ms']:.3f} ms | 1% low {m['low_1pct_fps']:.1f} FPS | 0.1% low {m['low_0_1pct_fps']:.1f} FPS")
    for issue in s["issues"]:
        print(f"  [{issue['level']}] {issue['code']}: {issue['message']}")
    print(f"  usable: {s['usable']}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    p_sum = sub.add_parser("summarize", help="metrics + sanity checks for one log")
    p_sum.add_argument("log")
    p_sum.add_argument("--skip-seconds", type=float, default=0.0, help="discard this much time at the start")
    p_sum.add_argument("--duration-seconds", type=float, default=None, help="measure this long after the skip")
    p_sum.add_argument("--cap-fps", type=float, action="append", default=[],
                       help="known frame cap(s) to flag (repeatable), e.g. 60 for menus")
    p_sum.add_argument("--json", help="write the summary here")
    p_sec = sub.add_parser("per-second", help="FPS per 1-second bucket")
    p_sec.add_argument("log")
    args = parser.parse_args(argv)

    if args.command == "summarize":
        s = summarize(args.log, args.skip_seconds, args.duration_seconds, args.cap_fps)
        _print_summary(s)
        if args.json:
            Path(args.json).write_text(json.dumps(s, indent=2, sort_keys=True) + "\n")
        return 0 if s["usable"] else 2
    if args.command == "per-second":
        for i, fps in enumerate(per_second_fps(read_log(args.log).frametimes_ms)):
            print(f"{i}\t{fps:.1f}")
        return 0
    return 1


if __name__ == "__main__":
    sys.exit(main())
