# Benchmark handoff

Read this first. It is self-contained: it doesn't assume access to the conversation that produced it.

> **Update 2026-11-09, sixth/seventh session (read first).** The owner confirmed the CPU/GPU power-limit fix and
> lifted the measurement gate, subject to hardware validation. Validation, calibration, the variance study and the
> harness-vs-MangoHud check are done (all EXPLORATORY, configurations A and B only):
> **[results/calibration-2026-11-09/CALIBRATION.md](results/calibration-2026-11-09/CALIBRATION.md)**.
> - Hardware passes for this workload (CPU 145 W sustained; GPU capped at 70 W with Dynamic Boost off, game ≤ 58 W).
> - Methodology frozen in **scenario S1 version 2** and PLAN.md: hidden window pinned to eDP-2 (`[display]`, enforced
>   for performance runs), moderate level 100, GPU-bound threshold 90%, Student-t verdicts, 12 blocks within-launch /
>   16 blocks install-level, **campaigns run unattended** (desktop use shifted FPS ~10%).
> - Tooling fixes: render-thread identification (CPU-bound labels were impossible), `--monitor`, window state for
>   visible runs, sampler throttle counters + GPU power limit, racy coverage check, monitor-size check.
> - **Next:** (1) the owner reviews and authorizes publishing; (2) push the scrubbed branch (see "Publication" below)
>   with the pre-registrations in `results/pr-*/PREREGISTRATION.txt`; (3) only then run the official campaigns,
>   unattended, exactly as pre-registered (`pfbench.py campaign` with the recorded arguments).
> - Owner's scope rule (2026-11-09): keep environment checks brief, read-only and proportional; don't disable, kill or
>   reconfigure unrelated processes (e.g. the animated wallpaper); record confounders and proceed; stop only for issues
>   that would materially invalidate results. The gate text further down is historical.
>
> **Publication:** private server addresses were in the branch's first commit (PLAN.md, scenarios/R1-atm10-sky.md). The branch
> history was rewritten locally to remove them (old→new hashes: [HISTORY-REWRITE.md](HISTORY-REWRITE.md); this file's hashes are the new ones); the unscrubbed history is kept on the local-only branch
> `benchmarks/foundation-prescrub` (never push it; exploratory launches' provenance points at its commits). Pilot logs
> still contain the local home path `/home/mini` (low risk, left as raw data).

Earlier update: 2026-11-09 (fifth session that day: reproducibility and reporting readiness, the last preparation
phase before the hardware power-limit validation. Earlier sessions: launcher system data, reduced-TPS split, Forge
1.20.1 harness and its functional record).

**Before launching anything, check no other Claude session is working here** (`pgrep -af 'pfbench.py|devlaunch.Main'`,
`git status`): on 2026-11-09 two sessions ran in this checkout at once; `pfbench.py` refuses to start a second client,
but edits and commits would collide.

## Where things stand

- Repo: `~/Work/particle-filter`, branch **`benchmarks/foundation`** (made from `main` at `bcba10f`; not
  pushed, not merged). Commits so far (`git log --oneline main..`): foundation `10aa2a5`, first handoff
  `01a1e0a`, NeoForge 1.21.1 harness `19e0b1c`, analysis pipeline + methodology safeguards + Sodium mod set
  `a26c33d`, reduced-TPS policy handoff `97a5886`, launcher system data + reduced-TPS implementation `dd2656d`,
  Forge 1.20.1 port + functional record `9b4f82e`, then reproducibility/reporting and this handoff
  (`git log --oneline main..`).
- Untracked `AGENTS.md` and `.agents/` in the repo root are unrelated and pre-existing: leave them alone
  and never stage them.
- **No Particle Filter performance has been measured.** Nothing in `benchmarks/` may be quoted as a
  performance result. The harness and pipeline have only passed functional checks (60 FPS cap, 5 s phases) and
  unit tests on synthetic data (94 tests).
- **Actual performance testing remains blocked until the owner confirms the laptop's CPU/GPU power-limit problems
  are resolved.** Not confirmed as of 2026-11-09.

### Done

| Item | Where | Verified by |
|---|---|---|
| Code analysis: Particle Filter's interception, rule evaluation, cost per path, Forge vs NeoForge, confounders, Sodium Extra equivalence | `IMPLEMENTATION.md` | reading the source and the decompiled Minecraft/Sodium Extra code (file:line refs inside) |
| Methodology: questions Q1-Q8, configurations, metric definitions, controls, statistics, phases, risks | `PLAN.md` | n/a (design) |
| Synthetic harness design | `HARNESS.md`, `scenarios/S1-synthetic.toml` | n/a (design) |
| Analysis tools (stdlib Python): `frametimes.py`, `compare.py`, `schedule.py`, `collect_env.py`, `sample_system.py`, `make_pf_config.py`, `ingest_harness.py`; `pilot_devclient.sh` | `tools/` | **94 unit tests pass** (all suites): `python3 -m unittest discover -s benchmarks/tools/tests -v` from the repo root |
| Functional pipeline pilot (title screen; **not a performance result**) | `results/pilot-2026-11-09/PILOT.md` | 3 launches × 30 s fully logged |
| **pfbench harness 0.2.0, NeoForge 1.21.1** (separate Gradle build, mod id `pfbench`): generator (4 variants), counters, frame log, phase runner, mode switching by reflection, template world, launcher `pfbench.py` (template / run / campaign / check) with the performance interlock | `harness/` (`harness/README.md`) | functional bring-ups below |
| First functional bring-up (harness 0.1.0) | `results/functional-2026-11-09/FUNCTIONAL.md` | `check.json` per launch |
| **Analysis pipeline**: `ingest_harness.py` (launch → per-window `run.json` + `summary.json` with workload, diagnostics, environment, provenance); `compare.py` strata, environment pairing, order-balance table, label gate | `tools/`, `templates/run.json` | unit tests on synthetic harness runs (known speed-up found exactly; exclusions for environment, launch shape, failed checks); run end to end on functional runs (windows unusable by design) |
| **Methodology safeguards** (owner's five checks): carry-over, warmup and order, provenance, equal rendering environment, checks apart from measurement | `harness/README.md` "Methodology safeguards"; PLAN.md §2, §4, §5, §8 | `results/functional-2026-11-09-pipeline/FUNCTIONAL.md` |
| **Sodium 0.8.13 + Sodium Extra 0.9.5** downloaded (owner-approved) into `harness/run/downloads/`, compatibility verified, `--modset sodium`, configuration E | `results/functional-2026-11-09-pipeline/SODIUM.md`, `configs/sodium/` | functional checks pass for A, C/B and E (typed and vanilla) with Sodium |
| **Launcher system data** (task 3 of the previous handoff): `collect_env.py` before/after every launch, `sample_system.py` on the game JVM (never `--self-cpu`), clock alignment (`harness.json["clock"]`, sampler `mono_ns`), GC-log parser, CPU-/GPU-bound label, power state in the pairing environment, optional `--mangohud` | `harness/pfbench.py`, `tools/system_data.py`, `tools/sample_system.py` | unit tests (`test_system_data.py`); `results/functional-2026-11-09-sysdata/FUNCTIONAL.md` (files written and aligned; no timings read) |
| **Reduced-TPS policy implemented** (task 1): `tps_class` (< 19.6 = reduced), achieved TPS, requests per tick and per second, per-tick view; `compare.py` pairs on per-tick workload, keeps primary/saturation datasets × bound label apart, lists every pair and the held/lost-20-TPS pairs | `tools/ingest_harness.py` (run.json schema 2), `tools/compare.py`, PLAN.md §5 | unit tests on synthetic runs with slowed ticks (`ReducedTpsTests`, `CompareTests`) |
| **Forge 1.20.1 harness** (task 2; accepted by the owner as complete): `harness-forge/` (legacyforge, Java 17, refmap) compiling the shared `harness/src/main`; per-loader `Platform`; `pfbench.py --loader forge` (own template, named-mappings Particle Filter jar, vanilla modset only) | `harness-forge/`, `harness/README.md` "Forge 1.20.1" | `results/functional-2026-11-09-forge/FUNCTIONAL.md`; unit tests (`LoaderTests`) |
| **Reproducibility audit**: third-party guide (requirements, exact commands for both loaders, supported configurations, campaign designs, analysis rebuild, the owner machine's local assumptions); `pfbench.py` no longer needs mise (`java_prefix()`); ingest writes campaign-relative paths | `REPRODUCING.md`; `harness/pfbench.py`, `tools/ingest_harness.py` | documented prepare commands run with a plain JDK 21 for both harnesses; **clean checkout of tag v1.2.0 rebuilt byte-identical jars** (release SHA-256s and the Forge named jar `613c58b6…`); `GradleCommandTests` |
| **Campaign dry run + design checks**: `campaign` validates every launch first, `--dry-run` prints pairability per PLAN.md comparison, balance, carry-over, scripted time; performance campaigns with an impossible comparison are refused. Found: combined designs (A,B,C,D,C-miss; A,B,C,E with Sodium) would have produced **zero** pairs for Q1, Q8 and Q7 | `harness/pfbench.py` (`campaign_analysis`), `results/dryrun-2026-11-09/DRYRUN.md` (+ `dry-runs.txt`) | `CampaignAnalysisTests` cross-check the dry-run verdicts against `compare.py` on synthetic launches laid out as scheduled |
| **Publication and auditability**: `publish_campaign.py` (archive raw launches byte for byte, rebuild `runs/` + `comparison.*` + `report.generated.md` from raw only, `SHA256SUMS`, `verify` = hashes + byte-identical rebuild); report templates (`report.md` interpretation, `limitations.md`); audit guide (evidence map, what hashes/logs/checks can't prove, pre-registration, single-machine limits, replication, discrepancy reports) | `tools/publish_campaign.py`, `templates/`, `AUDIT.md`, README "Data layout", PLAN.md §9 | `PublishTests` (round trip from another location, edited raw data and hand-edited results caught, labels/loaders never mixed, world/jars left out but hashed); end to end on a 2-launch functional campaign (`functional-publish-test`, not archived in the repo) |

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

**Reduced-TPS policy (decided 2026-11-09; recorded in PLAN.md §5 "Reduced-TPS runs"; implemented 2026-11-09)**
- Retain valid runs where either configuration falls below 20 TPS; don't exclude them.
- Normal 20-TPS comparisons are the **primary FPS dataset**; reduced-TPS comparisons are classified separately as
  **saturation / stress-test results**. Never combine saturated and non-saturated measurements in one aggregate.
- Report achieved TPS, FPS, frame-time distributions, particle requests per tick and particle requests per
  real-world second.
- Keep per-tick-normalized analysis where meaningful, but never as a substitute for real-time measurements.
- Explicitly identify cases where filtering maintains 20 TPS while the baseline cannot.
- Preserve raw measurements, including unfavourable results.

**Reproducibility and reporting phase (2026-11-09)**
- The Forge 1.20.1 port and analysis integration are accepted as complete; don't repeat them.
- This was the final preparation phase before the hardware power-limit validation. After it, **stop**: the actual
  campaign resumes only after the owner confirms the power-limit fix.
- Avoid unnecessary new tooling or dependencies (everything stays Python standard library + the existing Gradle builds).

**Approved**
- Commit benchmark work to `benchmarks/foundation` (stage only `benchmarks/` files).
- The isolated NeoForge 1.21.1 synthetic harness (`benchmarks/harness/`, its own Gradle build, its own mod id).
- **Downloading compatible Sodium and Sodium Extra releases into an isolated benchmark environment** (2026-11-09;
  done: `benchmarks/harness/run/downloads/`).
- Limited **functional** validation of tools and harness, clearly labelled "functional, not a performance result".
- Continue building and testing infrastructure (2026-11-09: analysis pipeline first, then the Forge 1.20.1 port).

**Prohibited**
- ~~No performance runs until the power-limit fix is confirmed~~: **confirmed by the owner 2026-11-09 (sixth
  session); calibration and variance studies are allowed. Official campaigns only after the methodology is
  pre-registered, published and frozen, and the owner authorizes pushing.** It includes timing comparisons that look like validation (harness vs MangoHud
  agreement, hidden-window throttling).
- Modifying Particle Filter's production code (`src/`, build files for the release jars).
- Modifying existing Minecraft installations or worlds (CurseForge instances, their configs, saves).
- Copying ATM10 server worlds or benchmarking against the live server (real-world scenario R1 is on hold).
- Changing CPU affinity (`taskset`, pinning, `sample_system.py --self-cpu`), firmware, GPU settings, or system power configuration.
- Pushing, merging, or touching `main`. Installing system packages without asking.
- Installing a separate production Forge environment (not yet; next-task 3 below needs the owner's approval).

## Unresolved issues (known, not yet fixed)

- Thresholds are provisional until the variance study (phase 4, blocked): `jit_activity` (5 ms of JIT per second of
  window), tick-rate tolerance (2%, which also sets the 19.6 TPS reduced-TPS line), frame coverage (98%), the bound
  label (95% GPU utilization, 95% render thread, 5% clock spread, 3 samples), the warmup/settle/measure lengths in S1,
  and the block counts in DRYRUN.md.
- The sampler's own cost (1 s interval, one Python process + `nvidia-smi -lms`) is unmeasured; it runs in every
  configuration equally. MangoHud under the launcher writes its frame CSV but no `_summary.csv` (the game quits first).
- Both harnesses measure the ModDevGradle dev runtime, not a production install. On Forge 1.20.1 this means Particle
  Filter's named-mappings jar (same Gradle run as the reobfuscated release jar, both hashes recorded), not the
  release jar itself.
- Window size differs between launches on the hidden workspace (2536×1390 vs 2560×1440). Compare excludes
  mismatched pairs; performance runs are meant to be fullscreen on DP-1, which is still unverified (phase 2b, blocked).
- Phase 2b checks (harness frame log vs MangoHud, hidden vs visible window, fullscreen on DP-1) compare timings and
  stay blocked.
- **Not launchable yet:** Q6 rule/zone-count configurations (C-rules-N, D-zones-N) and the B0 sanity check (PLAN.md
  §2). `make_pf_config.py` can generate their configs; `pfbench.py CONFIGS` has no entries for them.
- **Not recorded:** fan state and ambient temperature; background load, display and power state only before/after each
  launch (AUDIT.md §2 "Gaps").
- `scenarios/S1-synthetic.toml` says `minecraft = "1.21.1"`, `loader = "neoforge-21.1.250"` although Forge launches use
  it too (the fields are descriptive; real versions are in `harness.json` and checked). Left unchanged to keep the
  scenario hash; fix it with a version bump before the first performance campaign if wanted.
- Byte-identical rebuild of the Particle Filter jars was shown on this machine with Temurin 21.0.12 only.
- `raw/` of a full campaign may be large (frame logs at uncapped FPS); publishing it as a release archive with
  `SHA256SUMS` in git is documented but untried.

## Next tasks (none measures performance)

Preparation is complete. **Stop here until the owner confirms the power-limit fix.** Then, in order:

1. Phase 2b (PLAN.md §7): harness frame log vs MangoHud, hidden vs visible window, fullscreen on DP-1, the sampler's
   own cost. Then phase 3 calibration and phase 4 variance study (they fix the provisional thresholds, phase lengths
   and block counts). `PFBENCH_PERFORMANCE_APPROVED=1` only after the confirmation.
2. Before each performance campaign: pre-register it (AUDIT.md §4: commit the `--dry-run` output and analysis
   arguments as `results/<campaign>/PREREGISTRATION.txt`), run it with `pfbench.py campaign`, then
   `publish_campaign.py archive/report/manifest/verify`. One campaign per question (DRYRUN.md).
3. Optional, needs approval: a production-Forge route (isolated Forge 1.20.1 install, reobfuscated harness) to measure
   the Forge release jar itself; Q6/B0 configurations in `pfbench.py` (small, not blocked, but not requested).

## Open questions for the owner

- When are the power-limit problems fixed? (This gates every performance phase.)
- Is there an acceptable real-world route now that world copies and live-server runs are ruled out
  (e.g. a new test world built for benchmarking in a separate, new instance)?
- How should discrepancy reports from third parties be handled publicly (AUDIT.md §7 leaves this to you)?
- Where should a campaign's `raw/` data be published: in git, or as a release archive pinned by `SHA256SUMS`?
  The benchmark branch also has to be published for anyone to reproduce from its commits.

## Read these, in order

1. `benchmarks/HANDOFF.md` (this file)
2. `benchmarks/REPRODUCING.md` and `benchmarks/AUDIT.md` (how a third party builds, runs, rebuilds and checks; what
   the evidence can't prove), `benchmarks/results/dryrun-2026-11-09/DRYRUN.md` (campaign designs)
3. `benchmarks/harness/README.md` (how the harness and pipeline work, the safeguards table, deviations, what's missing)
4. `benchmarks/results/functional-2026-11-09-sysdata/FUNCTIONAL.md`, `functional-2026-11-09-forge/FUNCTIONAL.md`,
   `functional-2026-11-09-pipeline/FUNCTIONAL.md` and `SODIUM.md`
5. `benchmarks/results/functional-2026-11-09/FUNCTIONAL.md` (first bring-up)
6. `benchmarks/HARNESS.md` and `benchmarks/scenarios/S1-synthetic.toml` (design, parameters)
7. `benchmarks/PLAN.md` §2, §4, §5, §7, §8 and §9 (configurations, controls, statistics, phases, risks, reporting)
8. Repo `CLAUDE.md` (build commands, Stonecutter layout, commit rules: repo-local noreply identity, AI disclosure)

Don't redo: code analysis, tool writing, the pipeline pilot, MangoHud probing, the harness bring-up, the Sodium
compatibility check, the Forge port and its functional validation, the reproducibility audit, the campaign dry run,
the publication tools and templates. They are finished and recorded. Don't analyse the functional runs' frame logs or
JIT/GC numbers; that would be measurement.

## Prompt for the next session

```
Continue the Particle Filter benchmark project in ~/Work/particle-filter on branch benchmarks/foundation.
Read benchmarks/HANDOFF.md first and follow its owner decisions exactly, then read the files it lists.
Infrastructure, reproducibility and reporting tools are complete: the pfbench harness (NeoForge 1.21.1, Forge 1.20.1),
launcher system data, the analysis pipeline with the reduced-TPS split, campaign design checks (campaign --dry-run)
and publication (tools/publish_campaign.py archive/report/manifest/verify). [If I have confirmed the power-limit fix:
start phase 2b as PLAN.md section 7 describes, pre-registering each campaign as AUDIT.md section 4 says. Otherwise:
stop; there is nothing unblocked left.] Don't modify Particle Filter's production code, existing Minecraft
installations or worlds, CPU affinity, or power/GPU settings. Leave AGENTS.md and .agents/ alone. Commit finished work
to benchmarks/foundation (don't push) and update benchmarks/HANDOFF.md before stopping.
```
