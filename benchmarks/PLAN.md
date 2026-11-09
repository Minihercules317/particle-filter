# Particle Filter benchmark plan

Status (2026-11-09): **design + infrastructure + pipeline pilot**, committed on branch `benchmarks/foundation`.
No Particle Filter performance has been measured. Every number below is a design parameter or a figure from
the pipeline pilot, not a result.

> **Gate: no performance runs yet.** The laptop has unresolved CPU/GPU power-limit problems. Until the
> owner explicitly confirms they are fixed, run **no** benchmarks, calibration sweeps, variance studies
> or other performance measurements. Only limited *functional* validation of the tools is allowed, and it
> must be labelled as such (e.g. "functional check, not a performance result"). The owner's other
> decisions are in [HANDOFF.md](HANDOFF.md).

Read with [IMPLEMENTATION.md](IMPLEMENTATION.md) (how the mod works, what could cost time) and
[HARNESS.md](HARNESS.md) (the synthetic workload tool still to be built).

## 1. Questions and how each is answered

| # | Question | Experiment | Primary comparison |
|---|---|---|---|
| Q1 | Overhead when installed but inactive? | S-overhead, R-overhead | B vs A (and B-idle vs A-idle, with no particle load) |
| Q2 | CPU/GPU work avoided by filtering? | S-filter with system sampling + diagnostic profiling | C vs B: render-thread CPU, GPU utilization, frame CPU time, GC |
| Q3 | FPS and frame-time consistency in realistic heavy scenes? | R-filter (real world), S-filter vanilla variant | C/D vs B: mean frame time, p99, 1% low |
| Q4 | Scaling with spawn attempts? | S-filter at every load level | C − B difference as a function of attempt rate |
| Q5 | Zones vs global overhead? | S-filter | D vs C at equal suppression |
| Q6 | Does rule/zone count matter? | S-scaling | D-zones-{1,10,100}, C-rules-{1,10,100,1000} vs their 1-rule versions |
| Q7 | vs Sodium Extra doing the same job? | S-compare (typed, global only) | C vs E, both with Sodium installed |
| Q8 | Workloads where Particle Filter is slower? | S-overhead, C-miss at extreme load, untyped variant | B vs A, C-miss vs B |

Suppressing particles does not have to raise FPS. If the frame is limited by something else (chunk
rendering, entities, GPU fill), removing particles may change nothing. The plan treats "no measurable
difference" as a legitimate, reportable answer.

## 2. Configurations

All configurations in a comparison share the same Minecraft, loader, Java, JVM arguments, mod set (apart
from the factor being tested), options, world and camera.

| Id | Particle Filter | Rules | Purpose |
|---|---|---|---|
| **A** | not installed | n/a | baseline |
| **B** | installed | `mode: NONE` (Active rules: None) | installed, inactive. `NONE` compiles an empty snapshot: the same runtime path as having no rules |
| B0 | installed | empty config, mode ALL | sanity check that B ≡ "no rules at all" (run once in the variance study) |
| **C** | installed | target type hidden everywhere (`mode: EVERYWHERE`) | global filtering |
| **C-miss** | installed | rules that never match the workload (`mode: EVERYWHERE`) | worst case: pays lookups, saves nothing (Q8) |
| **D** | installed | target hidden inside one zone that encloses every emitter (`mode: ZONES`) | spatial filtering, same suppression as C |
| D-zones-N | installed | as D plus N−1 non-matching zones in the same dimension, matching zone last | zone-count scaling (Q6) |
| C-rules-N | installed | target + N−1 non-matching global rules | rule-count scaling (Q6) |
| **E** | not installed | Sodium Extra hides the target type | comparator (Q7); A, B and C are re-run with Sodium for this series |

B, C and D can share one config file (`benchmarks/configs/pf-synthetic.json`) holding both the global
rule and the zone. Only the Active rules mode changes, so the harness can switch between them inside one
launch.

**Equivalent workload rule:** compared configurations must receive the same *attempts* and keep the same
*unfiltered* particles (the control type) alive. The harness logs both; `compare.py` rejects pairs that
differ by more than 2%. If an alternative can't suppress exactly the same set (zones, untyped particles,
per-block rules for Sodium Extra), there is no comparison for that case, and the report says why.

## 3. Environments

### S: controlled synthetic (dev client, NeoForge 1.21.1 first, then Forge 1.20.1)

- The repo's dev client (`./gradlew :1.21.1-neoforge:runClient`), vanilla rendering, plus the pfbench
  harness. Its run directory is gitignored and holds no user data.
- Fixed template world copied fresh for each launch; locked camera; HUD hidden; Particles = All.
- **Load levels (provisional; finalized by the Phase 3 calibration).** The harness target particle has
  a fixed 40-tick lifetime, so the steady-state live count = attempts per tick × 40, up to the 16,384
  limit per render type:

  | Level | Attempts/tick | Live target particles (expected) | Why |
  |---|---|---|---|
  | idle | 0 | 0 | pure installation overhead |
  | low | 10 | ~400 | ordinary play |
  | moderate | 50 | ~2,000 | busy base |
  | heavy | 200 | ~8,000 | heavy machinery / effects |
  | saturating | 410 | ~16,400 | at the per-type limit |
  | extreme | 2,000 | 16,384 alive, ~80,000 attempted per lifetime | beyond the limit: eviction churn, construction cost only |

  The control type runs at a fixed moderate rate (e.g. 25/tick) at every level.
- Variants: typed (default), untyped, block-dust, vanilla (`minecraft:flame` or `minecraft:end_rod`).

### R: real world (ATM10 To the Sky): ON HOLD

> **Owner decision (2026-11-09):** don't copy ATM10 server worlds, don't benchmark against the live
> server, and don't modify existing Minecraft installations or worlds. The real-world benchmark is on hold
> until the owner chooses another route. The notes below are the design for when that happens.

- **There is no local copy of the base:** the instance's `saves/` is empty and the bases are on
  two private multiplayer servers (addresses withheld from publication; known from the zone list in the
  instance's Particle Filter config).
- Options considered: a backup copy of the server world in a separate benchmark-only instance, or a
  clearly labelled field observation on the live server. **The owner declined both (2026-11-09).**
  An acceptable real-world route is still an open question for the owner.
- Scenarios: stand at fixed coordinates near (1) AE2 growth accelerators, (2) an automated farm, and
  (3) another particle-heavy build the owner picks. Run a Particle Filter scan *before* each block
  (never during a measured window) to record which particles and how many; the scan counts are the
  workload description.
- Hard to control: machine duty cycles (power and input buffers), farm cycles, random ticks, entity AI,
  chunk loading, integrated-server work on the same CPU, autosave every 5 minutes, day/night lighting, and
  weather. Mitigations: `/time set`, `/weather clear` and `/gamerule doDaylightCycle false` in the copy only;
  same coordinates and rotation via `/tp`; flag windows overlapping an autosave or a GC pause; within-launch
  mode switching (B/C/D) so slow drifts cancel in pairs.
- Particle Core is installed with `asynchronousTicking = true`. It stays as installed (that is the real
  environment) and is recorded. A follow-up can test with and without it as its own factor.

## 4. Measurement

### Tools (verified status on this machine, 2026-11-09)

| Tool | Gives | Status |
|---|---|---|
| MangoHud 0.8.4 (host package) | per-frame frame time, CPU load, RAM, `cpu_mhz` | **Works** for the dev client via `mangohud` + Gradle (env vars reach the game JVM; the OpenGL layer loads, the game renders on the NVIDIA GPU). **`no_display` disables logging entirely** in 0.8.4, so runs use the minimal `fps_only,background_alpha=0` HUD, identical in every run. GPU columns (`gpu_load`, `gpu_temp`, `gpu_power`) are **zero** on this hybrid laptop. With `log_interval=0` every frame is logged (checked: frame-time sum matches the `elapsed` span). |
| MangoHud in the CurseForge Flatpak | same | HUD known to work (Java wrapper). Logging untested. It is a different MangoHud build (Flatpak extension), so record its version. |
| `nvidia-smi -lms` (via `sample_system.py`) | GPU util, clocks, power, temperature, P-state, throttle reasons | Works |
| `sample_system.py` (/proc, /sys) | CPU % per game thread, the core each ran on, package temperature, P/E-core clocks, RSS | Works (pilot) |
| pfbench harness | frame log aligned to phases, attempts/created/alive, tick times, GC | **Not built** (HARNESS.md) |
| JVM unified GC log (`-Xlog:gc*:file=…:time,uptime`) | GC pauses and frequency | Standard JVM feature; parser not written yet |
| JFR (`jcmd`/`-XX:StartFlightRecording`) | CPU sampling, allocation profiling | Available (Temurin 21); diagnostic runs only |
| spark (installed in ATM10 To the Sky) | sampling profiler in game | Available there; diagnostic runs only |
| `perf`, PresentMon, gamemode | n/a | not installed; PresentMon is Windows-only. Not needed for the first phases |

### Metric definitions (implemented in `tools/frametimes.py`)

Frame times are in milliseconds. A *window* is the frames whose running sum of frame times falls in
`(skip, skip + duration]`. Percentiles use the **nearest-rank** method: the p-th percentile is the value at
rank ⌈p/100 · N⌉ of the sorted frame times.

| Metric | Definition |
|---|---|
| Average FPS | N / Σ frame time × 1000 (time-weighted; **not** the mean of per-frame FPS) |
| Mean frame time | Σ frame time / N. **Primary outcome** for every comparison: it is additive, so differences are meaningful in ms |
| Median, p90, p95, p99, p99.9 frame time | nearest-rank percentiles |
| **1% low FPS** | 1000 / (mean of the slowest ⌈1% · N⌉ frame times), the "average of the worst 1%" |
| 0.1% low FPS | same with 0.1% |
| p99 as FPS | 1000 / p99 frame time; some tools call *this* "1% low", so both are reported, under different names |
| Hitches | frames > 50 ms |
| Per-second FPS | frames / time in consecutive 1-second buckets (used for the drift check) |

MangoHud's own summary file (`*_summary.csv`, "1% Min FPS", "97% Percentile") is kept as raw data
but never used for results, because its definitions differ.

Sanity checks per window (errors make a window unusable; warnings stay in the report):
too few frames (< 1000), non-positive frame times, frames > 1 s, frame-time sum ≠ `elapsed` span by > 2%
(not every frame logged), window shorter than requested, average within 1% of a known frame cap (measures the
limiter), > 3% FPS change between the first and last third of the window (drift).

### CPU-bound vs GPU-bound

Each window is labelled from the sampler data: GPU-bound if NVIDIA utilization ≥ 95% at stable clocks;
CPU-bound if the render thread is ≥ 95% busy and GPU utilization is lower. Otherwise it is labelled "mixed".
At each load level, a resolution probe (same window at 1280×720 and 2560×1440) confirms the label: large
FPS changes with resolution mean GPU-bound. Results are reported per label and never pooled across them.

### Controlled variables

| Variable | Setting | How it's enforced or recorded |
|---|---|---|
| Resolution / window | Fullscreen 2560×1440 on **DP-1** (wired to the NVIDIA GPU; the laptop panel eDP-2 is on the Intel iGPU, which adds a PRIME copy) | `options.txt` (`fullscreen:true`); window state recorded via `hyprctl`. The pilot caught the window opening **tiled 1261×1390 in one launch and fullscreen 2560×1440 in the next**, so it is checked every run |
| Window visibility | Visible and focused for measured runs | Hidden special workspace was fine for the 60 FPS-capped pilot; whether hidden windows are throttled at uncapped FPS is **unverified**: Phase 2 tests it |
| Render / simulation distance | 8 / 5 (synthetic); as played (real world) | `options.txt`, recorded |
| Graphics, AO, clouds, biome blend, entity distance | fixed per campaign | `options.txt` keys recorded by `collect_env.py` |
| VSync / frame cap | VSync off, `maxFps` 260 (= unlimited) | recorded; the cap check catches mistakes. Menus are always capped at 60 (`Minecraft.getFramerateLimit`) |
| Particles setting | All | recorded; Decreased randomly drops a third (LevelRenderer.java:2685) |
| Java and JVM args | same build and arguments in every configuration (dev: Temurin 21.0.12.1+1) | `collect_env.py --java`, command line via `--pid` |
| Shaders / optimization mods | none (synthetic); as installed (real world) | mod list + SHA-256 per jar |
| World state | fresh template copy per launch (synthetic); backup copy (real) | world hash recorded |
| Camera | fixed coordinates and rotation | harness (synthetic); `/tp x y z yaw pitch` (real) |
| Warmup / JIT | ≥ 60 s with workload running after world load; 15 s settle after each mode switch (≥ 2× particle lifetime) | phases.csv |
| Background activity | close heavy apps (the env snapshot shows browsers/ChatGPT running during the pilot); no screen recording; no downloads | `top_processes`, `loadavg`, PSI in env snapshots before and after |
| CPU placement | Hybrid CPU: P-cores 0-7, E-cores 8-23. In the pilot the render thread was on an E-core in ~80% of samples (at a light 60 FPS load) | **Not changed:** the owner prohibits changing CPU affinity (no `taskset`, no pinning; `sample_system.py --self-cpu` stays unused). Placement is recorded per thread (`last_cpu`) and its variance is accepted and reported |
| Power / thermals | AC power, platform profile as found, same fan state | **Not changed:** the owner prohibits changing firmware, GPU settings or power configuration. `collect_env.py` records AC, platform profile, EPP; package temperature and GPU clocks/throttle reasons per second; 60 s cool-down between launches |
| GPU clocks | not locked (prohibited) | recorded per second; drift check |
| CPU/GPU power limits | **unresolved problems on this laptop (owner, 2026-11-09).** As read: RAPL MMIO long-term 95 W / short-term 30 W, package 170/210 W | Performance runs are blocked until the owner confirms the fix. `collect_env.py` records every RAPL limit; a campaign is invalid if the limits change partway through |
| Battery charge cap | conservation mode active (BAT0 "Not charging" at 79%) | recorded; on AC it doesn't throttle, but keep it constant |

## 5. Statistical design

- **Unit of analysis:** one measurement window (30 s by default) of one configuration. Windows in the same
  launch are paired; separate launches give the launch-to-launch variance.
- **Blocks:** a block is one round of every configuration being compared. Inside a launch (B/C/D/C-miss mode
  switches) the order follows a Williams design (`tools/schedule.py`, seeded), so each configuration appears in
  each position and after each other configuration equally often. Install-level factors (A vs B, E)
  need separate launches, which alternate in Williams order too (e.g. A B B A B A A B …).
- **Repetitions:** chosen from the Phase 4 variance study, not guessed. With σ_d = SD of the paired
  difference in mean frame time (% of reference) and h = the wanted 95% CI half-width,
  n ≈ (t₀.₉₇₅,ₙ₋₁ · σ_d / h)², iterated over n. Minimum 8 blocks per comparison regardless. For install-level
  comparisons, n counts **launches**, not windows.
- **Analysis (`tools/compare.py`):** per block, difference = metric(config) − metric(reference). Report the
  mean difference, the difference in %, and a 95% percentile bootstrap CI (10,000 resamples, fixed seed),
  plus every individual run in a table.
- **Verdicts, pre-registered:** margin m = **±1% of mean frame time** for Q1 ("no measurable overhead"). A
  difference is called *better/worse* only if the whole CI is beyond ±m, *equivalent* if the whole CI is
  within ±m, and *inconclusive* otherwise. Inconclusive and negative results are published like the others.
- **Primary outcomes:** mean frame time (all questions); p99 frame time and 1% low are secondary and
  descriptive. Only primary outcomes support claims, which limits the multiple-comparisons problem.
- **Exclusions:** only by the automatic sanity checks or a reason written in `run.json` *before* looking at
  the comparison. Excluded runs stay in the raw data and are listed in every report.
- **Never:** pooling CPU-bound and GPU-bound windows; comparing runs with different workloads; presenting
  extreme synthetic loads as typical gameplay; quoting a single run.

## 6. Diagnostic profiling (separate from FPS runs)

Questions like "where did the time go" (Q2) are answered with runs that are **never** used for FPS
numbers: JFR CPU sampling and allocation profiling (`-XX:StartFlightRecording`) at the extreme load for B,
C and C-miss; spark in the real-world copy. These show whether the per-attempt string allocation or Mixin
callback objects appear at all, and how much ticking and rendering time filtered particles cost.

## 7. Ordered experimental plan

| Phase | What | Automated? | Needs approval? |
|---|---|---|---|
| 0 | Code analysis, tools, pipeline pilot | done | n/a |
| 1 | Build the pfbench harness (separate Gradle build in `benchmarks/harness/`), NeoForge 1.21.1 | code only | **approved; next task** |
| 2a | Functional bring-up (allowed now, label as functional): it builds, launches, loads the world, spawns, counters are consistent (A: created = attempts; C: target created = 0, control unchanged), files are written and parse | mostly; screenshot check by eye | approved |
| 2b | Measurement validation: harness frame log vs MangoHud (means within 1%), hidden vs visible window throttling, fullscreen on DP-1 | yes | **blocked** until power limits are fixed (these compare timings) |
| 3 | Calibration sweep in A: attempts/tick log-spaced 5 to 5,000; find where FPS drops, where live counts saturate, and when the scene is CPU-bound or GPU-bound. Fix the load levels | yes | **blocked** until power limits are fixed |
| 4 | Variance study: config B, 5 launches × 6 windows (no pinning); also B0 vs B. Fix window length, warmup, repetitions | yes | **blocked** until power limits are fixed |
| 5 | Synthetic campaigns: S-overhead (A/B idle + loads), S-filter (B/C/**C-miss**/D × levels), S-scaling (zones, rules), untyped + block-dust variants. C-miss is a required scenario | yes | **blocked** until power limits are fixed, then the owner's go-ahead for long batches |
| 6 | Comparator: investigating Sodium/Sodium Extra compatibility with the dev client and harness is **approved if kept isolated** (separate run directory, nothing in the play instances). Measuring A/B/C/E with Sodium in all is a performance run | yes | investigation approved; measurement **blocked** |
| 7 | Real world (ATM10) | n/a | **on hold**: world copies and live-server runs declined; needs a new owner decision |
| 8 | Forge 1.20.1 harness port; replication of S-overhead and S-filter (subset) | yes | port: no; measurement **blocked** |
| 9 | Report: `results/<campaign>/report.md` from the tools; review; only then any public wording | partly | **yes** before publishing anything |
| 10 | Regression mode: a 10-minute subset (B vs A, C vs B at heavy) runnable before releases, compared to the stored baseline | yes | no |

## 8. Risks and limitations

- **One machine.** Results are for this laptop (Core Ultra 9 275HX hybrid CPU, RTX 5070 Ti Laptop + Intel
  iGPU, Linux/Hyprland/XWayland, NVIDIA 615.78.08). Other hardware can rank differently, especially
  GPU-bound vs CPU-bound balance. The report says so and the tools let others re-run it.
- **Laptop thermals and boost** can drift over long batches; mitigated by drift checks, cool-downs,
  interleaving, and logging clocks and temperatures, but not eliminated.
- **Synthetic ≠ real.** The harness particle is simpler than many modded particles (no physics, small sprite).
  The vanilla variant and the real-world scenario guard against over-generalizing.
- **Spawn path coverage.** Mods that bypass `ClientLevel.addParticle` or use custom block effects are only
  covered by the untyped and real-world tests.
- **Instrumentation overhead.** MangoHud's minimal HUD, the sampler (unpinned) and the harness counters
  all cost a little, but the same in every configuration. Profilers are never used in FPS runs.
- **XWayland and the compositor.** The game runs under XWayland on Hyprland 0.56. Compositor behaviour
  (direct scanout in fullscreen, tearing, VRR on DP-1) can affect frame pacing. `hyprctl` reported VRR on DP-1
  as on at one moment and off later the same night, so it changes at runtime (likely only for fullscreen
  windows). `collect_env.py` records VRR, tearing and direct-scanout state before and after every run; Hyprland
  settings are never changed during a campaign.
- **Particle Core in the real-world instance** changes particle ticking. Real-world results describe Particle
  Filter *alongside* Particle Core unless that factor is tested separately.
- **Live-server field observation** can't support claims of statistical significance; it shows plausibility only.
- **MangoHud quirks found in the pilot:** no log with `no_display`; GPU metrics zero; the log file is
  created at log *start* and the `_summary.csv` at log end (a script that stops at the first file truncates the log,
  which happened in the pilot and was fixed).

## 9. Reporting rules

- Every published number links to its campaign directory with raw logs, env snapshots, `run.json` files and the
  exact tool versions (git commit).
- State hardware, loader, Minecraft version, load level and CPU/GPU-bound label next to every figure.
- Include equivalent and inconclusive results, and any configuration where Particle Filter was slower.
- No marketing wording (README, CurseForge, homepage) until a campaign has been reviewed by the owner.
- Keep the project's AI disclosure: the benchmark code and documents were written by Claude; the owner
  reviews the design and approves campaigns.
