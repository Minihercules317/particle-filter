# Changelog

All code in this project is written by Claude (Anthropic's AI model); see the README's "Who did what" section.

## 1.0.1

- Added the mod icon, shown in the in-game Mods list

## 1.0.0

First release, for Minecraft 1.21.1 / NeoForge 21.1.

- Particle scan around the crosshair, listing particle type IDs (or class names for untyped particles) with counts
- Hide each particle everywhere or only in a zone, with checkboxes
- Zones are named boxes (center X/Z, bottom Y, width × height × length) that you can move, resize, and see outlined in the world while editing
- Hide breaking, mining and block-dust particles by block ID, mod (`namespace:*`), or tag (`#tag`)
- One tabbed menu (`/particles`): Scan, Hidden, Zones
