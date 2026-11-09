#!/usr/bin/env python3
"""Sample CPU (per game thread), CPU clocks/temperature and NVIDIA GPU state while a benchmark runs.

Read-only and low-overhead: reads /proc and /sys once per interval and leaves GPU polling to one
`nvidia-smi -lms` process. Writes three CSVs into --out-dir:

  threads.csv  t_s, tid, name, cpu_pct, last_cpu       (threads above --min-thread-pct, plus named ones)
  system.csv   t_s, cpu_busy_pct, pcore_mhz_mean, ecore_mhz_mean, pkg_temp_c, game_rss_mb
  gpu.csv      nvidia-smi output (timestamp, utilization, clocks, power, temperature, P-state)

cpu_pct is percent of one logical CPU (a thread using a full core reads 100). last_cpu is the CPU the
thread last ran on, which shows P-core vs E-core placement on hybrid Intel CPUs.

Stops after --duration seconds, when the process exits, or on Ctrl-C. Standard library only.

Usage:
  sample_system.py --pid PID --out-dir DIR [--interval 1.0] [--duration 120]
  sample_system.py --match 'forgeclientdev|neoforge' --out-dir DIR ...   # find the game by command line
"""
from __future__ import annotations

import argparse
import csv
import glob
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

CLK_TCK = os.sysconf("SC_CLK_TCK")
# Always written when present, even when idle, so their absence is visible in the data.
WATCHED_THREADS = ("Render thread", "Server thread", "Worker-Main", "Particle", "C2ME", "GC Thread", "G1 ")


def find_pid(pattern: str) -> int | None:
    rx = re.compile(pattern)
    me = os.getpid()
    for d in glob.glob("/proc/[0-9]*"):
        pid = int(Path(d).name)
        if pid == me:
            continue
        try:
            cmd = Path(d, "cmdline").read_bytes().replace(b"\0", b" ").decode(errors="replace")
        except OSError:
            continue
        if "java" in cmd and rx.search(cmd) and "sample_system.py" not in cmd:
            return pid
    return None


def read_cpu_list(path: str) -> list[int]:
    try:
        text = Path(path).read_text().strip()
    except OSError:
        return []
    cpus: list[int] = []
    for part in text.split(","):
        if "-" in part:
            a, b = part.split("-")
            cpus.extend(range(int(a), int(b) + 1))
        elif part:
            cpus.append(int(part))
    return cpus


def thread_times(pid: int) -> dict[int, tuple[str, int, int]]:
    """tid -> (name, utime+stime ticks, last cpu)."""
    out = {}
    for d in glob.glob(f"/proc/{pid}/task/*"):
        try:
            stat = Path(d, "stat").read_text()
            name = Path(d, "comm").read_text().strip()
        except OSError:
            continue
        # comm may contain spaces/parens; fields after the last ')' are fixed.
        fields = stat[stat.rfind(")") + 2:].split()
        ticks = int(fields[11]) + int(fields[12])   # utime, stime (fields 14 and 15 of stat)
        out[int(Path(d).name)] = (name, ticks, int(fields[36]))  # processor (field 39)
    return out


def cpu_busy() -> tuple[int, int]:
    fields = [int(x) for x in Path("/proc/stat").read_text().splitlines()[0].split()[1:]]
    idle = fields[3] + fields[4]
    return sum(fields) - idle, sum(fields)


def mean_mhz(cpus: list[int]) -> float | None:
    vals = []
    for c in cpus:
        try:
            vals.append(int(Path(f"/sys/devices/system/cpu/cpu{c}/cpufreq/scaling_cur_freq").read_text()) / 1000)
        except OSError:
            pass
    return round(sum(vals) / len(vals), 1) if vals else None


def package_temp_path() -> str | None:
    for hw in glob.glob("/sys/class/hwmon/hwmon*"):
        try:
            if Path(hw, "name").read_text().strip() != "coretemp":
                continue
        except OSError:
            continue
        for label in glob.glob(f"{hw}/temp*_label"):
            if Path(label).read_text().strip().startswith("Package"):
                return label.replace("_label", "_input")
    return None


def rss_mb(pid: int) -> float | None:
    try:
        for line in Path(f"/proc/{pid}/status").read_text().splitlines():
            if line.startswith("VmRSS:"):
                return round(int(line.split()[1]) / 1024, 1)
    except OSError:
        return None
    return None


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    target = p.add_mutually_exclusive_group(required=True)
    target.add_argument("--pid", type=int)
    target.add_argument("--match", help="regex matched against java command lines")
    p.add_argument("--out-dir", type=Path, required=True)
    p.add_argument("--interval", type=float, default=1.0)
    p.add_argument("--duration", type=float, default=None)
    p.add_argument("--min-thread-pct", type=float, default=1.0)
    p.add_argument("--self-cpu", type=int, help="pin this sampler to one CPU. NOT USED in this project: the owner "
                                                "prohibits CPU affinity changes (benchmarks/HANDOFF.md)")
    p.add_argument("--no-gpu", action="store_true")
    args = p.parse_args(argv)

    pid = args.pid or find_pid(args.match)
    if pid is None or not Path(f"/proc/{pid}").exists():
        print("game process not found", file=sys.stderr)
        return 1
    if args.self_cpu is not None:
        os.sched_setaffinity(0, {args.self_cpu})
    args.out_dir.mkdir(parents=True, exist_ok=True)

    gpu_proc = None
    if not args.no_gpu and shutil.which("nvidia-smi"):
        q = "timestamp,utilization.gpu,utilization.memory,clocks.gr,clocks.mem,power.draw,temperature.gpu,pstate,clocks_event_reasons.active"
        gpu_proc = subprocess.Popen(["nvidia-smi", f"--query-gpu={q}", "--format=csv",
                                     f"-lms={max(100, int(args.interval * 1000))}", "-f", str(args.out_dir / "gpu.csv")])

    pcores = read_cpu_list("/sys/devices/cpu_core/cpus")
    ecores = read_cpu_list("/sys/devices/cpu_atom/cpus")
    temp_path = package_temp_path()
    start = time.monotonic()
    prev_threads = thread_times(pid)
    prev_busy, prev_total = cpu_busy()
    print(f"sampling pid {pid} every {args.interval}s into {args.out_dir}", file=sys.stderr)
    with open(args.out_dir / "threads.csv", "w", newline="") as tf, open(args.out_dir / "system.csv", "w", newline="") as sf:
        tw, sw = csv.writer(tf), csv.writer(sf)
        tw.writerow(["t_s", "tid", "name", "cpu_pct", "last_cpu"])
        sw.writerow(["t_s", "cpu_busy_pct", "pcore_mhz_mean", "ecore_mhz_mean", "pkg_temp_c", "game_rss_mb"])
        try:
            next_t = start + args.interval
            while True:
                time.sleep(max(0.0, next_t - time.monotonic()))
                next_t += args.interval
                if not Path(f"/proc/{pid}").exists():
                    break
                now = time.monotonic()
                t = round(now - start, 3)
                cur = thread_times(pid)
                for tid, (name, ticks, last_cpu) in cur.items():
                    before = prev_threads.get(tid)
                    if before is None:
                        continue
                    pct = (ticks - before[1]) / CLK_TCK / args.interval * 100
                    if pct >= args.min_thread_pct or name.startswith(WATCHED_THREADS):
                        tw.writerow([t, tid, name, round(pct, 1), last_cpu])
                prev_threads = cur
                busy, total = cpu_busy()
                busy_pct = (busy - prev_busy) / max(1, total - prev_total) * 100
                prev_busy, prev_total = busy, total
                temp = None
                if temp_path:
                    try:
                        temp = int(Path(temp_path).read_text()) / 1000
                    except OSError:
                        pass
                sw.writerow([t, round(busy_pct, 1), mean_mhz(pcores), mean_mhz(ecores), temp, rss_mb(pid)])
                tf.flush()
                sf.flush()
                if args.duration is not None and now - start >= args.duration:
                    break
        except KeyboardInterrupt:
            pass
        finally:
            if gpu_proc:
                gpu_proc.terminate()
                gpu_proc.wait(timeout=5)
    return 0


if __name__ == "__main__":
    sys.exit(main())
