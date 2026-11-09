# pfbench harness: functional bring-up (2026-11-09)

> **Functional, not a performance result.** These launches checked that the harness works: it builds,
> launches, loads the template world, spawns its workload, switches Particle Filter's mode, counts
> correctly and writes files that parse. Every launch ran at a 60 FPS cap, in a window on a hidden Hyprland
> workspace, with 5-second phases. No timing was analysed or is reported here. Performance runs stay blocked
> until the owner confirms the laptop's power-limit problems are fixed (HANDOFF.md).

Harness: `benchmarks/harness/` (pfbench 0.1.0, NeoForge 21.1.250, Minecraft 1.21.1, Temurin 21.0.12).
Particle Filter: the 1.2.0 release jar (`build/libs/1.2.0/particlefilter-neoforge-1.2.0+1.21.1.jar`,
SHA-256 in each `launch.json`). Scenario: `S1-synthetic.toml` as committed with this record.

## What was checked

`pfbench.py check` runs these checks after every launch and writes `check.json`:

- The harness finished without an error and wrote `frames.csv`, `ticks.csv`, `phases.csv` and `harness.json`.
- The world is the template (`pfbench`), the player is a spectator at the camera position, Particles = All.
- No frame or tick rows were dropped for lack of buffer space.
- Particle Filter is present exactly when the configuration needs it, and every requested mode switch took effect.
- Every phase logged exactly its planned number of ticks (20 per second).
- Per phase (settle after a switch, and measure): **target created = attempts** in A, B and C-miss, and
  **target created = 0** in C and D; **control created = attempts** in every configuration.
- Per measure tick, for the fixed-lifetime particles: **target alive = rate × (lifetime + 1)**
  (50 × 41 = 2,050) when unfiltered and 0 when filtered; **control alive = 25 × 41 = 1,025**. (A particle
  with a 40-tick lifetime is in 41 consecutive post-tick counts: the tick it is added and 40 more.)
- `frames.csv` parses with `tools/frametimes.py` (format `generic-ns`, no skipped rows, row count equal to
  the harness's own count) and every phase has frames. Only row counts are printed.

## Launches

| Run | Configurations (level: moderate, 50 attempts/tick) | Variant | Result |
|---|---|---|---|
| `20261109T095221Z-C-B-D-typed-functional` | C → B → D in one launch | typed `pfbench:target` | 41/41 checks pass |
| `20261109T095327Z-A-typed-functional` | A (Particle Filter not installed) | typed | 21/21 pass |
| `20261109T095404Z-C-miss-typed-functional` | C-miss | typed | **failed to start** (see below) |
| `20261109T100419Z-C-miss-typed-functional` | C-miss (10 non-matching rules) | typed | 23/23 pass |
| `20261109T100504Z-C-B-D-untyped-functional` | C → B → D | untyped `pfbench.UntypedParticle` via `ParticleEngine.add` | 41/41 pass |
| `20261109T100604Z-C-B-block_dust-functional` | C → B | block dust (`minecraft:block`, stone; block rule) | 30/30 pass |
| `20261109T100654Z-C-B-vanilla-functional` | C → B | vanilla `minecraft:end_rod` | 28/28 pass |

Block dust and end_rod have random lifetimes (vanilla code), so their alive counts are logged but not
checked; end_rod also shares the translucent queue with the control particle, so the control alive check is
skipped for that variant.

`runs/<run>/` holds each launch's `check.json`, `harness.json` and `launch.json`. The frame and tick logs
stay in the gitignored `benchmarks/harness/run/` directory and are not part of this record.

## Problem found and fixed

The first C-miss launch hung: NeoForge's early loading window failed to hand over to the game window
("We seem to be having trouble handing off the window, tried for 1 second") and waited on an error dialog
inside the hidden workspace. The launcher had moved the window while it was still the loading window.
The stuck client was stopped by hand (SIGTERM was ignored, SIGKILL worked); its run directory is kept as-is.

Fix (`pfbench.py`): every run directory now gets its own `config/fml.toml` with `earlyWindowControl = false`,
so a single window exists from start to end, and the launcher hides the window only once its title is the
game's. The four launches after the fix ran without problems. The first two launches (C-B-D typed, A) ran
with the early window still on.

## Frustum check (HARNESS.md, Risks)

`frustum-B.png` (configuration B: red target and blue control) and `frustum-C.png` (C: control only) are the
harness's screenshots at the end of those measure phases. The whole emitter plane is in view with a margin on
every side. This needed one scenario change: the camera was raised to y 102.38 (eye at 104.0, the plane's
centre). By calculation, at the old height (eye ≈ 101.6, 13.5 blocks from the plane) a 70° vertical field of
view reaches only y ≈ 111.1, so the top ~1 block of the plane (up to y 112) would have been out of view; the
old height was never launched. The window was 2536 × 1390 (tiled on the special workspace). Minecraft's field
of view is vertical, so the vertical coverage doesn't depend on the window; fullscreen 2560 × 1440 has nearly
the same aspect ratio, and the plane (x −8 to 8) uses about half the horizontal view.
