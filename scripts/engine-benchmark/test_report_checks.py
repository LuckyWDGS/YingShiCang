"""Pure report validation tests; never invoke Android, ADB, or Gradle."""
import copy
import unittest

from report_checks import classify, observed_format, output_metrics


def healthy_report(mode="exo-hard"):
    """A full minute with observed format and foreground/surface evidence."""
    samples = []
    for ms in range(0, 60001, 1000):
        sample = {
            "ms": ms,
            "positionMs": ms % 60000,
            "state": 3,
            "playing": True,
            "errors": 0,
            "codecErrors": 0,
            "resumed": True,
            "windowFocused": True,
            "surfaceAvailable": True,
            "surfaceValid": True,
        }
        if mode.startswith("exo"):
            sample.update(rendered=ms // 40, skipped=0, decoderDrops=0)
        else:
            sample.update(
                decoder="H.265 / HEVC (High Efficiency Video Coding)",
                videoWidth=1920,
                videoHeight=1080,
                containerFps=25.0,
                decoderDrops=0,
                outputDrops=0,
                hwdec="mediacodec",
            )
        samples.append(sample)
    report = {
        "schemaVersion": 2,
        "run": "synthetic-healthy",
        "mode": mode,
        "status": "finished",
        "requestedMs": 60000,
        "elapsedMs": 60000,
        "firstFrameMs": 500,
        "errors": 0,
        "codecErrors": 0,
        "requestedFixture": "hevc1080",
        "lifecycleEvidence": {
            "backgroundEvents": 0,
            "surfaceLosses": 0,
            "focusLosses": 0,
        },
        "events": [],
        "samples": samples,
    }
    if mode.startswith("exo"):
        report.update(
            decoder="OMX.qcom.video.decoder.hevc",
            videoFormat={
                "sampleMimeType": "video/hevc",
                "width": 1920,
                "height": 1080,
                "frameRate": 25.0,
            },
        )
    return report


class ReportClassificationTests(unittest.TestCase):
    def assertRejected(self, report, reason):
        result = classify(report)
        self.assertFalse(result["passed"], result)
        self.assertIn(reason, result["reasons"])

    def test_full_exo_run_passes_with_observed_format_and_lifecycle(self):
        report = healthy_report()
        self.assertTrue(classify(report)["passed"], classify(report))
        metrics = output_metrics(report)
        self.assertAlmostEqual(metrics["renderedFps"], 25.0, places=2)
        self.assertEqual(metrics["skippedTotal"], 0)
        self.assertEqual(metrics["maxFrozenRenderedWithSkipsSeconds"], 0)

    def test_advancing_position_cannot_hide_frozen_rendering_and_skips(self):
        report = healthy_report()
        for sample in report["samples"]:
            if sample["ms"] >= 10000:
                sample["rendered"] = 225
                sample["skipped"] = (sample["ms"] - 9000) // 40
        self.assertGreater(report["samples"][-2]["positionMs"], report["samples"][10]["positionMs"])
        self.assertRejected(report, "rendered_frozen")
        self.assertGreaterEqual(
            output_metrics(report)["maxFrozenRenderedWithSkipsSeconds"], 5
        )

    def test_exactly_five_seconds_of_frozen_rendering_rejects(self):
        report = healthy_report()
        for sample in report["samples"]:
            if 10000 <= sample["ms"] <= 14000:
                sample["rendered"] = 225
                sample["skipped"] = (sample["ms"] - 9000) // 40
            elif sample["ms"] > 14000:
                sample["skipped"] = 125
        self.assertRejected(report, "rendered_frozen")

    def test_two_second_stall_recovers_without_rejecting(self):
        report = healthy_report()
        for sample in report["samples"]:
            if 10000 <= sample["ms"] <= 11000:
                sample["rendered"] = 225
                sample["skipped"] = (sample["ms"] - 9000) // 40
            elif sample["ms"] > 11000:
                sample["skipped"] = 50
        self.assertTrue(classify(report)["passed"], classify(report))
        self.assertLessEqual(
            output_metrics(report)["maxFrozenRenderedWithSkipsSeconds"], 2
        )

    def test_counter_reset_breaks_separate_short_freeze_streaks(self):
        report = healthy_report()
        for sample in report["samples"]:
            ms = sample["ms"]
            if 10000 <= ms <= 12000:
                sample.update(rendered=225, skipped=(ms - 9000) // 40)
            elif ms == 13000:
                sample.update(rendered=0, skipped=0, positionMs=0)
            elif 14000 <= ms <= 16000:
                sample.update(rendered=0, skipped=(ms - 13000) // 40)
            elif ms > 16000:
                sample.update(rendered=(ms - 13000) // 40, skipped=75)
        self.assertTrue(classify(report)["passed"], classify(report))
        metrics = output_metrics(report)
        self.assertLess(metrics["maxFrozenRenderedWithSkipsSeconds"], 5)
        self.assertGreater(metrics["renderedFps"], 0)

    def test_fixture_loop_and_render_counter_reset_are_allowed(self):
        report = healthy_report()
        for sample in report["samples"]:
            sample["positionMs"] = sample["ms"] % 30000
            sample["rendered"] = (sample["ms"] % 30000) // 40
        self.assertTrue(classify(report)["passed"], classify(report))
        self.assertAlmostEqual(output_metrics(report)["renderedFps"], 25.0, places=2)

    def test_zero_render_progress_fails_even_without_skip_counter_growth(self):
        report = healthy_report()
        for sample in report["samples"]:
            sample["rendered"] = 1
        self.assertRejected(report, "no_render_progress")

    def test_partial_duration_or_unfinished_status_rejects(self):
        cases = (
            ("status", "running", "not_finished"),
            ("elapsedMs", 59999, "duration_incomplete"),
            ("firstFrameMs", -1, "no_first_frame"),
        )
        for key, value, reason in cases:
            with self.subTest(key=key):
                report = healthy_report()
                report[key] = value
                self.assertRejected(report, reason)
        report = healthy_report()
        report["samples"].pop()
        self.assertRejected(report, "sample_duration_incomplete")

    def test_top_level_and_sample_errors_reject_even_if_recovered(self):
        for field, reason in (("errors", "playback_error"), ("codecErrors", "codec_error")):
            for scope in ("report", "sample"):
                with self.subTest(field=field, scope=scope):
                    report = healthy_report()
                    target = report if scope == "report" else report["samples"][12]
                    target[field] = 1
                    self.assertRejected(report, reason)

    def test_lifecycle_losses_reject_even_when_final_state_recovers(self):
        cases = (
            ("backgroundEvents", "resumed", "backgrounded"),
            ("surfaceLosses", "surfaceAvailable", "surface_lost"),
            ("surfaceLosses", "surfaceValid", "surface_lost"),
            ("focusLosses", "windowFocused", "focus_lost"),
        )
        for counter, flag, reason in cases:
            for evidence in ("counter", "sample"):
                with self.subTest(counter=counter, flag=flag, evidence=evidence):
                    report = healthy_report()
                    if evidence == "counter":
                        report["lifecycleEvidence"][counter] = 1
                    else:
                        report["samples"][12][flag] = False
                    self.assertTrue(report["samples"][-1][flag])
                    self.assertRejected(report, reason)

    def test_missing_schema_or_lifecycle_telemetry_rejects(self):
        for missing in ("schemaVersion", "lifecycleEvidence", "sample_flag"):
            with self.subTest(missing=missing):
                report = healthy_report()
                if missing == "sample_flag":
                    del report["samples"][12]["surfaceValid"]
                else:
                    del report[missing]
                self.assertRejected(report, "lifecycle_unverified")

    def test_missing_or_insufficient_exo_render_telemetry_rejects(self):
        for missing in ("rendered", "skipped"):
            with self.subTest(missing=missing):
                report = healthy_report()
                for sample in report["samples"]:
                    sample.pop(missing)
                self.assertRejected(report, "missing_render_evidence")
        report = healthy_report()
        for sample in report["samples"][:-1]:
            sample.pop("rendered")
            sample.pop("skipped")
        self.assertRejected(report, "missing_render_evidence")


class ObservedFormatTests(unittest.TestCase):
    def test_exo_format_is_observed_instead_of_inferred_from_requested_fixture(self):
        report = healthy_report()
        report["requestedFixture"] = "avc1080"
        observed = observed_format(report)
        self.assertEqual(observed["codec"], "HEVC")
        self.assertEqual(observed["width"], 1920)
        self.assertEqual(observed["height"], 1080)
        self.assertEqual(observed["frameRate"], 25.0)
        self.assertEqual(observed["fixture"], "hevc1080")
        self.assertFalse(observed["matchesRequested"])
        self.assertIn("fixture_mismatch", classify(report)["reasons"])

    def test_requested_fixture_does_not_supply_missing_observed_codec(self):
        report = healthy_report()
        report.pop("videoFormat")
        report.pop("decoder")
        observed = observed_format(report)
        self.assertIsNone(observed["codec"])
        self.assertIsNone(observed["fixture"])
        self.assertIsNone(observed["matchesRequested"])
        self.assertIn("fixture_unverified", classify(report)["reasons"])

    def test_ffmpeg_lavc_library_prefix_is_not_an_avc_codec_observation(self):
        report = healthy_report()
        report["decoder"] = "ffmpegLavc63.1.102-vp9"
        report["videoFormat"]["sampleMimeType"] = "video/x-vnd.on2.vp9"
        self.assertIsNone(observed_format(report)["codec"])
        self.assertIn("fixture_unverified", classify(report)["reasons"])

    def test_mpv_uses_sampled_codec_dimensions_and_fps(self):
        report = healthy_report("mpv-hard")
        report["requestedFixture"] = "avc1080"
        observed = observed_format(report)
        self.assertEqual(observed["codec"], "HEVC")
        self.assertEqual(observed["width"], 1920)
        self.assertEqual(observed["height"], 1080)
        self.assertEqual(observed["frameRate"], 25.0)
        self.assertEqual(observed["fixture"], "hevc1080")
        self.assertFalse(observed["matchesRequested"])

    def test_mpv_does_not_require_exo_render_counters(self):
        report = healthy_report("mpv-hard")
        self.assertTrue(classify(report)["passed"], classify(report))
        self.assertIsNone(output_metrics(report)["renderedFps"])

    def test_hevc_4k_and_avc_and_av1_are_recognized_from_observations(self):
        cases = (
            ("video/hevc", "ffmpegLavc63.1.102-hevc", 3840, 2160, "HEVC", "hevc4k"),
            ("video/avc", "ffmpegLavc63.1.102-h264", 1920, 1080, "AVC", "avc1080"),
            ("video/av01", "c2.android.av1.decoder", 1920, 1080, "AV1", None),
        )
        for mime, decoder, width, height, codec, fixture in cases:
            with self.subTest(codec=codec, fixture=fixture):
                report = healthy_report()
                report["decoder"] = decoder
                report["videoFormat"].update(sampleMimeType=mime, width=width, height=height)
                observed = observed_format(report)
                self.assertEqual(observed["codec"], codec)
                self.assertEqual(observed["width"], width)
                self.assertEqual(observed["height"], height)
                if fixture is not None:
                    self.assertEqual(observed["fixture"], fixture)

    def test_contradictory_exo_mime_and_decoder_report_codec_conflict(self):
        report = healthy_report()
        report["decoder"] = "ffmpegLavc63.1.102-h264"
        observed = observed_format(report)
        self.assertEqual(observed["codec"], "conflict")
        self.assertIsNone(observed["fixture"])
        self.assertIn("codec_conflict", classify(report)["reasons"])
        self.assertFalse(classify(report)["passed"])

    def test_contradictory_mpv_sample_codecs_report_codec_conflict(self):
        report = healthy_report("mpv-soft")
        report["samples"][12]["decoder"] = "H.264 / AVC / MPEG-4 AVC / MPEG-4 part 10"
        observed = observed_format(report)
        self.assertEqual(observed["codec"], "conflict")
        self.assertIsNone(observed["fixture"])
        self.assertIn("codec_conflict", classify(report)["reasons"])

    def test_classification_and_metrics_do_not_mutate_report(self):
        report = healthy_report()
        original = copy.deepcopy(report)
        classify(report)
        observed_format(report)
        output_metrics(report)
        self.assertEqual(report, original)


if __name__ == "__main__":
    unittest.main()
