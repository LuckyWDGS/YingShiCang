"""Verify TV search input on the guarded LDPlayer and restore original app data.

The localhost fixture contains text labels only. UIAutomator times are observed
upper bounds, not frame timings. Suggestion-provider networking is not intercepted;
clear-state checks cover late visible results, while deterministic cancellation is
covered by application unit tests. Chinese submission uses the existing local
search action, so this does not claim Chinese IME/editor typing coverage.
"""
from __future__ import annotations

import argparse
from contextlib import closing
import json
import pathlib
import sqlite3
import sys
import tempfile
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

from verify_all_source_discovery import AllSourceVerification, DATABASE_FILES, DEFAULT_PREF
from verify_discovery import PACKAGE, SERIAL
from verify_discovery_latency import LatencyFixture, LatencyVerification


QUERIES = {"A": "A", "AB": "AB", "ABC": "ABC", "春": "Spring"}


class InputFixture(LatencyFixture):
    """Two enabled sources and one disabled source, all with fast textual hits."""

    def __init__(self, port=0):
        super().__init__(port=port, sources=3, fast_seconds=.02)

    @staticmethod
    def input_vod(source, token):
        item = LatencyFixture.vod(source, "M")
        item.update(vod_id="qa-input-" + token, vod_name="Input-" + token + " " + source,
                    vod_content="Isolated input fixture label only.")
        return item

    def response(self, path, query):
        word = query.get("wd", [""])[0]
        prepared = dict(query)
        if word in QUERIES:
            prepared["wd"] = ["LatencyNew"]
        code, body, event, pause = super().response(path, prepared)
        if event["kind"] == "search" and word in QUERIES:
            with self.lock:
                event["word"] = word
            body = {"page": 1, "pagecount": 1,
                    "list": [self.input_vod(event["source"], QUERIES[word])]}
        elif event["kind"] == "detail" and event["ids"].startswith("qa-input-"):
            token = event["ids"].removeprefix("qa-input-")
            if token in QUERIES.values():
                body = {"list": [self.input_vod(event["source"], token)]}
        return code, body, event, pause


class InputVerification(AllSourceVerification):
    connect_app_server = LatencyVerification.connect_app_server

    def __init__(self, output, fixture, wait_seconds, *, baseline=False):
        assert SERIAL == "127.0.0.1:5555", "Only the authorized emulator is allowed"
        # Parent calls verified_adb before any app or device mutation.
        super().__init__(output, fixture, wait_seconds)
        self.forward_port = None
        self.metrics = {}
        self.baseline = baseline
        self.observations = []

    def install_fixture(self):
        """Replace only the active config and temporary input/rank preferences."""
        assert self.original is not None
        with tempfile.TemporaryDirectory(prefix="ysc-input-qa-") as temporary:
            temp = pathlib.Path(temporary)
            for name in DATABASE_FILES:
                data = self.original.get("databases/" + name)
                if data is not None:
                    (temp / name).write_bytes(data)
            prepared = temp / "fixture-db"
            with closing(sqlite3.connect(temp / "tv")) as source, closing(sqlite3.connect(prepared)) as target:
                source.backup(target)
                row = target.execute("SELECT id FROM Config WHERE type=0 ORDER BY time DESC LIMIT 1").fetchone()
                assert row is not None, "No active type-0 config; no app files changed"
                target.execute("UPDATE Config SET url=?,name=?,home=?,json=? WHERE id=?",
                               (self.fixture.config_url, "QA input fixture", "srcA", "", row[0]))
                target.commit()
            self.install_file(prepared, "databases/tv")
            for name in DATABASE_FILES[1:]:
                self.shell("run-as", PACKAGE, "rm", "-f", "databases/" + name)
            self.shell("run-as", PACKAGE, "mkdir", "-p", "shared_prefs")
            default = ET.fromstring(self.original.get("shared_prefs/" + DEFAULT_PREF, b"<map/>"))
            for key in ("config_0", "keyword", "zhuyin"):
                for node in list(default):
                    if node.get("name") == key:
                        default.remove(node)
            ET.SubElement(default, "string", {"name": "config_0"}).text = self.fixture.config_url
            ET.SubElement(default, "string", {"name": "keyword"}).text = "[]"
            ET.SubElement(default, "boolean", {"name": "zhuyin", "value": "false"})
            rank = {"rank" + str(index): json.dumps({"data": [{"title": "QA rank " + str(index)}]})
                    for index in (1, 2, 3)}
            preferences = {DEFAULT_PREF: ET.tostring(default, encoding="utf-8", xml_declaration=True),
                           "search_ranks.xml": self.pref_xml(None, rank)}
            for name, data in preferences.items():
                local = temp / name
                local.write_bytes(data)
                self.shell("run-as", PACKAGE, "rm", "-f", "shared_prefs/" + name + ".bak")
                self.install_file(local, "shared_prefs/" + name)

    def point(self, tree, *, identifier=None, text=None):
        node = self.by_id(tree, identifier) if identifier else next(
            (candidate for candidate in tree.iter("node") if candidate.get("text") == text), None)
        assert node is not None, "Visible input control missing: " + (identifier or text)
        parents = {child: parent for parent in tree.iter() for child in parent}
        while node is not None and node.get("clickable") != "true":
            node = parents.get(node)
        assert node is not None, "Input target lacks a clickable ancestor"
        left, top, right, bottom = self.bounds(node)
        return (left + right) // 2, (top + bottom) // 2

    def tap_point(self, point):
        self.shell("input", "tap", str(point[0]), str(point[1]))

    def tap_control(self, tree, *, identifier=None, text=None):
        self.tap_point(self.point(tree, identifier=identifier, text=text))

    def assert_editor(self, tree, expected):
        editor = self.by_id(tree, "keyword")
        assert editor is not None, "Search editor is missing"
        actual = editor.get("text", "")
        # Some Android accessibility implementations expose an empty edit hint as text.
        if not expected and actual == "请输入要搜索的内容":
            actual = ""
        assert actual == expected, "Editor text mismatch"
        if expected:
            status = self.by_id(tree, "searchStatus")
            self.observations.append({"editor": expected, "observed_s": self.fixture.elapsed(),
                                      "suggestion_status_present": status is not None,
                                      "suggestion_status_text": status.get("text", "") if status is not None else ""})
            if not self.baseline:
                assert status is None, "Nonempty input shows a suggestion status prompt"
            assert self.by_id(tree, "hotGroups") is None, "Hot groups overlap nonempty input"
            assert self.by_id(tree, "word").get("text") == "相关关键词", "Nonempty input heading mismatch"
        else:
            assert self.by_id(tree, "hotGroups") is not None, "Clearing input does not restore hot groups"
            assert self.by_id(tree, "wordRecycler") is None, "Old suggestions remain visible after clearing"

    def open_editor(self):
        home = self.wait_ui("home-before-input", lambda tree: self.by_description(tree, "主页") is not None)
        self.tap_control(home, text="搜索")
        tree = self.wait_ui("input-open", lambda value: self.by_id(value, "keyword") is not None)
        self.assert_editor(tree, "")
        self.screenshot("input-empty")
        return tree

    def check_typing(self):
        tree = self.open_editor()
        points = {letter: self.point(tree, text=letter) for letter in "ABC"}
        start = time.monotonic()
        self.tap_point(points["A"])
        accepted = time.monotonic()
        self.screenshot("input-single-A-immediate")
        tree = self.dump("input-single-A")
        self.assert_editor(tree, "A")
        self.metrics["single_letter_input"] = {"tap_command_completion_s": accepted - start,
                                               "editor_observed_upper_s": time.monotonic() - start}
        self.record("Single A appears in the editor; suggestion prompt presence recorded" if self.baseline
                    else "Single A appears in the editor without a suggestion prompt")
        self.tap_control(tree, identifier="clearInput")
        tree = self.dump("before-rapid-typing")
        self.assert_editor(tree, "")
        start = time.monotonic()
        for letter in "ABC":
            self.tap_point(points[letter])  # No per-key sleep or UI hierarchy wait.
        accepted = time.monotonic()
        self.screenshot("input-rapid-ABC-immediate")
        tree = self.dump("input-rapid-ABC")
        self.assert_editor(tree, "ABC")
        self.metrics["rapid_ABC"] = {"three_tap_command_completion_s": accepted - start,
                                      "editor_observed_upper_s": time.monotonic() - start}
        self.record("Three consecutive keyboard taps retain ABC; suggestion prompt presence recorded" if self.baseline
                    else "Three consecutive keyboard taps retain ABC without a suggestion prompt")
        self.tap_control(tree, identifier="backspace")
        tree = self.dump("input-delete-AB")
        self.assert_editor(tree, "AB")
        self.record("Delete removes one trailing character and retains AB; suggestion prompt presence recorded" if self.baseline
                    else "Delete removes one trailing character and retains AB without a prompt")
        self.tap_control(tree, identifier="clearInput")
        tree = self.dump("input-cleared")
        self.assert_editor(tree, "")
        time.sleep(3.2)
        tree = self.dump("input-cleared-after-late-suggestions")
        self.assert_editor(tree, "")
        self.screenshot("input-cleared-after-late-suggestions")
        self.record("Clear restores hot groups; late suggestion activity does not reappear in the cleared editor")
        # A held Delete retains the original clear-all shortcut.
        self.tap_point(points["A"])
        tree = self.dump("input-before-held-delete")
        x, y = self.point(tree, identifier="backspace")
        self.shell("input", "swipe", str(x), str(y), str(x), str(y), "800")
        tree = self.dump("input-held-delete")
        self.assert_editor(tree, "")
        self.record("Holding Delete clears all input")
        return tree, points

    def observe_results(self, stage, word, started, since):
        prefix = "Input-" + QUERIES[word] + " "
        tree = self.wait_ui(stage + "-results-screen", lambda value: self.by_id(value, "results") is not None)
        screen_observed = self.fixture.elapsed()
        tree = self.wait_ui(stage + "-first-card", lambda value: any(title.startswith(prefix) for title in self.titles(value)))
        first_observed = self.fixture.elapsed()
        self.wait_events(lambda records: {"srcA", "src02"}.issubset(
            {event["source"] for event in records if event["kind"] == "search" and event["completed"]}),
            "both enabled input fixture searches complete", since)
        tree = self.dump(stage + "-complete")
        titles = self.titles(tree)
        assert set(titles) == {prefix + "srcA", prefix + "src02"}, "Current input fixture results are incomplete or stale"
        records = self.fixture.snapshot(since)
        searches = [event for event in records if event["kind"] == "search"]
        assert all(event["word"] == word for event in searches), "A stale input submitted a formal search"
        assert not any(event["source"] == "src01" for event in searches), "Disabled search source was queried"
        self.metrics[stage].update({"results_screen_observed_upper_s": screen_observed - started,
                                   "first_card_observed_upper_s": first_observed - started,
                                   "first_fixture_search_request_s": min(event["request_s"] for event in searches) - started,
                                   "search_sources": sorted({event["source"] for event in searches}),
                                   "visible_card_count": len(titles)})
        self.screenshot(stage + "-complete")
        self.record(stage + ": both enabled sources return the current single-character search; no stale/disabled-source cards")
        self.key(4)
        self.wait_ui(stage + "-return-home", lambda value: self.by_description(value, "主页") is not None)
        self.record(stage + ": Back returns Home")

    def check_single_letter_submit(self, tree, points):
        self.fixture.set_stage("single-letter-submit")
        since, started = self.fixture.mark(), self.fixture.elapsed()
        self.tap_point(points["A"])
        # Submit immediately using the known stable layout bounds, before any dump.
        submit_started = self.fixture.elapsed()
        self.tap_control(tree, identifier="search")
        self.metrics["single-letter-submit"] = {"key_to_submit_command_s": submit_started - started,
                                                "submit_command_completion_s": self.fixture.elapsed() - submit_started}
        self.screenshot("single-letter-submit-immediate")
        self.observe_results("single-letter-submit", "A", started, since)

    def check_single_chinese_submit(self):
        self.connect_app_server()
        self.fixture.set_stage("single-chinese-submit")
        since, started = self.fixture.mark(), self.fixture.elapsed()
        body = urllib.parse.urlencode({"do": "search", "word": "春"}).encode("utf-8")
        request = urllib.request.Request("http://127.0.0.1:%s/action" % self.forward_port,
                                         data=body, headers={"Content-Type": "application/x-www-form-urlencoded"})
        with urllib.request.urlopen(request, timeout=8) as response:
            assert response.status == 200, "Local single-Chinese search action failed"
        self.metrics["single-chinese-submit"] = {"existing_local_action_completion_s": self.fixture.elapsed() - started,
                                                 "submission_route": "existing Home local search action; no Chinese IME typing"}
        self.observe_results("single-chinese-submit", "春", started, since)

    def write_result(self):
        result = {"serial": SERIAL, "fixture_only": True, "text_only_no_media": True, "baseline": self.baseline,
                  "checks": self.steps, "details": self.check_details, "metrics": self.metrics,
                  "original_restored_and_hash_verified": self.restore_verified, "error": self.error,
                  "not_covered": ["frame-accurate input/render timings", "real external-source speed",
                                  "deterministically delayed suggestion-provider HTTP responses",
                                  "Chinese IME/editor typing", "selected-text replacement in the editor",
                                  "phone runtime"]}
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
            self.wait_events(lambda records: any(event["kind"] == "config" and event["completed"] for event in records),
                             "input fixture config loaded")
            tree, points = self.check_typing()
            self.check_single_letter_submit(tree, points)
            self.check_single_chinese_submit()
            crash = self.command("logcat", "-b", "crash", "-d").decode("utf-8", errors="replace")
            (self.output / "crash.log").write_text(crash, encoding="utf-8")
            assert "FATAL EXCEPTION" not in crash and "Fatal signal" not in crash, "Input fixture produced a fatal crash"
            self.record("Crash buffer contains no fatal exception or signal")
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
                raise RuntimeError("Original app data restoration failed; inspect the local saved snapshot and result.json") from restore_error


def fixture_self_test():
    fixture = InputFixture()
    fixture.start()
    try:
        with urllib.request.urlopen(fixture.config_url, timeout=3) as response:
            config = json.load(response)
        assert len(config["sites"]) == 3 and config["sites"][1]["searchable"] == 0
        for word, token in QUERIES.items():
            query = urllib.parse.urlencode({"wd": word})
            with urllib.request.urlopen("http://127.0.0.1:%d/api/srcA?%s" % (fixture.port, query), timeout=3) as response:
                body = json.load(response)
            item = body["list"][0]
            assert item["vod_name"] == "Input-" + token + " srcA"
            assert not item["vod_pic"] and not item["vod_play_url"]
            code, details, event, pause = fixture.response("/api/srcA", {"ids": [item["vod_id"]]})
            assert code == 200 and details["list"][0]["vod_name"] == item["vod_name"]
        assert {event["word"] for event in fixture.snapshot() if event["kind"] == "search"} == set(QUERIES)
        print("PASS: isolated A/AB/ABC/single-Chinese routes and consistent text-only details; no adb used", flush=True)
    finally:
        fixture.close()


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=pathlib.Path, default=pathlib.Path(__file__).resolve().parents[1]
                        / ".codex/session/qa/search-input")
    parser.add_argument("--port", type=int, default=0)
    parser.add_argument("--wait-seconds", type=float, default=45)
    parser.add_argument("--baseline", action="store_true", help="Observe the old installed app and record suggestion prompts without failing them")
    parser.add_argument("--fixture-self-test", action="store_true", help="Host-only fixture validation; never access adb")
    args = parser.parse_args()
    if args.fixture_self_test:
        fixture_self_test()
        return
    if not 30 <= args.wait_seconds <= 60:
        parser.error("--wait-seconds must be 30..60")
    fixture = InputFixture(args.port)
    try:
        InputVerification(args.output.resolve(), fixture, args.wait_seconds, baseline=args.baseline).run()
    finally:
        if not fixture.thread.is_alive():
            fixture.server.server_close()


if __name__ == "__main__":
    main()
