# Pipeline pilot, 2026-11-09

**This pilot tested the measurement pipeline. It is not evidence about Particle Filter's performance.**
The game sat on its title screen, which Minecraft caps at 60 FPS while no world is loaded
(`Minecraft.getFramerateLimit`), so the right answer was known in advance (~16.67 ms per frame).

## Setup

- Repo dev client, NeoForge 1.21.1 (`./gradlew :1.21.1-neoforge:runClient`), gitignored `run/1.21.1-neoforge`;
  Particle Filter 1.2.0 from source (commit `bcba10f`) with that directory's existing dev config. No world loaded.
- Launched by `benchmarks/tools/pilot_devclient.sh OUT 60 30 --hide`: MangoHud 0.8.4 (host package),
  `fps_only,background_alpha=0,log_interval=0,autostart_log=60,log_duration=30`; window moved to a hidden
  Hyprland special workspace; `sample_system.py` pinned itself (only the sampler, not the game) to CPU 23;
  environment snapshots before and after. After the pilot the owner prohibited CPU affinity changes, so the
  script no longer pins anything.
- Hardware and software: see `runs/*/env-before.json`.

## Runs

| Run | Frames | Window | Avg FPS | Mean ms | Median ms | p99 ms | 1% low FPS | 0.1% low FPS | Checks |
|---|---|---|---|---|---|---|---|---|---|
| p1-title-hidden | 1790 | 30.0 s | 59.67 | 16.759 | 16.754 | 18.086 | 53.0 | 46.2 | frame_limited (expected) |
| p2-title-hidden | 1790 | 30.0 s | 59.68 | 16.756 | 16.757 | 18.052 | 54.2 | 52.3 | frame_limited (expected) |
| p3-title-hidden | 1790 | 30.0 s | 59.68 | 16.757 | 16.758 | 17.978 | 55.1 | 53.9 | frame_limited (expected); rerun to verify the script after adding `window_after` and `PILOT_BLOCK` |
| p0a / p0b-partial-early-stop | 381 / 324 | 6.4 / 5.4 s | n/a | n/a | n/a | n/a | n/a | n/a | too_few_frames, window_short: excluded (script bug, below) |

## What the pilot established

- **Frame times are collected reliably:** three separate launches each gave exactly 30 s of per-frame data.
  The sum of frame times matched MangoHud's `elapsed` span within 2%, so every frame was logged.
- **Parsing works on the real format.** MangoHud 0.8.4 writes a `cpu_mhz` column the documentation doesn't list;
  the parser handles it.
- **Metrics are plausible:** mean frame time 16.76 ms against the 16.67 ms limiter target. Minecraft's
  limiter overshoots slightly (59.7 FPS).
- **Outputs are reproducible:** re-running `frametimes.py` on the same logs gives identical `summary.json`
  contents.
- **Metadata is captured:** env snapshots before and after, window state, thread and GPU samples.
- **Obvious errors are detected:** the frame-cap warning fired on both runs. The truncated logs
  (p0a/p0b) failed with `too_few_frames` and `window_short`. A probe window that overlapped mod loading
  failed with `freeze` and `drift` (`probes/hud-visible`).

## Problems found (all fixed or recorded in PLAN.md)

1. **MangoHud `no_display` disables logging.** With `no_display`, no CSV was written at all (`probes/no-display`).
   With a visible HUD (`probes/hud-visible`) or the minimal `fps_only` HUD (`probes/fps-only`), logging works.
   Benchmarks use the minimal HUD in every configuration.
2. **MangoHud creates the CSV when logging starts** and writes `*_summary.csv` when it ends. The first
   version of the script stopped the game as soon as the CSV appeared, which truncated the logs (p0a/p0b,
   kept and marked excluded). The script now waits for the summary file.
3. **MangoHud's GPU columns are all zero** on this hybrid laptop. The cause wasn't investigated (it may be
   watching the Intel iGPU rather than the NVIDIA GPU that renders). GPU data comes from `nvidia-smi`
   instead (`runs/*/system/gpu.csv`).
4. **The window opened differently in each launch:** tiled 1261×1390 on workspace 12 in p1, fullscreen 2560×1440
   on workspace 15 in p2 (`run.json` → `window`). In p3 the same window was 1261×1390 while loading and
   2536×1390 after it moved to the special workspace (`window_after`), so its size follows the tiling layout. Fine for a frame-capped menu; for real runs the resolution
   must be forced and checked every launch.
5. **Hybrid CPU placement:** the render thread ran on an E-core (CPUs 8-23) in ~80% of 1-second samples
   in all three runs (77-81%). At this light load that's expected scheduler behaviour, but it is a source of variance under load.
   Pinning is not allowed (owner decision), so placement is recorded per thread and its variance accepted.
6. Process discovery: the game JVM is found by `net.neoforged.devlaunch.Main`. `pgrep -f` must not run from a
   shell whose own command line contains the pattern (it matched itself once during the probes).
7. Hyprland 0.56 needs Lua dispatchers (`hl.dsp.window.move({...})`); the old
   `movetoworkspacesilent` syntax fails.

## Not established yet

- Behaviour at uncapped frame rates, and whether a hidden window is throttled when not frame-capped.
- MangoHud logging inside the CurseForge Flatpak.
- Anything in a loaded world, with particles, or with Particle Filter rules active.
