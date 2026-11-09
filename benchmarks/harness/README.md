# pfbench: the synthetic benchmark harness

A benchmark-only client mod and launcher that implement [HARNESS.md](../HARNESS.md) for **NeoForge 1.21.1**.
It is **not** part of Particle Filter. It's a separate Gradle build that the release build (`settings.gradle.kts`
at the repository root), CI and CurseForge uploads never include. Particle Filter contains no benchmark code.

> **Status: built and functionally validated** ([results/functional-2026-11-09](../results/functional-2026-11-09/FUNCTIONAL.md)).
> **No performance runs yet:** they are blocked until the owner confirms the laptop's power-limit problems are
> fixed. The launcher refuses a non-`--functional` run unless `PFBENCH_PERFORMANCE_APPROVED=1` is set, which
> must only happen after that confirmation.

Written by Claude (AI), like the rest of the benchmark suite and the mod.

## Use

From the repository root (Java comes from mise; Gradle runs offline from the cache the main build filled):

```sh
mise exec -- ./gradlew -p benchmarks/harness compileJava        # build only
python3 benchmarks/harness/pfbench.py template --hide           # once: create the template void world
python3 benchmarks/harness/pfbench.py run --functional --hide --sequence C:moderate,B:moderate,D:moderate
python3 benchmarks/harness/pfbench.py check benchmarks/harness/run/runs/<run-id>
```

- `--sequence` lists `CONFIG:LEVEL` entries ([PLAN.md §2](../PLAN.md) ids; levels from the scenario's `[levels]`).
  One launch has one install state: `A` alone (Particle Filter not installed), `B`/`C`/`D` together (one
  config file, the harness switches the Active rules mode), or `C-miss` alone.
- `--variant typed|untyped|block_dust|vanilla` picks what the target is (scenario `[variants]`). The launcher
  writes a Particle Filter config whose rule matches that target (`tools/make_pf_config.py`). `block_dust`
  works with B/C only: block rules are global, so there is no D.
- `--functional`: 5 s phases (3 s settle after a switch), 60 FPS cap, windowed (1280×720 requested; Hyprland's
  tiling may resize it), screenshots at the
  end of each measured phase, and every output labelled `functional`.
- `--hide` moves the game window to the hidden Hyprland special workspace `pfbench` (functional runs).
- Particle Filter's jar defaults to the repository's built NeoForge jar for the current `mod.version`
  (`mise exec -- ./gradlew :1.21.1-neoforge:build buildAndCollect`); `--pf-jar` picks another.

Everything a launch writes is under `benchmarks/harness/run/` (gitignored): the template in `run/template/`, and one
directory per launch in `run/runs/<run-id>/` with the game directory in `game/`. Nothing touches a Minecraft
installation, world or system setting.

## How a launch works

1. `pfbench.py` reads the scenario (`../scenarios/S1-synthetic.toml`) and writes `plan.json`: the full phase list
   with tick counts, rates and modes. The mod executes the plan literally and makes no decisions of its own.
2. It prepares the game directory: `options.txt` from the scenario's `[options]`, `config/fml.toml` with
   NeoForge's early loading window off, a fresh copy of the template world, and for B/C/D/C-miss Particle
   Filter's release jar in `mods/` plus its config in `config/particlefilter.json`.
3. It starts `./gradlew -p benchmarks/harness runClient` with Gradle properties that point the run at that
   directory and plan, and open the world with `--quickPlaySingleplayer pfbench`.
4. In game, the harness waits for the world, makes the player a spectator at the camera position, waits 1 s,
   then runs the phases: `load-settle` → `warmup` → for each entry, `NN-CONFIG-LEVEL-settle` (mode switch at its
   start) → `NN-CONFIG-LEVEL` (measure). Then it writes its files and quits the game.
5. `pfbench.py check` runs the functional checks (FUNCTIONAL.md lists them) and writes `check.json`. It prints
   no timings.

## Outputs (per launch)

| File | Content |
|---|---|
| `plan.json` | What was executed (its SHA-256 is in `harness.json`) |
| `frames.csv` | `t_ns,frame_ns,cpu_frame_ns,phase`: one row per frame from the first phase to the last. `frame_ns` is the interval between frame ends (end of `Minecraft.runTick`, after swap and limiter); `cpu_frame_ns` is `Minecraft.getFrameTimeNs()` (render to blit), a diagnostic. `tools/frametimes.py` reads it |
| `ticks.csv` | One row per client tick: attempts (target, control); created (target, control, untyped, block, vanilla, other); alive per render type (opaque, translucent, terrain, total); client tick ns; integrated server average tick ns; GC count and time; heap used |
| `phases.csv` | Per phase: role, configuration, level, requested mode and the mode after the switch, start/end `t_ns`, ticks, rate |
| `harness.json` | Label, versions, mod list, seed, variant, setup state (world, gamerules, player, options, window), Particle Filter mode per phase, buffer overflow counts, notes |
| `launch.json` | Launcher side: sequence, scenario and template hashes, Particle Filter jar and config SHA-256, window state |
| `check.json` | Functional check results |
| `game/gc.log` | JVM GC log (scenario `[jvm]` arguments) |

## Design notes and deviations from HARNESS.md

- **Particle Filter comes from `mods/`, not the Gradle classpath.** The launcher copies the release jar into each
  run's `mods/` folder for B/C/D/C-miss and leaves it out for A. One harness build then serves every configuration,
  and the jar under test is the one players run (its SHA-256 is recorded). The harness reaches it only through
  `PfConfig.setMode`/`PfConfig.mode` by reflection, and changes only the mode, never the rules.
- **Template world:** created in game by `pfbench.py template` (`WorldOpenFlows.createFreshLevel` with the
  `minecraft:the_void` flat preset, the scenario's seed and gamerules, then time 6000 and clear weather).
  Every launch gets a fresh copy; the template is never opened. Its tree hash is in `run/template/template.json`.
- **Counters:** `created` is counted at `ParticleEngine.createParticle` RETURN (non-null result) by particle type;
  untyped particles at `ParticleEngine.add` TAIL. A cancelling HEAD injector (Particle Filter's) returns before
  either point, so filtered spawns never count. `alive` is the size of each render type's queue, read at the end of
  `Minecraft.tick` (after `ParticleEngine.tick`). Fixed-lifetime particles are alive in **lifetime + 1** post-tick
  counts, so the steady state is `rate × 41` at a 40-tick lifetime.
- **Workload:** spawning happens at the start of the client tick (`ClientTickEvent.Pre`). Phases are counted in
  ticks, so every run gets the same number of attempts. Emitters are a 16×16 grid with jitter from
  `java.util.Random(seed)` that stays inside each cell; the target and control walk the grid round-robin from
  different starting points. The control spawns one block behind the target plane (`plane_z_offset`).
- **Particles:** `pfbench:target` (opaque, red) and `pfbench:control` (translucent, blue) reuse Minecraft's
  `generic_0` sprite at a fixed size, with no motion, physics or randomness. The untyped variant goes straight to
  `ParticleEngine.add`, so it skips vanilla's 32-block and Particles-setting checks, as mods that spawn that way do.
- **Camera:** the player position and rotation are reset every tick (spectator, HUD hidden). The camera was raised to
  y 102.38 so the whole emitter plane is in view (FUNCTIONAL.md, frustum check).
- **No early loading window** (`earlyWindowControl = false` in the run's own `config/fml.toml`): the window
  handoff hung once when the window had been hidden (FUNCTIONAL.md). It also makes the window the same in every run.
- **No I/O while phases run**, except screenshots in functional plans. Frames and ticks go into preallocated
  arrays (room for 2,000 FPS); overflow is counted in `harness.json` and fails the check.

## Not done yet

- Splitting `frames.csv` into per-phase `summary.json` files and writing `run.json` entries (with the workload
  counters) for `tools/compare.py`. Needed before any campaign; analysis tooling only.
- Integrating `tools/collect_env.py` and `tools/sample_system.py` into the launcher, and optional MangoHud wrapping.
- Forge 1.20.1 port; Sodium/Sodium Extra compatibility check (HANDOFF.md, next steps).
- Phase 2b checks (harness frame log vs MangoHud, hidden-window throttling, fullscreen on DP-1): they compare
  timings, so they wait for the power-limit confirmation.
