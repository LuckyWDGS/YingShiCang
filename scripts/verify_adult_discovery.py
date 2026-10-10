"""Isolated 18+ discovery regression on the authorized LDPlayer.

All cards are inert text labels: no pictures, playback addresses or production
content. Device mode is for the root operator only. --fixture-self-test exercises
the real localhost routes without constructing an adb helper or touching a device.
Original database/preferences are restored and hash-verified by the established
all-source wrapper, even if a check fails.
"""
from __future__ import annotations

import argparse
import collections
import json
import pathlib
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

from verify_all_source_discovery import (
    ADULT_CHIP, DISCOVERY_PREF, ORDINARY_CHIP, SOURCES,
    AllSourceVerification, Fixture,
)
from verify_discovery import PACKAGE, SERIAL, TABS
from verify_discovery_entry import EntryVerification


PAGE_SIZE = 20
PROFILES = ("mixed", "fallback", "nomarker", "unsupported", "failure", "rawmetadata", "rawchoice", "emptyfailure")
SECTIONS = ("full", "remaining", "rawmetadata", "emptyretry", "restartonly")
EARLIER_COVERAGE = ["movie/TV entry click and Back controls", "mixed all-source adult pagination and ordinary/Home isolation",
                    "same-id adult detail source origin", "three-round metadata-only scan and page-4 continue",
                    "normal no-adult-metadata empty movie/TV state"]
CATEGORIES = {
    "movie-alpha": ("电影", "movie", False),
    "tv-alpha": ("电视剧", "tv", False),
    "movie-r": ("18+ 成人电影", "movie", True),
    "tv-r": ("18+ 成人剧集", "tv", True),
    "generic-r": ("18+ 成人向", "generic", True),
    "music-only": ("音乐", "music", False),
    "movie-rawflag": ("电影", "movie", True),
    "tv-rawflag": ("电视剧", "tv", True),
    "generic-rawflag": ("全部片单", "generic", True),
}


class AdultFixture(Fixture):
    """Three sources with deliberately different real metadata declarations."""

    def __init__(self, port):
        super().__init__(port)
        self.profile = "mixed"
        self.adult_failure_armed = False
        self.adult_delay = None

    @property
    def config_url(self):
        return f"http://127.0.0.1:{self.port}/config-{self.profile}.json"

    def select_profile(self, profile):
        assert profile in PROFILES
        with self.lock:
            self.profile = profile
            self.adult_failure_armed = profile in ("failure", "emptyfailure")
            self.adult_delay = None
        return self.set_stage(profile + "-setup")

    def arm_adult_delay(self, seconds):
        with self.lock:
            self.adult_delay = seconds

    @staticmethod
    def source_categories(profile, source):
        if profile == "unsupported":
            return ("music-only",)
        ordinary = ("movie-alpha", "tv-alpha")
        if profile == "rawmetadata":
            return ordinary + (("generic-rawflag",) if source == "srcSlow" else ("movie-rawflag", "tv-rawflag"))
        if profile in ("mixed", "failure") and source == "srcSlow":
            return ordinary + ("movie-r", "tv-r", "generic-r")
        return ordinary

    @staticmethod
    def source_filters(profile, source, category):
        if profile == "rawchoice":
            opaque = {"srcA": "opaque-a", "srcB": "opaque-b", "srcSlow": "opaque-c"}[source]
            flag = {"age_rating": 18} if source == "srcB" else {"is_adult": True}
            return [{"key": "k_gate", "name": "内容", "init": opaque,
                     "value": [{"n": "全部", "v": "normal-only"}, {"n": "限定", "v": opaque, **flag}]}]
        if profile in ("mixed", "failure", "emptyfailure") and source == "srcA":
            return [{"key": "k_gate", "name": "内容", "init": "normal-only",
                     "value": [{"n": "全部", "v": "normal-only"},
                               {"n": "18+", "v": "adult-only"}]}]
        return []

    @staticmethod
    def source_class(profile, source, category):
        row = {"type_id": category, "type_name": CATEGORIES[category][0]}
        if profile == "rawmetadata" and category.endswith("rawflag"):
            row.update({"age_rating": 18} if source == "srcB" else {"is_adult": True})
        if profile == "rawchoice" and source == "srcSlow":
            row["filters"] = AdultFixture.source_filters(profile, source, category)
        return row

    @staticmethod
    def card(source, kind, page, index, *, adult=False, explicit=False):
        marker = "M" if kind == "movie" else "T"
        # Identity is shared across sources; actual origin must still be retained.
        card = {"vod_id": f"scope-{kind}-{page}-{index:02d}",
                "vod_name": f"{'AdultLabel' if adult else 'NormalLabel'} {marker} {source} P{page} {index:02d}",
                "vod_pic": "", "type_name": "电影" if kind == "movie" else "电视剧",
                "vod_class": "", "vod_tag": "", "vod_year": "2026",
                "vod_area": "中国大陆", "vod_remarks": "Inert QA label",
                "vod_play_from": "", "vod_play_url": "",
                "vod_content": "Isolated QA text label only."}
        if adult and not explicit:
            # Alternate actual tag and age/rating fields. A title is not evidence.
            if index % 3 == 0:
                card["vod_tag"] = "18+"
            elif index % 3 == 1:
                card["vod_age"] = "18"
            else:
                card["vod_rating"] = "NC-17"
        return card

    def response(self, path, query):
        profiles_pattern = "|".join(PROFILES)
        config_match = re.fullmatch(r"/config-(" + profiles_pattern + r")\.json", path)
        api_match = re.fullmatch(r"/api/(" + profiles_pattern + r")/(srcA|srcB|srcSlow)", path)
        profile = config_match[1] if config_match else api_match[1] if api_match else "INVALID"
        source = api_match[2] if api_match else "fixture"
        raw_category = query.get("t", [""])[0]
        category = raw_category if raw_category in CATEGORIES else "" if not raw_category else "INVALID"
        raw_ids = query.get("ids", [""])[0]
        ids = raw_ids if re.fullmatch(r"scope-(movie|tv)-[1-4]-\d{2}", raw_ids) else "" if not raw_ids else "INVALID"
        raw_action = query.get("ac", [""])[0]
        action = raw_action if raw_action in ("", "detail", "videolist") else "INVALID"
        try:
            page = int(query.get("pg", ["1"])[0])
            raw_facets = json.loads(query.get("f", ["{}"]) [0])
        except (ValueError, TypeError):
            page, raw_facets = -1, {}
        facets = {key: value if key == "k_gate" and value in ("normal-only", "adult-only", "opaque-a", "opaque-b", "opaque-c") else "INVALID"
                  for key, value in raw_facets.items()} if isinstance(raw_facets, dict) else {"INVALID": "INVALID"}
        kind = "config" if config_match else "detail" if ids else "page" if category else "metadata"
        event = {"profile": profile, "source": source, "kind": kind, "category": category,
                 "page": page, "ids": ids, "ac": action, "facets": facets,
                 "status": 200, "delayed": False, "completed": False, "response": "pending"}
        pause = 0
        with self.lock:
            event["index"], event["stage"] = len(self.events), self.stage
            self.events.append(event)
            if kind == "page" and source == "srcA" and facets.get("k_gate") == "adult-only" and page == 1:
                if profile in ("failure", "emptyfailure") and self.adult_failure_armed:
                    self.adult_failure_armed = False
                    event["status"] = 503
                if self.adult_delay is not None:
                    pause, self.adult_delay = self.adult_delay, None
                    event["delayed"] = True
        if config_match:
            body = {"name": "QA Adult " + profile, "sites": [
                {"key": key, "name": key, "type": 1,
                 "api": f"http://127.0.0.1:{self.port}/api/{profile}/{key}",
                 "searchable": 0 if key == "srcB" else 1,
                 "quickSearch": 0, "timeout": 20}
                for key in SOURCES], "parses": [], "flags": [], "ads": []}
        elif not api_match:
            event["status"], body = 404, {"error": "Unknown fixture route"}
        elif event["status"] == 503:
            body = {"error": "One-shot genuine HTTP failure"}
        elif kind == "metadata":
            categories = self.source_categories(profile, source)
            body = {"class": [self.source_class(profile, source, key) for key in categories],
                    "filters": ({} if profile == "rawchoice" and source == "srcSlow" else
                                {key: self.source_filters(profile, source, key) for key in categories}), "list": []}
        elif kind == "detail" and ids != "INVALID":
            body = {"list": [{"vod_id": ids, "vod_name": "QA source detail " + source,
                              "vod_pic": "", "vod_play_from": "", "vod_play_url": "",
                              "vod_content": "Empty playable detail."}]}
        elif category not in self.source_categories(profile, source) or page not in ((1, 2, 3, 4) if profile in ("fallback", "emptyfailure") else (1, 2)):
            event["status"], body = 400, {"error": "Undeclared category or page"}
        else:
            declared = self.source_filters(profile, source, category)
            allowed_gate = {choice["v"] for facet in declared for choice in facet["value"]}
            valid_facets = (facets.get("k_gate") in allowed_gate and len(facets) == 1
                            if declared else not facets)
            if action != "detail" or not valid_facets:
                event["status"], body = 400, {"error": "Undeclared facet or incorrect API action"}
            elif category in ("generic-r", "generic-rawflag"):
                unknown = self.card(source, "tv", 1, 97, adult=True, explicit=True)
                unknown["type_name"] = ""
                unknown["vod_name"] = "UnknownKind AdultLabel srcSlow"
                body = {"page": page, "pagecount": 1, "list": [unknown,
                        self.card(source, "movie", 1, 98, adult=True, explicit=True),
                        self.card(source, "tv", 1, 99, adult=True, explicit=True)]}
            elif category == "music-only":
                body = {"page": page, "pagecount": 1, "list": []}
            else:
                _, vod_kind, adult_category = CATEGORIES[category]
                explicit = adult_category or facets.get("k_gate") in ("adult-only", "opaque-a", "opaque-b", "opaque-c")
                cards = []
                for index in range(PAGE_SIZE):
                    adult = explicit or (profile == "fallback" and page == 4 and index % 2 == 0)
                    adult = adult or (profile in ("mixed", "failure") and source == "srcB"
                                      and page == 2 and index % 2 == 0)
                    cards.append(self.card(source, vod_kind, page, index, adult=adult, explicit=explicit))
                pagecount = 4 if profile in ("fallback", "emptyfailure") else 1 if category.endswith("rawflag") else 2
                body = {"page": page, "pagecount": pagecount, "limit": PAGE_SIZE,
                        "total": PAGE_SIZE * pagecount, "list": cards}
        return event["status"], body, event, pause


class AdultVerification(EntryVerification):
    def __init__(self, output, fixture, wait_seconds, slow_seconds, section="full"):
        super().__init__(output, fixture, wait_seconds, slow_seconds)
        self.section = section
        self.dump_retries = []

    @staticmethod
    def tool_dump_error(error):
        return (isinstance(error, subprocess.CalledProcessError) and error.returncode == 137
                and isinstance(error.cmd, (list, tuple))
                and list(error.cmd[-4:]) == ["shell", "uiautomator", "dump", "/sdcard/ysc-discovery-qa.xml"])

    @staticmethod
    def dump_npe_block(block):
        if PACKAGE.encode("utf-8") in block:
            return False
        return (b"java.lang.NullPointerException" in block
                and b"AccessibilityNodeInfo.getContentDescription()' on a null object reference" in block
                and b"com.android.uiautomator.core.AccessibilityNodeInfoDumper.childNafCheck" in block
                and b"com.android.commands.uiautomator.DumpCommand.run" in block
                and b"com.android.commands.uiautomator.Launcher.main" in block)

    @staticmethod
    def fatal_blocks(raw):
        return re.split(rb"(?m)^.*FATAL EXCEPTION:[^\r\n]*(?:\r?\n)?", raw)[1:]

    @classmethod
    def fresh_dump_npe(cls, before, after):
        """Recognize only new crash-buffer records from the dump tool itself."""
        if not after.startswith(before):
            return False
        new = after[len(before):]
        blocks = cls.fatal_blocks(new)
        return b"Fatal signal" not in new and len(blocks) == 1 and cls.dump_npe_block(blocks[0])

    def dump(self, label="ui"):
        for attempt in range(4):
            # A baseline prevents an earlier tool crash masking another exit 137.
            # Preserve the crash buffer; the final crash check still examines it.
            before = self.command("logcat", "-b", "crash", "-d")
            try:
                return super().dump(label)
            except subprocess.CalledProcessError as error:
                if not self.tool_dump_error(error) or attempt == 3:
                    raise
                after = self.command("logcat", "-b", "crash", "-d")
                if not self.fresh_dump_npe(before, after):
                    raise
                self.dump_retries.append({"label": label, "retry": attempt + 1,
                                          "exit_code": 137, "exact_tool_npe_verified": True})
                time.sleep(.3)

    def assert_removed_controls(self, tree):
        super().assert_removed_controls(tree)
        labels = {node.get("text", "") for node in tree.iter("node")}
        assert not labels.intersection({"重置", "收起"}), "Removed filter header button remains visible"

    def assert_ordinary(self, tree):
        self.assert_no_success_count(tree)
        assert not any(title.startswith("AdultLabel") for title in self.titles(tree)), "18+ result escaped ordinary/Home scope"

    @staticmethod
    def state_text(tree):
        status = AllSourceVerification.by_id(tree, "discoveryStatus")
        return status.get("text", "") if status is not None else ""

    def select_profile(self, profile):
        self.shell("am", "force-stop", PACKAGE)
        self.fixture.select_profile(profile)
        self.install_fixture()
        self.launch()
        self.wait_events(lambda events: any(event["kind"] == "config" and event["profile"] == profile
                                            and event["completed"] for event in events), profile + " configuration loaded")

    def enter_plain(self, tab):
        self.tap(description=TABS[0])
        tree = self.tap(description=TABS[tab])
        self.assert_active(tree, tab)
        self.check_panel(tree, False)
        tree = self.tap(description=TABS[tab])
        self.check_panel(tree, True)
        ordinary = self.by_description(tree, ORDINARY_CHIP)
        assert ordinary is not None and ordinary.get("selected") == "true", "18+ restored on category entry"
        return tree

    def ordinary_ready(self, tab, label):
        marker = "M" if tab == 4 else "T"
        return self.wait_ui(label, lambda tree: self.count_at_least(tree, PAGE_SIZE * len(SOURCES))
                            and any(title.startswith("NormalLabel " + marker) for title in self.titles(tree)), ordinary=True)

    def adult_visible(self, tab, label, minimum=PAGE_SIZE * 2):
        marker = "M" if tab == 4 else "T"
        tree = self.wait_ui(label, lambda current: self.count_at_least(current, minimum)
                            and any(title.startswith("AdultLabel " + marker) for title in self.titles(current)))
        assert self.titles(tree) and all(title.startswith("AdultLabel " + marker) for title in self.titles(tree)), "Ordinary or wrong-kind cards remained in 18+"
        return tree

    def ensure_panel(self, tab, visible):
        tree = self.dump("ensure-panel")
        if (self.by_id(tree, "filterPanel") is not None) != visible:
            tree = self.tap(description=TABS[tab])
        self.check_panel(tree, visible)
        return tree

    def check_filter_entries(self):
        for tab in (4, 5):
            self.enter_plain(tab)
            self.record(TABS[tab] + ": first click selects content only; second click opens filters with no 重置/收起")
            tree = self.tap(description=TABS[tab])
            self.check_panel(tree, False)
            self.assert_active(tree, tab)
            self.record(TABS[tab] + ": repeating current category closes filters without header buttons")
            self.tap(description=TABS[tab])
            self.key(4)
            tree = self.dump("adult-entry-back-" + str(tab))
            self.check_panel(tree, False)
            self.assert_active(tree, tab)
            self.record(TABS[tab] + ": Back closes filters and keeps current category")
        self.tap(description=TABS[0])

    def adult_events(self, events, tab):
        ordinary = "movie-alpha" if tab == 4 else "tv-alpha"
        scoped = "movie-r" if tab == 4 else "tv-r"
        return [event for event in events if event["kind"] == "page"
                and event["category"] in (ordinary, scoped, "generic-r")]

    def check_mixed_scope_and_paging(self, tab):
        marker = "M" if tab == 4 else "T"
        ordinary = "movie-alpha" if tab == 4 else "tv-alpha"
        scoped = "movie-r" if tab == 4 else "tv-r"
        self.enter_plain(tab)
        self.ordinary_ready(tab, marker + "-ordinary")
        stage = self.fixture.set_stage("mixed-" + marker + "-adult")
        start = self.fixture.mark()
        self.tap(description=ADULT_CHIP)
        self.wait_events(lambda events: all(any(event["source"] == source and event["kind"] == "page"
                                                and event["completed"] for event in events)
                                           for source in SOURCES), marker + " three-source adult first pass", start)
        self.adult_visible(tab, marker + "-mixed-adult-first")
        events = self.fixture.snapshot(start, stage)
        assert any(event["source"] == "srcA" and event["category"] == ordinary
                   and event["facets"] == {"k_gate": "adult-only"} for event in events), "Explicit adult filter was not requested"
        assert any(event["source"] == "srcB" and event["category"] == ordinary and not event["facets"]
                   for event in events), "Metadata-only searchable=0 source was omitted"
        assert any(event["source"] == "srcSlow" and event["category"] == scoped for event in events), "Explicit adult category was not requested"
        assert not any(event["source"] == "srcSlow" and event["category"] == ordinary for event in events), "Explicit category source fell back to ordinary categories"
        assert "失败" not in self.state_text(self.dump(marker + "-successful-adult-state")), "Successful mixed adult mode reports failure"
        self.screenshot(marker + "-mixed-adult-labels")
        self.record(TABS[tab] + ": filter/category/item metadata sources participate; searchable=0 retained; successful adult state has no failure")

        self.ensure_panel(tab, False)
        tree = self.dump(marker + "-adult-paging-before")
        deadline = time.monotonic() + self.wait_seconds
        scrolls = 0
        while True:
            events = self.fixture.snapshot(start, stage)
            complete = all(any(event["source"] == source and event["kind"] == "page"
                               and event["page"] == 2 and event["completed"] for event in events)
                           for source in SOURCES)
            if complete and self.count_at_least(tree, 91):
                break
            assert time.monotonic() < deadline and scrolls < 24, "Adult second pages did not append after real scrolling"
            self.scroll_content(tree)
            scrolls += 1
            tree = self.dump(marker + "-adult-scroll-%02d" % scrolls)
        # Explicit sources yield 40 each, plus one correctly typed generic row;
        # the mixed item-only source yields only ten.
        assert self.count(tree) == "片单，共91部", "Adult metadata fallback accepted ordinary/wrong-kind rows or lost source identity"
        assert all(title.startswith("AdultLabel " + marker) for title in self.titles(tree)), "Adult paging leaked ordinary rows"
        assert any(event["category"] == "generic-r" for event in events), "Generic adult category was never examined for explicit item kind"
        assert not any(title.startswith("UnknownKind") for title in self.titles(tree)), "Untyped generic adult row was accepted"
        self.check_details[marker + "_adult_paging"] = {"sources": list(SOURCES), "count": 91, "scrolls": scrolls}
        self.record(TABS[tab] + ": independent source page 2 appends; item-only adult fields retain ten marked rows and exclude ten ordinary rows")

        self.ensure_panel(tab, True)
        self.tap(description=ORDINARY_CHIP)
        self.ordinary_ready(tab, marker + "-switch-back-ordinary")
        self.record(TABS[tab] + ": selecting 全部 immediately restores ordinary mode without 18+ cards")

        self.tap(description=ADULT_CHIP)
        self.adult_visible(tab, marker + "-before-home")
        tree = self.tap(description=TABS[0])
        self.assert_ordinary(tree)
        self.check_panel(tree, False)
        self.enter_plain(tab)
        self.ordinary_ready(tab, marker + "-reentry-ordinary")
        self.record(TABS[tab] + ": Home and category re-entry revoke 18+ and show no adult fixture cards")

    def scroll_to_title(self, title):
        tree = self.dump("find-source-card")
        # Return to the beginning before seeking the exact card.
        for attempt in range(4):
            if title in self.titles(tree):
                return tree
            recycler = self.by_id(tree, "recycler")
            assert recycler is not None
            left, top, right, bottom = self.bounds(recycler)
            self.shell("input", "swipe", str((left + right) // 2), str(top + (bottom-top)//5),
                       str((left + right)//2), str(top + (bottom-top)*4//5), "180")
            tree = self.dump("seek-top-%02d" % attempt)
        for attempt in range(24):
            if title in self.titles(tree):
                return tree
            self.scroll_content(tree)
            tree = self.dump("seek-card-%02d" % attempt)
        raise AssertionError("Adult source card never became visible: " + title)

    def check_detail_origin(self):
        self.enter_plain(4)
        self.ordinary_ready(4, "origin-ordinary-ready")
        self.tap(description=ADULT_CHIP)
        self.adult_visible(4, "origin-adult-ready")
        self.ensure_panel(4, False)
        for source in ("srcA", "srcSlow"):
            title = f"AdultLabel M {source} P1 00"
            self.scroll_to_title(title)
            start = self.fixture.mark()
            self.tap_title(title)
            events = self.wait_events(lambda records: any(event["kind"] == "detail" and event["completed"] for event in records),
                                     "adult detail origin " + source, start, timeout=25)
            details = [event for event in events if event["kind"] == "detail"]
            assert details and all(event["source"] == source and event["ids"] == "scope-movie-1-00"
                                   and event["ac"] == "detail" for event in details), "Same-id adult card opened wrong source/detail id"
            time.sleep(.5)
            self.key(4)
            self.return_from_detail()
        self.record("Same-id adult labels from two sources open their own real source+vod_id detail endpoint; no media playback")

    def check_metadata_only(self):
        self.select_profile("fallback")
        for tab in (4, 5):
            marker = "M" if tab == 4 else "T"
            self.enter_plain(tab)
            self.ordinary_ready(tab, marker + "-fallback-ordinary")
            start = self.fixture.mark()
            self.fixture.set_stage("fallback-" + marker)
            self.tap(description=ADULT_CHIP)
            tree = self.wait_ui(marker + "-fallback-scan-budget", lambda current: self.by_id(current, "discoveryRetry") is not None
                                and self.by_id(current, "discoveryRetry").get("text") == "继续查找")
            assert self.count(tree) == "片单，共0部" and "失败" not in self.state_text(tree), "Empty scan budget was misreported as failure"
            before_continue = self.adult_events(self.fixture.snapshot(start), tab)
            assert all(event["page"] <= 3 for event in before_continue), "Automatic empty scanning exceeded three-round budget"
            self.tap(identifier="discoveryRetry")
            tree = self.adult_visible(tab, marker + "-fallback-fourth-page", minimum=30)
            assert self.count(tree) == "片单，共30部", "Metadata-only scan accepted unmarked rows"
            events = self.adult_events(self.fixture.snapshot(start), tab)
            for source in SOURCES:
                assert {1, 2, 3, 4}.issubset({event["page"] for event in events if event["source"] == source}), "Continue did not advance independent raw cursors to matching page 4"
            assert all(not event["facets"] for event in events), "Fallback fabricated an undeclared adult API facet"
            assert "失败" not in self.state_text(tree) and self.by_id(tree, "discoveryRetry") is None, "Successful metadata scan reports failure"
            self.screenshot(marker + "-metadata-only-adult")
            self.record(TABS[tab] + ": empty metadata scan pauses after three rounds; 继续查找 reaches actual raw page 4 tag/age/rating matches")

    def check_empty_profile(self, profile):
        self.select_profile(profile)
        for tab in (4, 5):
            self.enter_plain(tab)
            start = self.fixture.mark()
            self.fixture.set_stage(profile + "-" + str(tab))
            self.tap(description=ADULT_CHIP)
            if profile != "unsupported":
                self.wait_events(lambda events: all(any(event["source"] == source and event["completed"]
                                                        for event in events) for source in SOURCES),
                                 profile + " all sources completed", start)
            tree = self.wait_ui(profile + "-empty-" + str(tab), lambda current: self.count(current) == "片单，共0部"
                                and bool(self.state_text(current)))
            state = self.state_text(tree)
            assert "失败" not in state and self.by_id(tree, "discoveryRetry") is None, "Unsupported/no-match adult content is misreported as network failure"
            assert not self.titles(tree), "No-match fixture unexpectedly rendered cards"
            if profile == "nomarker":
                self.wait_events(lambda events: all(any(event["source"] == source and event["kind"] == "page"
                                                        and event["page"] == 2 and event["completed"] for event in events)
                                                   for source in SOURCES), "unmarked raw pages exhausted", start)
                assert "暂无" in state, "No metadata matches do not show normal empty state"
            else:
                assert not any(event["kind"] == "page" for event in self.fixture.snapshot(start)), "Unsupported category invented a movie/TV request"
                metadata = self.fixture.snapshot()
                assert all(any(event["profile"] == profile and event["source"] == source
                               and event["kind"] == "metadata" and event["completed"]
                               for event in metadata) for source in SOURCES), "Unsupported cache never obtained metadata for every source"
            self.check_details[profile + "_" + str(tab)] = {"status": state, "retry_visible": False}
            self.screenshot(profile + "-" + str(tab) + "-normal-empty")
            self.record(TABS[tab] + ": " + profile + " displays normal empty/unsupported state with no false failure or retry")

    def check_true_failure_retry(self):
        self.select_profile("failure")
        self.enter_plain(4)
        self.ordinary_ready(4, "failure-ordinary-preparation")
        start = self.fixture.mark()
        stage = self.fixture.set_stage("adult-real-http-failure")
        self.tap(description=ADULT_CHIP)
        tree = self.wait_ui("adult-real-failure-visible", lambda current: self.by_id(current, "discoveryRetry") is not None
                            and "失败" in self.state_text(current))
        assert all(title.startswith("AdultLabel") for title in self.titles(tree)), "Failed adult request retained ordinary mode cards"
        self.ensure_panel(4, False)
        tree = self.dump("failed-source-healthy-paging-before")
        deadline = time.monotonic() + self.wait_seconds
        for attempt in range(24):
            events = self.fixture.snapshot(start, stage)
            if all(any(event["source"] == source and event["kind"] == "page" and event["page"] == 2
                       and event["completed"] for event in events) for source in ("srcB", "srcSlow")):
                break
            assert time.monotonic() < deadline, "A failed adult source blocked healthy pagination"
            self.scroll_content(tree)
            tree = self.dump("failed-source-healthy-scroll-%02d" % attempt)
        else:
            raise AssertionError("Healthy sources did not reach page 2 while another source failed")
        self.wait_ui("healthy-adult-pages-retained-with-failure", lambda current: self.count_at_least(current, 51)
                     and self.by_id(current, "discoveryRetry") is not None)
        self.ensure_panel(4, True)
        self.record("A failed adult source does not block independent healthy source page-2 scrolling")
        before = self.fixture.snapshot(start, stage)
        successful = collections.Counter((event["source"], event["category"], event["page"])
                                         for event in before if event["kind"] == "page" and event["status"] == 200)
        self.screenshot("adult-real-failure-with-retry")
        self.tap(identifier="discoveryRetry")
        self.adult_visible(4, "adult-real-failure-recovered", minimum=71)
        after = self.fixture.snapshot(start, stage)
        failed = [event for event in after if event["source"] == "srcA" and event["kind"] == "page"
                  and event["category"] == "movie-alpha" and event["page"] == 1]
        assert [event["status"] for event in failed] == [503, 200], "Retry did not repeat exact failed adult page"
        for signature, count in successful.items():
            assert sum(event["kind"] == "page" and event["status"] == 200
                       and (event["source"], event["category"], event["page"]) == signature for event in after) == count, "Retry repeated a successful adult page"
        tree = self.wait_ui("adult-retry-cleared-failure", lambda current: self.by_id(current, "discoveryRetry") is None
                            and "失败" not in self.state_text(current))
        self.check_details["adult_true_failure"] = {"statuses": [event["status"] for event in failed], "successful_pages_retained": True}
        self.record("A genuine adult-page HTTP 503 remains actionable; retry repeats only that source/page and preserves successful pages")

    def check_cancel_and_restart(self):
        initial_saved = {node.get("name"): json.loads(node.text or "{}")
                         for node in ET.fromstring(self.read_file("shared_prefs/" + DISCOVERY_PREF)).findall("string")}
        inactive_legacy_seed = initial_saved.get("tab_5", {})
        self.ensure_panel(4, True)
        self.tap(description=ORDINARY_CHIP)
        self.ordinary_ready(4, "late-ordinary-preparation")
        start = self.fixture.mark()
        self.fixture.set_stage("adult-delayed-switch-to-all")
        self.fixture.arm_adult_delay(self.slow_seconds)
        self.tap(description=ADULT_CHIP)
        self.wait_events(lambda records: any(event["delayed"] for event in records), "adult delayed request started", start)
        self.tap(description=ORDINARY_CHIP)
        delayed = [event for event in self.fixture.snapshot(start) if event["delayed"]]
        assert delayed and not delayed[0]["completed"], "Delay completed before ordinary selection; increase slow-seconds"
        self.ordinary_ready(4, "late-ordinary-ready")
        deadline = time.monotonic() + self.slow_seconds + 10
        observations = 0
        while True:
            tree = self.dump("late-adult-remains-excluded")
            self.assert_ordinary(tree)
            observations += 1
            if any(event["delayed"] and event["completed"] for event in self.fixture.snapshot(start)):
                break
            assert time.monotonic() < deadline, "Delayed adult server response did not finish"
            time.sleep(.4)
        self.assert_ordinary(self.dump("late-adult-after-response"))
        self.check_details["late_adult_observations"] = observations + 1
        self.record("Selecting 全部 during an in-flight adult response cancels/rejects its late rows; no stale adult labels return")

        self.ensure_panel(4, True)
        self.tap(description=ADULT_CHIP)
        self.adult_visible(4, "restart-adult-before")
        time.sleep(.4)
        saved = {node.get("name"): json.loads(node.text or "{}")
                 for node in ET.fromstring(self.read_file("shared_prefs/" + DISCOVERY_PREF)).findall("string")}
        assert "content" not in saved.get("tab_4", {}), "Active movie 18+ selection persisted"
        assert saved.get("tab_5", {}) == inactive_legacy_seed, "Active movie filter unexpectedly modified untouched legacy TV preferences"
        self.check_details["persisted_adult_gate"] = {"active_tab4_content_key_absent": True,
                                                     "untouched_tab5_fixture_legacy_seed_unchanged": True}
        self.shell("am", "force-stop", PACKAGE)
        self.launch()
        self.enter_plain(4)
        self.ordinary_ready(4, "restart-adult-off")
        self.enter_plain(5)
        self.ordinary_ready(5, "restart-tv-legacy-adult-off")
        self.record("Active movie 18+ is absent from saved JSON; movie and untouched legacy-TV preference both remain ordinary after restart")

    def check_restart_only(self):
        self.select_profile("mixed")
        self.enter_plain(4)
        self.ordinary_ready(4, "restartonly-ordinary-preparation")
        self.tap(description=ADULT_CHIP)
        self.adult_visible(4, "restartonly-initial-adult")
        self.check_cancel_and_restart()

    def check_rawmetadata(self):
        """Class restrictions need no adult word in their name or their cards."""
        self.select_profile("rawmetadata")
        for tab in (4, 5):
            marker = "M" if tab == 4 else "T"
            ordinary = "movie-alpha" if tab == 4 else "tv-alpha"
            declared = "movie-rawflag" if tab == 4 else "tv-rawflag"
            stage = self.fixture.set_stage("rawmetadata-" + marker + "-ordinary")
            start = self.fixture.mark()
            self.enter_plain(tab)
            self.ordinary_ready(tab, marker + "-rawmetadata-ordinary")
            before = self.fixture.snapshot(start, stage)
            assert all(event["category"] == ordinary for event in before if event["kind"] == "page"), "Ordinary mode queried a raw-flag restricted category"
            self.record(TABS[tab] + ": raw is_adult/age_rating categories with ordinary names are excluded before explicit 18+ selection")

            stage = self.fixture.set_stage("rawmetadata-" + marker + "-adult")
            start = self.fixture.mark()
            self.tap(description=ADULT_CHIP)
            tree = self.adult_visible(tab, marker + "-rawmetadata-restricted-cards", minimum=41)
            assert self.count(tree) == "片单，共41部", "Raw class metadata lost restricted cards or accepted wrong/unknown media kind"
            self.wait_events(lambda events: all(any(event["source"] == source and event["kind"] == "page"
                                                    and event["completed"] for event in events)
                                               for source in SOURCES), marker + " raw metadata source pages", start)
            events = self.fixture.snapshot(start, stage)
            for source in ("srcA", "srcB"):
                assert any(event["source"] == source and event["category"] == declared
                           and not event["facets"] and event["status"] == 200 for event in events), "Actual raw-declared category was not used for " + source
            assert any(event["source"] == "srcSlow" and event["category"] == "generic-rawflag"
                       and event["status"] == 200 for event in events), "Raw generic adult category was not examined"
            assert "失败" not in self.state_text(tree) and self.by_id(tree, "discoveryRetry") is None, "Successful raw class metadata mode reports failure"
            assert not any(title.startswith("UnknownKind") for title in self.titles(tree)), "Generic raw flag inferred unknown media kind"
            self.check_details["rawmetadata_" + marker] = {"count": 41, "sources": list(SOURCES),
                                                        "class_flag_source": "srcA", "class_numeric_age_source": "srcB",
                                                        "generic_flag_source": "srcSlow", "unknown_kind_excluded": True}
            self.screenshot(marker + "-raw-class-metadata")
            self.record(TABS[tab] + ": ordinary-name classes with raw is_adult:true and numeric age_rating:18 admit inert untagged cards; generic flag retains only matching explicit media kind")

            self.ensure_panel(tab, False)
            for source in ("srcA", "srcB"):
                title = f"AdultLabel {marker} {source} P1 00"
                self.scroll_to_title(title)
                detail_start = self.fixture.mark()
                self.tap_title(title)
                events = self.wait_events(lambda records: any(event["kind"] == "detail" and event["completed"] for event in records),
                                         "raw metadata origin " + marker + " " + source, detail_start, timeout=25)
                details = [event for event in events if event["kind"] == "detail"]
                expected_id = "scope-" + ("movie" if tab == 4 else "tv") + "-1-00"
                assert details and all(event["source"] == source and event["ids"] == expected_id
                                       and event["ac"] == "detail" for event in details), "Raw restriction cards with shared id lost their actual source origin"
                time.sleep(.5)
                self.key(4)
                self.return_from_detail()
            self.record(TABS[tab] + ": same-id raw-restriction cards preserve their two actual source/detail identities without playback")
            self.ensure_panel(tab, True)
            self.tap(description=ORDINARY_CHIP)
            self.ordinary_ready(tab, marker + "-rawmetadata-switched-off")
            self.tap(description=ADULT_CHIP)
            self.adult_visible(tab, marker + "-rawmetadata-before-home", minimum=41)
            self.assert_ordinary(self.tap(description=TABS[0]))
            self.enter_plain(tab)
            self.ordinary_ready(tab, marker + "-rawmetadata-reentry-off")
            self.record(TABS[tab] + ": selecting 全部, leaving to Home and re-entry exclude every raw-flag adult label")

        self.select_profile("rawchoice")
        for tab in (4, 5):
            marker = "M" if tab == 4 else "T"
            stage = self.fixture.set_stage("rawchoice-" + marker + "-ordinary")
            start = self.fixture.mark()
            self.enter_plain(tab)
            self.ordinary_ready(tab, marker + "-rawchoice-normal-default")
            events = self.fixture.snapshot(start, stage)
            assert all(event["facets"] == {"k_gate": "normal-only"} for event in events if event["kind"] == "page"), "Ordinary defaults selected a raw-flag adult option"
            self.record(TABS[tab] + ": ordinary mode ignores an initial raw-adult opaque filter choice and sends the real normal default")
            stage = self.fixture.set_stage("rawchoice-" + marker + "-adult")
            start = self.fixture.mark()
            self.tap(description=ADULT_CHIP)
            tree = self.adult_visible(tab, marker + "-rawchoice-opaque-adult", minimum=60)
            assert self.count(tree) == "片单，共60部", "Raw filter choice restriction failed to scope untagged cards"
            events = self.fixture.snapshot(start, stage)
            for source, opaque in (("srcA", "opaque-a"), ("srcB", "opaque-b"), ("srcSlow", "opaque-c")):
                assert any(event["source"] == source and event["kind"] == "page"
                           and event["facets"] == {"k_gate": opaque} and event["status"] == 200
                           for event in events), "Raw filter choice did not send exact real opaque value for " + source
            assert "失败" not in self.state_text(tree) and self.by_id(tree, "discoveryRetry") is None, "Raw filter choice mode reports failure"
            self.screenshot(marker + "-raw-choice-metadata")
            self.check_details["rawchoice_" + marker] = {"count": 60, "source_facets": {"srcA": "opaque-a", "srcB": "opaque-b", "srcSlow": "opaque-c"},
                                                       "global_and_inline_filter_metadata": True, "initial_adult_option_ignored_in_normal_mode": True}
            self.record(TABS[tab] + ": global and inline raw is_adult/age_rating choices with opaque n/v scope untagged cards across every source")
            self.tap(description=ORDINARY_CHIP)
            self.ordinary_ready(tab, marker + "-rawchoice-off")
            self.record(TABS[tab] + ": selecting 全部 excludes raw-choice adult labels and restores ordinary rows")

    def check_empty_retry(self):
        self.select_profile("emptyfailure")
        self.enter_plain(4)
        self.ordinary_ready(4, "empty-failure-ordinary-preparation")
        stage = self.fixture.set_stage("empty-adult-continue-retries-failed")
        start = self.fixture.mark()
        self.tap(description=ADULT_CHIP)
        tree = self.wait_ui("empty-adult-failure-continue-visible", lambda current: self.count(current) == "片单，共0部"
                            and self.by_id(current, "discoveryRetry") is not None
                            and self.by_id(current, "discoveryRetry").get("text") == "继续查找")
        assert "失败" in self.state_text(tree), "Genuine failed source is hidden in empty adult continue state"
        before = self.fixture.snapshot(start, stage)
        failed = [event for event in before if event["source"] == "srcA" and event["kind"] == "page"
                  and event["facets"] == {"k_gate": "adult-only"}]
        assert len(failed) == 1 and failed[0]["page"] == 1 and failed[0]["status"] == 503, "Empty-source failure did not retain exact first raw-page cursor"
        for source in ("srcB", "srcSlow"):
            successful = [event for event in before if event["source"] == source and event["kind"] == "page"]
            assert {event["page"] for event in successful} == {1, 2, 3}, "Healthy empty sources did not pause after three scan rounds"
            assert all(event["status"] == 200 and event["completed"] for event in successful), "Empty healthy scans unexpectedly failed"
        self.screenshot("empty-adult-failure-continue")
        self.record("Empty adult mode retains real source failure and pauses healthy scans after three rounds with 继续查找")
        self.tap(identifier="discoveryRetry")
        tree = self.adult_visible(4, "empty-adult-continue-retry-recovered", minimum=PAGE_SIZE)
        assert self.count(tree) == "片单，共20部", "Continue retry accepted ordinary labels or lost recovered adult page"
        after = self.fixture.snapshot(start, stage)
        failed = [event for event in after if event["source"] == "srcA" and event["kind"] == "page"
                  and event["category"] == "movie-alpha" and event["page"] == 1
                  and event["facets"] == {"k_gate": "adult-only"}]
        assert [event["status"] for event in failed] == [503, 200], "Continue skipped exact failed-source first-page retry"
        for source in ("srcB", "srcSlow"):
            for page in (1, 2, 3):
                assert sum(event["source"] == source and event["kind"] == "page" and event["page"] == page
                           for event in after) == 1, "Continue retry repeated a successful healthy raw page"
        assert "失败" not in self.state_text(tree) and self.by_id(tree, "discoveryRetry") is None, "Recovered empty-source retry retained failure UI"
        self.check_details["empty_continue_retry"] = {"same_failed_page_statuses": [503, 200], "adult_count": 20,
                                                     "healthy_raw_pages_preserved": True, "automatic_empty_rounds": 3}
        self.screenshot("empty-adult-continue-recovered-labels")
        self.record("继续查找 retries the real failed source at page 1, renders its 20 adult labels and preserves successful healthy cursors")
        self.tap(description=ORDINARY_CHIP)
        self.ordinary_ready(4, "empty-retry-back-to-ordinary")
        self.tap(description=ADULT_CHIP)
        self.adult_visible(4, "empty-retry-before-home", minimum=PAGE_SIZE)
        self.assert_ordinary(self.tap(description=TABS[0]))
        self.enter_plain(4)
        self.ordinary_ready(4, "empty-retry-reentry-ordinary")
        self.record("Recovered empty-result adult mode remains isolated from selecting 全部, Home and category re-entry")

    def write_result(self):
        result = {"serial": SERIAL, "fixture_only_data_checks": True, "checks": self.steps,
                  "details": self.check_details, "observations": self.observations,
                  "section": self.section, "dump_tool_retries": self.dump_retries,
                  "requires_prior_evidence_for_full_coverage": self.section != "full",
                  "original_restored_and_hash_verified": self.restore_verified,
                  "final_home": self.final_home, "error": self.error,
                  "not_covered": ["availability/content of real external sources",
                                  "physical phone runtime, frame timing and playback"]
                                 + (EARLIER_COVERAGE if self.section == "remaining" else
                                    EARLIER_COVERAGE + ["unsupported/HTTP failure retry, delayed cancellation and restart section"]
                                    if self.section == "rawmetadata" else
                                    EARLIER_COVERAGE + ["raw class/choice metadata, nonempty retry, delayed cancellation and restart"]
                                    if self.section == "emptyretry" else
                                    EARLIER_COVERAGE + ["raw class/choice metadata, normal empty/unsupported and retry"]
                                    if self.section == "restartonly" else ["raw class and choice metadata restrictions", "empty-result continue retries failed source"])}
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
            self.wait_events(lambda events: any(event["kind"] == "config" and event["completed"] for event in events), "fixture config loaded")
            if self.section == "full":
                self.check_filter_entries()
                self.check_mixed_scope_and_paging(4)
                self.check_mixed_scope_and_paging(5)
                self.check_detail_origin()
                self.check_metadata_only()
                self.check_empty_profile("nomarker")
            if self.section == "rawmetadata":
                self.check_rawmetadata()
            elif self.section == "emptyretry":
                self.check_empty_retry()
            elif self.section == "restartonly":
                self.check_restart_only()
            else:
                self.check_empty_profile("unsupported")
                self.check_true_failure_retry()
                self.check_cancel_and_restart()
            crash = self.command("logcat", "-b", "crash", "-d")
            (self.output / "crash.log").write_bytes(crash)
            blocks = self.fatal_blocks(crash)
            assert b"Fatal signal" not in crash, "Fatal native signal occurred"
            if blocks:
                assert len(blocks) == len(self.dump_retries) and all(self.dump_npe_block(block) for block in blocks), "Unclassified or product fatal crash occurred"
                self.check_details["crash_buffer"] = {"tool_dump_npe_count": len(blocks), "other_fatal_count": 0}
                self.record("Crash buffer retains recorded UIAutomator dump-tool NPE(s); no product or other fatal exception/signal")
            else:
                self.check_details["crash_buffer"] = {"tool_dump_npe_count": 0, "other_fatal_count": 0}
                self.record("Crash buffer contains no fatal exception or signal")
        except BaseException as error:
            self.error = str(error) if isinstance(error, AssertionError) else type(error).__name__
            raise
        finally:
            try:
                if self.original is not None and not (self.output / "crash.log").exists():
                    try:
                        (self.output / "crash.log").write_bytes(self.command("logcat", "-b", "crash", "-d"))
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
                    self.assert_ordinary(tree)
                    self.screenshot("restored-home")
                    self.final_home = True
                    self.record("Original configuration/data restored with SHA-256 verification; final ordinary Home")
            finally:
                self.write_result()
            if restore_error is not None:
                raise restore_error


def fixture_self_test():
    command = ["fixture-adb", "-s", SERIAL, "shell", "uiautomator", "dump", "/sdcard/ysc-discovery-qa.xml"]
    exact_error = subprocess.CalledProcessError(137, command)
    assert AdultVerification.tool_dump_error(exact_error)
    assert not AdultVerification.tool_dump_error(subprocess.CalledProcessError(1, command))
    assert not AdultVerification.tool_dump_error(subprocess.CalledProcessError(137, command[:-4] + ["shell", "am", "start", "fixture"]))
    baseline = b"--------- beginning of crash\n"
    known_npe = (b"10-10 18:04:32 E AndroidRuntime: FATAL EXCEPTION: main\n"
                 b"java.lang.NullPointerException: Attempt to invoke virtual method 'java.lang.CharSequence android.view.accessibility.AccessibilityNodeInfo.getContentDescription()' on a null object reference\n"
                 b"at com.android.uiautomator.core.AccessibilityNodeInfoDumper.childNafCheck\n"
                 b"at com.android.commands.uiautomator.DumpCommand.run\n"
                 b"at com.android.commands.uiautomator.Launcher.main\n")
    assert AdultVerification.fresh_dump_npe(baseline, baseline + known_npe)
    assert not AdultVerification.fresh_dump_npe(baseline + known_npe, baseline + known_npe), "Old tool failure incorrectly permits retry"
    assert not AdultVerification.fresh_dump_npe(baseline, baseline + known_npe + PACKAGE.encode())
    assert not AdultVerification.fresh_dump_npe(baseline, baseline + known_npe + b"Fatal signal 11")
    assert not AdultVerification.fresh_dump_npe(baseline, baseline + known_npe + b"E AndroidRuntime: FATAL EXCEPTION: other\n")
    fixture = AdultFixture(0)
    fixture.start()
    try:
        def get(path, query=None, expect=200):
            url = f"http://127.0.0.1:{fixture.port}" + path
            if query:
                url += "?" + urllib.parse.urlencode(query)
            try:
                with urllib.request.urlopen(url, timeout=3) as response:
                    assert response.status == expect
                    return json.load(response)
            except urllib.error.HTTPError as error:
                assert error.code == expect
                return json.load(error)

        for profile in PROFILES:
            fixture.select_profile(profile)
            config = get("/config-" + profile + ".json")
            assert len(config["sites"]) == 3 and config["sites"][1]["searchable"] == 0
            for source in SOURCES:
                metadata = get(f"/api/{profile}/{source}")
                assert {row["type_id"] for row in metadata["class"]} == set(fixture.source_categories(profile, source))
        for category in ("movie-alpha", "tv-alpha"):
            for page in (1, 2):
                mixed = get("/api/mixed/srcB", {"ac": "detail", "t": category, "pg": page})
                adult = [row for row in mixed["list"] if row["vod_name"].startswith("AdultLabel")]
                assert len(adult) == (0 if page == 1 else 10)
                assert all(row.get("vod_tag") or row.get("vod_age") or row.get("vod_rating") for row in adult)
                assert all(not row["vod_pic"] and not row["vod_play_url"] for row in mixed["list"])
                assert mixed["pagecount"] == 2
            explicit = get("/api/mixed/srcA", {"ac": "detail", "t": category, "pg": 1,
                                              "f": json.dumps({"k_gate": "adult-only"})})
            assert len(explicit["list"]) == PAGE_SIZE and all(row["vod_name"].startswith("AdultLabel") for row in explicit["list"])
            assert all(not row["vod_tag"] and not row["vod_class"] and not row.get("vod_age") for row in explicit["list"])
            nomarker = get("/api/nomarker/srcB", {"ac": "detail", "t": category, "pg": 2})
            assert all(row["vod_name"].startswith("NormalLabel") for row in nomarker["list"])
        for category in ("movie-r", "tv-r"):
            body = get("/api/mixed/srcSlow", {"ac": "detail", "t": category, "pg": 1})
            assert len(body["list"]) == PAGE_SIZE and body["pagecount"] == 2
        generic = get("/api/mixed/srcSlow", {"ac": "detail", "t": "generic-r", "pg": 1})
        assert [row["type_name"] for row in generic["list"]] == ["", "电影", "电视剧"]
        for source in ("srcA", "srcB", "srcSlow"):
            metadata = get(f"/api/rawmetadata/{source}")
            restricted = [row for row in metadata["class"] if row["type_id"].endswith("rawflag")]
            assert restricted and all(row.get("age_rating") == 18 if source == "srcB" else row.get("is_adult") is True
                                      for row in restricted)
            assert all("18+" not in row["type_name"] and "成人" not in row["type_name"] for row in restricted)
        for source in ("srcA", "srcB"):
            for category in ("movie-rawflag", "tv-rawflag"):
                body = get(f"/api/rawmetadata/{source}", {"ac": "detail", "t": category, "pg": 1})
                assert len(body["list"]) == PAGE_SIZE and body["pagecount"] == 1
                assert all(row["vod_name"].startswith("AdultLabel") and not row["vod_tag"] and not row["vod_class"]
                           and not row.get("vod_age") and not row.get("vod_rating") for row in body["list"])
        rawgeneric = get("/api/rawmetadata/srcSlow", {"ac": "detail", "t": "generic-rawflag", "pg": 1})
        assert [row["type_name"] for row in rawgeneric["list"]] == ["", "电影", "电视剧"]
        for source, opaque in (("srcA", "opaque-a"), ("srcB", "opaque-b"), ("srcSlow", "opaque-c")):
            metadata = get(f"/api/rawchoice/{source}")
            choices = (metadata["class"][0]["filters"] if source == "srcSlow" else metadata["filters"]["movie-alpha"])[0]
            assert choices["init"] == opaque and choices["value"][1]["v"] == opaque
            assert choices["value"][1]["n"] == "限定"
            assert choices["value"][1].get("age_rating") == 18 if source == "srcB" else choices["value"][1].get("is_adult") is True
            for gate in ("normal-only", opaque):
                body = get(f"/api/rawchoice/{source}", {"ac": "detail", "t": "movie-alpha", "pg": 1,
                                                       "f": json.dumps({"k_gate": gate})})
                assert len(body["list"]) == PAGE_SIZE
                assert all(row["vod_name"].startswith("AdultLabel" if gate == opaque else "NormalLabel")
                           and not row["vod_tag"] and not row["vod_class"] for row in body["list"])
        get("/api/rawchoice/srcB", {"ac": "detail", "t": "movie-alpha", "pg": 1,
                                  "f": json.dumps({"k_gate": "opaque-a"})}, expect=400)
        fallback_before = get("/api/fallback/srcB", {"ac": "detail", "t": "movie-alpha", "pg": 3})
        fallback_after = get("/api/fallback/srcB", {"ac": "detail", "t": "movie-alpha", "pg": 4})
        assert all(row["vod_name"].startswith("NormalLabel") for row in fallback_before["list"])
        assert sum(row["vod_name"].startswith("AdultLabel") for row in fallback_after["list"]) == 10
        assert fallback_before["pagecount"] == fallback_after["pagecount"] == 4
        get("/api/mixed/srcB", {"ac": "detail", "t": "movie-r", "pg": 1}, expect=400)
        get("/api/mixed/srcB", {"ac": "detail", "t": "movie-alpha", "pg": 1,
                              "f": json.dumps({"k_gate": "adult-only"})}, expect=400)
        fixture.select_profile("failure")
        query = {"ac": "detail", "t": "movie-alpha", "pg": 1,
                 "f": json.dumps({"k_gate": "adult-only"})}
        get("/api/failure/srcA", query, expect=503)
        assert get("/api/failure/srcA", query)["list"]
        fixture.select_profile("emptyfailure")
        get("/api/emptyfailure/srcA", query, expect=503)
        recovered = get("/api/emptyfailure/srcA", query)
        assert recovered["pagecount"] == 4 and len(recovered["list"]) == PAGE_SIZE
        assert all(row["vod_name"].startswith("AdultLabel") and not row["vod_tag"] for row in recovered["list"])
        for source in ("srcB", "srcSlow"):
            for page in (1, 2, 3, 4):
                healthy_empty = get(f"/api/emptyfailure/{source}", {"ac": "detail", "t": "movie-alpha", "pg": page})
                assert healthy_empty["pagecount"] == 4
                assert all(row["vod_name"].startswith("NormalLabel") for row in healthy_empty["list"])
        detail = get("/api/mixed/srcSlow", {"ac": "detail", "ids": "scope-movie-1-00"})
        assert not detail["list"][0]["vod_play_url"]
        print("PASS: host-only adult fixture declarations, tags/age/ratings, ordinary isolation, bounded paging, strict protocols, genuine 503 retry and exact dump-tool retry classification; no adb", flush=True)
    finally:
        fixture.close()


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture-self-test", action="store_true")
    parser.add_argument("--section", choices=SECTIONS, default="full")
    parser.add_argument("--output", type=pathlib.Path,
                        default=pathlib.Path(__file__).resolve().parents[1] / ".codex/session/qa/adult-discovery")
    parser.add_argument("--port", type=int, default=0)
    parser.add_argument("--wait-seconds", type=float, default=45)
    parser.add_argument("--slow-seconds", type=float, default=14)
    args = parser.parse_args()
    if args.fixture_self_test:
        fixture_self_test()
        return
    if not 25 <= args.wait_seconds <= 60 or not 10 <= args.slow_seconds <= 16:
        parser.error("Require wait-seconds 25..60 and slow-seconds 10..16")
    fixture = AdultFixture(args.port)
    try:
        AdultVerification(args.output.resolve(), fixture, args.wait_seconds, args.slow_seconds, args.section).run()
    finally:
        if not fixture.thread.is_alive():
            fixture.server.server_close()


if __name__ == "__main__":
    main()
