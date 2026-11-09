# Reproducing the Particle Filter benchmarks

For someone who wants to build and run the benchmark harness, rerun the analysis, or check a published result
without Claude Code and without trusting anyone's summary. Everything here uses plain `git`, a JDK, Gradle (through
the wrapper in the repository) and Python's standard library. How to judge what a check does and doesn't prove is
in [AUDIT.md](AUDIT.md).

> **Status (2026-11-09): no performance results exist.** The harness, launcher and analysis are built and pass
> functional checks and unit tests. Performance campaigns wait for the owner's machine to be fixed (HANDOFF.md).
> The commands below work today for functional runs and for checking the tools.

Nothing in this guide touches a Minecraft installation, launcher profile, world or system setting. Every game
directory is created under `benchmarks/harness/run/` (gitignored). Gradle caches its downloads in `~/.gradle/`, as
for any Gradle project, separate from the Minecraft launcher's own files.

## 1. Requirements

| Need | Version used by the owner | Required? | Notes |
|---|---|---|---|
| Linux | Omarchy (Arch) 4.5, kernel 7.2 | **yes** | The launcher and sampler read `/proc` and `/sys`; `pgrep` (procps) finds the game. |
| JDK 21 on `PATH`/`JAVA_HOME` | Temurin 21.0.12 | **yes** | Runs Gradle and the NeoForge game. Gradle downloads a Java 17 toolchain for Forge (foojay resolver) on first use. If `mise` and a `mise.toml` are present, the launcher uses `mise exec --` instead; neither is needed. |
| Python | 3.14.8 | **yes, ≥ 3.11** | Standard library only (`tomllib`). Nothing to `pip install`. |
| Network access | n/a | **once** | Preparing the Gradle cache (step 3). Launches then run `--offline` and download nothing. |
| RAM | 32 GB | ≥ 8 GB | Fixed 4 GB game heap (`scenarios/S1-synthetic.toml`, `[jvm]`). |
| NVIDIA GPU + `nvidia-smi` | RTX 5070 Ti Laptop, driver 615.78.08 | optional | Without it there are no GPU samples and every window is labelled `unlabelled` instead of CPU/GPU-bound. |
| Hyprland (`hyprctl`) | 0.56.2 | optional | Only for `--hide` (functional runs) and for recording the window state from the compositor. Other desktops: the harness records the window size from inside the game. |
| MangoHud | 0.8.4 | optional | Only for `--mangohud` (an independent frame log, phase 2b). |
| Sodium 0.8.13 + Sodium Extra 0.9.5 (NeoForge 1.21.1) | | optional | Only for `--modset sodium` (configuration E). See step 4. |

## 2. Get the exact code

```sh
git clone https://github.com/Minihercules317/particle-filter.git
cd particle-filter
git checkout <commit>        # the git_commit a report lists under "Binaries and inputs"
```

A report built from uncommitted changes lists those paths (`git_dirty_paths`); such a report can't be reproduced
exactly and says so. The benchmark suite currently lives on the branch `benchmarks/foundation`, which has not been
published yet.

## 3. Build once (online)

From the repository root:

```sh
./gradlew build buildAndCollect                        # Particle Filter jars for both loaders
./gradlew -p benchmarks/harness prepareClientRun       # NeoForge 1.21.1 harness + Minecraft + assets
./gradlew -p benchmarks/harness-forge prepareClientRun # Forge 1.20.1 harness + Minecraft + assets
python3 -m unittest discover -s benchmarks/tools/tests # analysis and launcher tests on synthetic data (no game)
```

**Check the Particle Filter jars against the release.** The jars in `build/libs/<version>/` should match the SHA-256
values in the GitHub release notes and the hash each report lists. On the owner's machine, a clean checkout of tag
`v1.2.0` rebuilt with Temurin 21.0.12 produced byte-identical jars (NeoForge `0806e94c…`, Forge `5d7e1bf5…`) and
the Forge named-mappings jar the Forge harness loads (`613c58b6…`; `versions/1.20.1-forge/build/devlibs/`).
Other JDK builds may differ. If yours do, the release jar can still be passed explicitly with
`pfbench.py run --pf-jar` (NeoForge). For Forge the named-mappings jar isn't published, so its hash is only
reproducible by building.

## 4. Optional: rendering mods for configuration E

Download the two jars from their Modrinth version pages and put them under `benchmarks/harness/run/downloads/`. The
launcher refuses them unless their SHA-256 equals the pinned value (`RENDER_MODS` in `harness/pfbench.py`):

| File | Into | SHA-256 | Source |
|---|---|---|---|
| `sodium-neoforge-0.8.13+mc1.21.1.jar` | `run/downloads/sodium/` | `575d187b15328d7bddd8a9b1bee64333c22c14735f5780ec49eac0cd5503c08d` | https://modrinth.com/mod/sodium/version/mc1.21.1-0.8.13-neoforge |
| `sodium-extra-neoforge-0.9.5+mc1.21.1.jar` | `run/downloads/sodium-extra/` | `37c33efdee16fc515392fd422b6ec8769cbc1079f6a2eb6cff2072411d511faf` | https://modrinth.com/mod/sodium-extra/version/mc1.21.1-0.9.5+neoforge |

Their shared configs are in `benchmarks/configs/sodium/`. These jars are third-party and are never committed or
archived with results (reports carry their hashes).

## 5. Supported configurations

| Loader (`--loader`) | Minecraft | Mod sets (`--modset`) | Configurations | Variants (`--variant`) |
|---|---|---|---|---|
| `neoforge` (default) | 1.21.1, NeoForge 21.1.250 | `vanilla`, `sodium` | A, B, C, D, C-miss; E (only with `sodium`) | `typed`, `untyped`, `block_dust` (no D), `vanilla` (end_rod); E only with `typed`/`vanilla` |
| `forge` | 1.20.1, Forge 47.4.28 | `vanilla` | A, B, C, D, C-miss | as above |

Configurations (PLAN.md §2): **A** not installed, **B** installed with Active rules None, **C** target hidden
everywhere, **D** target hidden in one zone around all emitters, **C-miss** rules that never match, **E** Sodium
Extra hides the target. Load levels are in `scenarios/S1-synthetic.toml` `[levels]` (provisional until calibration).
Rule- and zone-count scaling (Q6: C-rules-N, D-zones-N) and the B0 sanity check are designed but **not yet
launchable** by `pfbench.py`.

The scenario file's `minecraft`/`loader` fields name the first target only; the versions that actually ran are in
each launch's `harness.json` (`versions`) and are checked against the plan.

## 6. Functional runs (allowed now; not performance data)

```sh
python3 benchmarks/harness/pfbench.py template                    # once per loader: the void template world
python3 benchmarks/harness/pfbench.py template --loader forge
python3 benchmarks/harness/pfbench.py run --functional --sequence C:moderate,B:moderate,D:moderate
python3 benchmarks/harness/pfbench.py run --functional --loader forge --sequence A:moderate
python3 benchmarks/harness/pfbench.py check benchmarks/harness/run/runs/<run-id>   # rerun the checks
```

`--functional` means 5-second phases, a 60 FPS cap and a window: it checks the plumbing, never performance. Each
launch directory gets `check.json` with every functional check.

## 7. Campaign designs

One campaign answers one question. The launcher groups configurations that share an install state into one launch
and counterbalances launches and entries (Williams design, seeded). `compare.py` only pairs windows from launches of
the same shape, so **mixing questions in one campaign can make whole comparisons impossible**.
`campaign --dry-run` prints the order, which comparisons can be paired, the balance and the scripted duration, and a
performance campaign that would produce unpairable data is refused. The dry runs of the designs below are recorded in
[results/dryrun-2026-11-09/DRYRUN.md](results/dryrun-2026-11-09/DRYRUN.md).

| Question | Command (add `--seed N`; `--dry-run` first) | Reference |
|---|---|---|
| Q1 overhead, installed but inactive | `campaign --configs A,B --levels idle,moderate,heavy --blocks 16` | A |
| Q2-Q5 filtering, global vs zones | `campaign --configs C,B,D --levels heavy --blocks 12` (one campaign per level) | B |
| Q8 rules that never match | `campaign --configs B,C-miss --levels extreme --blocks 16` | B |
| Spawn paths | `--variant untyped` (C,B,D, 12 blocks) and `--variant block_dust` (C,B, 12 blocks) | B |
| Q7 vs Sodium Extra | `campaign --modset sodium --configs C,E --levels heavy --blocks 16` | C |
| Forge 1.20.1 replication | the Q1 and Q2-Q5 commands with `--loader forge` | as above |

Block counts come from the variance study (PLAN.md §5, set 2026-11-09): 16 for comparisons that need separate
launches (B vs A, C-miss vs B, E vs C), 12 for within-launch ones; each a multiple of the Williams row count (2 rows
for two items, 6 for three), which the dry run checks. Performance runs follow the scenario's `[display]`: the
window is hidden on a special workspace pinned to the named monitor (eDP-2 on the owner's machine; name your own).
Run campaigns unattended: desktop use shifted frame rates ~10% in the variance study.

Performance campaigns are interlocked: without `--functional` the launcher refuses unless
`PFBENCH_PERFORMANCE_APPROVED=1` is set. On the owner's machine that waits for the power-limit fix. On another
machine it is the replicator's own decision, recorded with their report.

## 8. From launches to a report

```sh
C=benchmarks/results/<campaign>
T=benchmarks/tools
python3 $T/publish_campaign.py archive  $C benchmarks/harness/run/runs/<campaign>-b*   # raw data into $C/raw/
python3 $T/publish_campaign.py report   $C --reference B --margin-pct 1                 # runs/, comparison.*, report.generated.md
python3 $T/publish_campaign.py manifest $C                                              # SHA256SUMS
python3 $T/publish_campaign.py verify   $C                                              # hashes + rebuild from raw/
```

- `archive` copies each launch directory byte for byte, except the world copy, mod jars, screenshots and
  Minecraft's telemetry and user caches. These are listed with their SHA-256 in `ARCHIVE.json`. It also lists files
  that contain the owner's home directory path, for review before publishing.
- `report` rebuilds everything derived (`runs/`, `comparison.md`, `comparison.csv`, `report.generated.md`) from
  `raw/` alone, using `ingest_harness.py` and `compare.py`. The generated report has the classification
  (synthetic, real-world or functional; primary 20-TPS vs saturation datasets), the methodology, hardware and
  software as recorded (values that differ between launches are marked), binary and input hashes, links to every
  raw launch, the statistical results and the standard limitations.
- Human interpretation goes in a separate `report.md` (template: `templates/report.md`). Every number it quotes
  must be found in `comparison.md`.
- Large campaigns: `raw/` can be published as an archive attached to a release instead of in git. `SHA256SUMS`, committed
  with the reports, still pins every file.

### Check a published campaign

```sh
sha256sum -c SHA256SUMS                                          # inside the campaign directory: files unchanged
python3 benchmarks/tools/publish_campaign.py verify <campaign>   # same, plus a full rebuild from raw/
```

`verify` copies `raw/` to a temporary directory, rebuilds every derived file with the stored `report-args.json`
and compares them byte for byte with the published ones. It works from any checkout location. A difference in the
"How this report was made" tools hash means the analysis code differs from the one that produced the report.

## 9. Assumptions found on the owner's machine

Recorded so that nobody mistakes them for requirements, and so that a replication on another machine knows what to
record instead.

| Assumption | Where | Effect elsewhere |
|---|---|---|
| Java comes from `mise` (untracked `mise.toml`); on this machine `java` is a mise shim that fails outside the repo | `pfbench.py java_prefix()` | Used only if mise and `mise.toml` exist; otherwise plain `./gradlew` |
| Gradle cache already filled by the main build | launches use `--offline` | Run step 3 once online |
| Hyprland 0.56 Lua dispatch for hiding the window | `pfbench.py hide_window` | `--hide` does nothing without `hyprctl`; performance runs don't hide anyway |
| NVIDIA dGPU on DP-1, Intel iGPU on the laptop panel (PRIME) | PLAN.md §4 controlled variables | Use your own display; the window size and renderer are recorded and must match within a campaign |
| Intel hybrid CPU (P-cores 0-7, E-cores 8-23), `intel-rapl` power limits | `collect_env.py`, `sample_system.py` | Fields that don't exist are recorded as absent; the power-state comparison uses what exists |
| Fullscreen 2560×1440, `maxFps 260`, VSync off | scenario `[options]` | Recorded; any resolution works as long as it is the same within a campaign |
| One dev client at a time | `pgrep -f net.neoforged.devlaunch.Main` | Close other Gradle/NeoForge dev clients first (the launcher refuses otherwise) |
| The template world is created per machine | `pfbench.py template` | Its tree hash differs between machines; the world is generated from the scenario's seed and settings |
