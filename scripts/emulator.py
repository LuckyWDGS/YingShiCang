"""Safe adb wrapper: always selects and verifies an emulator before device mutations."""
import argparse
import os
import pathlib
import subprocess
import sys
from adb_target import verified_adb


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", default="emulator-5580")
    parser.add_argument("--output", type=pathlib.Path)
    parser.add_argument("args", nargs=argparse.REMAINDER)
    opt = parser.parse_args()
    try:
        base = verified_adb(opt.serial)
    except ValueError as error:
        parser.error(str(error))
    args = opt.args[1:] if opt.args[:1] == ["--"] else opt.args
    if not args or args[0] not in {"shell", "exec-out", "install", "pull", "push", "logcat", "forward", "reverse"}:
        parser.error("Provide an explicit device command")
    if opt.output:
        opt.output.parent.mkdir(parents=True, exist_ok=True)
        with opt.output.open("wb") as out:
            sys.exit(subprocess.run(base + args, stdout=out, timeout=180).returncode)
    sys.exit(subprocess.run(base + args, timeout=180).returncode)


if __name__ == "__main__":
    main()
