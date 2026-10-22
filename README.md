# Particle Filter

A client-side NeoForge mod for finding out which particles are cluttering your screen and hiding exactly those: everywhere, only inside zones you define, or only for specific blocks' breaking and mining effects.

Built for big modded bases where automation (AE2 growth accelerators, crystal farms, machines) fills the screen with particles, and turning particles down to "Minimal" would remove them everywhere else too.

## Features

- **Scan:** look at a machine and press Scan. For 5 seconds the mod records every particle spawned within 6 blocks, then lists them by particle ID (e.g. `ae2:energy_fx`) with how many appeared. Particles that mods spawn without a type are listed by class name.
- **Hide everywhere** or **hide here** with a checkbox per particle. "Hide here" adds it to the zone around the scanned spot, creating a 9×5×9 zone if there isn't one (previewed in the world before you commit).
- **Zones** are boxes with a name, a position (center X/Z, bottom Y) and a size (width × height × length, 1-256 blocks). Edit them by typing or with −/+, or snap them to your position or crosshair; the outline updates live in the world. Zones belong to one server (or singleplayer world) and dimension.
- **Block particles:** hide the breaking, mining and block-dust (sprinting/landing) particles of specific blocks, whole mods (`geore:*`), or block tags (`#c:ores`). A keybind toggles the block you're looking at.
- **One list of everything hidden**, showing whether each entry is hidden everywhere, in which zone, or for block breaking, with one-click unhide.
- Filtering happens at the particle engine, so it works however a mod spawns its particles, and hidden particles are never created (a small CPU saving rather than drawing them invisibly).

## Usage

Open the menu with **`/particles`**, the **Open Particle Filter** keybind, or the Config button in the Mods list. It has three tabs:

- **Scan:** look at the machine making the particles, press **Scan**, and tick **Hide everywhere** or **Hide here** next to each particle you want gone. The game keeps running while the menu is open, so the scan collects while you wait.
- **Hidden:** everything hidden and where. Unhide anything, or type an ID to hide it directly.
- **Zones:** zones in your current world. Hover a zone to highlight it, **Edit** to change its name, position, size and contents, or create a new one around you.

Commands (client-side only; nothing is sent to the server):

| Command | Does |
|---|---|
| `/particles` | Opens the menu on the last tab used |
| `/particles scan` | Starts a scan at your crosshair and opens the Scan tab |
| `/particles hidden` | Opens the Hidden tab |
| `/particles zones` | Opens the Zones tab |

Keybinds (unbound by default, under Controls → Particle Filter): Open Particle Filter, Scan Particles at Crosshair, Hide/Show Breaking Particles of Targeted Block, Always Show Zone Outlines.

Settings are stored in `config/particlefilter.json`.

## Compatibility

- Minecraft 1.21.1, NeoForge 21.1+. Client-side only: install it on your client; servers don't need it.
- Works alongside particle optimization mods such as Particle Core and Sodium.

## Limitations

- Zones are tied to the server address you connect with; connecting through a different address won't show them.
- Effects that mods draw with their own renderers, rather than the particle system, can't be filtered.

## Building

Requires JDK 21.

```sh
./gradlew build
```

The jar is written to `build/libs/`. `./gradlew runClient` starts a development client.

## License

MIT. See [LICENSE](LICENSE).
