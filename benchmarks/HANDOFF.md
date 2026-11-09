# Benchmark handoff

Read this first. It is self-contained: it doesn't assume access to the conversation that produced it.
Last updated 2026-11-09 (third and fourth sessions that day: launcher system data, reduced-TPS split, Forge 1.20.1
harness; the fourth session wrote the Forge functional record and committed the port).

**Before launching anything, check no other Claude session is working here** (`pgrep -af 'pfbench.py|devlaunch.Main'`,
`git status`): on 2026-11-09 two sessions ran in this checkout at once; `pfbench.py` refuses to start a second client,
but edits and commits would collide.

## Where things stand

- Repo: `~/Work/particle-filter`, branch **`benchmarks/foundation`** (made from `main` at `bcba10f`; not
  pushed, not merged). Commits so far (`git log --oneline main..`): foundation `fc2906f`, first handoff
  `858f1bf`, NeoForge 1.21.1 harness `85cb36f`, analysis pipeline + methodology safeguards + Sodium mod set
  `fc67e49`, reduced-TPS policy handoff `615845d`, launcher system data + reduced-TPS implementation `bee31ca`, then
  the Forge 1.20.1 harness port and this handoff (see `git log --oneline main..`).
- Untracked `AGENTS.md` and `.agents/` in the repo root are unrelated and pre-existing: leave them alone
  and never stage them.
- **No Particle Filter performance has been measured.** Nothing in `benchmarks/` may be quoted as a
  performance result. The harness and pipeline have only passed functional checks (60 FPS cap, 5 s phases) and
  unit tests on synthetic data (78 tests).
- **Actual performance testing remains blocked until the owner confirms the laptop's CPU/GPU power-limit problems
  are resolved.** Not confirmed as of 2026-11-09.

### Done

| Item | Where | Verified by |
|---|---|---|
| Code analysis: Particle Filter's interception, rule evaluation, cost per path, Forge vs NeoForge, confounders, Sodium Extra equivalence | `IMPLEMENTATION.md` | reading the source and the decompiled Minecraft/Sodium Extra code (file:line refs inside) |
| Methodology: questions Q1-Q8, configurations, metric definitions, controls, statistics, phases, risks | `PLAN.md` | n/a (design) |
| Synthetic harness design | `HARNESS.md`, `scenarios/S1-synthetic.toml` | n/a (design) |
| Analysis tools (stdlib Python): `frametimes.py`, `compare.py`, `schedule.py`, `collect_env.py`, `sample_system.py`, `make_pf_config.py`, `ingest_harness.py`; `pilot_devclient.sh` | `tools/` | **78 unit tests pass** (all suites): `python3 -m unittest discover -s benchmarks/tools/tests -v` from the repo root |
| Functional pipeline pilot (title screen; **not a performance result**) | `results/pilot-2026-11-09/PILOT.md` | 3 launches × 30 s fully logged |
| **pfbench harness 0.2.0, NeoForge 1.21.1** (separate Gradle build, mod id `pfbench`): generator (4 variants), counters, frame log, phase runner, mode switching by reflection, template world, launcher `pfbench.py` (template / run / campaign / check) with the performance interlock | `harness/` (`harness/README.md`) | functional bring-ups below |
| First functional bring-up (harness 0.1.0) | `results/functional-2026-11-09/FUNCTIONAL.md` | `check.json` per launch |
| **Analysis pipeline**: `ingest_harness.py` (launch → per-window `run.json` + `summary.json` with workload, diagnostics, environment, provenance); `compare.py` strata, environment pairing, order-balance table, label gate | `tools/`, `templates/run.json` | unit tests on synthetic harness runs (known speed-up found exactly; exclusions for environment, launch shape, failed checks); run end to end on functional runs (windows unusable by design) |
| **Methodology safeguards** (owner's five checks): carry-over, warmup and order, provenance, equal rendering environment, checks apart from measurement | `harness/README.md` "Methodology safeguards"; PLAN.md §2, §4, §5, §8 | `results/functional-2026-11-09-pipeline/FUNCTIONAL.md` |
| **Sodium 0.8.13 + Sodium Extra 0.9.5** downloaded (owner-approved) into `harness/run/downloads/`, compatibility verified, `--modset sodium`, configuration E | `results/functional-2026-11-09-pipeline/SODIUM.md`, `configs/sodium/` | functional checks pass for A, C/B and E (typed and vanilla) with Sodium |
| **Launcher system data** (task 3 of the previous handoff): `collect_env.py` before/after every launch, `sample_system.py` on the game JVM (never `--self-cpu`), clock alignment (`harness.json["clock"]`, sampler `mono_ns`), GC-log parser, CPU-/GPU-bound label, power state in the pairing environment, optional `--mangohud` | `harness/pfbench.py`, `tools/system_data.py`, `tools/sample_system.py` | unit tests (`test_system_data.py`); `results/functional-2026-11-09-sysdata/FUNCTIONAL.md` (files written and aligned; no timings read) |
| **Reduced-TPS policy implemented** (task 1): `tps_class` (< 19.6 = reduced), achieved TPS, requests per tick and per second, per-tick view; `compare.py` pairs on per-tick workload, keeps primary/saturation datasets × bound label apart, lists every pair and the held/lost-20-TPS pairs | `tools/ingest_harness.py` (run.json schema 2), `tools/compare.py`, PLAN.md §5 | unit tests on synthetic runs with slowed ticks (`ReducedTpsTests`, `CompareTests`) |
| **Forge 1.20.1 harness** (task 2): `harness-forge/` (legacyforge, Java 17, refmap) compiling the shared `harness/src/main`; per-loader `Platform`; `pfbench.py --loader forge` (own template, named-mappings Particle Filter jar, vanilla modset only) | `harness-forge/`, `harness/README.md` "Forge 1.20.1" | `results/functional-2026-11-09-forge/FUNCTIONAL.md`; unit tests (`LoaderTests`) |

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

- Thresholds are provisional until the variance study (phase 4, blocked): `jit_activity` (5 ms of JIT per second of
  window), tick-rate tolerance (2%, which also sets the 19.6 TPS reduced-TPS line), frame coverage (98%), the bound
  label (95% GPU utilization, 95% render thread, 5% clock spread, 3 samples), and the warmup/settle/measure lengths in S1.
- The sampler's own cost (1 s interval, one Python process + `nvidia-smi -lms`) is unmeasured; it runs in every
  configuration equally. MangoHud under the launcher writes its frame CSV but no `_summary.csv` (the game quits first).
- Both harnesses measure the ModDevGradle dev runtime, not a production install. On Forge 1.20.1 this means Particle
  Filter's named-mappings jar (same Gradle run as the reobfuscated release jar, both hashes recorded), not the
  release jar itself.
- Window size differs between launches on the hidden workspace (2536×1390 vs 2560×1440). Compare excludes
  mismatched pairs; performance runs are meant to be fullscreen on DP-1, which is still unverified (phase 2b, blocked).
- Phase 2b checks (harness frame log vs MangoHud, hidden vs visible window, fullscreen on DP-1) compare timings and
  stay blocked.

## Next tasks (for the next session; none measures performance)

The infrastructure tasks of the previous handoff are done. What remains is either blocked on the owner or small:

1. **Wait for the owner's power-limit confirmation.** Then, in order (PLAN.md §7): phase 2b (harness frame log vs
   MangoHud, hidden vs visible window, fullscreen on DP-1; the sampler's own cost), phase 3 calibration, phase 4
   variance study (fixes the provisional thresholds above and the block counts), then the campaigns.
   `PFBENCH_PERFORMANCE_APPROVED=1` only after that confirmation.
2. Optional, not blocked: a performance-plan dry run per loader (`campaign ... --dry-run`) to review the full launch
   order and duration with the owner; reporting templates (`templates/report.md`) for the two datasets.
3. Optional: a production-Forge route (installed Forge 1.20.1 in an isolated directory, reobfuscated harness) if the
   owner wants the release jar itself measured on Forge. Needs the owner's approval (new install).

Performance phases (PLAN.md §7, phases 2b-8) wait for the power-limit confirmation. `pfbench.py run`/`campaign`
without `--functional` refuse to start unless `PFBENCH_PERFORMANCE_APPROVED=1` is set; set it only after the owner confirms.

## Open questions for the owner

- When are the power-limit problems fixed? (This gates every performance phase.)
- Is there an acceptable real-world route now that world copies and live-server runs are ruled out
  (e.g. a new test world built for benchmarking in a separate, new instance)?

## Read these, in order

1. `benchmarks/HANDOFF.md` (this file)
2. `benchmarks/harness/README.md` (how the harness and pipeline work, the safeguards table, deviations, what's missing)
3. `benchmarks/results/functional-2026-11-09-sysdata/FUNCTIONAL.md`, `functional-2026-11-09-forge/FUNCTIONAL.md`,
   `functional-2026-11-09-pipeline/FUNCTIONAL.md` and `SODIUM.md`
4. `benchmarks/results/functional-2026-11-09/FUNCTIONAL.md` (first bring-up)
5. `benchmarks/HARNESS.md` and `benchmarks/scenarios/S1-synthetic.toml` (design, parameters)
6. `benchmarks/PLAN.md` §2, §4, §5, §7 and §8 (configurations, controls, statistics, phases, risks)
7. Repo `CLAUDE.md` (build commands, Stonecutter layout, commit rules: repo-local noreply identity, AI disclosure)

Don't redo: code analysis, tool writing, the pipeline pilot, MangoHud probing, the harness bring-up, the Sodium
compatibility check, the Forge port and its functional validation. They are finished and recorded. Don't analyse the functional runs' frame logs or JIT/GC numbers;
that would be measurement.

## Prompt for the next session

```
Continue the Particle Filter benchmark project in ~/Work/particle-filter on branch benchmarks/foundation.
Read benchmarks/HANDOFF.md first and follow its owner decisions exactly, then read the files it lists.
The pfbench harness (NeoForge 1.21.1 and Forge 1.20.1), the launcher's system data (environment snapshots, sampler,
GC log, bound label), the analysis pipeline with the reduced-TPS split (ingest_harness.py -> compare.py) and the
Sodium mod set are built and functionally validated. [If I have confirmed the power-limit fix: start phase 2b as
PLAN.md section 7 describes. Otherwise: only the unblocked items under "Next tasks".] Functional validation only,
labelled as functional, unless I have confirmed the fix. Don't modify Particle Filter's production code, existing
Minecraft installations or worlds, CPU affinity, or power/GPU settings. Leave AGENTS.md and .agents/ alone. Commit
finished work to benchmarks/foundation (don't push) and update benchmarks/HANDOFF.md before stopping.
```
