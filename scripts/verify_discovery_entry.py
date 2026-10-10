"""Verify deliberate two-click discovery filters on the authorized LDPlayer.

Seven category checks depend only on UI state, not external provider availability.
Movie/TV data and scrolling use text-only localhost sources. Every original DB
and preference file is restored with the existing SHA-256 verification wrapper.
Only the root agent should run device mode; --fixture-self-test never accesses adb.
"""
from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys
import time
import urllib.parse
import urllib.request

from verify_all_source_discovery import (
    ORDINARY_CHIP, SOURCES, AllSourceVerification, Fixture as AllSourceFixture,
)
from verify_discovery import PACKAGE, SERIAL, TABS


PAGE_SIZE = 20


class EntryFixture(AllSourceFixture):
    """Three ordinary sources with enough rows to require viewport pagination."""

    def arm_ordinary_delay(self, tab, seconds):
        category = "movie-alpha" if tab == 4 else "tv-alpha"
        with self.lock:
            self.delay = ("srcSlow", category, "normal-only", 1, seconds)

    def response(self, path, query):
        status, body, event, pause = super().response(path, query)
        if (status == 200 and event["kind"] == "page"
                and event["category"] in ("movie-alpha", "tv-alpha")
                and event["facets"].get("k_gate") == "normal-only"):
            kind = "movie" if event["category"] == "movie-alpha" else "tv"
            marker = "M" if kind == "movie" else "T"
            page, source = event["page"], event["source"]
            cards = []
            for index in range(PAGE_SIZE):
                card = self.vod(source, kind, page)
                card["vod_id"] = f"entry-{kind}-{page}-{index:02d}"
                card["vod_name"] = f"Entry {marker} {source} P{page} {index:02d}"
                cards.append(card)
            body = {"page": page, "pagecount": 3, "limit": PAGE_SIZE,
                    "total": PAGE_SIZE * 3, "list": cards}
        return status, body, event, pause


class EntryVerification(AllSourceVerification):
    def __init__(self, output, fixture, wait_seconds, slow_seconds):
        assert SERIAL == "127.0.0.1:5555", "Only the authorized emulator is allowed"
        super().__init__(output, fixture, wait_seconds)
        self.slow_seconds = slow_seconds
        self.observations = []
        self.final_home = False

    def assert_removed_controls(self, tree):
        assert self.by_id(tree, "loadProgress") is None, "Removed discovery loading spinner is visible"
        assert self.by_id(tree, "toTop") is None, "Removed return-to-top control is visible"
        labels = {node.get("text", "") for node in tree.iter("node")}
        assert not labels.intersection({"↑  回到顶部", "回到顶部", "返回顶部"}), "Return-to-top label is visible"

    def dump(self, label="ui"):
        for attempt in range(4):
            try:
                tree = super().dump(label)
                break
            except AssertionError as error:
                if str(error) != "Fresh UI hierarchy not available" or attempt == 3:
                    raise
                time.sleep(.3)
        self.assert_removed_controls(tree)
        self.assert_no_success_count(tree)
        self.observations.append({"label": label, "panel_open": self.by_id(tree, "filterPanel") is not None,
                                  "spinner_visible": False, "to_top_visible": False})
        return tree

    def assert_active(self, tree, tab):
        active = self.by_description(tree, TABS[tab])
        assert active is not None and active.get("selected") == "true", "Category did not activate: " + TABS[tab]
        if tab:
            assert self.by_id(tree, "actions") is None, "Home shortcuts remain visible on a category"

    def check_seven_entries(self):
        for tab in range(1, 8):
            self.tap(description=TABS[0])
            tree = self.tap(description=TABS[tab])
            self.assert_active(tree, tab)
            self.check_panel(tree, False)
            self.record(TABS[tab] + ": first click selects content with filters closed")

            tree = self.tap(description=TABS[tab])
            self.assert_active(tree, tab)
            self.check_panel(tree, True)
            if tab in (4, 5):
                ordinary = self.by_description(tree, ORDINARY_CHIP)
                assert ordinary is not None and ordinary.get("selected") == "true", "18+ unexpectedly enabled on category entry"
            self.record(TABS[tab] + ": second click opens filters")

            tree = self.tap(description=TABS[tab])
            self.check_panel(tree, False)
            self.assert_active(tree, tab)
            self.record(TABS[tab] + ": third click closes filters without changing category")

            self.tap(description=TABS[tab])
            self.key(4)
            tree = self.dump("tab-%d-back-closes" % tab)
            self.check_panel(tree, False)
            self.assert_active(tree, tab)
            self.record(TABS[tab] + ": Back closes filters and keeps category active")

            self.tap(description=TABS[tab])
            next_tab = tab % 7 + 1
            tree = self.tap(description=TABS[next_tab])
            self.check_panel(tree, False)
            self.assert_active(tree, next_tab)
            self.record(TABS[tab] + " → " + TABS[next_tab] + ": switching closes the previous filters")
        self.screenshot("seven-entry-last-switch")
        self.tap(description=TABS[0])

    @staticmethod
    def page_events(events, category, page=None):
        return [event for event in events if event["kind"] == "page"
                and event["category"] == category
                and (page is None or event["page"] == page)]

    @staticmethod
    def count(tree):
        recycler = AllSourceVerification.by_id(tree, "recycler")
        return recycler.get("content-desc", "") if recycler is not None else ""

    @classmethod
    def count_at_least(cls, tree, minimum):
        match = re.fullmatch(r"片单，共(\d+)部", cls.count(tree))
        return match is not None and int(match.group(1)) >= minimum

    def scroll_content(self, tree):
        recycler = self.by_id(tree, "recycler")
        assert recycler is not None, "Discovery content recycler is missing"
        left, top, right, bottom = self.bounds(recycler)
        x, height = (left + right) // 2, bottom - top
        self.shell("input", "swipe", str(x), str(top + height * 4 // 5),
                   str(x), str(top + height // 5), "220")
        time.sleep(.25)

    def check_source_loading_and_paging(self, tab):
        marker = "M" if tab == 4 else "T"
        category = "movie-alpha" if tab == 4 else "tv-alpha"
        self.tap(description=TABS[0])
        stage = self.fixture.set_stage("entry-%s-delayed-page1" % marker)
        since = self.fixture.mark()
        self.fixture.arm_ordinary_delay(tab, self.slow_seconds)
        tree = self.tap(description=TABS[tab])
        self.assert_active(tree, tab)
        self.check_panel(tree, False)

        self.wait_events(lambda events: any(event["delayed"] and not event["completed"]
                                            for event in self.page_events(events, category, 1)),
                         marker + " delayed source really started", since)
        tree = self.dump("%s-spinner-hidden-while-request-pending" % marker)
        events = self.fixture.snapshot(since, stage)
        delayed = [event for event in self.page_events(events, category, 1) if event["delayed"]]
        assert delayed and not delayed[0]["completed"], "Slow request finished before pending UI check; increase slow-seconds"
        self.assert_removed_controls(tree)
        self.check_panel(tree, False)
        self.screenshot("%s-pending-no-spinner" % marker)
        self.record(TABS[tab] + ": real delayed source request remains pending with no loading spinner")

        self.wait_events(lambda events: all(any(event["source"] == source and event["completed"]
                                                and event["status"] == 200
                                                for event in self.page_events(events, category, 1))
                                           for source in SOURCES), marker + " three page-1 responses", since)
        expected_count = PAGE_SIZE * len(SOURCES)
        tree = self.wait_ui("%s-page1-rendered" % marker,
                            lambda current: self.count(current) == f"片单，共{expected_count}部"
                            and any(title.startswith("Entry " + marker) for title in self.titles(current)),
                            ordinary=True)
        events = self.fixture.snapshot(since, stage)
        assert not self.page_events(events, category, 2), "Fixture viewport was too short to isolate scroll-triggered pagination"
        self.check_details[marker + "_page1_sources"] = sorted({event["source"] for event in self.page_events(events, category, 1)})
        self.record(TABS[tab] + ": all three source page-1 responses render, including searchable=0")

        paging_stage = self.fixture.set_stage("entry-%s-scroll-page2" % marker)
        scroll_since = self.fixture.mark()
        scrolls = 0
        deadline = time.monotonic() + self.wait_seconds
        while not self.page_events(self.fixture.snapshot(scroll_since), category, 2):
            if time.monotonic() >= deadline or scrolls >= 18:
                raise AssertionError("Scrolling did not request page 2: " + TABS[tab])
            self.scroll_content(tree)
            scrolls += 1
            tree = self.dump("%s-scrolled-%02d" % (marker, scrolls))
            self.assert_active(tree, tab)
            self.check_panel(tree, False)
        self.wait_events(lambda events: all(any(event["source"] == source and event["completed"]
                                                and event["status"] == 200
                                                for event in self.page_events(events, category, 2))
                                           for source in SOURCES), marker + " independent page-2 responses", scroll_since)
        tree = self.wait_ui("%s-page2-appended" % marker,
                            lambda current: self.count_at_least(current, expected_count * 2), ordinary=True)
        # Bring actual page-2 cards into view, proving that responses reached the list.
        for attempt in range(15):
            if any(" P2 " in title for title in self.titles(tree)):
                break
            self.scroll_content(tree)
            tree = self.dump("%s-page2-visible-%02d" % (marker, attempt))
        assert any(" P2 " in title for title in self.titles(tree)), "Appended page-2 cards never became visible"
        self.assert_removed_controls(tree)
        self.screenshot("%s-page2-scrolled-no-top-button" % marker)
        page2 = self.page_events(self.fixture.snapshot(scroll_since, paging_stage), category, 2)
        self.check_details[marker + "_paging"] = {"scrolls_until_request": scrolls,
                                                "page2_sources": sorted({event["source"] for event in page2}),
                                                "page2_cards_visible": True}
        self.record(TABS[tab] + ": scrolling requests and appends all-source page 2; no return-to-top button")

    def write_result(self):
        result = {"serial": SERIAL, "fixture_only_data_checks": True,
                  "checks": self.steps, "details": self.check_details,
                  "observations": self.observations,
                  "original_restored_and_hash_verified": self.restore_verified,
                  "final_home": self.final_home, "error": self.error,
                  "not_covered": ["real external hot/rank provider availability",
                                  "frame timing, physical phone runtime and playback"]}
        (self.output / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        (self.output / "fixture-requests.json").write_text(json.dumps(self.fixture.snapshot(), ensure_ascii=False, indent=2), encoding="utf-8")

    def run(self):
        restore_error = None
        try:
            self.snapshot_app_data()
            self.fixture.start()
            self.command("reverse", f"tcp:{self.fixture.port}", f"tcp:{self.fixture.port}")
            self.reverse_installed = True
            self.install_fixture()
            self.command("logcat", "-b", "crash", "-c")
            self.launch()
            self.wait_events(lambda events: any(event["kind"] == "config" and event["completed"]
                                                for event in events), "fixture config loaded")
            self.check_seven_entries()
            self.check_source_loading_and_paging(4)
            self.check_source_loading_and_paging(5)
            crash = self.command("logcat", "-b", "crash", "-d").decode("utf-8", errors="replace")
            (self.output / "crash.log").write_text(crash, encoding="utf-8")
            assert "FATAL EXCEPTION" not in crash and "Fatal signal" not in crash, "Crash buffer contains a fatal crash"
            self.record("Crash buffer contains no fatal exception or fatal signal")
        except BaseException as error:
            self.error = str(error) if isinstance(error, AssertionError) else type(error).__name__
            raise
        finally:
            try:
                if self.original is not None and not (self.output / "crash.log").exists():
                    try:
                        crash = self.command("logcat", "-b", "crash", "-d").decode("utf-8", errors="replace")
                        (self.output / "crash.log").write_text(crash, encoding="utf-8")
                    except Exception:
                        self.check_details["crash_capture_on_failure"] = "unavailable"
                self.restore_app_data()
            except BaseException as error:
                restore_error = error
                self.check_details["restoration_error"] = type(error).__name__
            try:
                if self.reverse_installed:
                    self.command("reverse", "--remove", f"tcp:{self.fixture.port}", check=False)
                self.cleanup_remote()
            finally:
                if self.fixture.thread.is_alive():
                    self.fixture.close()
            try:
                if self.restore_verified:
                    self.launch()
                    tree = self.tap(description=TABS[0])
                    self.assert_active(tree, 0)
                    self.check_panel(tree, False)
                    self.assert_removed_controls(tree)
                    self.screenshot("restored-home")
                    self.final_home = True
                    self.record("Restored original configuration left on Home with filters closed")
            finally:
                self.write_result()
            if restore_error is not None:
                raise restore_error


def fixture_self_test():
    """Exercise real localhost HTTP routes without constructing verified_adb."""
    fixture = EntryFixture(0)
    fixture.start()
    try:
        def get(path, query=None):
            url = f"http://127.0.0.1:{fixture.port}" + path
            if query:
                url += "?" + urllib.parse.urlencode(query)
            with urllib.request.urlopen(url, timeout=3) as response:
                return json.load(response)

        config = get("/config.json")
        assert len(config["sites"]) == 3 and config["sites"][1]["searchable"] == 0
        metadata = get("/api/srcA")
        assert "movie-alpha" in metadata["filters"] and "tv-alpha" in metadata["filters"]
        for category, marker in (("movie-alpha", "M"), ("tv-alpha", "T")):
            fixture.set_stage("self-test-" + marker)
            fixture.arm_ordinary_delay(4 if marker == "M" else 5, 0)
            page1 = get("/api/srcSlow", {"t": category, "pg": 1,
                        "f": json.dumps({"k_gate": "normal-only", "k_genre": "g-any"})})
            page2 = get("/api/srcB", {"t": category, "pg": 2,
                        "f": json.dumps({"k_gate": "normal-only", "k_genre": "g-any"})})
            assert len(page1["list"]) == PAGE_SIZE and page1["pagecount"] == 3
            assert len(page2["list"]) == PAGE_SIZE and all(" P2 " in row["vod_name"] for row in page2["list"])
            assert not ({row["vod_id"] for row in page1["list"]} & {row["vod_id"] for row in page2["list"]})
            assert all(not row["vod_pic"] and not row["vod_play_url"] and row["vod_class"] == "喜剧"
                       for row in page1["list"] + page2["list"])
            events = fixture.snapshot(stage="self-test-" + marker)
            assert any(event["delayed"] and event["source"] == "srcSlow" for event in events)
        print("PASS: host-only discovery-entry fixture routes, ordinary labels, source identities and pagination; no adb", flush=True)
    finally:
        fixture.close()


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=pathlib.Path,
                        default=pathlib.Path(__file__).resolve().parents[1] / ".codex/session/qa/discovery-entry")
    parser.add_argument("--fixture-self-test", action="store_true")
    parser.add_argument("--port", type=int, default=0)
    parser.add_argument("--slow-seconds", type=float, default=12)
    parser.add_argument("--wait-seconds", type=float, default=40)
    args = parser.parse_args()
    if args.fixture_self_test:
        fixture_self_test()
        return
    if not 10 <= args.slow_seconds <= 16 or not 25 <= args.wait_seconds <= 60:
        parser.error("Require slow-seconds 10..16 and wait-seconds 25..60")
    fixture = EntryFixture(args.port)
    try:
        EntryVerification(args.output.resolve(), fixture, args.wait_seconds, args.slow_seconds).run()
    finally:
        if not fixture.thread.is_alive():
            fixture.server.server_close()


if __name__ == "__main__":
    main()
