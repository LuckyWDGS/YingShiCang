"""Check real current-config UI after fixtures, restoring original DB and preferences."""
import argparse
import json
import pathlib
import sys
import time

from verify_all_source_discovery import AllSourceVerification, ORDINARY_CHIP, TABS


class CurrentVerification(AllSourceVerification):
    def __init__(self, output):
        super().__init__(output, None, 55)
        self.observations = {}

    def ready(self, label, timeout=180):
        deadline = time.monotonic() + timeout
        while True:
            tree = self.dump(label)
            self.assert_ordinary(tree)
            if self.by_id(tree, "loadProgress") is None or time.monotonic() >= deadline:
                status = self.by_id(tree, "discoveryStatus")
                recycler = self.by_id(tree, "recycler")
                observation = {
                    "status": status.get("text", "") if status is not None else "",
                    "loading": self.by_id(tree, "loadProgress") is not None,
                    "visible_title_count": len(self.titles(tree)),
                    "list_description": recycler.get("content-desc", "") if recycler is not None else "",
                    "visible_source_badge_count": sum(bool(node.get("text")) for node in tree.iter("node")
                        if node.get("resource-id", "").endswith(":id/site")),
                }
                self.observations[label] = observation
                self.screenshot(label)
                print(label, json.dumps(observation, ensure_ascii=False), flush=True)
                return tree
            time.sleep(.5)

    def run(self):
        try:
            self.snapshot_app_data()
            self.command("logcat", "-b", "crash", "-c")
            self.launch()
            self.tap(description="主页")
            self.ready("current-home", 55)
            for tab in (4, 5):
                tree = self.tap(description=TABS[tab])
                if self.by_id(tree, "filterPanel") is None:
                    tree = self.tap(description=TABS[tab])
                self.tap(text="重置")
                label = "current-movie" if tab == 4 else "current-tv"
                tree = self.ready(label)
                ordinary = self.by_description(tree, ORDINARY_CHIP)
                assert ordinary is not None and ordinary.get("selected") == "true"
                assert not self.observations[label]["loading"], "Current config did not finish within 180 seconds"
                self.record(label + ": ordinary mode, completed real source scope and hidden successful count")
            crash = self.command("logcat", "-b", "crash", "-d").decode("utf-8", errors="replace")
            (self.output / "crash.log").write_text(crash, encoding="utf-8")
            assert "FATAL EXCEPTION" not in crash and "Fatal signal" not in crash
            self.record("Current-config probe crash buffer has no fatal exception/signal")
        except BaseException as error:
            self.error = str(error) if isinstance(error, AssertionError) else type(error).__name__
            raise
        finally:
            try:
                self.restore_app_data()
            finally:
                self.cleanup_remote()
                result = {"serial": "127.0.0.1:5555", "fixture_only": False,
                          "checks": self.steps, "observations": self.observations, "error": self.error,
                          "original_restored_and_hash_verified": self.restore_verified,
                          "limits": ["This probe records real availability; some external sources may fail or not support categories.",
                                     "It is not exhaustive playback or universal age classification verification."]}
                (self.output / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
            if self.restore_verified:
                self.launch()
                self.tap(description="主页")
                self.ready("restored-home", 55)


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    CurrentVerification(parser.parse_args().output.resolve()).run()
