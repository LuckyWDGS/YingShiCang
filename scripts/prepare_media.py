"""Prepare hash-locked Media3 1.11.1 with the reviewed FongMi overlay.

Build native libraries separately (scripts/native/README.md) and import with
sync_media3_native.py. Existing dependency directories are never overwritten.
"""
import argparse
import hashlib
import json
import os
import posixpath
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
RECIPE = ROOT / 'scripts/media3'
LOCK = json.loads((RECIPE / 'sources.lock.json').read_text())


def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            result.update(chunk)
    return result.hexdigest()


def verify(destination):
    for relative, expected in LOCK['files'].items():
        path = destination / relative
        actual = hashlib.sha256(path.read_text(encoding='utf-8').encode()).hexdigest()
        if actual != expected:
            raise ValueError('Source overlay differs: ' + relative)


def prepare(destination, archive=None):
    destination = destination.resolve()
    patch = RECIPE / 'media3-1.11.1-fongmi.patch'
    if digest(patch) != LOCK['patchSha256']:
        raise ValueError('Media3 patch hash mismatch')
    marker = destination / '.yingshicang-source.json'
    if marker.exists() and json.loads(marker.read_text()).get('patchSha256') == LOCK['patchSha256']:
        verify(destination)
        print('Verified prepared source:', destination)
        return
    if destination.exists():
        raise SystemExit('Destination exists; choose a new directory to preserve local changes.')
    destination.parent.mkdir(parents=True, exist_ok=True)
    if archive is None:
        archive = destination.parent / 'androidx-media-1.11.1.zip'
        if not archive.exists():
            temporary = archive.with_suffix('.download')
            url = f"https://codeload.github.com/androidx/media/zip/refs/tags/{LOCK['archiveRef']}"
            print('Downloading hash-locked Media3 source:', url, flush=True)
            with urllib.request.urlopen(url, timeout=60) as response, temporary.open('wb') as output:
                shutil.copyfileobj(response, output, 1024 * 1024)
            if digest(temporary) != LOCK['archiveSha256']:
                raise ValueError('Downloaded source archive hash mismatch')
            os.replace(temporary, archive)
    if digest(archive) != LOCK['archiveSha256']:
        raise ValueError('Source archive hash mismatch')
    with zipfile.ZipFile(archive) as zipped:
        for entry in zipped.infolist():
            relative = PurePosixPath(entry.filename)
            if len(relative.parts) < 2:
                continue
            if relative.is_absolute() or '..' in relative.parts:
                raise ValueError('Invalid archive path')
            target = destination.joinpath(*relative.parts[1:]).resolve()
            if destination not in target.parents:
                raise ValueError('Archive path escapes dependency root')
            if entry.is_dir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                # Materialize only links to regular files within this verified archive.
                if (entry.external_attr >> 16) & 0o170000 == 0o120000:
                    link = zipped.read(entry).decode('utf-8')
                    resolved = posixpath.normpath(posixpath.join(posixpath.dirname(entry.filename), link))
                    if not resolved.startswith(relative.parts[0] + '/'):
                        raise ValueError('Archive link escapes source root')
                    linked = zipped.getinfo(resolved)
                    if linked.is_dir() or (linked.external_attr >> 16) & 0o170000 == 0o120000:
                        raise ValueError('Unsupported nested archive link')
                    target.write_bytes(zipped.read(linked))
                    continue
                with zipped.open(entry) as source, target.open('wb') as output:
                    shutil.copyfileobj(source, output, 1024 * 1024)
    subprocess.run(['git', 'apply', '--check', str(patch)], cwd=destination, check=True)
    subprocess.run(['git', 'apply', '--whitespace=nowarn', str(patch)], cwd=destination, check=True)
    verify(destination)
    marker.write_text(json.dumps({k: v for k, v in LOCK.items() if k != 'files'}, indent=2))
    print('Prepared:', destination)
    print('Next: build both native ABIs (scripts/native/README.md), then sync_media3_native.py.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--destination', type=Path, default=ROOT / '.dependencies/media')
    parser.add_argument('--archive', type=Path, help='Use a downloaded, hash-verified source archive')
    parser.add_argument('--verify', action='store_true')
    args = parser.parse_args()
    if args.verify:
        verify(args.destination)
        print('Source overlay hashes verified')
    else:
        prepare(args.destination, args.archive)
