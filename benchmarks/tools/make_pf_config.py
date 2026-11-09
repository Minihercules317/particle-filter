#!/usr/bin/env python3
"""Write Particle Filter config files (config/particlefilter.json) for benchmark configurations.

The format is the one PfConfig reads (hiddenParticles, hiddenBlocks, zones, mode). The generated file goes
into a benchmark run directory, never into a player's instance. Standard library only.

Configurations (see PLAN.md §2):
  synthetic   global rule + one enclosing zone for the target; mode chosen with --mode (B=NONE, C=EVERYWHERE, D=ZONES)
  miss        --rules N global rules that never match the workload (C-miss)
  rules       target + N-1 non-matching global rules (C-rules-N)
  zones       N-1 non-matching zones elsewhere, then the matching zone last (D-zones-N, worst case for the loop)

Usage:
  make_pf_config.py synthetic --mode NONE --out run/1.21.1-neoforge/config/particlefilter.json
  make_pf_config.py zones --count 100 --out ...
"""
from __future__ import annotations

import argparse
import json
import sys
import uuid

# Defaults match benchmarks/scenarios/S1-synthetic.toml.
WORLD = "pfbench"
DIMENSION = "minecraft:overworld"
TARGET = "pfbench:target"
# Encloses the whole emitter plane (x -16..17, y 90..120, z 10..19).
TARGET_ZONE = {"x": 0, "y": 90, "z": 14, "width": 33, "height": 30, "length": 9}
MODES = ("ALL", "EVERYWHERE", "ZONES", "NONE")


def zone(name: str, particles: list[str], x: int, y: int, z: int, width: int, height: int, length: int) -> dict:
    # Deterministic ids so regenerated files are byte-identical.
    zid = str(uuid.uuid5(uuid.NAMESPACE_URL, f"pfbench/{name}/{x}/{y}/{z}"))
    return {"id": zid, "name": name, "world": WORLD, "dimension": DIMENSION, "x": x, "y": y, "z": z,
            "width": width, "height": height, "length": length, "particles": particles}


def non_matching_ids(n: int) -> list[str]:
    """Particle ids that never occur in the workload but look like real ones (same namespace length mix)."""
    return [f"pfbench:unused_{i:04d}" for i in range(n)]


def build(kind: str, mode: str, count: int, target: str) -> dict:
    data = {"hiddenParticles": [], "hiddenBlocks": [], "zones": [], "mode": mode}
    if kind == "synthetic":
        data["hiddenParticles"] = [target]
        data["zones"] = [zone("Target zone", [target], **TARGET_ZONE)]
    elif kind == "miss":
        data["hiddenParticles"] = non_matching_ids(count)
    elif kind == "rules":
        data["hiddenParticles"] = non_matching_ids(count - 1) + [target]
    elif kind == "zones":
        # Same size as the target zone, placed far away (never containing a spawn), on a 64-block grid.
        for i in range(count - 1):
            data["zones"].append(zone(f"Other zone {i + 1}", [target], x=1000 + 64 * (i % 32), y=90,
                                      z=1000 + 64 * (i // 32), width=33, height=30, length=9))
        data["zones"].append(zone("Target zone", [target], **TARGET_ZONE))
    else:
        raise ValueError(kind)
    return data


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("kind", choices=("synthetic", "miss", "rules", "zones"))
    p.add_argument("--mode", choices=MODES, help="Active rules mode (default: NONE for synthetic, EVERYWHERE for "
                                                 "miss/rules, ZONES for zones)")
    p.add_argument("--count", type=int, default=1, help="number of rules or zones")
    p.add_argument("--target", default=TARGET)
    p.add_argument("--out", help="file to write (default: stdout)")
    args = p.parse_args(argv)
    if args.count < 1:
        p.error("--count must be at least 1")
    mode = args.mode or {"synthetic": "NONE", "miss": "EVERYWHERE", "rules": "EVERYWHERE", "zones": "ZONES"}[args.kind]
    text = json.dumps(build(args.kind, mode, args.count, args.target), indent=2) + "\n"
    if args.out:
        with open(args.out, "w") as fh:
            fh.write(text)
    else:
        sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
