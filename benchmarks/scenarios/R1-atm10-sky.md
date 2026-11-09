# R1: real-world scenario, All the Mods 10: To the Sky (ON HOLD)

> **Owner decision, 2026-11-09:** do **not** copy ATM10 server worlds, do **not** benchmark against the live
> server, and do **not** modify existing Minecraft installations or worlds. This scenario is on hold until
> the owner picks another route. The plan below is kept as design notes only; its "Plan" steps 1-2 and the
> field-observation fallback are currently **not allowed**.

## Environment as found (2026-11-09, read-only inspection)

- Instance: `~/Documents/curseforge/minecraft/Instances/All the Mods 10: To the Sky   ATM10SKY`
  (CurseForge Flatpak), NeoForge 21.1.250, Minecraft 1.21.1, 331 mod jars, 12 GB heap,
  `-XX:+UseG1GC`.
- Relevant mods: Sodium 0.6.13, Iris 1.8.12 (shaders off), Lithium 0.15.4, ModernFix 5.27.27, FerriteCore
  7.0.3, spark 1.10.124, **Particle Core 0.3.3** (`asynchronousTicking = true`), and **Particle Filter
  1.1.0** (the instance has not been updated to 1.2.0).
- Options: fullscreen, `maxFps:260`, VSync off, render and simulation distance 12, Particles All.
- `saves/` is empty: the bases are on multiplayer servers.
- The other instance ("All the Mods 10 - ATM10") has Sodium 0.8.13 and Sodium Extra 0.9.5. It is the only local
  source of Sodium Extra, and it's a different modpack.

## Plan

1. **Copy, don't touch.** Make a benchmark-only copy of the instance (separate CurseForge profile or a
   directory copy launched the same way) and put a **backup copy** of the server world in its `saves/`.
   The play instance, its config and the live server are never modified.
2. In the copy: Particle Filter 1.2.0 (the version being evaluated), the pfbench harness jar (once built),
   and `config/particlefilter.json` from `benchmarks/configs/` adapted to the base's coordinates.
   The jar list and every config are recorded by `tools/collect_env.py --instance`.
3. Scenarios (the owner picks the exact spots):
   - R1a: AE2 growth accelerators, the case that motivated the mod;
   - R1b: an automated farm;
   - R1c: one more particle-heavy build.
   For each, write down the coordinates, yaw and pitch, and what is running.
4. **Workload description:** before each block, run a Particle Filter scan at the spot (5 s, never inside a
   measured window) and save the counts. Large changes between blocks mean the machines changed state,
   and the block is flagged.
5. **Per launch:** `/tp` to the spot, `/time set 6000`, `/weather clear`, `/gamerule doDaylightCycle false`
   (in the copy only); wait for chunks; warm up 60 s; then B / C / D windows by Active rules mode, in
   Williams order (`tools/schedule.py`), with 15 s settling after each switch. A (Particle Filter removed)
   needs its own launches, alternating with B launches.
6. Autosave: note each autosave (every 5 minutes) and drop windows that overlap it, or use
   `/save-off` in the copy for the session.

## What can't be controlled, and how it's handled

| Factor | Handling |
|---|---|
| Machine cycles, power, input buffers | scan counts per block; paired windows close together in time |
| Integrated server (machines tick on the same CPU) | record server tick time (harness) and thread CPU (sampler); report it next to FPS |
| Chunk loading at the spot | settle period; same spot every time; render distance fixed |
| Entity activity, random ticks | identical world copy each launch; same duration |
| GC with a 12 GB heap | GC log in every run; windows with a long pause are flagged, not hidden |

## Decisions and approvals needed from the owner

- Can a backup of a server world (which of the two private servers; addresses withheld from publication) be
  copied into a benchmark-only instance?
- OK to create that instance copy (several GB) and to put Particle Filter 1.2.0 and the harness in it?
- Frame logging there: rely on the harness's own frame log, or set MangoHud logging for the Flatpak
  (it would mean a `MANGOHUD_CONFIG`/`MANGOHUD_CONFIGFILE` entry in the CurseForge Flatpak override,
  which also affects the play instance unless it is removed afterwards)?
- If no world copy is possible: is a clearly labelled **field observation** on the live server acceptable?
