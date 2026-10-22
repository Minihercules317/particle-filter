# Particle Filter

A client-side NeoForge mod for finding out which particles are cluttering your screen and hiding exactly those: everywhere, only inside named zones, or only for specific blocks' breaking and mining effects.

Built for big modded bases where automation (AE2 growth accelerators, crystal farms, machines) fills the screen with particles, and turning particles down to "Minimal" would remove them everywhere else too.

## Features

- **Scan:** look at a machine and scan. For 5 seconds the mod records every particle spawned within 6 blocks, then lists them most-frequent first by particle ID (e.g. `ae2:energy_fx`). Particles that mods spawn without a type are listed by class name.
- **Hide everywhere** any particle from the scan results with one click.
- **Hide here:** hide a particle only inside a zone around the scanned block. Zones:
  - have a name, a center you can type, nudge, or move to your position or crosshair, and a radius (1-128)
  - are outlined in the world, live, while you edit them
  - belong to one server (or singleplayer world) and one dimension
- **Block particles:** hide the breaking, mining and block-dust (sprinting/landing) particles of specific blocks, whole mods (`geore:*`), or block tags (`#c:ores`). A keybind toggles the block you're looking at.
- **One list of everything hidden**, showing whether each entry is hidden everywhere, in which zone, or for block breaking, with one-click unhide.
- Filtering happens at the particle engine, so it applies no matter how a mod spawns its particles. Hidden particles are never created, which saves a little CPU instead of drawing them invisibly.

## Usage

Open the menu with **`/particles`**, the **Open Particle Filter** keybind, or the Config button in the Mods list.

Typical workflow:

1. Look at the noisy machine and choose **Scan particles at crosshair**.
2. In the results, press **Everywhere** or **Here** next to each particle you want gone.
3. Adjust the zone's size or position in **Zones** if needed.

Commands (client-side only; nothing is sent to the server):

| Command | Opens |
|---|---|
| `/particles` | Main menu |
| `/particles scan` | Starts a scan at your crosshair |
| `/particles results` | Last scan results |
| `/particles hidden` | Everything hidden |
| `/particles zones` | Zones in this world |

Keybinds (unbound by default, under Controls → Particle Filter): Open Particle Filter, Scan (Shift: last results), Hide/Show Particles of Targeted Block, Toggle Zone Outlines.

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
