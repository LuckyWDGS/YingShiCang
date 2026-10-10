"""Observe real configured movie/TV 18+ availability without exporting content.

Root operator only. Uses guarded authorized LDPlayer helpers and preserves every
database/preference byte. No fixture configuration, screenshot, title, source URL,
raw hierarchy or crash log is saved. Success can be empty when providers have no
explicit adult metadata; this is an observation, not an availability assertion.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

from verify_all_source_discovery import (
    ADULT_CHIP, DISCOVERY_PREF, ORDINARY_CHIP, AllSourceVerification,
)
from verify_discovery import PACKAGE, SERIAL, TABS
from verify_adult_discovery import AdultVerification

DIAGNOSTIC_TAG = "DiscoverySummary"
DIAGNOSTIC_COUNTS = {
    "configured_sources", "initialized_sources", "failed_sources", "unsupported_sources",
    "declared_categories", "adult_categories", "explicit_queries", "fallback_queries",
    "item_kind_required_queries", "round_raw_items", "round_raw_adult_item_evidence",
    "round_accepted_items", "round_rejected_items", "round_unknown_kind_rejected", "visible_items",
}
DIAGNOSTIC_ERRORS = {
    "TimeoutException", "SocketTimeoutException", "InterruptedIOException", "IOException",
    "CancellationException", "RejectedExecutionException", "ExecutionException", "RuntimeException", "Exception",
}
DIAGNOSTIC_KEYS = DIAGNOSTIC_COUNTS | {"tab", "adult", "round", "retry", "more", "round_errors"}


def diagnostic_record(line):
    """Accept only the debug tag's exact count/boolean/error-family JSON schema."""
    match = re.fullmatch(r"(?:D/DiscoverySummary\(\s*\d+\):|\d+(?:\.\d+)?\s+\d+\s+\d+\s+D\s+DiscoverySummary\s*:)\s*(\{.*\})\s*", line.strip())
    if match is None:
        return None
    try:
        data = json.loads(match[1])
    except (json.JSONDecodeError, TypeError):
        return None
    if not isinstance(data, dict) or set(data) != DIAGNOSTIC_KEYS:
        return None
    if type(data["tab"]) is not int or data["tab"] not in (4, 5):
        return None
    if any(type(data[key]) is not bool for key in ("adult", "retry", "more")):
        return None
    if type(data["round"]) is not int or not 0 <= data["round"] <= 2**63 - 1:
        return None
    if any(type(data[key]) is not int or not 0 <= data[key] <= 2**31 - 1 for key in DIAGNOSTIC_COUNTS):
        return None
    errors = data["round_errors"]
    if not isinstance(errors, dict) or not set(errors).issubset(DIAGNOSTIC_ERRORS):
        return None
    if any(type(count) is not int or not 0 <= count <= 2**31 - 1 for count in errors.values()):
        return None
    return data

STATUS_TEXT = {
    "", "暂无符合条件的站源内容", "站源未提供符合所选条件的分类或标签",
    "部分站源不支持所选筛选条件", "部分站源加载失败",
    "部分站源加载失败，已保留当前结果", "暂未找到符合条件的18+内容",
    "已加载站源暂无符合条件的18+内容", "当前站源暂无符合条件的18+内容",
    "暂未找到符合条件的18+内容，部分站源加载失败",
    "已加载站源暂无符合条件的18+内容，部分站源加载失败",
    "当前站源暂无符合条件的18+内容，部分站源加载失败",
    "正在切换站源配置…", "片单加载失败", "暂无符合条件的片单",
    "暂无符合条件的片单，部分片单加载失败", "部分片单加载失败",
}


class ActualCurrentAdultProbe(AllSourceVerification):
    def __init__(self, output, wait_seconds=180, diagnostics=False):
        assert SERIAL == "127.0.0.1:5555"
        super().__init__(output, None, wait_seconds)
        self.observations = {}
        self.ui_counters = []
        self.final_home = False
        self.fatal_crash = None
        self.diagnostics = diagnostics
        self.diagnostic_summaries = []
        self.diagnostic_seen = set()
        self.diagnostic_baseline = set()
        self.diagnostic_rejected_records = 0
        self.dump_tool_retries = []
        self.crash_counts = None

    def capture_diagnostics(self, label, *, baseline=False):
        if not self.diagnostics:
            return
        # Fixed tag filter; raw log bytes remain in memory and are never printed/saved.
        raw = self.command("logcat", "-d", "-v", "epoch", "-s", DIAGNOSTIC_TAG + ":D", "*:S")
        for line in raw.decode("utf-8", errors="replace").splitlines():
            data = diagnostic_record(line)
            if data is None:
                if "DiscoverySummary" in line:
                    self.diagnostic_rejected_records += 1
                continue
            # Timestamp/PID prefix separates equal counters from different runners.
            # Only a digest is kept, and neither the prefix nor raw line is exported.
            identity = hashlib.sha256(line.encode("utf-8")).hexdigest()
            if baseline:
                self.diagnostic_baseline.add(identity)
            elif identity not in self.diagnostic_seen and identity not in self.diagnostic_baseline:
                self.diagnostic_seen.add(identity)
                self.diagnostic_summaries.append({"observed_at_label": label, **data})

    @staticmethod
    def tool_dump_error(error):
        return (isinstance(error, subprocess.CalledProcessError) and error.returncode == 137
                and isinstance(error.cmd, (list, tuple))
                and list(error.cmd[-4:]) == ["shell", "uiautomator", "dump", "/sdcard/ysc-current-adult-observation.xml"])

    def classify_crash(self):
        crash = self.command("logcat", "-b", "crash", "-d")
        blocks = AdultVerification.fatal_blocks(crash)
        known_tool_count = sum(AdultVerification.dump_npe_block(block) for block in blocks)
        native_fatal = b"Fatal signal" in crash
        self.fatal_crash = native_fatal or len(blocks) != len(self.dump_tool_retries) or known_tool_count != len(blocks)
        self.crash_counts = {"confirmed_dump_tool_npe_count": known_tool_count,
                             "other_fatal_count": len(blocks) - known_tool_count,
                             "native_fatal_signal": native_fatal}
        assert not self.fatal_crash, "Fatal product/other crash occurred during real-source observation"

    @staticmethod
    def sanitized(tree):
        recycler = AllSourceVerification.by_id(tree, "recycler")
        match = re.search(r"片单，共(\d+)部", recycler.get("content-desc", "") if recycler is not None else "")
        status = AllSourceVerification.by_id(tree, "discoveryStatus")
        raw_status = status.get("text", "") if status is not None else ""
        retry = AllSourceVerification.by_id(tree, "discoveryRetry")
        raw_action = retry.get("text", "") if retry is not None else ""
        ordinary = AllSourceVerification.by_description(tree, ORDINARY_CHIP)
        adult = AllSourceVerification.by_description(tree, ADULT_CHIP)
        active = next((index for index, label in enumerate(TABS)
                       if (node := AllSourceVerification.by_description(tree, label)) is not None
                       and node.get("selected") == "true"), None)
        return {"active_tab_index": active, "list_count": int(match[1]) if match else None,
                "visible_card_count": sum(node.get("resource-id", "").endswith(":id/name") and bool(node.get("text")) for node in tree.iter("node")),
                "status": raw_status if raw_status in STATUS_TEXT else "redacted_unrecognized_status",
                "action": raw_action if raw_action in ("", "重试", "继续查找") else "redacted_unrecognized_action",
                "panel_open": AllSourceVerification.by_id(tree, "filterPanel") is not None,
                "ordinary_selected": ordinary is not None and ordinary.get("selected") == "true",
                "adult_selected": adult is not None and adult.get("selected") == "true"}

    def dump(self, label="ui"):
        # Do not call Verification.dump: it intentionally persists XML including titles.
        self.step += 1
        remote = "/sdcard/ysc-current-adult-observation.xml"
        for attempt in range(4):
            before = self.command("logcat", "-b", "crash", "-d")
            self.shell("rm", "-f", remote)
            try:
                self.shell("uiautomator", "dump", remote, timeout=25)
                raw = self.command("exec-out", "cat", remote).decode("utf-8")
                end = raw.rfind("</hierarchy>")
                if end < 0:
                    raise AssertionError("Fresh UI hierarchy not available")
                tree = ET.fromstring(raw[:end + len("</hierarchy>")])
                break
            except AssertionError as error:
                if str(error) != "Fresh UI hierarchy not available" or attempt == 3:
                    raise
                time.sleep(.3)
            except subprocess.CalledProcessError as error:
                if not self.tool_dump_error(error) or attempt == 3:
                    raise
                after = self.command("logcat", "-b", "crash", "-d")
                if not AdultVerification.fresh_dump_npe(before, after):
                    raise
                self.dump_tool_retries.append({"label": label, "retry": attempt + 1,
                                               "exit_code": 137, "exact_tool_npe_verified": True})
                time.sleep(.3)
            finally:
                self.shell("rm", "-f", remote, check=False)
        summary = self.sanitized(tree)
        summary.update(label=label, sample=self.step)
        self.ui_counters.append(summary)
        self.capture_diagnostics(label)
        labels = {node.get("text", "") for node in tree.iter("node")}
        assert not labels.intersection({"重置", "收起"}), "Removed header action remains visible"
        assert self.by_id(tree, "loadProgress") is None, "Removed discovery spinner remains visible"
        assert self.by_id(tree, "toTop") is None, "Removed return-top action remains visible"
        self.assert_no_success_count(tree)
        return tree

    def screenshot(self, label):
        raise AssertionError("Real content screenshot export is disabled in this observer")

    def default_discovery_preferences(self):
        assert self.original is not None
        data = self.pref_xml(self.original.get("shared_prefs/" + DISCOVERY_PREF),
                             {"tab_4": "{}", "tab_5": "{}"})
        with tempfile.TemporaryDirectory(prefix="ysc-current-adult-default-") as directory:
            local = pathlib.Path(directory) / "discovery.xml"
            local.write_bytes(data)
            self.shell("run-as", PACKAGE, "rm", "-f", "shared_prefs/" + DISCOVERY_PREF + ".bak")
            self.install_file(local, "shared_prefs/" + DISCOVERY_PREF)

    def observe(self, label, *, tab, adult, timeout=None):
        timeout = self.wait_seconds if timeout is None else timeout
        started = time.monotonic()
        deadline = started + timeout
        previous, stable_since = None, started
        terminal = False
        while True:
            tree = self.dump(label)
            current = self.sanitized(tree)
            assert current["active_tab_index"] == tab, "Unexpected active discovery category"
            if tab in (4, 5):
                assert current["panel_open"], "Filter panel must stay open while observing to prevent viewport auto-pagination"
                assert current["adult_selected"] == adult and current["ordinary_selected"] != adult, "Content selection does not match the explicit observation scope"
            signature = (current["list_count"], current["status"], current["action"])
            now = time.monotonic()
            if signature != previous:
                stable_since = now
                previous = signature
            # Status is populated only by a completed source round. Intermediate
            # automatic 18+ empty rounds are silent. Blank success has no completion
            # signal, so observe until the bounded deadline and do not call it done.
            terminal = bool(current["status"] and current["status"] != "redacted_unrecognized_status"
                            and now - stable_since >= 5)
            if terminal or now >= deadline:
                current.update(elapsed_seconds=round(now - started, 3), terminal_status_observed=terminal,
                               observation_deadline_reached=not terminal)
                self.observations[label] = current
                print("OBSERVATION:", label, json.dumps(current, ensure_ascii=False), flush=True)
                return tree
            time.sleep(.7)

    def run(self):
        try:
            self.snapshot_app_data()
            self.command("logcat", "-b", "crash", "-c")
            self.capture_diagnostics("before-original-launch", baseline=True)
            self.default_discovery_preferences()
            self.launch()
            self.tap(description=TABS[0])
            for tab in (4, 5):
                self.tap(description=TABS[0])
                tree = self.tap(description=TABS[tab])
                assert self.by_id(tree, "filterPanel") is None, "First category click opened filters"
                self.tap(description=TABS[tab])
                label = "current-movie" if tab == 4 else "current-tv"
                self.observe(label + "-ordinary", tab=tab, adult=False)
                self.tap(description=ADULT_CHIP)
                tree = self.observe(label + "-adult", tab=tab, adult=True)
                action = self.by_id(tree, "discoveryRetry")
                if action is not None and action.get("text") == "继续查找":
                    self.tap(identifier="discoveryRetry")
                    self.observe(label + "-adult-one-continue", tab=tab, adult=True)
                self.record(label + ": real original configured sources observed for ordinary and explicit 18+; no content exported")
                self.tap(description=TABS[0])
            self.classify_crash()
            self.record("Real-source observation has no product/other fatal exception or signal; any tool NPE is exactly verified")
        except BaseException as error:
            # Assertions are this script's fixed labels; external command errors may
            # carry private arguments or output, so export only their class name.
            self.error = str(error) if isinstance(error, AssertionError) else type(error).__name__
            raise
        finally:
            try:
                self.restore_app_data()
            finally:
                self.cleanup_remote()
                if self.restore_verified:
                    self.launch()
                    tree = self.tap(description=TABS[0])
                    self.final_home = self.sanitized(tree)["active_tab_index"] == 0
                    assert self.final_home, "Original configuration Home was not restored"
                    self.classify_crash()
                    self.record("Original-data SHA256 restoration verified; original configuration Home left active")
                result = {"serial": SERIAL, "fixture_only": False, "config_changed": False,
                          "checks": self.steps, "observations": self.observations,
                          "sanitized_ui_samples": self.ui_counters, "error": self.error,
                          "fatal_crash": self.fatal_crash, "final_home": self.final_home,
                          "crash_counts": self.crash_counts, "dump_tool_retries": self.dump_tool_retries,
                          "diagnostics_enabled": self.diagnostics,
                          "diagnostic_tag": DIAGNOSTIC_TAG if self.diagnostics else None,
                          "diagnostic_summaries": self.diagnostic_summaries,
                          "diagnostic_schema_rejected_records": self.diagnostic_rejected_records,
                          "original_file_count": self.check_details.get("original_file_count"),
                          "original_restored_and_hash_verified": self.restore_verified,
                          "limits": ["Availability observations do not require nonempty adult results; sources can lack declared labels or have genuine network/plugin failures.",
                                     "No raw UI XML, titles, images, source identifiers, URLs or crash logs exported.",
                                     "Silent success has no exposed final-round signal; deadline observations are not a claim of complete source enumeration.",
                                     "At most one manual continue per tab; no playback, posters, full-catalog classification or phone performance claim."]}
                (self.output / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--wait-seconds", type=float, default=180)
    parser.add_argument("--diagnostics", action="store_true", help="Capture strict count-only DiscoverySummary debug records; never export raw logs")
    args = parser.parse_args()
    ActualCurrentAdultProbe(args.output.resolve(), args.wait_seconds, args.diagnostics).run()
