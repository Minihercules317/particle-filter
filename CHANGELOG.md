# Changelog

All code in this project is written by Claude (Anthropic's AI model); see the README's "Who did what" section.

## 1.3.0

- Scan results are grouped into folders by mod, so a modpack's worth of particles stays readable. Each mod's folder holds its particles and everything made from its blocks; a particle made from many blocks (like breaking debris) gets its own folder, split by the blocks' mod. A folder's **Everywhere** and **Here** switches hide the whole group with one rule that also covers particles the scan didn't catch, and show partly on when only some of it is hidden. Click a folder to open or close it
- Rules can now cover groups: a mod's particles (`geore:*`), everything made from a block, a mod's blocks or a block tag (`*@geore:*`, `*@#c:ores`), or one particle from certain blocks (`minecraft:block@geore:*`). Type them on the Hidden page or get them from Scan's folders; the Hidden page names them ("Everything made from GeOre blocks"), and hiding a group removes the narrower entries it covers. Block entries from earlier versions (**Hide block**) become such rules automatically and keep working
- Scan also lists particles your rules already hide, dimmed and after those still appearing, so you can see what's left once your rules are in place and show any of them again
- Here makes a zone per source of a particle, fitted to where it appeared, so two sources far apart get separate zones. Zones that overlap enough merge into one box around both. A particle with more sources than Here makes zones for gets a suggestion to hide it everywhere instead. Hover a row to see its zones. If there's already a zone at the scanned spot, Here uses it; a button switches to new zones and back
- New **Zone options** on the Scan page, closed by default: how far apart spots can be and still count as one source (1-32 blocks, 6 by default), how many zones one particle gets (1-32, 8 by default), and how much zones must overlap to merge (0-100% of the smaller zone, 50% by default; at 0% zones that touch face to face merge too; zones follow the slider as you drag it). While open, the number beside each Here switch says how many zones it makes
- Zones made with Here are the smallest box around where the particles appeared, with no extra margin, and keep fitting as you switch particles on and off or change Zone options until you edit the zone yourself or scan again
- New **Grow to fit**: when a zone hides a particle that still appears just outside it, a notice offers to enlarge the zone to cover those spots. Hover it to preview the new outline in the world first. Zones only grow, never shrink
- Scan: the scan length (1-60 seconds, 3 by default) and radius (1-32 blocks, 6 by default) are adjustable and saved
- Hidden page: switch an entry off to keep it without applying it, or delete it. Each zone shows its coordinates
- Zone editor: sizes read Side 1 × Height × Side 2 with the number of blocks affected, and each side is labeled left-right or front-back from your view. Every zone gets its own outline color, so zones that touch or overlap are easy to tell apart
- The menu reopens on the last page you used, until you leave the world or server. A **?** button opens a quick start guide, and Back buttons are labeled
- Particles made from a block, such as breaking and mining debris or the dust when you land on a block, are listed once per block in Scan (e.g. `TerrainParticle (minecraft:stone)`), so you can hide one block's everywhere or in a zone and keep another's. A rule for the particle itself still covers every block
- Move the menu by dragging its background, and resize it from its right or bottom edge or corner. It reopens where you left it; double-click the background to put it back
- Config in the Mods list now opens two new settings, both off by default, instead of the menu (a button there still opens it in a world): **Walk while the menu is open** (move, jump and sneak while a Particle Filter screen is open, except while typing in a box) and **Lines to zones** (a line from your crosshair to each zone in its outline color, shown through blocks whenever outlines show)
- Long text in the menus wraps instead of being cut off with "…"
- If the config file can't be read (for example after a typo in a hand edit), it is kept as a backup copy (`particlefilter.broken-<time>.json`) instead of being overwritten with defaults
- Config files from earlier versions load as they are: block entries become the matching group rules, and the new settings get defaults. An earlier version opening the config afterwards no longer sees those block entries

## 1.2.0

- Now also available for Minecraft 1.20.1 with Forge 47+. Each release has one jar per version and loader: `particlefilter-neoforge-<version>+1.21.1.jar` for NeoForge 1.21.1 and `particlefilter-forge-<version>+1.20.1.jar` for Forge 1.20.1. Both have the same features

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
