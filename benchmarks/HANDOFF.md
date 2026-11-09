# Benchmark handoff

Read this first. It is self-contained: it doesn't assume access to the conversation that produced it.
Last updated 2026-11-09.

## Where things stand

- Repo: `~/Work/particle-filter`, branch **`benchmarks/foundation`** (made from `main` at `bcba10f`; not
  pushed, not merged). Foundation commit: **`fc2906f`** ("Add benchmark foundation: plan, analysis tools
  and pipeline pilot"); this handoff file is in the commit after it (`git log --oneline main..`).
- Untracked `AGENTS.md` and `.agents/` in the repo root are unrelated and pre-existing: leave them alone
  and never stage them.
- **No Particle Filter performance has been measured.** Nothing in `benchmarks/` may be quoted as a
  performance result.

### Done

| Item | Where | Verified by |
|---|---|---|
| Code analysis: Particle Filter's interception, rule evaluation, cost per path, Forge vs NeoForge, confounders, Sodium Extra equivalence | `IMPLEMENTATION.md` | reading the source and the decompiled Minecraft/Sodium Extra code (file:line refs inside) |
| Methodology: questions Q1-Q8, configurations, metric definitions, controls, statistics, phases, risks | `PLAN.md` | n/a (design) |
| Synthetic harness design | `HARNESS.md`, `scenarios/S1-synthetic.toml` | n/a (design; not built) |
| Analysis tools (stdlib Python): `frametimes.py`, `compare.py`, `schedule.py`, `collect_env.py`, `sample_system.py`, `make_pf_config.py`; `pilot_devclient.sh` | `tools/` | **25 unit tests pass**: `python3 -m unittest discover -s benchmarks/tools/tests -v` from the repo root |
| Benchmark Particle Filter configs (B/C/D in one file; C-miss) | `configs/` | test checks zone geometry against the emitter plane |
| Functional pipeline pilot (title screen, 60 FPS cap; **not a performance result**) | `results/pilot-2026-11-09/PILOT.md` | 3 launches × 30 s fully logged; reprocessing gives identical output; checks flag known faults |

### Key findings to carry forward

- **Interception** (`src/main/java/particlefilter/mixin/ParticleEngineMixin.java`): typed particles are rejected
  at `createParticle` HEAD, before construction; untyped particles at `add` HEAD, after the mod built them;
  block break/mining at `destroy`/`crack` HEAD. Rules live in a volatile immutable snapshot
  (`filter/FilterRules.java`). Zones are a linear loop.
- **C-miss** (rules exist, none match) is the likeliest way Particle Filter could cost performance: every typed
  attempt does a registry lookup and allocates a new `String` (`ResourceLocation.toString()`), then a set lookup,
  and saves nothing. It **must stay an explicit scenario** (owner decision).
- **Vanilla limits:** spawns > 32 blocks from the camera or under Particles ≠ All never reach the mod;
  16,384 live particles per render type, older ones evicted; `particlesToAdd` is unbounded. Attempts,
  created and alive must be counted separately.
- **Sodium Extra 0.9.5** also rejects at `createParticle` HEAD by type id, so the comparison is fair only for
  global typed suppression. Its block-break switches are global, and it can't do zones or untyped particles.
  It needs Sodium (0.8.x); the only local copy is in the "All the Mods 10 - ATM10" instance, which must not be modified.
- **Pilot instrumentation issues:** MangoHud 0.8.4 logs nothing with `no_display` (use
  `fps_only,background_alpha=0`). It creates the CSV at log start and `*_summary.csv` at log end. Its GPU columns are zero
  on this laptop (use `nvidia-smi`). The game window size varied between launches (tiled vs fullscreen;
  follows the Hyprland layout). The render thread ran on E-cores ~80% of the time at light load.
- **Machine quirks:** dev game JVM is found by `net.neoforged.devlaunch.Main`. `pgrep -f` matches any shell whose
  command text contains the pattern (run probes from script files). Hyprland 0.56 dispatch is Lua:
  `hl.dsp.window.move({ workspace = "special:pfbench", window = "pid:N", follow = false })`. DP-1 is on the
  NVIDIA GPU, eDP-2 on the Intel iGPU. VRR state changes at runtime.

## Owner decisions (binding)

**Approved**
- Commit benchmark work to `benchmarks/foundation` (stage only `benchmarks/` files).
- Implement the isolated NeoForge 1.21.1 synthetic harness (`benchmarks/harness/`, its own Gradle build, its own mod id).
- Investigate Sodium Extra compatibility, kept isolated (its own run directory; nothing in existing instances).
- Limited **functional** validation of tools and harness, clearly labelled "functional, not a performance result".

**Prohibited**
- **No performance benchmarks, calibration sweeps, variance studies or performance results until the owner
  explicitly confirms the laptop's CPU/GPU power-limit problems are resolved.** This includes timing
  comparisons that look like validation (harness vs MangoHud agreement, hidden-window throttling).
- Modifying Particle Filter's production code (`src/`, build files for the release jars).
- Modifying existing Minecraft installations or worlds (CurseForge instances, their configs, saves).
- Copying ATM10 server worlds or benchmarking against the live server (real-world scenario R1 is on hold).
- Changing CPU affinity (`taskset`, pinning, `sample_system.py --self-cpu`), firmware, GPU settings, or system power configuration.
- Pushing, merging, or touching `main`. Installing system packages without asking.

## Next task: build the pfbench harness (functional only)

Implement `HARNESS.md` for NeoForge 1.21.1 as a separate Gradle build in `benchmarks/harness/`:

1. Gradle build mirroring `build.neoforge.gradle.kts` (ModDevGradle, NeoForge 21.1.250, Java 21 via mise),
   **not** listed in the root `settings.gradle.kts`, so release builds and CI never include it.
   Mod id `pfbench`. Particle Filter goes on the dev runtime classpath as an optional dependency for configs B/C/D.
2. Generator (fixed seed, emitter grid, `pfbench:target` opaque / `pfbench:control` translucent with fixed
   lifetimes, untyped and block-dust variants), counters (attempts, created at `createParticle` RETURN, alive
   per render type), preallocated frame log (`frames.csv`: `t_ns,frame_ns,cpu_frame_ns,phase`), phase
   script from `scenarios/S1-synthetic.toml`, Particle Filter mode switching via reflection
   (`PfConfig.setMode`), `--quickPlaySingleplayer` launch, fresh copy of a template void world per launch.
3. Functional checks only: it builds; launches; loads the world; with Particle Filter absent created == attempts;
   in mode EVERYWHERE target created == 0 and control unchanged; outputs parse with `tools/frametimes.py`
   (the `frame_ns` column is supported). Keep runs short, and **don't interpret or report timings**.
4. Commit to `benchmarks/foundation` with a descriptive message, and update this file.

Then: Forge 1.20.1 port of the harness; isolated Sodium/Sodium Extra compatibility check. Performance phases
(PLAN.md §7, phases 2b-8) wait for the power-limit confirmation.

## Open questions for the owner

- When are the power-limit problems fixed? (This gates every performance phase.)
- Is there an acceptable real-world route now that world copies and live-server runs are ruled out
  (e.g. a new test world built for benchmarking in a separate, new instance)?
- Allowed to download Sodium + Sodium Extra jars into an isolated benchmark run directory for the compatibility check?
  (Investigation is approved; this confirms that downloads count as isolated.)

## Read these, in order

1. `benchmarks/HANDOFF.md` (this file)
2. `benchmarks/HARNESS.md` (what to build)
3. `benchmarks/scenarios/S1-synthetic.toml` (parameters the harness reads)
4. `benchmarks/PLAN.md` §2, §4 and §7 (configurations, metrics and controls, phases with gates)
5. `benchmarks/IMPLEMENTATION.md` §1-2 (which hooks the counters must sit next to)
6. Repo `CLAUDE.md` (build commands, Stonecutter layout, commit rules: repo-local noreply identity, AI disclosure)

Don't redo: code analysis, tool writing, the pipeline pilot, or MangoHud probing. They are finished and
recorded. Don't rerun the pilot; that would be measurement.

## Prompt for the next session

```
Continue the Particle Filter benchmark project in ~/Work/particle-filter on branch benchmarks/foundation.
Read benchmarks/HANDOFF.md first and follow its owner decisions exactly, then read the files it lists.
Your task: implement the isolated NeoForge 1.21.1 pfbench synthetic harness described in
benchmarks/HARNESS.md as a separate Gradle build under benchmarks/harness/ (not part of the release
build), and do only functional validation (it builds, launches, spawns, counters are consistent, outputs
parse), labelled as functional. Do not run or report any performance measurements: the laptop's power-limit
problems are unresolved until I say otherwise. Don't modify Particle Filter's production code, existing
Minecraft installations or worlds, CPU affinity, or power/GPU settings. Leave AGENTS.md and .agents/ alone.
Commit finished work to benchmarks/foundation (don't push) and update benchmarks/HANDOFF.md before stopping.
```
