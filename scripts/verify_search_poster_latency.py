"""Measure optional poster-enrichment delay while preserving valid search results.

The isolated fixture returns textual search hits in 0.25 s and deliberately holds
their empty-poster detail responses for 8 s. UI hierarchy timing is an observed
upper bound. This does not measure or promise real external-site speeds.
Every original database/preference file is restored and SHA256 verified by the
existing guarded QA harness. Only the verified LDPlayer 127.0.0.1:5555 is used.
"""
from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys
import urllib.request

from verify_discovery_latency import LatencyFixture, LatencyVerification


class PosterLatencyFixture(LatencyFixture):
    def __init__(self, port=0, sources=18, detail_seconds=8.0):
        super().__init__(port=port, sources=sources, slow_seconds=9, fast_seconds=.25)
        self.detail_seconds = detail_seconds

    def response(self, path, query):
        code, body, event, pause = super().response(path, query)
        if event["kind"] == "detail" and event["stage"] == "poster-keyword":
            pause = self.detail_seconds
            event["delay_s"] = pause
            event["delayed"] = True
        return code, body, event, pause


class PosterLatencyVerification(LatencyVerification):
    def __init__(self, output, fixture, wait_seconds, *, baseline=False):
        super().__init__(output, fixture, wait_seconds, baseline=baseline, keyword=True)

    def check_filter_latency(self):
        pass  # Original benchmark stays frozen; this run tests only keyword search.

    def check_cancelled_adult(self):
        pass

    @staticmethod
    def rendered_count(tree):
        for node in tree.iter("node"):
            if node.get("resource-id", "").endswith(":id/results"):
                match = re.search(r"共(\d+)项", node.get("content-desc", ""))
                if match:
                    return int(match.group(1))
        return None

    def check_keyword_latency(self):
        self.tap(description="主页")
        self.connect_app_server()
        timing = self.request_keyword("poster-keyword", "LatencyNew")
        first_tree = self.first_card("poster-keyword", timing, "M", prefix="KeywordNew M ")
        assert not any(title.startswith("KeywordOld") or title.startswith("AdultScope")
                       for title in self.titles(first_tree)), "Unexpected query/scope cards appeared"
        expected = set(self.fixture.sources) - {"src01"}
        since = timing[2]
        tree = self.wait_ui("poster-valid-hits-preserved",
                            lambda current: self.rendered_count(current) == len(expected), ordinary=True)
        self.wait_events(lambda records: expected.issubset({event["source"] for event in records
                                                          if event["kind"] == "search" and event["completed"]}),
                         "all searchable fixture source queries completed", since)
        # On an optimized app the server's sleep still runs after the client closes
        # its Call. Wait for its attempted write and record the real disconnect.
        records = self.wait_events(lambda records: any(event["kind"] == "detail" for event in records)
                                   and all(event["completed"] for event in records if event["kind"] == "detail"),
                                   "all deliberately slow optional detail writes attempted", since)
        details = [event for event in records if event["kind"] == "detail"]
        searches = [event for event in records if event["kind"] == "search"]
        assert details, "No optional empty-poster enrichment was attempted; fixture did not exercise this path"
        assert all(event["delay_s"] == self.fixture.detail_seconds for event in details), "Fixture detail delay was not applied"
        assert not any(event["source"] == "src01" for event in searches), "Disabled search source was queried"
        metric = self.metrics["poster-keyword"]
        first_observed = metric["first_visible_observed_s"]
        first_detail_ready = min(event["response_ready_s"] for event in details)
        outcomes = {source: [event["response"] for event in details if event["source"] == source]
                    for source in sorted({event["source"] for event in details})}
        metric.update({"search_source_count": len({event["source"] for event in searches}),
                       "expected_valid_hits": len(expected),
                       "rendered_valid_hits": self.rendered_count(tree),
                       "detail_request_count": len(details),
                       "deliberate_detail_delay_s": self.fixture.detail_seconds,
                       "first_detail_response_ready_latency_s": first_detail_ready - timing[0],
                       "first_visible_before_first_detail_response_ready": first_observed < first_detail_ready,
                       "detail_response_outcomes_by_source": outcomes,
                       "detail_disconnected_count": sum(event["response"] == "disconnected" for event in details),
                       "peak_processing_requests": max(event["active_requests"] for event in records if event["processing"]),
                       "valid_results_preserved_without_waiting_for_posters": first_observed < first_detail_ready
                                                                                and self.rendered_count(tree) == len(expected)})
        if not self.baseline:
            assert metric["first_visible_before_first_detail_response_ready"], "Valid hits still waited for optional slow detail responses"
            assert all(event["response"] == "disconnected" for event in details), "Timed-out optional detail HTTP Calls were not all cancelled"
        self.screenshot("poster-hits-after-late-details")
        self.record("Optional detail fixture: first visible %.3f s; all %d valid hits preserved; %d/%d detail writes disconnected" % (
            metric["first_card_latency_upper_s"], self.rendered_count(tree),
            metric["detail_disconnected_count"], len(details)))
        self.key(4)
        self.wait_ui("poster-final-home", lambda current: self.by_description(current, "主页") is not None,
                     ordinary=True)

    def write_result(self):
        super().write_result()
        target = self.output / "result.json"
        result = json.loads(target.read_text(encoding="utf-8"))
        result["fixture"]["deliberate_optional_detail_seconds"] = self.fixture.detail_seconds
        result["fixture"]["test_scope"] = "keyword-search optional poster enrichment only"
        result["fixture"].pop("delayed_first_source_seconds", None)
        target.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")


def fixture_self_test():
    fixture = PosterLatencyFixture()
    fixture.start()
    try:
        fixture.set_stage("poster-keyword")
        with urllib.request.urlopen("http://127.0.0.1:%d/api/src02?wd=LatencyNew" % fixture.port,
                                    timeout=3) as response:
            body = json.load(response)
        item = body["list"][0]
        assert item["vod_name"] == "KeywordNew M src02" and not item["vod_pic"] and not item["vod_play_url"]
        code, body, event, pause = fixture.response("/api/src02", {"ids": [item["vod_id"]]})
        assert code == 200 and pause == 8 and event["delayed"]
        assert body["list"][0]["vod_name"] == item["vod_name"]
        print("PASS: fast text-only search hit and deliberately 8-second optional detail route; no adb used", flush=True)
    finally:
        fixture.close()


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=pathlib.Path, default=pathlib.Path(__file__).resolve().parents[1]
                        / ".codex/session/qa/search-poster-latency")
    parser.add_argument("--baseline", action="store_true", help="Observe old installed app; tolerate waiting enrichment and sent detail responses")
    parser.add_argument("--fixture-self-test", action="store_true", help="Host-only validation; never access adb")
    parser.add_argument("--sources", type=int, default=18)
    parser.add_argument("--port", type=int, default=0)
    parser.add_argument("--detail-seconds", type=float, default=8)
    parser.add_argument("--wait-seconds", type=float, default=55)
    args = parser.parse_args()
    if args.fixture_self_test:
        fixture_self_test()
        return
    if not 2 <= args.sources <= 18 or not 8 <= args.detail_seconds <= 10 or not 30 <= args.wait_seconds <= 60:
        parser.error("Require sources 2..18, detail-seconds 8..10, wait-seconds 30..60")
    fixture = PosterLatencyFixture(args.port, args.sources, args.detail_seconds)
    try:
        PosterLatencyVerification(args.output.resolve(), fixture, args.wait_seconds,
                                  baseline=args.baseline).run()
    finally:
        if not fixture.thread.is_alive():
            fixture.server.server_close()


if __name__ == "__main__":
    main()
