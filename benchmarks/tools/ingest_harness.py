#!/usr/bin/env python3
"""Turn pfbench harness runs into compare.py input: one run.json + summary.json per measured window.

Standard library only. Reads a finished launch directory written by benchmarks/harness/pfbench.py
(plan.json, launch.json, harness.json, check.json, phases.csv, ticks.csv, frames.csv) and writes, for every
phase with role "measure":

  CAMPAIGN/runs/<launch-run-id>--<phase>/summary.json   frametimes.py metrics of that phase's frames, plus checks
  CAMPAIGN/runs/<launch-run-id>--<phase>/run.json       configuration, block, position, workload counters,
                                                        diagnostics, the environment compared runs must share,
                                                        and provenance (jar hashes, versions, commit)

Measurement and correctness stay separate: the functional checks (check.json, written by `pfbench.py check`)
are only read here. A window whose launch failed them gets an error-level issue, so compare.py never uses it.
Runs labelled "functional" are refused unless --allow-functional is given (to test this pipeline); their outputs
keep the label, and compare.py refuses them in turn unless told otherwise.

Usage:
  ingest_harness.py RUN_DIR [RUN_DIR ...] --campaign CAMPAIGN_DIR [--block N] [--allow-functional] [--force]
"""
from __future__ import annotations

import argparse
import csv
import json
import statistics
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import frametimes  # noqa: E402

SCHEMA_VERSION = 1
TICKS_PER_SECOND = 20
UNLIMITED_FPS = 260          # Minecraft's "Unlimited" frame-rate setting
# Provisional thresholds (to be revisited after the variance study, PLAN.md phase 4). They flag windows for a
# human to look at; only the error-level ones make a window unusable.
JIT_WARN_MS_PER_S = 5.0      # JIT compile time per second of window: compilation still busy = warmup unfinished
TICK_RATE_TOLERANCE = 0.02   # phase took > 2% longer than ticks/20: client ticks fell behind (per-second load drops)
FRAME_COVERAGE = 0.98        # frames must cover >= 98% of the phase's wall time
# The ticks.csv column holding each variant's target queue (vanilla end_rod shares the control's queue).
VARIANT_QUEUE = {"typed": "alive_opaque", "untyped": "alive_opaque", "block_dust": "alive_terrain",
                 "vanilla": "alive_translucent"}
VARIANT_CREATED = {"typed": "created_target", "untyped": "created_untyped", "block_dust": "created_block",
                   "vanilla": "created_vanilla"}
CUMULATIVE = ("gc_count", "gc_time_ms", "jit_time_ms", "classes_loaded", "server_saves")


class IngestError(Exception):
    pass


def read_csv(path: Path) -> list[dict]:
    with open(path, newline="") as fh:
        return list(csv.DictReader(fh))


def load_json(path: Path) -> dict:
    return json.loads(path.read_text()) if path.exists() else {}


def _int(row: dict, col: str, default: int | None = None) -> int | None:
    v = row.get(col)
    if v in (None, ""):
        return default
    return int(v)


def window_frames(frames: list[dict], phase: str) -> frametimes.FrameLog:
    rows = [r for r in frames if r["phase"] == phase]
    return frametimes.FrameLog([int(r["frame_ns"]) / 1e6 for r in rows], [int(r["t_ns"]) for r in rows],
                               source_format="generic-ns")


def deltas(ticks: list[dict], phase: str) -> dict:
    """Increase of each cumulative counter over the phase: last row of the phase minus the row before it."""
    idx = [i for i, r in enumerate(ticks) if r["phase"] == phase]
    if not idx:
        return {}
    first, last = idx[0], idx[-1]
    before = ticks[first - 1] if first > 0 else None
    out = {}
    for col in CUMULATIVE:
        if col not in ticks[last]:
            out[col] = None
            continue
        end = _int(ticks[last], col)
        start = _int(before, col) if before is not None else _int(ticks[first], col)
        out[col] = None if end is None or start is None or end < 0 or start < 0 else end - start
    return out


def workload(rows: list[dict], variant: str, duration_s: float) -> dict:
    n = len(rows)
    s = lambda col: sum(_int(r, col, 0) for r in rows)  # noqa: E731
    queue = VARIANT_QUEUE[variant]
    has_control = n > 0 and "alive_control" in rows[0]

    def control_live(r: dict) -> int:
        if has_control:
            return _int(r, "alive_control", 0)
        return _int(r, "alive_translucent", 0)   # older logs: exact except for the vanilla variant

    def target_live(r: dict) -> int:
        v = _int(r, queue, 0)
        return v - control_live(r) if variant == "vanilla" else v

    attempts = s("attempts_target")
    return {
        "ticks": n,
        "duration_s": duration_s,
        "client_ticks_per_s": n / duration_s if duration_s > 0 else None,
        "attempts_per_tick": attempts / n if n else None,
        "spawn_attempts_per_s": attempts / duration_s if duration_s > 0 else None,
        "target_created_per_tick": s(VARIANT_CREATED[variant]) / n if n else None,
        "target_created_per_s": s(VARIANT_CREATED[variant]) / duration_s if duration_s > 0 else None,
        "target_live_mean": statistics.fmean(target_live(r) for r in rows) if n else None,
        "control_attempts_per_tick": s("attempts_control") / n if n else None,
        "control_particles_live_mean": statistics.fmean(control_live(r) for r in rows) if n else None,
        "control_live_source": "alive_control" if has_control else "alive_translucent",
        "alive_total_mean": statistics.fmean(_int(r, "alive_total", 0) for r in rows) if n else None,
    }


def diagnostics(rows: list[dict], frames_log: list[dict], phase: str, d: dict) -> dict:
    cpu = [int(r["cpu_frame_ns"]) for r in frames_log if r["phase"] == phase and r.get("cpu_frame_ns") not in (None, "")]
    srv = [_int(r, "server_tick_avg_ns") for r in rows]
    srv = [v for v in srv if v is not None and v >= 0]
    return {
        "gc_count": d.get("gc_count"),
        "gc_time_ms": d.get("gc_time_ms"),
        "jit_compile_ms": d.get("jit_time_ms"),
        "classes_loaded": d.get("classes_loaded"),
        "server_saves": d.get("server_saves"),
        "client_tick_ms_mean": statistics.fmean(_int(r, "client_tick_ns", 0) for r in rows) / 1e6 if rows else None,
        "server_tick_ms_mean": statistics.fmean(srv) / 1e6 if srv else None,
        "heap_used_mb_mean": statistics.fmean(_int(r, "heap_used_bytes", 0) for r in rows) / 2**20 if rows else None,
        "cpu_frame_ms_mean": statistics.fmean(cpu) / 1e6 if cpu else None,
    }


def window_issues(check: dict, harness: dict, wl: dict, diag: dict, nominal_s: float, frames_s: float) -> list[dict]:
    issues = []

    def add(level: str, code: str, message: str) -> None:
        issues.append({"level": level, "code": code, "message": message})

    if not check:
        add("error", "functional_check_missing", "no check.json: run `pfbench.py check` on the launch first")
    elif not check.get("passed"):
        failed = [c["check"] for c in check.get("checks", []) if not c.get("ok")]
        add("error", "functional_check_failed", f"{len(failed)} functional checks failed, e.g. {failed[:3]}")
    if harness.get("ok") is not True:
        add("error", "harness_error", str(harness.get("error")))
    if wl["duration_s"] > 0 and frames_s < FRAME_COVERAGE * wl["duration_s"]:
        add("error", "frames_missing", f"frames cover {frames_s:.2f} s of a {wl['duration_s']:.2f} s phase")
    if nominal_s > 0 and wl["duration_s"] > nominal_s * (1 + TICK_RATE_TOLERANCE):
        add("warning", "client_tick_rate_low",
            f"{wl['ticks']} ticks took {wl['duration_s']:.2f} s (nominal {nominal_s:.2f} s): fewer than 20 client ticks "
            "per second, so the per-second workload is lower than planned")
    jit = diag.get("jit_compile_ms")
    if jit is not None and wl["duration_s"] > 0 and jit > JIT_WARN_MS_PER_S * wl["duration_s"]:
        add("warning", "jit_activity", f"{jit} ms of JIT compilation during the window: warmup may be unfinished")
    if diag.get("classes_loaded"):
        add("info", "classes_loaded", f"{diag['classes_loaded']} classes loaded during the window")
    if diag.get("server_saves"):
        add("warning", "autosave_in_window", f"{diag['server_saves']} level saves on the integrated server during the window")
    if diag.get("gc_count"):
        add("info", "gc_in_window", f"{diag['gc_count']} GC events, {diag.get('gc_time_ms')} ms")
    return issues


def environment(plan: dict, launch: dict, harness: dict, level: str) -> dict:
    """What every run compared with this one must share. compare.py rejects pairs that differ in any key."""
    v = harness.get("versions", {})
    setup = harness.get("setup", {})
    win = setup.get("window_at_start", {})
    prov = launch.get("provenance") or plan.get("provenance") or {}
    render = launch.get("render_environment") or {}
    return {
        "label": plan.get("label"),
        "scenario_sha256": plan.get("scenario", {}).get("sha256"),
        "template_sha256_tree": launch.get("template_sha256_tree"),
        "variant": plan.get("variant"),
        "level": level,
        "launch_entries": len(launch.get("entries") or []) or None,   # a config alone vs with others in one launch
        "minecraft": v.get("minecraft"),
        "neoforge": v.get("neoforge"),
        "java": v.get("java"),
        "jvm_args_requested": launch.get("jvm_args_requested"),
        "harness_version": v.get("pfbench"),
        "harness_sources_sha256": prov.get("harness_sources_sha256"),
        "modset": launch.get("modset", "vanilla"),
        "render_environment_sha256": render.get("sha256"),
        "gl_renderer": setup.get("gl", {}).get("renderer"),
        "window": f"{win.get('width')}x{win.get('height')}{' fullscreen' if win.get('fullscreen') else ''}",
    }


def ingest_run(run: Path, campaign: Path, block: int | None, allow_functional: bool, force: bool) -> list[Path]:
    plan, launch = load_json(run / "plan.json"), load_json(run / "launch.json")
    harness, check = load_json(run / "harness.json"), load_json(run / "check.json")
    if not plan:
        raise IngestError(f"{run}: no plan.json")
    if not harness:
        raise IngestError(f"{run}: no harness.json (the harness didn't finish); nothing to ingest")
    label = plan.get("label")
    if label != "performance" and not allow_functional:
        raise IngestError(f"{run}: labelled {label!r}, not performance data. Use --allow-functional only to test the "
                          "pipeline; the outputs keep the label and compare.py refuses them by default")
    camp = launch.get("campaign") or {}
    blk = camp.get("block", block)
    if blk is None:
        raise IngestError(f"{run}: no block number (launch.json has no campaign); pass --block")
    phases = read_csv(run / "phases.csv")
    ticks = read_csv(run / "ticks.csv")
    frames = read_csv(run / "frames.csv")
    by_name = {p["name"]: p for p in phases}
    positions = {(e["config"], e["level"]): e["position"] for e in launch.get("entries") or []}
    variant = plan["variant"]
    pf = launch.get("particlefilter")
    options = harness.get("setup", {}).get("options", {})
    caps = [float(options["maxFps"])] if options.get("maxFps") and int(options["maxFps"]) < UNLIMITED_FPS else []
    written = []
    for idx, p in enumerate(phases):
        if p["role"] != "measure":
            continue
        name = p["name"]
        out = campaign / "runs" / f"{plan['run_id']}--{name}"
        if out.exists() and not force:
            raise IngestError(f"{out} exists (use --force to rewrite)")
        rows = [r for r in ticks if r["phase"] == name]
        if not p["start_t_ns"] or not p["end_t_ns"]:
            raise IngestError(f"{run}: phase {name} has no start/end time")
        duration_s = (int(p["end_t_ns"]) - int(p["start_t_ns"])) / 1e9
        nominal_s = int(p["ticks"]) / TICKS_PER_SECOND
        log = window_frames(frames, name)
        frames_s = sum(log.frametimes_ms) / 1000
        summary = frametimes.summarize_log(log, f"{(run / 'frames.csv')}#{name}", cap_fps=caps)
        wl = workload(rows, variant, duration_s)
        diag = diagnostics(rows, frames, name, deltas(ticks, name))
        summary["issues"].extend(window_issues(check, harness, wl, diag, nominal_s, frames_s))
        summary["usable"] = not any(i["level"] == "error" for i in summary["issues"])
        summary["label"] = label
        summary["window"] = {"phase": name, "start_t_ns": int(p["start_t_ns"]), "end_t_ns": int(p["end_t_ns"])}
        settle = phases[idx - 1] if idx > 0 and phases[idx - 1]["role"] == "switch-settle" else {}
        config, level = p["config"], p["level"]
        meta = {
            "schema": SCHEMA_VERSION,
            "label": label,
            "campaign": campaign.name,
            "run_id": f"{plan['run_id']}--{name}",
            "launch_id": plan["run_id"],
            "phase": name,
            "config": config,
            "level": level,
            "variant": variant,
            "stratum": f"{level}/{variant}/{launch.get('modset', 'vanilla')}",
            "block": blk,
            "launch_in_block": camp.get("launch_in_block", 1),
            "position_in_launch": positions.get((config, level)),
            "order": positions.get((config, level)),
            "scenario": plan.get("scenario", {}).get("id"),
            "scenario_sha256": plan.get("scenario", {}).get("sha256"),
            "particle_filter": {
                "installed": pf is not None,
                "jar": pf.get("jar") if pf else None,
                "jar_sha256": pf.get("sha256") if pf else None,
                "config_kind": pf.get("config_kind") if pf else None,
                "config_sha256": pf.get("config_sha256") if pf else None,
                "mode_requested": settle.get("mode_requested") or None,
                "mode": settle.get("mode_after_start") or None,
                "version_loaded": harness.get("versions", {}).get("particlefilter"),
            },
            "sodium_extra_hides_target": bool(launch.get("sodium_extra_hides_target")),
            "frame_log": "frames.csv",
            "window_s": {"phase_duration": duration_s, "nominal": nominal_s},
            "workload": wl,
            "diagnostics": diag,
            "environment": environment(plan, launch, harness, level),
            "provenance": {
                **(launch.get("provenance") or {}),
                "launch_dir": str(run).replace(str(Path.home()), "~"),
                "plan_sha256": harness.get("plan_sha256"),
                "mods_dir": launch.get("mods_dir"),
                "mods_loaded": harness.get("mods"),
                "mods_folder_in_game": harness.get("mods_folder"),
                "mod_files_loaded": harness.get("mod_files"),
                "render_mods": launch.get("render_mods"),
                "sodium_configs": launch.get("sodium_configs"),
                "inputs_sha256": launch.get("inputs_sha256"),
                "jvm_args": harness.get("jvm_args"),
                "versions": harness.get("versions"),
                "gl": harness.get("setup", {}).get("gl"),
                "functional_check_passed": check.get("passed") if check else None,
            },
            "bound": "unlabelled",   # needs the system sampler (PLAN.md section 4); not integrated yet
            "started_utc": launch.get("started_utc"),
            "operator_notes": "",
            "exclude": False,
            "exclude_reason": "",
        }
        out.mkdir(parents=True, exist_ok=True)
        (out / "summary.json").write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n")
        (out / "run.json").write_text(json.dumps(meta, indent=2) + "\n")
        written.append(out)
    return written


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("runs", nargs="+", type=Path, help="launch directories (benchmarks/harness/run/runs/<id>)")
    p.add_argument("--campaign", required=True, type=Path, help="campaign directory (results/<campaign>)")
    p.add_argument("--block", type=int, help="block number for launches made outside `pfbench.py campaign`")
    p.add_argument("--allow-functional", action="store_true", help="ingest functional runs (pipeline tests only)")
    p.add_argument("--force", action="store_true", help="rewrite windows that were ingested before")
    args = p.parse_args(argv)
    errors = 0
    for run in args.runs:
        try:
            for out in ingest_run(run.resolve(), args.campaign, args.block, args.allow_functional, args.force):
                print(f"wrote {out}")
        except IngestError as e:
            print(f"ingest_harness: {e}", file=sys.stderr)
            errors += 1
    return 0 if errors == 0 else 2


if __name__ == "__main__":
    sys.exit(main())
