# Benchmark handoff

Read this first. It is self-contained: it doesn't assume access to the conversation that produced it.
Last updated 2026-11-09 (end of the second session that day; documentation-only handoff commit).

## Where things stand

- Repo: `~/Work/particle-filter`, branch **`benchmarks/foundation`** (made from `main` at `bcba10f`; not
  pushed, not merged). Commits so far (`git log --oneline main..`): foundation `fc2906f`, first handoff
  `858f1bf`, NeoForge 1.21.1 harness `85cb36f`, analysis pipeline + methodology safeguards + Sodium mod set
  `fc67e49`, then this handoff with the owner's reduced-TPS policy (documentation only).
- Untracked `AGENTS.md` and `.agents/` in the repo root are unrelated and pre-existing: leave them alone
  and never stage them.
- **No Particle Filter performance has been measured.** Nothing in `benchmarks/` may be quoted as a
  performance result. The harness and pipeline have only passed functional checks (60 FPS cap, 5 s phases) and
  unit tests on synthetic data.
- **Actual performance testing remains blocked until the owner confirms the laptop's CPU/GPU power-limit problems
  are resolved.** Not confirmed as of 2026-11-09.

### Done

| Item | Where | Verified by |
|---|---|---|
| Code analysis: Particle Filter's interception, rule evaluation, cost per path, Forge vs NeoForge, confounders, Sodium Extra equivalence | `IMPLEMENTATION.md` | reading the source and the decompiled Minecraft/Sodium Extra code (file:line refs inside) |
| Methodology: questions Q1-Q8, configurations, metric definitions, controls, statistics, phases, risks | `PLAN.md` | n/a (design) |
| Synthetic harness design | `HARNESS.md`, `scenarios/S1-synthetic.toml` | n/a (design) |
| Analysis tools (stdlib Python): `frametimes.py`, `compare.py`, `schedule.py`, `collect_env.py`, `sample_system.py`, `make_pf_config.py`, `ingest_harness.py`; `pilot_devclient.sh` | `tools/` | **52 unit tests pass**: `python3 -m unittest discover -s benchmarks/tools/tests -v` from the repo root |
| Functional pipeline pilot (title screen; **not a performance result**) | `results/pilot-2026-11-09/PILOT.md` | 3 launches × 30 s fully logged |
| **pfbench harness 0.2.0, NeoForge 1.21.1** (separate Gradle build, mod id `pfbench`): generator (4 variants), counters, frame log, phase runner, mode switching by reflection, template world, launcher `pfbench.py` (template / run / campaign / check) with the performance interlock | `harness/` (`harness/README.md`) | functional bring-ups below |
| First functional bring-up (harness 0.1.0) | `results/functional-2026-11-09/FUNCTIONAL.md` | `check.json` per launch |
| **Analysis pipeline**: `ingest_harness.py` (launch → per-window `run.json` + `summary.json` with workload, diagnostics, environment, provenance); `compare.py` strata, environment pairing, order-balance table, label gate | `tools/`, `templates/run.json` | unit tests on synthetic harness runs (known speed-up found exactly; exclusions for environment, launch shape, failed checks); run end to end on functional runs (windows unusable by design) |
| **Methodology safeguards** (owner's five checks): carry-over, warmup and order, provenance, equal rendering environment, checks apart from measurement | `harness/README.md` "Methodology safeguards"; PLAN.md §2, §4, §5, §8 | `results/functional-2026-11-09-pipeline/FUNCTIONAL.md` |
| **Sodium 0.8.13 + Sodium Extra 0.9.5** downloaded (owner-approved) into `harness/run/downloads/`, compatibility verified, `--modset sodium`, configuration E | `results/functional-2026-11-09-pipeline/SODIUM.md`, `configs/sodium/` | functional checks pass for A, C/B and E (typed and vanilla) with Sodium |

### Key findings to carry forward

- **Interception** (`src/main/java/particlefilter/mixin/ParticleEngineMixin.java`): typed particles are rejected
  at `createParticle` HEAD, before construction; untyped at `add` HEAD; block break/mining at `destroy`/`crack`
  HEAD. `PfConfig.setMode` saves the config file (I/O) and rebuilds the rule snapshot: the harness does it at the
  start of a settle phase.
- **C-miss** is the likeliest way Particle Filter could cost performance and **must stay an explicit scenario**.
- **Vanilla limits:** spawns > 32 blocks away or under Particles ≠ All never reach the mod; 16,384 live particles
  per render type; attempts, created and alive are counted separately.
- **Sodium Extra 0.9.5** rejects at `createParticle` HEAD by type id; its map is saved under the JSON key `"other"`
  (a wrong key is silently ignored). Its hook runs in every configuration that has it. Sodium's jar nests four
  Fabric API modules (jar-in-jar).
- **Methodological problems found and fixed this session** (details in the functional record):
  1. The functional settle (3 s = 60 ticks) was shorter than end_rod's longest life (72 post-tick counts). Measured:
     B's end_rods survived until settle tick 71. Now every settle must be ≥ longest life + 2 ticks (`pfbench.py`
     refuses less); functional settle is 4 s. The 15 s performance settle was always long enough.
  2. The warmup ran only the first configuration's mode, so the others' code paths were first compiled during their
     own settle. Now the warmup cycles all configurations of a launch twice; JIT compile time is logged per tick and
     flags windows.
  3. B measured alongside C/D could have been paired with A measured alone (different warmup and switching).
     Launch shape is now part of the environment `compare.py` matches; Q1 runs B alone (`campaign --configs A,B`).
  4. `compare.py` would have averaged windows of different load levels in one block. It now compares within strata
     (level/variant/modset) only.
  5. The in-game jar hash missed Sodium's nested jars; the harness now hashes the `mods/` folder and lists nested files.
  6. Emitter cursors carried over between phases (different spawn order per phase); now reset per phase.
  7. Window size varies between launches on the hidden workspace (2536×1390 vs 2560×1440); it is part of the
     environment, and a check fails if it changes within a launch.
- **Machine quirks:** dev game JVM is found by `net.neoforged.devlaunch.Main`. `pgrep -f` matches any shell whose
  command text contains the pattern. Hyprland 0.56 dispatch is Lua:
  `hl.dsp.window.move({ workspace = "special:pfbench", window = "pid:N", follow = false })`. NeoForge's early
  loading window is off in every run directory (its handoff hung once on the hidden workspace). DP-1 is on the
  NVIDIA GPU, eDP-2 on the Intel iGPU. VRR state changes at runtime. MangoHud 0.8.4 logs nothing with `no_display`.

## Owner decisions (binding)

**Reduced-TPS policy (decided 2026-11-09; recorded in PLAN.md §5 "Reduced-TPS runs"; not implemented yet)**
- Retain valid runs where either configuration falls below 20 TPS; don't exclude them.
- Normal 20-TPS comparisons are the **primary FPS dataset**; reduced-TPS comparisons are classified separately as
  **saturation / stress-test results**. Never combine saturated and non-saturated measurements in one aggregate.
- Report achieved TPS, FPS, frame-time distributions, particle requests per tick and particle requests per
  real-world second.
- Keep per-tick-normalized analysis where meaningful, but never as a substitute for real-time measurements.
- Explicitly identify cases where filtering maintains 20 TPS while the baseline cannot.
- Preserve raw measurements, including unfavourable results.

**Approved**
- Commit benchmark work to `benchmarks/foundation` (stage only `benchmarks/` files).
- The isolated NeoForge 1.21.1 synthetic harness (`benchmarks/harness/`, its own Gradle build, its own mod id).
- **Downloading compatible Sodium and Sodium Extra releases into an isolated benchmark environment** (2026-11-09;
  done: `benchmarks/harness/run/downloads/`).
- Limited **functional** validation of tools and harness, clearly labelled "functional, not a performance result".
- Continue building and testing infrastructure (2026-11-09: analysis pipeline first, then the Forge 1.20.1 port).

**Prohibited**
- **No performance benchmarks, calibration sweeps, variance studies or performance results until the owner
  explicitly confirms the laptop's CPU/GPU power-limit problems are resolved.** As of 2026-11-09 the owner has
  **not** confirmed this. It includes timing comparisons that look like validation (harness vs MangoHud
  agreement, hidden-window throttling).
- Modifying Particle Filter's production code (`src/`, build files for the release jars).
- Modifying existing Minecraft installations or worlds (CurseForge instances, their configs, saves).
- Copying ATM10 server worlds or benchmarking against the live server (real-world scenario R1 is on hold).
- Changing CPU affinity (`taskset`, pinning, `sample_system.py --self-cpu`), firmware, GPU settings, or system power configuration.
- Pushing, merging, or touching `main`. Installing system packages without asking.

## Unresolved issues (known, not yet fixed)

- **Reduced-TPS handling is documented but not implemented.** Today `compare.py` rejects pairs whose
  `spawn_attempts_per_s` differ by > 2% (so a pair where one side drops below 20 TPS is excluded), and
  `ingest_harness.py` only adds a `client_tick_rate_low` warning. Both contradict the policy above until task 1 is done.
- `run.json["bound"]` is always `unlabelled`: the system sampler isn't integrated, so windows can't yet be labelled
  CPU-/GPU-bound (PLAN.md §4; results must never pool the two).
- Thresholds are provisional until the variance study (phase 4, blocked): `jit_activity` (5 ms of JIT per second of
  window), tick-rate tolerance (2%), frame coverage (98%), and the warmup/settle/measure lengths in S1.
- Window size differs between launches on the hidden workspace (2536×1390 vs 2560×1440). Compare excludes
  mismatched pairs; performance runs are meant to be fullscreen on DP-1, which is still unverified (phase 2b, blocked).
- Phase 2b checks (harness frame log vs MangoHud, hidden vs visible window, fullscreen on DP-1) compare timings and
  stay blocked.
- No GC-log parser yet (per-window GC comes from MXBeans). Forge 1.20.1 has no harness yet.

## Next tasks (implementation, for the next session; none measures performance)

1. **Implement the reduced-TPS policy** (PLAN.md §5, §2):
   - `ingest_harness.py`: add `tps_class` (`"20tps"` or `"reduced"`, proposed threshold 19.6 achieved TPS) and
     achieved TPS to every window's `run.json`; keep both per-tick and per-second request rates (already in
     `workload`); make `client_tick_rate_low` informational, not a reason to exclude.
   - `compare.py`: check workload equality **per tick** (`attempts_per_tick`, `control_particles_live_mean`), not
     per second; put each pair into the primary dataset (both windows 20 TPS) or the saturation dataset (either
     window reduced) and never aggregate across them; report achieved TPS for both sides, FPS, frame-time
     distribution, requests per tick and per second; add a table of pairs where the filtered configuration held
     20 TPS and the reference did not; keep every run (including unfavourable ones) in the run table.
   - Unit tests on synthetic runs (`tools/tests/test_pipeline.py` has `make_run(..., slow_ticks=...)` for this).
   - Update `harness/README.md` ("From a launch to compare.py") and `templates/run.json`.
2. **Forge 1.20.1 port** of the harness (separate build, e.g. `benchmarks/harness-forge/`, ModDevGradle
   legacyforge with reobfuscation and a mixin refmap; Java 17; `getFrameTimeNs` differs, see HARNESS.md Risks;
   `LevelEvent.Save` and `ClientTickEvent` live in Forge's packages). Reuse `pfbench.py` by making the loader a
   parameter (game pattern, data version, Gradle project, Particle Filter jar name). Functional checks as in
   `results/functional-2026-11-09-pipeline`.
3. **Launcher integration:** `tools/collect_env.py` before/after and `tools/sample_system.py` during a launch
   (never `--self-cpu`), optional MangoHud wrapping, the CPU-/GPU-bound label in `run.json["bound"]`, a GC-log
   parser. Validate only that files are written.

Performance phases (PLAN.md §7, phases 2b-8) wait for the power-limit confirmation. `pfbench.py run`/`campaign`
without `--functional` refuse to start unless `PFBENCH_PERFORMANCE_APPROVED=1` is set; set it only after the owner confirms.

## Open questions for the owner

- When are the power-limit problems fixed? (This gates every performance phase.)
- Is there an acceptable real-world route now that world copies and live-server runs are ruled out
  (e.g. a new test world built for benchmarking in a separate, new instance)?

## Read these, in order

1. `benchmarks/HANDOFF.md` (this file)
2. `benchmarks/harness/README.md` (how the harness and pipeline work, the safeguards table, deviations, what's missing)
3. `benchmarks/results/functional-2026-11-09-pipeline/FUNCTIONAL.md` and `SODIUM.md`
4. `benchmarks/results/functional-2026-11-09/FUNCTIONAL.md` (first bring-up)
5. `benchmarks/HARNESS.md` and `benchmarks/scenarios/S1-synthetic.toml` (design, parameters)
6. `benchmarks/PLAN.md` §2, §4, §5, §7 and §8 (configurations, controls, statistics, phases, risks)
7. Repo `CLAUDE.md` (build commands, Stonecutter layout, commit rules: repo-local noreply identity, AI disclosure)

Don't redo: code analysis, tool writing, the pipeline pilot, MangoHud probing, the harness bring-up, the Sodium
compatibility check. They are finished and recorded. Don't analyse the functional runs' frame logs or JIT/GC numbers;
that would be measurement.

## Prompt for the next session

```
Continue the Particle Filter benchmark project in ~/Work/particle-filter on branch benchmarks/foundation.
Read benchmarks/HANDOFF.md first and follow its owner decisions exactly, then read the files it lists.
The NeoForge 1.21.1 pfbench harness, the analysis pipeline (ingest_harness.py -> compare.py) and the Sodium mod set
are built and functionally validated. Your task: the next tasks in HANDOFF.md, starting with implementing the
reduced-TPS policy (PLAN.md section 5) in ingest_harness.py and compare.py with unit tests on synthetic data, then
the Forge 1.20.1 port of the harness. Functional validation only, labelled as functional. Do not run or report any performance
measurements, and don't analyse the timings of existing runs: the laptop's power-limit problems are unresolved
until I say otherwise. Don't modify Particle Filter's production code, existing Minecraft installations or worlds,
CPU affinity, or power/GPU settings. Leave AGENTS.md and .agents/ alone. Commit finished work to
benchmarks/foundation (don't push) and update benchmarks/HANDOFF.md before stopping.
```
