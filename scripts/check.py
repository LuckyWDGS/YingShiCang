"""Run the repeatable local regression checks, including both service test roots."""
import argparse
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--offline', action='store_true', help='Use cached Gradle dependencies only')
    parser.add_argument('--with-apks', action='store_true', help='Also package TV and mobile debug APKs')
    args = parser.parse_args()
    gradle = str(ROOT / ('gradlew.bat' if os.name == 'nt' else 'gradlew'))
    tasks = [':app:testLeanbackDebugUnitTest', ':app:compileMobileDebugJavaWithJavac']
    if args.with_apks:
        tasks += [':app:assembleLeanbackDebug', ':app:assembleMobileDebug']
    commands = [[gradle, *tasks, '--console=plain', *(['--offline'] if args.offline else [])],
                [sys.executable, '-m', 'unittest', 'discover', '-s', 'service', '-v'],
                [sys.executable, '-m', 'unittest', 'discover', '-s', 'service/aliyun_fc', '-v']]
    failed = False
    for command in commands:
        result = subprocess.run(command, cwd=ROOT)
        failed |= result.returncode != 0
    return int(failed)


if __name__ == '__main__':
    raise SystemExit(main())
