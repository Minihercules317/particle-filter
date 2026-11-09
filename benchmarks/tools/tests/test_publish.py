"""Tests for campaign design checks (pfbench.py campaign) and publication (tools/publish_campaign.py), using
synthetic harness launches from test_pipeline.make_run. No real run's timings are read.

Run from the repository root:  python3 -m unittest discover -s benchmarks/tools/tests -v
"""
from __future__ import annotations

import contextlib
import io
import json
import shutil
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_pipeline import TempDir, compare, ingest_harness, make_run, pfbench, quiet  # noqa: E402

import publish_campaign  # noqa: E402  (test_pipeline put benchmarks/tools on sys.path)


def build_from_schedule(root: Path, configs: list[str], blocks: int, label: str = "performance",
                        frame_ms: dict | None = None) -> list[Path]:
    """Synthetic launches in exactly the order `pfbench.py campaign` would run them."""
    runs = []
    for b, launches in enumerate(pfbench.campaign_schedule(configs, ["moderate"], blocks, seed=1), start=1):
        for li, launch in enumerate(launches, start=1):
            seq = ",".join(f"{c}:{lv}" for c, lv in launch)
            runs.append(make_run(root, seq, block=b, launch_in_block=li, label=label,
                                 frame_ms=frame_ms or {"A": 5.0, "B": 5.0, "C": 4.5, "D": 4.6, "C-miss": 5.1}))
    return runs


def pairs(report: dict, config: str) -> int:
    total = 0
    for srep in report["strata"].values():
        for ds in srep["datasets"].values():
            r = ds["results"].get(config)
            if r:
                total += r["metrics"]["mean_frametime_ms"]["pairs"]
    return total


class CampaignAnalysisTests(TempDir):
    def statuses(self, configs: list[str], blocks: int = 6) -> dict:
        a = pfbench.campaign_analysis(configs, ["moderate"], blocks, seed=1)
        return {(x["config"], x["reference"]): x["status"] for x in a["comparisons"]}

    def test_combined_campaign_flags_q1_and_q8_as_impossible(self):
        s = self.statuses(["A", "B", "C", "D", "C-miss"])
        self.assertTrue(s[("B", "A")].startswith("IMPOSSIBLE"))
        self.assertTrue(s[("C-miss", "B")].startswith("IMPOSSIBLE"))
        self.assertEqual(s[("C", "B")], "pairable")
        self.assertEqual(s[("D", "C")], "pairable")

    def test_one_campaign_per_question_is_pairable(self):
        self.assertEqual(self.statuses(["A", "B"])[("B", "A")], "pairable")
        self.assertEqual(self.statuses(["B", "C-miss"])[("C-miss", "B")], "pairable")
        self.assertEqual(self.statuses(["C", "E"])[("E", "C")], "pairable")
        s = self.statuses(["C", "B", "D"])
        self.assertTrue(s[("B", "A")].startswith("not answerable"))
        self.assertEqual({s[k] for k in [("C", "B"), ("D", "B"), ("D", "C")]}, {"pairable"})

    def test_sodium_comparator_with_b_and_c_together_is_impossible(self):
        s = self.statuses(["A", "B", "C", "E"])
        self.assertTrue(s[("E", "C")].startswith("IMPOSSIBLE"))

    def test_analysis_agrees_with_what_compare_pairs(self):
        """The dry-run verdicts must match compare.py on data laid out exactly as the campaign would run it."""
        camp = self.dir / "campaign"
        for run in build_from_schedule(self.dir / "l", ["A", "B", "C", "D", "C-miss"], 6):
            ingest_harness.ingest_run(run, camp, None, False, False)
        runs = compare.load_runs(camp)
        vs_b = compare.compare(runs, "B", ["mean_frametime_ms"], 1.0, 2.0)
        self.assertEqual((pairs(vs_b, "C"), pairs(vs_b, "D"), pairs(vs_b, "C-miss")), (6, 6, 0))
        self.assertTrue(any("C-miss" in what and "launch_entries" in why for what, why in vs_b["excluded"]))
        vs_a = compare.compare(runs, "A", ["mean_frametime_ms"], 1.0, 2.0)
        self.assertEqual(pairs(vs_a, "B"), 0)

        camp2 = self.dir / "campaign-q1"
        for run in build_from_schedule(self.dir / "l2", ["A", "B"], 8):
            ingest_harness.ingest_run(run, camp2, None, False, False)
        self.assertEqual(pairs(compare.compare(compare.load_runs(camp2), "A", ["mean_frametime_ms"], 1.0, 2.0), "B"), 8)

    def test_balance_is_exact_only_for_multiples_of_the_williams_rows(self):
        six = pfbench.campaign_analysis(["C", "B", "D"], ["moderate"], 6, seed=1)["balance"][0]
        eight = pfbench.campaign_analysis(["C", "B", "D"], ["moderate"], 8, seed=1)["balance"][0]
        self.assertTrue(six["exact"] and six["positions_balanced"])
        self.assertEqual(six["carry_over_min_max"], (2, 2))
        self.assertFalse(eight["exact"])

    def test_performance_campaign_that_cant_be_paired_is_refused_before_anything_runs(self):
        err = io.StringIO()
        with self.assertRaises(SystemExit), contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(err):
            pfbench.main(["campaign", "--configs", "A,B,C,D,C-miss", "--levels", "heavy", "--blocks", "6",
                          "--seed", "1", "--name", "test-unpairable"])
        self.assertIn("compare.py can't pair", err.getvalue())   # the design check, not the power-limit interlock
        self.assertIn("C-miss vs B: IMPOSSIBLE", err.getvalue())
        self.assertFalse((pfbench.RUN_ROOT / "campaigns" / "test-unpairable.json").exists())

    def test_dry_run_reports_without_refusing(self):
        self.assertEqual(quiet(pfbench.main, ["campaign", "--configs", "A,B,C,D,C-miss", "--levels", "heavy",
                                              "--blocks", "6", "--seed", "1", "--dry-run"]), 0)

    def test_invalid_launch_options_are_refused_up_front(self):
        for argv in (["--configs", "D,C", "--variant", "block_dust"], ["--configs", "C,E"],
                     ["--configs", "C,E", "--modset", "sodium", "--variant", "untyped"],
                     ["--configs", "C,B", "--loader", "forge", "--modset", "sodium"]):
            with self.subTest(argv=argv), self.assertRaises(SystemExit):
                quiet(pfbench.main, ["campaign", "--levels", "heavy", "--blocks", "2", "--seed", "1", "--dry-run",
                                     *argv])


class GradleCommandTests(unittest.TestCase):
    def test_plain_gradlew_without_mise(self):
        """A third party without mise gets plain ./gradlew (REPRODUCING.md section 1)."""
        from unittest import mock
        with mock.patch.object(pfbench.shutil, "which", return_value=None):
            self.assertEqual(pfbench.java_prefix(), [])
        with mock.patch.object(pfbench.shutil, "which", return_value="/usr/bin/mise"), \
                mock.patch.object(pfbench, "REPO", Path("/nonexistent")):
            self.assertEqual(pfbench.java_prefix(), [])   # mise installed but no mise.toml: not used


class PublishTests(TempDir):
    def campaign(self, label: str = "performance") -> Path:
        launches = build_from_schedule(self.dir / "launches", ["C", "B"], 2, label=label)
        for run in launches:   # what a real launch has but publication must leave out
            (run / "game" / "saves" / "pfbench").mkdir(parents=True)
            (run / "game" / "saves" / "pfbench" / "level.dat").write_bytes(b"world")
            (run / "game" / "mods").mkdir()
            (run / "game" / "mods" / "particlefilter.jar").write_bytes(b"jar")
            (run / "game" / "gc.log").write_text("[0.1s][info][gc] GC(0) Pause Full (System.gc()) 10M->5M(4096M) 3.0ms\n")
        camp = self.dir / "results" / "syn-campaign"
        quiet(publish_campaign.archive, camp, launches)
        return camp

    def test_round_trip_rebuilds_identically(self):
        camp = self.campaign()
        publish_campaign.report(camp, "B")
        publish_campaign.manifest(camp)
        self.assertEqual(publish_campaign.verify(camp), [])
        moved = self.dir / "elsewhere" / camp.name   # a third party's checkout lives somewhere else
        shutil.copytree(camp, moved)
        self.assertEqual(publish_campaign.verify(moved), [])

    def test_report_is_generated_from_files_and_classified(self):
        camp = self.campaign()
        publish_campaign.report(camp, "B")
        text = (camp / "report.generated.md").read_text()
        self.assertIn("Generated by `benchmarks/tools/publish_campaign.py report`", text)
        self.assertIn("Synthetic benchmark", text)
        self.assertIn("Performance measurement", text)
        self.assertIn("`primary:unlabelled`: 2 pairs", text)
        self.assertIn("](raw/syn-b01-l1-", text)
        self.assertIn("## Limitations", text)
        self.assertIn("What the evidence does not prove", text)
        for f in [camp / "report.generated.md", camp / "comparison.md", *(camp / "runs").rglob("*.json")]:
            self.assertNotIn(str(self.dir), f.read_text(), f)

    def test_world_and_jars_are_left_out_but_hashed(self):
        camp = self.campaign()
        some = next((camp / "raw").glob("syn-*"))
        self.assertFalse((some / "game" / "saves").exists())
        self.assertFalse((some / "game" / "mods").exists())
        self.assertTrue((some / "game" / "gc.log").exists())
        left = json.loads((camp / "ARCHIVE.json").read_text())["launches"][some.name]["left_out"]
        self.assertIn("game/mods/particlefilter.jar", left)
        self.assertEqual(len(left["game/saves/pfbench/level.dat"]["sha256"]), 64)

    def test_edited_raw_data_is_caught(self):
        camp = self.campaign()
        publish_campaign.report(camp, "B")
        publish_campaign.manifest(camp)
        frames = next((camp / "raw").glob("syn-*")) / "frames.csv"
        frames.write_text(frames.read_text().replace("5000000", "4000000"))
        problems = publish_campaign.verify(camp)
        self.assertTrue(any(p.startswith("changed: raw/") for p in problems), problems)
        publish_campaign.manifest(camp)   # even with a fresh manifest the derived files no longer follow from raw/
        self.assertTrue(any(p.startswith("rebuild differs") for p in publish_campaign.verify(camp)))

    def test_hand_edited_results_are_caught(self):
        camp = self.campaign()
        publish_campaign.report(camp, "B")
        (camp / "comparison.md").write_text((camp / "comparison.md").read_text() + "\nbetter by 50%\n")
        publish_campaign.manifest(camp)
        self.assertIn("rebuild differs: comparison.md", publish_campaign.verify(camp))

    def test_functional_launches_are_labelled_as_such(self):
        camp = self.campaign(label="functional")
        with self.assertRaises(SystemExit):
            quiet(publish_campaign.report, camp, "B")
        publish_campaign.report(camp, "B", allow_functional=True)
        self.assertIn("FUNCTIONAL / PIPELINE TEST, NOT A RESULT", (camp / "report.generated.md").read_text())

    def test_labels_and_loaders_are_never_archived_together(self):
        camp = self.campaign()
        other = make_run(self.dir / "other", "B:moderate", label="functional", run_id="func-launch")
        with self.assertRaises(SystemExit):
            quiet(publish_campaign.archive, camp, [other])
        forge = make_run(self.dir / "other", "B:moderate", run_id="forge-launch")
        launch = json.loads((forge / "launch.json").read_text())
        (forge / "launch.json").write_text(json.dumps(launch | {"loader": "forge"}))
        with self.assertRaises(SystemExit):
            quiet(publish_campaign.archive, camp, [forge])


if __name__ == "__main__":
    unittest.main()
