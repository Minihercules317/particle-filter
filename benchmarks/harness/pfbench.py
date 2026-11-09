#!/usr/bin/env python3
"""Launcher for the pfbench harness (benchmarks/HARNESS.md). Standard library only (Python 3.11+).

It turns a scenario file into a plan.json, prepares an isolated run directory (options.txt, a fresh copy of the
template world, Particle Filter's release jar and config when the configuration needs them), starts the harness
through Gradle, waits for it to quit, and checks the counters.

  pfbench.py template [--force] [--hide]
      Creates the template void world once (run/template/pfbench), from the scenario's [world] section.
  pfbench.py run --sequence C:heavy,B:heavy,D:heavy --functional [--variant typed] [--hide]
      One launch. Each entry is CONFIG:LEVEL (PLAN.md section 2 ids; levels from the scenario's [levels]).
      All entries of a launch must share an install state: A alone, or B/C/D together, or C-miss alone.
  pfbench.py check RUN_DIR
      Functional checks of a finished run (counters consistent, files parse). Prints no timings.

Everything it writes lives under benchmarks/harness/run/ (gitignored). It never touches a Minecraft
installation, world or system setting.

PERFORMANCE RUNS ARE BLOCKED (benchmarks/HANDOFF.md): until the owner confirms the laptop's power-limit problems
are fixed, only --functional runs are allowed. A non-functional run refuses to start unless
PFBENCH_PERFORMANCE_APPROVED=1 is set, which must only happen after that confirmation.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
import shutil
import subprocess
import sys
import time
import tomllib
from datetime import datetime, timezone
from pathlib import Path

HARNESS = Path(__file__).resolve().parent
REPO = HARNESS.parent.parent
TOOLS = REPO / "benchmarks" / "tools"
RUN_ROOT = HARNESS / "run"
TEMPLATE_DIR = RUN_ROOT / "template"
DEFAULT_SCENARIO = REPO / "benchmarks" / "scenarios" / "S1-synthetic.toml"
GAME_PATTERN = "net.neoforged.devlaunch.Main"
DATA_VERSION_1_21_1 = 3955
TICKS_PER_SECOND = 20

sys.path.insert(0, str(TOOLS))
import make_pf_config  # noqa: E402  (benchmarks/tools)

# Configuration id -> (Particle Filter config kind or None for "not installed", Active rules mode)
CONFIGS = {
    "A": (None, None),
    "B": ("synthetic", "NONE"),
    "C": ("synthetic", "EVERYWHERE"),
    "D": ("synthetic", "ZONES"),
    "C-miss": ("miss", "EVERYWHERE"),
}
# What Particle Filter must match to filter each variant's target, and the ticks.csv column that counts it.
VARIANTS = {
    "typed": ("pfbench:target", "created_target"),
    "untyped": ("pfbench.UntypedParticle", "created_untyped"),
    "block_dust": ("minecraft:stone", "created_block"),   # a block rule, not a particle rule
    "vanilla": ("minecraft:end_rod", "created_vanilla"),
}
FUNCTIONAL_PHASES = {"settle_after_load": 5, "warmup_with_workload": 5, "settle_after_switch": 3, "measure": 5}
FUNCTIONAL_OPTIONS = {"maxFps": 60, "fullscreen": False}   # light on the machine; windowed so it can be hidden
FUNCTIONAL_WINDOW = ["--width", "1280", "--height", "720"]


def die(message: str) -> None:
    print(f"pfbench: {message}", file=sys.stderr)
    sys.exit(2)


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def sha256_tree(root: Path) -> str:
    """Hash of every file's relative path and content, in sorted order."""
    h = hashlib.sha256()
    for path in sorted(p for p in root.rglob("*") if p.is_file()):
        h.update(path.relative_to(root).as_posix().encode())
        h.update(b"\0")
        h.update(path.read_bytes())
    return h.hexdigest()


def load_scenario(path: Path) -> tuple[dict, str]:
    raw = path.read_bytes()
    return tomllib.loads(raw.decode()), hashlib.sha256(raw).hexdigest()


def under_run_root(path: Path) -> Path:
    path = path.resolve()
    if RUN_ROOT.resolve() not in path.parents:
        die(f"refusing to touch {path}: not under {RUN_ROOT}")
    return path


def remove_tree(path: Path) -> None:
    """Deletes a directory, but only an absolute path under run/."""
    shutil.rmtree(under_run_root(path))


def game_running() -> bool:
    # pgrep never matches itself, and this process's command line doesn't contain the pattern.
    return subprocess.run(["pgrep", "-f", GAME_PATTERN], capture_output=True).returncode == 0


def options_txt(options: dict) -> str:
    """options.txt in Minecraft's format. Keys not listed keep Minecraft's defaults."""
    lines = [f"version:{DATA_VERSION_1_21_1}"]
    for key, value in options.items():
        if key == "fov":
            value = (float(value) - 70.0) / 40.0   # stored as (degrees - 70) / 40
        if isinstance(value, bool):
            text = "true" if value else "false"
        elif isinstance(value, str):
            text = json.dumps(value)
        else:
            text = str(value)
        lines.append(f"{key}:{text}")
    return "\n".join(lines) + "\n"


def prepare_game_dir(game: Path, options: dict) -> None:
    """options.txt plus config/fml.toml with NeoForge's early loading window off. The early window hands its GL
    context to the game window after loading; that handoff failed once when the window had been moved to a hidden
    workspace, leaving the game stuck on an error dialog. Without the early window there is one window from start
    to end, the same in every run."""
    (game / "options.txt").write_text(options_txt(options))
    (game / "config").mkdir(exist_ok=True)
    (game / "config" / "fml.toml").write_text("earlyWindowControl = false\n")


def pf_jar() -> Path:
    props = tomllib.loads((REPO / "stonecutter.properties.toml").read_text())
    version = props["mod"]["version"]
    jar = REPO / "build" / "libs" / version / f"particlefilter-neoforge-{version}+1.21.1.jar"
    if not jar.exists():
        die(f"{jar} not found; build it with: mise exec -- ./gradlew :1.21.1-neoforge:build buildAndCollect")
    return jar


def pf_config(kind: str, variant: str, first_mode: str) -> dict:
    target, _ = VARIANTS[variant]
    if variant == "block_dust":
        if kind != "synthetic":
            die("block_dust supports only B/C (block rules are global)")
        data = make_pf_config.build("synthetic", first_mode, 1, "pfbench:target")
        data["hiddenParticles"], data["zones"], data["hiddenBlocks"] = [], [], [target]
        return data
    count = 10 if kind == "miss" else 1   # C-miss: 10 rules that never match (configs/pf-c-miss.json)
    return make_pf_config.build(kind, first_mode, count, target)


def gradle(args: list[str], log: Path) -> subprocess.Popen:
    cmd = ["mise", "exec", "--", "./gradlew", "--offline", "--console=plain", "-p", "benchmarks/harness", "runClient", *args]
    with open(log, "w") as fh:
        fh.write("$ " + " ".join(cmd) + "\n")
        fh.flush()
        return subprocess.Popen(cmd, cwd=REPO, stdout=fh, stderr=subprocess.STDOUT, start_new_session=True)


def game_pid(timeout: float = 180) -> int | None:
    deadline = time.time() + timeout
    while time.time() < deadline:
        r = subprocess.run(["pgrep", "-f", GAME_PATTERN], capture_output=True, text=True)
        if r.returncode == 0 and r.stdout.split():
            return int(r.stdout.split()[0])
        time.sleep(1)
    return None


def window(pid: int) -> dict | None:
    if not shutil.which("hyprctl"):
        return None
    r = subprocess.run(["hyprctl", "clients", "-j"], capture_output=True, text=True)
    try:
        for c in json.loads(r.stdout or "[]"):
            if c.get("pid") == pid:
                return {k: c.get(k) for k in ("class", "title", "size", "workspace", "monitor", "floating", "fullscreen")}
    except json.JSONDecodeError:
        pass
    return None


def hide_window(pid: int, log: Path) -> dict | None:
    """Moves the game window to the hidden special workspace pfbench (functional runs only)."""
    info = None
    for _ in range(120):
        info = window(pid)
        title = str((info or {}).get("title") or "")
        if title.startswith("Minecraft") and "Loading" not in title and (info.get("size") or [0])[0] > 0:
            break
        time.sleep(1)
    if not info:
        return None
    # Hyprland 0.56 dispatchers are Lua (benchmarks/HANDOFF.md, machine quirks).
    cmd = f'hl.dsp.window.move({{ workspace = "special:pfbench", window = "pid:{pid}", follow = false }})'
    with open(log, "a") as fh:
        subprocess.run(["hyprctl", "dispatch", cmd], stdout=fh, stderr=subprocess.STDOUT)
    time.sleep(1)
    return window(pid)


def wait_for_exit(proc: subprocess.Popen, timeout: float) -> bool:
    try:
        proc.wait(timeout=timeout)
        return True
    except subprocess.TimeoutExpired:
        os.killpg(proc.pid, 15)
        try:
            proc.wait(timeout=30)
        except subprocess.TimeoutExpired:
            os.killpg(proc.pid, 9)
        return False


# ---- template ----

def cmd_template(args: argparse.Namespace) -> int:
    scenario, scenario_sha = load_scenario(Path(args.scenario))
    world = scenario["world"]
    target = TEMPLATE_DIR / world["template"]
    if target.exists() and not args.force:
        print(f"template exists: {target} (use --force to recreate)")
        return 0
    if game_running():
        die("a dev client is already running; close it first")
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    work = RUN_ROOT / f"template-{stamp}"
    game = work / "game"
    game.mkdir(parents=True)
    prepare_game_dir(game, dict(scenario["options"]) | FUNCTIONAL_OPTIONS)
    gamerules = world.get("gamerules", {})
    plan = {
        "format": 1, "kind": "create_template", "run_id": f"template-{stamp}", "label": "functional",
        "output_dir": str(work),
        "scenario": {"id": scenario["scenario"]["id"], "version": scenario["scenario"]["version"], "sha256": scenario_sha},
        "seed": world["seed"],
        "camera": camera(scenario),
        "world": {"level_name": world["template"], "time": world["time"], "preset": world["preset"],
                  "doDaylightCycle": gamerules.get("doDaylightCycle", False),
                  "doWeatherCycle": gamerules.get("doWeatherCycle", False),
                  "doMobSpawning": gamerules.get("doMobSpawning", False),
                  "randomTickSpeed": gamerules.get("randomTickSpeed", 0)},
    }
    plan_file = work / "plan.json"
    plan_file.write_text(json.dumps(plan, indent=2) + "\n")
    proc = gradle([f"-Ppfbench.gameDir={game}", f"-Ppfbench.plan={plan_file}", "-Ppfbench.gameArgs=" + " ".join(FUNCTIONAL_WINDOW)],
                  work / "gradle.log")
    pid = game_pid()
    if pid and args.hide:
        hide_window(pid, work / "window.log")
    if not wait_for_exit(proc, 900):
        die(f"template creation timed out; see {work}/gradle.log")
    made = game / "saves" / world["template"]
    harness = json.loads((work / "harness.json").read_text()) if (work / "harness.json").exists() else {}
    if not (made / "level.dat").exists() or not harness.get("ok"):
        die(f"template world wasn't created; see {work}/gradle.log and {work}/harness.json")
    if target.exists():
        remove_tree(target)
    TEMPLATE_DIR.mkdir(parents=True, exist_ok=True)
    shutil.copytree(made, target)
    info = {"created_utc": stamp, "scenario": plan["scenario"], "world": plan["world"], "seed": plan["seed"],
            "sha256_tree": sha256_tree(target), "setup": harness.get("setup"), "versions": harness.get("versions")}
    (TEMPLATE_DIR / "template.json").write_text(json.dumps(info, indent=2) + "\n")
    print(f"template world: {target}\nsha256 (tree): {info['sha256_tree']}")
    return 0


def camera(scenario: dict) -> dict:
    c = scenario["camera"]
    return {"position": c["position"], "yaw": c["yaw"], "pitch": c["pitch"], "hide_hud": c.get("hide_hud", True)}


# ---- run ----

def parse_sequence(text: str, levels: dict) -> list[tuple[str, str]]:
    entries = []
    for item in text.split(","):
        config, _, level = item.strip().rpartition(":")
        if config not in CONFIGS:
            die(f"unknown configuration {config!r} (known: {', '.join(CONFIGS)})")
        if level not in levels:
            die(f"unknown level {level!r} (scenario levels: {', '.join(levels)})")
        entries.append((config, level))
    kinds = {CONFIGS[c][0] for c, _ in entries}
    if len(kinds) != 1:
        die("one launch can't mix install states: use A alone, B/C/D together, or C-miss alone")
    return entries


def build_plan(scenario: dict, scenario_sha: str, entries: list[tuple[str, str]], variant: str, functional: bool,
               run_id: str, out: Path) -> dict:
    timing = FUNCTIONAL_PHASES if functional else scenario["phases"]
    levels = scenario["levels"]
    ticks = {k: int(round(v * TICKS_PER_SECOND)) for k, v in timing.items() if k != "cooldown_between_launches"}
    first_config, first_level = entries[0]
    phases = [
        {"name": "load-settle", "role": "settle", "ticks": ticks["settle_after_load"], "target_per_tick": 0,
         "control": False, "mode": CONFIGS[first_config][1]},
        {"name": "warmup", "role": "warmup", "ticks": ticks["warmup_with_workload"],
         "target_per_tick": levels[first_level], "control": True},
    ]
    for i, (config, level) in enumerate(entries, start=1):
        name = f"{i:02d}-{config}-{level}"
        common = {"target_per_tick": levels[level], "control": True, "config": config, "level": level}
        phases.append({"name": f"{name}-settle", "role": "switch-settle", "ticks": ticks["settle_after_switch"],
                       "mode": CONFIGS[config][1], **common})
        phases.append({"name": name, "role": "measure", "ticks": ticks["measure"], "screenshot": functional, **common})
    total_s = sum(p["ticks"] for p in phases) / TICKS_PER_SECOND
    e, pt, pc = scenario["emitters"], scenario["particles"]["target"], scenario["particles"]["control"]
    return {
        "format": 1, "kind": "run", "run_id": run_id, "label": "functional" if functional else "performance",
        "output_dir": str(out),
        "scenario": {"id": scenario["scenario"]["id"], "version": scenario["scenario"]["version"], "sha256": scenario_sha},
        "seed": scenario["world"]["seed"], "variant": variant, "camera": camera(scenario),
        "emitters": {"plane_z": e["plane_z"], "x_range": e["x_range"], "y_range": e["y_range"], "grid": e["grid"]},
        "target_lifetime_ticks": pt["lifetime_ticks"], "control_lifetime_ticks": pc["lifetime_ticks"],
        "control_attempts_per_tick": pc["attempts_per_tick"], "control_z_offset": pc.get("plane_z_offset", 1.0),
        "frame_capacity": int(total_s * 2000) + 10_000,   # room for 2,000 FPS; overflow is counted, never silent
        "screenshots": functional,
        "phases": phases,
    }


def cmd_run(args: argparse.Namespace) -> int:
    if not args.functional and os.environ.get("PFBENCH_PERFORMANCE_APPROVED") != "1":
        die("performance runs are blocked until the owner confirms the laptop's power-limit problems are fixed "
            "(benchmarks/HANDOFF.md). Use --functional for bring-up checks.")
    scenario_path = Path(args.scenario)
    scenario, scenario_sha = load_scenario(scenario_path)
    entries = parse_sequence(args.sequence, scenario["levels"])
    if args.variant not in VARIANTS:
        die(f"unknown variant {args.variant!r}")
    kind = CONFIGS[entries[0][0]][0]
    if args.variant == "block_dust" and any(c == "D" for c, _ in entries):
        die("block_dust can't be filtered by zones (Particle Filter's block rules are global)")
    template = TEMPLATE_DIR / scenario["world"]["template"]
    if not (template / "level.dat").exists():
        die(f"no template world at {template}; run: pfbench.py template")
    if game_running():
        die("a dev client is already running; close it first")

    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    run_id = args.run_id or f"{stamp}-{'-'.join(c for c, _ in entries)}-{args.variant}" + ("-functional" if args.functional else "")
    out = RUN_ROOT / "runs" / run_id
    if out.exists():
        die(f"{out} exists; pick another --run-id")
    game = out / "game"
    (game / "saves").mkdir(parents=True)
    shutil.copytree(template, game / "saves" / scenario["world"]["template"])   # fresh copy; the template is never opened
    prepare_game_dir(game, dict(scenario["options"]) | (FUNCTIONAL_OPTIONS if args.functional else {}))

    install = {"particlefilter": None}
    if kind is not None:
        jar = Path(args.pf_jar) if args.pf_jar else pf_jar()
        (game / "mods").mkdir()
        shutil.copy2(jar, game / "mods" / jar.name)
        cfg = pf_config(kind, args.variant, CONFIGS[entries[0][0]][1])
        (game / "config" / "particlefilter.json").write_text(json.dumps(cfg, indent=2) + "\n")
        install = {"particlefilter": {"jar": jar.name, "sha256": sha256_file(jar), "config_kind": kind,
                                      "config_sha256": sha256_file(game / "config" / "particlefilter.json")}}

    plan = build_plan(scenario, scenario_sha, entries, args.variant, args.functional, run_id, out)
    plan_file = out / "plan.json"
    plan_file.write_text(json.dumps(plan, indent=2) + "\n")
    total_s = sum(p["ticks"] for p in plan["phases"]) / TICKS_PER_SECOND

    launch = {"run_id": run_id, "label": plan["label"], "started_utc": stamp, "sequence": args.sequence,
              "variant": args.variant, "scenario_file": scenario_path.name, "scenario_sha256": scenario_sha,
              "template_sha256_tree": json.loads((TEMPLATE_DIR / "template.json").read_text()).get("sha256_tree")
              if (TEMPLATE_DIR / "template.json").exists() else None,
              **install, "hidden_window": args.hide}
    gradle_args = [f"-Ppfbench.gameDir={game}", f"-Ppfbench.plan={plan_file}",
                   f"-Ppfbench.world={scenario['world']['template']}",
                   "-Ppfbench.jvmArgs=" + scenario.get("jvm", {}).get("args", "")]
    if args.functional:
        gradle_args.append("-Ppfbench.gameArgs=" + " ".join(FUNCTIONAL_WINDOW))
    proc = gradle(gradle_args, out / "gradle.log")
    pid = game_pid()
    if pid is None:
        wait_for_exit(proc, 5)
        die(f"the game didn't start; see {out}/gradle.log")
    launch["window"] = hide_window(pid, out / "window.log") if args.hide else window(pid)
    finished = wait_for_exit(proc, total_s + 600)
    launch["finished_utc"] = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    launch["exited_by_itself"] = finished
    (out / "launch.json").write_text(json.dumps(launch, indent=2) + "\n")
    if not finished:
        print(f"pfbench: timed out after {total_s + 600:.0f} s; see {out}/gradle.log", file=sys.stderr)
    return check(out)


# ---- functional checks ----

def read_csv(path: Path) -> list[dict]:
    with open(path, newline="") as fh:
        return list(csv.DictReader(fh))


def check(run: Path) -> int:
    """Functional checks only: are the counters consistent and do the files parse? Reports no timing."""
    results: list[dict] = []

    def record(name: str, ok: bool, detail: str = "") -> None:
        results.append({"check": name, "ok": bool(ok), "detail": detail})

    harness_file = run / "harness.json"
    if not harness_file.exists():
        record("harness.json written", False, "missing: the harness didn't finish (see gradle.log)")
        return report(run, results)
    harness = json.loads(harness_file.read_text())
    plan = json.loads((run / "plan.json").read_text())
    record("harness finished without error", harness.get("ok") is True, harness.get("error") or "")
    for f in ("frames.csv", "ticks.csv", "phases.csv"):
        record(f"{f} written", (run / f).exists())
    if not all(r["ok"] for r in results):
        return report(run, results)

    setup = harness.get("setup", {})
    record("world is the template (level name pfbench)", setup.get("level_name") == "pfbench", str(setup.get("level_name")))
    record("player is a spectator at the camera position", setup.get("player_spectator") is True
           and [round(v, 2) for v in setup.get("player_position", [])] == [round(v, 2) for v in plan["camera"]["position"]],
           str(setup.get("player_position")))
    record("Particles setting is All", setup.get("options", {}).get("particles") == "ALL",
           str(setup.get("options", {}).get("particles")))
    record("no frame or tick rows dropped", harness.get("frames_dropped_capacity") == 0 and harness.get("ticks_dropped_capacity") == 0)

    phases = read_csv(run / "phases.csv")
    configs = {p["config"] for p in phases if p["config"]}
    pf_expected = configs != {"A"}
    record("Particle Filter installed as the configuration requires",
           bool(setup.get("particlefilter_present")) == pf_expected, f"present={setup.get('particlefilter_present')}")
    for p in phases:
        if p["mode_requested"] and pf_expected:
            record(f"mode switched: {p['name']}", p["mode_after_start"] == p["mode_requested"],
                   f"requested {p['mode_requested']}, got {p['mode_after_start']}")

    ticks = read_csv(run / "ticks.csv")
    by_phase: dict[str, list[dict]] = {}
    for t in ticks:
        by_phase.setdefault(t["phase"], []).append(t)
    variant = plan["variant"]
    _, created_col = VARIANTS[variant]
    life_t = plan["target_lifetime_ticks"] + 1   # a particle is alive in lifetime+1 consecutive post-tick counts
    life_c = plan["control_lifetime_ticks"] + 1
    rate_c = plan["control_attempts_per_tick"]

    for p in phases:
        rows = by_phase.get(p["name"], [])
        record(f"ticks logged: {p['name']}", len(rows) == int(p["ticks"]), f"{len(rows)} of {p['ticks']}")
        if p["role"] not in ("measure", "switch-settle") or not rows:
            continue
        s = lambda col: sum(int(r[col]) for r in rows)  # noqa: E731
        attempts, created = s("attempts_target"), s(created_col)
        filtered = p["config"] in ("C", "D")
        expect = 0 if filtered else attempts
        record(f"{p['name']}: target created {'== 0' if filtered else '== attempts'}", created == expect,
               f"attempts {attempts}, created {created}")
        record(f"{p['name']}: control created == attempts", s("created_control") == s("attempts_control"),
               f"attempts {s('attempts_control')}, created {s('created_control')}")
        if p["role"] != "measure":
            continue
        rate = float(p["target_per_tick"])
        if variant in ("typed", "untyped") and rate == int(rate):
            want = 0 if filtered else min(int(rate) * life_t, 16384)
            ok = sum(1 for r in rows if int(r["alive_opaque"]) == want)
            record(f"{p['name']}: target alive == {want} every tick", ok == len(rows), f"{ok}/{len(rows)} ticks")
        if variant != "vanilla" and rate_c == int(rate_c):   # end_rod shares the translucent queue
            want_c = min(int(rate_c) * life_c, 16384)
            ok = sum(1 for r in rows if int(r["alive_translucent"]) == want_c)
            record(f"{p['name']}: control alive == {want_c} every tick", ok == len(rows), f"{ok}/{len(rows)} ticks")

    # The frame log must parse with the analysis tool; only row counts are reported here, never timings.
    sys.path.insert(0, str(TOOLS))
    import frametimes  # noqa: E402
    try:
        log = frametimes.read_log(run / "frames.csv")
        n = len(log.frametimes_ms)
        record("frames.csv parses with tools/frametimes.py",
               n > 0 and log.rows_skipped == 0 and n == harness.get("frames_logged") and log.source_format == "generic-ns",
               f"{n} rows, {log.rows_skipped} skipped, format {log.source_format}")
    except Exception as e:  # noqa: BLE001
        record("frames.csv parses with tools/frametimes.py", False, repr(e))
    frames = read_csv(run / "frames.csv")
    names = {r["phase"] for r in frames}
    missing = [p["name"] for p in phases if p["name"] not in names]
    record("every phase has frames", not missing, ", ".join(missing))
    return report(run, results)


def report(run: Path, results: list[dict]) -> int:
    passed = all(r["ok"] for r in results)
    out = {"label": "functional", "note": "Functional checks of the harness. Not a performance result.",
           "passed": passed, "checks": results}
    (run / "check.json").write_text(json.dumps(out, indent=2) + "\n")
    for r in results:
        print(f"{'PASS' if r['ok'] else 'FAIL'}  {r['check']}" + (f"  ({r['detail']})" if r["detail"] else ""))
    print(f"\n{'ALL CHECKS PASSED' if passed else 'SOME CHECKS FAILED'} (functional; no timings reported) -> {run / 'check.json'}")
    return 0 if passed else 1


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="command", required=True)
    t = sub.add_parser("template", help="create the template void world")
    t.add_argument("--scenario", default=str(DEFAULT_SCENARIO))
    t.add_argument("--force", action="store_true")
    t.add_argument("--hide", action="store_true", help="move the game window to the hidden special workspace")
    r = sub.add_parser("run", help="one launch")
    r.add_argument("--scenario", default=str(DEFAULT_SCENARIO))
    r.add_argument("--sequence", required=True, help="CONFIG:LEVEL,... e.g. C:heavy,B:heavy,D:heavy")
    r.add_argument("--variant", default="typed", choices=list(VARIANTS))
    r.add_argument("--functional", action="store_true", help="short phases, 60 FPS cap, windowed: bring-up checks only")
    r.add_argument("--hide", action="store_true", help="move the game window to the hidden special workspace")
    r.add_argument("--pf-jar", help="Particle Filter jar (default: the repo's build/libs/<version>/ NeoForge jar)")
    r.add_argument("--run-id")
    c = sub.add_parser("check", help="functional checks of a finished run")
    c.add_argument("run_dir")
    args = p.parse_args(argv)
    if args.command == "template":
        return cmd_template(args)
    if args.command == "run":
        return cmd_run(args)
    return check(Path(args.run_dir).resolve())


if __name__ == "__main__":
    sys.exit(main())
