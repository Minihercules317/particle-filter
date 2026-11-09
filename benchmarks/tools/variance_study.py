#!/usr/bin/env python3
"""Variance study (PLAN.md section 7, phase 4): how noisy one configuration is, and how many blocks a comparison needs.

EXPLORATORY analysis. It reads launches in which ONE configuration ran several windows at the same load
(scenarios/S1-variance.toml) and reports nothing about Particle Filter's performance: every window is the same
configuration, so every "difference" here is noise.

For each launch it uses the measured phases' frames (frames.csv) and computes, per window, the mean frame time
(the primary outcome). Then:

  within-launch   SD of window means inside a launch, in % of the launch mean (pooled over launches)
  null pairs      differences between consecutive windows (1-2, 3-4, 5-6) of a launch, in %: the paired difference
                  a within-launch comparison (C vs B) would see if the two configurations were identical
  between-launch  SD of launch means, in % of the grand mean: the noise an install-level comparison (A vs B) sees,
                  because those configurations need separate launches
  position        mean frame time by window position in the launch (warmup/drift effects)
  window length   the null-pair SD when each window is cut to its first 10, 15, 20 s (shorter windows: more noise)
  blocks needed   n with t(0.975, n-1) * sd_d / sqrt(n) <= h, for half-widths h, for both comparison kinds

Standard library only. Usage:
  variance_study.py RUN_DIR [RUN_DIR ...] [--json out.json]
"""
from __future__ import annotations

import argparse
import csv
import json
import math
import statistics
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import compare  # noqa: E402  (t quantiles)


def window_frames(run: Path) -> list[tuple[str, list[float]]]:
    """[(phase name, frame times in ms)] for every measured phase, in order."""
    phases = [p for p in csv.DictReader(open(run / "phases.csv")) if p["role"] == "measure"]
    by_phase: dict[str, list[float]] = {p["name"]: [] for p in phases}
    with open(run / "frames.csv", newline="") as fh:
        reader = csv.reader(fh)
        next(reader)
        for row in reader:
            if row[3] in by_phase:
                by_phase[row[3]].append(int(row[1]) / 1e6)
    return [(p["name"], by_phase[p["name"]]) for p in phases]


def mean_ft(frames: list[float], seconds: float | None = None) -> float:
    if seconds is not None:
        out, t = [], 0.0
        for f in frames:
            t += f
            if t > seconds * 1000:
                break
            out.append(f)
        frames = out
    return statistics.fmean(frames)


def blocks_needed(sd_pct: float, h_pct: float, minimum: int = 2, cap: int = 500) -> int:
    for n in range(max(2, minimum), cap + 1):
        if compare.t_quantile(0.975, n - 1) * sd_pct / math.sqrt(n) <= h_pct:
            return n
    return cap


def analyse(runs: list[Path]) -> dict:
    launches = []
    for run in runs:
        wins = window_frames(run)
        launches.append({"run": run.name, "windows": [(name, frames) for name, frames in wins]})
    out: dict = {"launches": [], "label": "EXPLORATORY variance study: one configuration, no comparison"}
    within_sd, null_pairs, launch_means = [], [], []
    null_by_len: dict[str, list[float]] = {"10s": [], "15s": [], "20s": [], "full": []}
    by_pos: dict[int, list[float]] = {}
    for la in launches:
        means = [mean_ft(f) for _, f in la["windows"]]
        lm = statistics.fmean(means)
        launch_means.append(lm)
        within_sd.append(statistics.stdev(means) / lm * 100)
        for i, m in enumerate(means, start=1):
            by_pos.setdefault(i, []).append((m - lm) / lm * 100)
        pairs = []
        for a, b in zip(la["windows"][0::2], la["windows"][1::2]):
            d = (mean_ft(b[1]) - mean_ft(a[1])) / lm * 100
            pairs.append(d)
            null_pairs.append(d)
            for key, secs in (("10s", 10), ("15s", 15), ("20s", 20), ("full", None)):
                null_by_len[key].append((mean_ft(b[1], secs) - mean_ft(a[1], secs)) / lm * 100)
        out["launches"].append({"run": la["run"], "fps_mean": 1000 / lm, "mean_frametime_ms": lm,
                                "window_mean_frametime_ms": means, "within_sd_pct": within_sd[-1],
                                "null_pair_diffs_pct": pairs})
    grand = statistics.fmean(launch_means)
    between_sd = statistics.stdev(launch_means) / grand * 100 if len(launch_means) > 1 else float("nan")
    # Root-mean-square of the within-launch SDs (pooled, equal window counts).
    pooled_within = math.sqrt(statistics.fmean([s * s for s in within_sd]))
    sd_null = math.sqrt(statistics.fmean([d * d for d in null_pairs]))   # mean of a null difference is 0 by design
    sd_launch_diff = between_sd * math.sqrt(2)
    out.update({
        "grand_mean_frametime_ms": grand, "grand_fps": 1000 / grand,
        "within_launch_sd_pct_pooled": pooled_within,
        "null_pair_sd_pct": sd_null, "null_pairs": len(null_pairs),
        "null_pair_sd_pct_by_window_length": {k: math.sqrt(statistics.fmean([d * d for d in v])) for k, v in
                                              null_by_len.items() if v},
        "between_launch_sd_pct": between_sd,
        "install_level_pair_sd_pct": sd_launch_diff,
        "position_offset_pct_mean": {pos: statistics.fmean(v) for pos, v in sorted(by_pos.items())},
        "blocks_needed": {
            "within_launch (e.g. C vs B)": {f"h={h}%": blocks_needed(sd_null, h, 8) for h in (0.25, 0.5, 1.0)},
            "install_level (e.g. B vs A)": {f"h={h}%": blocks_needed(sd_launch_diff, h, 8) for h in (0.25, 0.5, 1.0)},
        },
        "note": ("blocks_needed uses the planning rule of PLAN.md section 5 with a minimum of 8 blocks. sd values are "
                 "estimates from few launches; the between-launch SD from 5 launches has wide uncertainty "
                 "(its 95% interval spans roughly 0.6x to 2.9x the estimate)."),
    })
    return out


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("runs", nargs="+", type=Path)
    p.add_argument("--json", type=Path)
    args = p.parse_args(argv)
    res = analyse(args.runs)
    text = json.dumps(res, indent=2)
    if args.json:
        args.json.write_text(text + "\n")
    print(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
