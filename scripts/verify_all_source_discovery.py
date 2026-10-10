"""Run isolated all-source discovery QA on the verified LDPlayer, then restore its data.

Fixture titles are plain labels. There are no posters, playable URLs, or adult visuals.
Original app data is private: only hashes and relative filenames enter result.json.
"""
from __future__ import annotations

import argparse
import collections
from contextlib import closing
import hashlib
import http.server
import json
import pathlib
import re
import sqlite3
import sys
import tempfile
import threading
import time
import urllib.parse
import uuid
import xml.etree.ElementTree as ET

from verify_discovery import PACKAGE, SERIAL, TABS, Verification


DATABASE_FILES = ("tv", "tv-wal", "tv-shm", "tv-journal")
DEFAULT_PREF = PACKAGE + "_preferences.xml"
DISCOVERY_PREF = "discovery_filters.xml"
ADULT_CHIP = "内容：18+（成人向）"
ORDINARY_CHIP = "内容：全部"
SOURCES = ("srcA", "srcB", "srcSlow")
KINDS = {4: ("movie", "M"), 5: ("tv", "T")}
CATEGORIES = {
    "movie-alpha": ("电影", "movie", False),
    "tv-alpha": ("电视剧", "tv", False),
    "movie-r": ("18+ 成人电影", "movie", True),
    "tv-r": ("18+ 成人剧集", "tv", True),
}


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


class Fixture:
    """In-memory test data. Logs contain only allowlisted fixture identifiers."""

    def __init__(self, port: int):
        self.lock = threading.Lock()
        self.events = []
        self.stage = "setup"
        self.failure = None
        self.delay = None
        fixture = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass  # BaseHTTPRequestHandler would log URLs, which this QA never needs.

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
                outcome = "sent"
                try:
                    payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
                    self.send_response(code)
                    self.send_header("Content-Type", "application/json; charset=utf-8")
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
        self.thread = threading.Thread(target=self.server.serve_forever,
                                       name="ysc-discovery-fixture", daemon=True)

    @property
    def config_url(self):
        return f"http://127.0.0.1:{self.port}/config.json"

    def start(self):
        self.thread.start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=3)

    def set_stage(self, stage):
        with self.lock:
            self.stage = stage
        return stage

    def mark(self):
        with self.lock:
            return len(self.events)

    def snapshot(self, since=0, stage=None):
        with self.lock:
            return [dict(event) for event in self.events[since:]
                    if stage is None or event["stage"] == stage]

    def arm_failure(self):
        with self.lock:
            self.failure = ("srcSlow", "movie-alpha", "normal-only", 1)

    def arm_delay(self, seconds=18):
        with self.lock:
            self.delay = ("srcSlow", "movie-alpha", "adult-only", 1, seconds)

    def complete(self, event, outcome):
        with self.lock:
            event["completed"] = True
            event["response"] = outcome

    @staticmethod
    def facets(adult_category=False):
        return [
            {"key": "k_genre", "name": "类型", "init": "g-any",
             "value": [{"n": "全部", "v": "g-any"}, {"n": "喜剧", "v": "g-comedy"}]},
            {"key": "k_gate", "name": "内容", "init": "adult-only" if adult_category else "normal-only",
             "value": ([{"n": "18+", "v": "adult-only"}] if adult_category else
                       [{"n": "全部", "v": "normal-only"}, {"n": "18+", "v": "adult-only"}])},
        ]

    @staticmethod
    def vod(source, kind, page, adult=False, blocked=False):
        marker = "M" if kind == "movie" else "T"
        if blocked:
            name, identifier = f"BlockedAdult {marker} {source}", f"blocked-{kind}"
        elif adult:
            name, identifier = f"AdultScope {marker} {source}", f"adult-{kind}"
        elif page == 2:
            name, identifier = f"Ord{marker} {source}", "shared-id"
        else:
            name, identifier = f"Page3 {marker} {source}", f"page3-{kind}"
        return {"vod_id": identifier, "vod_name": name, "vod_pic": "",
                "type_name": "电影" if kind == "movie" else "电视剧",
                "vod_class": "18+ 成人向" if adult or blocked else "喜剧",
                "vod_year": "2026", "vod_area": "中国大陆", "vod_remarks": "QA label",
                "vod_play_from": "", "vod_play_url": "", "vod_content": "Isolated QA label only."}

    def response(self, path, query):
        source = path.strip("/").removeprefix("api/")
        raw_category = query.get("t", [""])[0]
        category = raw_category if raw_category in CATEGORIES else "" if not raw_category else "INVALID"
        raw_ids = query.get("ids", [""])[0]
        ids = raw_ids if re.fullmatch(r"(?:shared-id|adult-(?:movie|tv)|page3-(?:movie|tv)|blocked-(?:movie|tv))", raw_ids) else "" if not raw_ids else "INVALID"
        try:
            page = int(query.get("pg", ["1"])[0])
            facets = json.loads(query.get("f", ["{}"])[0])
        except (ValueError, TypeError):
            page, facets = -1, {}
        allowed = {"k_gate": {"normal-only", "adult-only"}, "k_genre": {"g-any", "g-comedy"}}
        safe_facets = {key: value if isinstance(value, str) and value in values else "INVALID"
                       for key, values in allowed.items() if key in facets
                       for value in [facets[key]]} if isinstance(facets, dict) else {}
        mode = safe_facets.get("k_gate", "")
        kind = "config" if path == "/config.json" else "detail" if ids else "page" if category else "metadata"
        raw_action = query.get("ac", [""])[0]
        action = raw_action if raw_action in ("", "detail", "videolist") else "INVALID"
        event = {"source": source if source in SOURCES else "fixture", "ac": action,
                 "kind": kind, "category": category, "page": page,
                 "ids": ids, "facets": safe_facets, "status": 200,
                 "delayed": False, "completed": False, "response": "pending"}
        pause = 0
        with self.lock:
            event["index"] = len(self.events)
            event["stage"] = self.stage
            self.events.append(event)
            signature = (source, category, mode, page)
            if kind == "page" and signature == self.failure:
                self.failure = None
                event["status"] = 503
            if kind == "page" and self.delay is not None and signature == self.delay[:4]:
                pause = self.delay[4]
                self.delay = None
                event["delayed"] = True
        if kind == "config":
            body = {"sites": [{"key": key, "name": key, "type": 1,
                               "api": f"http://127.0.0.1:{self.port}/api/{key}",
                               "searchable": 0 if key == "srcB" else 1,
                               "quickSearch": 0, "timeout": 20}
                              for key in SOURCES], "parses": [], "flags": [], "ads": []}
        elif source not in SOURCES:
            event["status"], body = 404, {"error": "Unknown fixture route"}
        elif event["status"] == 503:
            body = {"error": "One-shot fixture failure"}
        elif kind == "metadata":
            body = {"class": [{"type_id": key, "type_name": value[0]} for key, value in CATEGORIES.items()],
                    "filters": {key: self.facets(value[2]) for key, value in CATEGORIES.items()}, "list": []}
        elif kind == "detail":
            # No flags or episodes, hence no real playback can be started by this fixture.
            body = {"list": [{"vod_id": ids, "vod_name": f"Detail {source}", "vod_pic": "",
                              "vod_play_from": "", "vod_play_url": "", "vod_content": "QA detail."}]}
        elif category == "INVALID" or page not in (1, 2, 3) or mode not in allowed["k_gate"] or safe_facets.get("k_genre") not in allowed["k_genre"]:
            event["status"], body = 400, {"error": "Undeclared fixture category or facet"}
        else:
            _, vod_kind, adult_category = CATEGORIES[category]
            adult = mode == "adult-only" or adult_category
            items = ([self.vod(source, vod_kind, page, adult=True)] if adult and page == 1 else
                     [] if adult else [self.vod(source, vod_kind, page, blocked=page == 1)])
            body = {"page": page, "pagecount": 1 if adult else 3, "limit": 1,
                    "total": 1 if adult else 3, "list": items}
        return event["status"], body, event, pause


class AllSourceVerification(Verification):
    def __init__(self, output, fixture, wait_seconds):
        # Verification calls verified_adb(SERIAL), rejecting every physical/remote target.
        super().__init__(output)
        self.fixture = fixture
        self.wait_seconds = wait_seconds
        self.original = None
        self.restore_verified = False
        self.reverse_installed = False
        self.remote_dir = "/data/local/tmp/ysc-source-qa-" + uuid.uuid4().hex
        self.remote_files = set()
        self.remote_created = False
        self.error = None
        self.check_details = {}

    def list_prefs(self):
        output = self.shell("run-as", PACKAGE, "sh", "-c",
                            "'find shared_prefs -maxdepth 1 -type f -print'", check=False).decode("utf-8")
        names = []
        for relative in output.splitlines():
            if not relative.startswith("shared_prefs/"):
                continue
            name = relative.removeprefix("shared_prefs/")
            if not re.fullmatch(r"[A-Za-z0-9_.-]+", name):
                raise AssertionError("Preference filename is outside the scoped safe-name format")
            names.append(name)
        return sorted(names)

    def exists(self, relative):
        # All relative paths supplied here are fixed constants or validated preference names.
        return self.shell("run-as", PACKAGE, "sh", "-c",
                          "'test -f " + relative + " && echo present'", check=False).strip() == b"present"

    def read_file(self, relative):
        return self.command("exec-out", "run-as", PACKAGE, "cat", relative)

    def snapshot_app_data(self):
        self.shell("run-as", PACKAGE, "id")
        self.shell("am", "force-stop", PACKAGE)
        snapshot = {}
        for name in DATABASE_FILES:
            relative = "databases/" + name
            if self.exists(relative):
                snapshot[relative] = self.read_file(relative)
        assert "databases/tv" in snapshot, "Installed app database tv is unavailable; no app files changed"
        for name in self.list_prefs():
            relative = "shared_prefs/" + name
            snapshot[relative] = self.read_file(relative)
        private = self.output / "private-original"
        assert not private.exists(), "Use a fresh output directory to preserve the original snapshot"
        for relative, data in snapshot.items():
            target = private / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        manifest = {relative: digest(data) for relative, data in snapshot.items()}
        (private / "sha256.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
        self.original = snapshot
        self.check_details["original_file_count"] = len(snapshot)

    def install_file(self, local, relative):
        if not self.remote_created:
            self.shell("mkdir", self.remote_dir)
            self.shell("chmod", "755", self.remote_dir)
            self.remote_created = True
        remote = self.remote_dir + "/" + uuid.uuid4().hex
        self.remote_files.add(remote)
        self.command("push", str(local), remote)
        self.shell("chmod", "644", remote)
        self.shell("run-as", PACKAGE, "cp", remote, relative)
        self.shell("run-as", PACKAGE, "chmod", "600", relative)

    @staticmethod
    def pref_xml(original, values):
        root = ET.fromstring(original) if original else ET.Element("map")
        for name, value in values.items():
            for node in list(root):
                if node.get("name") == name:
                    root.remove(node)
            ET.SubElement(root, "string", {"name": name}).text = value
        return ET.tostring(root, encoding="utf-8", xml_declaration=True)

    def install_fixture(self):
        assert self.original is not None
        with tempfile.TemporaryDirectory(prefix="ysc-source-qa-") as temporary:
            temp = pathlib.Path(temporary)
            for name in DATABASE_FILES:
                data = self.original.get("databases/" + name)
                if data is not None:
                    (temp / name).write_bytes(data)
            prepared = temp / "fixture-db"
            # SQLite sees the copied WAL/SHM next to tv; backup merges committed WAL data.
            with closing(sqlite3.connect(temp / "tv")) as original_db, closing(sqlite3.connect(prepared)) as modified_db:
                original_db.backup(modified_db)
                row = modified_db.execute("SELECT id FROM Config WHERE type=0 ORDER BY time DESC LIMIT 1").fetchone()
                assert row is not None, "No type-0 configuration row; no app files changed"
                modified_db.execute("UPDATE Config SET url=?,name=?,home=?,json=? WHERE id=?",
                                    (self.fixture.config_url, "QA all-source scope", "srcA", "", row[0]))
                modified_db.commit()
            self.install_file(prepared, "databases/tv")
            for name in DATABASE_FILES[1:]:
                self.shell("run-as", PACKAGE, "rm", "-f", "databases/" + name)
            self.shell("run-as", PACKAGE, "mkdir", "-p", "shared_prefs")
            # Legacy adult prefs must be ignored. Ordinary values are otherwise defaults.
            saved = {
                "tab_4": json.dumps({"content": "情色", "genre": "", "region": "", "sort": "U", "year": ""}, ensure_ascii=False),
                "tab_5": json.dumps({"content": "情色", "type": "", "tv_form": "", "show_form": "", "region": "", "sort": "U", "year": "", "platform": ""}, ensure_ascii=False),
            }
            preference_data = {
                DISCOVERY_PREF: self.pref_xml(None, saved),
                DEFAULT_PREF: self.pref_xml(self.original.get("shared_prefs/" + DEFAULT_PREF),
                                           {"config_0": self.fixture.config_url}),
            }
            for name, data in preference_data.items():
                local = temp / name
                local.write_bytes(data)
                self.shell("run-as", PACKAGE, "rm", "-f", "shared_prefs/" + name + ".bak")
                self.install_file(local, "shared_prefs/" + name)

    def restore_app_data(self):
        if self.original is None:
            return
        self.shell("am", "force-stop", PACKAGE)
        # Remove only enumerated preference files and the four named tv companions.
        current_prefs = self.list_prefs()
        original_prefs = [relative.removeprefix("shared_prefs/") for relative in self.original
                          if relative.startswith("shared_prefs/")]
        for name in sorted(set(current_prefs + original_prefs + [DISCOVERY_PREF, DEFAULT_PREF,
                                                                DISCOVERY_PREF + ".bak", DEFAULT_PREF + ".bak"])):
            self.shell("run-as", PACKAGE, "rm", "-f", "shared_prefs/" + name)
        for name in DATABASE_FILES:
            self.shell("run-as", PACKAGE, "rm", "-f", "databases/" + name)
        with tempfile.TemporaryDirectory(prefix="ysc-source-restore-") as temporary:
            for index, (relative, data) in enumerate(self.original.items()):
                local = pathlib.Path(temporary) / str(index)
                local.write_bytes(data)
                self.install_file(local, relative)
        for relative, data in self.original.items():
            assert digest(self.read_file(relative)) == digest(data), "App data restoration hash mismatch: " + relative
        for name in DATABASE_FILES:
            assert self.exists("databases/" + name) == ("databases/" + name in self.original), "Database companion restoration mismatch"
        assert self.list_prefs() == sorted(original_prefs), "Preference file inventory restoration mismatch"
        self.restore_verified = True
        self.record("Original tv database, companions and every preference restored; SHA-256 verified before launch")

    def cleanup_remote(self):
        for remote in sorted(self.remote_files):
            self.shell("rm", "-f", remote, check=False)
        if self.remote_created:
            self.shell("rmdir", self.remote_dir, check=False)

    @staticmethod
    def titles(tree):
        return [node.get("text", "") for node in tree.iter("node")
                if node.get("resource-id", "").endswith(":id/name") and node.get("text")]

    def assert_no_success_count(self, tree):
        text = " ".join(node.get("text", "") for node in tree.iter("node"))
        assert re.search(r"已加载\s*\d+\s*部", text) is None, "Successful load count row is visible"

    def assert_ordinary(self, tree):
        self.assert_no_success_count(tree)
        assert not any("AdultScope" in title or "BlockedAdult" in title for title in self.titles(tree)), "Adult fixture result escaped normal scope"

    def wait_ui(self, label, predicate, *, ordinary=False, timeout=None):
        deadline = time.monotonic() + (timeout or self.wait_seconds)
        while True:
            tree = self.dump(label)
            self.assert_no_success_count(tree)
            if ordinary:
                self.assert_ordinary(tree)
            if predicate(tree):
                return tree
            if time.monotonic() >= deadline:
                raise AssertionError("Timed out: " + label)
            time.sleep(.4)

    def wait_events(self, predicate, label, since=0, timeout=None):
        deadline = time.monotonic() + (timeout or self.wait_seconds)
        while True:
            events = self.fixture.snapshot(since)
            if predicate(events):
                return events
            if time.monotonic() >= deadline:
                raise AssertionError("Timed out waiting for fixture requests: " + label)
            time.sleep(.05)

    def enter(self, tab, *, reset=False):
        tree = self.tap(description=TABS[tab])
        if self.by_id(tree, "filterPanel") is None:
            tree = self.tap(description=TABS[tab])
        if reset:
            tree = self.tap(text="重置")
        ordinary = self.by_description(tree, ORDINARY_CHIP)
        assert ordinary is not None and ordinary.get("selected") == "true", "18+ unexpectedly restored on entry"
        return tree

    def ordinary_ready(self, tab, label):
        marker = KINDS[tab][1]
        required = {f"Ord{marker} srcA", f"Ord{marker} srcB"}
        return self.wait_ui(label, lambda tree: required.issubset(self.titles(tree))
                            and self.by_id(tree, "loadProgress") is None, ordinary=True)

    def adult_ready(self, tab, label):
        marker = KINDS[tab][1]
        required = {f"AdultScope {marker} srcA", f"AdultScope {marker} srcB"}
        tree = self.wait_ui(label, lambda tree: required.issubset(self.titles(tree))
                            and self.by_id(tree, "loadProgress") is None)
        assert all(title.startswith("AdultScope") for title in self.titles(tree)), "Ordinary cards retained in adult mode"
        assert self.by_description(tree, ADULT_CHIP).get("selected") == "true"
        return tree

    def check_scope(self, tab):
        kind, marker = KINDS[tab]
        stage = self.fixture.set_stage(kind + "-ordinary")
        self.enter(tab)
        tree = self.ordinary_ready(tab, kind + "-ordinary-ready")
        events = self.fixture.snapshot(stage=stage)
        for source in ("srcA", "srcB"):
            pages = [event["page"] for event in events if event["source"] == source and event["kind"] == "page"]
            assert 1 in pages and 2 in pages, "Filtered raw first page did not advance for " + source
        assert all(event["facets"].get("k_gate") == "normal-only" for event in events if event["kind"] == "page"), "Normal mode requested adult category"
        self.screenshot(kind + "-ordinary")
        self.record(kind + ": legacy 18+ off, raw filtered page advances, shared id from srcA/srcB remains distinct; searchable=0 included")

        stage = self.fixture.set_stage(kind + "-adult")
        self.tap(description=ADULT_CHIP)
        self.adult_ready(tab, kind + "-adult-ready")
        events = self.fixture.snapshot(stage=stage)
        for source in ("srcA", "srcB"):
            assert any(event["source"] == source and event["kind"] == "page"
                       and event["facets"].get("k_gate") == "adult-only" for event in events), "Adult scope omitted " + source
        assert all(event["facets"].get("k_gate") == "adult-only" for event in events if event["kind"] == "page"), "Adult mode requested ordinary data"
        self.screenshot(kind + "-adult-labels-only")
        self.record(kind + ": explicit 18+ maps opaque adult-only facet for all fixture sources")
        self.tap(text="收起")
        adult_category = "movie-r" if tab == 4 else "tv-r"
        self.wait_events(lambda records: all(any(event["source"] == source and event["kind"] == "page"
                                                 and event["category"] == adult_category
                                                 and event["facets"].get("k_gate") == "adult-only"
                                                 for event in records)
                                            for source in ("srcA", "srcB")), kind + " declared adult category")
        self.wait_ui(kind + "-adult-category-finished", lambda current: self.by_id(current, "loadProgress") is None)
        self.record(kind + ": additional declared adult category uses its actual nonnumeric id")

        self.fixture.set_stage(kind + "-leave-home")
        tree = self.tap(description="主页")
        self.assert_ordinary(tree)
        self.check_panel(tree, False)
        self.wait_ui(kind + "-home-isolation", lambda current: self.by_id(current, "loadProgress") is None,
                     ordinary=True)
        self.enter(tab)
        self.ordinary_ready(tab, kind + "-reentry-ordinary")
        self.record(kind + ": Home has no adult fixture cards; re-entry turns 18+ off")

        # Expanding filters intentionally pauses viewport-based pagination. Collapse via UI.
        self.fixture.set_stage(kind + "-page3")
        self.tap(text="收起")
        self.wait_events(lambda records: all(any(event["source"] == source and event["kind"] == "page"
                                                 and event["page"] == 3 for event in records)
                                            for source in ("srcA", "srcB")), kind + " page 3")
        self.wait_ui(kind + "-page3-rendered", lambda current: any(title.startswith("Page3 " + marker)
                                                                  for title in self.titles(current)), ordinary=True)
        self.record(kind + ": independent source page 3 appended after ordinary raw page 2")

    def check_genre(self):
        stage = self.fixture.set_stage("opaque-genre")
        self.enter(4, reset=True)
        self.ordinary_ready(4, "genre-before")
        start = self.fixture.mark()
        self.tap(description="类型：喜剧")
        self.ordinary_ready(4, "genre-after")
        events = self.fixture.snapshot(start, stage)
        for source in ("srcA", "srcB"):
            assert any(event["source"] == source and event["kind"] == "page"
                       and event["facets"].get("k_genre") == "g-comedy" for event in events), "Canonical genre did not use declared opaque value"
        assert all(event["facets"].get("k_genre") == "g-comedy" for event in events if event["kind"] == "page")
        self.record("Movie comedy selection sends actual source k_genre=g-comedy for searchable and nonsearchable sources")

    def check_retry(self):
        self.enter(4, reset=True)
        self.ordinary_ready(4, "retry-preparation")
        stage = self.fixture.set_stage("partial-failure")
        self.fixture.arm_failure()
        self.tap(text="重置")
        tree = self.wait_ui("partial-failure-ready", lambda current: self.by_id(current, "discoveryRetry") is not None
                            and self.by_id(current, "loadProgress") is None, ordinary=True)
        status = self.by_id(tree, "discoveryStatus")
        assert status is not None and "失败" in status.get("text", ""), "Partial failure is not reported"
        before = self.fixture.snapshot(stage=stage)
        successful = collections.Counter((event["source"], event["category"], event["page"])
                                         for event in before if event["kind"] == "page" and event["status"] == 200)
        assert all(successful[(source, "movie-alpha", 1)] == 1 for source in ("srcA", "srcB"))
        self.tap(identifier="discoveryRetry")
        self.ordinary_ready(4, "partial-retry-ready")
        after = self.fixture.snapshot(stage=stage)
        for signature, count in successful.items():
            assert sum(event["kind"] == "page" and event["status"] == 200
                       and (event["source"], event["category"], event["page"]) == signature for event in after) == count, "Retry repeated a successful page"
        failed_page = [event for event in after if event["source"] == "srcSlow" and event["kind"] == "page"
                       and event["category"] == "movie-alpha" and event["page"] == 1]
        assert [event["status"] for event in failed_page] == [503, 200], "Failure did not retry the identical page"
        assert any(event["source"] == "srcSlow" and event["page"] == 2 and event["status"] == 200 for event in after)
        self.check_details["partial_retry"] = {"successful_initial_pages_unchanged": True,
                                               "failed_page_statuses": [event["status"] for event in failed_page]}
        self.record("Partial failure exposes retry; retry preserves successful pages and repeats the failed source/page only")

    def check_stale_adult(self):
        self.enter(4, reset=True)
        self.ordinary_ready(4, "stale-preparation")
        stage = self.fixture.set_stage("delayed-adult-reset")
        self.fixture.arm_delay()
        start = self.fixture.mark()
        self.tap(description=ADULT_CHIP)
        self.wait_events(lambda events: any(event["delayed"] for event in events), "delayed adult started", start)
        self.tap(text="重置")
        delayed = [event for event in self.fixture.snapshot(start, stage) if event["delayed"]]
        assert delayed and not delayed[0]["completed"], "Delayed response completed before Reset; this run did not test an in-flight mode change"
        self.ordinary_ready(4, "stale-reset-ordinary")
        deadline = time.monotonic() + 24
        observations = 0
        while True:
            tree = self.dump("stale-adult-remains-absent")
            self.assert_ordinary(tree)
            assert self.by_description(tree, ORDINARY_CHIP).get("selected") == "true"
            observations += 1
            events = self.fixture.snapshot(start, stage)
            if any(event["delayed"] and event["completed"] for event in events):
                break
            if time.monotonic() >= deadline:
                raise AssertionError("Delayed fixture response did not finish within bounded stale-response check")
            time.sleep(.4)
        # Reinspect after the delayed response/disconnect, rather than only during loading.
        self.assert_ordinary(self.dump("stale-adult-after-response"))
        self.check_details["stale_response_observations"] = observations + 1
        self.record("Reset during a delayed adult request immediately returns ordinary mode and rejects the old response")

    def tap_title(self, title):
        tree = self.dump("before-card-tap")
        target = next((node for node in tree.iter("node") if node.get("text") == title
                       and node.get("resource-id", "").endswith(":id/name")), None)
        assert target is not None, "Visible source card is missing: " + title
        parents = {child: parent for parent in tree.iter() for child in parent}
        node = target
        while node is not None and node.get("clickable") != "true":
            node = parents.get(node)
        assert node is not None, "Card has no clickable hierarchy ancestor"
        left, top, right, bottom = self.bounds(node)
        self.shell("input", "tap", str((left + right) // 2), str((top + bottom) // 2))

    def return_from_detail(self):
        for _ in range(4):
            tree = self.dump("return-detail")
            if self.by_description(tree, "电影筛选") is not None:
                return tree
            self.key(4)
        raise AssertionError("Did not return from source detail to discovery")

    def check_details_origin(self):
        self.fixture.set_stage("detail-origin")
        self.enter(4, reset=True)
        self.ordinary_ready(4, "detail-preparation")
        self.tap(text="收起")
        for source in ("srcA", "srcB"):
            self.wait_ui("detail-card-visible", lambda tree: "OrdM " + source in self.titles(tree), ordinary=True)
            start = self.fixture.mark()
            self.tap_title("OrdM " + source)
            events = self.wait_events(lambda records: any(event["kind"] == "detail" for event in records),
                                     "detail origin " + source, start, timeout=25)
            detail = [event for event in events if event["kind"] == "detail"]
            assert detail and all(event["source"] == source and event["ids"] == "shared-id"
                                  and event["ac"] == "detail" for event in detail), "Same-id source card opened the wrong source"
            self.wait_events(lambda records: any(event["kind"] == "detail" and event["completed"] for event in records),
                             "detail response " + source, start, timeout=25)
            # Give VideoActivity its response before Back. There is no playable fixture URL.
            time.sleep(.5)
            self.key(4)
            self.return_from_detail()
        self.record("Same vod id from srcA/srcB opens its own ac=detail endpoint with no fixture media playback")

    def check_restart_gate(self):
        self.fixture.set_stage("restart-gate")
        self.enter(4, reset=True)
        self.ordinary_ready(4, "restart-preparation")
        self.tap(description=ADULT_CHIP)
        self.adult_ready(4, "restart-before")
        time.sleep(.4)
        current = self.read_file("shared_prefs/" + DISCOVERY_PREF)
        saved = {node.get("name"): json.loads(node.text or "{}") for node in ET.fromstring(current).findall("string")}
        assert "content" not in saved.get("tab_4", {}), "18+ persisted into saved filter state"
        self.shell("am", "force-stop", PACKAGE)
        self.launch()
        self.enter(4)
        self.ordinary_ready(4, "restart-after")
        self.record("18+ is absent from persisted filter JSON and is off after app restart")

    def write_result(self):
        result = {"serial": SERIAL, "fixture_only": True,
                  "checks": self.steps, "details": self.check_details,
                  "original_restored_and_hash_verified": self.restore_verified,
                  "error": self.error,
                  "not_covered": ["runtime configuration replacement (root verifies separately)",
                                  "real external source availability (root verifies separately)"]}
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
            self.check_scope(4)
            self.check_scope(5)
            self.check_genre()
            self.check_retry()
            self.check_stale_adult()
            self.check_details_origin()
            self.check_restart_gate()
            crash = self.command("logcat", "-b", "crash", "-d").decode("utf-8", errors="replace")
            (self.output / "crash.log").write_text(crash, encoding="utf-8")
            assert "FATAL EXCEPTION" not in crash and "Fatal signal" not in crash, "Crash buffer contains a crash"
            self.record("Crash buffer contains no fatal exception or fatal signal")
        except BaseException as error:
            # Record a sanitized type. Assertion messages contain only our own fixture labels.
            self.error = str(error) if isinstance(error, AssertionError) else type(error).__name__
            raise
        finally:
            try:
                if self.original is not None and not (self.output / "crash.log").exists():
                    # Failure paths still preserve crash evidence; do not emit its contents.
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
                        default=pathlib.Path(__file__).resolve().parents[1] / ".codex/session/qa/all-source-fixture")
    parser.add_argument("--port", type=int, default=0, help="Local fixture port; default chooses a free port")
    parser.add_argument("--wait-seconds", type=float, default=55, help="Bounded source/UI polling; must be 30..60")
    args = parser.parse_args()
    if not 30 <= args.wait_seconds <= 60:
        parser.error("--wait-seconds must be between 30 and 60")
    fixture = Fixture(args.port)
    try:
        AllSourceVerification(args.output.resolve(), fixture, args.wait_seconds).run()
    finally:
        if not fixture.thread.is_alive():
            fixture.server.server_close()


if __name__ == "__main__":
    main()
