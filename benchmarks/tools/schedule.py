#!/usr/bin/env python3
"""Make a counterbalanced run order for a benchmark campaign.

Each block runs every configuration once. Block orders follow a Williams design (a balanced Latin square):
every configuration appears in every position equally often and, for an even number of configurations,
directly follows every other configuration equally often, so slow drift (heat soak, background tasks) and
carry-over effects do not favour one configuration. The rows of the square are shuffled with a fixed
seed, so the schedule is random but reproducible.

Standard library only.

Usage:
  schedule.py --configs A B C D --blocks 8 --seed 1 [--campaign NAME] [--csv schedule.csv]
"""
from __future__ import annotations

import argparse
import csv
import random
import sys


def williams_rows(n: int) -> list[list[int]]:
    """Williams design for n treatments: n rows if n is even, 2n rows (square plus its mirror) if odd."""
    if n < 1:
        raise ValueError("need at least one configuration")
    first = [0]
    lo, hi = 1, n - 1
    take_lo = True
    while len(first) < n:
        if take_lo:
            first.append(lo)
            lo += 1
        else:
            first.append(hi)
            hi -= 1
        take_lo = not take_lo
    rows = [[(x + shift) % n for x in first] for shift in range(n)]
    if n % 2 == 1:
        rows += [list(reversed(r)) for r in rows]
    return rows


def make_schedule(configs: list[str], blocks: int, seed: int) -> list[list[str]]:
    rows = williams_rows(len(configs))
    rng = random.Random(seed)
    out: list[list[str]] = []
    while len(out) < blocks:
        batch = rows[:]
        rng.shuffle(batch)
        out.extend([[configs[i] for i in r] for r in batch])
    return out[:blocks]


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--configs", nargs="+", required=True)
    p.add_argument("--blocks", type=int, required=True, help="number of blocks (repetitions of every config)")
    p.add_argument("--seed", type=int, required=True, help="record this in the campaign file")
    p.add_argument("--campaign", default="campaign", help="prefix for run ids")
    p.add_argument("--csv", help="write the run sheet here instead of printing it")
    args = p.parse_args(argv)
    if len(set(args.configs)) != len(args.configs):
        p.error("configuration names must be unique")

    rows = []
    for b, order in enumerate(make_schedule(args.configs, args.blocks, args.seed), start=1):
        for pos, config in enumerate(order, start=1):
            rows.append({"run_id": f"{args.campaign}-b{b:02d}-{pos}-{config}", "block": b, "order": pos,
                         "config": config})
    if args.csv:
        with open(args.csv, "w", newline="") as fh:
            w = csv.DictWriter(fh, fieldnames=["run_id", "block", "order", "config"])
            w.writeheader()
            w.writerows(rows)
    else:
        print("run_id,block,order,config")
        for r in rows:
            print(f"{r['run_id']},{r['block']},{r['order']},{r['config']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
