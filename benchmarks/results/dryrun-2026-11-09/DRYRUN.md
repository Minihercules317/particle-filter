# Campaign schedule dry run (2026-11-09)

> **Design check, not a measurement.** `pfbench.py campaign --dry-run` launched nothing: it computes launch orders,
> pairability, balance and scripted duration from the scenario file. No game ran, and no performance figure exists
> or is implied. Full output of every command, including each block's launch order: [dry-runs.txt](dry-runs.txt)
> (seed 1, `scenarios/S1-synthetic.toml` version 1, performance timing).

## What was checked

| Property | How | Outcome |
|---|---|---|
| Counterbalancing | Position of every configuration inside its launch and of every launch inside its block; how often each item directly follows each other one | Exact for every proposed design: equal position counts and equal first-order carry-over counts. Exact only when the block count is a multiple of the Williams row count (2 rows for two items, 6 for three). The dry run now says so (e.g. 8 blocks of C,B,D: positions 3/2/3) |
| Configuration coverage | Each comparison PLAN.md §1 needs (B vs A, C vs B, D vs B, D vs C, C-miss vs B, E vs C) against the configurations present | Every question has a campaign below in which its comparison is pairable. C-miss (required by the owner) is its own campaign |
| Pairing rules | Launch shape (entries per launch) is part of the environment compare.py pairs on (PLAN.md §2) | **Problem found and fixed**, see below |
| Loader separation | `--loader` per campaign; loader and loader version in the pairing environment; `publish_campaign.py archive` refuses to mix loaders or labels in one campaign | Forge with `--modset sodium` refused before anything runs; unit test: different loaders never pair |
| Invalid launches | Variant/configuration/mod-set combinations (block_dust with D, E without Sodium, E with untyped, Sodium on Forge) | Now refused for the whole campaign before the first launch. Before, a campaign stopped at the first invalid launch, after earlier launches had run |

## Problem found: combined campaigns produce unpairable data

The launcher puts configurations that share an install state into one launch (B, C, D share one Particle Filter
config; A, C-miss and E each need their own install). compare.py pairs only windows from launches of the same shape,
because warmup and mode switching differ between a configuration measured alone and one measured with others. So:

- `--configs A,B,C,D,C-miss`: B runs in 3-entry launches, A and C-miss alone. **B vs A (Q1) and C-miss vs B (Q8)
  would have produced zero pairs.** 12 of the 18 launches (A's and C-miss's) would only have been comparable with each
  other (A vs C-miss, which answers nothing).
- `--modset sodium --configs A,B,C,E` (the Sodium comparator as first described): C runs with B, E alone. **E vs C
  (Q7) would have produced zero pairs**, and 4 blocks over 3 launch groups is unbalanced.

Fix (`pfbench.py`): `campaign` computes this before running. `--dry-run` prints every needed comparison as
`pairable`, `not answerable` (reference missing) or `IMPOSSIBLE` (shapes differ), and a performance campaign with an
impossible comparison, or a configuration nothing can pair with, is refused. The verdicts were cross-checked
against compare.py: synthetic launches laid out exactly as the schedule would run them give 6 pairs for C and D
vs B and 0 for C-miss vs B and B vs A in the combined design, and 8 pairs for B vs A in the separate Q1 design
(`tools/tests/test_publish.py`, `CampaignAnalysisTests`).

## Proposed campaign set (one question each)

| Question | Design | Launches | Scripted time* |
|---|---|---|---|
| Q1 overhead, installed but inactive | `--configs A,B --levels idle,moderate,heavy --blocks 12` | 24 | 1.82 h |
| Q2-Q5 filtering (per level) | `--configs C,B,D --levels heavy --blocks 12` | 12 | 0.90 h |
| Q8 rules that never match | `--configs B,C-miss --levels extreme --blocks 8` | 16 | 0.81 h |
| Untyped spawn path | `--configs C,B,D --variant untyped --levels heavy --blocks 12` | 12 | 0.90 h |
| Block-break particles | `--configs C,B --variant block_dust --levels heavy --blocks 8` | 8 | 0.49 h |
| Q7 vs Sodium Extra | `--modset sodium --configs C,E --levels heavy --blocks 8` | 16 | 0.81 h |
| Forge Q1 | `--loader forge --configs A,B --levels idle,heavy --blocks 8` | 16 | 1.01 h |
| Forge Q2-Q5 | `--loader forge --configs C,B,D --levels heavy --blocks 12` | 12 | 0.90 h |

\* Scripted phases (warmup, settles, 30 s windows) plus 60 s cool-downs; game start-up and shutdown come on top. The
phase lengths, load levels and block counts are provisional until calibration and the variance study (phases 3-4),
which also decide whether the Q2-Q5 design is repeated per load level.

## Not covered by any campaign yet

- **Q6** rule- and zone-count scaling (C-rules-N, D-zones-N): configs can be generated (`tools/make_pf_config.py`)
  but `pfbench.py` has no configurations for them.
- **B0** (empty config) sanity check for the variance study: designed (PLAN.md §2), not launchable.
- **Real world** (R1): on hold by owner decision.
