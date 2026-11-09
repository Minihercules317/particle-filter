"""Tests for the harness-to-compare.py pipeline (pfbench.py plan/check/campaign, ingest_harness.py, compare.py),
using synthetic harness runs whose correct answers are known exactly. No real run's timings are read.

Run from the repository root:  python3 -m unittest discover -s benchmarks/tools/tests -v
"""
from __future__ import annotations

import contextlib
import csv
import io
import json
import sys
import tempfile
import tomllib
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1]
HARNESS = TOOLS.parent / "harness"
sys.path.insert(0, str(TOOLS))
sys.path.insert(0, str(HARNESS))

import compare  # noqa: E402
import ingest_harness  # noqa: E402
import pfbench  # noqa: E402

SCENARIO_PATH = TOOLS.parent / "scenarios" / "S1-synthetic.toml"
TICK_COLUMNS = ["tick", "t_ns", "phase", "attempts_target", "attempts_control", "created_target", "created_control",
                "created_untyped", "created_block", "created_vanilla", "created_other", "alive_opaque",
                "alive_translucent", "alive_terrain", "alive_total", "client_tick_ns", "server_tick_avg_ns", "gc_count",
                "gc_time_ms", "heap_used_bytes", "alive_control", "jit_time_ms", "classes_loaded", "server_saves"]
PF_SHA = "a" * 64


def scenario() -> tuple[dict, str]:
    return pfbench.load_scenario(SCENARIO_PATH)


def quiet(fn, *args, **kwargs):
    with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
        return fn(*args, **kwargs)


def make_run(root: Path, sequence: str, *, label: str = "performance", variant: str = "typed", block: int = 1,
             launch_in_block: int = 1, frame_ms: dict | None = None, jit_ms_in: dict | None = None,
             saves_in: set | None = None, carry_over: bool = False, slow_ticks: dict | None = None,
             mods_sha: str | None = None, check_passed: bool | None = None, functional_timing: bool = False,
             render_sha: str = "r" * 64, window: tuple = (2560, 1440, True), run_id: str | None = None) -> Path:
    """Writes a launch directory in the harness's exact output format.

    frame_ms: config -> frame time in ms for that config's measured window (default 5 ms everywhere).
    jit_ms_in: phase name -> JIT ms added during that phase. saves_in: phases with an autosave.
    carry_over: the first measure tick of every filtered phase still has target particles alive.
    slow_ticks: phase name -> factor by which that phase's wall time is longer than ticks/20.
    """
    sc, sc_sha = scenario()
    entries = pfbench.parse_sequence(sequence, sc["levels"])
    run_id = run_id or f"syn-b{block:02d}-l{launch_in_block}-{sequence.replace(':', '_').replace(',', '-')}"
    out = root / run_id
    out.mkdir(parents=True)
    plan = pfbench.build_plan(sc, sc_sha, entries, variant, functional_timing, run_id, out,
                              {"harness_version": "0.2.0", "harness_sources_sha256": "h" * 64})
    plan["label"] = label
    (out / "plan.json").write_text(json.dumps(plan))
    frame_ms = frame_ms or {}
    jit_ms_in, saves_in, slow_ticks = jit_ms_in or {}, saves_in or set(), slow_ticks or {}
    life_t, life_c = plan["target_lifetime_ticks"] + 1, plan["control_lifetime_ticks"] + 1
    rate_c = int(plan["control_attempts_per_tick"])
    created_col = pfbench.VARIANTS[variant][1]
    queue_col = pfbench.VARIANT_QUEUE[variant]

    frames, ticks, phases = [], [], []
    t = 0
    tick_no = jit = saves = gc = 0
    for idx, p in enumerate(plan["phases"]):
        n_ticks = p["ticks"]
        dur_ns = int(n_ticks / 20 * 1e9 * slow_ticks.get(p["name"], 1.0))
        start = t
        ft_ns = int(frame_ms.get(p.get("config"), 5.0) * 1e6) if p["role"] == "measure" else 4_000_000
        nf = dur_ns // ft_ns
        for _ in range(nf):
            t += ft_ns
            frames.append([t, ft_ns, ft_ns // 2, p["name"]])
        t = start + dur_ns
        rate = int(p.get("target_per_tick", 0))
        filtered = p.get("config") in pfbench.FILTERED
        for k in range(n_ticks):
            jit += jit_ms_in.get(p["name"], 0) // n_ticks if k else jit_ms_in.get(p["name"], 0) % n_ticks
            if p["name"] in saves_in and k == n_ticks // 2:
                saves += 3
            control = p.get("control", False)
            alive_t = 0 if filtered else min(rate * life_t, 16384)
            if carry_over and filtered and p["role"] == "measure" and k == 0:
                alive_t = 7
            alive_c = rate_c * life_c if control else 0
            row = dict.fromkeys(TICK_COLUMNS, 0)
            row.update({"tick": tick_no, "t_ns": start + int(dur_ns * (k + 1) / n_ticks), "phase": p["name"],
                        "attempts_target": rate, "attempts_control": rate_c if control else 0,
                        created_col: 0 if filtered else rate, "created_control": rate_c if control else 0,
                        "alive_control": alive_c, "server_tick_avg_ns": 1_000_000, "client_tick_ns": 500_000,
                        "gc_count": gc, "gc_time_ms": gc * 3, "heap_used_bytes": 2**30, "jit_time_ms": jit,
                        "classes_loaded": 20000, "server_saves": saves})
            row["alive_translucent"] = alive_c + (alive_t if variant == "vanilla" else 0)
            if queue_col != "alive_translucent":
                row[queue_col] = alive_t
            row["alive_total"] = row["alive_opaque"] + row["alive_translucent"] + row["alive_terrain"]
            ticks.append(row)
            tick_no += 1
        mode = p.get("mode") or ""
        phases.append([idx, p["name"], p["role"], p.get("config") or "", p.get("level") or "", mode,
                       mode if pfbench.CONFIGS[entries[0][0]][0] else "", start, t, n_ticks,
                       p.get("target_per_tick", 0), str(p.get("control", False)).lower()])

    with open(out / "frames.csv", "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["t_ns", "frame_ns", "cpu_frame_ns", "phase"])
        w.writerows(frames)
    with open(out / "ticks.csv", "w", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=TICK_COLUMNS)
        w.writeheader()
        w.writerows(ticks)
    with open(out / "phases.csv", "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["index", "name", "role", "config", "level", "mode_requested", "mode_after_start", "start_t_ns",
                    "end_t_ns", "ticks", "target_per_tick", "control"])
        w.writerows(phases)

    pf_kind = pfbench.CONFIGS[entries[0][0]][0]
    jar = "particlefilter-neoforge-1.2.0+1.21.1.jar"
    mods_dir = {jar: PF_SHA} if pf_kind else {}
    folder = [{"file": jar, "sha256": mods_sha or PF_SHA}] if pf_kind else []
    loaded = [{"file": jar, "nested": False, "mods": ["particlefilter 1.2.0"]}] if pf_kind else []
    harness = {
        "label": label, "ok": True, "run_id": run_id, "plan_sha256": "p" * 64,
        "versions": {"minecraft": "1.21.1", "neoforge": "21.1.250", "pfbench": "0.2.0", "java": "21.0.12",
                     "particlefilter": "1.2.0" if pf_kind else None},
        "mods": ["minecraft 1.21.1", "neoforge 21.1.250", "pfbench 0.2.0"] + (["particlefilter 1.2.0"] if pf_kind else []),
        "mods_folder": folder, "mod_files": loaded, "jvm_args": ["-Xms4G", "-Xmx4G"],
        "setup": {"level_name": "pfbench", "player_spectator": True, "player_position": plan["camera"]["position"],
                  "options": {"particles": "ALL", "maxFps": 60 if functional_timing else 260},
                  "particlefilter_present": bool(pf_kind), "gl": {"renderer": "Test GPU/PCIe"},
                  "window_at_start": {"width": window[0], "height": window[1], "fullscreen": window[2]}},
        "window_at_end": {"width": window[0], "height": window[1], "fullscreen": window[2]},
        "frames_logged": len(frames), "frames_dropped_capacity": 0, "ticks_dropped_capacity": 0,
    }
    (out / "harness.json").write_text(json.dumps(harness))
    launch = {
        "run_id": run_id, "label": label, "started_utc": "20261109T000000Z", "sequence": sequence,
        "entries": [{"position": i, "config": c, "level": lv} for i, (c, lv) in enumerate(entries, start=1)],
        "variant": variant, "scenario_sha256": sc_sha, "template_sha256_tree": "t" * 64,
        "particlefilter": {"jar": jar, "sha256": PF_SHA, "config_kind": pf_kind, "config_sha256": "c" * 64} if pf_kind else None,
        "modset": "vanilla", "render_mods": {}, "sodium_extra_hides_target": False, "mods_dir": mods_dir,
        "render_environment": {"sha256": render_sha}, "jvm_args_requested": sc["jvm"]["args"],
        "provenance": {"harness_version": "0.2.0", "harness_sources_sha256": "h" * 64, "git_commit": "0" * 40},
        "campaign": {"name": "syn", "block": block, "launch_in_block": launch_in_block, "seed": 1},
    }
    (out / "launch.json").write_text(json.dumps(launch))
    if check_passed is None:
        quiet(pfbench.check, out)
    else:
        (out / "check.json").write_text(json.dumps({"passed": check_passed, "checks": [
            {"check": "synthetic", "ok": check_passed, "detail": ""}]}))
    return out


class TempDir(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self._tmp.name)

    def tearDown(self):
        self._tmp.cleanup()


class PlanTests(unittest.TestCase):
    def test_warmup_runs_every_configuration_in_two_rounds(self):
        sc, sha = scenario()
        entries = [("C", "heavy"), ("B", "heavy"), ("D", "heavy")]
        plan = pfbench.build_plan(sc, sha, entries, "typed", False, "x", Path("/tmp/x"))
        warm = [p for p in plan["phases"] if p["role"] == "warmup"]
        self.assertEqual([p["config"] for p in warm], ["C", "B", "D", "C", "B", "D"])
        self.assertEqual([p["mode"] for p in warm[:3]], ["EVERYWHERE", "NONE", "ZONES"])
        self.assertEqual(sum(p["ticks"] for p in warm), sc["phases"]["warmup_with_workload"] * 20)

    def test_single_configuration_has_one_warmup_phase(self):
        sc, sha = scenario()
        plan = pfbench.build_plan(sc, sha, [("A", "heavy")], "typed", False, "x", Path("/tmp/x"))
        self.assertEqual([p["name"] for p in plan["phases"]][:2], ["load-settle", "warmup"])

    def test_every_measure_follows_a_switch_settle_with_gc(self):
        sc, sha = scenario()
        plan = pfbench.build_plan(sc, sha, [("C", "heavy"), ("B", "heavy")], "typed", False, "x", Path("/tmp/x"))
        ph = plan["phases"]
        for i, p in enumerate(ph):
            if p["role"] == "measure":
                self.assertEqual(ph[i - 1]["role"], "switch-settle")
                self.assertEqual(ph[i - 1]["config"], p["config"])
                self.assertTrue(ph[i - 1]["gc_at_start"])

    def test_min_settle_covers_the_longest_particle_life(self):
        self.assertEqual(pfbench.min_settle_ticks((40, 40), "typed"), 43)
        self.assertEqual(pfbench.min_settle_ticks((40, 40), "block_dust"), 43)
        self.assertEqual(pfbench.min_settle_ticks((40, 40), "vanilla"), 74)   # end_rod lives up to 71 ticks

    def test_settle_shorter_than_particle_life_is_refused(self):
        sc, sha = scenario()
        sc = json.loads(json.dumps(sc))
        sc["phases"]["settle_after_switch"] = 3   # 60 ticks < 74 needed by end_rod
        with self.assertRaises(SystemExit):
            quiet(pfbench.build_plan, sc, sha, [("C", "heavy"), ("B", "heavy")], "vanilla", False, "x", Path("/tmp/x"))

    def test_functional_timing_is_long_enough_for_every_variant(self):
        sc, sha = scenario()
        for variant in pfbench.VARIANTS:
            pfbench.build_plan(sc, sha, [("C", "heavy"), ("B", "heavy")], variant, True, "x", Path("/tmp/x"))

    def test_install_states_cannot_mix(self):
        levels = scenario()[0]["levels"]
        for bad in ("A:heavy,B:heavy", "E:heavy,A:heavy", "C-miss:heavy,C:heavy", "B:heavy,B:heavy"):
            with self.assertRaises(SystemExit):
                quiet(pfbench.parse_sequence, bad, levels)
        self.assertEqual(len(pfbench.parse_sequence("B:heavy,C:heavy,D:heavy", levels)), 3)


class CampaignScheduleTests(unittest.TestCase):
    def test_positions_balance_inside_and_between_launches(self):
        blocks = pfbench.campaign_schedule(["C", "B", "D", "A"], ["heavy"], 6, seed=3)
        self.assertEqual(len(blocks), 6)
        inner_pos = {c: [0, 0, 0] for c in "CBD"}
        a_first = 0
        for launches in blocks:
            self.assertEqual(len(launches), 2)
            for launch in launches:
                if len(launch) == 3:
                    for i, (c, _) in enumerate(launch):
                        inner_pos[c][i] += 1
            a_first += launches[0] == [("A", "heavy")]
        for counts in inner_pos.values():
            self.assertEqual(counts, [2, 2, 2])   # 3 configs: Williams square + mirror = 6 rows
        self.assertEqual(a_first, 3)

    def test_reproducible(self):
        a = pfbench.campaign_schedule(["C", "B", "D"], ["heavy"], 4, seed=9)
        b = pfbench.campaign_schedule(["C", "B", "D"], ["heavy"], 4, seed=9)
        self.assertEqual(a, b)


class SodiumConfigTests(TempDir):
    BASE = {"particle_settings": {"particles": True, "rain_splash": True, "other": {"minecraft:flame": True}},
            "render_settings": {"fog_distance": 0}}

    def test_only_the_target_entry_differs(self):
        hidden = pfbench.sodium_extra_options(self.BASE, "pfbench:target", True)
        shown = pfbench.sodium_extra_options(self.BASE, "pfbench:target", False)
        self.assertEqual(hidden["particle_settings"]["other"], {"pfbench:target": False})
        self.assertEqual(shown["particle_settings"]["other"], {"pfbench:target": True})
        self.assertEqual(pfbench.normalized_sodium_extra(hidden), pfbench.normalized_sodium_extra(shown))
        self.assertEqual(self.BASE["particle_settings"]["other"], {"minecraft:flame": True})   # base untouched

    def test_render_environment_equal_for_E_and_baseline(self):
        envs = []
        for hide in (True, False):
            game = self.dir / f"g{hide}"
            (game / "config").mkdir(parents=True)
            (game / "options.txt").write_text("version:3955\n")
            (game / "config" / "sodium-options.json").write_text("{}\n")
            se = pfbench.sodium_extra_options(self.BASE, "pfbench:target", hide)
            (game / "config" / "sodium-extra-options.json").write_text(json.dumps(se, indent=2))
            envs.append(pfbench.render_environment(game, "sodium"))
        self.assertEqual(envs[0]["sha256"], envs[1]["sha256"])
        (self.dir / "gFalse" / "config" / "sodium-options.json").write_text('{"quality": 1}\n')
        self.assertNotEqual(envs[0]["sha256"], pfbench.render_environment(self.dir / "gFalse", "sodium")["sha256"])


class CheckTests(TempDir):
    def load_check(self, run: Path) -> dict:
        return json.loads((run / "check.json").read_text())

    def test_consistent_run_passes(self):
        run = make_run(self.dir, "C:moderate,B:moderate,D:moderate")
        failed = [c for c in self.load_check(run)["checks"] if not c["ok"]]
        self.assertEqual(failed, [])

    def test_carry_over_into_a_filtered_window_fails(self):
        run = make_run(self.dir, "B:moderate,C:moderate", carry_over=True)
        failed = [c["check"] for c in self.load_check(run)["checks"] if not c["ok"]]
        self.assertTrue(any("no target particle alive" in f for f in failed), failed)

    def test_wrong_jar_hash_fails(self):
        run = make_run(self.dir, "B:moderate", mods_sha="b" * 64)
        failed = [c["check"] for c in self.load_check(run)["checks"] if not c["ok"]]
        self.assertIn("mods/ held exactly the jars the launcher copied (SHA-256, read in game)", failed)

    def test_vanilla_carry_over_detected_through_control_counter(self):
        run = make_run(self.dir, "B:moderate,C:moderate", variant="vanilla", carry_over=True, functional_timing=True)
        failed = [c["check"] for c in self.load_check(run)["checks"] if not c["ok"]]
        self.assertTrue(any("no target particle alive" in f for f in failed), failed)


class IngestTests(TempDir):
    def setUp(self):
        super().setUp()
        self.camp = self.dir / "campaign"

    def windows(self) -> list[dict]:
        return [json.loads((d / "run.json").read_text()) | {"_summary": json.loads((d / "summary.json").read_text())}
                for d in sorted((self.camp / "runs").glob("*/"))]

    def test_functional_runs_are_refused_by_default(self):
        run = make_run(self.dir / "r", "B:moderate", label="functional")
        with self.assertRaises(ingest_harness.IngestError):
            ingest_harness.ingest_run(run, self.camp, None, False, False)
        out = ingest_harness.ingest_run(run, self.camp, None, True, False)
        self.assertEqual(json.loads((out[0] / "run.json").read_text())["label"], "functional")

    def test_one_window_per_measure_phase_with_exact_metrics_and_workload(self):
        run = make_run(self.dir / "r", "C:moderate,B:moderate", frame_ms={"C": 4.0, "B": 5.0})
        ingest_harness.ingest_run(run, self.camp, None, False, False)
        w = {x["config"]: x for x in self.windows()}
        self.assertEqual(set(w), {"C", "B"})
        self.assertAlmostEqual(w["C"]["_summary"]["metrics"]["mean_frametime_ms"], 4.0)
        self.assertAlmostEqual(w["B"]["_summary"]["metrics"]["mean_frametime_ms"], 5.0)
        for x in w.values():
            self.assertTrue(x["_summary"]["usable"], x["_summary"]["issues"])
            self.assertAlmostEqual(x["workload"]["spawn_attempts_per_s"], 50 * 20)
            self.assertAlmostEqual(x["workload"]["control_particles_live_mean"], 25 * 41)
            self.assertEqual(x["particle_filter"]["jar_sha256"], PF_SHA)
            self.assertEqual(x["block"], 1)
            self.assertEqual(x["stratum"], "moderate/typed/vanilla")
        self.assertEqual(w["C"]["position_in_launch"], 1)
        self.assertEqual(w["C"]["particle_filter"]["mode"], "EVERYWHERE")
        self.assertEqual(w["C"]["workload"]["target_live_mean"], 0)
        self.assertEqual(w["B"]["workload"]["target_live_mean"], 50 * 41)
        self.assertEqual(w["B"]["workload"]["target_created_per_s"], 50 * 20)

    def test_warnings_for_jit_autosave_and_slow_ticks(self):
        run = make_run(self.dir / "r", "C:moderate,B:moderate",
                       jit_ms_in={"01-C-moderate": 5000}, saves_in={"02-B-moderate"}, slow_ticks={"02-B-moderate": 1.1})
        ingest_harness.ingest_run(run, self.camp, None, False, False)
        codes = {x["config"]: {i["code"] for i in x["_summary"]["issues"]} for x in self.windows()}
        self.assertIn("jit_activity", codes["C"])
        self.assertNotIn("jit_activity", codes["B"])
        self.assertIn("autosave_in_window", codes["B"])
        self.assertIn("client_tick_rate_low", codes["B"])

    def test_failed_functional_check_makes_windows_unusable(self):
        run = make_run(self.dir / "r", "B:moderate", check_passed=False)
        ingest_harness.ingest_run(run, self.camp, None, False, False)
        s = self.windows()[0]["_summary"]
        self.assertFalse(s["usable"])
        self.assertIn("functional_check_failed", {i["code"] for i in s["issues"]})

    def test_existing_windows_are_not_overwritten(self):
        run = make_run(self.dir / "r", "B:moderate")
        ingest_harness.ingest_run(run, self.camp, None, False, False)
        with self.assertRaises(ingest_harness.IngestError):
            ingest_harness.ingest_run(run, self.camp, None, False, False)


class EndToEndCompareTests(TempDir):
    def build_campaign(self, blocks: int = 6, c_ms: float = 4.5, b_ms: float = 5.0, **kw) -> Path:
        camp = self.dir / "campaign"
        for b, launches in enumerate(pfbench.campaign_schedule(["C", "B"], ["moderate"], blocks, seed=1), start=1):
            for li, launch in enumerate(launches, start=1):
                jitter = 1 + 0.002 * ((b * 7) % 5 - 2)   # small, deterministic block-to-block variation
                seq = ",".join(f"{c}:{lv}" for c, lv in launch)
                run = make_run(self.dir / "launches", seq, block=b, launch_in_block=li,
                               frame_ms={"C": c_ms * jitter, "B": b_ms * jitter}, **kw)
                ingest_harness.ingest_run(run, camp, None, kw.get("label", "performance") != "performance", False)
        return camp

    def test_known_speedup_is_found(self):
        camp = self.build_campaign()
        runs = compare.load_runs(camp)
        report = compare.compare(runs, "B", ["mean_frametime_ms"], 1.0, 2.0)
        r = report["strata"]["moderate/typed/vanilla"]["results"]["C"]["metrics"]["mean_frametime_ms"]
        self.assertEqual(r["pairs"], 6)
        self.assertAlmostEqual(r["mean_diff_pct"], -10.0, places=6)
        self.assertEqual(r["verdict"], "better")
        md = compare.to_markdown(report, runs, camp)
        self.assertIn("## Order balance", md)
        self.assertNotIn("PIPELINE TEST", md)

    def test_functional_runs_need_the_flag_and_are_marked(self):
        camp = self.build_campaign(blocks=2, label="functional")
        self.assertEqual(quiet(compare.main, [str(camp), "--reference", "B"]), 2)
        out = self.dir / "report.md"
        self.assertEqual(quiet(compare.main, [str(camp), "--reference", "B", "--allow-functional", "--out", str(out)]), 0)
        self.assertTrue(out.read_text().startswith("# PIPELINE TEST, NOT A RESULT"))

    def test_mixed_labels_refused(self):
        runs = [{"meta": {"label": "performance"}}, {"meta": {"label": "functional"}}]
        self.assertIsNotNone(compare.check_labels(runs, True))

    def test_environment_mismatch_excludes_the_pair(self):
        camp = self.dir / "campaign"
        a = make_run(self.dir / "l", "A:moderate", block=1, window=(2560, 1440, True))
        b = make_run(self.dir / "l", "B:moderate", block=1, launch_in_block=2, window=(2536, 1390, False))
        for run in (a, b):
            ingest_harness.ingest_run(run, camp, None, False, False)
        report = compare.compare(compare.load_runs(camp), "A", ["mean_frametime_ms"], 1.0, 2.0)
        reasons = [why for _, why in report["excluded"]]
        self.assertTrue(any("environment differs" in r and "window" in r for r in reasons), reasons)

    def test_launch_shape_must_match(self):
        """B measured alongside C/D is not paired with A measured alone (different warmup and switching)."""
        camp = self.dir / "campaign"
        ingest_harness.ingest_run(make_run(self.dir / "l", "A:moderate"), camp, None, False, False)
        ingest_harness.ingest_run(make_run(self.dir / "l", "C:moderate,B:moderate,D:moderate", launch_in_block=2),
                                  camp, None, False, False)
        report = compare.compare(compare.load_runs(camp), "A", ["mean_frametime_ms"], 1.0, 2.0)
        self.assertTrue(any("launch_entries" in why for _, why in report["excluded"]))

    def test_strata_are_never_pooled(self):
        camp = self.dir / "campaign"
        for lv in ("moderate", "heavy"):
            ingest_harness.ingest_run(make_run(self.dir / "l", f"C:{lv},B:{lv}", run_id=f"r-{lv}"), camp, None, False, False)
        report = compare.compare(compare.load_runs(camp), "B", ["mean_frametime_ms"], 1.0, 2.0)
        self.assertEqual(set(report["strata"]), {"moderate/typed/vanilla", "heavy/typed/vanilla"})
        for s in report["strata"].values():
            self.assertEqual(s["results"]["C"]["metrics"]["mean_frametime_ms"]["pairs"], 1)


class ScenarioTests(unittest.TestCase):
    def test_scenario_parses_and_settle_is_long_enough(self):
        sc = tomllib.loads(SCENARIO_PATH.read_text())
        ticks = sc["phases"]["settle_after_switch"] * 20
        for variant in pfbench.VARIANTS:
            self.assertGreaterEqual(ticks, pfbench.min_settle_ticks((40, 40), variant))
        self.assertIn("-Xms4G", sc["jvm"]["args"])


if __name__ == "__main__":
    unittest.main()
