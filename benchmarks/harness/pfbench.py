#!/usr/bin/env python3
"""Launcher for the pfbench harness (benchmarks/HARNESS.md). Standard library only (Python 3.11+).

It turns a scenario file into a plan.json, prepares an isolated run directory (options.txt, a fresh copy of the
template world, Particle Filter's release jar and config, and the rendering mods when the configuration needs them),
starts the harness through Gradle, waits for it to quit, and checks the counters.

  pfbench.py template [--loader neoforge|forge] [--force] [--hide]
      Creates the template void world once per loader (run/template/pfbench for NeoForge 1.21.1,
      run/template-forge-1.20.1/pfbench for Forge 1.20.1), from the scenario's [world] section.
  pfbench.py run --sequence C:heavy,B:heavy,D:heavy --functional [--variant typed] [--modset vanilla] [--hide]
      One launch. Each entry is CONFIG:LEVEL (PLAN.md section 2 ids; levels from the scenario's [levels]).
      All entries of a launch must share an install state: A alone, B/C/D together, C-miss alone, or E alone
      (E needs --modset sodium).
      --loader forge runs the Forge 1.20.1 harness (benchmarks/harness-forge) instead of NeoForge 1.21.1. Its dev
      runtime uses Mojang's names, so it loads Particle Filter's named-mappings jar from the same build as the
      release jar (the release jar is reobfuscated to SRG names); both hashes are recorded. --modset vanilla only.
  pfbench.py campaign --configs C,B,D,A --levels heavy --blocks 8 --seed 1 [--functional] [--dry-run]
      Several launches in a counterbalanced (Williams) order: every configuration appears in every position and,
      for an even count, after every other one equally often, both inside launches and between launches.
  pfbench.py check RUN_DIR
      Functional checks of a finished run (counters consistent, files parse, provenance matches). Prints no timings.

Every launch also takes environment snapshots before and after (tools/collect_env.py: env-before.json,
env-after.json), runs tools/sample_system.py against the game process for its whole life (system/; never with
--self-cpu: CPU affinity changes are prohibited), and can run under MangoHud (--mangohud: raw frame log in mangohud/,
for the blocked phase 2b cross-check; the HUD is part of the rendering environment, so it must be the same in every
launch that is compared).

Turning finished runs into compare.py input is done by benchmarks/tools/ingest_harness.py.

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
CONFIGS_DIR = REPO / "benchmarks" / "configs"
RUN_ROOT = HARNESS / "run"
FORGE_HARNESS = REPO / "benchmarks" / "harness-forge"
DOWNLOADS = RUN_ROOT / "downloads"
DEFAULT_SCENARIO = REPO / "benchmarks" / "scenarios" / "S1-synthetic.toml"
# Both ModDevGradle runs (NeoForge and legacy Forge) start the game JVM through DevLaunch.
GAME_PATTERN = "net.neoforged.devlaunch.Main"
TICKS_PER_SECOND = 20
QUEUE_LIMIT = 16384   # ParticleEngine's EvictingQueue size per render type

sys.path.insert(0, str(TOOLS))
import collect_env  # noqa: E402  (benchmarks/tools)
import make_pf_config  # noqa: E402
import schedule  # noqa: E402
import system_data  # noqa: E402

# Configuration id -> (Particle Filter config kind or None for "not installed", Active rules mode,
#                      Sodium Extra hides the target)
CONFIGS = {
    "A": (None, None, False),
    "B": ("synthetic", "NONE", False),
    "C": ("synthetic", "EVERYWHERE", False),
    "D": ("synthetic", "ZONES", False),
    "C-miss": ("miss", "EVERYWHERE", False),
    "E": (None, None, True),   # PLAN.md Q7: Sodium Extra hides the target type; needs --modset sodium
}
FILTERED = {"C", "D", "E"}   # configurations in which the target must never be created
# What a filter must match to hide each variant's target, and the ticks.csv column that counts it.
VARIANTS = {
    "typed": ("pfbench:target", "created_target"),
    "untyped": ("pfbench.UntypedParticle", "created_untyped"),
    "block_dust": ("minecraft:stone", "created_block"),   # a block rule, not a particle rule
    "vanilla": ("minecraft:end_rod", "created_vanilla"),
}
# Longest lifetime a variant's target can have, in ticks (None: the scenario's fixed target lifetime).
# From the Minecraft 1.21.1 sources: Particle's default lifetime is (int)(4 / (rand * 0.9 + 0.1)) <= 40, which
# block dust (TerrainParticle) keeps; EndRodParticle sets 60 + nextInt(12) <= 71.
VARIANT_MAX_LIFETIME = {"typed": None, "untyped": None, "block_dust": 40, "vanilla": 71}
# Render-type queue each variant's target lives in (ticks.csv column); vanilla shares the control's queue.
VARIANT_QUEUE = {"typed": "alive_opaque", "untyped": "alive_opaque", "block_dust": "alive_terrain",
                 "vanilla": "alive_translucent"}
# Sodium Extra can only hide typed particles by type id (IMPLEMENTATION.md section 8).
E_VARIANTS = {"typed", "vanilla"}

# Rendering mods, pinned by SHA-256. Downloaded once by hand from Modrinth into run/downloads/<name>/ (isolated,
# gitignored) and verified against Modrinth's SHA-512 (results/functional-2026-11-09-pipeline/SODIUM.md).
RENDER_MODS = {
    "sodium": {"file": "sodium-neoforge-0.8.13+mc1.21.1.jar", "mod_id": "sodium",
               "sha256": "575d187b15328d7bddd8a9b1bee64333c22c14735f5780ec49eac0cd5503c08d",
               "source": "https://modrinth.com/mod/sodium/version/mc1.21.1-0.8.13-neoforge"},
    "sodium-extra": {"file": "sodium-extra-neoforge-0.9.5+mc1.21.1.jar", "mod_id": "sodium_extra",
                     "sha256": "37c33efdee16fc515392fd422b6ec8769cbc1079f6a2eb6cff2072411d511faf",
                     "source": "https://modrinth.com/mod/sodium-extra/version/mc1.21.1-0.9.5+neoforge"},
}
MODSETS = {"vanilla": [], "sodium": ["sodium", "sodium-extra"]}
SODIUM_CONFIGS = CONFIGS_DIR / "sodium"   # sodium-options.json, sodium-extra-options.json (shared by every config)

FUNCTIONAL_PHASES = {"settle_after_load": 5, "warmup_with_workload": 6, "settle_after_switch": 4, "measure": 5}
FUNCTIONAL_OPTIONS = {"maxFps": 60, "fullscreen": False}   # light on the machine; windowed so it can be hidden
FUNCTIONAL_WINDOW = ["--width", "1280", "--height", "720"]
FUNCTIONAL_COOLDOWN_S = 5
HARNESS_SOURCES = ("src", "build.gradle.kts", "settings.gradle.kts", "gradle.properties")
FORGE_SOURCES = ("src", "build.gradle.kts", "settings.gradle.kts", "gradle.properties")
# What differs per loader. Every launch of a campaign uses one loader; ingest puts it in the pairing environment.
LOADERS = {
    "neoforge": {
        "minecraft": "1.21.1", "data_version": 3955, "gradle_dir": HARNESS, "template_dir": RUN_ROOT / "template",
        # Mojang names at runtime: the release jar itself runs in the dev client.
        "pf_jar": "build/libs/{v}/particlefilter-neoforge-{v}+1.21.1.jar", "pf_release_jar": None,
        "pf_build": "./gradlew :1.21.1-neoforge:build buildAndCollect",
        "modsets": ("vanilla", "sodium"),
        "sources": ((HARNESS, HARNESS_SOURCES),),
    },
    "forge": {
        "minecraft": "1.20.1", "data_version": 3465, "gradle_dir": FORGE_HARNESS,
        "template_dir": RUN_ROOT / "template-forge-1.20.1",
        # The release jar is reobfuscated to SRG names, which only a production Forge loads; the dev client (Mojang
        # names) loads the jar reobfJar was made from, written by the same Gradle run (devlibs/).
        "pf_jar": "versions/1.20.1-forge/build/devlibs/particlefilter-forge-{v}+1.20.1.jar",
        # reobfJar's own output (the dev jar's sibling); buildAndCollect copies it to build/libs/<v>/ for release.
        "pf_release_jar": "versions/1.20.1-forge/build/libs/particlefilter-forge-{v}+1.20.1.jar",
        "pf_collected_jar": "build/libs/{v}/particlefilter-forge-{v}+1.20.1.jar",
        "pf_build": "./gradlew :1.20.1-forge:build buildAndCollect",
        "modsets": ("vanilla",),   # no approved rendering mods for Forge 1.20.1
        "sources": ((HARNESS, ("src/main", "gradle.properties")), (FORGE_HARNESS, FORGE_SOURCES)),
    },
}
SAMPLER_INTERVAL_S = 1.0
SAMPLER_STOP_TIMEOUT_S = 15
ENV_SNAPSHOT_TIMEOUT_S = 120
# The pilot's MangoHud settings (tools/pilot_devclient.sh): the smallest HUD (0.8.4 logs nothing with no_display),
# one row per frame, logging from 1 s after the first frame until the game quits.
MANGOHUD_CONFIG = "fps_only,background_alpha=0,log_interval=0,autostart_log=1,log_versioning"


def die(message: str) -> None:
    print(f"pfbench: {message}", file=sys.stderr)
    sys.exit(2)


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    return sha256_bytes(path.read_bytes())


def sha256_tree(root: Path, paths: tuple[str, ...] | None = None) -> str:
    """Hash of every file's relative path and content, in sorted order (optionally only under `paths`)."""
    files: list[Path] = []
    for top in ([root / p for p in paths] if paths else [root]):
        if top.is_file():
            files.append(top)
        elif top.is_dir():
            files.extend(p for p in top.rglob("*") if p.is_file() and "__pycache__" not in p.parts)
    h = hashlib.sha256()
    for path in sorted(files):
        h.update(path.relative_to(root).as_posix().encode())
        h.update(b"\0")
        h.update(path.read_bytes())
    return h.hexdigest()


def canonical_json(data) -> bytes:
    return json.dumps(data, sort_keys=True, separators=(",", ":")).encode()


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


def harness_version() -> str:
    for line in (HARNESS / "gradle.properties").read_text().splitlines():
        if line.startswith("harness_version="):
            return line.split("=", 1)[1].strip()
    return "unknown"


def git(*args: str) -> str:
    """git's output with trailing whitespace removed (leading spaces are kept: porcelain status needs them)."""
    r = subprocess.run(["git", *args], cwd=REPO, capture_output=True, text=True)
    return r.stdout.rstrip() if r.returncode == 0 else ""


def harness_sources_sha256(loader: str) -> str:
    """Hash of the harness sources one loader's build compiles (Forge: the shared sources plus its own)."""
    parts = [sha256_tree(root, paths) for root, paths in LOADERS[loader]["sources"]]
    return parts[0] if len(parts) == 1 else sha256_bytes("\n".join(parts).encode())


def provenance(loader: str = "neoforge") -> dict:
    """What produced a run: commit, uncommitted changes that matter, and hashes of the code that ran."""
    dirty = [line[3:] for line in git("status", "--porcelain", "--", "benchmarks", "src").splitlines()
             if not line[3:].startswith("benchmarks/harness/run/")]
    return {
        "git_commit": git("rev-parse", "HEAD"),
        "git_dirty_paths": dirty,
        "harness_version": harness_version(),
        "loader": loader,
        "harness_sources_sha256": harness_sources_sha256(loader),
        "launcher_sha256": sha256_file(Path(__file__).resolve()),
        "tools_sha256": sha256_tree(TOOLS, tuple(p.name for p in sorted(TOOLS.glob("*.py")))),
    }


def options_txt(options: dict, loader: str = "neoforge") -> str:
    """options.txt in Minecraft's format. Keys not listed keep Minecraft's defaults."""
    lines = [f"version:{LOADERS[loader]['data_version']}"]
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


def prepare_game_dir(game: Path, options: dict, loader: str = "neoforge") -> None:
    """options.txt plus config/fml.toml with NeoForge's early loading window off. The early window hands its GL
    context to the game window after loading; that handoff failed once when the window had been moved to a hidden
    workspace, leaving the game stuck on an error dialog. Without the early window there is one window from start
    to end, the same in every run."""
    (game / "options.txt").write_text(options_txt(options, loader))
    (game / "config").mkdir(exist_ok=True)
    (game / "config" / "fml.toml").write_text("earlyWindowControl = false\n")


def pf_jar(loader: str = "neoforge") -> Path:
    """The Particle Filter jar the loader's dev client loads (see LOADERS)."""
    props = tomllib.loads((REPO / "stonecutter.properties.toml").read_text())
    version = props["mod"]["version"]
    jar = REPO / LOADERS[loader]["pf_jar"].format(v=version)
    if not jar.exists():
        build = " ".join([*java_prefix(), LOADERS[loader]["pf_build"]])
        die(f"{jar} not found; build it from the repository root with: {build}")
    return jar


def pf_release_jar(loader: str, jar: Path) -> Path | None:
    """The release jar built together with `jar`, when the loader can't run the release jar itself (Forge)."""
    pattern = LOADERS[loader]["pf_release_jar"]
    if pattern is None:
        return None
    version = jar.name.split("-")[2].split("+")[0]   # particlefilter-<loader>-<version>+<mc>.jar
    release = REPO / pattern.format(v=version)
    return release if release.exists() else None


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


# ---- rendering mods (Sodium / Sodium Extra) ----

def render_mod_jar(name: str) -> Path:
    spec = RENDER_MODS[name]
    jar = DOWNLOADS / name / spec["file"]
    if not jar.exists():
        die(f"{jar} missing: download {spec['source']} into {jar.parent}/ (see SODIUM.md in the functional record)")
    if sha256_file(jar) != spec["sha256"]:
        die(f"{jar}: SHA-256 doesn't match the pinned {spec['sha256']}")
    return jar


def sodium_extra_options(base: dict, target: str, hide: bool) -> dict:
    """Sodium Extra's options with exactly one per-type entry: the target, hidden (E) or shown (every other
    configuration). With the entry present in both, its map lookup does the same work; only the value differs.
    The JSON key is "other" (ParticleSettings.otherMap carries @SerializedName("other") in Sodium Extra 0.9.5)."""
    data = json.loads(json.dumps(base))
    data.setdefault("particle_settings", {})["other"] = {target: not hide}
    return data


def normalized_sodium_extra(data: dict) -> dict:
    """Sodium Extra's options without the per-type particle map, which is the factor being compared."""
    data = json.loads(json.dumps(data))
    data.get("particle_settings", {}).pop("other", None)
    return data


def render_environment(game: Path, modset: str) -> dict:
    """Everything that sets how the game renders, apart from the filter under test. Runs compared with each other
    must have the same `sha256` (compare.py rejects pairs that don't)."""
    cfg = game / "config"
    env = {"modset": modset, "options_txt_sha256": sha256_file(game / "options.txt"),
           "render_mods": {n: RENDER_MODS[n]["sha256"] for n in MODSETS[modset]}}
    sodium = cfg / "sodium-options.json"
    extra = cfg / "sodium-extra-options.json"
    env["sodium_options_sha256"] = sha256_file(sodium) if sodium.exists() else None
    env["sodium_extra_options_sha256_without_particle_map"] = (
        sha256_bytes(canonical_json(normalized_sodium_extra(json.loads(extra.read_text())))) if extra.exists() else None)
    env["sha256"] = sha256_bytes(canonical_json(env))
    return env


# ---- Gradle, window ----

def java_prefix() -> list[str]:
    """How Gradle gets its JVM. On the owner's machine Java comes from mise (`mise.toml`, untracked, not in the
    repository); elsewhere `./gradlew` runs on whatever JDK 17+ is on PATH / JAVA_HOME. Either way the game JVM is
    the Gradle toolchain (Java 21 for NeoForge, 17 for Forge), whose version and vendor harness.json records."""
    return ["mise", "exec", "--"] if shutil.which("mise") and (REPO / "mise.toml").exists() else []


def gradle(args: list[str], log: Path, mangohud_dir: Path | None = None, loader: str = "neoforge") -> subprocess.Popen:
    project = str(LOADERS[loader]["gradle_dir"].relative_to(REPO))
    # --offline: a launch never downloads anything (REPRODUCING.md: prepare the Gradle cache once, online).
    cmd = [*java_prefix(), "./gradlew", "--offline", "--console=plain", "-p", project, "runClient", *args]
    env = None
    if mangohud_dir is not None:
        cmd = ["mangohud", "--dlsym", *cmd]
        env = os.environ | {"MANGOHUD": "1", "MANGOHUD_CONFIG": f"{MANGOHUD_CONFIG},output_folder={mangohud_dir}"}
    with open(log, "w") as fh:
        fh.write("$ " + " ".join(cmd) + "\n")
        fh.flush()
        return subprocess.Popen(cmd, cwd=REPO, stdout=fh, stderr=subprocess.STDOUT, start_new_session=True, env=env)


# ---- environment snapshots, system sampler ----

def env_snapshot(out: Path, name: str, game: Path, jvm_args: str, note: str, java: str | None = None) -> dict:
    """tools/collect_env.py into out/<name>.json, in its own process (read-only queries; a few seconds)."""
    cmd = [sys.executable, "-I", str(TOOLS / "collect_env.py"), "--out", str(out / f"{name}.json"),
           "--instance", str(game), "--repo", str(REPO), "--jvm-args", jvm_args, "--note", note]
    if java:
        cmd += ["--java", java]
    with open(out / "env.log", "a") as fh:
        fh.write("$ " + " ".join(cmd) + "\n")
        fh.flush()
        try:
            code = subprocess.run(cmd, stdout=fh, stderr=subprocess.STDOUT, timeout=ENV_SNAPSHOT_TIMEOUT_S).returncode
        except subprocess.TimeoutExpired:
            code = None
    return {"file": f"{name}.json", "returncode": code}


def start_sampler(pid: int, out: Path) -> subprocess.Popen:
    """tools/sample_system.py for the game's whole life. Never --self-cpu (CPU affinity changes are prohibited)."""
    cmd = [sys.executable, "-I", str(TOOLS / "sample_system.py"), "--pid", str(pid), "--out-dir", str(out / "system"),
           "--interval", str(SAMPLER_INTERVAL_S)]
    with open(out / "sampler.log", "w") as log:
        log.write("$ " + " ".join(cmd) + "\n")
        log.flush()
        return subprocess.Popen(cmd, stdout=log, stderr=subprocess.STDOUT)


def stop_sampler(proc: subprocess.Popen) -> int | None:
    """The sampler stops by itself when the game exits; stop it if it doesn't."""
    try:
        return proc.wait(timeout=SAMPLER_STOP_TIMEOUT_S)
    except subprocess.TimeoutExpired:
        proc.terminate()
        try:
            return proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()
            return None


def game_process(pid: int) -> tuple[dict, str | None]:
    """The game JVM as the launcher saw it (command line, affinity: recorded, never changed) and its java binary."""
    info = collect_env.redact(collect_env.process_info(pid))
    try:
        exe = os.readlink(f"/proc/{pid}/exe")
    except OSError:
        exe = None
    info["java_exe"] = collect_env.redact(exe)
    return info, exe


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


def wait_for_window(pid: int) -> dict | None:
    """The compositor's view of the game window once it has finished loading (None without hyprctl)."""
    info = None
    for _ in range(120):
        info = window(pid)
        title = str((info or {}).get("title") or "")
        if title.startswith("Minecraft") and "Loading" not in title and (info.get("size") or [0])[0] > 0:
            break
        time.sleep(1)
    return info


def hide_window(pid: int, log: Path, monitor: str | None = None) -> dict | None:
    """Moves the game window to the hidden special workspace pfbench and, with `monitor`, pins that workspace to the
    monitor. A special workspace belongs to whichever monitor had focus, and a fullscreen window takes that monitor's
    size: seen 2026-11-09, launches made while the owner worked on the laptop panel ran at 2560x1600 instead of
    2560x1440, so they could not be paired with the others."""
    info = wait_for_window(pid)
    if not info:
        return None
    # Hyprland 0.56 dispatchers are Lua (benchmarks/HANDOFF.md, machine quirks).
    cmds = [f'hl.dsp.window.move({{ workspace = "special:pfbench", window = "pid:{pid}", follow = false }})']
    if monitor:
        cmds.append(f'hl.dsp.workspace.move({{ workspace = "special:pfbench", monitor = "{monitor}" }})')
    with open(log, "a") as fh:
        for cmd in cmds:
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
    loader = args.loader
    template_dir = LOADERS[loader]["template_dir"]
    target = template_dir / world["template"]
    if target.exists() and not args.force:
        print(f"template exists: {target} (use --force to recreate)")
        return 0
    if game_running():
        die("a dev client is already running; close it first")
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    work = RUN_ROOT / f"template-{stamp}" if loader == "neoforge" else RUN_ROOT / f"template-{loader}-{stamp}"
    game = work / "game"
    game.mkdir(parents=True)
    prepare_game_dir(game, dict(scenario["options"]) | FUNCTIONAL_OPTIONS, loader)
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
        "loader": loader,
        "provenance": provenance(loader),
    }
    plan_file = work / "plan.json"
    plan_file.write_text(json.dumps(plan, indent=2) + "\n")
    proc = gradle([f"-Ppfbench.gameDir={game}", f"-Ppfbench.plan={plan_file}", "-Ppfbench.gameArgs=" + " ".join(FUNCTIONAL_WINDOW)],
                  work / "gradle.log", loader=loader)
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
    template_dir.mkdir(parents=True, exist_ok=True)
    shutil.copytree(made, target)
    info = {"created_utc": stamp, "loader": loader, "scenario": plan["scenario"], "world": plan["world"],
            "seed": plan["seed"], "sha256_tree": sha256_tree(target), "setup": harness.get("setup"),
            "versions": harness.get("versions")}
    (template_dir / "template.json").write_text(json.dumps(info, indent=2) + "\n")
    print(f"template world: {target}\nsha256 (tree): {info['sha256_tree']}")
    return 0


def camera(scenario: dict) -> dict:
    c = scenario["camera"]
    return {"position": c["position"], "yaw": c["yaw"], "pitch": c["pitch"], "hide_hud": c.get("hide_hud", True)}


# ---- run ----

def install_state(config: str) -> tuple:
    pf_kind, _, se_hides = CONFIGS[config]
    return (pf_kind, se_hides)


def parse_sequence(text: str, levels: dict) -> list[tuple[str, str]]:
    entries = []
    for item in text.split(","):
        config, _, level = item.strip().rpartition(":")
        if config not in CONFIGS:
            die(f"unknown configuration {config!r} (known: {', '.join(CONFIGS)})")
        if level not in levels:
            die(f"unknown level {level!r} (scenario levels: {', '.join(levels)})")
        entries.append((config, level))
    if len({install_state(c) for c, _ in entries}) != 1:
        die("one launch can't mix install states: use A alone, B/C/D together, C-miss alone, or E alone")
    if len(set(entries)) != len(entries):
        die("an entry appears twice in one launch")
    return entries


def max_alive_ticks(plan_or_scenario_lifetimes: tuple[int, int], variant: str) -> int:
    """Post-tick counts a particle of this launch can stay alive for: lifetime + 1 (the tick it's added and
    `lifetime` more), for the longest-lived of the target and the control."""
    target_life, control_life = plan_or_scenario_lifetimes
    life = VARIANT_MAX_LIFETIME[variant] or target_life
    return max(life, control_life) + 1


def min_settle_ticks(lifetimes: tuple[int, int], variant: str) -> int:
    """Shortest settle after a switch that guarantees no particle from the previous phase survives into the
    measured window: every particle alive at the switch is gone after max_alive_ticks, plus one tick for
    particles still waiting in ParticleEngine.particlesToAdd, plus one spare."""
    return max_alive_ticks(lifetimes, variant) + 2


def build_plan(scenario: dict, scenario_sha: str, entries: list[tuple[str, str]], variant: str, functional: bool,
               run_id: str, out: Path, prov: dict | None = None, loader: str = "neoforge") -> dict:
    timing = FUNCTIONAL_PHASES if functional else scenario["phases"]
    levels = scenario["levels"]
    ticks = {k: int(round(v * TICKS_PER_SECOND)) for k, v in timing.items()
             if isinstance(v, (int, float)) and not isinstance(v, bool) and k != "cooldown_between_launches"}
    gc_at_switch = bool(scenario["phases"].get("gc_at_switch", False))
    e, pt, pc = scenario["emitters"], scenario["particles"]["target"], scenario["particles"]["control"]
    lifetimes = (pt["lifetime_ticks"], pc["lifetime_ticks"])
    settle_min = min_settle_ticks(lifetimes, variant)
    if ticks["settle_after_switch"] < settle_min:
        die(f"settle_after_switch is {ticks['settle_after_switch']} ticks; variant {variant} needs at least {settle_min} "
            "so no particle from the previous phase reaches the measured window")

    first_config, _ = entries[0]
    phases = [{"name": "load-settle", "role": "settle", "ticks": ticks["settle_after_load"], "target_per_tick": 0,
               "control": False, "mode": CONFIGS[first_config][1]}]
    # Warmup runs every configuration of the launch (twice, round-robin, when there are several), so the JIT has
    # compiled every path the measured phases take (filtered, unfiltered, zone checks) before any of them is measured.
    warm = ticks["warmup_with_workload"]
    rounds = 2 if len(entries) > 1 else 1
    segments = [(r, i, c, lv) for r in range(1, rounds + 1) for i, (c, lv) in enumerate(entries, start=1)]
    seg_ticks = [warm // len(segments)] * len(segments)
    seg_ticks[-1] += warm - sum(seg_ticks)
    for (r, i, config, level), n in zip(segments, seg_ticks):
        name = "warmup" if len(segments) == 1 else f"warmup-r{r}-{i:02d}-{config}"
        phases.append({"name": name, "role": "warmup", "ticks": n, "target_per_tick": levels[level], "control": True,
                       "mode": CONFIGS[config][1], "config": config, "level": level})
    for i, (config, level) in enumerate(entries, start=1):
        name = f"{i:02d}-{config}-{level}"
        common = {"target_per_tick": levels[level], "control": True, "config": config, "level": level}
        phases.append({"name": f"{name}-settle", "role": "switch-settle", "ticks": ticks["settle_after_switch"],
                       "mode": CONFIGS[config][1], "gc_at_start": gc_at_switch, **common})
        phases.append({"name": name, "role": "measure", "ticks": ticks["measure"], "screenshot": functional, **common})
    total_s = sum(p["ticks"] for p in phases) / TICKS_PER_SECOND
    return {
        "format": 1, "kind": "run", "run_id": run_id, "label": "functional" if functional else "performance",
        "loader": loader, "minecraft": LOADERS[loader]["minecraft"],
        "output_dir": str(out),
        "scenario": {"id": scenario["scenario"]["id"], "version": scenario["scenario"]["version"], "sha256": scenario_sha},
        "seed": scenario["world"]["seed"], "variant": variant, "camera": camera(scenario),
        "emitters": {"plane_z": e["plane_z"], "x_range": e["x_range"], "y_range": e["y_range"], "grid": e["grid"]},
        "target_lifetime_ticks": pt["lifetime_ticks"], "control_lifetime_ticks": pc["lifetime_ticks"],
        "control_attempts_per_tick": pc["attempts_per_tick"], "control_z_offset": pc.get("plane_z_offset", 1.0),
        "min_settle_ticks": settle_min,
        "frame_capacity": int(total_s * 2000) + 10_000,   # room for 2,000 FPS; overflow is counted, never silent
        "screenshots": functional,
        "phases": phases,
        "provenance": prov or {},
    }


def copy_inputs(game: Path, dest: Path) -> dict:
    """Copies the files that configure the game into the run directory and returns their SHA-256s."""
    dest.mkdir(parents=True, exist_ok=True)
    hashes = {}
    for rel in ("options.txt", "config/fml.toml", "config/particlefilter.json", "config/sodium-options.json",
                "config/sodium-extra-options.json"):
        src = game / rel
        if src.exists():
            shutil.copy2(src, dest / src.name)
            hashes[src.name] = sha256_file(src)
    return hashes


def launch_problem(entries: list[tuple[str, str]], variant: str, modset: str, loader: str) -> str | None:
    """Why this launch can't run with these options, or None. `campaign` checks every launch before the first starts."""
    if variant not in VARIANTS:
        return f"unknown variant {variant!r}"
    if loader not in LOADERS:
        return f"unknown loader {loader!r} (known: {', '.join(LOADERS)})"
    if modset not in MODSETS:
        return f"unknown modset {modset!r} (known: {', '.join(MODSETS)})"
    if modset not in LOADERS[loader]["modsets"]:
        return f"--modset {modset} isn't available for {loader} (available: {', '.join(LOADERS[loader]['modsets'])})"
    se_hides = CONFIGS[entries[0][0]][2]
    if variant == "block_dust" and any(c == "D" for c, _ in entries):
        return "block_dust can't be filtered by zones (Particle Filter's block rules are global)"
    if se_hides and modset != "sodium":
        return "configuration E (Sodium Extra hides the target) needs --modset sodium"
    if se_hides and variant not in E_VARIANTS:
        return f"Sodium Extra can only hide typed particles by type id: E supports {', '.join(sorted(E_VARIANTS))}"
    return None


def run_launch(*, scenario_path: Path, sequence: str, variant: str, modset: str, functional: bool, hide: bool,
               monitor: str | None = None,
               pf_jar_path: str | None = None, run_id: str | None = None, sodium_defaults: bool = False,
               campaign: dict | None = None, mangohud: bool = False, loader: str = "neoforge") -> tuple[int, Path]:
    if not functional and os.environ.get("PFBENCH_PERFORMANCE_APPROVED") != "1":
        die("performance runs are blocked until the owner confirms the laptop's power-limit problems are fixed "
            "(benchmarks/HANDOFF.md). Use --functional for bring-up checks.")
    scenario, scenario_sha = load_scenario(scenario_path)
    entries = parse_sequence(sequence, scenario["levels"])
    problem = launch_problem(entries, variant, modset, loader)
    if problem:
        die(problem)
    pf_kind, _, se_hides = CONFIGS[entries[0][0]]
    if modset == "sodium" and not sodium_defaults and not all(
            (SODIUM_CONFIGS / f).exists() for f in ("sodium-options.json", "sodium-extra-options.json")):
        die(f"no Sodium config templates in {SODIUM_CONFIGS}; capture the mods' defaults once with --sodium-defaults")
    if sodium_defaults and not functional:
        die("--sodium-defaults is for capturing the mods' default configs in a functional run")
    template_dir = LOADERS[loader]["template_dir"]
    template = template_dir / scenario["world"]["template"]
    if not (template / "level.dat").exists():
        die(f"no template world at {template}; run: pfbench.py template --loader {loader}")
    if mangohud and not shutil.which("mangohud"):
        die("--mangohud: mangohud is not installed")
    if game_running():
        die("a dev client is already running; close it first")

    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    tags = ([loader] if loader != "neoforge" else []) + [c for c, _ in entries] + [variant] + ([modset] if modset != "vanilla" else [])
    run_id = run_id or f"{stamp}-{'-'.join(tags)}" + ("-functional" if functional else "")
    out = RUN_ROOT / "runs" / run_id
    if out.exists():
        die(f"{out} exists; pick another --run-id")
    game = out / "game"
    (game / "saves").mkdir(parents=True)
    shutil.copytree(template, game / "saves" / scenario["world"]["template"])   # fresh copy; the template is never opened
    prepare_game_dir(game, dict(scenario["options"]) | (FUNCTIONAL_OPTIONS if functional else {}), loader)

    jars: dict[str, str] = {}   # file name in mods/ -> SHA-256 of what was copied
    install: dict = {"particlefilter": None, "modset": modset, "render_mods": {}, "sodium_extra_hides_target": se_hides}
    if pf_kind is not None or modset != "vanilla":
        (game / "mods").mkdir()
    if pf_kind is not None:
        jar = Path(pf_jar_path) if pf_jar_path else pf_jar(loader)
        shutil.copy2(jar, game / "mods" / jar.name)
        jars[jar.name] = sha256_file(game / "mods" / jar.name)
        cfg = pf_config(pf_kind, variant, CONFIGS[entries[0][0]][1])
        (game / "config" / "particlefilter.json").write_text(json.dumps(cfg, indent=2) + "\n")
        install["particlefilter"] = {"jar": jar.name, "sha256": jars[jar.name], "config_kind": pf_kind,
                                     "config_sha256": sha256_file(game / "config" / "particlefilter.json"),
                                     "jar_path": str(jar).replace(str(Path.home()), "~")}
        release = pf_release_jar(loader, jar)
        if LOADERS[loader]["pf_release_jar"] is not None:
            # Named-mappings jar: record the release jar it was reobfuscated into, and that they come from one build.
            collected = REPO / LOADERS[loader]["pf_collected_jar"].format(v=jar.name.split("-")[2].split("+")[0])
            install["particlefilter"]["release_jar"] = {
                "jar": release.name if release else None, "sha256": sha256_file(release) if release else None,
                "built_seconds_apart": round(abs(release.stat().st_mtime - jar.stat().st_mtime), 1) if release else None,
                "collected_copy_sha256": sha256_file(collected) if collected.exists() else None}
    for name in MODSETS[modset]:
        jar = render_mod_jar(name)
        shutil.copy2(jar, game / "mods" / jar.name)
        jars[jar.name] = sha256_file(game / "mods" / jar.name)
        install["render_mods"][name] = {"jar": jar.name, "sha256": jars[jar.name], "mod_id": RENDER_MODS[name]["mod_id"],
                                        "source": RENDER_MODS[name]["source"]}
    if modset == "sodium":
        if sodium_defaults:
            install["sodium_configs"] = "mod defaults (capture run: the launcher wrote no Sodium configs)"
        else:
            shutil.copy2(SODIUM_CONFIGS / "sodium-options.json", game / "config" / "sodium-options.json")
            base = json.loads((SODIUM_CONFIGS / "sodium-extra-options.json").read_text())
            se = sodium_extra_options(base, VARIANTS[variant][0], se_hides)
            (game / "config" / "sodium-extra-options.json").write_text(json.dumps(se, indent=2) + "\n")
            install["sodium_configs"] = {"template_dir": str(SODIUM_CONFIGS.relative_to(REPO)),
                                         "sodium_extra_target": VARIANTS[variant][0],
                                         "sodium_extra_target_enabled": not se_hides}

    prov = provenance(loader)
    plan = build_plan(scenario, scenario_sha, entries, variant, functional, run_id, out, prov, loader)
    plan_file = out / "plan.json"
    plan_file.write_text(json.dumps(plan, indent=2) + "\n")
    total_s = sum(p["ticks"] for p in plan["phases"]) / TICKS_PER_SECOND
    inputs = copy_inputs(game, out / "inputs")

    launch = {"run_id": run_id, "label": plan["label"], "loader": loader, "minecraft": LOADERS[loader]["minecraft"],
              "started_utc": stamp, "sequence": sequence,
              "entries": [{"position": i, "config": c, "level": lv} for i, (c, lv) in enumerate(entries, start=1)],
              "variant": variant, "scenario_file": scenario_path.name, "scenario_sha256": scenario_sha,
              "template_sha256_tree": json.loads((template_dir / "template.json").read_text()).get("sha256_tree")
              if (template_dir / "template.json").exists() else None,
              **install, "mods_dir": jars, "inputs_sha256": inputs,
              "render_environment": render_environment(game, modset),
              "jvm_args_requested": scenario.get("jvm", {}).get("args", ""),
              "provenance": prov, "campaign": campaign, "hidden_window": hide, "hidden_window_monitor": monitor if hide else None,
              "mangohud": {"config": MANGOHUD_CONFIG, "dir": "mangohud",
                           "version": collect_env.run(["mangohud", "--version"])} if mangohud else None}
    gradle_args = [f"-Ppfbench.gameDir={game}", f"-Ppfbench.plan={plan_file}",
                   f"-Ppfbench.world={scenario['world']['template']}",
                   "-Ppfbench.jvmArgs=" + scenario.get("jvm", {}).get("args", "")]
    if functional:
        gradle_args.append("-Ppfbench.gameArgs=" + " ".join(FUNCTIONAL_WINDOW))
    jvm_args = scenario.get("jvm", {}).get("args", "")
    launch["env_snapshots"] = {"before": env_snapshot(out, "env-before", game, jvm_args, f"pfbench {run_id}: before launch")}
    (out / "launch.json").write_text(json.dumps(launch, indent=2) + "\n")   # rewritten with the outcome below
    if mangohud:
        (out / "mangohud").mkdir()
    proc = gradle(gradle_args, out / "gradle.log", out / "mangohud" if mangohud else None, loader)
    pid = game_pid()
    if pid is None:
        wait_for_exit(proc, 5)
        die(f"the game didn't start; see {out}/gradle.log")
    sampler = start_sampler(pid, out)
    launch["game_process"], java_exe = game_process(pid)
    # Recorded once the window has loaded (before 2026-11-09 a visible run read it before the window existed: null).
    launch["window"] = hide_window(pid, out / "window.log", monitor) if hide else wait_for_window(pid)
    finished = wait_for_exit(proc, total_s + 600)
    launch["finished_utc"] = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    launch["exited_by_itself"] = finished
    launch["system_sampler"] = {"dir": "system", "interval_s": SAMPLER_INTERVAL_S, "self_cpu": None,
                                "returncode": stop_sampler(sampler)}
    launch["env_snapshots"]["after"] = env_snapshot(out, "env-after", game, jvm_args,
                                                    f"pfbench {run_id}: after the game quit", java=java_exe)
    launch["inputs_after_sha256"] = copy_inputs(game, out / "inputs-after")   # what the mods saved on exit
    (out / "launch.json").write_text(json.dumps(launch, indent=2) + "\n")
    if not finished:
        print(f"pfbench: timed out after {total_s + 600:.0f} s; see {out}/gradle.log", file=sys.stderr)
    return check(out), out


def cmd_run(args: argparse.Namespace) -> int:
    code, _ = run_launch(scenario_path=Path(args.scenario), sequence=args.sequence, variant=args.variant,
                         modset=args.modset, functional=args.functional, hide=args.hide, monitor=args.monitor, pf_jar_path=args.pf_jar,
                         run_id=args.run_id, sodium_defaults=args.sodium_defaults, mangohud=args.mangohud,
                         loader=args.loader)
    return code


# ---- campaign ----

# Comparisons PLAN.md section 1 needs: (configuration, reference, questions). A campaign can only answer one if
# both configurations run in launches of the same shape (number of entries): the launch shape is part of the
# environment compare.py pairs on, because warmup and mode switching differ between a configuration measured alone
# and one measured alongside others.
EXPECTED_COMPARISONS = [
    ("B", "A", "Q1 overhead when installed but inactive"),
    ("C", "B", "Q2-Q4 global filtering"),
    ("D", "B", "Q3 zone filtering"),
    ("D", "C", "Q5 zones vs global"),
    ("C-miss", "B", "Q8 rules that never match"),
    ("E", "C", "Q7 Sodium Extra vs Particle Filter"),
]


def launch_groups(configs: list[str], levels: list[str]) -> dict[tuple, list[tuple[str, str]]]:
    """Entries that share an install state run in one launch: install state -> entries."""
    groups: dict[tuple, list[tuple[str, str]]] = {}
    for config in configs:
        for level in levels:
            groups.setdefault(install_state(config), []).append((config, level))
    return groups


def campaign_analysis(configs: list[str], levels: list[str], blocks: int, seed: int) -> dict:
    """What a campaign design can answer, before anything runs: launch shapes, which configurations compare.py will
    be able to pair, which of PLAN.md's comparisons are possible, missing or impossible, and how well the order is
    balanced. Pure design arithmetic: reads no measurement."""
    groups = launch_groups(configs, levels)
    shape = {c: len(groups[install_state(c)]) for c in configs}
    partners = {c: sorted(o for o in configs if o != c and shape[o] == shape[c]) for c in configs}
    unpairable = [c for c in configs if not partners[c]] if len(configs) > 1 else []
    comparisons = []
    for cfg, ref, question in EXPECTED_COMPARISONS:
        if cfg not in configs:
            continue
        if ref not in configs:
            status = f"not answerable: reference {ref} isn't in this campaign"
        elif shape[cfg] != shape[ref]:
            status = (f"IMPOSSIBLE: {cfg} runs in launches of {shape[cfg]} entr{'y' if shape[cfg] == 1 else 'ies'}, "
                      f"{ref} in launches of {shape[ref]}; compare.py will pair none of them")
        else:
            status = "pairable"
        comparisons.append({"config": cfg, "reference": ref, "question": question, "status": status})

    plan = campaign_schedule(configs, levels, blocks, seed)
    labels = ["|".join(f"{c}:{lv}" for c, lv in g) for g in groups.values()]
    balance = []

    def tally(unit: str, orders: list[list[str]], items: list[str]) -> None:
        rows = len(schedule.williams_rows(len(items)))
        pos = {i: [0] * len(items) for i in items}
        after: dict[tuple[str, str], int] = {}
        for order in orders:
            for k, item in enumerate(order):
                pos[item][k] += 1
                if k:
                    after[(order[k - 1], item)] = after.get((order[k - 1], item), 0) + 1
        pairs = [after.get((a, b), 0) for a in items for b in items if a != b]
        balance.append({
            "unit": unit, "items": items, "williams_rows": rows, "exact": blocks % rows == 0,
            "positions": pos,
            "positions_balanced": all(max(v) == min(v) for v in pos.values()),
            "carry_over_min_max": (min(pairs), max(pairs)) if pairs else None,
        })

    label_of = dict(zip(groups, labels))
    if len(labels) > 1:
        orders = [[label_of[install_state(launch[0][0])] for launch in launches] for launches in plan]
        tally("launches within a block", orders, labels)
    for label, entries in zip(labels, groups.values()):
        if len(entries) > 1:
            items = [f"{c}:{lv}" for c, lv in entries]
            orders = [[f"{c}:{lv}" for c, lv in launch] for launches in plan for launch in launches
                      if set(launch) == set(entries)]
            tally(f"entries within launch {label}", orders, items)
    return {"groups": {label: [f"{c}:{lv}" for c, lv in g] for label, g in zip(labels, groups.values())},
            "shape": shape, "partners": partners, "unpairable": unpairable, "comparisons": comparisons,
            "balance": balance, "plan": plan}


def campaign_schedule(configs: list[str], levels: list[str], blocks: int, seed: int) -> list[list[list[tuple[str, str]]]]:
    """blocks -> launches -> entries. Entries sharing an install state go into one launch, in Williams order
    (tools/schedule.py); the launches of a block alternate in Williams order too. Seeds differ per group, and every
    order is reproducible from `seed`."""
    groups = launch_groups(configs, levels)
    keys = list(groups)
    labels = ["|".join(f"{c}:{lv}" for c, lv in groups[k]) for k in keys]
    group_orders = schedule.make_schedule(labels, blocks, seed)
    inner = {k: schedule.make_schedule([f"{c}:{lv}" for c, lv in groups[k]], blocks, seed + 1 + i)
             for i, k in enumerate(keys)}
    out = []
    for b in range(blocks):
        launches = []
        for label in group_orders[b]:
            k = keys[labels.index(label)]
            launches.append([tuple(item.rsplit(":", 1)) for item in inner[k][b]])
        out.append(launches)
    return out


def print_campaign_analysis(analysis: dict, scenario: dict, scenario_path: Path, args: argparse.Namespace,
                            cooldown: float) -> None:
    """The dry-run summary: pairability, balance and phase time. Design arithmetic only, no measurement."""
    print("\nlaunch groups (one launch each per block; entries share an install state):")
    for label, items in analysis["groups"].items():
        print(f"  {len(items)} entr{'y' if len(items) == 1 else 'ies'}: {label}")
    print("\ncomparisons PLAN.md needs, for the configurations in this campaign:")
    for x in analysis["comparisons"] or [{"config": "-", "reference": "-", "question": "none", "status": "n/a"}]:
        print(f"  {x['config']} vs {x['reference']} ({x['question']}): {x['status']}")
    for c in analysis["unpairable"]:
        print(f"  WARNING: {c} has no configuration with the same launch shape; its windows can't be paired at all")
    print("\norder balance (Williams design, seed {}):".format(args.seed))
    for bal in analysis["balance"]:
        exact = "exact" if bal["exact"] else f"NOT exact: use a multiple of {bal['williams_rows']} blocks"
        co = bal["carry_over_min_max"]
        print(f"  {bal['unit']}: {exact}; positions per item "
              + "; ".join(f"{k} {v}" for k, v in bal["positions"].items())
              + (f"; each item directly after each other one {co[0]}-{co[1]} times" if co else ""))
    if not analysis["balance"]:
        print("  one configuration per launch and one launch per block: nothing to balance")
    _, sha = load_scenario(scenario_path)
    total_s = 0.0
    launches = 0
    for block in analysis["plan"]:
        for launch in block:
            p = build_plan(scenario, sha, launch, args.variant, args.functional, "dry-run", RUN_ROOT / "dry-run", {},
                           args.loader)
            total_s += sum(ph["ticks"] for ph in p["phases"]) / TICKS_PER_SECOND
            launches += 1
    total_s += cooldown * max(0, launches - 1)
    print(f"\n{launches} launches, {total_s / 3600:.2f} h of scripted phases and cool-downs "
          f"({'functional' if args.functional else 'performance'} timing, {cooldown:g} s cool-down; game start-up and "
          "quit not included). Loader: " + f"{args.loader} (Minecraft {LOADERS[args.loader]['minecraft']}), "
          f"mod set {args.modset}, variant {args.variant}.")


def cmd_campaign(args: argparse.Namespace) -> int:
    scenario, _ = load_scenario(Path(args.scenario))
    configs = [c.strip() for c in args.configs.split(",") if c.strip()]
    levels = [lv.strip() for lv in args.levels.split(",") if lv.strip()]
    for c in configs:
        if c not in CONFIGS:
            die(f"unknown configuration {c!r}")
    for lv in levels:
        if lv not in scenario["levels"]:
            die(f"unknown level {lv!r}")
    if len(set(configs)) != len(configs):
        die("a configuration is listed twice")
    analysis = campaign_analysis(configs, levels, args.blocks, args.seed)
    plan = analysis["plan"]
    for launches in plan:
        for launch in launches:
            problem = launch_problem(launch, args.variant, args.modset, args.loader)
            if problem:
                die(f"launch {','.join(f'{c}:{lv}' for c, lv in launch)}: {problem}")
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    name = args.name or f"campaign-{stamp}"
    sheet = {"campaign": name, "seed": args.seed, "configs": configs, "levels": levels, "variant": args.variant,
             "modset": args.modset, "label": "functional" if args.functional else "performance",
             "mangohud": args.mangohud, "loader": args.loader,
             "blocks": [[",".join(f"{c}:{lv}" for c, lv in launch) for launch in launches] for launches in plan]}
    cooldown = FUNCTIONAL_COOLDOWN_S if args.functional else scenario["phases"].get("cooldown_between_launches", 60)
    for b, launches in enumerate(sheet["blocks"], start=1):
        print(f"block {b}: " + "  |  ".join(launches))
    print_campaign_analysis(analysis, scenario, Path(args.scenario), args, cooldown)
    design_errors = [f"{c} can't be paired with any other configuration of this campaign" for c in analysis["unpairable"]]
    design_errors += [f"{x['config']} vs {x['reference']}: {x['status']}" for x in analysis["comparisons"]
                      if x["status"].startswith("IMPOSSIBLE")]
    if args.dry_run:
        return 0
    if design_errors and not args.functional:
        die("this campaign would measure configurations that compare.py can't pair; split it into one campaign per "
            "question (REPRODUCING.md, \"Campaign designs\"):\n  " + "\n  ".join(design_errors))
    if not args.functional and os.environ.get("PFBENCH_PERFORMANCE_APPROVED") != "1":
        die("performance runs are blocked until the owner confirms the laptop's power-limit problems are fixed "
            "(benchmarks/HANDOFF.md). Use --functional or --dry-run.")
    sheet_dir = RUN_ROOT / "campaigns"
    sheet_dir.mkdir(parents=True, exist_ok=True)
    sheet["launches"] = []
    failures = 0
    for b, launches in enumerate(sheet["blocks"], start=1):
        for li, sequence in enumerate(launches, start=1):
            if sheet["launches"]:
                time.sleep(cooldown)
            run_id = f"{name}-b{b:02d}-l{li}-{sequence.replace(':', '_').replace(',', '-')}"
            code, out = run_launch(scenario_path=Path(args.scenario), sequence=sequence, variant=args.variant,
                                   modset=args.modset, functional=args.functional, hide=args.hide, monitor=args.monitor, run_id=run_id,
                                   campaign={"name": name, "block": b, "launch_in_block": li, "seed": args.seed},
                                   mangohud=args.mangohud, loader=args.loader)
            failures += code != 0
            sheet["launches"].append({"block": b, "launch_in_block": li, "sequence": sequence, "run_id": run_id,
                                      "check_passed": code == 0})
            (sheet_dir / f"{name}.json").write_text(json.dumps(sheet, indent=2) + "\n")
    print(f"campaign {name}: {len(sheet['launches'])} launches, {failures} with failed checks -> {sheet_dir / (name + '.json')}")
    return 0 if failures == 0 else 1


# ---- functional checks ----

def read_csv(path: Path) -> list[dict]:
    with open(path, newline="") as fh:
        return list(csv.DictReader(fh))


def check(run: Path) -> int:
    """Functional checks only: are the counters consistent, do the files parse, did the run use exactly the files
    the launcher prepared? Reports no timing. Kept apart from measurement: it reads the logs after the game quit."""
    results: list[dict] = []

    def record(name: str, ok: bool, detail: str = "") -> None:
        results.append({"check": name, "ok": bool(ok), "detail": detail})

    harness_file = run / "harness.json"
    if not harness_file.exists():
        record("harness.json written", False, "missing: the harness didn't finish (see gradle.log)")
        return report(run, results)
    harness = json.loads(harness_file.read_text())
    plan = json.loads((run / "plan.json").read_text())
    launch = json.loads((run / "launch.json").read_text()) if (run / "launch.json").exists() else {}
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
    record("window size unchanged from first phase to quit", setup.get("window_at_start") == harness.get("window_at_end"),
           f"{setup.get('window_at_start')} -> {harness.get('window_at_end')}")
    record("no frame or tick rows dropped", harness.get("frames_dropped_capacity") == 0 and harness.get("ticks_dropped_capacity") == 0)

    phases = read_csv(run / "phases.csv")
    configs = {p["config"] for p in phases if p["config"]}
    pf_expected = any(CONFIGS[c][0] is not None for c in configs)
    record("Particle Filter installed as the configuration requires",
           bool(setup.get("particlefilter_present")) == pf_expected, f"present={setup.get('particlefilter_present')}")
    for p in phases:
        if p["mode_requested"] and pf_expected:
            record(f"mode switched: {p['name']}", p["mode_after_start"] == p["mode_requested"],
                   f"requested {p['mode_requested']}, got {p['mode_after_start']}")

    # Provenance: the run used exactly the jars the launcher copied, and the versions it expected.
    if launch:
        prov = launch.get("provenance") or {}
        record("harness version matches the source tree", harness.get("versions", {}).get("pfbench") == prov.get("harness_version"),
               f"loaded {harness.get('versions', {}).get('pfbench')}, source {prov.get('harness_version')}")
        folder = {f["file"]: f.get("sha256") for f in harness.get("mods_folder", [])}
        record("mods/ held exactly the jars the launcher copied (SHA-256, read in game)", folder == launch.get("mods_dir", {}),
               f"in game: {sorted(folder)}")
        mod_ids = {m.split(" ", 1)[0] for m in harness.get("mods", [])}
        loader = launch.get("loader", "neoforge")
        versions = harness.get("versions", {})
        if "loader" in versions:
            record("harness ran on the planned loader and Minecraft version",
                   versions["loader"] == loader and versions.get("minecraft") == LOADERS[loader]["minecraft"],
                   f"{versions['loader']} {versions.get('minecraft')}, planned {loader} {LOADERS[loader]['minecraft']}")
        if launch.get("particlefilter"):
            want = launch["particlefilter"]["jar"].split("-")[2].split("+")[0]   # particlefilter-<loader>-<ver>+<mc>.jar
            got = versions.get("particlefilter")
            record("loaded Particle Filter version matches the jar", got == want, f"loaded {got}, jar {want}")
            if "release_jar" in launch["particlefilter"]:
                rel = launch["particlefilter"]["release_jar"]
                apart = rel.get("built_seconds_apart")
                record("named-mappings jar was built with the release jar (same Gradle run, <= 60 s apart)",
                       bool(rel.get("sha256")) and apart is not None and apart <= 60,
                       f"release {rel.get('jar')} {str(rel.get('sha256'))[:12]}, {apart} s apart")
                record("that release jar is the one collected for release (build/libs/<version>/)",
                       rel.get("collected_copy_sha256") in (None, rel.get("sha256")),
                       f"collected copy {str(rel.get('collected_copy_sha256'))[:12]}")
        for name, spec in RENDER_MODS.items():
            want = name in MODSETS[launch.get("modset", "vanilla")]
            record(f"{spec['mod_id']} loaded only when the mod set includes it", (spec["mod_id"] in mod_ids) == want,
                   f"loaded={spec['mod_id'] in mod_ids}, modset={launch.get('modset', 'vanilla')}")
        record("GPU renderer recorded", bool(setup.get("gl", {}).get("renderer")), str(setup.get("gl", {}).get("renderer")))
        sc = launch.get("sodium_configs")
        if isinstance(sc, dict):
            after = run / "inputs-after" / "sodium-extra-options.json"
            value = None
            if after.exists():
                value = json.loads(after.read_text()).get("particle_settings", {}).get("other", {}).get(sc["sodium_extra_target"])
            record("Sodium Extra kept the target entry the launcher wrote", value == sc["sodium_extra_target_enabled"],
                   f"{sc['sodium_extra_target']} = {value}")

    if plan.get("label") != "functional":
        shots = list((run / "game" / "screenshots").glob("*.png")) if (run / "game" / "screenshots").exists() else []
        record("no screenshots in a performance run", not shots, f"{len(shots)} files")

    ticks = read_csv(run / "ticks.csv")
    by_phase: dict[str, list[dict]] = {}
    for t in ticks:
        by_phase.setdefault(t["phase"], []).append(t)
    variant = plan["variant"]
    _, created_col = VARIANTS[variant]
    life_t = plan["target_lifetime_ticks"] + 1   # a particle is alive in lifetime+1 consecutive post-tick counts
    life_c = plan["control_lifetime_ticks"] + 1
    rate_c = plan["control_attempts_per_tick"]
    queue_col = VARIANT_QUEUE[variant]
    have_control_col = bool(ticks) and "alive_control" in ticks[0]
    for col in ("alive_control", "jit_time_ms", "classes_loaded", "server_saves"):
        record(f"ticks.csv has {col}", bool(ticks) and col in ticks[0])
    if ticks and "jit_time_ms" in ticks[0]:
        jit = [int(t["jit_time_ms"]) for t in ticks]
        record("JIT time is logged and never decreases", jit[0] >= 0 and all(b >= a for a, b in zip(jit, jit[1:])))

    # Contamination: every settle after a switch is long enough for the previous phase's particles to expire.
    settle_min = plan.get("min_settle_ticks") or min_settle_ticks((plan["target_lifetime_ticks"], plan["control_lifetime_ticks"]), variant)
    short = [p["name"] for p in phases if p["role"] == "switch-settle" and int(p["ticks"]) < settle_min]
    record(f"every settle after a switch is >= {settle_min} ticks (longest particle life + 2)", not short, ", ".join(short))
    # Warmup ran every configuration measured in this launch before the first measurement.
    measured = {p["config"] for p in phases if p["role"] == "measure"}
    warmed = {p["config"] for p in phases if p["role"] == "warmup" and p["config"]}
    record("warmup ran every measured configuration", measured <= warmed, f"measured {sorted(measured)}, warmed {sorted(warmed)}")

    for p in phases:
        rows = by_phase.get(p["name"], [])
        record(f"ticks logged: {p['name']}", len(rows) == int(p["ticks"]), f"{len(rows)} of {p['ticks']}")
        if p["role"] not in ("measure", "switch-settle") or not rows:
            continue
        s = lambda col: sum(int(r[col]) for r in rows)  # noqa: E731
        attempts, created = s("attempts_target"), s(created_col)
        filtered = p["config"] in FILTERED
        expect = 0 if filtered else attempts
        record(f"{p['name']}: target created {'== 0' if filtered else '== attempts'}", created == expect,
               f"attempts {attempts}, created {created}")
        record(f"{p['name']}: control created == attempts", s("created_control") == s("attempts_control"),
               f"attempts {s('attempts_control')}, created {s('created_control')}")
        if p["role"] != "measure":
            continue
        rate = float(p["target_per_tick"])
        want_c = min(int(rate_c) * life_c, QUEUE_LIMIT) if rate_c == int(rate_c) else None

        def target_alive(r: dict) -> int:
            n = int(r[queue_col])
            return n - int(r["alive_control"]) if variant == "vanilla" else n   # end_rod shares the control's queue

        if filtered and (variant != "vanilla" or have_control_col):
            # No target particle may be alive in a filtered window: none created, none left from the previous phase.
            ok = sum(1 for r in rows if target_alive(r) == 0)
            record(f"{p['name']}: no target particle alive (no carry-over)", ok == len(rows), f"{ok}/{len(rows)} ticks")
        elif variant in ("typed", "untyped") and rate == int(rate):
            want = min(int(rate) * life_t, QUEUE_LIMIT)
            ok = sum(1 for r in rows if int(r["alive_opaque"]) == want)
            record(f"{p['name']}: target alive == {want} every tick", ok == len(rows), f"{ok}/{len(rows)} ticks")
        if want_c is not None and have_control_col:
            ok = sum(1 for r in rows if int(r["alive_control"]) == want_c)
            record(f"{p['name']}: control alive == {want_c} every tick", ok == len(rows), f"{ok}/{len(rows)} ticks")
        if want_c is not None and variant != "vanilla":
            ok = sum(1 for r in rows if int(r["alive_translucent"]) == want_c)
            record(f"{p['name']}: translucent queue == control only ({want_c})", ok == len(rows), f"{ok}/{len(rows)} ticks")

    # The frame log must parse with the analysis tool; only row counts are reported here, never timings.
    import frametimes  # noqa: E402  (benchmarks/tools)
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
    if launch:
        check_system_data(run, launch, harness, phases, record)
    return report(run, results)


def check_system_data(run: Path, launch: dict, harness: dict, phases: list[dict], record) -> None:
    """Environment snapshots, system sampler, GC log, MangoHud: written, parseable, covering the measured phases.
    Counts and coverage only; no timing is read."""
    snaps = launch.get("env_snapshots")
    if snaps is not None:
        envs = {}
        for when in ("before", "after"):
            info = snaps.get(when) or {}
            path = run / str(info.get("file", f"env-{when}.json"))
            try:
                envs[when] = json.loads(path.read_text())
                ok = info.get("returncode") == 0 and bool(envs[when].get("cpu"))
            except (OSError, json.JSONDecodeError):
                ok = False
            record(f"environment snapshot written: {path.name}", ok, f"returncode {info.get('returncode')}")
        if len(envs) == 2:
            a, b = (system_data.power_state(envs[w]) for w in ("before", "after"))
            diff = sorted(k for k in a if a[k] != b.get(k))
            record("power settings unchanged from before to after the launch (AC, profiles, RAPL limits)", not diff,
                   ", ".join(f"{k}: {a[k]} -> {b.get(k)}" for k in diff))
    if launch.get("system_sampler") is not None:
        info = launch["system_sampler"]
        record("system sampler ran without --self-cpu (no affinity change)", info.get("self_cpu") is None)
        sampler = system_data.load_sampler(run / info.get("dir", "system"))
        record("system sampler wrote meta.json, system.csv and threads.csv",
               sampler is not None and bool(sampler["system"]) and bool(sampler["threads"]),
               f"{len(sampler['system']) if sampler else 0} system rows")
        clock = harness.get("clock")
        record("harness.json records the clock origin", bool(clock and clock.get("origin_nano_time")))
        measures = [p for p in phases if p["role"] == "measure" and p["start_t_ns"] and p["end_t_ns"]]
        if sampler and sampler["system"] and clock and measures:
            first = system_data.window_mono(harness, int(measures[0]["start_t_ns"]), int(measures[0]["end_t_ns"]))[0]
            last = system_data.window_mono(harness, int(measures[-1]["start_t_ns"]), int(measures[-1]["end_t_ns"]))[1]
            interval = int(float(sampler["meta"].get("interval_s", 1.0)) * 1e9)
            mono = [r["mono_ns"] for r in sampler["system"]]
            # One interval of slack at the end: the game quits right after its last phase, so the sampler's final
            # row can land just before the end (seen: 20 ms). Ingest counts whole intervals only, so the last window
            # loses at most one sample; a sampler that stopped early still fails.
            covered = min(mono) - interval <= first and max(mono) >= last - interval
            record("sampler covers every measured phase", covered,
                   f"{sum(1 for m in mono if first <= m <= last)} samples inside the measured span")
            if sampler["meta"].get("gpu_query"):
                record("GPU samples line up with the measured phases",
                       any(first <= g["mono_ns"] <= last for g in sampler["gpu"]), f"{len(sampler['gpu'])} GPU rows")
    gc_log = run / "game" / "gc.log"
    if gc_log.exists():
        pauses = system_data.parse_gc_log(gc_log)
        record("gc.log parses (the settles' System.gc() pauses are there)",
               any("System.gc()" in p["kind"] for p in pauses), f"{len(pauses)} pauses")
    if launch.get("mangohud"):
        logs = [f for f in (run / "mangohud").glob("*.csv") if not f.name.endswith("_summary.csv")]
        rows = sum(max(0, sum(1 for _ in open(f)) - 3) for f in logs)   # MangoHud: 2 header lines + column header
        record("MangoHud wrote a per-frame log", rows > 0, f"{len(logs)} files, {rows} rows")


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
    t.add_argument("--loader", default="neoforge", choices=list(LOADERS), help="neoforge (1.21.1) or forge (1.20.1)")
    t.add_argument("--force", action="store_true")
    t.add_argument("--hide", action="store_true", help="move the game window to the hidden special workspace")
    r = sub.add_parser("run", help="one launch")
    r.add_argument("--scenario", default=str(DEFAULT_SCENARIO))
    r.add_argument("--loader", default="neoforge", choices=list(LOADERS), help="neoforge (1.21.1) or forge (1.20.1)")
    r.add_argument("--sequence", required=True, help="CONFIG:LEVEL,... e.g. C:heavy,B:heavy,D:heavy")
    r.add_argument("--variant", default="typed", choices=list(VARIANTS))
    r.add_argument("--modset", default="vanilla", choices=list(MODSETS), help="rendering mods in every configuration")
    r.add_argument("--functional", action="store_true", help="short phases, 60 FPS cap, windowed: bring-up checks only")
    r.add_argument("--hide", action="store_true", help="move the game window to the hidden special workspace")
    r.add_argument("--monitor", help="with --hide: pin the hidden workspace to this monitor (e.g. DP-1), so the "
                                     "window size doesn't depend on which monitor had focus")
    r.add_argument("--mangohud", action="store_true", help="run under MangoHud and keep its per-frame log (raw data)")
    r.add_argument("--pf-jar", help="Particle Filter jar (default: the loader's jar of the repo's current version, see LOADERS)")
    r.add_argument("--run-id")
    r.add_argument("--sodium-defaults", action="store_true",
                   help="functional only: don't write Sodium configs, so the mods save their defaults (to capture them)")
    c = sub.add_parser("campaign", help="several launches in a counterbalanced order")
    c.add_argument("--scenario", default=str(DEFAULT_SCENARIO))
    c.add_argument("--loader", default="neoforge", choices=list(LOADERS), help="neoforge (1.21.1) or forge (1.20.1)")
    c.add_argument("--configs", required=True, help="e.g. C,B,D,A")
    c.add_argument("--levels", required=True, help="e.g. heavy or moderate,heavy")
    c.add_argument("--blocks", type=int, required=True)
    c.add_argument("--seed", type=int, required=True, help="record it; the whole order follows from it")
    c.add_argument("--variant", default="typed", choices=list(VARIANTS))
    c.add_argument("--modset", default="vanilla", choices=list(MODSETS))
    c.add_argument("--name")
    c.add_argument("--functional", action="store_true")
    c.add_argument("--hide", action="store_true")
    c.add_argument("--monitor", help="with --hide: pin the hidden workspace to this monitor (e.g. DP-1)")
    c.add_argument("--mangohud", action="store_true", help="every launch under MangoHud (all or none: the HUD renders)")
    c.add_argument("--dry-run", action="store_true",
                   help="print the launch order, pairability, balance and phase time; launch nothing")
    k = sub.add_parser("check", help="functional checks of a finished run")
    k.add_argument("run_dir")
    args = p.parse_args(argv)
    if args.command == "template":
        return cmd_template(args)
    if args.command == "run":
        return cmd_run(args)
    if args.command == "campaign":
        return cmd_campaign(args)
    return check(Path(args.run_dir).resolve())


if __name__ == "__main__":
    sys.exit(main())
