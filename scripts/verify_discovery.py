"""Exercise discovery controls on the designated LDPlayer, restoring its saved filters."""
import argparse
import json
import pathlib
import re
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

from adb_target import verified_adb


SERIAL = "127.0.0.1:5555"
PACKAGE = "com.yingshicang.player"
ACTIVITY = PACKAGE + "/com.fongmi.android.tv.ui.activity.HomeActivity"
PREFERENCES = "shared_prefs/discovery_filters.xml"
TABS = ["主页", "热播电影", "热播剧集", "热播综艺", "电影筛选", "电视筛选", "电影榜单", "电视剧榜单"]
GROUPS = {
    1: ["排序", "地区"], 2: ["分类"], 3: ["分类"],
    4: ["内容", "类型", "地区", "排序", "年代"],
    5: ["内容", "类型", "电视剧形式", "综艺形式", "地区", "排序", "年代", "平台"],
    6: ["榜单"], 7: ["榜单"],
}
CHOICES = {
    1: ("排序", ["最新", "热度"]), 2: ("分类", ["国产剧", "综合"]),
    3: ("分类", ["国内", "综合"]), 4: ("类型", ["喜剧", "全部类型"]),
    5: ("类型", ["电视剧", "不限"]),
    6: ("榜单", ["一周口碑电影榜", "实时热门电影"]),
    7: ("榜单", ["华语口碑剧集榜", "实时热门电视"]),
}


class Verification:
    def __init__(self, output):
        self.output = output
        self.output.mkdir(parents=True, exist_ok=True)
        self.base = verified_adb(SERIAL)
        self.steps = []
        self.step = 0
        self.providers = {}

    def command(self, *args, input_bytes=None, check=True, timeout=30):
        return subprocess.run(self.base + list(args), input=input_bytes, stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, check=check, timeout=timeout).stdout

    def shell(self, *args, **kwargs):
        return self.command("shell", *args, **kwargs)

    def key(self, code):
        self.shell("input", "keyevent", str(code))
        time.sleep(.25)

    def dump(self, label="ui"):
        self.step += 1
        remote = "/sdcard/ysc-discovery-qa.xml"
        self.shell("rm", "-f", remote)
        self.shell("uiautomator", "dump", remote, timeout=25)
        raw = self.command("exec-out", "cat", remote).decode("utf-8")
        end = raw.rfind("</hierarchy>")
        if end < 0:
            raise AssertionError("Fresh UI hierarchy not available")
        raw = raw[:end + len("</hierarchy>")]
        (self.output / f"{self.step:03d}-{label}.xml").write_text(raw, encoding="utf-8")
        return ET.fromstring(raw)

    @staticmethod
    def bounds(node):
        values = list(map(int, re.findall(r"-?\d+", node.get("bounds", ""))))
        if len(values) != 4 or values[2] <= values[0] or values[3] <= values[1]:
            raise AssertionError("Control has no visible bounds")
        return values

    @staticmethod
    def by_id(tree, identifier):
        return next((node for node in tree.iter("node")
                     if node.get("resource-id", "").endswith(":id/" + identifier)), None)

    @staticmethod
    def by_description(tree, label):
        return next((node for node in tree.iter("node")
                     if node.get("content-desc") == label), None)

    def tap(self, description=None, identifier=None, text=None):
        tree = self.dump("before-tap")
        node = (self.by_id(tree, identifier) if identifier else self.by_description(tree, description))
        if text:
            node = next((n for n in tree.iter("node") if n.get("text") == text
                         and n.get("clickable") == "true"), None)
        if node is None:
            raise AssertionError(f"Visible target missing: {description or identifier or text}")
        left, top, right, bottom = self.bounds(node)
        self.shell("input", "tap", str((left + right) // 2), str((top + bottom) // 2))
        time.sleep(.35)
        return self.dump("after-tap")

    def record(self, label):
        self.steps.append(label)
        print("PASS:", label, flush=True)

    def screenshot(self, label):
        (self.output / (label + ".png")).write_bytes(self.command("exec-out", "screencap", "-p"))

    def launch(self):
        self.shell("am", "start", "-n", ACTIVITY)
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            tree = self.dump("launch")
            if self.by_description(tree, "主页") is not None:
                return tree
            time.sleep(.5)
        raise AssertionError("Home discovery categories did not appear")

    def check_panel(self, tree, visible):
        panel = self.by_id(tree, "filterPanel")
        assert (panel is not None) == visible, "Filter panel visibility mismatch"
        return panel

    def assert_groups(self, tab, tree):
        panel = self.check_panel(tree, True)
        assert self.by_id(tree, "actions") is None, "Home shortcuts still visible on a discovery tab"
        expected = set(GROUPS[tab])
        seen = set()
        for attempt in range(4):
            seen.update(n.get("text") for n in panel.iter("node") if n.get("text") in expected)
            if seen == expected:
                break
            left, top, right, bottom = self.bounds(panel)
            x = (left + right) // 2
            height = bottom - top
            self.shell("input", "swipe", str(x), str(top + height * 4 // 5),
                       str(x), str(top + height // 5), "350")
            tree = self.dump("panel-scroll")
            panel = self.check_panel(tree, True)
        assert seen == expected, f"{TABS[tab]} missing groups: {expected - seen}"
        # Put the first option row back in view before choosing a value.
        if attempt:
            left, top, right, bottom = self.bounds(panel)
            x = (left + right) // 2
            height = bottom - top
            self.shell("input", "swipe", str(x), str(top + height // 5),
                       str(x), str(top + height * 4 // 5), "350")
            tree = self.dump("panel-top")
        return tree

    def choose_distinct(self, tab, tree):
        group, labels = CHOICES[tab]
        candidates = [(group + "：" + label, self.by_description(tree, group + "：" + label))
                      for label in labels]
        description = next((desc for desc, node in candidates if node is not None
                            and node.get("selected") != "true"), None)
        assert description, "No distinct visible filter choice"
        tree = self.tap(description=description)
        selected = self.by_description(tree, description)
        assert selected is not None and selected.get("selected") == "true", "One tap did not select option"
        self.record(TABS[tab] + " single-tap option: " + description)
        return description

    def wait_provider(self, label):
        deadline = time.monotonic() + 24
        while True:
            tree = self.dump(label)
            if self.by_id(tree, "loadProgress") is None or time.monotonic() >= deadline:
                status = self.by_id(tree, "discoveryStatus")
                titles = [n.get("text") for n in tree.iter("node")
                          if n.get("resource-id", "").endswith(":id/name") and n.get("text")]
                recycler = self.by_id(tree, "recycler")
                return {"status": status.get("text", "") if status is not None else "",
                        "titles": titles, "count": recycler.get("content-desc", "") if recycler is not None else "",
                        "loading": self.by_id(tree, "loadProgress") is not None}
            time.sleep(.5)

    def focus_tab(self, tab):
        for _ in range(12):
            tree = self.dump("focus-navigation")
            focused = next((n for n in tree.iter("node") if n.get("focused") == "true"), None)
            description = focused.get("content-desc", "") if focused is not None else ""
            if description in TABS:
                current = TABS.index(description)
                if current == tab:
                    return tree
                self.key(22 if current < tab else 21)
            elif focused is not None and focused.get("resource-id", "").endswith(":id/title"):
                self.key(20)
            else:
                self.key(19)
        raise AssertionError("Could not focus requested category with DPAD")

    def snapshot_preferences(self):
        # Verify run-as before touching UI so preferences can always be restored.
        self.shell("run-as", PACKAGE, "id")
        self.shell("am", "force-stop", PACKAGE)
        exists = self.shell("run-as", PACKAGE, "sh", "-c",
                            "'test -f " + PREFERENCES + " && echo present'", check=False).strip() == b"present"
        original = self.command("exec-out", "run-as", PACKAGE, "cat", PREFERENCES) if exists else None
        if original is not None:
            (self.output / "original-discovery-preferences.xml").write_bytes(original)
        return original

    def restore_preferences(self, original):
        self.shell("am", "force-stop", PACKAGE)
        if original is None:
            self.shell("run-as", PACKAGE, "rm", "-f", PREFERENCES, PREFERENCES + ".bak")
        else:
            # Some adb versions do not reliably forward stdin through exec-out/run-as.
            # Copy a scoped device file as the app UID instead of risking lost preferences.
            remote = "/data/local/tmp/ysc-discovery-preference-restore.xml"
            try:
                with tempfile.TemporaryDirectory(prefix="ysc-discovery-") as temporary:
                    local = pathlib.Path(temporary) / "preferences.xml"
                    local.write_bytes(original)
                    self.command("push", str(local), remote)
                    self.shell("run-as", PACKAGE, "cp", remote, PREFERENCES)
            finally:
                self.shell("rm", "-f", remote)
            restored = self.command("exec-out", "run-as", PACKAGE, "cat", PREFERENCES)
            assert restored == original, "Preference restoration failed"
            self.shell("run-as", PACKAGE, "rm", "-f", PREFERENCES + ".bak")
        self.launch()
        tree = self.tap(description="主页")
        self.check_panel(tree, False)
        self.screenshot("restored-home")
        self.record("Original discovery preferences restored; Home left active")

    def run(self):
        original = self.snapshot_preferences()
        self.command("logcat", "-b", "crash", "-c")
        try:
            self.launch()
            self.tap(description="主页")
            chosen = {}
            for tab in range(1, 8):
                tree = self.tap(description=TABS[tab])
                active = self.by_description(tree, TABS[tab])
                assert active is not None and active.get("selected") == "true", "One tap did not activate category"
                tree = self.assert_groups(tab, tree)
                before = self.wait_provider("provider-before")
                tree = self.dump("before-option")
                chosen[tab] = self.choose_distinct(tab, tree)
                after = self.wait_provider("provider-after")
                self.providers[tab] = {"before": before, "after": after,
                                       "visible_titles_changed": before["titles"] != after["titles"]}
                print("PROVIDER:", TABS[tab], after["status"],
                      "visible_titles_changed=" + str(self.providers[tab]["visible_titles_changed"]), flush=True)
                self.screenshot(f"tab-{tab}")
                tree = self.tap(text="收起")
                self.check_panel(tree, False)
                tree = self.tap(description=TABS[tab])
                self.check_panel(tree, True)
                self.key(4)
                tree = self.dump("back-collapse")
                self.check_panel(tree, False)
                assert self.by_description(tree, TABS[tab]).get("selected") == "true"
                self.record(TABS[tab] + " correct groups, collapse/reopen and Back")

            # apply() is flushed when the process is stopped; allow its worker to settle first.
            time.sleep(.5)
            current = self.command("exec-out", "run-as", PACKAGE, "cat", PREFERENCES)
            saved_keys = {node.get("name") for node in ET.fromstring(current).findall("string")}
            assert all("tab_" + str(tab) in saved_keys for tab in chosen), "Saved state missing a tab"
            self.shell("am", "force-stop", PACKAGE)
            self.launch()
            for tab, description in chosen.items():
                tree = self.tap(description=TABS[tab])
                selected = self.by_description(tree, description)
                assert selected is not None and selected.get("selected") == "true", "Selection not restored after restart"
            self.record("All seven independent filter choices persist after restart")

            self.focus_tab(7)
            self.key(20)
            tree = self.dump("focus-option")
            focused = next((n for n in tree.iter("node") if n.get("focused") == "true"), None)
            assert focused is not None and focused.get("content-desc") == chosen[7], "DPAD_DOWN missed selected option"
            self.key(22)
            tree = self.dump("focus-row-right")
            focused = next((n for n in tree.iter("node") if n.get("focused") == "true"), None)
            assert focused is not None and focused.get("content-desc", "").startswith("榜单："), "DPAD_RIGHT left filter row"
            self.key(19)
            tree = self.dump("focus-tab-up")
            assert self.by_description(tree, TABS[7]).get("focused") == "true", "DPAD_UP missed active category"
            self.key(4)
            tree = self.dump("focus-back")
            self.check_panel(tree, False)
            assert self.by_description(tree, TABS[7]).get("focused") == "true", "Back did not restore category focus"
            self.record("DPAD category → selected option, row navigation, Up and Back focus")
            crash = self.command("logcat", "-b", "crash", "-d").decode("utf-8", errors="replace")
            (self.output / "crash.log").write_text(crash, encoding="utf-8")
            assert "FATAL EXCEPTION" not in crash and "Fatal signal" not in crash, "Crash buffer contains a crash"
            self.record("Crash buffer empty")
        finally:
            self.restore_preferences(original)
            (self.output / "result.json").write_text(json.dumps({"serial": SERIAL, "checks": self.steps,
                                                               "providers": self.providers},
                                                        ensure_ascii=False, indent=2), encoding="utf-8")

    def run_bottom_row(self):
        original = self.snapshot_preferences()
        try:
            self.launch()
            self.tap(description=TABS[5])
            before = self.wait_provider("tv-platform-before")
            tree = self.dump("tv-platform-panel")
            panel = self.check_panel(tree, True)
            left, top, right, bottom = self.bounds(panel)
            x = (left + right) // 2
            height = bottom - top
            self.shell("input", "swipe", str(x), str(top + height * 4 // 5),
                       str(x), str(top + height // 5), "350")
            tree = self.dump("tv-platform-scrolled")
            description = "平台：Netflix"
            if self.by_description(tree, description).get("selected") == "true":
                description = "平台：HBO"
            tree = self.tap(description=description)
            assert self.by_description(tree, description).get("selected") == "true"
            after = self.wait_provider("tv-platform-after")
            self.providers[5] = {"before": before, "after": after,
                                 "visible_titles_changed": before["titles"] != after["titles"]}
            assert after["titles"] and not after["loading"] and "失败" not in after["status"], "Platform filter returned no live data"
            assert before["titles"] != after["titles"], "Platform filter did not change visible titles"
            self.record(description + " bottom-row single tap; live provider titles changed")
            self.screenshot("tv-platform-scrolled")
            self.shell("am", "force-stop", PACKAGE)
            self.launch()
            tree = self.tap(description=TABS[5])
            assert self.by_description(tree, description).get("selected") == "true"
            self.record("Bottom-row platform selection persisted after restart")
        finally:
            self.restore_preferences(original)
            (self.output / "result.json").write_text(json.dumps({"serial": SERIAL, "checks": self.steps,
                                                               "providers": self.providers},
                                                        ensure_ascii=False, indent=2), encoding="utf-8")


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=pathlib.Path,
                        default=pathlib.Path(__file__).resolve().parents[1] / ".codex/session/qa/discovery")
    parser.add_argument("--bottom-row-only", action="store_true",
                        help="Verify the scrollable TV platform row and live title changes")
    args = parser.parse_args()
    verification = Verification(args.output)
    if args.bottom_row_only:
        verification.run_bottom_row()
    else:
        verification.run()


if __name__ == "__main__":
    main()
