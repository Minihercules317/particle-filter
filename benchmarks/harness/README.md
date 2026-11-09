# pfbench: the synthetic benchmark harness

A benchmark-only client mod and launcher that implement [HARNESS.md](../HARNESS.md) for **NeoForge 1.21.1** and
(since 2026-11-09) **Forge 1.20.1** ([../harness-forge](../harness-forge), see "Forge 1.20.1" below).
It is **not** part of Particle Filter. It's a separate Gradle build that the release build (`settings.gradle.kts`
at the repository root), CI and CurseForge uploads never include. Particle Filter contains no benchmark code.

> **Status: built and functionally validated** ([results/functional-2026-11-09](../results/functional-2026-11-09/FUNCTIONAL.md),
> [results/functional-2026-11-09-pipeline](../results/functional-2026-11-09-pipeline/FUNCTIONAL.md),
> [results/functional-2026-11-09-sysdata](../results/functional-2026-11-09-sysdata/FUNCTIONAL.md),
> [results/functional-2026-11-09-forge](../results/functional-2026-11-09-forge/FUNCTIONAL.md)).
> **No performance runs yet:** they are blocked until the owner confirms the laptop's power-limit problems are
> fixed. The launcher refuses a non-`--functional` run or campaign unless `PFBENCH_PERFORMANCE_APPROVED=1` is set,
> which must only happen after that confirmation.

Written by Claude (AI), like the rest of the benchmark suite and the mod.

## Use

From the repository root. Launches run Gradle `--offline`, so prepare the cache once (online) first. On the
owner's machine Java comes from mise (`mise exec --`, used automatically when mise and the untracked `mise.toml`
exist); anywhere else a JDK 21 on `PATH` is enough. Third-party setup: [REPRODUCING.md](../REPRODUCING.md).

```sh
./gradlew -p benchmarks/harness prepareClientRun                 # once, online: harness, Minecraft, assets
python3 benchmarks/harness/pfbench.py template --hide           # once: create the template void world
python3 benchmarks/harness/pfbench.py run --functional --hide --sequence C:moderate,B:moderate,D:moderate
python3 benchmarks/harness/pfbench.py run --functional --hide --mangohud --sequence B:moderate   # + MangoHud frame log
python3 benchmarks/harness/pfbench.py check benchmarks/harness/run/runs/<run-id>
python3 benchmarks/harness/pfbench.py campaign --configs C,B,D --levels heavy --blocks 6 --seed 1 --dry-run
python3 benchmarks/tools/publish_campaign.py archive benchmarks/results/<campaign> benchmarks/harness/run/runs/<campaign>-b*
python3 benchmarks/tools/publish_campaign.py report benchmarks/results/<campaign> --reference B   # ingest + compare + report
python3 benchmarks/tools/publish_campaign.py manifest benchmarks/results/<campaign>
python3 benchmarks/tools/publish_campaign.py verify benchmarks/results/<campaign>
```

- `--sequence` lists `CONFIG:LEVEL` entries ([PLAN.md §2](../PLAN.md) ids; levels from the scenario's `[levels]`).
  One launch has one install state: `A` alone (Particle Filter not installed), `B`/`C`/`D` together (one
  config file, the harness switches the Active rules mode), `C-miss` alone, or `E` alone (Sodium Extra hides
  the target; needs `--modset sodium`).
- `--variant typed|untyped|block_dust|vanilla` picks what the target is (scenario `[variants]`). The launcher
  writes a Particle Filter config whose rule matches that target (`tools/make_pf_config.py`). `block_dust`
  works with B/C only: block rules are global, so there is no D. `E` works with `typed` and `vanilla` only
  (Sodium Extra hides typed particles by type id).
- `--modset vanilla|sodium`: rendering mods present in **every** configuration of the launch. `sodium` copies
  Sodium 0.8.13 and Sodium Extra 0.9.5 from `run/downloads/` (pinned by SHA-256; see
  [SODIUM.md](../results/functional-2026-11-09-pipeline/SODIUM.md) for where they come from) and writes their configs
  from `../configs/sodium/`. Only Sodium Extra's entry for the target differs between configurations.
- `--functional`: 5 s phases (6 s warmup, 4 s settle after a switch), 60 FPS cap, windowed (1280×720 requested;
  Hyprland's tiling may resize it), screenshots at the end of each measured phase, and every output labelled
  `functional`.
- `--hide` moves the game window to the hidden Hyprland special workspace `pfbench` (functional runs).
- Particle Filter's jar defaults to the repository's built NeoForge jar for the current `mod.version`
  (`./gradlew :1.21.1-neoforge:build buildAndCollect`); `--pf-jar` picks another.
- `--mangohud` runs the launch under MangoHud 0.8.4 with the pilot's minimal HUD (`fps_only,background_alpha=0`;
  `no_display` would stop its logging) and keeps the per-frame log in `mangohud/` as raw data for the phase 2b
  cross-check. The HUD renders, so it is part of the environment: use it for every launch of a campaign or none
  (`campaign --mangohud` does that).
- `campaign` runs several launches in a counterbalanced order (below). `--dry-run` launches nothing and prints the
  order, the launch groups, which of PLAN.md's comparisons the design can pair (`pairable`, `not answerable`,
  `IMPOSSIBLE`), the position and carry-over balance, and the scripted duration. Every launch's options are validated
  before the first one starts, and a performance campaign with an impossible comparison is refused: one campaign per
  question ([REPRODUCING.md §7](../REPRODUCING.md), [DRYRUN.md](../results/dryrun-2026-11-09/DRYRUN.md)).

Everything a launch writes is under `benchmarks/harness/run/` (gitignored): the template in `run/template/`, one
directory per launch in `run/runs/<run-id>/` with the game directory in `game/`, the downloaded rendering mods in
`run/downloads/`, campaign sheets in `run/campaigns/`. Nothing touches a Minecraft installation, world or system
setting.

## Forge 1.20.1

`benchmarks/harness-forge/` is a second Gradle build (ModDevGradle legacyforge 2.0.148, Forge 1.20.1-47.4.28,
Java 17, mixin refmap) that compiles the **same** harness code: `harness/src/main` holds everything shared (Runner,
counters, frame and tick logs, particles, both mixins, particle definitions); each build adds its own entry point,
`Platform` class (mod list, server tick time, world creation: the only API differences found), mods.toml and mixin
config (`harness/src/neoforge`, `harness-forge/src/main`). Both builds take the harness version from
`harness/gradle.properties`, and the Forge provenance hash covers the shared and the Forge-only sources.

```sh
./gradlew -p benchmarks/harness-forge prepareClientRun        # once, online
python3 benchmarks/harness/pfbench.py template --loader forge --hide          # run/template-forge-1.20.1/
python3 benchmarks/harness/pfbench.py run --loader forge --functional --hide --sequence C:moderate,B:moderate,D:moderate
```

- `--loader forge` on `template`, `run` and `campaign`; the default stays `neoforge`. The loader is recorded in
  `plan.json`, `launch.json` and `harness.json["versions"]["loader"]` (a check compares them), and ingest puts it in
  the environment, so windows of different loaders are never paired.
- **Particle Filter jar:** the release jar for Forge is reobfuscated to SRG names, which only a production Forge
  loads; the dev client runs on Mojang's names. The launcher therefore copies the jar `reobfJar` was made from
  (`versions/1.20.1-forge/build/devlibs/`, written by the same Gradle run as the release jar) and records both
  hashes and how far apart they were built (a check requires ≤ 60 s). The bytecode is the same apart from names.
  Like the NeoForge harness, this measures the dev runtime, not a production install (a limitation for both loaders).
- Only `--modset vanilla`: no rendering mods are approved for Forge 1.20.1, so configuration E doesn't exist there.
- Checked in the 1.20.1 sources: `Minecraft.getFrameTimeNs()` exists and spans render start to blit as in 1.21.1;
  particle lifetimes (end_rod 60–71, default ≤ 40) and the 32-block spawn limit are unchanged, so the settle rules hold;
  the integrated server's tick time is a float in ms (converted to ns).

## How a launch works

1. `pfbench.py` reads the scenario (`../scenarios/S1-synthetic.toml`) and writes `plan.json`: the full phase list
   with tick counts, rates and modes. The mod executes the plan literally and makes no decisions of its own.
2. It prepares the game directory: `options.txt` from the scenario's `[options]`, `config/fml.toml` with
   NeoForge's early loading window off, a fresh copy of the template world, Particle Filter's release jar and
   config for B/C/D/C-miss, and the rendering mods and their configs for `--modset sodium`. Copies of these input
   files go to `inputs/`.
3. It takes an environment snapshot (`tools/collect_env.py` → `env-before.json`: CPU, power and RAPL limits, GPU,
   displays, load, the run's game directory), then starts `./gradlew -p benchmarks/harness runClient` (under
   MangoHud with `--mangohud`) with Gradle properties that point the run at that directory and plan, and open the
   world with `--quickPlaySingleplayer pfbench`. As soon as the game JVM appears it starts `tools/sample_system.py`
   on it (`system/`, one sample per second until the game exits; never `--self-cpu`) and records the process's
   command line, java binary and CPU affinity (read, never changed).
4. In game, the harness waits for the world, makes the player a spectator at the camera position, waits 1 s,
   then runs the phases: `load-settle` → warmup → for each entry, `NN-CONFIG-LEVEL-settle` (mode switch and
   `System.gc()` at its start) → `NN-CONFIG-LEVEL` (measure). With several entries the warmup cycles through all of
   them twice (`warmup-r1-01-C`, `warmup-r1-02-B`, …), each in its own mode. Then the harness writes its files and quits.
5. The launcher stops the sampler if it hasn't stopped by itself, takes `env-after.json` (with the game's java
   version), copies the configs as the mods saved them on exit to `inputs-after/`, and `pfbench.py check` runs
   the functional checks and writes `check.json`. It prints no timings.

## Outputs (per launch)

| File | Content |
|---|---|
| `plan.json` | What was executed (its SHA-256 is in `harness.json`), including the launcher's provenance |
| `frames.csv` | `t_ns,frame_ns,cpu_frame_ns,phase`: one row per frame from the first phase to the last. `frame_ns` is the interval between frame ends (end of `Minecraft.runTick`, after swap and limiter); `cpu_frame_ns` is `Minecraft.getFrameTimeNs()` (render to blit), a diagnostic. `tools/frametimes.py` reads it |
| `ticks.csv` | One row per client tick: attempts (target, control); created (target, control, untyped, block, vanilla, other); alive per render type (opaque, translucent, terrain, total) and the control particles' own live count (`alive_control`); client tick ns; integrated server average tick ns; GC count and time; heap used; cumulative JIT compile time, classes loaded and level saves |
| `phases.csv` | Per phase: role, configuration, level, requested mode and the mode after the switch, start/end `t_ns`, ticks, rate |
| `harness.json` | Label, versions, mod list, every jar in `mods/` with its SHA-256 (read in game), every loaded mod file (nested jar-in-jar files marked), actual JVM arguments, GPU renderer/vendor/driver, seed, variant, setup state (world, gamerules, player, options, window at start and end), Particle Filter mode per phase, buffer overflow counts, notes |
| `launch.json` | Launcher side: sequence and entry positions, campaign/block, scenario and template hashes, Particle Filter jar and config SHA-256, rendering mods, SHA-256 of every input file before and after, the rendering-environment hash, provenance (git commit, uncommitted paths, harness source hash, launcher and tools hashes), window state |
| `inputs/`, `inputs-after/` | `options.txt`, `fml.toml`, `particlefilter.json`, `sodium-options.json`, `sodium-extra-options.json` as written before the launch and as saved at quit |
| `check.json` | Functional check results |
| `game/gc.log` | JVM GC log (scenario `[jvm]` arguments); `tools/system_data.py` parses its stop-the-world pauses |
| `env-before.json`, `env-after.json`, `env.log` | `tools/collect_env.py` snapshots before Gradle starts and after the game quit. The power state (AC, platform/power profile, governor, EPP, no_turbo, RAPL limits) must be equal in both: a check fails and ingest marks the windows unusable otherwise |
| `system/` (`meta.json`, `system.csv`, `threads.csv`, `gpu.csv`), `sampler.log` | `tools/sample_system.py` for the game's whole life: per-thread CPU % and core, CPU busy %, P-/E-core clocks, package temperature, RSS, nvidia-smi (utilization, clocks, power, temperature, P-state, throttle reasons). Rows carry `mono_ns` (CLOCK_MONOTONIC); `meta.json` holds wall/monotonic clock pairs that convert nvidia-smi's timestamps |
| `mangohud/` | With `--mangohud`: MangoHud's per-frame CSV (no `_summary.csv`: MangoHud writes it when logging stops, and the game quits first). Raw data, unused by the pipeline until phase 2b |
| `harness.json["clock"]` | `System.nanoTime()`, wall clock and JVM uptime at the phase origin (harness 0.3.0+): every `t_ns` is relative to it, which lines sampler and GC-log data up with phases |

## From a launch to compare.py

`tools/ingest_harness.py` writes one window per measured phase into a campaign directory:
`results/<campaign>/runs/<launch-id>--<phase>/{run.json,summary.json}`. `tools/publish_campaign.py report` runs it on
the archived launches in `results/<campaign>/raw/` (their paths are then written relative to the campaign, so a
rebuild anywhere is byte-identical), then `compare.py`, then the generated report.

- `summary.json` is `frametimes.py`'s summary of that phase's frames (all of them: the settle before it is a
  separate phase), plus window issues: `functional_check_failed` or `harness_error` (errors: unusable),
  `frames_missing` (error), `client_tick_rate_low`, `jit_activity`, `autosave_in_window` (warnings), `gc_in_window`,
  `classes_loaded` (info), and frametimes' own checks (e.g. `too_few_frames`, `frame_limited`).
- `run.json` (schema 2) has the configuration, `block`, `launch_in_block`, `position_in_launch`, the `stratum`
  (level/variant/modset), `achieved_tps` and `tps_class`, `workload` (particle requests, attempts and created per
  tick **and** per real-world second, target and control live means), `per_tick` (requests per tick, frames per
  tick, wall ms per tick), `diagnostics` (GC, JIT, classes, saves, tick times, heap, CPU frame time), `gc_log`
  (pauses that ended inside the window), `system` (sampler summary: render/server thread CPU % and P-core share,
  CPU busy, clocks, package temperature, GPU utilization/clocks/power/P-states/throttle reasons), `bound` and
  `bound_reason`, `environment` (what paired runs must share; see PLAN.md §2) and `provenance` (jar and config
  hashes, versions, mods, JVM arguments, GPU, commit).
- **Reduced TPS** (PLAN.md §5): a window whose achieved client tick rate is below 19.6 is `tps_class: "reduced"`.
  It stays usable (`client_tick_rate_low` is informational). Phases are counted in ticks, so its requests per
  tick equal everyone else's while its requests per second are lower.
- **Bound label** (PLAN.md §4, `tools/system_data.py`): `gpu-bound` if mean NVIDIA utilization ≥ 95% with the
  graphics clock within 5% (max−min over median); `cpu-bound` if the render thread is ≥ 95% busy and the GPU below
  95%; else `mixed`; `unlabelled` without aligned sampler data, with fewer than 3 sampler intervals in the window, or
  when the game doesn't render on the NVIDIA GPU. Provisional thresholds (phase 4).
- `compare.py` pairs windows whose workload **per tick** and environment match, then puts each pair into a dataset:
  `primary` (both windows at 20 TPS: the primary FPS dataset) or `saturation` (either below: stress-test results;
  achieved TPS and requests per second get their own difference rows), crossed with the pair's bound label
  (`differs` when the two windows' labels differ). No mean, interval or verdict combines datasets. The report lists
  every pair (TPS, FPS, frame times, requests per tick and per second, frames per tick, reference → configuration),
  the pairs where the configuration held 20 TPS and the reference did not, the reverse, and every window with its
  full frame-time distribution.
- Functional runs are refused unless `--allow-functional` (pipeline tests only); their windows keep the label.
  `compare.py` refuses them in turn unless `--allow-functional`, and then titles the report "PIPELINE TEST, NOT A
  RESULT". At a 60 FPS cap with 5 s phases they are also unusable by frametimes' own checks.

## Methodology safeguards

| Concern | What the harness does | How it's verified |
|---|---|---|
| Particles from one phase reaching the next measurement | A settle phase follows every switch and must last at least the longest particle life + 2 ticks (target 40 → 43; end_rod up to 71 → 74); `pfbench.py` refuses a shorter plan. Emitter cursors reset at every phase start | Check: no target particle alive in any filtered window, every tick (opaque/terrain queue, or translucent minus `alive_control` for end_rod). Measured: B's end_rods were gone from settle tick 72 |
| JVM warmup | Warmup cycles every configuration of the launch twice, each in its own mode; fixed heap; GC at each settle start | JIT compile time, class loading and GC per tick; windows flagged `jit_activity` / `classes_loaded` / `gc_in_window` |
| Configuration order | `campaign` orders entries inside launches and launches inside blocks by Williams design from one seed | `compare.py` shows position counts per configuration; pairs only within a stratum and with equal launch shape |
| Provenance | Jar hashes from the launcher **and** from inside the game, mod versions, configs before and after, commit and source hashes | Checks: `mods/` held exactly the copied jars; loaded Particle Filter version matches the jar; harness version matches the source; Sodium/Sodium Extra present only in their mod set |
| Equal rendering environment (Sodium Extra comparison) | One base config for Sodium and Sodium Extra; only the target's entry differs. A rendering-environment hash leaves out exactly that entry | Equal hash across A/C/B/E Sodium launches; check that Sodium Extra kept the entry; `compare.py` excludes pairs whose environment differs |
| Correctness checks apart from measurement | Checks read the logs after the game quit; performance plans take no screenshots (Runner ignores them) | `check.json` per launch; ingest marks windows of failed launches unusable; label gates in ingest and compare |

## Design notes and deviations from HARNESS.md

- **Particle Filter comes from `mods/`, not the Gradle classpath.** The launcher copies the release jar into each
  run's `mods/` folder for B/C/D/C-miss and leaves it out for A and E. One harness build then serves every
  configuration, and the jar under test is the one players run (its SHA-256 is recorded twice). The harness reaches it
  only through `PfConfig.setMode`/`PfConfig.mode` by reflection, and changes only the mode, never the rules.
  `setMode` also saves Particle Filter's config file; that happens at the start of a settle phase, outside windows.
- **Template world:** created in game by `pfbench.py template` (`WorldOpenFlows.createFreshLevel` with the
  `minecraft:the_void` flat preset, the scenario's seed and gamerules, then time 6000 and clear weather).
  Every launch gets a fresh copy; the template is never opened. Its tree hash is in `run/template/template.json`.
- **Counters:** `created` is counted at `ParticleEngine.createParticle` RETURN (non-null result) by particle type;
  untyped particles at `ParticleEngine.add` TAIL. A cancelling HEAD injector (Particle Filter's or Sodium Extra's)
  returns before either point, so filtered spawns never count. `alive` is the size of each render type's queue, read
  at the end of `Minecraft.tick` (after `ParticleEngine.tick`). Fixed-lifetime particles are alive in **lifetime + 1**
  post-tick counts, so the steady state is `rate × 41` at a 40-tick lifetime. `alive_control` counts control
  particles constructed minus expired; it stays exact below the queue limit (an evicted particle never expires).
- **Workload:** spawning happens at the start of the client tick (`ClientTickEvent.Pre`). Phases are counted in
  ticks, so every run gets the same number of attempts. Emitters are a 16×16 grid with jitter from
  `java.util.Random(seed)` that stays inside each cell; the target and control walk the grid round-robin from
  different starting points, reset at every phase. The control spawns one block behind the target plane.
- **Particles:** `pfbench:target` (opaque, red) and `pfbench:control` (translucent, blue) reuse Minecraft's
  `generic_0` sprite at a fixed size, with no motion, physics or randomness. The untyped variant goes straight to
  `ParticleEngine.add`, so it skips vanilla's 32-block and Particles-setting checks, as mods that spawn that way do.
- **Camera:** the player position and rotation are reset every tick (spectator, HUD hidden). The camera sits at
  y 102.38 so the whole emitter plane is in view (functional-2026-11-09, frustum check).
- **No early loading window** (`earlyWindowControl = false` in the run's own `config/fml.toml`): the window
  handoff hung once when the window had been hidden. It also makes the window the same in every run.
- **No I/O while phases run**, except screenshots in functional plans. Frames and ticks go into preallocated
  arrays (room for 2,000 FPS); overflow is counted in `harness.json` and fails the check. Jar hashing happens after
  the last phase.
- **Window size** is recorded at start and end and must not change during a launch. It does vary between launches
  on the hidden workspace (2536×1390 vs 2560×1440 seen with the same procedure), so it is part of the environment
  that paired runs must share.

## Not done yet

- Configurations for rule- and zone-count scaling (Q6: C-rules-N, D-zones-N) and the B0 sanity check.

- The bound label's thresholds and the sampler's own cost are unvalidated (they need performance runs: phases 2b/4).
- Phase 2b checks (harness frame log vs MangoHud, hidden-window throttling, fullscreen on DP-1): they compare
  timings, so they wait for the power-limit confirmation.
