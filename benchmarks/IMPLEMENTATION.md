# How Particle Filter suppresses particles, and what that costs

Analysis of Particle Filter 1.2.0 (commit `bcba10f`) for benchmark design. Nothing here has been measured
yet: the cost notes below are predictions from reading the code, to be confirmed or refuted by the
benchmarks in [PLAN.md](PLAN.md).

Mod paths are relative to `src/main/java/particlefilter/`. Minecraft references are to the decompiled
sources ModDevGradle produced for this repository (`neoforge-21.1.250-sources.jar` and
`forge-1.20.1-47.4.28-sources.jar`, under `versions/<mc>-<loader>/build/moddev/artifacts/`); line numbers are
from those files.

## 1. The particle pipeline in vanilla Minecraft

A typical particle request takes this path (1.21.1 line numbers; 1.20.1 is the same code):

1. `ClientLevel.addParticle(...)` (ClientLevel.java:696) → `LevelRenderer.addParticle` →
   `LevelRenderer.addParticleInternal` (LevelRenderer.java:2655).
2. **Vanilla discards many requests here, before any mod hook sees them:**
   - farther than 32 blocks from the camera (`distanceToSqr > 1024`, LevelRenderer.java:2670);
   - the Particles video setting: *Minimal* drops them; *Decreased* randomly turns a third of them into
     Minimal (LevelRenderer.java:2679-2690). This is randomness that differs between runs.
   - Exception: "force" requests (`ParticleType.getOverrideLimiter()`, or `addAlwaysVisibleParticle`)
     skip both checks.
3. `ParticleEngine.createParticle(options, x, y, z, ...)` (ParticleEngine.java:358) looks up the provider and
   **constructs** the particle (`makeParticle`, :371), then calls `add`.
4. `ParticleEngine.add(particle)` (:380) enforces per-`ParticleGroup` limits (only a few vanilla particles
   have a group, e.g. spore blossom) and queues the particle in `particlesToAdd` (:89). That queue is
   **unbounded**.
5. On the next client tick, `ParticleEngine.tick()` (:392) ticks every live particle, then moves queued
   particles into one `EvictingQueue` per render type with **16,384 slots** (:75, :414). When a queue is full,
   adding a particle silently evicts the oldest one.
6. `ParticleEngine.render(...)` (:455) draws every particle that passes the frustum check (:471,
   `getRenderBoundingBox`). Forge 1.20.1 culls only particles whose `shouldCull()` is true and uses
   `getBoundingBox()` (Forge ParticleEngine.java:429).

Mods also bypass steps 1-2: they may call `particleEngine.createParticle(...)` directly, or construct a
`Particle` themselves and call `particleEngine.add(...)` (step 4) without any particle type.

Block breaking (`ParticleEngine.destroy`, :502) and mining (`crack`, :545) build `TerrainParticle`s directly
and pass them to `add`. NeoForge/Forge let a block replace these with its own effects
(`IClientBlockExtensions.addDestroyEffects/addHitEffects`, :503 and :598).

**Consequences for benchmarking**

- *Spawn attempts* (requests in step 1), *particles created* (step 3 succeeded), and *particles alive*
  (in the evicting queues) are three different numbers. Above ~16,384 live particles of one render type,
  more attempts still cost construction and queueing, but the visible count stops growing.
- Synthetic spawners must spawn within 32 blocks of the camera and run with Particles = All, or vanilla
  discards the load before Particle Filter is involved.
- Ticking cost covers every live particle; rendering cost only those in view. Camera direction matters.

## 2. Where Particle Filter intercepts

All filtering is one mixin on `ParticleEngine`: `mixin/ParticleEngineMixin.java`.

| Hook | Location | What it filters | Rejected particles avoid |
|---|---|---|---|
| `createParticle` HEAD, cancellable | :40-58 | typed particles by type id (`ae2:energy_fx`); block-dust particles (`BlockParticleOption`) by block | provider lookup, **construction**, queueing, ticking, rendering |
| `createParticle` RETURN | :60-63 | nothing (clears the "inside a typed spawn" flag) | n/a |
| `add` HEAD, cancellable | :65-78 | particles added without a type, by class name | queueing, ticking, rendering (**not** construction: the mod already built it) |
| `destroy` HEAD, cancellable | :80-83 | block-breaking particles by block | the whole voxel-shape loop and every `TerrainParticle` |
| `crack` HEAD, cancellable | :85-88 | mining particles by block | the `TerrainParticle` |

Filtering decisions happen at spawn time, once per particle. Particle Filter adds no per-tick or per-frame
work for particles that were allowed.

A `ThreadLocal<Boolean>` (`IN_TYPED_SPAWN`, :35) is set at the end of the typed hook (:57) and cleared at
RETURN (:62), so the `add` call that `createParticle` makes is not filtered or counted twice (:67).

## 3. How the rules are evaluated

`filter/FilterRules.java` compiles the config into an immutable `Snapshot` (:34-45) held in a `volatile` field
(:47). Spawning threads read one reference and never lock, and `rebuild()` (:95-124) swaps in a new snapshot
when the config, world, dimension or Hold to Show All state changes. It never runs per particle.

- **Global rules:** `isParticleHidden` (:131) is one `Set.contains` on an immutable `Set.copyOf` (:133).
- **Zones:** after the global check, a linear loop over the zones in the current world and dimension
  (:134): six double comparisons per zone, then a `Set.contains` when the point is inside. Zones with no
  particles are left out at compile time. Cost grows with the number of zones here, not the number of
  rules inside them.
- **Block rules:** `isBlockHidden` (:141) returns at once when there are no block rules; otherwise it does a
  registry lookup (:144), two set lookups, then `state.is(tag)` for each configured tag (:147).
- **Active rules mode / Hold to Show All** (`config/FilterMode`) are applied at compile time: switching
  mode rebuilds the snapshot and leaves out the disabled rule kinds. A paused mode costs the same as having no rules.

## 4. Cost of each path (predicted, not yet measured)

Per **typed spawn attempt** that reaches `createParticle`:

| State | Work Particle Filter adds |
|---|---|
| No rules, no scan (config B) | `instanceof`; one or two volatile reads + `isEmpty()` checks; `ThreadLocal.set` ×2 (HEAD and RETURN); Mixin `CallbackInfoReturnable` objects for the two injectors (possibly removed by JIT escape analysis). The `add` hook on the same particle adds one `ThreadLocal.get` and a `CallbackInfo`. |
| Particle rules exist (C/D) | above, plus a registry reverse lookup (:49), **a new `String` from `ResourceLocation.toString()` per attempt** (:50, so the string's hash is recomputed every time), a hash-set lookup, and the zone loop. |
| Rule matches | returns `null` before construction: saves everything vanilla would have done for that particle. |
| Untyped particle, rules exist | `getBoundingBox().getCenter()` **allocates a `Vec3`** (:71); class-name lookup (cached by the JVM) and set lookup. |
| Block-dust particle or break/mine, block rules exist | registry lookup + set lookups + one `state.is` per tag. |

Per **client tick** (20 Hz): `FilterRules.tick()` (:65) compares the world and dimension, allocating a string from
`dimension().location().toString()` (:74); `ParticleScan.tick()`; key polling. This is a constant, tiny cost.

Per **frame**: `ZoneRenderer.render` (`render/ZoneRenderer.java:31`) returns immediately unless zone outlines
are switched on or a Particle Filter screen is open (:33). With outlines on, it streams the zone list
(`PfConfig.zonesHere`, `config/PfConfig.java:135-137`) and draws line boxes every frame. Benchmarks keep
outlines off and say so.

**Scanner** (`scan/ParticleScan.java`): active only for the 5 seconds of a scan. While running it records
every typed and untyped spawn: a distance check (:89) and, within 6 blocks, a `synchronized` map update
(:90). This is the only lock on the spawn path. Measured runs never include a scan.

### Performance-sensitive points to measure

1. Cost per attempt when **rules exist but don't match** the workload: every attempt pays the string
   allocation and lookups and saves nothing. This is the most likely way for Particle Filter to make things
   slower (research question 8), so it gets its own configuration, "C-miss".
2. **Allocation rate** at extreme attempt rates (the string per typed attempt, `Vec3` per untyped particle,
   Mixin callback objects): watch GC frequency and pause times, not only FPS.
3. **Zone count** (linear loop) and **block-tag count** (`state.is` per tag).
4. The **untyped path**: the mod has already constructed the particle, so less is saved than on the typed path.

### Thread safety

The spawn path reads only the volatile immutable snapshot, the scan's volatile `center`, and a
ThreadLocal. It takes no lock except during a scan. `showAll` (:49) is a plain field written and read on
the client thread.

## 5. Forge 1.20.1 vs NeoForge 1.21.1

The mixin, rules, config and scanner are one shared source with no Stonecutter conditionals in those files.
Only the loader glue differs: the tick hook is `ClientTickEvent.Post` on NeoForge
(`loader/NeoForgeEntry.java:25`) and the END phase of `TickEvent.ClientTickEvent` on Forge
(`loader/ForgeEntry.java:39`). Differences that matter for benchmarks come from the platform:
Java 17 vs 21, the frustum-culling rule above, and the Forge jar being reobfuscated (which has no runtime
cost). Results from one loader must not be presented as results for the other.

## 6. Existing test infrastructure

- No unit tests. Behaviour is checked in game (`CLAUDE.md`, "Testing").
- `./gradlew :<mc>-<loader>:runClient` starts a dev client with its own gitignored run directory
  (`run/<mc>-<loader>/`). It is the basis for the synthetic benchmark. ModDevGradle starts the game through
  `net.neoforged.devlaunch.Main`, so look for that string, not `forgeclientdev`, to find the game process.
- The temporary env-gated "self-test hook" pattern shows a dev-only code path can drive the client.
  The benchmark harness follows the same idea, but as a **separate mod** that is never in release jars
  (see [HARNESS.md](HARNESS.md)).
- CI (`.github/workflows/build.yml`) builds every push. The Python tests in `benchmarks/tools/tests` could
  run there too (stdlib only, under a second).

## 7. Confounders found while reading the code

| Confounder | Why it matters | Control |
|---|---|---|
| 32-block distance cull and Particles setting | attempts beyond 32 blocks, or with Particles ≠ All, never reach the mod | spawn within ~24 blocks; Particles = All; record it |
| 16,384 live particles per render type | live count saturates; extra attempts only add construction and eviction churn | log live counts; choose spawn rates on both sides of the limit |
| Unbounded `particlesToAdd` queue | a burst within one tick is fully constructed before eviction | measure the cost of attempts, not just live counts |
| Random lifetimes (most vanilla particles use an unseeded `RandomSource`) | live count varies between runs at the same attempt rate | fixed-lifetime harness particle for the controlled scenario; log live counts for vanilla ones |
| Frustum culling | rendering cost depends on what the camera sees | locked camera position and rotation |
| Hold to Show All / Active rules mode | change what is filtered without editing rules | set mode in the config; don't touch the key during runs |
| Zone outlines and open Particle Filter screens | per-frame drawing | off during measurement |
| Scanner running | per-particle synchronized work | never scan during a measured window |
| Particle Core (installed in the ATM10 To the Sky instance) | its `asynchronousTicking = true` moves particle ticking off the render thread, and it has its own per-type reductions | hold its jar and config constant in every configuration, record both, and treat "with/without Particle Core" as a separate factor |
| Sodium / Iris / Sodium Extra versions | change rendering cost and may touch particles | identical in every configuration of a comparison; hashes recorded by `collect_env.py` |
| Block-specific effects from modded blocks | NeoForge's `addHitEffects` can replace `crack`, so the `crack` hook never runs for such blocks | count what the block actually emits before relying on block rules in a scenario |

Two observations that don't affect performance: if a particle provider throws inside `createParticle`, the
RETURN hook doesn't run and `IN_TYPED_SPAWN` stays true until the next typed spawn on that thread, so
untyped particles skip filtering until then. And for blocks with custom hit effects, mining particles bypass
the `crack` hook (see the last table row). Neither is changed here; production code is out of scope for this stage.

## 8. Is a comparison with Sodium Extra fair?

Checked from the bytecode of `sodium-extra-neoforge-0.9.5+mc1.21.1.jar` (in the user's "All the Mods 10 -
ATM10" instance): its `MixinParticleEngine` injects at **HEAD of `createParticle`** and checks the particle
type's registry id against its per-type on/off map (`ParticleSettings.isParticleEnabled(ResourceLocation)`).
It also injects at HEAD of `destroy` and `crack`, but those are global switches for *all* block-break and
block-hit particles.

- **Fair:** global suppression of the same *typed* particle types (Particle Filter config C vs Sodium Extra).
  Both reject at the same point, before construction.
- **Fair with care:** "hide all block-breaking particles" only in a scenario where every broken block is one
  block type, so Particle Filter's per-block rule removes the same particles as Sodium Extra's global switch.
- **Not comparable:** zones, untyped (class-name) particles, per-block rules among several block types,
  and the scanner. Sodium Extra cannot do these, so the report says so instead of forcing a number.
- Sodium Extra requires Sodium, so **every** configuration in that comparison (baseline included) runs
  the same Sodium version. That instance runs Sodium 0.8.13; the ATM10 To the Sky instance runs 0.6.13
  without Sodium Extra.

Particle Core (`byTypeReductions`, `disableParticles`, `maxParticlesPerSheet`) is another candidate
comparator. Where it intercepts has not been checked yet.
