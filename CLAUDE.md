# Particle Filter

## What this is
A client-side Minecraft mod, built from one source for NeoForge 1.21.1 (Java 21) and Forge 1.20.1 (Java 17). It scans which particles are on screen and hides the chosen ones everywhere, inside named zones, or by block (breaking/mining debris). `/particles` opens the menu (Scan, Hidden, Zones pages; Active rules switch). Public on GitHub (Minihercules317/particle-filter, MIT) and CurseForge (project 1760372). Modrinth is off-limits: its rules bar mostly-AI projects.

## Rules (for Claude)
- **AI disclosure stays prominent.** Claude writes all code; the owner contributed the idea, design decisions, testing, media and icon, never code. Keep that in the README ("Who did what"), mod metadata, release notes and store pages. Never overstate the owner's coding role.
- **Broad wording.** User-facing text must cover any source of unwanted particles (lag, clutter, visibility), not just machines or one modpack.
- **Descriptive commits:** "Bump version to 1.2.0" or "Fix zone resize", never "Release 1.2.0". Commit as the repo-local identity `Minihercules317 <62471385+Minihercules317@users.noreply.github.com>`, never a personal email.
- **Media must be honest:** never fake or paint out particles; before/after shots differ only in particles.

## Commands
- Java comes from mise (`mise.toml`, untracked: `java = "temurin-21"`), so prefix commands with `mise exec --`. Gradle downloads Java 17 for the Forge build itself.
- Build every version: `mise exec -- ./gradlew build buildAndCollect` → `build/libs/<version>/particlefilter-<loader>-<version>+<mc>.jar`. One version: `./gradlew :1.20.1-forge:build`.
- Run a dev client: `mise exec -- ./gradlew :1.21.1-neoforge:runClient` (or `:1.20.1-forge:runClient`); each has its own `run/<mc>-<loader>/`.
- The version is `mod.version` in `stonecutter.properties.toml`, which also holds each build's loader version and metadata ranges.
- Release: use the `release` skill (`.claude/skills/release/`). Pushing a `v*` tag makes CI (`.github/workflows/release.yml`) build the jars and publish the GitHub release; `.github/workflows/build.yml` builds every push to main.
- CurseForge upload: `scripts/publish-curseforge.sh [--dry-run]` (token in `~/.config/curseforge/token`). CI does it instead if the `CURSEFORGE_TOKEN` secret is set.

## Testing
There are no unit tests; behaviour is checked in game. To check GUI or behaviour yourself, add a temporary dev self-test hook run via `./gradlew :<mc>-<loader>:runClient`: gate it on an env var, take screenshots with Minecraft's `Screenshot.grab`, and move the window to a hidden Hyprland special workspace. **Delete the hook before committing.** The owner tests releases in their ATM10 instance.

## Versions (Stonecutter)
- `settings.gradle.kts` lists the builds (`<mc>-<loader>`); each uses `build.<loader>.gradle.kts` (ModDevGradle: `moddev` for NeoForge, `moddev.legacyforge` for Forge, which reobfuscates the jar and needs the mixin refmap).
- All builds share `src/`. Code that differs is wrapped in Stonecutter comments, e.g. `//? if >1.20.1 {` … `//?} else {` … `//?}` or `//? if forge {`; the branch for the inactive version is commented out. Keep differences small: loader glue lives in `loader/NeoForgeEntry` and `loader/ForgeEntry`, and `gui/widget/ContainerWidget` stands in for a class 1.20.1 lacks.
- To edit or debug the code of another version, switch with `./gradlew "Set active project to 1.20.1-forge"`. **Switch back to 1.21.1-neoforge (`./gradlew "Reset active project"`) before committing**; `stonecutter.gradle.kts` then says `stonecutter active "1.21.1-neoforge"`.
- `META-INF/mods.toml` and `particlefilter.mixins.json` are templates filled per build (NeoForge gets it as `neoforge.mods.toml`).
- Adding a version or loader: add it in `settings.gradle.kts` and `stonecutter.properties.toml`, and give it CurseForge game versions in `scripts/publish-curseforge.sh`.

## Where things are
- `src/main/java/particlefilter/`: mod code (`gui/Theme`, `gui/widget/*` for the menu, `loader/*` for the loader entry points)
- `CHANGELOG.md`: one `## <version>` section per release (CI and the CurseForge script read it)
- `docs/images/`: README/CurseForge gallery images; `docs/compare/`: the before/after slider served by GitHub Pages
- `~/Work/notes/curseforge-description.md` and `curseforge-gallery.md`: CurseForge page text and captions (outside the repo)
