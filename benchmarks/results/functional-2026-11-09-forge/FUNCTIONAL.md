# Forge 1.20.1 harness port: functional validation (2026-11-09)

> **Functional, not a performance result.** Every launch ran at a 60 FPS cap, in a window on a hidden Hyprland
> workspace, with 5-second phases. They check that the Forge 1.20.1 build of the harness loads, runs the phase plan,
> switches Particle Filter's modes and counts particles correctly, that the launcher's system data works under Forge,
> and that the NeoForge 1.21.1 build still works after the shared code was split out. No frame time, TPS, sampler,
> GPU, JIT or GC figure was analysed or is reported. Performance runs stay blocked until the owner confirms the
> laptop's power-limit problems are fixed (HANDOFF.md).

Harness: pfbench **0.4.0** (shared sources in `harness/src/main`, per-loader `Platform`). Launched from commit
`bee31ca` with the uncommitted changes that this record's commit contains (listed in each
`plan.json["provenance"]["git_dirty_paths"]`); the harness-source, launcher and tools hashes of every launch equal
those of the committed tree.

| Loader | Versions | Particle Filter jar in `mods/` |
|---|---|---|
| Forge | Minecraft 1.20.1, Forge 47.4.28, Temurin 17.0.21 | `versions/1.20.1-forge/build/devlibs/particlefilter-forge-1.2.0+1.20.1.jar` (named mappings, SHA-256 `613c58b6…`), built 0.6 s apart from the release jar `5d7e1bf5…` it was reobfuscated into; the copy in `build/libs/1.2.0/` has the same hash |
| NeoForge | Minecraft 1.21.1, NeoForge 21.1.250, Temurin 21.0.12 | the 1.2.0 release jar (`0806e94c…`) |

Forge template world: `harness/run/template-forge-1.20.1/pfbench`, created by `pfbench.py template --loader forge`
(tree SHA-256 `6f390667…`), copied fresh into every launch.

## Launches

| Run | Configurations (moderate) | Variant | Checks |
|---|---|---|---|
| `functional-0.4.0-forge-A-typed` | A | typed | 45/45 pass |
| `functional-0.4.0-forge-C-B-D-typed` | C, B, D | typed | 81/81 pass |
| `functional-0.4.0-forge-C-miss-typed` | C-miss | typed | 51/51 pass |
| `functional-0.4.0-forge-C-B-untyped` | C, B | untyped | 67/67 pass |
| `functional-0.4.0-forge-C-B-block_dust` | C, B | block_dust | 66/66 pass |
| `functional-0.4.0-forge-B-C-vanilla` | B, C | vanilla | 64/64 pass |
| `functional-0.4.0-neoforge-C-B-typed` | C, B | typed | 65/65 pass (regression check of the shared-code split) |

All `--functional --hide`, `--modset vanilla`. Kept here per launch: `check.json`, `harness.json`, `launch.json`,
`plan.json`, `env-before.json`, `env-after.json` and `system/meta.json` (home paths written as `~`). Frame logs,
tick logs and sampler CSVs stay in the gitignored `harness/run/runs/` and were not analysed.

What the checks cover on Forge (same checks as NeoForge, plus the Forge-specific ones in bold):
- the template world, spectator camera, Particles = All, window size unchanged within the launch;
- every mode switch took effect (`EVERYWHERE`/`NONE`/`ZONES` read back by reflection through `PfControl`);
- particle counters: filtered configurations create 0 targets of all attempts, B creates all of them, controls are
  always created, no target carry-over between phases, exact steady-state alive counts, the translucent queue holds
  the control only (counts, not timings);
- warmup cycles every measured configuration, settles ≥ longest particle life + 2 ticks, JIT time logged;
- the jars in `mods/` (hashed in game) are exactly what the launcher copied; Sodium/Sodium Extra absent;
- **the harness ran on the planned loader and Minecraft version** (`harness.json["versions"]["loader"]`);
- **the named-mappings jar was built in the same Gradle run as the release jar (≤ 60 s apart) and that release jar is
  the collected one**;
- environment snapshots before/after, power settings unchanged, sampler without `--self-cpu` and covering every
  measured phase, clock origin recorded, `gc.log` parses.

Pipeline: `ingest_harness.py --allow-functional --block 1` on `functional-0.4.0-forge-C-B-D-typed`, then
`compare.py --allow-functional --reference B`, ran end to end in a scratch campaign (not kept). The run.json
environment carries `loader: forge`, `minecraft: 1.20.1`, `forge: 47.4.28` and Java 17, so Forge and NeoForge windows
can't be paired; the report is titled "PIPELINE TEST, NOT A RESULT". No figure from it was read.

## Process note

The launches were made by one session, and this record, the archive above and the commit by the next one, from the
existing `check.json` files; nothing was re-run. Earlier launches named `functional-forge-*` (harness 0.3.0, during
development of the port) are superseded by the 0.4.0 launches and not archived.

## Not checked (blocked or out of scope)

- Anything about timing, including whether the Forge and NeoForge dev runtimes behave alike under load (phase 2b+).
- A production Forge install running the reobfuscated release jar itself (HANDOFF.md, next task 3; needs approval).
- Configuration E on Forge: no rendering mods are approved for Forge 1.20.1.
