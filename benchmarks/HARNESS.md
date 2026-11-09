# pfbench harness: design

A separate, benchmark-only client mod that creates a known particle workload, controls the measurement
phases, and logs frame times and particle counters. It is **not** part of Particle Filter: it lives in its
own Gradle build under `benchmarks/harness/`, has its own mod id (`pfbench`), and is never included in
release jars, CI release artifacts or CurseForge uploads. Particle Filter gets no benchmark code or hooks.

Status: **built for NeoForge 1.21.1 and functionally validated** (2026-11-09; analysis pipeline, carry-over and warmup safeguards, provenance and the Sodium mod set added the same day): code and usage in
[harness/README.md](harness/README.md), which also lists where the implementation deviates from this design and
why; checks in [results/functional-2026-11-09](results/functional-2026-11-09/FUNCTIONAL.md). Any timing or
performance measurement waits until the owner confirms the laptop's power-limit problems are fixed.

## Why a harness is needed

Investigation showed that the controlled benchmark needs things no existing tool provides:

- **A repeatable load.** Vanilla particles have random lifetimes from an unseeded `RandomSource`, and
  natural sources (machines, mobs, weather) vary. The harness spawns particles at a fixed rate, from
  fixed points, with fixed lifetimes, so the number alive is rate × lifetime (until the 16,384-per-render-type
  limit; see IMPLEMENTATION.md §1).
- **Counters MangoHud can't see:** attempts, particles created, particles alive per render type.
- **Phase control:** warmup, measurement windows and Particle Filter mode switches at known times, so frame logs can be cut
  exactly and paired.
- **A frame log aligned to those phases.** MangoHud stamps its log only with time since the log started and
  a file name to the second. (MangoHud is kept as an independent cross-check and for GPU/CPU columns.)

## Scope (first version: NeoForge 1.21.1 only)

Forge 1.20.1 follows once the NeoForge numbers are trusted. The code is small and could be shared with
Stonecutter, but keeping the harness out of the release build matters more than avoiding duplication.

*Update 2026-11-09:* the Forge 1.20.1 build exists (`benchmarks/harness-forge`, built ahead of the NeoForge numbers
on the owner's instruction, functional checks only). It shares the harness code without Stonecutter: a second
Gradle build compiles `harness/src/main` plus a small per-loader `Platform` class; see harness/README.md.

### 1. Workload generator

Runs on the client tick, inside a singleplayer world:

- **Emitter grid:** N×N points on a vertical plane 12-16 blocks in front of a locked camera, entirely
  inside the frustum and inside vanilla's 32-block particle distance. Positions and velocities come from
  `java.util.Random(seed)`, so every run spawns the same sequence.
- **Particle types** (registered by the harness):
  - `pfbench:target`: the particle the configurations filter. Fixed lifetime (default 40 ticks),
    no physics, no motion, a small opaque sprite, render type `PARTICLE_SHEET_OPAQUE`.
  - `pfbench:control`: identical but never filtered, render type `PARTICLE_SHEET_TRANSLUCENT`. It stays
    on screen in every configuration, so "the rest of the scene" is constant. A separate render type means it has
    its own 16,384 queue and the target can't evict it.
  - **untyped variant:** the harness constructs `pfbench.UntypedParticle` itself and calls
    `ParticleEngine.add`, like mods that spawn particles without a type. It exercises Particle Filter's class-name path.
  - **block-dust variant:** `BlockParticleOption(minecraft:block, <a fixed block>)` through
    `ClientLevel.addParticle`. It exercises the block-rule path.
  - **vanilla variant:** e.g. `minecraft:flame` or `minecraft:end_rod`, for realism checks (random
    lifetimes; live counts are logged rather than assumed).
- **Spawn path:** `ClientLevel.addParticle(...)`, the normal path, so vanilla's distance and settings checks apply
  as in play. Particles = All is enforced and logged.
- **Rate:** attempts per tick, constant within a phase. Fractional rates use an accumulator, not randomness.

### 2. Counters (per tick, in memory, written at the end)

- `attempts`: requests the generator made, by variant.
- `created`: harness mixin at `ParticleEngine.createParticle` RETURN with a non-null result (Particle
  Filter's HEAD cancellation returns early, so cancelled attempts never reach RETURN), plus a counter at
  `add` for the untyped variant.
- `alive`: size of each render type's queue after `ParticleEngine.tick()`, via an accessor mixin on
  `ParticleEngine.particles`.
- Client and integrated-server tick durations, GC count and time (`GarbageCollectorMXBean`), heap used.

The counters are always on in every configuration (including A), so their small cost is part of the shared baseline.

### 3. Frame log

Mixin at the end of `Minecraft.runTick`: `System.nanoTime()` at each frame boundary into a preallocated
`long[]` (no allocation per frame), plus `Minecraft.getFrameTimeNs()` (CPU time of the frame, without the
limiter wait). Written as `frames.csv` with `t_ns,frame_ns,cpu_frame_ns,phase` at the end of the run;
`benchmarks/tools/frametimes.py` already reads this layout (`frame_ns` column).

### 4. Phases and automation

- Launch with `--quickPlaySingleplayer <world>` (vanilla 1.20+ argument) from a dedicated run
  configuration in the harness build, so no clicking is needed.
- Fixed world: a copy of a template world made by the harness build (flat "void" preset, fixed seed,
  `doDaylightCycle false`, `doWeatherCycle false`, `doMobSpawning false`, `randomTickSpeed 0`, time 6000,
  clear weather). Each run starts from a fresh copy, so no run modifies the template.
- Camera: player at fixed coordinates in spectator mode with yaw and pitch reset every tick. HUD hidden (F1).
- Script (`benchmarks/scenarios/*.toml`): ordered phases, e.g. `settle 20 s (chunks rendered) → warmup
  60 s with workload → [switch mode → settle 15 s → measure 30 s] × k → quit`. The harness writes a
  `phases.csv` with start/end `t_ns` for every phase.
- **Particle Filter mode switching** (configs B/C/D in one launch): the harness calls Particle Filter's
  existing `PfConfig.setMode(FilterMode)` reflectively when that mod is present. Active rules
  `NONE` compiles an empty snapshot (`FilterRules.rebuild`), which is the same code path as no rules.
  The harness changes only the mode, never the rules.
- Optional: start/stop MangoHud logging at phase boundaries. `mangohudctl set log_session` was not
  verified with the OpenGL layer during the pilot; until it is, MangoHud logs the whole session and the
  harness's own frame log is the primary source.

### 5. Output

One directory per run: `frames.csv`, `ticks.csv` (counters), `phases.csv`, `harness.json` (versions, seed,
scenario hash, options that matter, Particle Filter mode at each phase). `benchmarks/tools` turns these into
per-window `summary.json` files (one per phase) and `run.json` entries for `compare.py`, with workload counters
in `run.json["workload"]` so `compare.py` can reject pairs whose unfiltered workload differs.

## Overhead and isolation rules

- No logging or file I/O during measured phases; everything is buffered and written at the end.
- Counters are plain `long` fields on the client thread. The `alive` count reads queue sizes once per tick.
- The harness is present in **every** configuration of a comparison, so its cost is in the baseline.
  It is never compared against a run without it.
- Diagnostic profiling (spark, JFR, async-profiler) is done in separate runs and never mixed into FPS runs.

## Risks

- `getFrameTimeNs` semantics differ between 1.20.1 and 1.21.1; the wall-clock interval is the primary
  metric and the CPU time a diagnostic.
- Spectator-mode and locked-camera tricks may differ between versions; verify the frustum in a screenshot
  during harness bring-up, never during measured runs.
- If the harness's own particles differ too much from real modded particles, the synthetic results
  generalize poorly. That is why vanilla and untyped variants exist, and why the real-world scenario
  stays essential.
