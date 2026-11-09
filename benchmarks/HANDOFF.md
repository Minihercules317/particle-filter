# Benchmark handoff

Read this first. It is self-contained: it doesn't assume access to the conversation that produced it.
Last updated 2026-11-09.

## Where things stand

- Repo: `~/Work/particle-filter`, branch **`benchmarks/foundation`** (made from `main` at `bcba10f`; not
  pushed, not merged). Commits so far (`git log --oneline main..`): foundation `fc2906f`, first handoff
  `858f1bf`, then the NeoForge 1.21.1 harness with its functional bring-up (2026-11-09; this file was updated
  in that commit).
- Untracked `AGENTS.md` and `.agents/` in the repo root are unrelated and pre-existing: leave them alone
  and never stage them.
- **No Particle Filter performance has been measured.** Nothing in `benchmarks/` may be quoted as a
  performance result. The harness has only passed functional checks at a 60 FPS cap.

### Done

| Item | Where | Verified by |
|---|---|---|
| Code analysis: Particle Filter's interception, rule evaluation, cost per path, Forge vs NeoForge, confounders, Sodium Extra equivalence | `IMPLEMENTATION.md` | reading the source and the decompiled Minecraft/Sodium Extra code (file:line refs inside) |
| Methodology: questions Q1-Q8, configurations, metric definitions, controls, statistics, phases, risks | `PLAN.md` | n/a (design) |
| Synthetic harness design | `HARNESS.md`, `scenarios/S1-synthetic.toml` | n/a (design; not built) |
| Analysis tools (stdlib Python): `frametimes.py`, `compare.py`, `schedule.py`, `collect_env.py`, `sample_system.py`, `make_pf_config.py`; `pilot_devclient.sh` | `tools/` | **25 unit tests pass**: `python3 -m unittest discover -s benchmarks/tools/tests -v` from the repo root |
| Benchmark Particle Filter configs (B/C/D in one file; C-miss) | `configs/` | test checks zone geometry against the emitter plane |
| Functional pipeline pilot (title screen, 60 FPS cap; **not a performance result**) | `results/pilot-2026-11-09/PILOT.md` | 3 launches × 30 s fully logged; reprocessing gives identical output; checks flag known faults |
| **pfbench harness, NeoForge 1.21.1**: separate Gradle build, mod id `pfbench`; generator (typed, untyped, block dust, vanilla variants), counters, frame log, phase runner, Particle Filter mode switching by reflection, template void world, launcher `pfbench.py` (template / run / check) with a performance-run interlock | `harness/` (usage and deviations from HARNESS.md in `harness/README.md`) | compiles; functional bring-up below |
| Functional bring-up of the harness (**functional, not a performance result**): 6 good launches covering A, B, C, D, C-miss and all four variants; every counter check passes (e.g. C/D target created = 0, A/B/C-miss created = attempts, alive = rate × 41); frames parse with `frametimes.py`; frustum checked by screenshot | `results/functional-2026-11-09/FUNCTIONAL.md` | `check.json` per launch; no timings analysed |

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
- **Harness findings (2026-11-09):** NeoForge's early loading window can fail to hand over to the game window
  when the window was moved to a hidden workspace during loading (the game then waits on an error dialog and
  ignores SIGTERM). Every run directory now turns the early window off (`config/fml.toml`). In a hidden special
  workspace the window is tiled to 2536×1390 whatever size is requested. The camera moved to y 102.38 so the
  whole emitter plane is in view; S1 options gained `renderClouds` (the old `cloudStatus` key doesn't exist in
  1.21.1), `fov`, `pauseOnLostFocus = false`, `onboardAccessibility = false`, master volume 0.
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

## Next tasks (all allowed now; none measures performance)

1. **Analysis glue** (before any campaign): split a launch's `frames.csv` by phase into per-phase
   `summary.json` files (via `tools/frametimes.py`) and write `run.json` entries with the workload counters from
   `ticks.csv` (`run.json["workload"]`), so `tools/compare.py` can read harness runs and reject pairs whose
   workload differs. Test it with unit tests on synthetic CSVs, **not** on the functional runs' timings.
2. **Launcher integration:** call `tools/collect_env.py` before/after and `tools/sample_system.py` during a launch
   (never with `--self-cpu`); optional MangoHud wrapping. Validate only that files are written.
3. **Forge 1.20.1 port** of the harness (separate build, e.g. `benchmarks/harness-forge/`, ModDevGradle legacyforge
   with reobfuscation and a mixin refmap; `getFrameTimeNs` differs, see HARNESS.md Risks). Functional checks
   as in `results/functional-2026-11-09`.
4. **Sodium / Sodium Extra compatibility check**, isolated in its own run directory. Needs the owner's answer
   on downloads (open questions).

Performance phases (PLAN.md §7, phases 2b-8) wait for the power-limit confirmation. `pfbench.py run` without
`--functional` refuses to start unless `PFBENCH_PERFORMANCE_APPROVED=1` is set; set it only after the owner confirms.

## Open questions for the owner

- When are the power-limit problems fixed? (This gates every performance phase.)
- Is there an acceptable real-world route now that world copies and live-server runs are ruled out
  (e.g. a new test world built for benchmarking in a separate, new instance)?
- Allowed to download Sodium + Sodium Extra jars into an isolated benchmark run directory for the compatibility check?
  (Investigation is approved; this confirms that downloads count as isolated.)

## Read these, in order

1. `benchmarks/HANDOFF.md` (this file)
2. `benchmarks/harness/README.md` (how the harness works, its outputs, deviations from the design, what's missing)
3. `benchmarks/results/functional-2026-11-09/FUNCTIONAL.md` (what was checked and the one problem found)
4. `benchmarks/HARNESS.md` and `benchmarks/scenarios/S1-synthetic.toml` (design, parameters)
5. `benchmarks/PLAN.md` §2, §4 and §7 (configurations, metrics and controls, phases with gates)
6. Repo `CLAUDE.md` (build commands, Stonecutter layout, commit rules: repo-local noreply identity, AI disclosure)

Don't redo: code analysis, tool writing, the pipeline pilot, MangoHud probing, or the harness bring-up. They
are finished and recorded. Don't rerun the pilot, and don't analyse the functional runs' frame logs; that
would be measurement.

## Prompt for the next session

```
Continue the Particle Filter benchmark project in ~/Work/particle-filter on branch benchmarks/foundation.
Read benchmarks/HANDOFF.md first and follow its owner decisions exactly, then read the files it lists.
The NeoForge 1.21.1 pfbench harness is built and functionally validated. Your task: the next tasks in
HANDOFF.md, starting with the analysis glue (per-phase summaries and run.json from harness outputs, with unit
tests on synthetic data). Functional validation only, labelled as functional. Do not run or report any
performance measurements, and don't analyse the timings of existing runs: the laptop's power-limit problems
are unresolved until I say otherwise. Don't modify Particle Filter's production code, existing Minecraft
installations or worlds, CPU affinity, or power/GPU settings. Leave AGENTS.md and .agents/ alone.
Commit finished work to benchmarks/foundation (don't push) and update benchmarks/HANDOFF.md before stopping.
```
