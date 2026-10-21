"""List, download and de-obfuscate diagnostic logs that devices uploaded to OSS.

Read-only: uses the ysc-log-reader RAM user whose key is in OSS_ACCESS_KEY_ID /
OSS_ACCESS_KEY_SECRET (process environment first, then the Windows user environment,
so a fresh `setx` works without restarting). Credentials are only handed to the SDK.
Downloads go to .codex/session/logs/, which Git ignores.

  python scripts/fetch_logs.py latest [-n 5] [--download]
  python scripts/fetch_logs.py device K7Q2-9MZD [-n 5] [--download]
  python scripts/fetch_logs.py report <report id>
"""
import argparse
import datetime
import json
import os
import pathlib
import re
import subprocess
import sys

ENDPOINT = "https://oss-cn-beijing.aliyuncs.com"
BUCKET = "ying-shi-cang"
ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / ".codex" / "session" / "logs"
DEVICE = re.compile(r"[0-9A-Z]{4}-[0-9A-Z]{4}")
REPORT = re.compile(r"[0-9a-f-]{36}")
LOG = re.compile(r"logs/(?P<device>[^/]+)/(?P<day>\d{4}-\d{2}-\d{2})/(?P<version>[^_/]+)_(?P<batch>[0-9a-f-]{36})\.log")

sys.stdout.reconfigure(encoding="utf-8")
sys.stderr.reconfigure(encoding="utf-8")


def shown(path):
    try:
        return path.relative_to(ROOT)
    except ValueError:
        return path


def credential(name):
    value = os.environ.get(name)
    if value or os.name != "nt":
        return value
    import winreg
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, "Environment") as key:
            return winreg.QueryValueEx(key, name)[0]
    except OSError:
        return None


def open_bucket():
    try:
        import oss2
    except ImportError:
        sys.exit("缺少阿里云 OSS SDK：python -m pip install oss2")
    key_id, secret = credential("OSS_ACCESS_KEY_ID"), credential("OSS_ACCESS_KEY_SECRET")
    if not key_id or not secret:
        sys.exit("缺少只读凭证：请设置用户环境变量 OSS_ACCESS_KEY_ID 和 OSS_ACCESS_KEY_SECRET（见 docs/日志上传-阿里云OSS部署.md）")
    return oss2, oss2.Bucket(oss2.Auth(key_id, secret), ENDPOINT, BUCKET)


def uploads(oss2, bucket, prefix="logs/"):
    found = []
    for item in oss2.ObjectIteratorV2(bucket, prefix=prefix):
        match = LOG.fullmatch(item.key)
        if match:
            found.append(dict(match.groupdict(), key=item.key, size=item.size, modified=item.last_modified))
    return sorted(found, key=lambda upload: upload["modified"], reverse=True)


def read_json(oss2, bucket, key):
    try:
        return json.loads(bucket.get_object(key).read().decode("utf-8"))
    except oss2.exceptions.NoSuchKey:
        return None


def describe(info):
    device = (info or {}).get("device") or {}
    model = " ".join(part for part in (device.get("brand"), device.get("model")) if part) or "?"
    return model, device.get("android", "?"), device.get("app", "")


def show(oss2, bucket, rows):
    if not rows:
        print("没有找到日志。")
        return
    for row in rows:
        row["info"] = read_json(oss2, bucket, row["key"][:-4] + ".device.json")
        model, android, app = describe(row["info"])
        when = datetime.datetime.fromtimestamp(row["modified"]).strftime("%Y-%m-%d %H:%M:%S")
        print(f"{when}  {row['device']:<9}  {model}  Android {android}  App {app or row['version']}"
              f"  {row['size'] / 1024:.0f} KB  报告 {row['batch']}")


def download(oss2, bucket, row):
    target = OUT / row["device"] / row["day"]
    target.mkdir(parents=True, exist_ok=True)
    log = target / pathlib.PurePosixPath(row["key"]).name
    if not (log.is_file() and log.stat().st_size == row["size"]):
        bucket.get_object_to_file(row["key"], str(log))
    info = row.get("info") or read_json(oss2, bucket, row["key"][:-4] + ".device.json")
    if info is not None:
        log.with_suffix(".device.json").write_text(json.dumps(info, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"已下载 {shown(log)}")
    retrace(log, row["version"], info)


def retrace_tool():
    sdk = pathlib.Path(os.environ.get("ANDROID_HOME") or pathlib.Path.home() / "AppData/Local/Android/Sdk")
    tools = sorted(sdk.glob("cmdline-tools/*/bin/retrace.bat")) + sorted(sdk.glob("cmdline-tools/*/bin/retrace"))
    return tools[-1] if tools else None


def retrace(log, version, info):
    """Expand each one-line crash summary into a stack trace and run R8 retrace over it."""
    crashes = [line for line in log.read_text(encoding="utf-8", errors="replace").splitlines() if "\tuncaught\t" in line]
    if not crashes:
        return
    flavor = "phone" if "mobile" in describe(info)[2] else "tv"
    mapping = ROOT / "Release" / version / "mapping" / f"{flavor}-mapping.txt"
    tool = retrace_tool()
    if not mapping.is_file() or tool is None:
        reason = f"没有 {shown(mapping)}" if tool else "未找到 Android SDK 的 retrace"
        print(f"  {len(crashes)} 条崩溃未还原：{reason}")
        return
    expanded = []
    for line in crashes:
        stamp, _, summary = line.split("\t", 2)
        trace = summary.replace(" | caused by ", "\nCaused by: ").replace(" at ", "\n\tat ")
        expanded.append(f"# {stamp}\n{trace}\n")
    raw = log.with_suffix(".crash.txt")
    raw.write_text("\n".join(expanded), encoding="utf-8")
    result = subprocess.run([str(tool), str(mapping), str(raw)], capture_output=True, text=True,
                            encoding="utf-8", errors="replace", shell=tool.suffix == ".bat")
    if result.returncode:
        print(f"  retrace 失败：{(result.stderr or result.stdout).strip()[:200]}")
        return
    out = log.with_suffix(".retraced.log")
    out.write_text(result.stdout, encoding="utf-8")
    print(f"  {len(crashes)} 条崩溃已还原 -> {shown(out)}")


def main():
    parser = argparse.ArgumentParser(description="列出、下载并还原设备上传到 OSS 的诊断日志（只读）")
    sub = parser.add_subparsers(dest="command", required=True)
    latest = sub.add_parser("latest", help="所有设备中最新的上传")
    device = sub.add_parser("device", help="某台设备的最近上传")
    device.add_argument("device_id", help="设备 ID，例如 K7Q2-9MZD")
    report = sub.add_parser("report", help="按报告编号查找并下载")
    report.add_argument("report_id")
    for command in (latest, device):
        command.add_argument("-n", type=int, default=5, help="显示条数（默认 5）")
        command.add_argument("--download", action="store_true", help="下载到 .codex/session/logs/")
    args = parser.parse_args()

    if args.command == "device" and not DEVICE.fullmatch(args.device_id.upper()):
        parser.error("设备 ID 格式应为 XXXX-XXXX（数字和大写字母）")
    if args.command == "report" and not REPORT.fullmatch(args.report_id.lower()):
        parser.error("报告编号应为 36 位 UUID")
    oss2, bucket = open_bucket()
    try:
        if args.command == "latest":
            rows = uploads(oss2, bucket)[:args.n]
        elif args.command == "device":
            device_id = args.device_id.upper()
            profile = read_json(oss2, bucket, f"logs/{device_id}/device.json")
            if profile:
                model, android, app = describe(profile)
                print(f"设备 {device_id}：{model}，Android {android}，App {app}，最近上传 {profile.get('uploadedAt', '?')}")
            rows = uploads(oss2, bucket, f"logs/{device_id}/")[:args.n]
        else:
            rows = [row for row in uploads(oss2, bucket) if row["batch"] == args.report_id.lower()]
        show(oss2, bucket, rows)
        if args.command == "report" or args.download:
            for row in rows:
                download(oss2, bucket, row)
    except oss2.exceptions.AccessDenied as error:
        sys.exit(f"没有权限（403）：{error.message}。请确认只读用户已授权 ysc-log-oss-read。")
    except oss2.exceptions.ServerError as error:
        sys.exit(f"OSS 请求失败（HTTP {error.status}）：{error.message}")


if __name__ == "__main__":
    main()
