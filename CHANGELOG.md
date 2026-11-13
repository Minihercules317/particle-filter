# Changelog

All code in this project is written by Claude (Anthropic's AI model); see the README's "Who did what" section.

## 1.3.0

- New Diagnostic page, also opened with `/particles diagnostic`: it records every particle around you within render distance and splits them into those still appearing and those your rules already hide, so you can see what is left after your rules are in place
- On the Diagnostic page, Here gives each source of a particle its own zone, so two sources far apart no longer end up in one huge zone. Spots where a particle appeared count as one source when they are within a distance you set (**Same source within**, 1-32 blocks, 6 by default), even through other spots in between. A particle with more than 8 sources gets zones for its 8 busiest, with a suggestion to hide it everywhere instead
- Zones that overlap by at least a share you set with the **Merge zones sharing** slider (1-100% of the smaller zone, 50% by default) merge into one box around both
- Zones made with Here are now the smallest box around where the particles appeared, with no extra margin, and keep fitting as you switch particles on and off until you edit the zone yourself. Scanning a spot that already has a zone reuses that zone
- New **Grow to fit**: when particles still appear just outside a zone that hides them, a notice offers to enlarge the zone to cover them. Hover it to preview the new outline in the world first. Zones only grow, never shrink
- Scan: the scan length is adjustable (1-60 seconds, 3 by default) and saved. Particles you have already hidden are listed too, so you can show them again from the results. A New zone button makes a separate zone even when you are inside an existing one
- Hidden page: switch an entry off to keep it without applying it, or delete it. Each zone shows its coordinates
- Zone editor: sizes read Side 1 × Height × Side 2 with the number of blocks affected, and each side is labeled left-right or front-back from your view. Every zone gets its own outline color, so zones that touch or overlap are easy to tell apart
- The menu reopens on the last page you used, until you leave the world or server. A **?** button opens a quick start guide, and Back buttons are labeled
- Long text in the menus wraps instead of being cut off with "…"
- If the config file can't be read (for example after a typo in a hand edit), it is kept as a backup copy (`particlefilter.broken-<time>.json`) instead of being overwritten with defaults
- Config files from earlier versions load unchanged; the new settings get defaults

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
