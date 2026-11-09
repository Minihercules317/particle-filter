# Analysis pipeline and methodology safeguards: functional validation (2026-11-09)

> **Functional, not a performance result.** Every launch here ran at a 60 FPS cap, in a window on a hidden Hyprland
> workspace, with 5-second phases. They check that the harness, its checks and the analysis pipeline work. No
> timing, JIT or GC figure was analysed or is reported. Performance runs stay blocked until the owner confirms the
> laptop's power-limit problems are fixed (HANDOFF.md).

Harness: pfbench 0.2.0 (`benchmarks/harness/`), NeoForge 21.1.250, Minecraft 1.21.1, Temurin 21.0.12.
Particle Filter: the 1.2.0 release jar (SHA-256 `0806e94c…`, in every `launch.json` and, read in game, in every
`harness.json["mods_folder"]`). Scenario: `S1-synthetic.toml` as committed with this record.
Rendering mods: see [SODIUM.md](SODIUM.md).

## What changed since the first bring-up

The owner asked for five things to be verified before the Forge port. Each became a harness or tool change plus a
check; [harness/README.md](../../harness/README.md) ("Methodology safeguards") has the summary table.

1. **Carry-over between phases.** Every settle after a switch must last at least the longest particle life + 2
   ticks; `pfbench.py` refuses a shorter plan. Emitter cursors reset at every phase start. New check: no target
   particle alive in any filtered window, for every variant (the end_rod variant uses the control particle's own live
   counter, because both share the translucent queue).
2. **JVM warmup and order effects.** The warmup cycles every configuration of a launch twice in its own mode; `System.gc()`
   at each settle start; fixed heap (`-Xms4G -Xmx4G`); JIT compile time, class loading and level saves logged per
   tick and turned into per-window flags; `pfbench.py campaign` orders entries and launches by Williams design from
   one seed; `compare.py` reports position balance and pairs only within strata and equal launch shapes.
3. **Provenance.** Jar SHA-256s from the launcher and from inside the game (the whole `mods/` folder), every loaded
   mod file including nested jar-in-jar files, mod versions, actual JVM arguments, GPU renderer and driver, input
   configs before and after, git commit and uncommitted paths, harness source hash, launcher and tools hashes.
4. **Equal rendering environment for the Sodium Extra comparison.** One base config for both mods; only Sodium
   Extra's entry for the target differs; a rendering-environment hash that leaves out exactly that entry is part of
   every window's environment. Details in SODIUM.md.
5. **Correctness checks apart from measurement.** Checks read the logs after the game quit; performance plans take no
   screenshots (the Runner ignores screenshot requests unless the plan is functional; checked); `ingest_harness.py`
   marks every window of a launch whose checks failed as unusable; both ingest and `compare.py` refuse functional
   runs unless explicitly told to, and the report is then titled "PIPELINE TEST, NOT A RESULT".

## Launches (harness 0.2.0, final code)

| Run | Configurations (moderate: 50 attempts/tick) | Variant | Mod set | Checks |
|---|---|---|---|---|
| `20261109T125906Z-C-B-D-typed-functional` | C → B → D | typed | vanilla | 69/69 |
| `20261109T130005Z-B-C-vanilla-functional` | B → C (carry-over test: unfiltered end_rods before a filtered window) | vanilla `minecraft:end_rod` | vanilla | 52/52 |
| `20261109T130055Z-B-D-C-untyped-functional` | B → D → C | untyped | vanilla | 69/69 |
| `20261109T130154Z-B-C-block_dust-functional` | B → C | block dust (stone) | vanilla | 54/54 |
| `20261109T130244Z-A-typed-functional` | A | typed | vanilla | 35/35 |
| `20261109T130325Z-C-miss-typed-functional` | C-miss | typed | vanilla | 39/39 |
| `functional-campaign-test-b01-l1-B_moderate-C_moderate` | campaign block 1: B → C | typed | vanilla | 55/55 |
| `functional-campaign-test-b02-l1-C_moderate-B_moderate` | campaign block 2: C → B | typed | vanilla | 55/55 |
| Sodium launches (A, E, C → B, E vanilla) | see [SODIUM.md](SODIUM.md) | | sodium | 36/36, 36/36, 56/56, 35/35 |

`runs/<run>/` holds each launch's `check.json`, `harness.json`, `launch.json`, `plan.json`, and its input configs
before and after (home directory replaced by `~`). Frame and tick logs stay in the gitignored
`benchmarks/harness/run/`. `campaign-functional-campaign-test.json` is the campaign sheet.

Earlier launches of this session (`20261109T125023Z-C-B-D-typed`, `20261109T125135Z-B-C-vanilla`) ran before the
in-game jar list handled jar-in-jar files; on re-check they fail only that item and were repeated above. They are
not part of this record.

## Findings

- **Carry-over (measured as particle counts, not timing).** In both B → C vanilla launches, B's end_rods were
  still alive at settle tick 71 and gone from tick 72, as the lifetime math predicts (60 + nextInt(12) ≤ 71 ticks,
  alive in lifetime + 1 post-tick counts). The first bring-up's 3 s (60-tick) functional settle would have let about
  11 ticks of leftover end_rods into a C window after B (that bring-up ran C → B, so it wasn't affected). The
  performance settle (15 s) was always long enough. Functional settle is now 4 s; the minimum is enforced.
- **Typed, untyped, block dust:** every filtered window had zero target particles alive on every tick; every
  unfiltered typed/untyped window had exactly 50 × 41 = 2,050; the control had exactly 25 × 41 = 1,025 everywhere
  (its own counter and, except for end_rod, the translucent queue size).
- **Warmup:** every launch with several configurations warmed each one up twice (e.g. `warmup-r1-01-C`,
  `warmup-r1-02-B`, `warmup-r1-03-D`, then round 2) before the first measurement; checked per launch.
- **Campaign executor:** `campaign --configs C,B --blocks 2 --seed 1` ran B → C then C → B, recorded block and
  launch numbers in `launch.json`, and `ingest_harness.py` picked them up without `--block`. `compare.py` showed
  both configurations once in each position.
- **Pipeline, end to end on real files:** `ingest_harness.py` refused functional launches without
  `--allow-functional` and wrote one window per measured phase with it; `compare.py` refused them without the flag
  and titled its report "PIPELINE TEST, NOT A RESULT" with it. Every functional window was also unusable on
  frametimes' own checks (`too_few_frames`, `frame_limited`) and carried the `jit_activity` flag (expected with a
  6 s warmup). So functional data can't produce a verdict even by accident. The ingest outputs (which contain
  timing values) were written to a scratch directory and are not part of this record; their timing values were not read.
- **Window size** differed between launches with the same procedure (2536×1390 or 2560×1440 on the hidden
  workspace). It never changed within a launch (checked). Window size is part of the environment pairs must share.

## Not covered

- Timing checks of any kind (harness vs MangoHud, hidden vs visible window, fullscreen on DP-1): phase 2b, blocked.
- The `environment differs` and `launch shape` exclusions in `compare.py` are exercised by unit tests on synthetic
  runs (`tools/tests/test_pipeline.py`), not by these functional windows, which are excluded earlier as unusable.
