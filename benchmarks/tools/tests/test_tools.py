"""Tests for the benchmark tools, using synthetic logs whose correct answers are known exactly.

Run from the repository root:  python3 -m unittest discover -s benchmarks/tools/tests -v
"""
from __future__ import annotations

import gzip
import json
import math
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import compare  # noqa: E402
import frametimes  # noqa: E402
import schedule  # noqa: E402


def mangohud_csv(frametimes_ms: list[float], versioned: bool = False, elapsed_scale: float = 1.0) -> str:
    """A log in MangoHud's layout: system-info header, metrics header, then one row per frame."""
    lines = []
    if versioned:
        lines += ["v1", "0.8.4", "---------------------SYSTEM INFO---------------------"]
    lines += ["os,cpu,gpu,ram,kernel,driver,cpuscheduler",
              "Test OS,Test CPU,Test GPU,32GB,7.0,TestDriver 1.0,performance"]
    if versioned:
        lines.append("--------------------FRAME METRICS--------------------")
    lines.append("fps,frametime,cpu_load,cpu_power,gpu_load,cpu_temp,gpu_temp,gpu_core_clock,gpu_mem_clock,"
                 "gpu_vram_used,gpu_power,ram_used,swap_used,process_rss,elapsed")
    t_ns = 0.0
    for ft in frametimes_ms:
        t_ns += ft * 1e6 * elapsed_scale
        lines.append(f"{1000 / ft:.3f},{ft:.6f},10,5,50,60,55,1800,9000,2.0,40,12.0,0.0,3.5,{int(t_ns)}")
    return "\n".join(lines) + "\n"


class TempDirTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self._tmp.name)

    def tearDown(self):
        self._tmp.cleanup()

    def write(self, name: str, text: str) -> Path:
        path = self.dir / name
        if name.endswith(".gz"):
            with gzip.open(path, "wt") as fh:
                fh.write(text)
        else:
            path.write_text(text)
        return path


class PercentileTests(unittest.TestCase):
    def test_nearest_rank(self):
        values = list(range(1, 101))  # 1..100
        self.assertEqual(frametimes.percentile_nearest_rank(values, 99), 99)
        self.assertEqual(frametimes.percentile_nearest_rank(values, 100), 100)
        self.assertEqual(frametimes.percentile_nearest_rank(values, 50), 50)
        self.assertEqual(frametimes.percentile_nearest_rank(values, 0.5), 1)

    def test_worst_fraction_takes_at_least_one(self):
        self.assertEqual(frametimes.worst_fraction_mean([1, 2, 3], 0.01), 3)


class MetricTests(unittest.TestCase):
    def test_constant_frametime(self):
        m = frametimes.compute_metrics([10.0] * 2000)
        self.assertAlmostEqual(m["avg_fps"], 100.0)
        self.assertAlmostEqual(m["low_1pct_fps"], 100.0)
        self.assertAlmostEqual(m["p99_frametime_ms"], 10.0)
        self.assertEqual(m["stdev_frametime_ms"], 0.0)

    def test_avg_fps_is_time_weighted(self):
        # 1 frame of 100 ms + 9 frames of 10 ms = 10 frames in 190 ms -> 52.6 FPS, not mean(10, 100x9)/10 = 91 FPS.
        m = frametimes.compute_metrics([100.0] + [10.0] * 9)
        self.assertAlmostEqual(m["avg_fps"], 10 / 0.19)

    def test_one_percent_low(self):
        # 990 frames at 5 ms and 10 at 20 ms: the slowest 1% are exactly the ten 20 ms frames.
        m = frametimes.compute_metrics([5.0] * 990 + [20.0] * 10)
        self.assertAlmostEqual(m["low_1pct_fps"], 50.0)
        # nearest-rank p99 is the 990th value, still 5 ms: the two "1% low" definitions differ, as documented.
        self.assertAlmostEqual(m["p99_frametime_ms"], 5.0)
        self.assertAlmostEqual(m["p99_as_fps"], 200.0)
        self.assertEqual(m["hitches_over_50ms"], 0)


class ReadLogTests(TempDirTest):
    def test_plain_and_versioned_and_gzip_agree(self):
        fts = [4.0, 5.0, 6.0] * 500
        results = []
        for name, versioned in (("a.csv", False), ("b.csv", True), ("c.csv.gz", True)):
            log = frametimes.read_log(self.write(name, mangohud_csv(fts, versioned)))
            self.assertEqual(log.source_format, "mangohud")
            self.assertEqual(log.system_info["cpu"], "Test CPU")
            results.append(log.frametimes_ms)
        self.assertEqual(results[0], results[1])
        self.assertEqual(results[1], results[2])
        self.assertEqual(len(results[0]), 1500)

    def test_generic_ns(self):
        path = self.write("h.csv", "t_ns,frame_ns\n" + "".join(f"{i * 8_000_000},8000000\n" for i in range(1, 11)))
        log = frametimes.read_log(path)
        self.assertEqual(log.source_format, "generic-ns")
        self.assertEqual(log.frametimes_ms, [8.0] * 10)

    def test_no_frametime_column(self):
        with self.assertRaises(ValueError):
            frametimes.read_log(self.write("x.csv", "a,b\n1,2\n"))

    def test_bad_rows_counted_not_hidden(self):
        text = mangohud_csv([5.0] * 10).rstrip("\n") + "\n" + "nan,oops,,,,,,,,,,,,,\n"
        log = frametimes.read_log(self.write("bad.csv", text))
        self.assertEqual(len(log.frametimes_ms), 10)
        self.assertEqual(log.rows_skipped, 1)


class WindowTests(unittest.TestCase):
    def test_skip_and_duration(self):
        log = frametimes.FrameLog([10.0] * 1000)  # 10 s
        w = frametimes.select_window(log, skip_s=2.0, duration_s=5.0)
        self.assertEqual(len(w.frametimes_ms), 500)
        self.assertAlmostEqual(sum(w.frametimes_ms), 5000.0)


class SanityTests(TempDirTest):
    def summarize(self, fts, **kw):
        return frametimes.summarize(self.write("s.csv", mangohud_csv(fts, **{k: v for k, v in kw.items()
                                                                            if k == "elapsed_scale"})),
                                    cap_fps=kw.get("cap_fps"), duration_s=kw.get("duration_s"))

    def codes(self, s):
        return {i["code"] for i in s["issues"]}

    def test_clean_run_is_usable(self):
        s = self.summarize([5.0, 5.5, 4.5] * 2000)
        self.assertTrue(s["usable"], s["issues"])
        self.assertEqual(self.codes(s), set())

    def test_too_few_frames(self):
        s = self.summarize([5.0] * 100)
        self.assertFalse(s["usable"])
        self.assertIn("too_few_frames", self.codes(s))

    def test_freeze_is_error(self):
        s = self.summarize([5.0] * 2000 + [1500.0] + [5.0] * 2000)
        self.assertFalse(s["usable"])
        self.assertIn("freeze", self.codes(s))

    def test_frames_not_all_logged(self):
        # elapsed advances twice as fast as the frame times: half the frames are missing from the log.
        s = self.summarize([5.0] * 3000, elapsed_scale=2.0)
        self.assertIn("frames_not_all_logged", self.codes(s))
        self.assertFalse(s["usable"])

    def test_frame_limited(self):
        s = self.summarize([1000 / 60] * 3000, cap_fps=[60])
        self.assertIn("frame_limited", self.codes(s))
        self.assertTrue(s["usable"])  # a warning: the data is valid, it just measures the limiter

    def test_drift(self):
        fts = [5.0] * 2000 + [5.5] * 2000 + [6.0] * 2000   # FPS falls ~17% over the run
        self.assertIn("drift", self.codes(self.summarize(fts)))

    def test_window_short(self):
        s = self.summarize([5.0] * 2000, duration_s=60)  # 10 s of data, 60 s wanted
        self.assertIn("window_short", self.codes(s))
        self.assertFalse(s["usable"])


class ScheduleTests(unittest.TestCase):
    def test_williams_balance(self):
        for n in range(2, 7):
            rows = schedule.williams_rows(n)
            for pos in range(n):
                counts = [sum(1 for r in rows if r[pos] == t) for t in range(n)]
                self.assertEqual(len(set(counts)), 1, f"n={n} position {pos}: {counts}")
            pairs = {}
            for r in rows:
                for a, b in zip(r, r[1:]):
                    pairs[(a, b)] = pairs.get((a, b), 0) + 1
            self.assertEqual(len(pairs), n * (n - 1))
            self.assertEqual(len(set(pairs.values())), 1)

    def test_reproducible(self):
        a = schedule.make_schedule(["A", "B", "C"], 6, seed=3)
        self.assertEqual(a, schedule.make_schedule(["A", "B", "C"], 6, seed=3))
        for block in a:
            self.assertEqual(sorted(block), ["A", "B", "C"])


class CompareTests(TempDirTest):
    def add_run(self, run_id, config, block, mean_ft, usable=True, workload=None, tps_class="20tps", bound="unlabelled"):
        d = self.dir / "runs" / run_id
        d.mkdir(parents=True)
        meta = {"config": config, "block": block, "order": 1, "tps_class": tps_class, "bound": bound}
        if workload:
            meta["workload"] = workload
        (d / "run.json").write_text(json.dumps(meta))
        metrics = {"mean_frametime_ms": mean_ft, "p99_frametime_ms": mean_ft * 2, "avg_fps": 1000 / mean_ft,
                   "low_1pct_fps": 500 / mean_ft}
        (d / "summary.json").write_text(json.dumps({"usable": usable, "issues": [], "metrics": metrics}))

    def test_paired_difference_and_verdicts(self):
        # B is the reference; C is exactly 10% slower in every block; D is identical to B.
        for b, base in enumerate([5.0, 5.2, 4.9, 5.1, 5.0, 5.3], start=1):
            self.add_run(f"b{b}-B", "B", b, base)
            self.add_run(f"b{b}-C", "C", b, base * 1.10)
            self.add_run(f"b{b}-D", "D", b, base)
        report = compare.compare(compare.load_runs(self.dir), "B", ["mean_frametime_ms", "avg_fps"], 1.0, 2.0)
        c = report["strata"]["all"]["datasets"]["primary:unlabelled"]["results"]["C"]["metrics"]["mean_frametime_ms"]
        self.assertAlmostEqual(c["mean_diff_pct"], 10.0, places=6)
        self.assertEqual(c["verdict"], "worse")
        self.assertEqual(report["strata"]["all"]["datasets"]["primary:unlabelled"]["results"]["C"]["metrics"]["avg_fps"]["verdict"], "worse")
        self.assertTrue(report["strata"]["all"]["datasets"]["primary:unlabelled"]["results"]["D"]["metrics"]["mean_frametime_ms"]["verdict"].startswith("equivalent"))

    def test_unusable_and_mismatched_runs_are_excluded_and_listed(self):
        self.add_run("b1-B", "B", 1, 5.0, workload={"spawn_attempts_per_s": 1000})
        self.add_run("b1-C", "C", 1, 5.0, workload={"spawn_attempts_per_s": 1500})
        self.add_run("b2-B", "B", 2, 5.0)
        self.add_run("b2-C", "C", 2, 5.0, usable=False)
        report = compare.compare(compare.load_runs(self.dir), "B", ["mean_frametime_ms"], 1.0, 2.0)
        reasons = " ".join(why for _, why in report["excluded"])
        self.assertIn("workload differs", reasons)
        self.assertIn("not usable", reasons)
        self.assertEqual(report["strata"]["all"]["datasets"], {})   # no pair survived

    def test_workload_is_compared_per_tick_not_per_second(self):
        same_tick = {"attempts_per_tick": 50, "control_attempts_per_tick": 25, "control_particles_live_mean": 1025}
        self.add_run("b1-B", "B", 1, 5.0, workload=same_tick | {"spawn_attempts_per_s": 800}, tps_class="reduced")
        self.add_run("b1-C", "C", 1, 4.5, workload=same_tick | {"spawn_attempts_per_s": 1000})
        self.add_run("b2-B", "B", 2, 5.0, workload=same_tick)
        self.add_run("b2-C", "C", 2, 4.5, workload=same_tick | {"attempts_per_tick": 60})
        rep = compare.compare(compare.load_runs(self.dir), "B", ["mean_frametime_ms"], 1.0, 2.0)["strata"]["all"]
        self.assertEqual([what for what, _ in rep["excluded"]], ["block 2 C"])
        self.assertIn("attempts_per_tick", rep["excluded"][0][1])
        self.assertEqual(rep["datasets"]["saturation:unlabelled"]["results"]["C"]["blocks"], ["1"])

    def test_windows_of_one_side_with_different_tps_class_are_not_averaged(self):
        self.add_run("b1-B", "B", 1, 5.0)
        self.add_run("b1-C1", "C", 1, 4.5)
        self.add_run("b1-C2", "C", 1, 4.5, tps_class="reduced")
        rep = compare.compare(compare.load_runs(self.dir), "B", ["mean_frametime_ms"], 1.0, 2.0)["strata"]["all"]
        self.assertEqual(rep["datasets"], {})
        self.assertIn("TPS class", rep["excluded"][0][1])

    def test_bound_labels_are_never_pooled(self):
        for b in (1, 2, 3):
            self.add_run(f"b{b}-B", "B", b, 5.0, bound="gpu-bound")
            self.add_run(f"b{b}-C", "C", b, 4.5, bound="gpu-bound")
        self.add_run("b4-B", "B", 4, 5.0, bound="gpu-bound")
        self.add_run("b4-C", "C", 4, 3.0, bound="cpu-bound")
        rep = compare.compare(compare.load_runs(self.dir), "B", ["mean_frametime_ms"], 1.0, 2.0)["strata"]["all"]
        self.assertEqual(rep["datasets"]["primary:gpu-bound"]["results"]["C"]["blocks"], ["1", "2", "3"])
        self.assertEqual(rep["datasets"]["primary:differs"]["results"]["C"]["blocks"], ["4"])
        self.assertAlmostEqual(rep["datasets"]["primary:gpu-bound"]["results"]["C"]["metrics"]["mean_frametime_ms"]["mean_diff_pct"], -10.0)

    def test_runs_without_tick_data_are_kept_apart(self):
        for b in (1, 2):
            self.add_run(f"b{b}-B", "B", b, 5.0, tps_class=None)
            self.add_run(f"b{b}-C", "C", b, 4.5, tps_class=None)
        rep = compare.compare(compare.load_runs(self.dir), "B", ["mean_frametime_ms"], 1.0, 2.0)["strata"]["all"]
        self.assertEqual(set(rep["datasets"]), {"tps-unknown:unlabelled"})

    def test_inconclusive(self):
        diffs = [+5, -5, +6, -4, +5, -6]
        for b, d in enumerate(diffs, start=1):
            self.add_run(f"b{b}-B", "B", b, 5.0)
            self.add_run(f"b{b}-C", "C", b, 5.0 * (1 + d / 100))
        report = compare.compare(compare.load_runs(self.dir), "B", ["mean_frametime_ms"], 1.0, 2.0)
        self.assertEqual(report["strata"]["all"]["datasets"]["primary:unlabelled"]["results"]["C"]["metrics"]["mean_frametime_ms"]["verdict"], "inconclusive")

    def test_t_quantiles_match_tables(self):
        for df, q in ((1, 12.7062), (7, 2.3646), (11, 2.2010), (30, 2.0423), (200, 1.9719)):
            self.assertAlmostEqual(compare.t_quantile(0.975, df), q, places=3)
        self.assertAlmostEqual(compare.t_cdf(0.0, 5), 0.5, places=12)

    def test_t_interval_is_wider_than_bootstrap_at_small_n(self):
        values = [0.8, 1.4, -0.2, 0.9, 1.1, 0.3, 1.7, 0.6]
        lo, hi = compare.t_mean_ci(values)
        blo, bhi = compare.bootstrap_mean_ci(values)
        m = sum(values) / len(values)
        self.assertAlmostEqual((lo + hi) / 2, m, places=12)
        self.assertGreater(hi - lo, bhi - blo)
        self.assertTrue(math.isnan(compare.t_mean_ci([1.0])[0]))

    def test_bootstrap_ci_contains_mean_and_is_deterministic(self):
        values = [1.0, 2.0, 3.0, 4.0, 5.0]
        lo, hi = compare.bootstrap_mean_ci(values)
        self.assertLessEqual(lo, 3.0)
        self.assertGreaterEqual(hi, 3.0)
        self.assertEqual((lo, hi), compare.bootstrap_mean_ci(values))
        self.assertTrue(math.isnan(compare.bootstrap_mean_ci([1.0])[0]))


class PfConfigTests(unittest.TestCase):
    @staticmethod
    def contains(z, x, y, zz):
        # Same arithmetic as particlefilter.config.Zone.minX()/contains()
        min_x, min_z = z["x"] - z["width"] // 2, z["z"] - z["length"] // 2
        return (min_x <= x < min_x + z["width"] and z["y"] <= y < z["y"] + z["height"]
                and min_z <= zz < min_z + z["length"])

    def test_target_zone_encloses_emitter_plane_and_others_do_not(self):
        import make_pf_config as m
        data = m.build("zones", "ZONES", 50, m.TARGET)
        *others, target = data["zones"]
        # Emitter plane from S1-synthetic.toml: x -8..8, y 96..112, z 14
        for x in (-8.0, 0.0, 8.0):
            for y in (96.0, 112.0):
                self.assertTrue(self.contains(target, x, y, 14.0))
                self.assertFalse(any(self.contains(o, x, y, 14.0) for o in others))
        self.assertEqual(len(others), 49)

    def test_deterministic_and_modes(self):
        import make_pf_config as m
        self.assertEqual(m.build("synthetic", "NONE", 1, m.TARGET), m.build("synthetic", "NONE", 1, m.TARGET))
        rules = m.build("rules", "EVERYWHERE", 10, m.TARGET)
        self.assertEqual(len(rules["hiddenParticles"]), 10)
        self.assertIn(m.TARGET, rules["hiddenParticles"])
        self.assertNotIn(m.TARGET, m.build("miss", "EVERYWHERE", 10, m.TARGET)["hiddenParticles"])


if __name__ == "__main__":
    unittest.main()
