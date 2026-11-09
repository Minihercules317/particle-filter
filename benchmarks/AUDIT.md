# Auditing a Particle Filter benchmark

How someone who doesn't trust the authors (or the AI that wrote the code) can examine a campaign: what evidence each
launch leaves, what it can and can't establish, the limits of measuring on one machine, and how to replicate a
result or report a discrepancy. Commands are in [REPRODUCING.md](REPRODUCING.md).

## 1. What a campaign directory contains

```
results/<campaign>/
  PREREGISTRATION.txt   design fixed before the first launch (section 4)
  raw/<run-id>/         every launch, byte for byte as the launcher and game wrote it (failed ones too)
  ARCHIVE.json          what was copied; SHA-256 of what was left out (world copy, mod jars, screenshots)
  runs/                 per-window run.json + summary.json, derived from raw/ by ingest_harness.py
  comparison.md/.csv    compare.py output: every pair, every window, every exclusion
  report.generated.md   generated from the files above by publish_campaign.py; no hand-written numbers
  report-args.json      the analysis arguments (reference, margin, metrics), so the rebuild is exact
  report.md             human interpretation, attributed, quoting only numbers found in comparison.md
  SHA256SUMS            SHA-256 of every file above
```

## 2. Evidence that test conditions were equivalent

For each condition, where it is recorded and what checks it. "Check" means `pfbench.py check` (per launch,
`check.json`). "Pairing" means `compare.py` refuses to pair two windows whose `run.json["environment"]` differs and
lists the reason under "Excluded".

| Condition | Recorded in (raw/<run-id>/…) | Enforced or checked by |
|---|---|---|
| Minecraft, loader, Java version and vendor | `harness.json` `versions` (read inside the game) | Check: loader and Minecraft as planned. Pairing: versions |
| Particle Filter jar | `launch.json` (hash at copy time), `harness.json` `mods_folder` (hash read in game), `mod_files` | Check: `mods/` held exactly the copied jars; loaded version = jar version; Forge: named jar built with the release jar |
| Particle Filter rules and mode | `inputs/particlefilter.json` (before), `inputs-after/` (as saved at quit), `phases.csv` mode requested vs read back | Check: every mode switch took effect |
| Other mods and their configs | `harness.json` `mods`, `mod_files`; `launch.json` `render_mods`, `render_environment` | Check: Sodium present only in its mod set. Pairing: rendering-environment hash |
| Harness, launcher and analysis code | `plan.json`/`launch.json` `provenance`: git commit, uncommitted paths, source hashes | Check: harness version = source tree. Report: values that differ are marked |
| Scenario and template world | `launch.json` scenario SHA-256 and template tree hash | Pairing: scenario hash, template hash |
| Game options (render distance, particles, FPS cap, VSync, fullscreen) | `inputs/options.txt`, `harness.json` `setup.options` | Check: Particles = All. frametimes: average near a known cap is flagged |
| Window size, fullscreen, GPU renderer | `harness.json` `setup.window_at_start`, `window_at_end`, `gl` | Check: unchanged within a launch. Pairing: window and renderer |
| JVM arguments, heap, GC | `harness.json` `jvm_args`; `game/gc.log`; `ticks.csv` GC count/time | Pairing: requested JVM args. Per window: GC pauses inside it, `gc_in_window` |
| JIT warmup | `ticks.csv` `jit_time_ms`, `classes_loaded`; `phases.csv` warmup phases | Plan: warmup covers every configuration twice. Window warning `jit_activity` |
| Workload (particle requests, created, alive) | `ticks.csv` every tick | Check: filtered configurations create no target, controls always created, exact live counts, no carry-over. Pairing: per-tick workload within 2% |
| Client tick rate | `ticks.csv` timestamps, `phases.csv` | Every window gets achieved TPS and a class; reduced-TPS pairs go to the saturation dataset |
| Every frame | `frames.csv` (one row per frame) | frametimes: frame count, coverage of the window's wall time, drift between thirds |
| Power settings (AC, profiles, governor, EPP, turbo, RAPL limits) | `env-before.json`, `env-after.json` | Check: unchanged during the launch. Pairing: power-state hash |
| Clocks, temperatures, GPU state over time | `system/system.csv`, `threads.csv`, `gpu.csv` (1 s), aligned through `harness.json` `clock` | CPU-/GPU-bound label per window; datasets never mix labels |
| CPU placement of game threads | `system/threads.csv` (`last_cpu`) | Recorded only: pinning is not allowed on the owner's machine |
| Background load | `env-*.json` `load` (load average, PSI, top processes, memory) | Recorded only, before and after each launch |
| Display and compositor (refresh rate, VRR, tearing, direct scanout) | `env-*.json` `display` | Recorded only, before and after |
| Order of configurations | `launch.json` `campaign` (block, launch in block), `entries`; `raw/campaign-sheet-*.json` | `compare.py` order-balance table; the order is reproducible from the seed (`campaign --dry-run`) |
| Autosaves | `ticks.csv` `server_saves` | Window warning `autosave_in_window` |

**Gaps** (not recorded, or only at two moments): fan state and ambient temperature are not recorded. Background
processes, display state and power settings are read before and after each launch, not continuously. The sampler's
own cost (one Python process plus `nvidia-smi`) is unmeasured, but it runs in every configuration. Nothing records
launches that were started and then deleted. Section 4 covers that.

## 3. What hashes, logs and checks can and cannot establish

| Evidence | Establishes | Does not establish |
|---|---|---|
| `SHA256SUMS` matches | The files are the ones the manifest was made from. Committed to git, its history shows *when* that was | That the files were recorded honestly. Anyone who edits raw data can write a new manifest |
| `verify` rebuild matches | Every derived number (windows, pairs, intervals, verdicts, report tables) follows from `raw/` with the tools at the stated hash. Nothing was typed in or "adjusted" after analysis | That `raw/` is genuine, complete or from the stated machine. That the tools are correct (read them; the unit tests use synthetic data with known answers) |
| Jar and source hashes | Which binaries the launcher copied and the game reported loading. With a reproducible build (REPRODUCING.md §3) they tie the jar to tagged source | That the machine reported truthfully. Launcher and game run on the same, unaudited machine |
| Functional checks pass | The recorded data is internally consistent: workload counts match the plan, modes switched, versions match | That consistent-looking data wasn't fabricated or selected. A check can only test what was recorded |
| Environment snapshots and samples | What the operating system reported at those moments: power limits, clocks, load | What happened between snapshots. Conditions the OS doesn't expose (ambient temperature, firmware behaviour) |
| Campaign sheet, seed, run ids | The executed order is the planned order, and which launches the campaign contains | That no launch was run, discarded and repeated. Only pre-registration and an archive that keeps failed launches make that visible |

Everything above is produced by software on the machine being measured, which is operated by an interested party.
It shows internal consistency and traceability, not honesty. **The only real corroboration is an independent
replication on different hardware.**

## 4. Procedure that makes selective reporting visible

Before the first launch of a performance campaign:

1. Fix the design and commit it: `pfbench.py campaign … --dry-run > results/<campaign>/PREREGISTRATION.txt`, plus
   the analysis arguments (reference, margin, metrics) and any exclusion rule not already in PLAN.md §5. Commit it
   before the first launch, so the git history shows the design came first.
2. Run the campaign with `pfbench.py campaign` only (its sheet records every launch, including ones with failed
   checks). Don't delete launch directories.
3. `archive` every launch of the sheet, failed ones included. `report`, `manifest`, commit.
4. Write `report.md` (interpretation) last, quoting only numbers in `comparison.md`. Equivalent, inconclusive and
   unfavourable results are reported like favourable ones (PLAN.md §9).

An auditor checks: the pre-registration commit predates the campaign's launch timestamps (`launch.json`
`started_utc`), every launch on the sheet is in `raw/`, the analysis arguments match the pre-registration, and
`verify` passes.

## 5. Limitations of single-machine benchmarks

Everything in [templates/limitations.md](templates/limitations.md) (included in every generated report), and in
particular:

- Results are **paired differences on one machine**, not portable FPS numbers. The CPU/GPU balance decides whether
  removing particles changes frame time at all, and it differs between machines.
- **Laptop power and thermal behaviour** varies within a session. It is recorded, not controlled: the owner doesn't
  allow firmware, power, GPU or CPU-affinity changes. Counterbalanced order spreads drift over configurations
  without removing it from individual windows.
- **Hybrid CPU scheduling** (P- vs E-cores) is left to the OS and recorded per thread. On another machine, or another
  kernel, it can behave differently.
- **One software stack**: Linux, Hyprland under XWayland, one NVIDIA driver and the mods' development runtime. Windows,
  macOS, other drivers and production installs are untested.
- **One synthetic workload** and no real-world scenario yet (R1 is on hold). Synthetic results show what Particle
  Filter costs and saves under a controlled particle load, not what a given modpack gains.

## 6. Independent replication

1. Check out the report's commit, build (REPRODUCING.md §3) and confirm the Particle Filter jar hashes (or note why
   they differ).
2. Run `campaign --dry-run` with the published seed and arguments and compare the order with the report's raw data.
3. Run the same campaign on your machine. Record your own decision to run performance measurements
   (`PFBENCH_PERFORMANCE_APPROVED=1`) and anything you changed in the scenario (a changed scenario has a different
   hash, and that's fine if you report it).
4. `archive`, `report` (same reference, margin and metrics), `manifest`.
5. Compare **directions and intervals**, not absolute FPS: does each comparison fall in the same verdict class
   (better / worse / equivalent / inconclusive) and dataset (primary 20 TPS / saturation)? Different hardware can
   legitimately give a different verdict; that is a finding about hardware dependence, not necessarily an error.

## 7. Reporting a discrepancy

Open an issue at https://github.com/Minihercules317/particle-filter/issues with:

- the campaign id and commit you compared against, and `publish_campaign.py verify` output for the published
  campaign (does it rebuild for you?);
- your environment (`env-before.json` of one launch), your `report.generated.md` and `SHA256SUMS`, and where your raw
  data can be downloaded;
- what differs (comparison, dataset, verdict, size) and anything you changed from the published method.

How the project responds to discrepancy reports (for example, linking every report from the affected campaign's
`report.md`) is for the owner to decide and will be stated here once decided.
