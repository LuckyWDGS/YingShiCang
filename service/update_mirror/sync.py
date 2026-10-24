#!/usr/bin/env python3
"""Mirror the latest YingShiCang GitHub Release for the app's update check and download.

GitHub stays the source of truth: latest.json holds the GitHub API answer byte for byte,
and every mirrored asset must match the size and SHA-256 digest GitHub declares for it.
A release becomes visible only after all of its files are verified (latest.json is
replaced last, by rename). Standard library only; runs as an unprivileged systemd user.
"""

import hashlib
import json
import os
import re
import shutil
import sys
import tempfile
import urllib.error
import urllib.request

API = "https://api.github.com/repos/LuckyWDGS/YingShiCang/releases/latest"
WEB_ROOT = "https://github.com/LuckyWDGS/YingShiCang/releases/download/"
ROOT = os.environ.get("YSC_MIRROR_ROOT", "/var/www/ysc-update")
STATE = os.environ.get("YSC_MIRROR_STATE", "/var/lib/ysc-update")
PUBLIC = os.path.join(ROOT, "ysc")
DOWNLOAD = os.path.join(PUBLIC, "download")
STAGING = os.path.join(ROOT, "staging")
KEEP = 2
MAX_RELEASE = 1_048_576
MAX_ASSET = 300_000_000
MAX_TOTAL = 1_000_000_000
TAG = re.compile(r"v?[0-9]+\.[0-9]+\.[0-9]+")
NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,199}")
DIGEST = re.compile(r"sha256:([a-f0-9]{64})")
USER_AGENT = "YingShiCang-Mirror/1"


def log(message):
    print(message, flush=True)


def request(url, headers=None, timeout=60):
    merged = {"User-Agent": USER_AGENT}
    merged.update(headers or {})
    return urllib.request.urlopen(urllib.request.Request(url, headers=merged), timeout=timeout)


def fetch_release():
    """Returns (raw bytes, etag) or None when GitHub reports no change."""
    headers = {"Accept": "application/vnd.github+json"}
    etag_file = os.path.join(STATE, "etag")
    current = os.path.join(PUBLIC, "latest.json")
    if os.path.exists(etag_file) and os.path.exists(current):
        with open(etag_file) as f:
            etag = f.read().strip()
        if etag:
            headers["If-None-Match"] = etag
    try:
        with request(API, headers) as response:
            raw = response.read(MAX_RELEASE + 1)
            if len(raw) > MAX_RELEASE:
                raise ValueError("release answer too large")
            return raw, response.headers.get("ETag", "")
    except urllib.error.HTTPError as error:
        if error.code == 304:
            return None
        raise


def assets_of(release):
    if release.get("draft") or release.get("prerelease"):
        raise ValueError("latest release is not a published release")
    tag = release.get("tag_name", "")
    if not TAG.fullmatch(tag):
        raise ValueError("invalid tag")
    wanted, total = [], 0
    for asset in release.get("assets", []):
        name = asset.get("name", "")
        if name != "release-manifest.json" and not name.endswith(".apk"):
            continue
        if not NAME.fullmatch(name) or ".." in name:
            raise ValueError("invalid asset name")
        if asset.get("state") != "uploaded":
            raise ValueError("asset not uploaded yet: " + name)
        url = asset.get("browser_download_url", "")
        if url != WEB_ROOT + tag + "/" + name:
            raise ValueError("asset url outside the release: " + name)
        size = asset.get("size")
        if not isinstance(size, int) or size <= 0 or size > MAX_ASSET:
            raise ValueError("invalid asset size: " + name)
        digest = DIGEST.fullmatch(str(asset.get("digest", "")).lower())
        if not digest:
            raise ValueError("asset has no SHA-256 digest: " + name)
        total += size
        wanted.append((name, url, size, digest.group(1)))
    if total > MAX_TOTAL:
        raise ValueError("release too large to mirror")
    if not any(name == "release-manifest.json" for name, *_ in wanted):
        raise ValueError("release has no release-manifest.json")
    return tag, wanted


def verified(path, size, sha):
    if not os.path.isfile(path) or os.path.getsize(path) != size:
        return False
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest() == sha


def download(url, path, size, sha):
    with request(url, timeout=120) as response, open(path, "wb") as out:
        received = 0
        while True:
            block = response.read(1 << 20)
            if not block:
                break
            received += len(block)
            if received > size:
                raise ValueError("asset larger than declared")
            out.write(block)
        out.flush()
        os.fsync(out.fileno())
    if not verified(path, size, sha):
        raise ValueError("asset size or SHA-256 mismatch: " + os.path.basename(path))


def mirror(tag, wanted):
    target = os.path.join(DOWNLOAD, tag)
    if all(verified(os.path.join(target, name), size, sha) for name, _, size, sha in wanted):
        return
    os.makedirs(STAGING, exist_ok=True)
    stage = tempfile.mkdtemp(prefix=tag + "-", dir=STAGING)
    try:
        for name, url, size, sha in wanted:
            existing = os.path.join(target, name)
            path = os.path.join(stage, name)
            if verified(existing, size, sha):
                shutil.copy2(existing, path)
            else:
                log("downloading " + name)
                download(url, path, size, sha)
            os.chmod(path, 0o644)
        os.chmod(stage, 0o755)
        if os.path.exists(target):
            shutil.rmtree(target)
        os.rename(stage, target)
    finally:
        if os.path.exists(stage):
            shutil.rmtree(stage, ignore_errors=True)


def publish(raw, etag):
    fd, temp = tempfile.mkstemp(prefix=".latest-", dir=PUBLIC)
    with os.fdopen(fd, "wb") as out:
        out.write(raw)
        out.flush()
        os.fsync(out.fileno())
    os.chmod(temp, 0o644)
    os.replace(temp, os.path.join(PUBLIC, "latest.json"))
    with open(os.path.join(STATE, "etag"), "w") as f:
        f.write(etag or "")


def prune(current):
    def key(tag):
        return tuple(int(part) for part in tag.lstrip("v").split("."))
    tags = sorted((t for t in os.listdir(DOWNLOAD) if TAG.fullmatch(t)), key=key, reverse=True)
    keep = set(tags[:KEEP]) | {current}
    for tag in tags:
        if tag not in keep:
            shutil.rmtree(os.path.join(DOWNLOAD, tag), ignore_errors=True)
            log("pruned " + tag)
    if os.path.isdir(STAGING):
        for name in os.listdir(STAGING):
            shutil.rmtree(os.path.join(STAGING, name), ignore_errors=True)


def main():
    os.makedirs(DOWNLOAD, exist_ok=True)
    os.makedirs(STATE, exist_ok=True)
    answer = fetch_release()
    if answer is None:
        log("unchanged")
        return 0
    raw, etag = answer
    tag, wanted = assets_of(json.loads(raw))
    mirror(tag, wanted)
    publish(raw, etag)
    prune(tag)
    log("published " + tag)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:  # systemd records the failure; the previous release stays published
        log("sync failed: %s: %s" % (type(error).__name__, error))
        sys.exit(1)
