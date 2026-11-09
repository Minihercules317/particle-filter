# <Campaign id>: <one-line purpose>

> Status: draft | reviewed by owner | published. Benchmark code and this report were written by Claude
> (AI); the owner reviewed the design and approved the runs.

## What was compared

- Question(s): Q?
- Configurations: …
- Scenario and version: … (sha256 …)
- Load level(s) and variant(s): …
- CPU-bound / GPU-bound label of the windows: …

## Setup

- Hardware, drivers, OS, compositor: from `env-*.json` (summarize; link the files)
- Minecraft, loader, Java, JVM args, mod list hash:
- Particle Filter version / commit:
- Options that matter (resolution, render distance, VSync/cap, particles):
- Pinning, power profile, cool-down:

## Design

- Blocks: n = …, order: Williams design, seed …
- Warmup, settle, window length:
- Pre-registered margin and primary outcome: mean frame time, ±…%
- Exclusions (with reasons, decided before looking at comparisons):

## Results

Paste `tools/compare.py` output here (it includes every run and every exclusion).

## Interpretation

What the numbers do and don't support. Say explicitly when a result is equivalent or inconclusive,
when Particle Filter was slower, and when the load is synthetic or extreme rather than typical play.

## Raw data

`results/<campaign>/runs/…`, tool versions: git commit …
