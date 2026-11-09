#!/usr/bin/env python3
"""Compare benchmark configurations within one campaign, using paired runs.

Standard library only. Expects the layout described in benchmarks/README.md:

  results/<campaign>/runs/<run-id>/run.json       run metadata (see templates/run.json)
  results/<campaign>/runs/<run-id>/summary.json   output of `frametimes.py summarize --json`

Runs are paired by their `block` (one block = every configuration measured once, in counterbalanced order).
For each configuration against the reference configuration, the per-block difference of each metric is
computed; the report gives the mean difference, its percentage, and a 95% percentile-bootstrap interval.
A difference is only called a speed-up or slow-down when the whole interval lies outside the equivalence
margin; otherwise the result is "equivalent" (interval inside the margin) or "inconclusive".

Runs whose summary is not usable, or whose workload counters differ from their block partner by more than
the tolerance, are excluded and listed in the report. Nothing is dropped silently.

Usage:
  compare.py CAMPAIGN_DIR --reference B [--metric mean_frametime_ms ...] [--margin-pct 1.0]
             [--workload-tolerance-pct 2.0] [--out report.md] [--csv runs.csv]
"""
from __future__ import annotations

import argparse
import csv
import json
import random
import statistics
import sys
from collections import defaultdict
from pathlib import Path

DEFAULT_METRICS = ("mean_frametime_ms", "p99_frametime_ms", "avg_fps", "low_1pct_fps")
# Lower is better for frame times; higher is better for frame rates.
LOWER_IS_BETTER = {"mean_frametime_ms", "median_frametime_ms", "p95_frametime_ms", "p99_frametime_ms",
                   "p99_9_frametime_ms", "max_frametime_ms", "stdev_frametime_ms", "hitches_over_50ms"}
# Workload counters a run.json may carry (from a harness or manual notes). Paired runs must agree on the
# *unfiltered* workload; see PLAN.md "Workload equivalence".
WORKLOAD_KEYS = ("spawn_attempts_per_s", "control_particles_live_mean")
BOOTSTRAP_RESAMPLES = 10_000
BOOTSTRAP_SEED = 20261109


def load_runs(campaign: Path) -> list[dict]:
    runs = []
    for run_dir in sorted((campaign / "runs").glob("*/")):
        meta_path, summary_path = run_dir / "run.json", run_dir / "summary.json"
        if not meta_path.exists():
            continue
        meta = json.loads(meta_path.read_text())
        summary = json.loads(summary_path.read_text()) if summary_path.exists() else None
        runs.append({"dir": run_dir.name, "meta": meta, "summary": summary})
    return runs


def bootstrap_mean_ci(values: list[float], level: float = 0.95, resamples: int = BOOTSTRAP_RESAMPLES,
                      seed: int = BOOTSTRAP_SEED) -> tuple[float, float]:
    """Percentile bootstrap interval for the mean. Fixed seed, so reports are reproducible."""
    if len(values) < 2:
        return (float("nan"), float("nan"))
    rng = random.Random(seed)
    n = len(values)
    means = sorted(statistics.fmean(rng.choices(values, k=n)) for _ in range(resamples))
    lo_i = int((1 - level) / 2 * resamples)
    hi_i = int((1 + level) / 2 * resamples) - 1
    return means[lo_i], means[hi_i]


def verdict(metric: str, ci_pct: tuple[float, float], margin_pct: float) -> str:
    lo, hi = ci_pct
    if lo != lo or hi != hi:  # NaN: fewer than two pairs
        return "not enough pairs"
    if -margin_pct <= lo and hi <= margin_pct:
        return f"equivalent (within ±{margin_pct:g}%)"
    lower_better = metric in LOWER_IS_BETTER
    if lo > margin_pct:
        return "worse" if lower_better else "better"
    if hi < -margin_pct:
        return "better" if lower_better else "worse"
    return "inconclusive"


def _workload_mismatch(a: dict, b: dict, tolerance_pct: float) -> str | None:
    for key in WORKLOAD_KEYS:
        va, vb = a.get("workload", {}).get(key), b.get("workload", {}).get(key)
        if va is None or vb is None:
            continue
        base = max(abs(va), abs(vb), 1e-9)
        if abs(va - vb) / base * 100 > tolerance_pct:
            return f"{key}: {va} vs {vb}"
    return None


def compare(runs: list[dict], reference: str, metrics: list[str], margin_pct: float,
            workload_tolerance_pct: float) -> dict:
    excluded = []
    # block -> config -> list of usable runs (several windows of one config in one block are averaged)
    blocks: dict[str, dict[str, list[dict]]] = defaultdict(lambda: defaultdict(list))
    for r in runs:
        meta, summary = r["meta"], r["summary"]
        if summary is None:
            excluded.append((r["dir"], "no summary.json"))
            continue
        if not summary.get("usable", False):
            codes = ", ".join(i["code"] for i in summary.get("issues", []) if i["level"] == "error")
            excluded.append((r["dir"], f"summary not usable ({codes})"))
            continue
        if meta.get("exclude"):
            excluded.append((r["dir"], f"excluded in run.json: {meta.get('exclude_reason', 'no reason given')}"))
            continue
        blocks[str(meta["block"])][meta["config"]].append(r)

    configs = sorted({c for b in blocks.values() for c in b})
    results: dict[str, dict] = {}
    for config in configs:
        if config == reference:
            continue
        per_metric = {}
        pairs_used = []
        diffs: dict[str, list[float]] = defaultdict(list)
        refs: dict[str, list[float]] = defaultdict(list)
        for block_id, by_config in sorted(blocks.items()):
            if reference not in by_config or config not in by_config:
                continue
            ref_runs, cfg_runs = by_config[reference], by_config[config]
            mismatch = _workload_mismatch(ref_runs[0]["meta"], cfg_runs[0]["meta"], workload_tolerance_pct)
            if mismatch:
                excluded.append((f"block {block_id} {config}", f"workload differs from {reference}: {mismatch}"))
                continue
            pairs_used.append(block_id)
            for m in metrics:
                ref_v = statistics.fmean(x["summary"]["metrics"][m] for x in ref_runs)
                cfg_v = statistics.fmean(x["summary"]["metrics"][m] for x in cfg_runs)
                diffs[m].append(cfg_v - ref_v)
                refs[m].append(ref_v)
        for m in metrics:
            d = diffs[m]
            if not d:
                continue
            ref_mean = statistics.fmean(refs[m])
            lo, hi = bootstrap_mean_ci(d)
            mean_d = statistics.fmean(d)
            pct = lambda v: v / ref_mean * 100 if ref_mean else float("nan")  # noqa: E731
            per_metric[m] = {
                "pairs": len(d),
                "reference_mean": ref_mean,
                "mean_diff": mean_d,
                "mean_diff_pct": pct(mean_d),
                "ci95_diff": [lo, hi],
                "ci95_diff_pct": [pct(lo), pct(hi)],
                "sd_diff": statistics.stdev(d) if len(d) > 1 else float("nan"),
                "verdict": verdict(m, (pct(lo), pct(hi)), margin_pct),
            }
        results[config] = {"blocks": pairs_used, "metrics": per_metric}
    return {"reference": reference, "configs": configs, "margin_pct": margin_pct,
            "results": results, "excluded": excluded}


def to_markdown(report: dict, runs: list[dict], campaign: Path) -> str:
    ref = report["reference"]
    lines = [f"# Comparison: {campaign.name}", "",
             f"Reference configuration: **{ref}**. Equivalence margin ±{report['margin_pct']:g}%. "
             f"Intervals are 95% percentile-bootstrap intervals of the mean paired difference "
             f"({BOOTSTRAP_RESAMPLES} resamples, seed {BOOTSTRAP_SEED}).", ""]
    for config, res in report["results"].items():
        lines += [f"## {config} vs {ref}", "", f"Paired blocks: {', '.join(res['blocks']) or 'none'}", "",
                  "| Metric | Pairs | Reference mean | Mean diff | Diff % | 95% CI (%) | Verdict |",
                  "|---|---|---|---|---|---|---|"]
        for m, r in res["metrics"].items():
            lo, hi = r["ci95_diff_pct"]
            lines.append(f"| {m} | {r['pairs']} | {r['reference_mean']:.4g} | {r['mean_diff']:+.4g} | "
                         f"{r['mean_diff_pct']:+.2f} | [{lo:+.2f}, {hi:+.2f}] | {r['verdict']} |")
        lines.append("")
    lines += ["## Every run", "", "| Run | Config | Block | Usable | Avg FPS | Mean ms | p99 ms | 1% low FPS |",
              "|---|---|---|---|---|---|---|---|"]
    for r in runs:
        s = r["summary"]
        if s:
            m = s["metrics"]
            lines.append(f"| {r['dir']} | {r['meta'].get('config')} | {r['meta'].get('block')} | {s['usable']} | "
                         f"{m['avg_fps']:.1f} | {m['mean_frametime_ms']:.3f} | {m['p99_frametime_ms']:.3f} | "
                         f"{m['low_1pct_fps']:.1f} |")
        else:
            lines.append(f"| {r['dir']} | {r['meta'].get('config')} | {r['meta'].get('block')} | no summary | | | | |")
    lines += ["", "## Excluded", ""]
    lines += [f"- {what}: {why}" for what, why in report["excluded"]] or ["- none"]
    return "\n".join(lines) + "\n"


def write_runs_csv(runs: list[dict], path: Path) -> None:
    keys = ["avg_fps", "mean_frametime_ms", "median_frametime_ms", "p99_frametime_ms", "p99_9_frametime_ms",
            "low_1pct_fps", "low_0_1pct_fps", "frames", "duration_s"]
    with open(path, "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["run", "config", "block", "order", "usable", *keys])
        for r in runs:
            s = r["summary"]
            m = s["metrics"] if s else {}
            w.writerow([r["dir"], r["meta"].get("config"), r["meta"].get("block"), r["meta"].get("order"),
                        s["usable"] if s else "", *[m.get(k, "") for k in keys]])


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("campaign", type=Path)
    p.add_argument("--reference", required=True, help="configuration every other one is compared against")
    p.add_argument("--metric", action="append", help=f"metric(s) to compare (default: {', '.join(DEFAULT_METRICS)})")
    p.add_argument("--margin-pct", type=float, default=1.0, help="equivalence margin in percent (pre-register it)")
    p.add_argument("--workload-tolerance-pct", type=float, default=2.0)
    p.add_argument("--out", type=Path, help="write the Markdown report here")
    p.add_argument("--csv", type=Path, help="write one row per run here")
    args = p.parse_args(argv)

    runs = load_runs(args.campaign)
    if not runs:
        print(f"no runs under {args.campaign / 'runs'}", file=sys.stderr)
        return 1
    report = compare(runs, args.reference, args.metric or list(DEFAULT_METRICS), args.margin_pct,
                     args.workload_tolerance_pct)
    md = to_markdown(report, runs, args.campaign)
    if args.out:
        args.out.write_text(md)
    else:
        print(md)
    if args.csv:
        write_runs_csv(runs, args.csv)
    return 0


if __name__ == "__main__":
    sys.exit(main())
