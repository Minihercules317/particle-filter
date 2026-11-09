# Branch history rewrite (2026-11-09)

Before publication, the history of `benchmarks/foundation` was rewritten once to remove two private multiplayer
server addresses from `benchmarks/PLAN.md` and `benchmarks/scenarios/R1-atm10-sky.md` (introduced in the branch's
first commit). Nothing else changed: every rewritten commit has the same message, author, dates and tree as before
except for those two lines, and the final tree is byte-identical. Raw launch data recorded before the rewrite (in
`results/*/runs/*/launch.json`, `plan.json`, `harness.json`, `env-*.json`) names the old commit hashes; they are kept
as recorded, and this table maps them. Commits made after the rewrite have no old hash.

| Old commit (in raw data) | Commit in the published history |
|---|---|
| `6db5752` | `48e7018` |
| `5d6e6fb` | `8a93ee9` |
| `da4f6ea` | `a2d89bd` |
| `dae5dda` | `9b4f82e` |
| `bee31ca` | `dd2656d` |
| `615845d` | `97a5886` |
| `fc67e49` | `a26c33d` |
| `85cb36f` | `19e0b1c` |
| `858f1bf` | `01a1e0a` |
| `fc2906f` | `10aa2a5` |
