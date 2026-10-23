# Changelog

All code in this project is written by Claude (Anthropic's AI model); see the README's "Who did what" section.

## 1.1.0

- New menu design: a dark, flat window with a sidebar for the Scan, Hidden and Zones pages, switches instead of checkboxes, smooth scrolling, and hover tooltips with full particle IDs
- New Active rules switch in the sidebar: apply All rules, Everywhere only (including block particles), Zones only, or None (show all). Your rules are kept; paused ones are marked on the Hidden page. Also on a new Cycle Active Rules keybind (unbound by default)
- New Hold to Show All Particles keybind (unbound by default): every rule is paused while it's held, and your Active rules setting comes back when you let go
- The zone editor is now a side panel, so the zone's outline stays visible in the world while you edit it
- Scan shows its progress, and how often each particle appeared compared to the others
- Hidden page groups entries by where they apply; press Enter in the ID box to hide a particle
- Shift-click − or + on zone sizes and positions to step by 10
- Config files from earlier versions load unchanged; the only addition is the saved Active rules setting, which older versions ignore

## 1.0.2

- In-game hints and the mod description now cover any source of unwanted particles, not just machines

## 1.0.1

- Added the mod icon, shown in the in-game Mods list

## 1.0.0

First release, for Minecraft 1.21.1 / NeoForge 21.1.

- Particle scan around the crosshair, listing particle type IDs (or class names for untyped particles) with counts
- Hide each particle everywhere or only in a zone, with checkboxes
- Zones are named boxes (center X/Z, bottom Y, width × height × length) that you can move, resize, and see outlined in the world while editing
- Hide breaking, mining and block-dust particles by block ID, mod (`namespace:*`), or tag (`#tag`)
- One tabbed menu (`/particles`): Scan, Hidden, Zones
