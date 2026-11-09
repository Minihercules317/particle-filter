# <Campaign id>: interpretation

<!--
Template for results/<campaign>/report.md, the only hand-written part of a campaign. Everything factual
(method, hardware, hashes, raw-data links, statistics, limitations) is in report.generated.md, which
tools/publish_campaign.py builds from raw data. This file must not restate those facts in its own words.

Rules (PLAN.md §9, AUDIT.md §4):
- Every number here is copied from comparison.md and cites the row it came from: dataset, configuration pair,
  metric. Never round in a way that changes a verdict; never compute new numbers by hand.
- Name the class of every result: synthetic primary (20 TPS), synthetic saturation (reduced TPS) or real world.
  Never combine classes, bound labels, loaders or strata in one statement.
- Report equivalent, inconclusive and unfavourable results as prominently as favourable ones.
- Don't publish while report.generated.md says "FUNCTIONAL / PIPELINE TEST".
Delete this comment block in the finished file.
-->

> **Status:** draft | reviewed by the owner | published.
> **Generated report:** [report.generated.md](report.generated.md), which holds the method, hardware, hashes, raw data,
> statistics and limitations. Rebuild check: `python3 benchmarks/tools/publish_campaign.py verify <campaign>`.
> **Pre-registration:** [PREREGISTRATION.txt](PREREGISTRATION.txt), commit `<hash>` (dated before the first launch).
> **Authorship:** <who wrote this interpretation>. The benchmark code and report generator were written by Claude (AI);
> the owner reviewed the method and approved the runs.

## Question

Which question from PLAN.md §1 this campaign answers (one campaign, one question), and the pre-registered primary
outcome and margin.

## Results by class

### Synthetic, primary 20-TPS dataset

For each comparison: the verdict as compare.py states it (better / worse / equivalent / inconclusive), the mean
difference and its 95% interval, with the bound label and stratum. Quote from comparison.md, e.g.
"C vs B, heavy/typed/vanilla, primary:gpu-bound: mean frame time `<copy>` (95% CI `<copy>`), verdict `<copy>`".

### Synthetic, saturation / stress-test dataset (reduced TPS)

Only if comparison.md has saturation pairs. Achieved TPS and requests per second for both configurations; the
pairs where filtering held 20 TPS and the reference didn't, and the reverse. These are stress tests, not gameplay.

### Real world

Only for a real-world campaign (R scenarios); otherwise "not part of this campaign".

## What the results do and don't support

Plain statements of what follows and what doesn't, including:
- where Particle Filter was slower, and by how much (cited);
- where the difference is inconclusive (the interval crosses the margin), and what that means: not "no effect";
- which hardware state the result holds for (bound label, power state from report.generated.md), and that another
  machine can differ.

## Deviations from the plan

Anything that differs from PREREGISTRATION.txt or PLAN.md: launches that failed their checks (they are in raw/),
windows excluded and why (copied from comparison.md "Excluded"), changed parameters. "None" if none.

## Review

| Date | Reviewer | Outcome |
|---|---|---|
| | | |
