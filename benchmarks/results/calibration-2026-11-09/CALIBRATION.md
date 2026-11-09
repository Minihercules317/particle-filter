# Hardware validation, calibration and variance study: first session (2026-11-09)

> **EXPLORATORY. Not a Particle Filter result.** Calibration launches ran configuration **A** (Particle Filter not
> installed) and variance launches ran **B** only, so nothing here compares Particle Filter with anything. These
> numbers set methodology (load levels, window placement, block counts) and must never be quoted as performance
> figures. Raw launches stay in `benchmarks/harness/run/runs/` (gitignored): `cal-sweep1-A-visible`,
> `cal-sweep2-A-visible`, `cal-sweep3-A-hidden`, `var-B-hidden-L1` … `L5`. Scenarios: `scenarios/S1-calibration.toml`,
> `scenarios/S1-variance.toml` (copies of S1 that differ only in `[levels]` and id, so their data can't pair with S1).

The owner confirmed the CPU/GPU power-limit fix on 2026-11-09 and lifted the measurement gate, subject to validation.
`PFBENCH_PERFORMANCE_APPROVED=1` was used for these launches.

## 1. Hardware validation (read-only)

| Check | Finding | Verdict |
|---|---|---|
| CPU power limits | `intel-rapl-mmio:0` 145 W / 190 W in the performance profile (was stuck at 95/30). 240 s all-core load (24 busy processes): package ~165 W at first, ~152 W for 2.5 min, then **145 W** sustained (RAPL energy counters) | **Pass**: limits are applied and held |
| CPU thermals | All-core load reaches **105 °C (TjMax)** with continuous thermal throttling (clocks swing 1.5–3.7 GHz). In the game (51–62 W package) the maximum is 94–104 °C on single-core boost; package throttle events per 30 s window 0–8, core throttle events 0 | **Pass for this workload** (minor, now recorded per window). All-core loads would be thermally limited |
| GPU power | Enforced limit **70 W** (default 70, max 140), `nvidia-powerd` (Dynamic Boost) disabled: unchanged from the state the earlier power-limit notes describe. The game drew **≤ 58 W**, P0, graphics clock 2.7–2.8 GHz stable, no power-cap or thermal event reasons (only `0x400`, which `nvidia-smi -q` reports as "Reliability") | **Pass for this workload**: the 70 W cap is never reached. Not verified that the GPU "fix" changed anything; heavier GPU loads would hit 70 W |
| Memory / swap | ~19 GB available, swap barely used, memory PSI zero | Pass |
| Background activity | ~1 core of background CPU (terminal, Hyprland, Spotify + visualizer). The animated wallpaper (`owe-render`) runs on the **NVIDIA GPU** (C+G, 580 MiB) and only pauses for fullscreen windows; it never paused for the hidden game window | Recorded confounder; not changed (owner: don't reconfigure unrelated processes) |
| Graphics configuration | **Not stable while the owner uses the desktop** (section 3) | Fixed for hidden runs by `--monitor` (needs confirming) |

## 2. Calibration sweep (configuration A, typed variant)

Hidden window on DP-1's special workspace, 2560×1440 (`cal-sweep3-A-hidden`). Every window held 20 TPS.

| Attempts/tick | Live target | Avg FPS | Mean ms | Bound label (fixed tool) |
|---|---|---|---|---|
| 0–100 | 0–4,100 | ~1,540–1,580 | 0.63–0.65 | mixed (GPU 92–95%: effectively GPU-limited) |
| 200 | 8,200 | 1,437 | 0.696 | mixed (render thread 89%, GPU 92%) |
| 410–5,000 | 16,384 (queue full) | 891–910 | 1.10–1.12 | **cpu-bound** (render thread ~99% on a P-core, GPU 65–71%) |

- Particles below ~100/tick cost nothing measurable in A at this frame rate: the frame is GPU-limited at ~1,540 FPS.
  The useful load range is **200–410 attempts/tick**; above 410 the live count is capped at 16,384 and FPS plateaus
  (only construction/eviction cost grows; 5,000/tick still held 20 TPS).
- **No reduced-TPS windows occurred even at 5,000 attempts/tick** in A: the saturation dataset may stay empty for A/B.
- Visible fullscreen sweeps (1, 2) were not usable: see section 3.

## 3. Problems found and fixed

1. **Render-thread misidentification (analysis bug, fixed).** Three threads are named "Render thread" (the game's
   main thread plus NVIDIA driver workers that inherit the name). `system_data.window_system` averaged them: a thread
   at ~80–99% read as ~38%, so **no window could ever be labelled CPU-bound**. Now the lowest TID is the render
   thread; the others are reported separately (`same_name_threads`). Unit-tested.
2. **Visible fullscreen runs are uncontrolled on a desktop in use.** In sweep 1 FPS sat at 250–400 (GPU 28%, render
   thread 75%: presentation-limited) for 2 min, then jumped to ~1,500 within one second without any workload change;
   in sweep 2 the "fullscreen" window was 1280×707. The launcher recorded no compositor window state for visible runs
   (it queried before the window existed; fixed: `wait_for_window`).
3. **Hidden-window geometry followed the owner's focus.** The special workspace belongs to the focused monitor:
   `var-B-hidden-L3..L5` ran at 2560×1600 (laptop panel) instead of 2560×1440, so they can't pair with L1–L2. Fixed:
   `pfbench.py run/campaign --hide --monitor <name>` pins the workspace (Hyprland
   `hl.dsp.workspace.move`). **Not yet exercised in a launch.**
4. **Sampler coverage check raced the game's exit** (last row 20 ms before the end of the last window). Now allows
   one sampler interval of slack (ingest counts whole intervals only).
5. Added per-window thermal-throttle event counts (sampler schema 2) and the GPU's enforced power limit to the sampler.
6. **Statistics:** verdicts now use a paired Student-t interval; the percentile bootstrap (too narrow at 8–12 blocks)
   is still reported next to it (`compare.py`, `t_quantile` checked against t tables).

## 4. Variance study (configuration B, 200 attempts/tick, 6 × 30 s windows per launch, hidden)

`tools/variance_study.py`. "Null pairs" are differences between consecutive windows of the same configuration: the
paired difference a within-launch comparison would see if the configurations were identical.

| Group | Launches | Null-pair SD | Within-launch SD | Between-launch SD | Notes |
|---|---|---|---|---|---|
| Hidden on DP-1 (NVIDIA-driven monitor), 2560×1440 | L1, L2 | **3.8%** | 2.6% | 0.9% | one window +8.8%, one +2.7% |
| Hidden on eDP-2 (iGPU-driven panel), 2560×1600 | L3, L4, L5 | **0.17%** | 0.11% | 0.27% | no position effect (all positions within ±0.07%) |

Interpretation of this first group was confounded with time and with what the owner was doing; the head-to-head
below replaced it.

### 4b. Head-to-head with the placement pinned (`--hide --monitor`, balanced order eDP-2, DP-1, DP-1, eDP-2, eDP-2, DP-1)

| Group | Launches | FPS per launch | Null-pair SD | Between-launch SD |
|---|---|---|---|---|
| eDP-2, 2560×1600 | M1, M4, M5 | 1,484 / 1,361 / 1,363 | 1.38% | 4.9% (M1) |
| DP-1, 2560×1440 | M2, M3, M6 | 1,452 / 1,473 / 1,462 | 0.67% | 0.72% |

- **The monitor is not what matters; episodic background GPU contention is.** M1's first two windows ran at 1,640 FPS
  with the GPU at 99.9%; from window 3 on it settled at ~1,405 FPS and ~92%, the level every other launch showed.
  Something else on the NVIDIA GPU usually takes a slice (desktop compositing, the animated wallpaper, other GPU
  clients) and was briefly absent. `nvidia-smi pmon` gives no per-process share on this driver, so the culprit isn't
  identified, and per the owner's instruction nothing was changed.
- **Quiet launches agree closely on either monitor:** on eDP-2, L3, L4, L5, M4 and M5 span 1,361-1,369 FPS (≈ 0.25%);
  on DP-1 the spread was 0.72%. Position effects in quiet launches stay within ±0.4%: the warmup is adequate.
- **Decisions (scenario version 2):** hidden window pinned to **eDP-2** (lowest spread among quiet launches, and the
  monitor the owner isn't working on); **official campaigns run unattended**; GPU-bound threshold 90%;
  **12 blocks** within-launch, **16 blocks** install-level (sized for σ ≈ 0.7%, half-widths ≈ ±0.45-0.5%, inside
  the ±1% margin). If a campaign's variance turns out larger, its intervals widen honestly: the t-interval and the
  counterbalanced order keep it unbiased, and an inconclusive verdict is reported as such.

### 4c. Harness frame log vs MangoHud (phase 2b)

Both visible sweeps ran under MangoHud 0.8.4 (`mangohud/`), an independent per-frame log of the same launches. Aligned
at the end (MangoHud logs 528 and 1,496 extra start-up frames), the mean frame time per measured window agrees within
**+0.08% to +0.32% in 20 of 22 windows** (MangoHud slightly higher: the tools time frames at different points); the
two exceptions are the launches' edge windows (−0.76%, −1.26%), where the frame-count alignment is least certain.
Per-frame values differ by ~0.35 ms on ~1 ms frames (different measurement points, same means). PLAN.md's criterion
(means within 1%) passes; a constant bias of ~0.2% cancels in paired differences.

## 5. Status after this session

Done: display mode, load levels, phase lengths, block counts, bound threshold and verdict statistics are frozen in
scenario version 2 and PLAN.md; every campaign design passes `campaign --dry-run`. The harness-vs-MangoHud check passed (4c).
Not done: B0 and the Q6 configurations are not launchable (not needed for the pre-registered campaigns).
