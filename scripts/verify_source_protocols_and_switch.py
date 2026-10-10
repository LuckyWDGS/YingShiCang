"""Supplemental isolated discovery QA: XML/type-4 protocols, eight sites, config changes.

Uses only the verified LDPlayer. Original DB/prefs are restored and hashed by the
primary QA helper; this script never includes real configuration values in logs.
"""
from __future__ import annotations

import argparse
import base64
import contextlib
import http.server
import json
import pathlib
import re
import sqlite3
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

from verify_all_source_discovery import (ADULT_CHIP, ORDINARY_CHIP, PACKAGE, SERIAL,
                                         AllSourceVerification, CATEGORIES, Fixture)


PROTOCOL_SOURCES = ("srcA", "srcB", "srcC", "srcD", "srcE", "srcF", "src4", "src0")
CONFIG_NAMES = {"old": "QA Protocol Old", "new": "QA Protocol New", "broken": "QA Protocol Broken"}


class ProtocolFixture(Fixture):
    def __init__(self, port):
        self.lock = threading.Lock()
        self.events = []
        self.stage = "setup"
        self.delay_armed = False
        self.active = 0
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
                code, body, content_type, event, pause = fixture.response(parsed.path, query)
                if pause:
                    time.sleep(pause)
                outcome = "sent"
                try:
                    payload = body.encode("utf-8") if isinstance(body, str) else json.dumps(body, ensure_ascii=False).encode("utf-8")
                    fixture.ready(event)
                    self.send_response(code)
                    self.send_header("Content-Type", content_type)
                    self.send_header("Content-Length", str(len(payload)))
                    self.send_header("Cache-Control", "no-store")
                    self.end_headers()
                    self.wfile.write(payload)
                except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                    outcome = "disconnected"
                finally:
                    fixture.complete(event, outcome)

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", port), Handler)
        self.server.daemon_threads = True
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True,
                                       name="ysc-protocol-fixture")

    @property
    def config_url(self):
        return self.url("old")

    def url(self, revision):
        assert revision in CONFIG_NAMES
        return f"http://127.0.0.1:{self.port}/config-{revision}.json"

    def ready(self, event):
        # Stop measuring processing before releasing the response. A client may
        # begin its next request before this server thread completes its cleanup.
        with self.lock:
            event["response_ready"] = True
            if event["kind"] in ("metadata", "page", "extension"):
                self.active -= 1

    def complete(self, event, outcome):
        with self.lock:
            event["completed"] = True
            event["response"] = outcome

    def arm_old_adult_delay(self):
        with self.lock:
            self.delay_armed = True

    def config(self, revision):
        if revision == "broken":
            return {"sites": []}
        return {"name": CONFIG_NAMES[revision], "sites": [
            {"key": source, "name": source, "type": 0 if source == "src0" else 4 if source == "src4" else 1,
             "api": f"http://127.0.0.1:{self.port}/api/{revision}/{source}",
             "searchable": 0 if source in ("srcB", "src0", "src4") else 1,
             "timeout": 20,
             **({"ext": f"http://127.0.0.1:{self.port}/extension/{revision}.txt"} if source == "src4" else {})}
            for source in PROTOCOL_SOURCES], "parses": [], "flags": [], "ads": []}

    @staticmethod
    def xml_metadata():
        root = ET.Element("rss")
        classes = ET.SubElement(root, "class")
        for identifier, (label, _, _) in CATEGORIES.items():
            ET.SubElement(classes, "ty", {"id": identifier}).text = label
        ET.SubElement(root, "list", {"pagecount": "1"})
        return ET.tostring(root, encoding="unicode")

    @staticmethod
    def xml_page(item, pagecount):
        root = ET.Element("rss")
        items = ET.SubElement(root, "list", {"pagecount": str(pagecount)})
        if item:
            video = ET.SubElement(items, "video")
            for xml_name, json_name in (("id", "vod_id"), ("name", "vod_name"), ("type", "type_name"),
                                        ("pic", "vod_pic"), ("year", "vod_year"), ("area", "vod_area"),
                                        ("note", "vod_remarks"), ("des", "vod_content")):
                ET.SubElement(video, xml_name).text = item[json_name]
            ET.SubElement(video, "dl")  # no playable episodes
        return ET.tostring(root, encoding="unicode")

    def response(self, path, query):
        config_match = re.fullmatch(r"/config-(old|new|broken)\.json", path)
        extension_match = re.fullmatch(r"/extension/(old|new)\.txt", path)
        api_match = re.fullmatch(r"/api/(old|new)/(src[A-F]|src4|src0)", path)
        revision = config_match[1] if config_match else extension_match[1] if extension_match else api_match[1] if api_match else "INVALID"
        source = api_match[2] if api_match else "src4" if extension_match else "fixture"
        category_raw = query.get("t", [""])[0]
        category = category_raw if category_raw in CATEGORIES else "" if not category_raw else "INVALID"
        ids_raw = query.get("ids", [""])[0]
        ids = ids_raw if ids_raw == "shared-id" else "" if not ids_raw else "INVALID"
        action_raw = query.get("ac", [""])[0]
        action = action_raw if action_raw in ("", "detail", "videolist") else "INVALID"
        try:
            page = int(query.get("pg", ["1"])[0])
            if source == "src4" and "ext" in query:
                encoded = query["ext"][0]
                facets = json.loads(base64.urlsafe_b64decode(encoded + "=" * (-len(encoded) % 4)).decode("utf-8"))
            else:
                facets = json.loads(query.get("f", ["{}"]) [0])
        except (ValueError, TypeError, UnicodeDecodeError):
            page, facets = -1, {}
        safe_facets = {key: value if isinstance(value, str) and value in allowed else "INVALID"
                       for key, allowed in {"k_gate": {"normal-only", "adult-only"}, "k_genre": {"g-any", "g-comedy"}}.items()
                       if isinstance(facets, dict) and key in facets for value in [facets[key]]}
        kind = "config" if config_match else "extension" if extension_match else "detail" if ids else "page" if category else "metadata"
        event = {"revision": revision, "source": source, "kind": kind, "category": category,
                 "page": page, "ids": ids, "ac": action, "facets": safe_facets,
                 "filter_true": query.get("filter", [""])[0] == "true",
                 "base64_ext": source == "src4" and "ext" in query,
                 "fetched_extend": query.get("extend", [""])[0] == "QA-EXT-" + revision,
                 "status": 200, "delayed": False, "response_ready": False,
                 "completed": False, "response": "pending"}
        pause = 0
        with self.lock:
            event["index"], event["stage"] = len(self.events), self.stage
            if kind in ("metadata", "page", "extension"):
                self.active += 1
            event["active_requests"] = self.active
            self.events.append(event)
            if self.delay_armed and revision == "old" and source == "srcA" and kind == "page" and safe_facets.get("k_gate") == "adult-only":
                self.delay_armed = False
                event["delayed"] = True
                pause = 18
        content_type = "application/xml; charset=utf-8" if source == "src0" else "application/json; charset=utf-8"
        if config_match:
            body = self.config(revision)
            if revision == "broken":
                pause = 1
        elif extension_match:
            body, content_type = "QA-EXT-" + revision, "text/plain; charset=utf-8"
        elif not api_match:
            event["status"], body = 404, {"error": "Unknown fixture route"}
        elif kind == "metadata":
            pause = max(pause, 1.25)
            body = self.xml_metadata() if source == "src0" else {
                "class": [{"type_id": key, "type_name": value[0]} for key, value in CATEGORIES.items()],
                "filters": {key: self.facets(value[2]) for key, value in CATEGORIES.items()}, "list": []}
        elif kind == "detail":
            item = {"vod_id": "shared-id", "vod_name": "Fixture detail " + source,
                    "type_name": "电影", "vod_pic": "", "vod_year": "2026", "vod_area": "中国大陆",
                    "vod_remarks": "QA label", "vod_content": "Empty playable detail", "vod_play_from": "", "vod_play_url": ""}
            body = self.xml_page(item, 1) if source == "src0" else {"list": [item]}
            if source == "src4" and not event["fetched_extend"]:
                event["status"], body = 400, {"error": "Type-4 detail extension was not resolved"}
        elif category not in CATEGORIES or page not in (1, 2):
            event["status"], body = 400, {"error": "Unknown fixture category/page"}
        else:
            _, vod_kind, adult_category = CATEGORIES[category]
            gate = safe_facets.get("k_gate", "")
            valid = action == ("videolist" if source == "src0" else "detail")
            if source != "src0":
                valid = valid and gate in ("normal-only", "adult-only") and safe_facets.get("k_genre") in ("g-any", "g-comedy")
            if not valid:
                event["status"], body = 400, {"error": "Invalid fixture protocol"}
            else:
                adult = adult_category or gate == "adult-only"
                item = self.vod(source, vod_kind, page, adult=adult, blocked=not adult and page == 1)
                if not adult and page == 2:
                    item["vod_name"] = ("Old" if revision == "old" else "New") + ("M" if vod_kind == "movie" else "T") + " " + source
                if source == "src0" and (adult or page == 1):
                    item["type_name"] = "成人电影" if vod_kind == "movie" else "成人剧集"
                body = self.xml_page(item, 1 if adult else 2) if source == "src0" else {
                    "page": page, "pagecount": 1 if adult else 2, "list": [item]}
        return event["status"], body, content_type, event, pause


class ProtocolVerification(AllSourceVerification):
    def __init__(self, output, fixture, wait_seconds):
        super().__init__(output, fixture, wait_seconds)
        self.config_ids = {}
        self.forward_port = None

    def install_fixture(self):
        super().install_fixture()
        # Isolate the Lines dialog from real names/URLs while retaining original data privately.
        with tempfile.TemporaryDirectory(prefix="ysc-protocol-config-") as temporary:
            local = pathlib.Path(temporary) / "fixture-db"
            local.write_bytes(self.read_file("databases/tv"))
            with contextlib.closing(sqlite3.connect(local)) as db:
                active = db.execute("SELECT id,time FROM Config WHERE type=0 ORDER BY time DESC LIMIT 1").fetchone()
                assert active is not None
                db.execute("DELETE FROM Config WHERE type=0 AND id<>?", (active[0],))
                db.execute("UPDATE Config SET name=? WHERE id=?", (CONFIG_NAMES["old"], active[0]))
                self.config_ids["old"] = active[0]
                for offset, revision in enumerate(("new", "broken"), 1):
                    cursor = db.execute("INSERT INTO Config(type,time,url,json,name,home,parse) VALUES(?,?,?,?,?,?,?)",
                                        (0, active[1] - offset, self.fixture.url(revision), "", CONFIG_NAMES[revision], "srcA", ""))
                    self.config_ids[revision] = cursor.lastrowid
                db.commit()
            self.install_file(local, "databases/tv")

    def connect_app_server(self):
        # Server.start() binds the first free emulator port in 9978..9998. Probe only
        # the existing read-only /device endpoint; never retain its private fields.
        for remote in range(9978, 9999):
            local = self.command("forward", "tcp:0", f"tcp:{remote}").decode("ascii").strip()
            assert local.isdigit(), "ADB did not allocate a local forwarding port"
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:{local}/device", timeout=1) as response:
                    value = json.loads(response.read(32768).decode("utf-8"))
                if isinstance(value, dict) and {"type", "uuid", "ip"}.issubset(value):
                    self.forward_port = local
                    return
            except (OSError, ValueError, urllib.error.URLError):
                pass
            self.command("forward", "--remove", "tcp:" + local, check=False)
        raise AssertionError("App local HTTP endpoint is unavailable")

    def runtime_load(self, revision):
        assert self.forward_port is not None and revision in CONFIG_NAMES
        config = {"id": self.config_ids[revision], "type": 0, "url": self.fixture.url(revision),
                  "name": CONFIG_NAMES[revision], "home": "srcA", "json": ""}
        body = urllib.parse.urlencode({"do": "sync", "mode": "1", "type": "history", "force": "false",
                                       "config": json.dumps(config), "targets": "[]"}).encode("utf-8")
        request = urllib.request.Request(f"http://127.0.0.1:{self.forward_port}/action", data=body,
                                         headers={"Content-Type": "application/x-www-form-urlencoded"})
        with urllib.request.urlopen(request, timeout=8) as response:
            assert response.status == 200, "Local configuration load command failed"

    @staticmethod
    def text_values(tree):
        return [node.get("text", "") for node in tree.iter("node")]

    def tap_text_ancestor(self, title):
        tree = self.dump("before-fixture-control")
        target = next((node for node in tree.iter("node") if node.get("text") == title), None)
        assert target is not None, "Visible fixture control missing: " + title
        parents = {child: parent for parent in tree.iter() for child in parent}
        node = target
        while node is not None and node.get("clickable") != "true":
            node = parents.get(node)
        assert node is not None, "Fixture control lacks a clickable hierarchy ancestor"
        left, top, right, bottom = self.bounds(node)
        self.shell("input", "tap", str((left + right) // 2), str((top + bottom) // 2))
        time.sleep(.3)

    @classmethod
    def fixture_card_titles(cls, tree):
        # Arrival order is deliberately incremental. A card's title TextView can
        # be below the viewport while its partially visible clickable card still
        # has the real title/source accessibility identity. Match fixture labels
        # only; navigation/status descriptions must not count as loaded cards.
        source = "(?:" + "|".join(re.escape(value) for value in PROTOCOL_SOURCES) + ")"
        pattern = re.compile(r"^((?:(?:Old|New)[MT]|AdultScope [MT]|BlockedAdult [MT]) "
                             + source + r")(?=$|[^A-Za-z0-9])")
        titles = set(cls.titles(tree))
        for node in tree.iter("node"):
            match = pattern.match(node.get("content-desc", ""))
            if match:
                titles.add(match.group(1))
        return titles

    def assert_ordinary(self, tree):
        super().assert_ordinary(tree)
        assert not any("AdultScope" in title or "BlockedAdult" in title
                       for title in self.fixture_card_titles(tree)), "Adult fixture card identity escaped normal scope"
        adult = self.by_description(tree, ADULT_CHIP)
        ordinary = self.by_description(tree, ORDINARY_CHIP)
        assert adult is None or adult.get("selected") != "true", "Scrolling changed the explicit adult gate"
        assert ordinary is None or ordinary.get("selected") == "true", "Scrolling disabled ordinary mode"

    @classmethod
    def list_swipe_coordinates(cls, tree, reverse=False):
        assert cls.by_id(tree, "filterPanel") is None, "Close filter controls before scrolling the underlying card list"
        recycler = cls.by_id(tree, "recycler")
        assert recycler is not None, "Protocol card list is unavailable"
        left, top, right, bottom = cls.bounds(recycler)
        x, height = (left + right) // 2, bottom - top
        high, low = top + height // 5, top + height * 4 // 5
        return (x, high if reverse else low, x, low if reverse else high)

    def scroll_list(self, label, reverse=False):
        # Always use a fresh hierarchy and close a reappearing filter panel before
        # injecting another gesture. Repeated end-of-list swipes can otherwise
        # operate the overlay's controls instead of the intended card RecyclerView.
        tree = self.dump(label + "-before-scroll")
        self.assert_ordinary(tree)
        if self.by_id(tree, "filterPanel") is not None:
            self.tap(text="收起")
            self.wait_ui(label + "-closed-filters", lambda current: self.by_id(current, "filterPanel") is None,
                         ordinary=True)
            tree = self.dump(label + "-fresh-card-bounds")
            self.assert_ordinary(tree)
        points = self.list_swipe_coordinates(tree, reverse)
        self.shell("input", "swipe", *(str(value) for value in points), "250")
        after = self.dump(label + "-after-scroll")
        self.assert_ordinary(after)
        if self.by_id(after, "filterPanel") is not None:
            self.tap(text="收起")
            after = self.wait_ui(label + "-closed-filters-after-scroll",
                                 lambda current: self.by_id(current, "filterPanel") is None, ordinary=True)
        return after

    def normal_ready(self, tab, revision, label):
        marker = "M" if tab == 4 else "T"
        prefix = "Old" if revision == "old" else "New"
        required = {prefix + marker + " srcA", prefix + marker + " srcB"}
        tree = self.wait_ui(label, lambda current: required.issubset(self.fixture_card_titles(current))
                            and self.by_id(current, "loadProgress") is None, ordinary=True)
        assert all(not title.startswith("Old" if revision == "new" else "New") for title in self.fixture_card_titles(tree)), "Cards from replaced configuration remained visible"
        chip = self.by_description(tree, ORDINARY_CHIP)
        assert chip is not None and chip.get("selected") == "true", "Configuration change retained 18+ authorization"
        return tree

    def collect_titles(self):
        found = set()
        expected = {"OldM " + source for source in PROTOCOL_SOURCES}
        for _ in range(5):
            tree = self.dump("collect-protocol-source-cards")
            self.assert_ordinary(tree)
            found.update(self.titles(tree))
            if expected.issubset(found):
                break
            found.update(self.titles(self.scroll_list("collect-protocol-source-cards")))
        # Restore top using fresh recycler bounds, so later sourceA/B taps are visible.
        for _ in range(3):
            self.scroll_list("protocol-list-restore-top", reverse=True)
        return found

    def scroll_to_title(self, title):
        for _ in range(6):
            tree = self.dump("find-protocol-source-card")
            self.assert_ordinary(tree)
            if title in self.titles(tree):
                return
            self.scroll_list("find-protocol-source-card")
        raise AssertionError("Source card was not found using fresh hierarchy bounds: " + title)

    def check_type4_detail(self):
        self.scroll_to_title("OldM src4")
        start = self.fixture.mark()
        self.tap_title("OldM src4")
        events = self.wait_events(lambda records: any(event["kind"] == "detail" and event["completed"] for event in records),
                                 "type-4 originating detail with extension", start)
        details = [event for event in events if event["kind"] == "detail"]
        assert details and all(event["revision"] == "old" and event["source"] == "src4"
                               and event["ids"] == "shared-id" and event["fetched_extend"]
                               and event["status"] == 200 for event in details), "Type-4 card detail lost its origin or fetched extension"
        time.sleep(.4)
        self.key(4)
        self.return_from_detail()
        self.record("Non-home type-4 card opens its actual detail endpoint with resolved remote extension")

    def check_protocols(self):
        stage = self.fixture.set_stage("old-protocols")
        self.enter(4)
        self.normal_ready(4, "old", "old-eight-source-ready")
        events = self.fixture.snapshot(stage=stage)
        for source in PROTOCOL_SOURCES:
            assert any(event["source"] == source and event["kind"] == "metadata" and event["status"] == 200 for event in events), "Metadata missing beyond worker batch: " + source
            assert all(any(event["source"] == source and event["kind"] == "page" and event["category"] == "movie-alpha"
                           and event["page"] == page and event["status"] == 200 for event in events) for page in (1, 2)), "Raw-page advance missing for " + source
        peak = max(event["active_requests"] for event in events if event["kind"] in ("metadata", "page", "extension"))
        assert 2 <= peak <= 12, "Fixture observed concurrency outside the bounded twelve-HTTP-source limit"
        type4 = [event for event in events if event["source"] == "src4"]
        assert any(event["kind"] == "extension" for event in type4), "Remote type-4 extension was not fetched"
        assert any(event["kind"] == "metadata" and event["filter_true"] and event["fetched_extend"] for event in type4)
        assert all(event["base64_ext"] and event["fetched_extend"] and event["facets"].get("k_gate") == "normal-only"
                   for event in type4 if event["kind"] == "page"), "Type-4 base64 filter/extension protocol is wrong"
        xml_pages = [event for event in events if event["source"] == "src0" and event["kind"] == "page"]
        assert xml_pages and all(event["ac"] == "videolist" and event["facets"] == {} for event in xml_pages), "Type-0 XML protocol sent invented facets"
        self.tap(text="收起")
        visible = self.collect_titles()
        assert {"OldM " + source for source in PROTOCOL_SOURCES}.issubset(visible), "Eight-source cards did not all render after hierarchy-derived scrolling"
        assert not any(event["source"] == "src0" and event["kind"] == "page" and event["page"] > 2 for event in self.fixture.snapshot(stage=stage)), "XML pagecount=2 did not stop pagination"
        self.check_details["protocols"] = {"source_count": 8, "peak_processing_requests": peak,
                                            "concurrency_interval": "request accepted to response ready, excludes server write cleanup",
                                            "type4_remote_extension_and_base64_filters": True,
                                            "type0_xml_pagecount_and_videolist": True}
        self.screenshot("eight-source-protocols-ordinary")
        self.record("Eight sources run concurrently; type-4 fetched extension/base64 filters and type-0 XML pagecount pass")
        self.check_type4_detail()

    def check_active_runtime_switch(self):
        self.enter(4, reset=True)
        self.normal_ready(4, "old", "runtime-switch-preparation")
        self.connect_app_server()
        stage = self.fixture.set_stage("active-runtime-replacement")
        start = self.fixture.mark()
        self.fixture.arm_old_adult_delay()
        self.tap(description=ADULT_CHIP)
        self.wait_events(lambda events: any(event["delayed"] for event in events), "old adult request started", start)
        self.runtime_load("new")
        delayed = [event for event in self.fixture.snapshot(start, stage) if event["delayed"]]
        assert delayed and not delayed[0]["completed"], "Old request completed before config replacement; test was not in flight"
        self.normal_ready(4, "new", "runtime-replacement-new-ordinary")
        events = self.fixture.snapshot(start, stage)
        for source in PROTOCOL_SOURCES:
            assert any(event["revision"] == "new" and event["source"] == source and event["kind"] == "metadata" for event in events), "Old metadata reused across changed config: " + source
        assert any(event["revision"] == "new" and event["source"] == "src4" and event["kind"] == "extension" for event in events), "Same-key type4 reused old remote extension"
        deadline = time.monotonic() + 24
        while True:
            tree = self.dump("runtime-switch-stale-old-response")
            self.assert_ordinary(tree)
            assert not any(title.startswith("Old") for title in self.fixture_card_titles(tree)), "Old configuration cards returned after replacement"
            if any(event["delayed"] and event["completed"] for event in self.fixture.snapshot(start, stage)):
                break
            if time.monotonic() >= deadline:
                raise AssertionError("Old delayed response did not finish during config-switch QA")
            time.sleep(.4)
        self.normal_ready(4, "new", "runtime-switch-after-old-response")
        self.tap(text="收起")
        self.scroll_to_title("NewM srcA")
        detail_start = self.fixture.mark()
        self.tap_title("NewM srcA")
        events = self.wait_events(lambda records: any(event["kind"] == "detail" and event["completed"] for event in records),
                                 "new configuration detail", detail_start)
        assert all(event["revision"] == "new" and event["source"] == "srcA" and event["ids"] == "shared-id"
                   for event in events if event["kind"] == "detail"), "Same-key card opened prior configuration API"
        time.sleep(.4)
        self.key(4)
        self.return_from_detail()
        self.screenshot("runtime-replacement-ordinary")
        self.record("Active-filter runtime replacement revokes 18+, reloads same-key metadata/extensions/APIs, rejects delayed old cards, routes detail to new API")

    def check_line_success_and_rollback(self):
        # The app exposes Lines through Home; switching here exercises SourceManager
        # and its real rollback callback. Active-filter invalidation is checked above.
        self.fixture.set_stage("line-success")
        self.tap(description="主页")
        self.tap_text_ancestor("线路")
        self.tap(description="切换线路 " + CONFIG_NAMES["old"])
        tree = self.wait_ui("line-old-success", lambda current: any("已切换至「" + CONFIG_NAMES["old"] + "」" in value
                                                                    for value in self.text_values(current)))
        assert self.by_description(tree, "切换线路 " + CONFIG_NAMES["old"]).get("selected") == "true"
        self.tap(description="关闭")
        self.enter(4)
        self.normal_ready(4, "old", "line-success-old-ordinary")
        self.record("Lines UI successfully activates the old fixture through SourceManager with same source keys/different APIs")

        stage = self.fixture.set_stage("line-rollback")
        self.tap(description="主页")
        self.tap_text_ancestor("线路")
        self.tap(description="切换线路 " + CONFIG_NAMES["broken"])
        tree = self.wait_ui("line-broken-rollback", lambda current: any("切换失败，已保留原线路" in value
                                                                       for value in self.text_values(current)))
        old = self.by_description(tree, "切换线路 " + CONFIG_NAMES["old"])
        assert old is not None and old.get("selected") == "true", "Rollback lost the previous active configuration"
        assert any(event["kind"] == "config" and event["revision"] == "broken" for event in self.fixture.snapshot(stage=stage)), "Broken fixture was not attempted"
        self.tap(description="关闭")
        self.enter(4)
        self.normal_ready(4, "old", "rollback-old-data-restored")
        self.tap(text="收起")
        self.scroll_to_title("OldM srcA")
        detail_start = self.fixture.mark()
        self.tap_title("OldM srcA")
        events = self.wait_events(lambda records: any(event["kind"] == "detail" and event["completed"] for event in records),
                                 "rollback detail", detail_start)
        assert all(event["revision"] == "old" and event["source"] == "srcA" for event in events if event["kind"] == "detail"), "Rollback detail used failed/new source API"
        time.sleep(.4)
        self.key(4)
        self.return_from_detail()
        self.screenshot("rollback-ordinary-old")
        self.record("Broken Lines activation reports failure, retains prior active line, reloads its ordinary data and correct details")

    def write_result(self):
        result = {"serial": SERIAL, "fixture_only": True, "checks": self.steps, "details": self.check_details,
                  "original_restored_and_hash_verified": self.restore_verified, "error": self.error,
                  "not_covered": ["type-3 crawler execution", "real external-source availability",
                                  "active-filter rollback via Lines (Lines is exposed only on Home; active success uses existing local sync API)"]}
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
            self.wait_events(lambda events: any(event["kind"] == "config" and event["completed"] for event in events), "protocol fixture loaded")
            self.wait_events(lambda events: any(event["kind"] == "metadata" and event["source"] == "srcA" and event["completed"] for event in events), "home source metadata complete")
            self.check_protocols()
            self.check_active_runtime_switch()
            self.check_line_success_and_rollback()
            crash = self.command("logcat", "-b", "crash", "-d").decode("utf-8", errors="replace")
            (self.output / "crash.log").write_text(crash, encoding="utf-8")
            assert "FATAL EXCEPTION" not in crash and "Fatal signal" not in crash
            self.record("Supplemental crash buffer contains no fatal exception/signal")
        except BaseException as error:
            self.error = str(error) if isinstance(error, AssertionError) else type(error).__name__
            raise
        finally:
            try:
                if self.original is not None and not (self.output / "crash.log").exists():
                    try:
                        (self.output / "crash.log").write_bytes(self.command("logcat", "-b", "crash", "-d"))
                    except Exception:
                        self.check_details["failure_crash_capture"] = "unavailable"
                self.restore_app_data()
            except BaseException as error:
                restore_error = error
                self.check_details["restoration_error"] = type(error).__name__
            try:
                if self.forward_port:
                    self.command("forward", "--remove", "tcp:" + self.forward_port, check=False)
                if self.reverse_installed:
                    self.command("reverse", "--remove", f"tcp:{self.fixture.port}", check=False)
                self.cleanup_remote()
            finally:
                if self.fixture.thread.is_alive():
                    self.fixture.close()
                self.write_result()
            if self.restore_verified:
                self.launch()
                tree = self.tap(description="主页")
                self.check_panel(tree, False)
                self.screenshot("restored-home")
            if restore_error is not None:
                raise RuntimeError("Original app data restoration failed; inspect private-original and result.json") from restore_error


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=pathlib.Path,
                        default=pathlib.Path(__file__).resolve().parents[1] / ".codex/session/qa/source-protocols-and-switch")
    parser.add_argument("--port", type=int, default=0)
    parser.add_argument("--wait-seconds", type=float, default=55)
    args = parser.parse_args()
    if not 30 <= args.wait_seconds <= 60:
        parser.error("--wait-seconds must be between 30 and 60")
    fixture = ProtocolFixture(args.port)
    try:
        ProtocolVerification(args.output.resolve(), fixture, args.wait_seconds).run()
    finally:
        if not fixture.thread.is_alive():
            fixture.server.server_close()


if __name__ == "__main__":
    main()
