# Sodium / Sodium Extra in the harness: compatibility check (2026-11-09)

> **Functional, not a performance result.** This records which files were downloaded, why they are compatible,
> how they are configured so every configuration of the comparator series renders the same way, and the
> functional checks that passed. No timing was analysed. Measuring the comparison (PLAN.md Q7) is a performance
> run and stays blocked until the owner confirms the laptop's power-limit problems are fixed.

The owner approved downloading compatible releases into an isolated benchmark environment (2026-11-09). Both
jars live only in `benchmarks/harness/run/downloads/` (gitignored) and are copied into a launch's own `mods/`
folder when it uses `--modset sodium`. Nothing in the CurseForge instances was read beyond listing and hashing
their jars, and nothing there was changed.

## Files

| Mod | Modrinth version | File | SHA-256 |
|---|---|---|---|
| Sodium | `mc1.21.1-0.8.13-neoforge` (release, 2026-08-28) | `sodium-neoforge-0.8.13+mc1.21.1.jar` | `575d187b15328d7bddd8a9b1bee64333c22c14735f5780ec49eac0cd5503c08d` |
| Sodium Extra | `mc1.21.1-0.9.5+neoforge` (release, 2026-10-25) | `sodium-extra-neoforge-0.9.5+mc1.21.1.jar` | `37c33efdee16fc515392fd422b6ec8769cbc1079f6a2eb6cff2072411d511faf` |

- Found with the Modrinth API (`/v2/project/<slug>/version?loaders=["neoforge"]&game_versions=["1.21.1"]`); both
  are the newest releases for NeoForge 1.21.1. Downloaded from `cdn.modrinth.com`; each matches the SHA-512
  Modrinth publishes. Both are byte-identical to the copies in the "All the Mods 10 - ATM10" instance.
- `pfbench.py` pins both SHA-256 values and refuses a file that doesn't match. To set up another machine: download
  the two files from the version pages linked in `pfbench.py` (`RENDER_MODS`) into
  `benchmarks/harness/run/downloads/sodium/` and `.../sodium-extra/`.

## Compatibility (from the jars' `META-INF/neoforge.mods.toml`)

| Mod | Requires | We run |
|---|---|---|
| Sodium 0.8.13 | Minecraft `1.21.1`; NeoForge `[21.1.82,)`; incompatible with Embeddium | Minecraft 1.21.1, NeoForge 21.1.250, no Embeddium |
| Sodium Extra 0.9.5 | Minecraft `[1.21.1]`; Sodium `[0.8.13+mc1.21.1]` (exactly); Greenlight optional | Sodium 0.8.13 |

Modrinth lists Iris, Reese's Sodium Options and Greenlight as optional dependencies of Sodium Extra; none is needed
or installed. Sodium's jar is a jar-in-jar container: the mod itself and four Fabric API modules
(`fabric-api-base`, `fabric-block-view-api-v2`, `fabric-renderer-api-v1`, `fabric-rendering-data-attachment-v1`)
load from `META-INF/jarjar/`. The harness lists them in `harness.json["mod_files"]` (marked `nested`).

At startup Sodium applies two workarounds on this machine (`NVIDIA_THREADED_OPTIMIZATIONS_BROKEN`,
`NO_ERROR_CONTEXT_UNSUPPORTED`; `gradle.log`). They are the same in every configuration.

## Configuration: the same rendering environment everywhere

- **Sodium Extra's filter** is checked at `ParticleEngine.createParticle` HEAD (`MixinParticleEngine.addParticle`):
  `particles` off hides everything, otherwise `other.getOrDefault(typeId, true)`. The map is saved under the JSON key
  **`"other"`** (`@SerializedName("other")` on `ParticleSettings.otherMap`). A file with the wrong key would load
  without error and hide nothing.
- **Base configs** (`benchmarks/configs/sodium/`) are the defaults both mods wrote in a capture launch
  (`--sodium-defaults`, run `20261109T125234Z-A-typed-sodium-functional`), with one change: Sodium's
  donation-prompt flags are set to "seen", so no prompt can appear over a run. Every Sodium launch gets
  `sodium-options.json` unchanged and `sodium-extra-options.json` with exactly one map entry, the target:
  `true` in A, B, C and D, `false` in E. With the entry present in every configuration, the map lookup does the
  same work everywhere; only its value differs.
- **Rendering-environment hash** (`launch.json["render_environment"]`): SHA-256 over `options.txt`,
  `sodium-options.json`, `sodium-extra-options.json` without its particle map, and the rendering mods' jar
  hashes. `ingest_harness.py` puts it into every window's `environment`; `compare.py` excludes pairs whose hash differs.
- Sodium Extra installs its `createParticle` hook in every configuration of the series, so its per-spawn lookup is
  part of the shared baseline. The comparison this supports: E vs A (Sodium Extra's filtering, Particle Filter
  absent in both), C vs B (Particle Filter's filtering, with Sodium Extra present in both), and E vs C (each doing
  the same job). PLAN.md Q7 and IMPLEMENTATION.md §8 say which cases are comparable at all (typed, global only).

## Functional checks (60 FPS cap, hidden window, 5 s phases)

| Run | Configurations | Variant | Result |
|---|---|---|---|
| `20261109T125234Z-A-typed-sodium-functional` | A (capture of default configs; launcher wrote no Sodium configs) | typed | ran fine; recorded for the defaults. Its check fails one item because it ran on the harness build before jar-in-jar files were handled (the in-game jar list missed Sodium's nested jar) |
| `20261109T125444Z-A-typed-sodium-functional` | A + Sodium | typed | all checks pass; Sodium Extra kept `pfbench:target = true`; target created = attempts |
| `20261109T125526Z-E-typed-sodium-functional` | E | typed | all pass; `pfbench:target = false` kept; **target created = 0**, no target alive, control unchanged |
| `20261109T125615Z-C-B-typed-sodium-functional` | C → B + Sodium | typed | all pass; C created 0, B created = attempts |
| `20261109T125716Z-E-vanilla-sodium-functional` | E | vanilla (`minecraft:end_rod`) | all pass; `minecraft:end_rod = false` kept; created 0; no end_rod alive (translucent queue minus the control's own count) |

The rendering-environment hash was identical (`eac6b427…`) for the four controlled launches and differed for the
capture launch, as it should. One launch (`…125526Z-E-typed…`) got a 2560×1440 window on the hidden workspace while
the others got 2536×1390; window size is part of the environment, so `compare.py` would not pair it with them. (For
performance runs the plan is fullscreen on DP-1, checked every launch.)
