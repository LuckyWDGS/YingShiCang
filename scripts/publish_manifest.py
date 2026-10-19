"""Generate a version manifest after APK signing/upload. Never stores publishing secrets."""
import argparse
import hashlib
import json
import pathlib
import re
import subprocess
import urllib.parse


def main():
    p = argparse.ArgumentParser()
    p.add_argument("apk", type=pathlib.Path)
    p.add_argument("--aapt", required=True)
    p.add_argument("--url", required=True)
    p.add_argument("--flavor", choices=["leanback", "mobile"], required=True)
    p.add_argument("--notes", default="")
    p.add_argument("--output", type=pathlib.Path, required=True)
    p.add_argument("--allow-local-test", action="store_true")
    args = p.parse_args()
    url = urllib.parse.urlparse(args.url)
    if url.scheme != "https" and not (args.allow_local_test and url.hostname in {"127.0.0.1", "10.0.2.2", "localhost"}):
        p.error("Use an HTTPS APK download URL")
    text = subprocess.check_output([args.aapt, "dump", "badging", str(args.apk)], encoding="utf-8")
    info = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", text)
    sdk = re.search(r"sdkVersion:'(\d+)'", text)
    abi = re.search(r"native-code: (.+)", text)
    if not info or not sdk or not abi:
        p.error("Cannot read APK metadata")
    if info[1] != "com.yingshicang.player":
        p.error("Unexpected application package")
    # ``hashlib.file_digest`` was added in Python 3.11, while the build
    # instructions support Python 3.10.  Keep the manifest generator usable
    # on the documented minimum runtime instead of failing after a successful
    # APK inspection.
    digest = hashlib.sha256()
    with args.apk.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            digest.update(chunk)
    value = dict(schemaVersion=1, packageName=info[1], versionCode=int(info[2]), versionName=info[3],
                 flavor=args.flavor, minSdk=int(sdk[1]), abis=re.findall(r"'([^']+)'", abi[1]),
                 size=args.apk.stat().st_size, sha256=digest.hexdigest(), url=args.url, notes=args.notes)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temp = args.output.with_suffix(args.output.suffix + ".tmp")
    try:
        temp.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        temp.replace(args.output)
    finally:
        if temp.exists():
            temp.unlink()
    print(args.output)


if __name__ == "__main__":
    main()
