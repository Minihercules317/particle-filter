# Particle Filter

<img src="src/main/resources/particlefilter.png" alt="Particle Filter icon" width="128">

A client-side Minecraft mod (NeoForge 1.21.1 and Forge 1.20.1) for hiding exactly the particles you don't want: everywhere, only inside areas you choose, or only for specific blocks' breaking and mining effects. Everything else keeps its particles.

The usual fix for too many particles is setting Particles to *Minimal*, which removes almost all of them everywhere. Particle Filter is for when you only want *some* gone. Use it to:

- calm down busy bases, farms and automated setups
- stop particular blocks from bursting into debris when you break or mine them
- remove one annoying particle from a mod without uninstalling the mod
- win back FPS when particles pile up (hidden particles are never created, not just drawn invisibly)
- clean up the view for screenshots, recording or streaming
- cut visual noise if certain effects are distracting or uncomfortable to look at

You don't need to know what a particle is called: a built-in scan finds it for you. It works with vanilla and modded particles.

**Download:** [CurseForge](https://www.curseforge.com/minecraft/mc-mods/particle-filter) (installs with one click in the CurseForge app) or [GitHub Releases](https://github.com/Minihercules317/particle-filter/releases).

> [!IMPORTANT]
> **AI disclosure: this mod's code was written by an AI.** All of the source code, build configuration and documentation were written by Claude, an AI model made by Anthropic, working in Claude Code. The project owner, Minihercules317, contributed the idea, the feature requests and the design decisions, tested it in game, and drew the icon. Minihercules317 did not write the code. See [Who did what](#who-did-what) for details.

## Screenshots

[![Before and after: a crystal farm with block-breaking debris, AE2 bubbles and sparks shown, then hidden by Particle Filter](docs/images/comparison.gif)](https://minihercules317.github.io/particle-filter/compare/)

A crystal farm in All the Mods 10: To the Sky with all particles shown (left) and with its debris, bubbles and sparks hidden (right). Same spot, same moment in the farm's cycle; the blocks are untouched. **[Try the interactive comparison](https://minihercules317.github.io/particle-filter/compare/)**.

![Editing a zone in the side panel while its outline is highlighted in the world](docs/images/1-zone-editor.png)

| | |
|---|---|
| ![The Hidden page, grouping hidden particles by zone](docs/images/2-hidden.png) | ![The Zones page, listing each zone's size, position and how many particles it hides](docs/images/3-zones.png) |
| **Hidden:** everything hidden, grouped by where it applies | **Zones:** each zone's size, position and hidden count |
| ![Scan results at a growth accelerator: ae2:energy_fx, TerrainParticle, ae2:lightning_fx and minecraft:falling_nectar with how often each appeared](docs/images/4-scan.png) | ![Zone outlines drawn over a machine floor in the world](docs/images/5-zone-outline.png) |
| **Scan:** lists every particle near what you're looking at, with counts | **Zone outlines** can stay visible while you play |

Screenshots are from All the Mods 10: To the Sky.

## Features

- **Scan:** look at where the particles are coming from (or just stand near them) and press Scan. For a few seconds (3 by default, adjustable from 1 to 60) the mod records every particle spawned within 6 blocks, then lists them by particle ID (e.g. `minecraft:flame`, `ae2:energy_fx`) with how many appeared. Particles that mods spawn without a type are listed by class name. Particles you've already hidden are listed too, so you can show them again from here.
- **Diagnostic:** its own page (or `/particles diagnostic`) that records every particle around you within render distance, split into those **still appearing** and those **your rules already hide**, with how close each came. That shows what's left once your rules are in place. Its Here switch gives each particle its own zone fitted to where it spawned; a particle whose zone would sit entirely inside another's shares that zone. If one of your zones already hides a particle that still appears just outside it, **Grow to fit** offers to enlarge that zone instead. Minecraft usually doesn't create particles more than 32 blocks from the player, so farther ones won't appear.
- **Hide everywhere** or **hide here** with a switch per particle. "Here" adds it to the zone around the scanned spot. If there isn't one, it creates a zone just big enough to cover every spot where those particles appeared, and no bigger. The line under the results says which zone Here uses, and a button switches to a new zone even inside an existing one. If particles you switch on in an existing zone also appeared outside it, **Grow to fit** offers to enlarge that zone to cover them (it never shrinks); hover the notice to see the new outline first.
- **Zones** are boxes with a name, a position (center X/Z, bottom Y) and a size (Side 1 × height × Side 2, 1-256 blocks each, with the number of blocks affected). The editor labels each side with the way it runs from where you're facing (left–right or front–back). Edit them by typing or with −/+, or snap them to your position or crosshair; the outline updates live in the world. Each zone gets its own outline color, and touching or overlapping zones never share one. Zones belong to one server (or singleplayer world) and dimension.
- **Block particles:** hide the breaking, mining and block-dust (sprinting/landing) particles of specific blocks, whole mods (`geore:*`), or block tags (`#c:ores`). A keybind toggles the block you're looking at.
- **One list of everything hidden**, showing whether each entry is hidden everywhere, in which zone (with its coordinates), or for block breaking. Switch an entry off to see the particle again without losing it, or delete it.
- **Active rules:** switch between applying all your rules, only the everywhere ones (including block particles), only zones, or none, from the menu or a keybind. Handy for comparing before and after; your rules are kept either way. There's also a **Hold to Show All Particles** keybind that pauses every rule only while held.
- Filtering happens at the particle engine, so it works however a mod spawns its particles, and hidden particles are never created (a small CPU saving rather than drawing them invisibly).

## Usage

Open the menu with **`/particles`**, the **Open Particle Filter** keybind, or the Config button in the Mods list. It reopens on the page you last used until you leave the world or server. The **?** button at the top opens a short quick start guide. The sidebar has four pages and the Active rules switch:

- **Scan:** look at where the unwanted particles are coming from, press **Scan**, and switch on **Everywhere** or **Here** next to each particle you want gone. The game keeps running while the menu is open, so the scan collects while you wait.
- **Diagnostic:** press **Diagnose** to list every particle around you, split into still appearing and already hidden, and hide what's left everywhere or in fitted zones.
- **Hidden:** everything hidden and where. Switch entries off and on, delete them (or a whole zone), or type an ID to hide it directly (Enter hides it as a particle).
- **Zones:** zones in your current world. Hover a zone to highlight it, **Edit** to change its name, position, size and contents in a side panel (the world stays visible so you can watch the outline move), or create a new one around you.
- **Active rules:** **All**, **Everywhere only**, **Zones only**, or **None (show all)**. Entries that are switched off show as *Paused* on the Hidden page.

Commands (client-side only; nothing is sent to the server):

| Command | Does |
|---|---|
| `/particles` | Opens the menu on the last page used in this world or server |
| `/particles scan` | Starts a scan at your crosshair and opens the Scan page |
| `/particles diagnostic` | Starts a diagnostic of everything around you and opens the Diagnostic page |
| `/particles hidden` | Opens the Hidden page |
| `/particles zones` | Opens the Zones page |

Keybinds (unbound by default, under Controls → Particle Filter): Open Particle Filter, Scan Particles at Crosshair, Hide/Show Breaking Particles of Targeted Block, Always Show Zone Outlines, Cycle Active Rules, Hold to Show All Particles.

Settings are stored in `config/particlefilter.json`.

## Compatibility

- Minecraft 1.21.1 with NeoForge 21.1+, or Minecraft 1.20.1 with Forge 47+. Download the jar for your version and loader. Client-side only: install it on your client; servers don't need it.
- Works alongside particle optimization mods such as Particle Core and Sodium (tested on 1.21.1).

## Limitations

- Zones are tied to the server address you connect with; connecting through a different address won't show them.
- Effects that mods draw with their own renderers, rather than the particle system, can't be filtered.
- Tested so far on one large modpack (All the Mods 10: To the Sky) and in a minimal development setup. Expect rough edges with other mods; bug reports are welcome.

## Who did what

**Minihercules317 (project owner): idea, design decisions, testing. No code.**

- Found the problem in their own modpack (breaking particles from GeOre ores and particle spam from AE2 growth accelerators) and asked for a way to hide specific particles rather than all of them.
- Asked for or decided on each feature: a menu reached by clicking the scan results, hiding particles only inside an area, seeing those areas in the world, outlines that update live while resizing, typing sizes instead of picking presets, naming and moving zones, one list showing everything hidden and where, one command for everything, zones measured by width, height and length instead of a radius, a way to cycle between which rules apply, and a modern menu redesign. For the usability update after 1.2.0: zones sized to fit the scanned particles, an adjustable scan duration, making a new zone from a scan inside an existing one, a diagnostic page that separates particles still appearing from hidden ones and makes one fitted zone per particle (shared when one lies inside another), switching hidden entries off instead of only deleting them, zone coordinates on the Hidden page, Side 1 and Side 2 with direction labels and a block count, distinct colors for overlapping zones, reopening the last menu during a session, clearer Back buttons, a quick start guide, and slimmer switches.
- Decided to turn the work into a standalone mod instead of a fork, named it, asked for a cleaner and more intuitive GUI, and chose to publish it.
- Tested every version in game and reported problems, with screenshots.
- Drew the mod icon.

**Claude (Anthropic's AI model, via Claude Code): all of the code and documentation.**

- Looked for existing mods that already solved this, then built the first prototype as additions to a fork of Particle Core.
- Designed the standalone architecture and wrote all of the Java source, mixins, GUI layout, build configuration, README and changelog.
- Tested it with an automated in-game self-test (spawning particles, scanning, hiding, and screenshotting each screen), then fixed the issues it and Minihercules317 found.

The code was tested in game, but no human has reviewed it line by line.

## Relationship to Particle Core

The features were first prototyped inside a fork of [Particle Core](https://github.com/fzzyhmstrs/pc) by fzzyhmstrs (MIT License), extending its per-type particle settings. Particle Filter then replaced that fork with a ground-up rewrite. **It contains no code from Particle Core**, does not depend on it, and runs fine next to the official Particle Core.

## Building

Requires JDK 21; Gradle downloads JDK 17 for the Forge 1.20.1 build itself. The build uses [Stonecutter](https://stonecutter.kikugie.dev/) to make a jar for each Minecraft version and loader from the same source.

```sh
./gradlew build buildAndCollect
```

The jars are written to `build/libs/<version>/`. `./gradlew :1.21.1-neoforge:runClient` (or `:1.20.1-forge:runClient`) starts a development client.

## License

MIT. See [LICENSE](LICENSE). The Gradle wrapper files (`gradlew`, `gradle/wrapper/`) are from the Gradle project and are licensed under the Apache License 2.0.
