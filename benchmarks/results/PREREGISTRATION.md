# Pre-registered campaigns (2026-11-09)

Fixed before any official launch. Each `pr-*/PREREGISTRATION.txt` holds the exact command, seed, analysis arguments,
rules and the full `campaign --dry-run` schedule. The method is PLAN.md + scenario `S1-synthetic.toml` version 2 at
the commit before the one that added these files; calibration and variance evidence for its choices is in
[calibration-2026-11-09/CALIBRATION.md](calibration-2026-11-09/CALIBRATION.md) (exploratory data, not results).

| Campaign | Question | Configs (reference first) | Level(s) | Blocks | Launches |
|---|---|---|---|---|---|
| `pr-q1-overhead-neoforge` | Q1 overhead, installed but inactive | A, B | idle, 100, 200 | 16 | 32 |
| `pr-q2q5-moderate-neoforge` | Q2-Q5 filtering | B, C, D | 100 | 12 | 12 |
| `pr-q2q5-heavy-neoforge` | Q2-Q5 filtering | B, C, D | 200 | 12 | 12 |
| `pr-q2q5-extreme-neoforge` | Q2-Q5 filtering (Q4: scaling across the three) | B, C, D | 2,000 | 12 | 12 |
| `pr-q8-cmiss-neoforge` | Q8 rules that never match | B, C-miss | 2,000 | 16 | 32 |
| `pr-untyped-neoforge` | untyped spawn path | B, C, D | 200 | 12 | 12 |
| `pr-blockdust-neoforge` | block-break particles | B, C | 200 | 12 | 12 |
| `pr-q7-sodium-neoforge` | Q7 vs Sodium Extra (Sodium in both) | C, E | 200 | 16 | 32 |
| `pr-q1-overhead-forge` | Q1 on Forge 1.20.1 | A, B | idle, 200 | 16 | 32 |
| `pr-q2q5-heavy-forge` | Q2-Q5 on Forge 1.20.1 | B, C, D | 200 | 12 | 12 |

Not pre-registered (not launchable or on hold): Q6 rule/zone-count scaling, the B0 check, real-world scenario R1.

**Protocol for every campaign:** AC power, performance profile, power limits as recorded by `collect_env.py`
(a change fails the launch); window hidden on a special workspace pinned to eDP-2 (scenario `[display]`); the machine
is **not used interactively** while a campaign runs (other applications are left as they are and recorded); one
campaign at a time; `pfbench.py campaign` only. Equivalence margin ±1% of mean frame time; verdicts from 95% Student-t
intervals; synthetic primary (20 TPS) and saturation (reduced TPS) datasets reported separately; inconclusive and
unfavourable results are published like the others.
