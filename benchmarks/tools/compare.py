#!/usr/bin/env python3
"""Compare benchmark configurations within one campaign, using paired runs.

Standard library only. Expects the layout described in benchmarks/README.md:

  results/<campaign>/runs/<run-id>/run.json       run metadata (see templates/run.json)
  results/<campaign>/runs/<run-id>/summary.json   output of `frametimes.py summarize --json`

Runs are paired by their `block` (one block = every configuration measured once, in counterbalanced order).
For each configuration against the reference configuration, the per-block difference of each metric is
computed; the report gives the mean difference, its percentage, a 95% Student-t interval of the mean paired
difference (the basis of every verdict) and, for comparison, a 95% percentile-bootstrap interval. A difference is
only called a speed-up or slow-down when the whole t-interval lies outside the equivalence margin; otherwise the
result is "equivalent" (interval inside the margin) or "inconclusive". (Changed 2026-11-09, before any official
campaign: with the planned 8-12 blocks a percentile bootstrap is too narrow, by roughly 10-15%, and would call
differences too readily.)

Runs whose summary is not usable, whose workload counters per client tick differ from their block partner by more
than the tolerance, or whose environment (run.json "environment": scenario, harness build, rendering mods and their
configs, GPU, window, JVM arguments, launch shape...) differs from their partner's, are excluded and listed in the
report. Nothing is dropped silently.

Runs are compared within a stratum (run.json "stratum", e.g. load level / variant / mod set), never across. Inside
a stratum, each pair goes into one dataset and is never aggregated with another (PLAN.md sections 4 and 5):
  primary      both windows held 20 client ticks per second (achieved >= 19.6): the primary FPS dataset
  saturation   either window fell below that: saturation / stress-test results, with achieved TPS and requests per
               real-world second reported next to the frame times
crossed with the pair's CPU-/GPU-bound label (run.json "bound"; "differs" when the two windows' labels differ).
Pairs where the configuration held 20 TPS and the reference did not (and the reverse) are listed on their own.
Every window, including unfavourable and excluded ones, is listed in the run table.
The report also shows how often each configuration ran in each position, so unbalanced orders are visible.

Only runs labelled "performance" are compared. Functional (bring-up) runs are refused unless --allow-functional
is given, which is for testing this pipeline; the report then says in its title that it is not a result.

Usage:
  compare.py CAMPAIGN_DIR --reference B [--metric mean_frametime_ms ...] [--margin-pct 1.0]
             [--workload-tolerance-pct 2.0] [--out report.md] [--csv runs.csv] [--allow-functional]
"""
from __future__ import annotations

import argparse
import csv
import json
import math
import random
import statistics
import sys
from collections import Counter, defaultdict
from pathlib import Path

DEFAULT_METRICS = ("mean_frametime_ms", "p99_frametime_ms", "avg_fps", "low_1pct_fps")
# Lower is better for frame times; higher is better for frame rates.
LOWER_IS_BETTER = {"mean_frametime_ms", "median_frametime_ms", "p95_frametime_ms", "p99_frametime_ms",
                   "p99_9_frametime_ms", "max_frametime_ms", "stdev_frametime_ms", "hitches_over_50ms"}
# Workload counters a run.json may carry. Paired runs must agree on the *unfiltered* workload per client tick
# (PLAN.md "Workload equivalence" and section 5): requests per tick are fixed by the plan, requests per second are not.
WORKLOAD_KEYS_PER_TICK = ("attempts_per_tick", "control_attempts_per_tick", "control_particles_live_mean")
WORKLOAD_KEYS_PER_SECOND = ("spawn_attempts_per_s", "control_particles_live_mean")   # runs without tick counters
# A window is reduced-TPS below 19.6 achieved client ticks per second (ingest_harness.REDUCED_TPS_BELOW).
REDUCED_TPS_BELOW = 19.6
# Extra metrics every saturation dataset reports: the tick rate itself and the real-time request rate.
SATURATION_METRICS = ("achieved_tps", "requests_per_s")
DESCRIPTIVE = {"requests_per_s"}   # differences are reported, never given a verdict
DATASET_TITLES = {
    "primary": "Primary FPS dataset (both windows at 20 TPS)",
    "saturation": "Saturation / stress-test dataset (either window below 20 TPS)",
    "tps-unknown": "TPS unknown (runs without tick counters)",
}
# Environment keys that may legitimately differ between paired runs: none so far. Every other key of
# run.json["environment"] must be equal (missing on both sides counts as equal).
ENVIRONMENT_IGNORED: frozenset[str] = frozenset()
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


def _betacf(a: float, b: float, x: float) -> float:
    """Continued fraction of the regularized incomplete beta function (modified Lentz; Numerical Recipes 6.4)."""
    tiny, qab, qap, qam = 1e-300, a + b, a + 1.0, a - 1.0
    c, d = 1.0, 1.0 - qab * x / qap
    d = 1.0 / (d if abs(d) > tiny else tiny)
    h = d
    for m in range(1, 300):
        m2 = 2 * m
        aa = m * (b - m) * x / ((qam + m2) * (a + m2))
        d = 1.0 + aa * d
        d = 1.0 / (d if abs(d) > tiny else tiny)
        c = 1.0 + aa / c
        c = c if abs(c) > tiny else tiny
        h *= d * c
        aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2))
        d = 1.0 + aa * d
        d = 1.0 / (d if abs(d) > tiny else tiny)
        c = 1.0 + aa / c
        c = c if abs(c) > tiny else tiny
        delta = d * c
        h *= delta
        if abs(delta - 1.0) < 1e-15:
            break
    return h


def _betainc(a: float, b: float, x: float) -> float:
    if x <= 0.0:
        return 0.0
    if x >= 1.0:
        return 1.0
    front = math.exp(math.lgamma(a + b) - math.lgamma(a) - math.lgamma(b) + a * math.log(x) + b * math.log(1.0 - x))
    if x < (a + 1.0) / (a + b + 2.0):
        return front * _betacf(a, b, x) / a
    return 1.0 - front * _betacf(b, a, 1.0 - x) / b


def t_cdf(t: float, df: int) -> float:
    """Student-t cumulative distribution (standard library only)."""
    tail = 0.5 * _betainc(df / 2.0, 0.5, df / (df + t * t))
    return 1.0 - tail if t >= 0 else tail


def t_quantile(p: float, df: int) -> float:
    """Inverse of t_cdf by bisection (accurate to ~1e-12)."""
    lo, hi = -1e3, 1e3
    for _ in range(200):
        mid = (lo + hi) / 2
        if t_cdf(mid, df) < p:
            lo = mid
        else:
            hi = mid
    return (lo + hi) / 2


def t_mean_ci(values: list[float], level: float = 0.95) -> tuple[float, float]:
    """Student-t interval for the mean of paired differences: mean ± t(1-(1-level)/2, n-1) · sd/√n."""
    n = len(values)
    if n < 2:
        return (float("nan"), float("nan"))
    m = statistics.fmean(values)
    half = t_quantile(1 - (1 - level) / 2, n - 1) * statistics.stdev(values) / math.sqrt(n)
    return m - half, m + half


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
    """Paired runs must request the same particles per tick. Per tick, not per second: a configuration that drops
    below 20 TPS gets fewer requests per second by design (PLAN.md section 5), and that is a result, not a mismatch.
    Runs without per-tick counters (manual runs) fall back to the per-second keys."""
    wa, wb = a.get("workload", {}), b.get("workload", {})
    keys = WORKLOAD_KEYS_PER_TICK if any(k in wa or k in wb for k in WORKLOAD_KEYS_PER_TICK) else WORKLOAD_KEYS_PER_SECOND
    for key in keys:
        va, vb = wa.get(key), wb.get(key)
        if va is None or vb is None:
            continue
        base = max(abs(va), abs(vb), 1e-9)
        if abs(va - vb) / base * 100 > tolerance_pct:
            return f"{key}: {va} vs {vb}"
    return None


def _environment_mismatch(a: dict, b: dict) -> str | None:
    ea, eb = a.get("environment") or {}, b.get("environment") or {}
    diffs = [k for k in sorted(set(ea) | set(eb)) if k not in ENVIRONMENT_IGNORED and ea.get(k) != eb.get(k)]
    if diffs:
        return "; ".join(f"{k}: {ea.get(k)!r} vs {eb.get(k)!r}" for k in diffs[:3]) + (" ..." if len(diffs) > 3 else "")
    return None


def achieved_tps(meta: dict) -> float | None:
    v = meta.get("achieved_tps")
    if v is None:
        w = meta.get("workload") or {}
        v = w.get("achieved_tps", w.get("client_ticks_per_s"))
    return v


def window_tps_class(meta: dict) -> str | None:
    """"20tps", "reduced", or None (no tick data, e.g. manual runs)."""
    if meta.get("tps_class"):
        return meta["tps_class"]
    tps = achieved_tps(meta)
    if tps is None:
        return None
    return "reduced" if tps < REDUCED_TPS_BELOW else "20tps"


def pair_tps_dataset(ref_class: str | None, cfg_class: str | None) -> str:
    """primary: both windows at 20 TPS. saturation: either below. tps-unknown: no tick data, none reduced."""
    if "reduced" in (ref_class, cfg_class):
        return "saturation"
    if ref_class == cfg_class == "20tps":
        return "primary"
    return "tps-unknown"


def pair_bound(ref_bound: str, cfg_bound: str) -> str:
    return ref_bound if ref_bound == cfg_bound else "differs"


def metric_value(run: dict, metric: str) -> float | None:
    """A frame-time metric from summary.json, or a window value from run.json (achieved_tps, workload rates)."""
    m = (run["summary"] or {}).get("metrics", {})
    if metric in m:
        return m[metric]
    meta = run["meta"]
    if metric == "achieved_tps":
        return achieved_tps(meta)
    if metric in (meta.get("workload") or {}):
        return meta["workload"][metric]
    return (meta.get("per_tick") or {}).get(metric)


def _uniform(runs: list[dict], key) -> tuple[bool, object]:
    values = {key(r) for r in runs}
    return len(values) == 1, next(iter(values))


def labels_of(runs: list[dict]) -> set[str]:
    return {str(r["meta"].get("label", "unlabelled")) for r in runs}


def check_labels(runs: list[dict], allow_functional: bool) -> str | None:
    """None if the runs may be compared, else the reason they may not."""
    labels = labels_of(runs)
    if len(labels) > 1:
        return f"runs with different labels can't be compared: {sorted(labels)}"
    if labels != {"performance"} and not allow_functional:
        return (f"runs are labelled {sorted(labels)}, not 'performance'. Functional runs are not performance data; "
                "use --allow-functional only to test the pipeline")
    return None


def order_balance(runs: list[dict]) -> dict:
    """stratum -> config -> {"position_in_launch": Counter, "launch_in_block": Counter}."""
    out: dict = defaultdict(lambda: defaultdict(lambda: {"position_in_launch": Counter(), "launch_in_block": Counter()}))
    for r in runs:
        m = r["meta"]
        cell = out[str(m.get("stratum", "all"))][m.get("config")]
        if m.get("position_in_launch") is not None:
            cell["position_in_launch"][int(m["position_in_launch"])] += 1
        if m.get("launch_in_block") is not None:
            cell["launch_in_block"][int(m["launch_in_block"])] += 1
    return out


def _unbalanced(counts: Counter) -> bool:
    if not counts:
        return False
    full = [counts.get(k, 0) for k in range(1, max(counts) + 1)]
    return max(full) - min(full) > 1


def compare(runs: list[dict], reference: str, metrics: list[str], margin_pct: float,
            workload_tolerance_pct: float) -> dict:
    """Compares within each stratum and, inside it, within each dataset (TPS class x bound label of the pair).
    Returns {"strata": {name: report}} plus the excluded list."""
    strata: dict[str, list[dict]] = defaultdict(list)
    for r in runs:
        strata[str(r["meta"].get("stratum", "all"))].append(r)
    out = {"reference": reference, "margin_pct": margin_pct, "strata": {}, "excluded": []}
    for name, members in sorted(strata.items()):
        rep = compare_stratum(members, reference, metrics, margin_pct, workload_tolerance_pct)
        out["strata"][name] = rep
        out["excluded"] += [(f"[{name}] {what}", why) for what, why in rep["excluded"]]
    out["configs"] = sorted({c for s in out["strata"].values() for c in s["configs"]})
    return out


def _window_row(runs: list[dict]) -> dict:
    """What the report shows for one side of a pair (several windows of one config in a block are averaged)."""
    val = lambda m: _mean_or_none([metric_value(r, m) for r in runs])  # noqa: E731
    return {"runs": [r["dir"] for r in runs], "tps": val("achieved_tps"), "tps_class": window_tps_class(runs[0]["meta"]),
            "bound": runs[0]["meta"].get("bound", "unlabelled"), "avg_fps": val("avg_fps"),
            "mean_frametime_ms": val("mean_frametime_ms"), "p99_frametime_ms": val("p99_frametime_ms"),
            "low_1pct_fps": val("low_1pct_fps"), "requests_per_tick": val("requests_per_tick"),
            "requests_per_s": val("requests_per_s"), "frames_per_tick": val("frames_per_tick")}


def _mean_or_none(values: list) -> float | None:
    values = [v for v in values if v is not None]
    return statistics.fmean(values) if values else None


def _stats(metric: str, diffs: list[float], refs: list[float], margin_pct: float) -> dict:
    ref_mean = statistics.fmean(refs)
    lo, hi = t_mean_ci(diffs)
    blo, bhi = bootstrap_mean_ci(diffs)
    mean_d = statistics.fmean(diffs)
    pct = lambda v: v / ref_mean * 100 if ref_mean else float("nan")  # noqa: E731
    return {
        "pairs": len(diffs),
        "reference_mean": ref_mean,
        "mean_diff": mean_d,
        "mean_diff_pct": pct(mean_d),
        "ci95_diff": [lo, hi],                     # Student-t: the verdict's basis
        "ci95_diff_pct": [pct(lo), pct(hi)],
        "ci95_bootstrap_diff_pct": [pct(blo), pct(bhi)],
        "sd_diff": statistics.stdev(diffs) if len(diffs) > 1 else float("nan"),
        "verdict": "descriptive" if metric in DESCRIPTIVE else verdict(metric, (pct(lo), pct(hi)), margin_pct),
    }


def compare_stratum(runs: list[dict], reference: str, metrics: list[str], margin_pct: float,
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
    # dataset key "tps:bound" -> config -> {"blocks", diffs, refs}
    acc: dict[str, dict[str, dict]] = defaultdict(lambda: defaultdict(
        lambda: {"blocks": [], "diffs": defaultdict(list), "refs": defaultdict(list)}))
    pairs = []
    for config in configs:
        if config == reference:
            continue
        for block_id, by_config in sorted(blocks.items()):
            if reference not in by_config or config not in by_config:
                continue
            ref_runs, cfg_runs = by_config[reference], by_config[config]
            mismatch = _workload_mismatch(ref_runs[0]["meta"], cfg_runs[0]["meta"], workload_tolerance_pct)
            if mismatch:
                excluded.append((f"block {block_id} {config}", f"workload differs from {reference}: {mismatch}"))
                continue
            env = _environment_mismatch(ref_runs[0]["meta"], cfg_runs[0]["meta"])
            if env:
                excluded.append((f"block {block_id} {config}", f"environment differs from {reference}: {env}"))
                continue
            # Never average saturated with non-saturated (or differently bound) windows of one side.
            split = [f"{c} {what}" for c, side in ((reference, ref_runs), (config, cfg_runs))
                     for what, key in (("TPS class", lambda r: window_tps_class(r["meta"])),
                                       ("bound label", lambda r: r["meta"].get("bound", "unlabelled")))
                     if not _uniform(side, key)[0]]
            if split:
                excluded.append((f"block {block_id} {config}",
                                 f"windows of one side differ in {', '.join(split)}; they can't be averaged"))
                continue
            ref_row, cfg_row = _window_row(ref_runs), _window_row(cfg_runs)
            tps_ds = pair_tps_dataset(ref_row["tps_class"], cfg_row["tps_class"])
            key = f"{tps_ds}:{pair_bound(ref_row['bound'], cfg_row['bound'])}"
            pairs.append({"block": block_id, "config": config, "dataset": key, "reference": ref_row, "config_row": cfg_row})
            cell = acc[key][config]
            cell["blocks"].append(block_id)
            for m in list(metrics) + (list(SATURATION_METRICS) if tps_ds == "saturation" else []):
                ref_v = _mean_or_none([metric_value(x, m) for x in ref_runs])
                cfg_v = _mean_or_none([metric_value(x, m) for x in cfg_runs])
                if ref_v is None or cfg_v is None:
                    continue
                cell["diffs"][m].append(cfg_v - ref_v)
                cell["refs"][m].append(ref_v)

    datasets = {}
    for key in sorted(acc, key=lambda k: (list(DATASET_TITLES).index(k.split(":")[0]), k)):
        tps_ds, bound = key.split(":", 1)
        results = {config: {"blocks": cell["blocks"],
                            "metrics": {m: _stats(m, d, cell["refs"][m], margin_pct) for m, d in cell["diffs"].items() if d}}
                   for config, cell in sorted(acc[key].items())}
        datasets[key] = {"tps": tps_ds, "bound": bound, "results": results}
    held = [p for p in pairs if p["config_row"]["tps_class"] == "20tps" and p["reference"]["tps_class"] == "reduced"]
    lost = [p for p in pairs if p["config_row"]["tps_class"] == "reduced" and p["reference"]["tps_class"] == "20tps"]
    return {"reference": reference, "configs": configs, "margin_pct": margin_pct, "datasets": datasets,
            "pairs": pairs, "held_20tps": held, "lost_20tps": lost, "excluded": excluded}


def _f(v, fmt: str = ".1f") -> str:
    return "n/a" if v is None else format(v, fmt)


def to_markdown(report: dict, runs: list[dict], campaign: Path) -> str:
    ref = report["reference"]
    labels = labels_of(runs)
    title = f"# Comparison: {campaign.name}"
    lines = [title, ""]
    if labels != {"performance"}:
        lines[0] = f"# PIPELINE TEST, NOT A RESULT: {campaign.name}"
        lines += [f"> These runs are labelled {', '.join(sorted(labels))}. They test the analysis pipeline and must "
                  "not be quoted as performance results.", ""]
    lines += [f"Reference configuration: **{ref}**. Equivalence margin ±{report['margin_pct']:g}%. "
              f"Verdicts use 95% Student-t intervals of the mean paired difference; 95% percentile-bootstrap intervals "
              f"({BOOTSTRAP_RESAMPLES} resamples, seed {BOOTSTRAP_SEED}) are shown next to them. Runs are only compared within a stratum, and "
              "inside it within a dataset: pairs where both windows held 20 client ticks per second form the **primary "
              f"FPS dataset**; pairs where either window fell below {REDUCED_TPS_BELOW:g} TPS form the **saturation / "
              "stress-test dataset**. Pairs are also kept apart by CPU-/GPU-bound label (`differs`: the two windows had "
              "different labels). No mean, interval or verdict combines datasets.", ""]
    for stratum, srep in report["strata"].items():
        lines += _stratum_markdown(stratum, srep, ref)
    lines += ["## Order balance", "",
              "How often each configuration ran in each position inside its launch, and as which launch of its block. "
              "Counts that differ by more than one between positions are marked: an order effect could then favour "
              "a configuration.", "",
              "| Stratum | Config | Position in launch | Launch in block | Balanced |", "|---|---|---|---|---|"]
    for stratum, by_config in sorted(order_balance(runs).items()):
        for config, cell in sorted(by_config.items(), key=lambda kv: str(kv[0])):
            pos, lib = cell["position_in_launch"], cell["launch_in_block"]
            fmt = lambda c: ", ".join(f"{k}: {v}" for k, v in sorted(c.items())) or "n/a"  # noqa: E731
            ok = "no" if _unbalanced(pos) or _unbalanced(lib) else "yes"
            lines.append(f"| {stratum} | {config} | {fmt(pos)} | {fmt(lib)} | {ok} |")
    lines.append("")
    return "\n".join(lines) + "\n" + _runs_markdown(report, runs)


def _stratum_markdown(stratum: str, report: dict, ref: str) -> list[str]:
    lines = [f"## Stratum {stratum}", ""]
    for key, ds in report["datasets"].items():
        lines += [f"### {DATASET_TITLES[ds['tps']]}, bound: {ds['bound']}", ""]
        if ds["tps"] == "saturation":
            lines += ["> Stress-test results, not the primary FPS dataset. Phases are counted in client ticks, so a "
                      "configuration below 20 TPS received fewer particle requests per real-world second than the other; "
                      "frame-time differences here mix rendering cost with that lower request rate. Achieved TPS and "
                      "requests per second are shown for that reason.", ""]
        for config, res in ds["results"].items():
            lines += [f"#### {config} vs {ref}", "", f"Paired blocks: {', '.join(res['blocks']) or 'none'}", "",
                      "| Metric | Pairs | Reference mean | Mean diff | Diff % | 95% t CI (%) | 95% bootstrap CI (%) | Verdict |",
                      "|---|---|---|---|---|---|---|---|"]
            for m, r in res["metrics"].items():
                lo, hi = r["ci95_diff_pct"]
                blo, bhi = r["ci95_bootstrap_diff_pct"]
                lines.append(f"| {m} | {r['pairs']} | {r['reference_mean']:.4g} | {r['mean_diff']:+.4g} | "
                             f"{r['mean_diff_pct']:+.2f} | [{lo:+.2f}, {hi:+.2f}] | [{blo:+.2f}, {bhi:+.2f}] | "
                             f"{r['verdict']} |")
            lines.append("")
    if report["pairs"]:
        lines += [f"### Every pair ({stratum})", "",
                  f"Reference → configuration for each paired block. Requests are particle spawn attempts (target + "
                  "control), before any filter.", "",
                  "| Block | Config | Dataset | TPS | Avg FPS | Mean ms | p99 ms | 1% low FPS | Requests/tick | "
                  "Requests/s | Frames/tick |", "|---|---|---|---|---|---|---|---|---|---|---|"]
        for p in report["pairs"]:
            a, b = p["reference"], p["config_row"]
            two = lambda k, fmt=".1f": f"{_f(a[k], fmt)} → {_f(b[k], fmt)}"  # noqa: E731
            lines.append(f"| {p['block']} | {p['config']} | {p['dataset']} | {two('tps', '.2f')} | {two('avg_fps')} | "
                         f"{two('mean_frametime_ms', '.3f')} | {two('p99_frametime_ms', '.3f')} | {two('low_1pct_fps')} | "
                         f"{_f(b['requests_per_tick'], '.1f')} | {two('requests_per_s', '.0f')} | {two('frames_per_tick', '.2f')} |")
        lines.append("")
    for title, rows in (("Configuration held 20 TPS, reference did not", report["held_20tps"]),
                        ("Configuration fell below 20 TPS, reference held it", report["lost_20tps"])):
        lines += [f"### {title} ({stratum})", ""]
        if not rows:
            lines += ["- none", ""]
            continue
        lines += ["| Block | Config | TPS (reference → config) | Avg FPS (reference → config) |", "|---|---|---|---|"]
        lines += [f"| {p['block']} | {p['config']} | {_f(p['reference']['tps'], '.2f')} → {_f(p['config_row']['tps'], '.2f')} | "
                  f"{_f(p['reference']['avg_fps'])} → {_f(p['config_row']['avg_fps'])} |" for p in rows]
        lines.append("")
    return lines


RUN_COLUMNS = (("avg_fps", "Avg FPS", ".1f"), ("mean_frametime_ms", "Mean ms", ".3f"),
               ("median_frametime_ms", "Median ms", ".3f"), ("p95_frametime_ms", "p95 ms", ".3f"),
               ("p99_frametime_ms", "p99 ms", ".3f"), ("p99_9_frametime_ms", "p99.9 ms", ".3f"),
               ("low_1pct_fps", "1% low FPS", ".1f"), ("low_0_1pct_fps", "0.1% low FPS", ".1f"),
               ("hitches_over_50ms", "Hitches", "d"))


def _runs_markdown(report: dict, runs: list[dict]) -> str:
    """Every window, usable or not, favourable or not (PLAN.md section 5, rule 6)."""
    head = ["Run", "Stratum", "Config", "Block", "Usable", "TPS", "TPS class", "Bound",
            *[c[1] for c in RUN_COLUMNS], "Requests/tick", "Requests/s", "Warnings"]
    lines = ["## Every run", "", "| " + " | ".join(head) + " |", "|" + "---|" * len(head)]
    for r in runs:
        s, meta = r["summary"], r["meta"]
        base = [r["dir"], str(meta.get("stratum", "all")), str(meta.get("config")), str(meta.get("block"))]
        tps = [_f(achieved_tps(meta), ".2f"), str(window_tps_class(meta) or "n/a"), str(meta.get("bound", "unlabelled"))]
        wl = meta.get("workload") or {}
        req = [_f(wl.get("requests_per_tick"), ".1f"), _f(wl.get("requests_per_s"), ".0f")]
        if s:
            m = s["metrics"]
            warn = ", ".join(sorted({i["code"] for i in s.get("issues", []) if i["level"] in ("warning", "error")}))
            cells = base + [str(s["usable"])] + tps + [_f(m.get(k), fmt) for k, _, fmt in RUN_COLUMNS] + req + [warn]
        else:
            cells = base + ["no summary"] + tps + [""] * len(RUN_COLUMNS) + req + [""]
        lines.append("| " + " | ".join(cells) + " |")
    lines += ["", "## Excluded", ""]
    lines += [f"- {what}: {why}" for what, why in report["excluded"]] or ["- none"]
    return "\n".join(lines) + "\n"


def write_runs_csv(runs: list[dict], path: Path) -> None:
    keys = ["avg_fps", "mean_frametime_ms", "median_frametime_ms", "p95_frametime_ms", "p99_frametime_ms",
            "p99_9_frametime_ms", "low_1pct_fps", "low_0_1pct_fps", "hitches_over_50ms", "frames", "duration_s"]
    with open(path, "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["run", "label", "stratum", "config", "block", "order", "usable", "achieved_tps", "tps_class", "bound",
                    "requests_per_tick", "requests_per_s", *keys])
        for r in runs:
            s, meta = r["summary"], r["meta"]
            m = s["metrics"] if s else {}
            wl = meta.get("workload") or {}
            w.writerow([r["dir"], meta.get("label"), meta.get("stratum", "all"), meta.get("config"), meta.get("block"),
                        meta.get("order"), s["usable"] if s else "", achieved_tps(meta), window_tps_class(meta) or "",
                        meta.get("bound", "unlabelled"), wl.get("requests_per_tick", ""), wl.get("requests_per_s", ""),
                        *[m.get(k, "") for k in keys]])


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("campaign", type=Path)
    p.add_argument("--reference", required=True, help="configuration every other one is compared against")
    p.add_argument("--metric", action="append", help=f"metric(s) to compare (default: {', '.join(DEFAULT_METRICS)})")
    p.add_argument("--margin-pct", type=float, default=1.0, help="equivalence margin in percent (pre-register it)")
    p.add_argument("--workload-tolerance-pct", type=float, default=2.0)
    p.add_argument("--out", type=Path, help="write the Markdown report here")
    p.add_argument("--csv", type=Path, help="write one row per run here")
    p.add_argument("--allow-functional", action="store_true",
                   help="compare functional (bring-up) runs: for testing this pipeline only, never a result")
    args = p.parse_args(argv)

    runs = load_runs(args.campaign)
    if not runs:
        print(f"no runs under {args.campaign / 'runs'}", file=sys.stderr)
        return 1
    refused = check_labels(runs, args.allow_functional)
    if refused:
        print(f"compare.py: {refused}", file=sys.stderr)
        return 2
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
