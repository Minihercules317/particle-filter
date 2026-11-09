# Launcher system data and reduced-TPS pipeline: functional validation (2026-11-09)

> **Functional, not a performance result.** Both launches ran at a 60 FPS cap, in a window on a hidden Hyprland
> workspace, with 5-second phases. They check that the launcher writes the environment snapshots, system-sampler
> data, clock alignment, GC-log summary and MangoHud log, and that `ingest_harness.py` and `compare.py` read them.
> No frame time, TPS, sampler, GPU, JIT or GC figure was analysed or is reported. Performance runs stay blocked
> until the owner confirms the laptop's power-limit problems are fixed (HANDOFF.md).

Harness: pfbench **0.3.0** (adds `harness.json["clock"]`), NeoForge 21.1.250, Minecraft 1.21.1, Temurin 21.0.12.
Particle Filter: the 1.2.0 release jar (SHA-256 `0806e94c…`). Launched from commit `615845d` with the uncommitted
changes that this record's commit contains (listed in each `launch.json["provenance"]["git_dirty_paths"]`).

## What was added

| Part | What it does |
|---|---|
| `pfbench.py run` / `campaign` | `tools/collect_env.py` into `env-before.json` (before Gradle starts) and `env-after.json` (after the game quit, with the game's own java binary); `tools/sample_system.py` against the game JVM from the moment it appears until it exits (`system/`, 1 s interval, **never `--self-cpu`**); the game process's command line and affinity (read, not changed) in `launch.json["game_process"]`; `--mangohud` runs the launch under MangoHud with the pilot's minimal HUD and keeps the per-frame log in `mangohud/` |
| Harness 0.3.0 | `harness.json["clock"]`: `System.nanoTime()`, wall clock and JVM uptime at the phase origin, so sampler rows (CLOCK_MONOTONIC) and GC-log lines (JVM uptime) line up with phases |
| `tools/sample_system.py` | `mono_ns` column on every row; `meta.json` with interval, P-/E-core lists and (wall clock, monotonic) pairs at start and end, which convert nvidia-smi's wall-clock timestamps |
| `tools/system_data.py` (new) | Loads sampler data, aligns it with windows, parses GC pauses from `game/gc.log`, the CPU-/GPU-bound label (PLAN.md §4), the power state from snapshots |
| `pfbench.py check` | New checks: both snapshots written; power settings (AC, profiles, EPP, no_turbo, RAPL limits) equal before and after; sampler ran without `--self-cpu`, wrote its files and covers every measured phase; GPU rows line up with the phases; `harness.json` has the clock; `gc.log` parses and contains the settles' `System.gc()` pauses; MangoHud wrote a per-frame log (row count only) |
| `ingest_harness.py` (schema 2) | Per window: `achieved_tps`, `tps_class`, requests per tick and per second, `per_tick`, `system`, `gc_log`, `bound` + `bound_reason`; `power_state_sha256` and `mangohud` in the environment; error `power_settings_changed` if a launch's power state changed |
| `compare.py` | Workload equality per tick; primary vs saturation datasets crossed with the bound label, never aggregated together; every-pair table; held/lost 20 TPS tables; every run with TPS, class, bound and the full frame-time distribution |

## Launches

| Run | Configurations | Flags | Checks |
|---|---|---|---|
| `functional-0.3.0-sysdata-C-B` | C, B (moderate) | `--functional --hide` | 64/64 pass |
| `functional-0.3.0-mangohud-B` | B (moderate) | `--functional --hide --mangohud` (MangoHud v0.8.4) | 49/49 pass |

Kept here: `check.json`, `harness.json`, `launch.json`, `plan.json`, `env-before.json`, `env-after.json` and
`system/meta.json` per launch (home paths written as `~`). The sampler CSVs, the MangoHud log and the frame logs stay
in the gitignored `harness/run/runs/` and were not analysed.

Functional findings:
- The sampler started well before the first phase and stopped by itself when the game exited (return code 0).
- The game renders on the NVIDIA GPU in these launches (`gl_renderer`), so the bound label could be computed; both
  windows of the C/B launch were labelled `mixed`, which is expected at a 60 FPS cap and says nothing about
  Particle Filter.
- `ingest_harness.py --allow-functional` → `compare.py --allow-functional` produced a report titled "PIPELINE TEST,
  NOT A RESULT" with the new sections; the functional windows stay unusable by design (frame-capped, too short).
- MangoHud under the launcher wrote its per-frame CSV but **no `_summary.csv`**: MangoHud writes the summary only when
  logging stops, and the game quits first. The frame CSV is the raw data the phase 2b cross-check needs; whether it
  holds every frame up to the quit is a timing question for phase 2b (blocked).

## Not checked (blocked or out of scope)

- Whether the 1 s sampler disturbs frame times, whether the bound thresholds (95% GPU utilization, 95% render thread,
  5% clock spread, 3 samples) separate CPU- and GPU-bound windows well: both need performance runs (phase 2b/4).
- Agreement between MangoHud's log and the harness's frame log (phase 2b).
