#!/usr/bin/env python3
"""Turn finished pfbench launches into a self-contained, checkable campaign directory, and check one.

Standard library only. The point is that a published result can be traced to raw measurements and rebuilt from them
by anyone, without trusting a written summary (AI-written or not): every figure in the generated report is copied
by this script from files in the campaign directory, and `verify` rebuilds those files from the raw data alone.

  publish_campaign.py archive CAMPAIGN RUN_DIR [RUN_DIR ...]
      Copies each launch directory (benchmarks/harness/run/runs/<run-id>, gitignored) into CAMPAIGN/raw/<run-id>/
      byte for byte, minus files that aren't measurements and are identified by hash instead (the fresh world copy,
      mod jars, screenshots, Minecraft's telemetry and user caches). ARCHIVE.json lists what was copied and the
      SHA-256 of everything left out. Launches of one campaign must share their label and loader. Failed launches
      are archived too: raw data is never dropped. Prints files that contain your home directory path, for review.

  publish_campaign.py report CAMPAIGN --reference B [--margin-pct 1] [--metric M ...] [--allow-functional]
      Rebuilds CAMPAIGN/runs/ from CAMPAIGN/raw/ (tools/ingest_harness.py), runs tools/compare.py
      (comparison.md, comparison.csv) and writes report.generated.md: classification, methodology, hardware and
      software as recorded, binary and input hashes, the raw-data index, the statistical results and the standard
      limitations (templates/limitations.md). The arguments go to report-args.json so `verify` can repeat them.

  publish_campaign.py manifest CAMPAIGN
      Writes CAMPAIGN/SHA256SUMS for every file in the campaign (`sha256sum -c SHA256SUMS` checks it).

  publish_campaign.py verify CAMPAIGN
      1. Checks SHA256SUMS. 2. Rebuilds everything `report` writes from raw/ in a temporary copy, with the stored
      report-args.json, and compares it byte for byte with the files in CAMPAIGN. Exit 0 only if both hold.

What this proves and doesn't (AUDIT.md): matching hashes show the files are the ones the manifest was made from,
and a matching rebuild shows the derived numbers follow from the raw files with these tools. Neither shows that the
raw files were recorded honestly or under the conditions they describe.
"""
from __future__ import annotations

import argparse
import filecmp
import hashlib
import json
import shutil
import sys
import tempfile
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
BENCH = TOOLS.parent
sys.path.insert(0, str(TOOLS))
import compare  # noqa: E402
import ingest_harness  # noqa: E402
import system_data  # noqa: E402

LIMITATIONS = BENCH / "templates" / "limitations.md"
# Left out of raw/ (identified by SHA-256 in ARCHIVE.json instead): not measurements, large, or not ours to
# redistribute (third-party mod jars).
EXCLUDED_PREFIXES = ("game/saves/", "game/mods/", "game/screenshots/", "game/logs/telemetry/", "game/resourcepacks/",
                     "game/defaultconfigs/", "game/usercache.json", "game/usernamecache.json")
DERIVED = ("runs", "comparison.md", "comparison.csv", "report.generated.md")   # rebuilt by `report`
REQUIRED = ("plan.json", "launch.json")
CONFIG_MEANING = {
    "A": "Particle Filter not installed (baseline)",
    "B": "Particle Filter installed, Active rules: None (inactive)",
    "C": "target hidden everywhere (global rule)",
    "D": "target hidden inside one zone enclosing every emitter",
    "C-miss": "rules that never match the workload (pays lookups, saves nothing)",
    "E": "Particle Filter not installed; Sodium Extra hides the target",
}


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def load_json(path: Path) -> dict:
    try:
        return json.loads(path.read_text())
    except (OSError, json.JSONDecodeError):
        return {}


def files_under(root: Path) -> list[Path]:
    return sorted(p for p in root.rglob("*") if p.is_file())


# ---- archive ----

def archive(campaign: Path, runs: list[Path], force: bool = False) -> dict:
    raw = campaign / "raw"
    index = load_json(campaign / "ARCHIVE.json") or {"format": 1, "launches": {}}
    for run in runs:
        missing = [f for f in REQUIRED if not (run / f).exists()]
        if missing:
            raise SystemExit(f"publish_campaign: {run}: not a pfbench launch directory (missing {', '.join(missing)})")
    new = {r.name: load_json(r / "launch.json") for r in runs}
    known = {k: v for k, v in index["launches"].items()}
    for key in ("label", "loader"):
        values = {str(v.get(key, "neoforge" if key == "loader" else None)) for v in new.values()}
        values |= {str(v.get(key)) for v in known.values()}
        if len(values) > 1:
            raise SystemExit(f"publish_campaign: launches with different {key}s ({', '.join(sorted(values))}) don't "
                             "belong in one campaign; archive them into separate campaign directories")
    home = str(Path.home()).encode()
    flagged = []
    for run in runs:
        dest = raw / run.name
        if dest.exists():
            if not force:
                raise SystemExit(f"publish_campaign: {dest} exists (use --force to replace it)")
            shutil.rmtree(dest)
        copied, left_out = [], {}
        for f in files_under(run):
            rel = f.relative_to(run).as_posix()
            if rel.startswith(EXCLUDED_PREFIXES):
                left_out[rel] = {"sha256": sha256_file(f), "bytes": f.stat().st_size}
                continue
            target = dest / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(f, target)
            copied.append(rel)
            if home in target.read_bytes():
                flagged.append(f"raw/{run.name}/{rel}")
        launch = new[run.name]
        index["launches"][run.name] = {
            "label": launch.get("label"), "loader": launch.get("loader", "neoforge"),
            "campaign": launch.get("campaign"), "sequence": launch.get("sequence"),
            "check_passed": load_json(run / "check.json").get("passed"),
            "files_copied": len(copied), "left_out": left_out,
        }
    sheets = {(v.get("campaign") or {}).get("name") for v in new.values()} - {None}
    for name in sorted(sheets):
        sheet = BENCH / "harness" / "run" / "campaigns" / f"{name}.json"
        if sheet.exists():
            shutil.copy2(sheet, raw / f"campaign-sheet-{name}.json")
    index["launches"] = dict(sorted(index["launches"].items()))
    campaign.mkdir(parents=True, exist_ok=True)
    (campaign / "ARCHIVE.json").write_text(json.dumps(index, indent=2) + "\n")
    return {"archived": [r.name for r in runs], "home_path_in": flagged}


# ---- report ----

def raw_launches(campaign: Path) -> list[Path]:
    raw = campaign / "raw"
    return sorted(p for p in raw.iterdir() if p.is_dir() and (p / "plan.json").exists()) if raw.exists() else []


def distinct(values: list) -> str:
    """Every distinct value with how many launches had it; a key with more than one value is marked."""
    counts: dict[str, int] = {}
    for v in values:
        key = "(not recorded)" if v in (None, "", [], {}) else (v if isinstance(v, str) else json.dumps(v, sort_keys=True))
        counts[key] = counts.get(key, 0) + 1
    text = "; ".join(f"`{k}` ×{n}" for k, n in counts.items())
    return text if len(counts) == 1 else f"**DIFFERS:** {text}"


def first_line(text) -> str | None:
    return str(text).strip().splitlines()[0] if text else None


def nvidia(env: dict) -> str | None:
    """GPU name and driver from the snapshot's nvidia-smi table. Its other columns are live readings (P-state, clocks,
    power, temperature, utilization) that differ from moment to moment; they are in system/gpu.csv per second."""
    rows = str((env.get("gpu") or {}).get("nvidia_smi") or "").strip().splitlines()
    if len(rows) < 2:
        return None
    head = [c.strip() for c in rows[0].split(",")]
    vals = [c.strip() for c in rows[1].split(",")]
    cols = dict(zip(head, vals))
    return f"{cols.get('name', vals[0])}, driver {cols.get('driver_version', '?')}"


def jvm_args(x: dict) -> list | None:
    """The game's actual JVM arguments with paths that name the launch itself made neutral, so launches compare."""
    args = x["harness"].get("jvm_args")
    if not args:
        return None
    own = str(x["plan"].get("output_dir") or "\0")
    return [a.replace(own, "<launch>").replace(str(Path.home()), "~") for a in args]


def pf_rules_sha(x: dict) -> str | None:
    """SHA-256 of the Particle Filter config as written before the launch, without its "mode" field."""
    cfg = load_json(x["dir"] / "inputs" / "particlefilter.json")
    if not cfg:
        return None
    cfg.pop("mode", None)
    return hashlib.sha256(json.dumps(cfg, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def classification(scenario_id: str, labels: set[str]) -> tuple[str, str]:
    kind = ("Synthetic benchmark (controlled workload from the pfbench harness)" if scenario_id.startswith("S")
            else "Real-world gameplay" if scenario_id.startswith("R") else "Unclassified scenario")
    if labels != {"performance"}:
        return kind, ("FUNCTIONAL / PIPELINE TEST, NOT A RESULT: these launches checked the tools; their timings "
                      "mean nothing about Particle Filter")
    return kind, "Performance measurement"


def build_report(campaign: Path, args: dict) -> str:
    launches = raw_launches(campaign)
    data = []
    for d in launches:
        data.append({"dir": d, "plan": load_json(d / "plan.json"), "launch": load_json(d / "launch.json"),
                     "harness": load_json(d / "harness.json"), "check": load_json(d / "check.json"),
                     "env": load_json(d / "env-before.json"), "env_after": load_json(d / "env-after.json")})
    runs = compare.load_runs(campaign)
    report = compare.compare(runs, args["reference"], args["metrics"], args["margin_pct"], args["workload_tolerance_pct"])
    labels = compare.labels_of(runs) or {str(x["plan"].get("label")) for x in data}
    first = data[0]
    sc = first["plan"].get("scenario") or {}
    kind, status = classification(str(sc.get("id", "")), labels)
    col = lambda f: [f(x) for x in data]  # noqa: E731

    datasets: dict[str, int] = {}
    for srep in report["strata"].values():
        for name, ds in srep["datasets"].items():
            n = sum((r["metrics"].get(args["metrics"][0]) or {}).get("pairs", 0) for r in ds["results"].values())
            datasets[name] = datasets.get(name, 0) + n
    tps = {"primary": "Primary FPS dataset (both windows at 20 client ticks per second)",
           "saturation": "Saturation / stress-test dataset (a window below 19.6 TPS)",
           "unknown": "TPS unknown (no tick data)"}

    L = [f"# {campaign.name}: generated report", "",
         "> **Generated by `benchmarks/tools/publish_campaign.py report` from the raw files in this directory.** Every "
         "value below is copied or computed by the script; none was typed by hand or written as a summary by a "
         "person or an AI. Interpretation, if any, lives in a separate, signed file (`report.md`). Rebuild and check "
         "with `python3 benchmarks/tools/publish_campaign.py verify " + campaign.name + "` (AUDIT.md explains what "
         "a passing check does and doesn't establish).", "",
         "## Classification", "",
         f"- **Benchmark class:** {kind}. Scenario `{sc.get('id')}` version {sc.get('version')}, SHA-256 "
         f"`{sc.get('sha256')}`.",
         f"- **Status:** {status}.",
         f"- **Loader:** {distinct(col(lambda x: x['launch'].get('loader', 'neoforge')))}; Minecraft "
         f"{distinct(col(lambda x: (x['harness'].get('versions') or {}).get('minecraft')))}.",
         "- **Datasets with pairs** (never combined; PLAN.md §5): "
         + ("; ".join(f"`{k}`: {v} pairs ({tps.get(k.split(':')[0], k)}, bound label `{k.split(':')[1]}`)"
                      for k, v in sorted(datasets.items())) or "none"), "",
         "## Methodology", "",
         f"Full method: `benchmarks/PLAN.md` at the commit below; harness design `benchmarks/HARNESS.md`. "
         f"Reference configuration **{args['reference']}**, primary metric `{args['metrics'][0]}`, pre-registered "
         f"equivalence margin ±{args['margin_pct']:g}%, workload tolerance {args['workload_tolerance_pct']:g}% per tick, "
         f"verdicts from 95% Student-t intervals of the mean paired difference, with 95% percentile-bootstrap intervals "
         f"({compare.BOOTSTRAP_RESAMPLES} resamples, seed {compare.BOOTSTRAP_SEED}) shown alongside.", "",
         "| Item | Recorded value(s) |", "|---|---|",
         f"| Configurations | " + "; ".join(f"**{c}**: {CONFIG_MEANING.get(c, '?')}" for c in
                                            sorted({e['config'] for x in data for e in x['launch'].get('entries') or []}))
         + " |",
         f"| Load levels (target attempts per tick) | " + "; ".join(sorted(
             {f"{p.get('level')}: {p.get('target_per_tick')}" for x in data for p in x['plan'].get('phases', [])
              if p.get('role') == 'measure'})) + " |",
         f"| Variant | {distinct(col(lambda x: x['plan'].get('variant')))} |",
         f"| Mod set | {distinct(col(lambda x: x['launch'].get('modset')))} |",
         f"| Phase lengths in ticks (role: ticks) | {distinct(col(lambda x: sorted({(p['role'], p['ticks']) for p in x['plan'].get('phases', [])})))} |",
         f"| Launch sequences | {distinct(col(lambda x: len(x['launch'].get('entries') or [])))} entries per launch |",
         f"| Blocks, seed | {len({(x['launch'].get('campaign') or {}).get('block') for x in data})} blocks; seed "
         f"{distinct(col(lambda x: (x['launch'].get('campaign') or {}).get('seed')))} (Williams design, `pfbench.py campaign`) |",
         f"| Launches | {len(data)}, functional checks passed in {sum(1 for x in data if x['check'].get('passed'))} |",
         "", "## Hardware and software (as recorded before each launch)", "",
         "A key whose value differs between launches is marked **DIFFERS**; compare.py pairs only windows whose "
         "environment matches (run.json `environment`), and lists every exclusion in the results below.", "",
         "| Key | Value(s) × launches |", "|---|---|",
         f"| CPU | {distinct(col(lambda x: (x['env'].get('cpu') or {}).get('model')))} |",
         f"| GPU (nvidia-smi name, driver) | {distinct(col(lambda x: nvidia(x['env'])))} |",
         f"| OpenGL renderer (in game) | {distinct(col(lambda x: ((x['harness'].get('setup') or {}).get('gl') or {}).get('renderer')))} |",
         f"| Kernel | {distinct(col(lambda x: (x['env'].get('software') or {}).get('kernel')))} |",
         f"| OS | {distinct(col(lambda x: ((x['env'].get('software') or {}).get('os') or {}).get('NAME')))} "
         f"{distinct(col(lambda x: ((x['env'].get('software') or {}).get('os') or {}).get('VERSION_ID')))} |",
         f"| Compositor | {distinct(col(lambda x: first_line((x['env'].get('display') or {}).get('hyprland'))))} |",
         f"| Java (game JVM) | {distinct(col(lambda x: (x['harness'].get('versions') or {}).get('java')))} |",
         f"| JVM arguments (actual; the launch's own directory shown as `<launch>`) | {distinct(col(jvm_args))} |",
         f"| Loader version | {distinct(col(lambda x: (x['harness'].get('versions') or {}).get(x['launch'].get('loader', 'neoforge'))))} |",
         f"| Window at start | {distinct(col(lambda x: (x['harness'].get('setup') or {}).get('window_at_start')))} |",
         f"| Power state before (AC, profiles, EPP, turbo, RAPL) | {distinct(col(lambda x: system_data.power_state(x['env'])))} |",
         f"| Power state unchanged during each launch | {distinct(col(lambda x: system_data.power_state(x['env']) == system_data.power_state(x['env_after']) if x['env'] and x['env_after'] else None))} |",
         f"| MangoHud | {distinct(col(lambda x: bool(x['launch'].get('mangohud'))))} |",
         "", "Load, top processes, temperatures, monitors and the full snapshot are in each launch's "
         "`env-before.json` / `env-after.json`; per-second CPU, clock, temperature and GPU samples in `system/`.", "",
         "## Binaries and inputs", "",
         "| Item | SHA-256 / value × launches |", "|---|---|",
         f"| Particle Filter jar in `mods/` | {distinct(col(lambda x: [(x['launch'].get('particlefilter') or {}).get('jar'), (x['launch'].get('particlefilter') or {}).get('sha256')] if x['launch'].get('particlefilter') else None))} |",
         f"| Release jar it was built with (Forge) | {distinct(col(lambda x: ((x['launch'].get('particlefilter') or {}).get('release_jar') or {}).get('sha256')))} |",
         f"| Particle Filter rules (config file minus its initial Active rules mode, which is the launch's first "
         f"configuration; the harness switches modes in game) | {distinct(col(pf_rules_sha))} |",
         f"| Rendering mods | {distinct(col(lambda x: {k: v.get('sha256') for k, v in (x['launch'].get('render_mods') or {}).items()} or ('none' if 'modset' in x['launch'] else None)))} |",
         f"| Rendering environment (mods + configs, minus the filter under test) | {distinct(col(lambda x: (x['launch'].get('render_environment') or {}).get('sha256')))} |",
         f"| pfbench harness version, source hash | {distinct(col(lambda x: [(x['launch'].get('provenance') or {}).get('harness_version'), (x['launch'].get('provenance') or {}).get('harness_sources_sha256')]))} |",
         f"| Launcher (`pfbench.py`), tools | {distinct(col(lambda x: [(x['launch'].get('provenance') or {}).get('launcher_sha256'), (x['launch'].get('provenance') or {}).get('tools_sha256')]))} |",
         f"| Git commit, uncommitted paths | {distinct(col(lambda x: [(x['launch'].get('provenance') or {}).get('git_commit'), (x['launch'].get('provenance') or {}).get('git_dirty_paths')]))} |",
         f"| Template world (tree hash) | {distinct(col(lambda x: x['launch'].get('template_sha256_tree')))} |",
         "", "Files left out of `raw/` (world copy, jars, screenshots) are listed with their SHA-256 in `ARCHIVE.json`.", "",
         "## Raw data", "",
         "| Launch (raw directory) | Block | Launch in block | Sequence | Functional checks |", "|---|---|---|---|---|"]
    for x in data:
        camp = x["launch"].get("campaign") or {}
        ok = x["check"].get("passed")
        L.append(f"| [`{x['dir'].name}`](raw/{x['dir'].name}/) | {camp.get('block')} | {camp.get('launch_in_block')} | "
                 f"`{x['launch'].get('sequence')}` | {'pass' if ok else 'FAIL' if ok is False else 'not run'} |")
    L += ["", "Per launch: `frames.csv` (every frame), `ticks.csv` (every client tick: particle requests, created, "
          "alive, tick times, GC, JIT), `phases.csv`, `plan.json`, `launch.json`, `harness.json`, `check.json`, "
          "`env-*.json`, `system/`, `game/gc.log`, `game/logs/`, `gradle.log`, `inputs*/`. Derived per-window data: "
          "[`runs/`](runs/) (`run.json`, `summary.json`); every pair and run: [`comparison.csv`](comparison.csv).", "",
          "## Statistical results", "",
          "Copied from [`comparison.md`](comparison.md) (tools/compare.py), headings moved down two levels.", ""]
    md = compare.to_markdown(report, runs, campaign)
    L += [("##" + line) if line.startswith("#") else line for line in md.splitlines()]
    L += ["", "## Limitations", ""]
    lim = LIMITATIONS.read_text().splitlines()
    L += [line for line in lim if not line.startswith("# ")]
    L += ["", "## How this report was made", "",
          f"`publish_campaign.py report {campaign.name} --reference {args['reference']} --margin-pct {args['margin_pct']:g}"
          + "".join(f" --metric {m}" for m in args["metrics"])
          + (" --allow-functional" if args["allow_functional"] else "")
          + "` (arguments in `report-args.json`), with tools SHA-256 "
          f"`{ingest_harness_tools_sha()}`. A different tools hash in a rebuilt copy means different analysis code.", ""]
    return "\n".join(L)


def ingest_harness_tools_sha() -> str:
    """The hash pfbench.py records as provenance `tools_sha256` (its sha256_tree over every tools/*.py)."""
    h = hashlib.sha256()
    for p in sorted(TOOLS.glob("*.py")):
        h.update(p.name.encode() + b"\0" + p.read_bytes())
    return h.hexdigest()


def report(campaign: Path, reference: str, margin_pct: float = 1.0, metrics: list[str] | None = None,
           workload_tolerance_pct: float = 2.0, allow_functional: bool = False) -> int:
    launches = raw_launches(campaign)
    if not launches:
        raise SystemExit(f"publish_campaign: no launches under {campaign / 'raw'}; run `archive` first")
    args = {"reference": reference, "margin_pct": margin_pct, "metrics": metrics or list(compare.DEFAULT_METRICS),
            "workload_tolerance_pct": workload_tolerance_pct, "allow_functional": allow_functional}
    if (campaign / "runs").exists():
        shutil.rmtree(campaign / "runs")   # derived data only; rebuilt from raw/ below
    for d in launches:
        try:
            ingest_harness.ingest_run(d, campaign, None, allow_functional, True)
        except ingest_harness.IngestError as e:
            raise SystemExit(f"publish_campaign: {e}")
    runs = compare.load_runs(campaign)
    refused = compare.check_labels(runs, allow_functional)
    if refused:
        raise SystemExit(f"publish_campaign: {refused}")
    rep = compare.compare(runs, reference, args["metrics"], margin_pct, workload_tolerance_pct)
    (campaign / "comparison.md").write_text(compare.to_markdown(rep, runs, campaign))
    compare.write_runs_csv(runs, campaign / "comparison.csv")
    (campaign / "report-args.json").write_text(json.dumps(args, indent=2) + "\n")
    (campaign / "report.generated.md").write_text(build_report(campaign, args))
    return 0


# ---- manifest, verify ----

def manifest(campaign: Path) -> Path:
    out = campaign / "SHA256SUMS"
    lines = [f"{sha256_file(f)}  {f.relative_to(campaign).as_posix()}" for f in files_under(campaign) if f != out]
    out.write_text("\n".join(lines) + "\n")
    return out


def check_manifest(campaign: Path) -> list[str]:
    sums = campaign / "SHA256SUMS"
    if not sums.exists():
        return ["no SHA256SUMS (run `manifest`)"]
    problems, listed = [], set()
    for line in sums.read_text().splitlines():
        digest, _, name = line.partition("  ")
        listed.add(name)
        f = campaign / name
        if not f.exists():
            problems.append(f"missing: {name}")
        elif sha256_file(f) != digest:
            problems.append(f"changed: {name}")
    for f in files_under(campaign):
        name = f.relative_to(campaign).as_posix()
        if f != sums and name not in listed:
            problems.append(f"not in SHA256SUMS: {name}")
    return problems


def verify(campaign: Path) -> list[str]:
    problems = check_manifest(campaign)
    args = load_json(campaign / "report-args.json")
    if not args:
        return problems + ["no report-args.json (run `report`)"]
    with tempfile.TemporaryDirectory() as tmp:
        copy = Path(tmp) / campaign.name   # same name: it appears in the reports
        shutil.copytree(campaign / "raw", copy / "raw")
        report(copy, args["reference"], args["margin_pct"], args["metrics"], args["workload_tolerance_pct"],
               args["allow_functional"])
        for name in DERIVED:
            a, b = campaign / name, copy / name
            if a.is_dir() or b.is_dir():
                fa = {p.relative_to(a).as_posix() for p in files_under(a)} if a.exists() else set()
                fb = {p.relative_to(b).as_posix() for p in files_under(b)} if b.exists() else set()
                problems += [f"rebuild differs: {name}/{n}" for n in sorted(fa ^ fb)]
                problems += [f"rebuild differs: {name}/{n}" for n in sorted(fa & fb)
                             if not filecmp.cmp(a / n, b / n, shallow=False)]
            elif not (a.exists() and b.exists() and filecmp.cmp(a, b, shallow=False)):
                problems.append(f"rebuild differs: {name}")
    return problems


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="command", required=True)
    a = sub.add_parser("archive", help="copy finished launches into CAMPAIGN/raw/")
    a.add_argument("campaign", type=Path)
    a.add_argument("runs", type=Path, nargs="+")
    a.add_argument("--force", action="store_true", help="replace launches already archived")
    r = sub.add_parser("report", help="rebuild runs/, comparison and report.generated.md from raw/")
    r.add_argument("campaign", type=Path)
    r.add_argument("--reference", required=True)
    r.add_argument("--margin-pct", type=float, default=1.0)
    r.add_argument("--metric", action="append")
    r.add_argument("--workload-tolerance-pct", type=float, default=2.0)
    r.add_argument("--allow-functional", action="store_true", help="pipeline tests only; the report says so")
    m = sub.add_parser("manifest", help="write SHA256SUMS")
    m.add_argument("campaign", type=Path)
    v = sub.add_parser("verify", help="check SHA256SUMS and rebuild the derived files from raw/")
    v.add_argument("campaign", type=Path)
    args = p.parse_args(argv)
    if args.command == "archive":
        result = archive(args.campaign, args.runs, args.force)
        print(f"archived {len(result['archived'])} launch(es) into {args.campaign / 'raw'}")
        if result["home_path_in"]:
            print("these files contain your home directory path (kept byte for byte; review before publishing):")
            for f in result["home_path_in"]:
                print(f"  {f}")
        return 0
    if args.command == "report":
        report(args.campaign, args.reference, args.margin_pct, args.metric, args.workload_tolerance_pct,
               args.allow_functional)
        print(f"wrote {args.campaign / 'report.generated.md'}, comparison.md, comparison.csv, runs/")
        return 0
    if args.command == "manifest":
        print(f"wrote {manifest(args.campaign)}")
        return 0
    problems = verify(args.campaign)
    for line in problems:
        print(line)
    print("verify: OK (hashes match; derived files rebuild identically from raw/)" if not problems
          else f"verify: {len(problems)} problem(s)")
    return 0 if not problems else 1


if __name__ == "__main__":
    sys.exit(main())
