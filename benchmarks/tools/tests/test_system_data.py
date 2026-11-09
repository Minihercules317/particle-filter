"""Tests for the system data a launch records (sampler, GC log, environment snapshots) and how ingest and the
functional checks use it. Synthetic data with known answers; the sampler smoke test samples a `sleep` process.

Run from the repository root:  python3 -m unittest discover -s benchmarks/tools/tests -v
"""
from __future__ import annotations

import csv
import json
import subprocess
import sys
import unittest
from datetime import datetime, timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_pipeline import TempDir, make_run, quiet  # noqa: E402

import ingest_harness  # noqa: E402
import pfbench  # noqa: E402
import system_data  # noqa: E402

TOOLS = Path(__file__).resolve().parents[1]
SAMPLER_EPOCH_NS = 1_762_700_000_000_000_000   # wall clock at the sampler's start
SAMPLER_MONO_NS = 50_000_000_000                # CLOCK_MONOTONIC at the sampler's start
UTC_OFFSET_S = -5 * 3600
NVIDIA_GPU = "NVIDIA GeForce RTX 4070 Laptop GPU/PCIe/SSE2"


def nvidia_time(mono_ns: int) -> str:
    """nvidia-smi's local wall-clock timestamp for a monotonic time (millisecond resolution)."""
    epoch_s = (SAMPLER_EPOCH_NS + mono_ns - SAMPLER_MONO_NS) / 1e9
    local = datetime(1970, 1, 1) + timedelta(seconds=epoch_s + UTC_OFFSET_S)
    return local.strftime("%Y/%m/%d %H:%M:%S.%f")[:-3]


def write_sampler(directory: Path, seconds: int, render_pct, gpu_util, gpu_clock, *, gpu: bool = True,
                  pcpus=(0, 1, 2, 3), render_cpu=lambda k: 1, driver_threads: bool = False,
                  throttle=None) -> None:
    """One sample per second for `seconds` s. render_pct/gpu_util/gpu_clock: value or function of the second k."""
    val = lambda v, k: v(k) if callable(v) else v  # noqa: E731
    directory.mkdir(parents=True)
    meta = {"schema": 1, "interval_s": 1.0, "performance_cpus": list(pcpus), "efficiency_cpus": [8, 9],
            "gpu_query": "timestamp,..." if gpu else None,
            "clock_start": {"epoch_ns": SAMPLER_EPOCH_NS, "mono_ns": SAMPLER_MONO_NS, "uncertainty_ns": 100,
                            "utc_offset_s": UTC_OFFSET_S}}
    (directory / "meta.json").write_text(json.dumps(meta))
    with open(directory / "system.csv", "w", newline="") as s, open(directory / "threads.csv", "w", newline="") as t:
        sw, tw = csv.writer(s), csv.writer(t)
        sw.writerow(["t_s", "mono_ns", "cpu_busy_pct", "pcore_mhz_mean", "ecore_mhz_mean", "pkg_temp_c", "game_rss_mb"]
                    + (["core_throttle_count", "package_throttle_count"] if throttle else []))
        tw.writerow(["t_s", "mono_ns", "tid", "name", "cpu_pct", "last_cpu"])
        for k in range(1, seconds + 1):
            mono = SAMPLER_MONO_NS + k * 1_000_000_000
            sw.writerow([k, mono, 20.0, 4000.0, 3000.0, 60 + k % 3, 4096] + (list(throttle(k)) if throttle else []))
            if driver_threads:   # NVIDIA driver workers inherit the name; they are created after the game's thread
                tw.writerow([k, mono, 107, "Render thread", 30.0, 12])
                tw.writerow([k, mono, 108, "Render thread", 0.0, 13])
            tw.writerow([k, mono, 101, "Render thread", val(render_pct, k), render_cpu(k)])
            tw.writerow([k, mono, 102, "Server thread", 10.0, 9])
    if gpu:
        with open(directory / "gpu.csv", "w", newline="") as g:
            g.write("timestamp, utilization.gpu [%], utilization.memory [%], clocks.current.graphics [MHz], "
                    "clocks.current.memory [MHz], power.draw [W], temperature.gpu, pstate, clocks_event_reasons.active\n")
            for k in range(0, seconds + 1):
                mono = SAMPLER_MONO_NS + k * 1_000_000_000 + 400_000_000
                g.write(f"{nvidia_time(mono)}, {val(gpu_util, k)} %, 30 %, {val(gpu_clock, k)} MHz, 8000 MHz, "
                        f"80.00 W, 70, P0, 0x0000000000000000\n")


HARNESS_CLOCK = {"origin_nano_time": SAMPLER_MONO_NS + 5_000_000_000, "origin_epoch_ms": 0,
                 "origin_jvm_uptime_ms": 20_000}


class AlignmentTests(TempDir):
    def test_window_counts_only_whole_intervals_and_converts_gpu_wall_clock(self):
        write_sampler(self.dir / "system", 40, 50.0, 98, 1800)
        sampler = system_data.load_sampler(self.dir / "system")
        harness = {"clock": HARNESS_CLOCK}
        start, end = system_data.window_mono(harness, 10_000_000_000, 20_000_000_000)   # sampler seconds 15..25
        s = system_data.window_system(sampler, start, end)
        self.assertEqual(s["samples"], 10)                 # rows at 16..25: their whole second lies in the window
        self.assertEqual(s["render_thread"]["samples"], 10)
        self.assertEqual(s["gpu"]["samples"], 10)          # GPU rows at 15.4 .. 24.4
        self.assertEqual(s["render_thread"]["on_performance_core_fraction"], 1.0)
        self.assertEqual(s["server_thread"]["on_performance_core_fraction"], 0.0)
        self.assertEqual(s["gpu"]["utilization_pct_mean"], 98)
        self.assertEqual(s["gpu"]["throttle_reasons_active"], [])

    def test_render_thread_is_the_lowest_tid_of_that_name(self):
        # Seen on the owner's machine (2026-11-09): three "Render thread" TIDs. Averaging them read ~38% for a thread
        # that was ~80% busy, which would hide CPU-bound windows.
        write_sampler(self.dir / "system", 40, 99.0, 60, 1800, driver_threads=True)
        sampler = system_data.load_sampler(self.dir / "system")
        start, end = system_data.window_mono({"clock": HARNESS_CLOCK}, 10_000_000_000, 20_000_000_000)
        s = system_data.window_system(sampler, start, end)
        self.assertEqual(s["render_thread"]["tid"], 101)
        self.assertEqual(s["render_thread"]["cpu_pct_mean"], 99.0)
        self.assertEqual(s["render_thread"]["same_name_threads"], 3)
        self.assertEqual(s["render_thread"]["same_name_others_cpu_pct_sum_mean"], 30.0)
        self.assertEqual(system_data.bound_label(s, True)[0], "cpu-bound")

    def test_throttle_events_counted_inside_the_window(self):
        write_sampler(self.dir / "system", 40, 50.0, 60, 1800, throttle=lambda k: (k * 2, 100 + (k >= 18)))
        sampler = system_data.load_sampler(self.dir / "system")
        start, end = system_data.window_mono({"clock": HARNESS_CLOCK}, 10_000_000_000, 20_000_000_000)
        s = system_data.window_system(sampler, start, end)
        self.assertEqual(s["core_throttle_events"], 20)     # rows at 15 (2*15) and 25 (2*25)
        self.assertEqual(s["package_throttle_events"], 1)

    def test_old_sampler_has_no_throttle_counts(self):
        write_sampler(self.dir / "system", 40, 50.0, 60, 1800)
        sampler = system_data.load_sampler(self.dir / "system")
        start, end = system_data.window_mono({"clock": HARNESS_CLOCK}, 10_000_000_000, 20_000_000_000)
        self.assertIsNone(system_data.window_system(sampler, start, end)["core_throttle_events"])

    def test_no_clock_no_window(self):
        self.assertIsNone(system_data.window_mono({}, 0, 1))


class BoundLabelTests(TempDir):
    def label(self, render, util, clock, seconds=40, sampled=True, end_s=20):
        d = self.dir / f"s{len(list(self.dir.iterdir()))}"
        write_sampler(d, seconds, render, util, clock)
        sampler = system_data.load_sampler(d)
        start, end = system_data.window_mono({"clock": HARNESS_CLOCK}, 10_000_000_000, end_s * 1_000_000_000)
        return system_data.bound_label(system_data.window_system(sampler, start, end), sampled)[0]

    def test_labels(self):
        self.assertEqual(self.label(50.0, 98, 1800), "gpu-bound")
        self.assertEqual(self.label(99.0, 60, 1800), "cpu-bound")
        self.assertEqual(self.label(50.0, 60, 1800), "mixed")
        self.assertEqual(self.label(99.0, 98, lambda k: 1500 if k % 2 else 1900), "mixed")   # unstable clocks
        self.assertEqual(self.label(50.0, 98, 1800, sampled=False), "unlabelled")
        self.assertEqual(self.label(50.0, 98, 1800, end_s=12), "unlabelled")   # 2 s window: too few samples
        self.assertEqual(system_data.bound_label(None, True)[0], "unlabelled")


GC_LOG = """\
[2026-11-09T08:05:01.499-0500][0.002s][info][gc     ] Using G1
[2026-11-09T08:05:02.117-0500][0.621s][info][gc,start    ] GC(0) Pause Young (Normal) (G1 Evacuation Pause)
[2026-11-09T08:05:02.122-0500][0.626s][info][gc          ] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 215M->28M(4096M) 4.924ms
[2026-11-09T08:05:32.000-0500][30.500s][info][gc          ] GC(1) Pause Full (System.gc()) 900M->200M(4096M) 120.500ms
[2026-11-09T08:05:32.010-0500][30.510s][info][gc          ] GC(2) Concurrent Mark Cycle 40.000ms
[2026-11-09T08:05:35.000-0500][33.000s][info][gc          ] GC(3) Pause Remark 53M->53M(4096M) 1.744ms
[2026-11-09T08:05:36.000-0500][34.000s][info][gc          ] GC(4) Pause Cleanup 56M->56M(4096M) 0.240ms
"""


class GcLogTests(TempDir):
    def test_pauses_parsed_and_counted_per_window(self):
        path = self.dir / "gc.log"
        path.write_text(GC_LOG)
        pauses = system_data.parse_gc_log(path)
        self.assertEqual([p["id"] for p in pauses], [0, 1, 3, 4])   # concurrent phases are not pauses
        self.assertEqual(pauses[0]["kind"], "Pause Young (Normal) (G1 Evacuation Pause)")
        self.assertEqual(pauses[1]["pause_ms"], 120.5)
        harness = {"clock": HARNESS_CLOCK}   # origin at JVM uptime 20 s
        w = system_data.window_gc(pauses, harness, 12_000_000_000, 14_500_000_000)   # uptime 32 .. 34.5 s
        self.assertEqual((w["pauses"], w["kinds"]), (2, ["Pause Cleanup", "Pause Remark"]))
        self.assertAlmostEqual(w["pause_ms_total"], 1.984)
        self.assertIsNone(system_data.window_gc(pauses, {}, 0, 1))


def env_snapshot(rapl_long: float = 95.0, profile: str = "performance") -> dict:
    return {"cpu": {"platform_profile": profile, "power_profile": "performance", "governor": "powersave",
                    "energy_performance_preference": "performance", "intel_no_turbo": "0",
                    "rapl_limits_w": {"intel-rapl:0": {"long_term": rapl_long, "short_term": 30.0}}},
            "power": {"ADP0": {"type": "Mains", "online": "1", "status": None, "capacity": None},
                      "BAT0": {"type": "Battery", "online": None, "status": "Not charging", "capacity": "79"}}}


def add_system_data(run: Path, *, after: dict | None = None, gpu_util=98, render=50.0, sampler=True) -> None:
    """Gives a make_run launch what pfbench 0.3.0 records: clock, sampler, snapshots, GC log."""
    harness = json.loads((run / "harness.json").read_text())
    launch = json.loads((run / "launch.json").read_text())
    phases = list(csv.DictReader(open(run / "phases.csv")))
    end_s = int(phases[-1]["end_t_ns"]) // 1_000_000_000 + 10
    harness["clock"] = HARNESS_CLOCK
    harness["setup"]["gl"]["renderer"] = NVIDIA_GPU
    (run / "harness.json").write_text(json.dumps(harness))
    if sampler:
        write_sampler(run / "system", end_s, render, gpu_util, 1800)
        launch["system_sampler"] = {"dir": "system", "interval_s": 1.0, "self_cpu": None, "returncode": 0}
    (run / "env-before.json").write_text(json.dumps(env_snapshot()))
    (run / "env-after.json").write_text(json.dumps(after or env_snapshot()))
    launch["env_snapshots"] = {"before": {"file": "env-before.json", "returncode": 0},
                               "after": {"file": "env-after.json", "returncode": 0}}
    (run / "launch.json").write_text(json.dumps(launch))
    (run / "game").mkdir(exist_ok=True)
    (run / "game" / "gc.log").write_text(GC_LOG)
    quiet(pfbench.check, run)


class IngestSystemTests(TempDir):
    def windows(self, camp: Path) -> dict:
        out = {}
        for d in (camp / "runs").glob("*/"):
            meta = json.loads((d / "run.json").read_text())
            out[meta["config"]] = meta | {"_summary": json.loads((d / "summary.json").read_text())}
        return out

    def test_bound_label_system_and_gc_summaries_reach_run_json(self):
        run = make_run(self.dir / "l", "C:moderate,B:moderate")
        add_system_data(run)
        ingest_harness.ingest_run(run, self.dir / "camp", None, False, False)
        for w in self.windows(self.dir / "camp").values():
            self.assertEqual(w["bound"], "gpu-bound", w["bound_reason"])
            self.assertGreater(w["system"]["samples"], 20)
            self.assertIsNotNone(w["gc_log"])
            self.assertEqual(w["environment"]["power_state_sha256"],
                             system_data.power_state_sha256(system_data.power_state(env_snapshot())))
            self.assertTrue(w["_summary"]["usable"], w["_summary"]["issues"])

    def test_gc_pauses_land_in_the_right_window(self):
        run = make_run(self.dir / "l", "B:moderate")
        add_system_data(run)
        harness = json.loads((run / "harness.json").read_text())
        measure = next(p for p in csv.DictReader(open(run / "phases.csv")) if p["role"] == "measure")
        up = HARNESS_CLOCK["origin_jvm_uptime_ms"] / 1000 + int(measure["start_t_ns"]) / 1e9 + 1
        (run / "game" / "gc.log").write_text(
            f"[x][{up:.3f}s][info][gc          ] GC(9) Pause Young (Normal) (G1 Evacuation Pause) 9M->1M(4096M) 2.500ms\n")
        ingest_harness.ingest_run(run, self.dir / "camp", None, False, False)
        gc = self.windows(self.dir / "camp")["B"]["gc_log"]
        self.assertEqual((gc["pauses"], gc["pause_ms_total"]), (1, 2.5))
        self.assertEqual(harness["clock"], HARNESS_CLOCK)

    def test_power_change_during_a_launch_makes_its_windows_unusable(self):
        run = make_run(self.dir / "l", "B:moderate")
        add_system_data(run, after=env_snapshot(rapl_long=30.0))
        ingest_harness.ingest_run(run, self.dir / "camp", None, False, False)
        s = self.windows(self.dir / "camp")["B"]["_summary"]
        self.assertFalse(s["usable"])
        self.assertIn("power_settings_changed", {i["code"] for i in s["issues"]})

    def test_launch_without_sampler_stays_unlabelled(self):
        run = make_run(self.dir / "l", "B:moderate")
        ingest_harness.ingest_run(run, self.dir / "camp", None, False, False)
        w = self.windows(self.dir / "camp")["B"]
        self.assertEqual(w["bound"], "unlabelled")
        self.assertIsNone(w["system"])
        self.assertIn("no_system_sampler", {i["code"] for i in w["_summary"]["issues"]})

    def test_different_power_state_excludes_the_pair(self):
        import compare
        camp = self.dir / "camp"
        a = make_run(self.dir / "l", "A:moderate", block=1)
        b = make_run(self.dir / "l", "B:moderate", block=1, launch_in_block=2)
        add_system_data(a)
        add_system_data(b)
        (b / "env-before.json").write_text(json.dumps(env_snapshot(profile="balanced")))
        (b / "env-after.json").write_text(json.dumps(env_snapshot(profile="balanced")))
        for run in (a, b):
            ingest_harness.ingest_run(run, camp, None, False, False)
        report = compare.compare(compare.load_runs(camp), "A", ["mean_frametime_ms"], 1.0, 2.0)
        self.assertTrue(any("power_state_sha256" in why for _, why in report["excluded"]), report["excluded"])


class CheckSystemTests(TempDir):
    def failed(self, run: Path) -> list[str]:
        return [c["check"] for c in json.loads((run / "check.json").read_text())["checks"] if not c["ok"]]

    def test_complete_system_data_passes(self):
        run = make_run(self.dir / "l", "C:moderate,B:moderate")
        add_system_data(run)
        checks = {c["check"] for c in json.loads((run / "check.json").read_text())["checks"]}
        self.assertIn("sampler covers every measured phase", checks)
        self.assertIn("gc.log parses (the settles' System.gc() pauses are there)", checks)
        self.assertEqual(self.failed(run), [])

    def test_missing_sampler_files_and_power_change_fail(self):
        run = make_run(self.dir / "l", "B:moderate")
        add_system_data(run, after=env_snapshot(profile="balanced"))
        (run / "system" / "system.csv").unlink()
        quiet(pfbench.check, run)
        failed = self.failed(run)
        self.assertIn("system sampler wrote meta.json, system.csv and threads.csv", failed)
        self.assertIn("power settings unchanged from before to after the launch (AC, profiles, RAPL limits)", failed)

    def test_sampler_that_stopped_early_fails_coverage(self):
        run = make_run(self.dir / "l", "B:moderate")
        add_system_data(run)
        rows = list(csv.reader(open(run / "system" / "system.csv")))
        with open(run / "system" / "system.csv", "w", newline="") as fh:
            csv.writer(fh).writerows(rows[:20])
        quiet(pfbench.check, run)
        self.assertIn("sampler covers every measured phase", self.failed(run))


class SamplerSmokeTest(TempDir):
    def test_sampler_writes_monotonic_rows_and_clock_pairs(self):
        """Functional only: samples a sleeping process for about a second (no GPU) and checks the files."""
        target = subprocess.Popen(["sleep", "5"])
        try:
            out = self.dir / "system"
            r = subprocess.run([sys.executable, "-I", str(TOOLS / "sample_system.py"), "--pid", str(target.pid),
                                "--out-dir", str(out), "--interval", "0.25", "--duration", "1", "--no-gpu",
                                "--min-thread-pct", "0"], capture_output=True, text=True, timeout=30)
        finally:
            target.kill()
            target.wait()
        self.assertEqual(r.returncode, 0, r.stderr)
        sampler = system_data.load_sampler(out)
        self.assertGreaterEqual(len(sampler["system"]), 3)
        self.assertGreaterEqual(len(sampler["threads"]), 3)
        mono = [row["mono_ns"] for row in sampler["system"]]
        self.assertEqual(mono, sorted(mono))
        meta = sampler["meta"]
        self.assertIsNone(meta["self_cpu"])
        self.assertEqual(meta["samples"], len(mono))
        self.assertLessEqual(meta["clock_start"]["mono_ns"], mono[0])
        self.assertGreaterEqual(meta["clock_end"]["mono_ns"], mono[-1])


if __name__ == "__main__":
    unittest.main()
