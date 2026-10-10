"""Measure controlled source latency on LDPlayer and restore every original data file.

This is a textual localhost fixture, not an external-source speed benchmark. UI
hierarchy snapshots give an observed first-card upper bound (not frame timing).
Only 127.0.0.1:5555 is used, through the existing verified emulator wrapper.
Run --fixture-self-test to validate this script without any device operations.
"""
from __future__ import annotations

import argparse
import http.server
import json
import pathlib
import re
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

from verify_all_source_discovery import (
    ADULT_CHIP, DEFAULT_PREF, DISCOVERY_PREF, ORDINARY_CHIP,
    AllSourceVerification, Fixture,
)
from verify_discovery import PACKAGE, SERIAL, TABS


LOADING_LINE = "正在筛选全部站源"


class LatencyFixture:
    """One deliberately slow first source; subsequent sites return fast labels."""

    def __init__(self, port=0, sources=18, slow_seconds=9.0, fast_seconds=.25):
        self.sources = ("srcA",) + tuple("src%02d" % index for index in range(1, sources))
        self.slow_seconds, self.fast_seconds = slow_seconds, fast_seconds
        self.lock = threading.Lock()
        self.events = []
        self.stage = "setup"
        self.active = 0
        self.origin = time.monotonic()
        fixture = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_GET(self):
                self.respond()

            def do_POST(self):
                self.respond()

            def respond(self):
                parsed = urllib.parse.urlsplit(self.path)
                query = urllib.parse.parse_qs(parsed.query)
                if self.command == "POST":
                    size = min(int(self.headers.get("Content-Length", "0")), 65536)
                    query.update(urllib.parse.parse_qs(self.rfile.read(size).decode("utf-8")))
                code, body, event, pause = fixture.response(parsed.path, query)
                if pause:
                    time.sleep(pause)
                with fixture.lock:
                    event["response_ready_s"] = fixture.elapsed()
                    fixture.active -= int(event["processing"])
                outcome = "sent"
                try:
                    data = json.dumps(body, ensure_ascii=False).encode("utf-8")
                    self.send_response(code)
                    self.send_header("Content-Type", "application/json; charset=utf-8")
                    self.send_header("Content-Length", str(len(data)))
                    self.send_header("Cache-Control", "no-store")
                    self.end_headers()
                    self.wfile.write(data)
                except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                    outcome = "disconnected"
                finally:
                    with fixture.lock:
                        event.update(completed=True, response=outcome,
                                     response_done_s=fixture.elapsed())

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", port), Handler)
        self.server.daemon_threads = True
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever,
                                       name="ysc-latency-fixture", daemon=True)

    def elapsed(self):
        return time.monotonic() - self.origin

    @property
    def config_url(self):
        return "http://127.0.0.1:%d/config.json" % self.port

    def start(self):
        self.thread.start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=3)

    def mark(self):
        with self.lock:
            return len(self.events)

    def set_stage(self, stage):
        with self.lock:
            self.stage = stage
        return stage

    def snapshot(self, since=0, stage=None):
        with self.lock:
            return [dict(event) for event in self.events[since:]
                    if stage is None or event["stage"] == stage]

    @staticmethod
    def vod(source, marker, *, adult=False, genre="g-any", search=""):
        # No pictures, playable URLs or adult visuals; labels exercise scope only.
        prefix = "Keyword" + search if search else "AdultScope" if adult else "Ord"
        return {"vod_id": "qa-" + (search or marker) + "-" + genre,
                "vod_name": prefix + " " + marker + " " + source,
                "vod_pic": "", "type_name": "电影" if marker == "M" else "电视剧",
                "vod_class": "18+ 成人向" if adult else "喜剧",
                "vod_year": "2026", "vod_area": "中国大陆", "vod_score": "8.0",
                "vod_remarks": "QA textual label", "vod_play_from": "", "vod_play_url": "",
                "vod_content": "Isolated latency fixture label only."}

    def response(self, path, query):
        source = path.strip("/").removeprefix("api/")
        source = source if source in self.sources else "fixture"
        category = query.get("t", [""])[0]
        category = category if category in ("movie-alpha", "tv-alpha") else "INVALID" if category else ""
        word = query.get("wd", [""])[0]
        word = word if word in ("LatencyOld", "LatencyNew") else "INVALID" if word else ""
        ids = query.get("ids", [""])[0]
        ids = ids if re.fullmatch(r"qa-[A-Za-z0-9,-]+", ids) else "INVALID" if ids else ""
        try:
            page = int(query.get("pg", ["1"])[0])
            facets = json.loads(query.get("f", ["{}"]) [0])
        except (ValueError, TypeError):
            page, facets = -1, {}
        allowed = {"k_gate": ("normal-only", "adult-only"), "k_genre": ("g-any", "g-comedy")}
        safe_facets = {key: value if value in options else "INVALID"
                       for key, options in allowed.items() if key in facets
                       for value in [facets[key]]} if isinstance(facets, dict) else {}
        kind = "config" if path == "/config.json" else "search" if word else "detail" if ids else "page" if category else "metadata"
        with self.lock:
            stage = self.stage
            processing = kind in ("metadata", "page", "search", "detail")
            self.active += int(processing)
            pause = self.fast_seconds if processing else 0
            delayed = source == "srcA" and kind == "page" and stage != "setup"
            if source == "srcA" and kind == "metadata" and stage == "cold-score":
                pause = 1.5
            if delayed:
                # A cold first-source round totals 9 s; warm cached rounds delay its
                # one page by 9 s. Every later source remains independently fast.
                pause = self.slow_seconds - 1.5 if stage == "cold-score" else self.slow_seconds
            if kind == "search" and word == "LatencyOld" and source == "srcA":
                delayed, pause = True, self.slow_seconds
            event = {"index": len(self.events), "stage": stage, "source": source,
                     "kind": kind, "category": category, "page": page,
                     "facets": safe_facets, "word": word, "ids": ids,
                     "request_s": self.elapsed(), "active_requests": self.active,
                     "processing": processing, "delay_s": pause, "delayed": delayed,
                     "completed": False, "response": "pending", "status": 200}
            self.events.append(event)
        if kind == "config":
            body = {"sites": [{"key": key, "name": key, "type": 1,
                               "api": "http://127.0.0.1:%d/api/%s" % (self.port, key),
                               "searchable": 0 if key == "src01" else 1,
                               "quickSearch": 0, "timeout": 20}
                              for key in self.sources], "parses": [], "flags": [], "ads": []}
        elif source == "fixture":
            event["status"], body = 404, {"error": "Unknown latency fixture route"}
        elif kind == "metadata":
            body = {"class": [{"type_id": "movie-alpha", "type_name": "电影"},
                              {"type_id": "tv-alpha", "type_name": "电视剧"}],
                    "filters": {key: Fixture.facets() for key in ("movie-alpha", "tv-alpha")},
                    "list": []}
        elif kind == "search":
            suffix = word.removeprefix("Latency")
            body = {"page": 1, "pagecount": 1, "list": [self.vod(source, "M", search=suffix)]}
        elif kind == "detail":
            # Search's optional empty-poster enrichment receives the same label.
            suffix = "Old" if "Old" in ids else "New" if "New" in ids else ""
            body = {"list": [self.vod(source, "M", search=suffix)]}
        elif category == "INVALID" or page != 1 or safe_facets.get("k_gate") not in allowed["k_gate"] or safe_facets.get("k_genre") not in allowed["k_genre"]:
            event["status"], body = 400, {"error": "Undeclared fixture category or facet"}
        else:
            marker = "M" if category == "movie-alpha" else "T"
            body = {"page": 1, "pagecount": 1, "limit": 1, "total": 1,
                    "list": [self.vod(source, marker, adult=safe_facets["k_gate"] == "adult-only",
                                      genre=safe_facets["k_genre"])]}
        return event["status"], body, event, pause


class LatencyVerification(AllSourceVerification):
    def __init__(self, output, fixture, wait_seconds, *, baseline=False, keyword=False):
        # Parent verifies SERIAL is an actual LDPlayer/AVD before any mutation.
        assert SERIAL == "127.0.0.1:5555", "Only the authorized emulator serial is allowed"
        super().__init__(output, fixture, wait_seconds)
        self.baseline, self.keyword = baseline, keyword
        self.metrics = {}
        self.observations = []
        self.forward_port = None

    def install_fixture(self):
        super().install_fixture()
        # Cold score mode tests the v25 whole-round ordering wait specifically.
        saved = {"tab_4": json.dumps({"content": "情色", "genre": "", "region": "", "sort": "S", "year": ""}, ensure_ascii=False),
                 "tab_5": json.dumps({"content": "情色", "type": "", "tv_form": "", "show_form": "", "region": "", "sort": "U", "year": "", "platform": ""}, ensure_ascii=False)}
        with tempfile.TemporaryDirectory(prefix="ysc-latency-prefs-") as temporary:
            local = pathlib.Path(temporary) / DISCOVERY_PREF
            local.write_bytes(self.pref_xml(None, saved))
            self.install_file(local, "shared_prefs/" + DISCOVERY_PREF)

    def dump(self, label="ui"):
        started = self.fixture.elapsed()
        tree = super().dump(label)
        ended = self.fixture.elapsed()
        self.observations.append({"label": label, "snapshot_start_s": started,
                                  "snapshot_return_s": ended, "titles": self.titles(tree)})
        self.assert_no_success_count(tree)
        texts = " ".join(node.get("text", "") for node in tree.iter("node"))
        if not self.baseline:
            assert LOADING_LINE not in texts, "Removed all-source loading line is visible"
        return tree

    def measured_tap(self, stage, *, description=None, text=None):
        tree = self.dump(stage + "-before-tap")
        node = self.by_description(tree, description) if description else next(
            (node for node in tree.iter("node") if node.get("text") == text and node.get("clickable") == "true"), None)
        assert node is not None, "Required fixture control missing: " + (description or text)
        left, top, right, bottom = self.bounds(node)
        self.fixture.set_stage(stage)
        start_index, started = self.fixture.mark(), self.fixture.elapsed()
        self.shell("input", "tap", str((left + right) // 2), str((top + bottom) // 2))
        action_done = self.fixture.elapsed()
        return started, action_done, start_index

    def first_card(self, stage, timing, marker, *, adult=False, prefix=None):
        started, action_done, since = timing
        previous_absent = started
        prefix = prefix or ("AdultScope " if adult else "Ord ") + marker + " "
        deadline = time.monotonic() + self.wait_seconds
        while True:
            tree = self.dump(stage + "-first-card")
            if not adult:
                self.assert_ordinary(tree)
            matching = [title for title in self.titles(tree) if title.startswith(prefix)]
            if matching:
                observed = self.fixture.elapsed()
                records = self.fixture.snapshot(since, stage)
                pages = [event for event in records if event["kind"] in ("page", "search")]
                response_times = [event["response_ready_s"] for event in pages if "response_ready_s" in event]
                slow_events = [event for event in pages if event["delayed"]]
                metric = {"action_start_s": started, "action_done_s": action_done,
                          "first_visible_observed_s": observed,
                          "first_card_latency_upper_s": observed - started,
                          "last_absent_snapshot_return_s": previous_absent,
                          "ui_polling_interval_note": "UI hierarchy is asynchronous; latency is a wall-clock observed upper bound, not first rendered frame",
                          "first_response_ready_latency_s": min(response_times) - started if response_times else None,
                          "visible_sample": matching[:5],
                          "first_visible_before_slow_response_ready": bool(slow_events) and all(
                              event.get("response_ready_s", float("inf")) > observed for event in slow_events),
                          "slow_request_seen": bool(slow_events)}
                self.metrics[stage] = metric
                self.screenshot(stage + "-first-visible")
                return tree
            previous_absent = self.fixture.elapsed()
            if time.monotonic() >= deadline:
                raise AssertionError("First visible card timed out: " + stage)
            time.sleep(.05)

    def finish_phase(self, stage, timing, *, ordinary=True):
        started, _, since = timing
        self.wait_events(lambda records: all(any(event["kind"] == "page" and event["source"] == source
                                                and event["completed"] for event in records)
                                           for source in self.fixture.sources), stage + " all configured source responses", since)
        tree = self.wait_ui(stage + "-round-finished", lambda current: self.by_id(current, "loadProgress") is None,
                            ordinary=ordinary)
        records = self.fixture.snapshot(since, stage)
        processing = [event for event in records if event["processing"]]
        pages = [event for event in records if event["kind"] == "page"]
        assert all(event["status"] == 200 for event in processing), "Fixture rejected a declared query"
        metric = self.metrics[stage]
        metric.update({"all_responses_ready_latency_s": max(event["response_ready_s"] for event in pages) - started,
                       "round_finished_observed_latency_s": self.fixture.elapsed() - started,
                       "peak_processing_requests": max(event["active_requests"] for event in processing),
                       "metadata_request_count": sum(event["kind"] == "metadata" for event in records),
                       "page_request_count": len(pages),
                       "source_count": len({event["source"] for event in pages}),
                       "last_source_request_start_latency_s": max(event["request_s"] for event in pages) - started,
                       "slow_response_outcomes": [event["response"] for event in pages if event["delayed"]]})
        assert metric["source_count"] == len(self.fixture.sources), "Not all configured sources were attempted"
        assert metric["peak_processing_requests"] >= 2, "Fixture did not observe concurrent source processing"
        if stage != "cold-score":
            assert metric["metadata_request_count"] == 0, "Warm metadata was fetched again"
        if not self.baseline:
            assert metric["first_visible_before_slow_response_ready"], "Fast cards waited for the delayed source"
        self.record(stage + ": observed first visible card %.3f s, first response %.3f s; %d sources, peak %d" % (
            metric["first_card_latency_upper_s"], metric["first_response_ready_latency_s"],
            metric["source_count"], metric["peak_processing_requests"]))
        return tree

    def check_filter_latency(self):
        timing = self.measured_tap("cold-score", description=TABS[4])
        tree = self.first_card("cold-score", timing, "M")
        assert self.by_description(tree, ORDINARY_CHIP).get("selected") == "true", "Legacy adult mode restored"
        self.finish_phase("cold-score", timing)
        timing = self.measured_tap("warm-tv", description=TABS[5])
        self.first_card("warm-tv", timing, "T")
        self.finish_phase("warm-tv", timing)
        timing = self.measured_tap("warm-score", description=TABS[4])
        self.first_card("warm-score", timing, "M")
        self.finish_phase("warm-score", timing)
        timing = self.measured_tap("warm-genre", description="类型：喜剧")
        self.first_card("warm-genre", timing, "M")
        self.finish_phase("warm-genre", timing)
        records = self.fixture.snapshot(stage="warm-genre")
        assert all(event["facets"].get("k_genre") == "g-comedy" for event in records if event["kind"] == "page"), "Warm genre query lost declared facet"
        self.record("Warm movie/TV switching and genre selection reuse metadata and include searchable=0")

    def check_cancelled_adult(self):
        timing = self.measured_tap("cancel-adult", description=ADULT_CHIP)
        start_index = timing[2]
        self.wait_events(lambda records: any(event["kind"] == "page" and event["delayed"] for event in records),
                         "adult delayed page started", start_index)
        delayed = [event for event in self.fixture.snapshot(start_index) if event["delayed"]]
        assert delayed and not delayed[0]["completed"], "Adult request finished before cancellation"
        timing = self.measured_tap("cancel-ordinary", description=ORDINARY_CHIP)
        tree = self.first_card("cancel-ordinary", timing, "M")
        assert self.by_description(tree, ORDINARY_CHIP).get("selected") == "true"
        self.finish_phase("cancel-ordinary", timing)
        self.wait_events(lambda records: any(event["delayed"] and event["completed"] for event in records),
                         "cancelled adult fixture response eventually completed", start_index)
        self.assert_ordinary(self.dump("cancel-adult-after-late-response"))
        home_timing = self.measured_tap("cancel-home", description="主页")
        home = self.dump("cancel-home-isolation")
        self.assert_ordinary(home)
        self.check_panel(home, False)
        self.measured_tap("reentry-ordinary", description=TABS[4])
        tree = self.wait_ui("reentry-ordinary-ready", lambda current: any(title.startswith("Ord M ") for title in self.titles(current)), ordinary=True)
        assert self.by_description(tree, ORDINARY_CHIP).get("selected") == "true"
        self.record("Cancellation rejects delayed 18+ response; Home is isolated and movie re-entry is ordinary")

    def connect_app_server(self):
        for remote in range(9978, 9999):
            local = self.command("forward", "tcp:0", "tcp:%d" % remote).decode("ascii").strip()
            assert local.isdigit(), "ADB did not allocate the fixture forward port"
            try:
                with urllib.request.urlopen("http://127.0.0.1:%s/device" % local, timeout=1) as response:
                    value = json.loads(response.read(32768).decode("utf-8"))
                if isinstance(value, dict) and {"type", "uuid", "ip"}.issubset(value):
                    self.forward_port = local
                    return
            except (OSError, ValueError, urllib.error.URLError):
                pass
            self.command("forward", "--remove", "tcp:" + local, check=False)
        raise AssertionError("App local HTTP endpoint unavailable for keyword fixture")

    def request_keyword(self, stage, keyword):
        assert keyword in ("LatencyOld", "LatencyNew")
        self.fixture.set_stage(stage)
        since, started = self.fixture.mark(), self.fixture.elapsed()
        body = urllib.parse.urlencode({"do": "search", "word": keyword}).encode("utf-8")
        request = urllib.request.Request("http://127.0.0.1:%s/action" % self.forward_port,
                                         data=body, headers={"Content-Type": "application/x-www-form-urlencoded"})
        with urllib.request.urlopen(request, timeout=8) as response:
            assert response.status == 200, "Local keyword search action failed"
        return started, self.fixture.elapsed(), since

    def check_keyword_latency(self):
        # Home's existing local search API opens SearchActivity/CollectActivity.
        self.tap(description="主页")
        self.connect_app_server()
        timing = self.request_keyword("keyword-old", "LatencyOld")
        self.first_card("keyword-old", timing, "M", prefix="KeywordOld M ")
        self.wait_events(lambda records: any(event["kind"] == "search" and event["delayed"] for event in records),
                         "old keyword delayed request started", timing[2])
        old = [event for event in self.fixture.snapshot(timing[2], "keyword-old") if event["delayed"]]
        assert old and not old[0]["completed"], "Old keyword finished before Back cancellation"
        self.key(4)
        self.wait_ui("keyword-return-home", lambda current: self.by_description(current, "主页") is not None, ordinary=True)
        next_timing = self.request_keyword("keyword-new", "LatencyNew")
        tree = self.first_card("keyword-new", next_timing, "M", prefix="KeywordNew M ")
        assert not any(title.startswith("KeywordOld") for title in self.titles(tree)), "Previous keyword cards escaped cancellation"
        expected = set(self.fixture.sources) - {"src01"}
        self.wait_events(lambda records: expected.issubset({event["source"] for event in records if event["kind"] == "search" and event["completed"]}),
                         "new keyword eligible source responses", next_timing[2])
        self.wait_events(lambda records: any(event["delayed"] and event["completed"] for event in records),
                         "old keyword late response completed", timing[2])
        tree = self.dump("keyword-new-after-old-response")
        assert not any(title.startswith("KeywordOld") for title in self.titles(tree)), "Late old keyword overwrote current results"
        for stage in ("keyword-old", "keyword-new"):
            records = self.fixture.snapshot(stage=stage)
            searches = [event for event in records if event["kind"] == "search"]
            self.metrics[stage].update({"search_source_count": len({event["source"] for event in searches}),
                                       "peak_processing_requests": max(event["active_requests"] for event in records if event["processing"]),
                                       "no_searchable_zero_request": not any(event["source"] == "src01" for event in searches)})
        self.screenshot("keyword-new-after-cancel")
        self.key(4)
        self.wait_ui("keyword-final-home", lambda current: self.by_description(current, "主页") is not None, ordinary=True)
        self.record("Keyword searches process concurrently; Back cancels old search and late old results stay absent")

    def write_result(self):
        result = {"serial": SERIAL, "fixture_only": True, "baseline": self.baseline,
                  "fixture": {"sources": len(self.fixture.sources), "delayed_first_source_seconds": self.fixture.slow_seconds,
                              "fast_request_seconds": self.fixture.fast_seconds, "text_only_no_media": True},
                  "checks": self.steps, "details": self.check_details, "metrics": self.metrics,
                  "original_restored_and_hash_verified": self.restore_verified, "error": self.error,
                  "not_covered": ["real external-source speed", "frame-accurate rendering latency", "type-3 crawler timing", "phone runtime"]}
        (self.output / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        (self.output / "fixture-requests.json").write_text(json.dumps(self.fixture.snapshot(), ensure_ascii=False, indent=2), encoding="utf-8")
        (self.output / "ui-observations.json").write_text(json.dumps(self.observations, ensure_ascii=False, indent=2), encoding="utf-8")

    def run(self):
        restore_error = None
        try:
            self.snapshot_app_data()
            self.fixture.start()
            self.command("reverse", "tcp:%d" % self.fixture.port, "tcp:%d" % self.fixture.port)
            self.reverse_installed = True
            self.install_fixture()
            self.command("logcat", "-b", "crash", "-c")
            self.launch()
            self.wait_events(lambda records: any(event["kind"] == "config" and event["completed"] for event in records), "latency fixture config loaded")
            self.check_filter_latency()
            self.check_cancelled_adult()
            if self.keyword:
                self.check_keyword_latency()
            crash = self.command("logcat", "-b", "crash", "-d").decode("utf-8", errors="replace")
            (self.output / "crash.log").write_text(crash, encoding="utf-8")
            assert "FATAL EXCEPTION" not in crash and "Fatal signal" not in crash, "Latency fixture produced a fatal crash"
            self.record("Crash buffer contains no fatal exception/signal")
        except BaseException as error:
            self.error = str(error) if isinstance(error, AssertionError) else type(error).__name__
            raise
        finally:
            try:
                self.restore_app_data()
            except BaseException as error:
                restore_error = error
                self.check_details["restoration_error"] = type(error).__name__
            try:
                if self.forward_port:
                    self.command("forward", "--remove", "tcp:" + self.forward_port, check=False)
                if self.reverse_installed:
                    self.command("reverse", "--remove", "tcp:%d" % self.fixture.port, check=False)
                self.cleanup_remote()
            finally:
                if self.fixture.thread.is_alive():
                    self.fixture.close()
                self.write_result()
            if self.restore_verified:
                try:
                    self.launch()
                    tree = self.tap(description="主页")
                    self.check_panel(tree, False)
                    self.screenshot("restored-home")
                    self.check_details["final_screen"] = "Home"
                except BaseException as error:
                    self.check_details["final_home_error"] = type(error).__name__
                    raise
                finally:
                    self.write_result()
            if restore_error is not None:
                raise RuntimeError("Original data restoration failed; inspect private-original and result.json") from restore_error


def fixture_self_test():
    """Check routing, timing and textual fixture content without touching adb."""
    fixture = LatencyFixture(sources=18, slow_seconds=9, fast_seconds=.01)
    fixture.start()
    try:
        with urllib.request.urlopen(fixture.config_url, timeout=3) as response:
            config = json.load(response)
        assert len(config["sites"]) == 18 and config["sites"][1]["searchable"] == 0
        for key, route in (("src01", ""), ("src02", "?t=movie-alpha&f=" + urllib.parse.quote(json.dumps({"k_gate": "normal-only", "k_genre": "g-comedy"}))),
                           ("src03", "?wd=LatencyNew"), ("src03", "?ids=qa-New-g-any")):
            with urllib.request.urlopen("http://127.0.0.1:%d/api/%s%s" % (fixture.port, key, route), timeout=3) as response:
                payload = json.load(response)
            for item in payload.get("list", []):
                assert not item["vod_pic"] and not item["vod_play_url"]
        fixture.set_stage("cold-score")
        _, _, event, pause = fixture.response("/api/srcA", {"t": ["movie-alpha"], "f": [json.dumps({"k_gate": "normal-only", "k_genre": "g-any"})]})
        assert pause == 7.5 and event["delayed"]
        records = fixture.snapshot()
        assert all(event["request_s"] >= 0 for event in records)
        assert all(event["response_ready_s"] >= event["request_s"] for event in records[:-1])
        print("PASS: 18 textual fixture sites, searchable=0, categories/facets/search/details and monotonic request/response timing; no adb used", flush=True)
    finally:
        fixture.close()


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=pathlib.Path, default=pathlib.Path(__file__).resolve().parents[1] / ".codex/session/qa/discovery-latency")
    parser.add_argument("--baseline", action="store_true", help="Observe v25; tolerate its loading text and waiting score order")
    parser.add_argument("--keyword", action="store_true", help="Also exercise existing local keyword search API and Back cancellation")
    parser.add_argument("--fixture-self-test", action="store_true", help="Validate host-only fixture; never access adb")
    parser.add_argument("--sources", type=int, default=18)
    parser.add_argument("--port", type=int, default=0)
    parser.add_argument("--slow-seconds", type=float, default=9)
    parser.add_argument("--fast-seconds", type=float, default=.25)
    parser.add_argument("--wait-seconds", type=float, default=55)
    args = parser.parse_args()
    if args.fixture_self_test:
        fixture_self_test()
        return
    if not 13 <= args.sources <= 24 or not 8 <= args.slow_seconds <= 10 or not .05 <= args.fast_seconds <= 1 or not 30 <= args.wait_seconds <= 60:
        parser.error("Require sources 13..24, slow-seconds 8..10, fast-seconds .05..1, wait-seconds 30..60")
    fixture = LatencyFixture(args.port, args.sources, args.slow_seconds, args.fast_seconds)
    try:
        LatencyVerification(args.output.resolve(), fixture, args.wait_seconds,
                            baseline=args.baseline, keyword=args.keyword).run()
    finally:
        if not fixture.thread.is_alive():
            fixture.server.server_close()


if __name__ == "__main__":
    main()
