"""Verify the two signed TV APKs and write a GitHub Release aggregate manifest.

This only reads APKs and writes the requested local JSON file. It does not build,
rename APKs, upload assets or publish a release. Python 3.10+ and Android SDK
build-tools (aapt and apksigner.jar) are required.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import zipfile


APPLICATION_ID = "com.yingshicang.player"
REPOSITORY = "https://github.com/LuckyWDGS/YingShiCang"
RELEASE_CERTIFICATE = "38f4139f496189ab3ff953334a7952c3cc30e48aa5c47f65b6b5904db0d2d81a"
MAX_APK_BYTES = 300_000_000
ABIS = ("arm64-v8a", "armeabi-v7a")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def run(*command):
    result = subprocess.run([str(part) for part in command], capture_output=True,
                            text=True, encoding="utf-8", errors="replace", timeout=180)
    if result.returncode:
        detail = (result.stderr or result.stdout).strip()
        raise ValueError(f"{Path(command[0]).name} failed: {detail}")
    return result.stdout


def tools(args):
    build_tools = None
    if not args.aapt or not args.apksigner_jar:
        sdk = args.sdk or os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
        require(sdk, "Set ANDROID_HOME/ANDROID_SDK_ROOT or pass --sdk (or both tool paths)")
        directory = Path(sdk).expanduser() / "build-tools"
        versions = [path for path in directory.iterdir()
                    if path.is_dir() and re.fullmatch(r"[0-9]+(?:\.[0-9]+)+", path.name)]
        require(versions, f"No stable SDK build-tools found in {directory}")
        build_tools = max(versions, key=lambda path: tuple(int(part) for part in path.name.split(".")))
    aapt = args.aapt or build_tools / ("aapt.exe" if os.name == "nt" else "aapt")
    signer = args.apksigner_jar or build_tools / "lib" / "apksigner.jar"
    java_home = os.environ.get("JAVA_HOME")
    java = args.java or (Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java")
                         if java_home else shutil.which("java"))
    require(java, "Set JAVA_HOME, put java on PATH, or pass --java")
    for path in (aapt, signer, java):
        require(Path(path).is_file(), f"Tool not found: {path}")
    return aapt, signer, java


def sha256(path):
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def inspect(apk, abi, aapt, signer, java):
    require(apk.is_file(), f"APK not found: {apk}")
    require(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,199}", apk.name)
            and ".." not in apk.name and apk.name.endswith(".apk"),
            f"Use an ASCII Release asset name ending in .apk: {apk.name}")
    before = apk.stat()
    require(0 < before.st_size <= MAX_APK_BYTES, f"APK must be 1..{MAX_APK_BYTES} bytes: {apk.name}")
    badging = run(aapt, "dump", "badging", apk)
    package = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'",
                        badging, re.MULTILINE)
    sdk = re.search(r"^sdkVersion:'([0-9]+)'", badging, re.MULTILINE)
    native = re.search(r"^native-code: (.+)$", badging, re.MULTILINE)
    require(package and sdk and native, f"Cannot read APK package, version, minSdk or ABI: {apk.name}")
    require(package[1] == APPLICATION_ID, f"Unexpected package name: {package[1]}")
    code, version, minimum = int(package[2]), package[3], int(sdk[1])
    require(0 < code <= 2_147_483_647, f"Invalid versionCode in {apk.name}")
    require(re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version),
            f"Use a formal x.y.z versionName, without v or prerelease suffix: {version}")
    require(minimum >= 23, f"Unsupported minSdk in {apk.name}: {minimum}")
    require("application-debuggable" not in badging, f"Debuggable APK cannot be released: {apk.name}")
    require(re.search(r"^leanback-launchable-activity:", badging, re.MULTILINE),
            f"APK does not expose the TV launcher: {apk.name}")
    require(re.findall(r"'([^']+)'", native[1]) == [abi],
            f"Expected only {abi} in aapt native-code: {apk.name}")
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        libraries = {name.split("/")[1] for name in names
                     if name.startswith("lib/") and name.endswith(".so") and len(name.split("/")) == 3}
        require(libraries == {abi}, f"Expected only {abi} native libraries: {apk.name}")
        require(f"assets/chaquopy/stdlib-{abi}.imy" in names,
                f"Missing packaged Python stdlib for {abi}: {apk.name}")
    signature = run(java, "-jar", signer, "verify", "--verbose", "--print-certs", apk)
    require("Verified using v2 scheme (APK Signature Scheme v2): true" in signature,
            f"APK v2 signature verification failed: {apk.name}")
    # SDK versions print either "Signer #1" or a scheme label such as "V2 Signer:".
    certificates = re.findall(r"^.+ certificate SHA-256 digest: ([a-fA-F0-9]{64})$",
                              signature, re.MULTILINE)
    require(re.search(r"^Number of signers: 1$", signature, re.MULTILINE)
            and {item.lower() for item in certificates} == {RELEASE_CERTIFICATE},
            f"APK must use the existing YingShiCang release certificate: {apk.name}")
    digest = sha256(apk)
    after = apk.stat()
    require((before.st_size, before.st_mtime_ns) == (after.st_size, after.st_mtime_ns),
            f"APK changed during verification: {apk.name}")
    entry = dict(abi=abi, file=apk.name, bytes=after.st_size, sha256=digest,
                 certificate_sha256=certificates[0].lower(), signatureVerified=True, debuggable=False)
    return (version, code, minimum), entry


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--arm64", type=Path, required=True, help="Signed TV arm64-v8a APK")
    parser.add_argument("--armv7", type=Path, required=True, help="Signed TV armeabi-v7a APK")
    parser.add_argument("--output", type=Path, required=True, help="Destination named release-manifest.json")
    parser.add_argument("--sdk", type=Path, help="Android SDK root; defaults to ANDROID_HOME/ANDROID_SDK_ROOT")
    parser.add_argument("--aapt", type=Path, help="Explicit aapt executable (optional)")
    parser.add_argument("--apksigner-jar", type=Path, help="Explicit SDK lib/apksigner.jar (optional)")
    parser.add_argument("--java", type=Path, help="Explicit Java executable (optional)")
    parser.add_argument("--overwrite", action="store_true", help="Replace an existing local manifest")
    args = parser.parse_args()
    temporary = None
    try:
        require(args.output.name == "release-manifest.json", "Output must be named release-manifest.json")
        require(args.overwrite or not args.output.exists(), "Output exists; use a new directory or --overwrite")
        require(args.arm64.resolve() != args.armv7.resolve(), "Provide two different APK files")
        require(args.arm64.name != args.armv7.name, "The two Release APK filenames must differ")
        aapt, signer, java = tools(args)
        packages = [inspect(apk.absolute(), abi, aapt, signer, java)
                    for apk, abi in zip((args.arm64, args.armv7), ABIS)]
        require(packages[0][0] == packages[1][0], "Both APKs must have the same versionName, versionCode and minSdk")
        version, code, minimum = packages[0][0]
        value = dict(schemaVersion=1, versionName=version, versionCode=code,
                     applicationId=APPLICATION_ID, flavor="leanback", minSdk=minimum,
                     packages=[entry for _, entry in packages],
                     releaseUrl=f"{REPOSITORY}/releases/tag/v{version}", publishedToRemote=False)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", newline="\n",
                                         dir=args.output.parent, prefix="manifest-", suffix=".tmp",
                                         delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(json.dumps(value, ensure_ascii=False, indent=2) + "\n")
        require(args.overwrite or not args.output.exists(), "Output appeared during verification; refusing to replace it")
        temporary.replace(args.output)
        print(f"Verified TV {version} / code {code}, minSdk {minimum}, both ARM ABIs")
        print(args.output.resolve())
        print(f"Upload this manifest and both original APKs to a formal v{version} Release; mark it Latest.")
    except (ValueError, OSError, subprocess.TimeoutExpired, zipfile.BadZipFile) as error:
        parser.exit(1, f"error: {error}\n")
    finally:
        if temporary is not None and temporary.exists():
            temporary.unlink()


if __name__ == "__main__":
    main()
