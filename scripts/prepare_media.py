"""Fetch the pinned FongMi Media3 source. No credentials; no mutable branch refs."""
import argparse
import json
import os
import pathlib
import shutil
import urllib.request
import zipfile

REVISION = "3c2cbe8ac742c2fe15eff52f03eeb3b1b648848d"
ROOT = pathlib.Path(__file__).resolve().parents[1]


def prepare(destination):
    destination = destination.resolve()
    marker = destination / ".yingshicang-source.json"
    if marker.exists() and json.loads(marker.read_text())["revision"] == REVISION:
        print("Pinned media dependency already prepared:", destination)
        return
    if destination.exists():
        raise SystemExit("Destination exists without matching revision marker; choose another directory.")
    destination.parent.mkdir(parents=True, exist_ok=True)
    archive = destination.parent / ("media-" + REVISION + ".zip")
    url = f"https://codeload.github.com/FongMi/media/zip/{REVISION}"
    if not archive.exists():
        temporary = archive.with_suffix(".download")
        print("Downloading pinned media source (large archive):", url, flush=True)
        with urllib.request.urlopen(url, timeout=60) as response, temporary.open("wb") as output:
            shutil.copyfileobj(response, output, 1024 * 1024)
        os.replace(temporary, archive)
    with zipfile.ZipFile(archive) as z:
        for item in z.infolist():
            relative = pathlib.PurePosixPath(item.filename)
            # Strip archive root, validate paths, never materialize links.
            if len(relative.parts) < 2:
                continue
            target = destination.joinpath(*relative.parts[1:]).resolve()
            if destination not in target.parents:
                raise ValueError("Archive path escapes dependency root")
            if item.is_dir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                with z.open(item) as source, target.open("wb") as output:
                    shutil.copyfileobj(source, output, 1024 * 1024)
    marker.write_text(json.dumps({"repository": "https://github.com/FongMi/media", "revision": REVISION, "archive": url}, indent=2))
    print("Prepared:", destination)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--destination", type=pathlib.Path, default=ROOT / ".dependencies/media")
    prepare(parser.parse_args().destination)
