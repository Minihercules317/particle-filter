# Particle Filter

## What this is
A client-side NeoForge mod for Minecraft 1.21.1 (NeoForge 21.1, Java 21). It scans which particles are on screen and hides the chosen ones everywhere, inside named zones, or by block (breaking/mining debris). `/particles` opens the menu (Scan, Hidden, Zones pages; Active rules switch). Public on GitHub (Minihercules317/particle-filter, MIT) and CurseForge (project 1760372). Modrinth is off-limits: its rules bar mostly-AI projects.

## Rules (for Claude)
- **AI disclosure stays prominent.** Claude writes all code; the owner contributed the idea, design decisions, testing, media and icon, never code. Keep that in the README ("Who did what"), mod metadata, release notes and store pages. Never overstate the owner's coding role.
- **Broad wording.** User-facing text must cover any source of unwanted particles (lag, clutter, visibility), not just machines or one modpack.
- **Descriptive commits:** "Bump version to 1.2.0" or "Fix zone resize", never "Release 1.2.0". Commit as the repo-local identity `Minihercules317 <62471385+Minihercules317@users.noreply.github.com>`, never a personal email.
- **Media must be honest:** never fake or paint out particles; before/after shots differ only in particles.

## Commands
- Java comes from mise (`mise.toml`, untracked: `java = "temurin-21"`), so prefix commands with `mise exec --`.
- Build: `mise exec -- ./gradlew build` → `build/libs/particlefilter-<version>.jar`
- Run a dev client: `mise exec -- ./gradlew runClient`
- The version is `version = '...'` in `build.gradle`.
- Release: use the `release` skill (`.claude/skills/release/`). Pushing a `v*` tag makes CI (`.github/workflows/release.yml`) build the jar and publish the GitHub release; `.github/workflows/build.yml` builds every push to main.
- CurseForge upload: `scripts/publish-curseforge.sh [--dry-run]` (token in `~/.config/curseforge/token`). CI does it instead if the `CURSEFORGE_TOKEN` secret is set.

## Testing
There are no unit tests; behaviour is checked in game. To check GUI or behaviour yourself, add a temporary dev self-test hook run via `./gradlew runClient`: gate it on an env var, take screenshots with Minecraft's `Screenshot.grab`, and move the window to a hidden Hyprland special workspace. **Delete the hook before committing.** The owner tests releases in their ATM10 instance.

## Where things are
- `src/main/java/particlefilter/`: mod code (`gui/Theme`, `gui/widget/*` for the menu)
- `CHANGELOG.md`: one `## <version>` section per release (CI and the CurseForge script read it)
- `docs/images/`: README/CurseForge gallery images; `docs/compare/`: the before/after slider served by GitHub Pages
- `~/Work/notes/curseforge-description.md` and `curseforge-gallery.md`: CurseForge page text and captions (outside the repo)
