"""Deterministic TV candidate continuity QA via existing per-host fixture proxies.

Only root runs the guarded emulator operations. No production test hook, global
proxy, DNS, Wi-Fi, or certificate-store change is needed. The temporary app config
routes only the suggestion/ranking hosts through a localhost CONNECT/TLS fixture.
Its temporary self-signed certificate relies on the app's existing HTTP client
behavior; this script does not alter that behavior. Unknown hosts are rejected and
no request is forwarded externally. Fixture words and results are plain QA labels.
UI hierarchy sampling proves visible states, not frame-accurate timing.
"""
from __future__ import annotations

import argparse
import datetime
import http.server
import json
import pathlib
import re
import ssl
import sys
import tempfile
import time
import urllib.parse
import urllib.request

from verify_discovery import PACKAGE, SERIAL
from verify_search_input import InputFixture, InputVerification


SUGGEST_HOST = "suggest.video.iqiyi.com"
RANK_HOST = "api.web.360kan.com"
ALLOWED_HOSTS = (SUGGEST_HOST, RANK_HOST)
SEED = ["QA Seed A one", "QA Seed A two"]
NEW = ["QA Latest ABCDE one", "QA Latest ABCDE two"]
LATEST = ["QA Latest ABC one", "QA Latest ABC two"]


class ContinuityFixture(InputFixture):
    def __init__(self, port=0):
        super().__init__(port)
        self.cert_dir = tempfile.TemporaryDirectory(prefix="ysc-suggestion-tls-")
        self.tls = self.make_tls(pathlib.Path(self.cert_dir.name))
        fixture = self
        original_handler = self.server.RequestHandlerClass

        class Handler(original_handler):
            def do_CONNECT(self):
                # Never accept arbitrary hosts/ports or forward a tunnel externally.
                if self.path not in tuple(host + ":443" for host in ALLOWED_HOSTS):
                    self.send_error(403)
                    return
                host = self.path.removesuffix(":443")
                self.send_response(200, "Connection established")
                self.end_headers()
                self.wfile.flush()
                self.close_connection = True

                class TLSHandler(http.server.BaseHTTPRequestHandler):
                    protocol_version = "HTTP/1.1"

                    def log_message(self, *args):
                        pass

                    def do_GET(self):
                        parsed = urllib.parse.urlsplit(self.path)
                        query = urllib.parse.parse_qs(parsed.query)
                        raw = query.get("key", [""])[0]
                        word = raw if re.fullmatch(r"[A-F]{1,6}", raw) else "INVALID"
                        if host == RANK_HOST:
                            code, body, event, pause = fixture.provider_event("rank", "", 503, {}, 0)
                        elif parsed.path == "/" and word != "INVALID":
                            code, body, event, pause = fixture.suggestion(word)
                        else:
                            code, body, event, pause = fixture.provider_event("rejected", "INVALID", 400, {}, 0)
                        if pause:
                            time.sleep(pause)
                        payload = json.dumps(body).encode("utf-8")
                        outcome = "sent"
                        try:
                            self.send_response(code)
                            self.send_header("Content-Type", "application/json; charset=utf-8")
                            self.send_header("Content-Length", str(len(payload)))
                            self.send_header("Cache-Control", "no-store")
                            self.send_header("Connection", "close")
                            self.end_headers()
                            self.wfile.write(payload)
                            self.wfile.flush()
                        except (OSError, ssl.SSLError):
                            outcome = "disconnected"
                        finally:
                            self.close_connection = True
                            with fixture.lock:
                                event.update(completed=True, response=outcome, response_done_s=fixture.elapsed())

                try:
                    with fixture.tls.wrap_socket(self.connection, server_side=True) as connection:
                        connection.settimeout(10)
                        TLSHandler(connection, self.client_address, self.server)
                except (OSError, ssl.SSLError):
                    # Failed handshakes are not exposed as private host/URL logs.
                    with fixture.lock:
                        fixture.events.append({"index": len(fixture.events), "stage": fixture.stage,
                                               "kind": "tls-handshake", "source": "fixture",
                                               "word": "", "completed": True, "response": "disconnected"})

        self.server.RequestHandlerClass = Handler

    @staticmethod
    def make_tls(directory):
        # Import only for this host-side fixture, not for unrelated QA scripts.
        from cryptography import x509
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import rsa
        from cryptography.x509.oid import NameOID
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        now = datetime.datetime.now(datetime.timezone.utc)
        subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, SUGGEST_HOST)])
        cert = (x509.CertificateBuilder().subject_name(subject).issuer_name(subject)
                .public_key(key.public_key()).serial_number(x509.random_serial_number())
                .not_valid_before(now - datetime.timedelta(days=1))
                .not_valid_after(now + datetime.timedelta(days=1))
                .add_extension(x509.SubjectAlternativeName([x509.DNSName(host) for host in ALLOWED_HOSTS]), critical=False)
                .sign(key, hashes.SHA256()))
        key_path, cert_path = directory / "key.pem", directory / "cert.pem"
        key_path.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                              serialization.NoEncryption()))
        cert_path.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(cert_path, key_path)
        return context

    def close(self):
        try:
            super().close()
        finally:
            self.cert_dir.cleanup()

    def response(self, path, query):
        word = query.get("wd", [""])[0]
        prepared = dict(query)
        if word == SEED[0]:
            prepared["wd"] = ["A"]
        code, body, event, pause = super().response(path, prepared)
        if event["kind"] == "config":
            body["proxy"] = [{"name": "QA suggestion fixture", "hosts": list(ALLOWED_HOSTS),
                              "urls": ["http://127.0.0.1:%d" % self.port]}]
        elif event["kind"] == "search" and word == SEED[0]:
            with self.lock:
                event["word"] = word
            body = {"page": 1, "pagecount": 1,
                    "list": [self.input_vod(event["source"], "SeedChoice")]}
        elif event["kind"] == "detail" and event["ids"] == "qa-input-SeedChoice":
            body = {"list": [self.input_vod(event["source"], "SeedChoice")]}
        return code, body, event, pause

    def provider_event(self, kind, word, code, body, pause):
        with self.lock:
            event = {"index": len(self.events), "stage": self.stage, "source": "fixture",
                     "kind": kind, "word": word, "status": code, "delay_s": pause,
                     "request_s": self.elapsed(), "delayed": pause > 0,
                     "completed": False, "response": "pending"}
            self.events.append(event)
        return code, body, event, pause

    def suggestion(self, word):
        with self.lock:
            stage = self.stage
        code, values, pause = 200, SEED, .02
        if stage == "hot-pending" or stage in ("old-pending", "stale-pending", "clear-pending", "click-pending"):
            values, pause = ["QA Late " + word], 5
        elif stage == "explicit-failure":
            code, values = 503, []
        elif stage == "empty-success":
            values = []
        elif stage == "new-success":
            values = NEW
        elif stage == "latest-success":
            values = LATEST
        return self.provider_event("suggestion", word, code, {"data": [{"title": value} for value in values]}, pause)


class ContinuityVerification(InputVerification):
    def __init__(self, output, fixture, wait_seconds, *, baseline=False):
        super().__init__(output, fixture, wait_seconds, baseline=baseline)
        self.observations = []

    @staticmethod
    def candidates(tree):
        words = InputVerification.by_id(tree, "wordRecycler")
        return [] if words is None else [node.get("text", "") for node in words.iter("node") if node.get("text")]

    @staticmethod
    def hot_words(tree):
        groups = InputVerification.by_id(tree, "hotGroups")
        return [] if groups is None else [node.get("text", "") for node in groups.iter("node")
                                         if node.get("resource-id", "").endswith(":id/text") and node.get("text")]

    def observe(self, label, tree, expected, *, hot=False):
        shown = self.hot_words(tree) if hot else self.candidates(tree)
        retained = shown == expected
        self.observations.append({"label": label, "observed_s": self.fixture.elapsed(),
                                  "display": "hot" if hot else "suggestion", "shown": shown,
                                  "expected": expected, "retained": retained,
                                  "editor": self.by_id(tree, "keyword").get("text", ""),
                                  "status_present": self.by_id(tree, "searchStatus") is not None})
        if not self.baseline:
            assert retained, "Visible candidate continuity mismatch: " + label
            assert self.by_id(tree, "searchStatus") is None, "Typing introduced a status prompt"
        self.screenshot(label)
        return retained

    def provider_requested(self, word, since):
        return self.wait_events(lambda records: any(event["kind"] == "suggestion" and event["word"] == word
                                                    for event in records), "controlled suggestion requested: " + word, since)

    def change(self, stage, point, word):
        self.fixture.set_stage(stage)
        since = self.fixture.mark()
        self.tap_point(point)
        self.provider_requested(word, since)
        return since

    def require_editor_text(self, tree, word):
        assert self.by_id(tree, "keyword").get("text", "") == word, "Editor did not retain current fixture word"

    def pending_then_timeout(self, stage, word, since, expected, *, hot=False):
        tree = self.dump(stage + "-pending")
        self.require_editor_text(tree, word)
        self.observe(stage + "-pending", tree, expected, hot=hot)
        records = self.wait_events(lambda values: any(event["kind"] == "suggestion" and event["completed"]
                                                      for event in values), "delayed provider write attempted", since)
        delayed = [event for event in records if event["kind"] == "suggestion" and event["delay_s"] == 5]
        assert delayed, "Controlled five-second suggestion was not exercised"
        assert all(event["response"] == "disconnected" for event in delayed), "Timed-out suggestion TLS connection stayed open"
        tree = self.dump(stage + "-after-timeout")
        self.observe(stage + "-after-timeout", tree, expected, hot=hot)
        self.record(stage + ": observed pending/timeout states and real TLS disconnect" +
                    ("; baseline continuity recorded" if self.baseline else "; candidates remained visible"))
        return tree

    def check_continuity(self):
        home = self.wait_ui("home-before-continuity", lambda tree: self.by_description(tree, "主页") is not None)
        self.tap_control(home, text="搜索")
        tree = self.wait_ui("continuity-empty", lambda value: self.by_id(value, "keyword") is not None)
        expected_hot = ["QA rank 2", "QA rank 1", "QA rank 3"]
        assert self.hot_words(tree) == expected_hot, "Cached textual hot ranks were not shown"
        points = {letter: self.point(tree, text=letter) for letter in "ABCDEF"}
        clear = self.point(tree, identifier="clearInput")
        # A first query times out. Cached hot candidates must stay visible throughout.
        since = self.change("hot-pending", points["A"], "A")
        self.screenshot("hot-first-input-immediate")
        tree = self.pending_then_timeout("hot-first-input", "A", since, expected_hot, hot=True)
        self.tap_point(clear)
        tree = self.dump("clear-before-seed")
        assert self.hot_words(tree) == expected_hot and not self.candidates(tree), "Clear did not restore the cached hot ranks"
        # Populate an actual suggestion list through the production HTTPS request path.
        since = self.change("seed-success", points["A"], "A")
        tree = self.wait_ui("seed-visible", lambda value: self.candidates(value) == SEED)
        self.observe("seed-visible", tree, SEED)
        self.record("Real suggestion request through the fixture TLS proxy populates the seed candidates")
        since = self.change("old-pending", points["B"], "AB")
        self.screenshot("old-candidates-after-next-key-immediate")
        tree = self.pending_then_timeout("old-candidates", "AB", since, SEED)
        # Explicit failures and successful empty results also preserve the seed list.
        for stage, key, word in (("explicit-failure", "C", "ABC"), ("empty-success", "D", "ABCD")):
            self.change(stage, points[key], word)
            tree = self.dump(stage)
            self.require_editor_text(tree, word)
            self.observe(stage, tree, SEED)
            self.record(stage + (": baseline continuity recorded" if self.baseline else ": seed candidates remain visible"))
        self.change("new-success", points["E"], "ABCDE")
        tree = self.wait_ui("new-current-visible", lambda value: self.candidates(value) == NEW)
        self.observe("new-current-visible", tree, NEW)
        self.record("A current nonempty result replaces the seed list with the new list")
        # Clear then revisit A: cached values should appear with no second A HTTP request.
        self.tap_point(clear)
        self.fixture.set_stage("cache-hit")
        since = self.fixture.mark()
        self.tap_point(points["A"])
        tree = self.wait_ui("cache-A-visible", lambda value: self.candidates(value) == SEED)
        self.observe("cache-A-visible", tree, SEED)
        assert not any(event["kind"] == "suggestion" for event in self.fixture.snapshot(since)), "A cached query reissued provider HTTP"
        self.record("Returning to A reuses seed candidates without a provider request")
        # Start AB but immediately switch to ABC after the fixture has accepted AB.
        since = self.change("stale-pending", points["B"], "AB")
        self.change("latest-success", points["C"], "ABC")
        tree = self.wait_ui("latest-current-visible", lambda value: self.candidates(value) == LATEST)
        records = self.wait_events(lambda values: any(event["kind"] == "suggestion" and event["word"] == "AB"
                                                      and event["completed"] for event in values), "old cancelled AB write attempted", since)
        old = [event for event in records if event["kind"] == "suggestion" and event["word"] == "AB"]
        assert old and all(event["response"] == "disconnected" for event in old), "Superseded AB TLS connection did not close"
        tree = self.dump("latest-after-stale-write")
        self.observe("latest-after-stale-write", tree, LATEST)
        self.record("Superseded AB is disconnected; its late write cannot replace current ABC candidates")
        # Clear during one more delayed request; late results must not replace hot ranks.
        # ABCE is uncached in both baseline and optimized controllers. ABCD may
        # be cached as an empty success by the baseline implementation.
        since = self.change("clear-pending", points["E"], "ABCE")
        self.tap_point(clear)
        self.wait_events(lambda values: any(event["kind"] == "suggestion" and event["completed"] for event in values),
                         "cleared query late write attempted", since)
        tree = self.dump("clear-rejects-late-write")
        assert self.hot_words(tree) == expected_hot and not self.candidates(tree), "A cleared query brought old suggestions back"
        self.screenshot("clear-rejects-late-write")
        self.record("Clear restores hot ranks and rejects the late suggestion write")
        self.fixture.set_stage("cache-before-click")
        self.tap_point(points["A"])
        tree = self.wait_ui("seed-before-candidate-click", lambda value: self.candidates(value) == SEED)
        since = self.change("click-pending", points["F"], "AF")
        tree = self.dump("seed-click-while-pending")
        self.require_editor_text(tree, "AF")
        visible = self.observe("seed-click-while-pending", tree, SEED)
        if self.baseline and not visible:
            self.check_details["candidate_click_while_pending"] = "not reachable: baseline hid the candidate list"
            self.tap_point(clear)
            self.record("Baseline hides pending candidates; clicking retained candidates is unreachable")
        else:
            self.fixture.set_stage("candidate-submit")
            search_since = self.fixture.mark()
            self.tap_control(tree, text=SEED[0])
            expected_cards = {"Input-SeedChoice srcA", "Input-SeedChoice src02"}
            tree = self.wait_ui("retained-candidate-formal-results", lambda value: set(self.titles(value)) == expected_cards)
            searches = [event for event in self.fixture.snapshot(search_since) if event["kind"] == "search"]
            assert {event["source"] for event in searches} == {"srcA", "src02"}, "Candidate formal search source scope mismatch"
            assert all(event["word"] == SEED[0] for event in searches), "Candidate click searched the pending editor word instead of its label"
            records = self.wait_events(lambda values: any(event["kind"] == "suggestion" and event["word"] == "AF"
                                                          and event["completed"] for event in values), "clicked pending suggestion write attempted", since)
            pending = [event for event in records if event["kind"] == "suggestion" and event["word"] == "AF"]
            assert pending and all(event["response"] == "disconnected" for event in pending), "Candidate submission did not cancel the pending TLS suggestion"
            self.screenshot("retained-candidate-formal-results")
            self.check_details["candidate_click_while_pending"] = "seed label searched by both enabled sources; pending suggestion disconnected"
            self.record("Clicking a retained candidate searches its label in both enabled sources and cancels the pending suggestion")
        self.key(4)
        self.wait_ui("continuity-back-home", lambda value: self.by_description(value, "主页") is not None)
        self.record("Back returns Home")

    def write_result(self):
        result = {"serial": SERIAL, "fixture_only": True, "baseline": self.baseline,
                  "proxy_scope": list(ALLOWED_HOSTS), "system_network_changed": False,
                  "text_only_no_media": True, "checks": self.steps, "details": self.check_details,
                  "original_restored_and_hash_verified": self.restore_verified, "error": self.error,
                  "not_covered": ["frame-accurate no-blank rendering proof", "real provider speed/availability",
                                  "DPAD focus retention while replacement is pending", "phone runtime"]}
        (self.output / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        (self.output / "fixture-requests.json").write_text(json.dumps(self.fixture.snapshot(), indent=2), encoding="utf-8")
        (self.output / "ui-observations.json").write_text(json.dumps(self.observations, indent=2), encoding="utf-8")

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
                             "continuity fixture config loaded")
            self.check_continuity()
            crash = self.command("logcat", "-b", "crash", "-d").decode("utf-8", errors="replace")
            (self.output / "crash.log").write_text(crash, encoding="utf-8")
            assert "FATAL EXCEPTION" not in crash and "Fatal signal" not in crash, "Continuity fixture produced a fatal crash"
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
    fixture = ContinuityFixture()
    fixture.start()
    try:
        with urllib.request.urlopen(fixture.config_url, timeout=3) as response:
            config = json.load(response)
        assert config["proxy"][0]["hosts"] == list(ALLOWED_HOSTS)
        proxy = "http://127.0.0.1:%d" % fixture.port
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({"https": proxy}),
                                            urllib.request.HTTPSHandler(context=ssl._create_unverified_context()))
        fixture.set_stage("seed-success")
        with opener.open("https://" + SUGGEST_HOST + "/?key=A", timeout=3) as response:
            assert [item["title"] for item in json.load(response)["data"]] == SEED
        fixture.set_stage("new-success")
        with opener.open("https://" + SUGGEST_HOST + "/?key=ABCDE", timeout=3) as response:
            assert [item["title"] for item in json.load(response)["data"]] == NEW
        fixture.set_stage("empty-success")
        with opener.open("https://" + SUGGEST_HOST + "/?key=ABCD", timeout=3) as response:
            assert json.load(response)["data"] == []
        with urllib.request.urlopen("http://127.0.0.1:%d/api/srcA?%s" %
                                    (fixture.port, urllib.parse.urlencode({"wd": SEED[0]})), timeout=3) as response:
            choice = json.load(response)["list"][0]
        assert choice["vod_name"] == "Input-SeedChoice srcA" and not choice["vod_play_url"]
        code, detail, _, _ = fixture.response("/api/srcA", {"ids": [choice["vod_id"]]})
        assert code == 200 and detail["list"][0]["vod_name"] == choice["vod_name"]
        fixture.set_stage("old-pending")
        try:
            opener.open("https://" + SUGGEST_HOST + "/?key=AB", timeout=.4)
            raise AssertionError("Delayed TLS fixture did not hold its response")
        except TimeoutError:
            pass
        deadline = time.monotonic() + 7
        while time.monotonic() < deadline:
            late = [event for event in fixture.snapshot() if event["kind"] == "suggestion" and event["word"] == "AB"]
            if late and late[0]["completed"]:
                break
            time.sleep(.05)
        assert late and late[0]["completed"] and late[0]["response"] == "disconnected", "Timed-out host client did not disconnect delayed TLS response"
        print("PASS: exact-host CONNECT/TLS proxy, seed/new/empty words, candidate-label search/details and real delayed-response disconnect; no adb or external forwarding", flush=True)
    finally:
        fixture.close()


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=pathlib.Path, default=pathlib.Path(__file__).resolve().parents[1]
                        / ".codex/session/qa/search-suggestion-continuity")
    parser.add_argument("--port", type=int, default=0)
    parser.add_argument("--wait-seconds", type=float, default=45)
    parser.add_argument("--baseline", action="store_true", help="Record the old candidate disappearance without failing continuity")
    parser.add_argument("--fixture-self-test", action="store_true", help="Host-only proxy/fixture validation; never access adb")
    args = parser.parse_args()
    if args.fixture_self_test:
        fixture_self_test()
        return
    if not 30 <= args.wait_seconds <= 60:
        parser.error("--wait-seconds must be 30..60")
    fixture = ContinuityFixture(args.port)
    try:
        ContinuityVerification(args.output.resolve(), fixture, args.wait_seconds, baseline=args.baseline).run()
    finally:
        if not fixture.thread.is_alive():
            fixture.server.server_close()
            fixture.cert_dir.cleanup()


if __name__ == "__main__":
    main()
