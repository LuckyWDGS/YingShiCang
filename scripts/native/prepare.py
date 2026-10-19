"""Create a new native producer from locked commits, preserving existing directories."""
from pathlib import Path
import argparse
import hashlib
import json
import subprocess

RECIPE = Path(__file__).resolve().parent
LOCK = json.loads((RECIPE / 'sources.lock.json').read_text())


def git(path, *args):
    subprocess.run(['git', '-C', str(path), *args], check=True)


def checkout(path, name, spec):
    if path.exists():
        raise ValueError('Refusing to overwrite existing source: ' + str(path))
    path.mkdir(parents=True)
    git(path, 'init', '-q')
    git(path, 'remote', 'add', 'origin', spec['repository'])
    git(path, 'fetch', '--depth=1', 'origin', spec['commit'])
    git(path, 'checkout', '--detach', 'FETCH_HEAD')
    git(path, 'submodule', 'update', '--init', '--recursive', '--depth=1')
    if 'patchSha256' in spec:
        patch = RECIPE / (name + '.patch')
        if hashlib.sha256(patch.read_bytes()).hexdigest() != spec['patchSha256']:
            raise ValueError('Patch hash mismatch: ' + name)
        git(path, 'apply', '--check', str(patch))
        git(path, 'apply', '--whitespace=nowarn', str(patch))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--destination', type=Path, required=True)
    args = parser.parse_args()
    destination = args.destination.resolve()
    checkout(destination, 'mpv-android', LOCK['sources']['mpv-android'])
    for name, spec in LOCK['sources'].items():
        if name != 'mpv-android':
            checkout(destination / 'buildscripts/deps' / name, name, spec)
    print('Prepared locked sources. Continue with scripts/native/README.md.')
