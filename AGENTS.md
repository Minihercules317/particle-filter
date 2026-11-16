# Particle Filter

## What this is
A client-side Minecraft mod, built from one source for NeoForge 1.21.1 (Java 21) and Forge 1.20.1 (Java 17). It scans which particles are on screen and hides the chosen ones everywhere or inside named zones, one by one or in groups (a mod's particles, everything made from a block, a mod's blocks or a block tag). `/particles` opens the menu (Scan, Hidden and Zones pages; Active rules switch); Config in the mod list opens a small settings screen (walking in the menu, lines to zones). Public on GitHub (Minihercules317/particle-filter, MIT) and CurseForge (project 1760372). Modrinth is off-limits: its rules bar mostly-AI projects.

## Rules (for Codex)
- **AI disclosure stays prominent.** Codex writes all code; the owner contributed the idea, design decisions, testing, media and icon, never code. Keep that in the README ("Who did what"), mod metadata, release notes and store pages. Never overstate the owner's coding role.
- **Broad wording.** User-facing text must cover any source of unwanted particles (lag, clutter, visibility), not just machines or one modpack.
- **Descriptive commits:** "Bump version to 1.2.0" or "Fix zone resize", never "Release 1.2.0". Commit as the repo-local identity `Minihercules317 <62471385+Minihercules317@users.noreply.github.com>`, never a personal email.
- **Media must be honest:** never fake or paint out particles; before/after shots differ only in particles.

## Commands
- Java comes from mise (`mise.toml`, untracked: `java = "temurin-21"`), so prefix commands with `mise exec --`. Gradle downloads Java 17 for the Forge build itself.
- Build every version: `mise exec -- ./gradlew build buildAndCollect` → `build/libs/<version>/particlefilter-<loader>-<version>+<mc>.jar`. One version: `./gradlew :1.20.1-forge:build`.
- Run a dev client: `mise exec -- ./gradlew :1.21.1-neoforge:runClient` (or `:1.20.1-forge:runClient`); each has its own `run/<mc>-<loader>/`.
- The version is `mod.version` in `stonecutter.properties.toml`, which also holds each build's loader version and metadata ranges.
- Release: use the `release` skill (`.agents/skills/release/`). Pushing a `v*` tag makes CI (`.github/workflows/release.yml`) build the jars and publish the GitHub release; `.github/workflows/build.yml` builds every push and pull request to main.
- CurseForge upload: `scripts/publish-curseforge.sh [--dry-run]` (token in `~/.config/curseforge/token`). CI does it instead if the `CURSEFORGE_TOKEN` secret is set.

## Testing
- Unit tests (JUnit 5) are in `src/test/java/particlefilter/`, in the same packages as the code they test. `build` runs them once per version, so CI runs them on every push and pull request, and a failing test blocks a tagged release. Just the tests: `mise exec -- ./gradlew test` (one version: `:1.20.1-forge:test`); report in `versions/<mc>-<loader>/build/reports/tests/test/index.html`.
- They run without the game. Minecraft's plain classes (`AABB`, `BlockPos`, `ResourceLocation`) work; anything needing the game started (registries, `Minecraft.getInstance()`, rendering, `ParticleFilter` and its keybinds) doesn't. Keep logic testable by passing it plain inputs (`PfConfig.parse`, `PfConfig.assignColors`, `FilterRules.compile`); code under test logs through its own `LoggerFactory.getLogger("Particle Filter")`, not `ParticleFilter.LOGGER`. On 1.20.1 even making a `TagKey` loads the registries, so such tests go inside `//? if >1.20.1 {`; rule tests avoid this by passing `FilterRules.compile` a fake `ParticlePattern.TagCheck`.
- Covered: `ZoneFit`, `Zone`, `FilterMode` (and its en_us.json names), reading and saving configs (old formats, colors, unreadable files, atomic saves), `SpawnRecorder`, `ParticleKey`, `ParticlePattern` (rule entries and groups, one covering another, old block entries turned into them), `ResultTree` (folders, switch states, locking) and compiling `FilterRules`. Not covered, so checked in game: the menu (including moving and resizing it), scans, the mixins, walking in the menu, tracers, and real block tags (the game's `GameTags` lookup).
- A bug fix starts with a test that fails without the fix. A behaviour change updates the tests describing it in the same commit; never weaken a test just to make it pass.
- Everything else is checked in game. To check GUI or behaviour yourself, add a temporary dev self-test hook run via `./gradlew :<mc>-<loader>:runClient`: gate it on an env var, take screenshots with Minecraft's `Screenshot.grab`, and move the window to a hidden Hyprland special workspace. **Delete the hook before committing.** The owner tests releases in their ATM10 instance.
- The owner's ATM10 instance (a CurseForge `java` process) may be running during dev runs; only ever stop the dev client you started.
- For a refactor meant to change no pixels, screenshot the same deterministic setup (fixed zones, camera, GUI scale, mouse parked) in a build of the commit before and after, and compare the images pixel by pixel.

## Versions (Stonecutter)
- `settings.gradle.kts` lists the builds (`<mc>-<loader>`); each uses `build.<loader>.gradle.kts` (ModDevGradle: `moddev` for NeoForge, `moddev.legacyforge` for Forge, which reobfuscates the jar and needs the mixin refmap).
- All builds share `src/`. Code that differs is wrapped in Stonecutter comments, e.g. `//? if >1.20.1 {` … `//?} else {` … `//?}` or `//? if forge {`; the branch for the inactive version is commented out. Keep differences small: loader glue lives in `loader/NeoForgeEntry` and `loader/ForgeEntry`, and `gui/widget/ContainerWidget` stands in for a class 1.20.1 lacks.
- To edit or debug the code of another version, switch with `./gradlew "Set active project to 1.20.1-forge"`. **Switch back to 1.21.1-neoforge (`./gradlew "Reset active project"`) before committing**; `stonecutter.gradle.kts` then says `stonecutter active "1.21.1-neoforge"`.
- `META-INF/mods.toml` and `particlefilter.mixins.json` are templates filled per build (NeoForge gets it as `neoforge.mods.toml`).
- Adding a version or loader: add it in `settings.gradle.kts` and `stonecutter.properties.toml`, and give it CurseForge game versions in `scripts/publish-curseforge.sh`.

## Where things are
- `src/main/java/particlefilter/`: mod code
  - `ParticleFilter`: shared setup, commands, keybinds; `loader/*`: the NeoForge and Forge entry points
  - `config/`: `PfConfig` (saved rules and zones, `config/particlefilter.json`), `Zone`, `FilterMode` (Active rules)
  - `filter/FilterRules`: compiled snapshot of the rules, checked as particles spawn; `filter/ParticleKey`: particle names, with the block after "@" for particles made from a block; `filter/ParticlePattern`: rule entries, exact or groups (`mod:*`, `*@block`, `*@mod:*`, `particle@#tag`), used by everywhere and zone lists alike (block entries from before 1.3.0 load as `*@block`); `gui/RuleNames`: how entries read in lists; `loader/ModNames`: mod display names; `mixin/ParticleEngineMixin`: where every particle is filtered and recorded; `mixin/KeyboardInputMixin`: walking while a menu is open (`gui/MenuWalking`)
  - `scan/`: `ParticleScan` (Scan page: records around the crosshair, appearing and hidden; picks where Here hides), `FittedZones` (the per-source zones Here makes and keeps adjusting, for a particle or a group), `ResultTree` (Scan results as folders: mod → particle → block mod, with each folder's rules and its switch states), `SpawnRecorder` (spawn points), `ZoneFit` (box math: fitting, sources, merging, growing)
  - `render/ZoneRenderer`: zone outlines in the world
  - `gui/`: `ParticleFilterScreen` is the window shell (sidebar, header, tabs) and each page is a class in `gui/page/` (`Page`, `ScanPage`, `HiddenPage`, `ZonesPage`); `ZoneEditScreen` is the zone editor drawer, `GuideScreen` the help, `SettingsScreen` the mod list's Config screen; `PfScreen` is their base with the shared helpers and constants; `Theme` holds colors and drawing helpers; `widget/*` the controls (`ScrollPanel` is the list)
- `src/test/java/particlefilter/`: unit tests, one `<Class>Test` per class tested
- `CHANGELOG.md`: one `## <version>` section per release (CI and the CurseForge script read it)
- `docs/images/`: README/CurseForge gallery images; `docs/compare/`: the before/after slider served by GitHub Pages; `docs/index.html`: the homepage (GitHub Pages from main)
- `~/Work/notes/curseforge-description.md` and `curseforge-gallery.md`: CurseForge page text and captions (outside the repo)

## Menu conventions
- Text wraps instead of being cut off with "…": use `PfScreen.wrap` and `drawLines`, and size the row from the line count (`textHeight`, `LINE`). Only button labels, page titles and lines with a tooltip giving the full text may be shortened with `Theme.fit`.
- The menu window can be moved and resized (`ParticleFilterScreen`, from 500 × 250 up to the whole screen; its place is saved in `PfConfig.window`). Pages lay out only from `cx`, `cw`, `bodyTop` and `bodyBottom`, so they must work at every size in that range; check new layouts at the smallest size.
- Lines made of parts joined by " · " use `PfScreen.wrapPieces`, which breaks only between parts, so no line starts or ends with "·".
- `ScrollPanel.Row` takes a fixed height or an `IntSupplier` read every frame (for text that changes while typing); widgets added without a y stay vertically centered.
- Pixel positions that depend on each other are named constants and derived (e.g. text room from button widths and gaps), not repeated literals. Constants live in the narrowest class that uses them; shared ones are in `PfScreen` (button heights, card insets, `entryRow` for the dot/name/switch/Delete row).
- Here hides particles in an existing zone (the one at the scanned block by default) or in new zones (`FittedZones`): one per source, the smallest block box around its spawn blocks with no margin. Spawn blocks within the source distance (`PfConfig.sourceDistance`, chained) form one source (`ZoneFit.sources`), at most `PfConfig.maxZones` zones per particle; zones overlapping by `PfConfig.mergePercent` of the smaller one merge (`ZoneFit.merge`).
- Scan results are a tree (`ResultTree`): each mod's folder adds `mod:*` and `*@mod:*`, a particle made from several blocks gets a folder (`particle`) split by the blocks' mod (`particle@mod:*`), and each mod folder also lists what its blocks made (`*@mod:*`), so those particles show twice. A folder switch adds its rules and drops the narrower entries they cover; switching off deletes every entry the folder owns (`ResultTree.owns`). A switch hidden by a group above it is locked (no exceptions yet).
- The Scan page shows only length and radius by default; the source distance, zone count and merge settings, and the zone count beside each Here switch, sit under the collapsible Zone options (`PfConfig.zoneOptionsOpen`). Same algorithm either way: the options only show settings.
