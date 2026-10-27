---
name: release
description: Release a new version of Particle Filter end to end — changelog, version bump, build and test, commit, tag (CI publishes the GitHub release), verify the jar and SHA-256, upload to CurseForge, and review the CurseForge page. Use when the user says "release 1.2.0", "ship it", "publish a new version" or similar. Takes the version as its argument.
---

# Release workflow

Argument: the new version, e.g. `1.2.0` (no leading `v`). If it's missing, suggest one from the changes since the last tag (patch for fixes, minor for features) and ask.

## 1. Check the state
- `git status` is clean and on `main`, up to date with `origin/main`.
- `git log --oneline $(git describe --tags --abbrev=0)..HEAD` lists what this release contains. If it's empty, stop and say so.
- The last run of the Build workflow on main is green (`gh run list --workflow build.yml -L 1`).

## 2. Changelog
- Add a `## <version>` section at the top of `CHANGELOG.md` (below the intro), written for players: what changed and why it matters, not internal refactors. Follow the style of the earlier sections.
- Keep the wording broad: any source of unwanted particles, not just machines.
- Show the section to the user and wait for an OK. CI and CurseForge both publish it word for word.

## 3. Version bump, build, test
- Set `version = '<version>'` in `build.gradle`. The version lives there, not in `gradle.properties`.
- `mise exec -- ./gradlew build` must pass.
- Quick test: ask the user to drop `build/libs/particlefilter-<version>.jar` into their ATM10 instance (replacing the old jar) and check the changed features, or use a temporary self-test hook (see CLAUDE.md, and delete it after). Don't tag until the user confirms it works in game.

## 4. Commit, tag, push
- Commit `CHANGELOG.md` and `build.gradle` as "Bump version to <version>" (repo-local noreply identity, plus the Co-Authored-By line).
- `git tag v<version> && git push origin main v<version>`. Pushing a tag is public and can't be quietly undone, so confirm with the user first.
- CI (`.github/workflows/release.yml`) checks that the tag matches `build.gradle`, builds the jar and creates the GitHub release. Its notes are the changelog section, the AI disclosure, install instructions and the SHA-256. Watch it with `gh run watch`.

## 5. Verify the release
- `gh release view v<version>` shows `particlefilter-<version>.jar` and the notes include the AI disclosure.
- Download the asset to the scratchpad and compare its `sha256sum` with the SHA-256 in the notes.
- If the user wants extra notes (highlights, upgrade tips like the 1.1.0 release had), edit them with `gh release edit v<version> --notes-file ...`, and keep the disclosure and SHA-256.

## 6. CurseForge
- If the `CURSEFORGE_TOKEN` repo secret is set (`gh secret list`), the release workflow's `curseforge` job already uploaded the jar. Check that job's log for the "Uploaded:" line, and **don't upload again**.
- Otherwise: `scripts/publish-curseforge.sh --dry-run <version>`, show the output to the user, then run it without `--dry-run`. The script adds the AI disclosure to the changelog itself. It needs the CI jar or a local build of the same commit.
- CurseForge may review the file before it goes public.

## 7. Review the page
- WebFetch https://www.curseforge.com/minecraft/mc-mods/particle-filter and the Files tab: the new file listed (or under review), the description current, and any new comments.
- If the description should mention new features, update `~/Work/notes/curseforge-description.md` (GitHub links stay at the bottom) and show the user the change before anyone pastes it.
- Report: release URL, SHA-256, CurseForge status, anything the user should do by hand.
