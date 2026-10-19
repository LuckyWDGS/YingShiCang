"""Root-only isolated playback QA on the explicitly allowed LDPlayer target.

Snapshots the stopped production app without reading auth values; only installs
and removes this tool's distinct package. Logs and backups belong under ignored QA.
"""
from pathlib import Path, PurePosixPath
import argparse
import hashlib
import io
import json
import subprocess
import sys
import tarfile
import time
from report_checks import classify, observed_format, output_metrics

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from adb_target import verified_adb
PACKAGE = 'com.yingshicang.enginebench'
PRODUCTION = 'com.yingshicang.player'
SERIAL = '127.0.0.2:5555'
ADB = None

def adb(*args, timeout=90, check=True):
    global ADB
    if ADB is None: ADB = verified_adb(SERIAL)
    return subprocess.run(ADB + list(args), capture_output=True, timeout=timeout, check=check).stdout

def shell(*args, **kwargs): return adb('shell', *args, **kwargs)
def su(*args, **kwargs): return shell('su', '0', *args, **kwargs)
def sha(data): return hashlib.sha256(data).hexdigest()

def snapshot(directory, label):
    result = {}
    for kind, path in [('internal', '/data/user/0/' + PRODUCTION),
                       ('external', '/sdcard/Android/data/' + PRODUCTION)]:
        exists = bool(su('ls', '-d', path, check=False).strip())
        if kind == 'internal': assert exists, 'Production app data must be present'
        entry = {'exists': exists, 'inventory': {}}
        if exists:
            temp = '/data/local/tmp/ysc-engine-bench-backup.tar'
            su('tar', '-cf', temp, '--exclude=./lib', '-C', path, '.', timeout=180)
            su('chmod', '644', temp)
            try: data = adb('exec-out', 'cat', temp, timeout=180)
            finally: su('rm', '-f', temp)
            (directory / f'{label}-{kind}.tar').write_bytes(data)
            with tarfile.open(fileobj=io.BytesIO(data)) as archive:
                for member in archive:
                    relative = PurePosixPath(member.name)
                    assert not relative.is_absolute() and '..' not in relative.parts
                    if member.isfile(): entry['inventory'][str(relative)] = sha(archive.extractfile(member).read())
            entry['archiveSha256'] = sha(data)
        result[kind] = entry
    return result

def prepare(args):
    assert not args.run.exists(), 'Use a fresh run directory'
    assert not shell('pm', 'path', PACKAGE, check=False).strip(), 'An existing benchmark package has another owner'
    args.run.mkdir(parents=True)
    assert su('id', '-u').strip() == b'0', 'A complete production snapshot requires root'
    state = {'serial': SERIAL, 'productionUnchanged': False, 'probeRemoved': False,
             'forward': adb('forward', '--list').decode(), 'reverse': adb('reverse', '--list').decode()}
    shell('am', 'force-stop', PRODUCTION)
    time.sleep(1)
    assert not shell('pidof', PRODUCTION, check=False).strip()
    state['baseline'] = snapshot(args.run, 'before')
    assert state['baseline']['internal']['inventory'], 'Empty production snapshot is not valid'
    state['device'] = {key: shell('getprop', key).decode().strip() for key in
                       ('ro.product.model', 'ro.build.version.sdk', 'ro.product.cpu.abilist',
                        'ro.dalvik.vm.native.bridge', 'ro.hardware')}
    state['apkSha256'] = sha(args.apk.read_bytes())
    (args.run / 'state.json').write_text(json.dumps(state, indent=2))
    assert b'Success' in adb('install', str(args.apk), timeout=180)
    # Keep media in the isolated app's internal files directory.  Recreating an
    # Android 11+ app-specific external directory as root can make FUSE expose
    # it with root-only ownership; Exo's FileDataSource then reports EACCES
    # while the native MPV path may still appear to work.  `run-as` copies each
    # fixture with the benchmark UID, so both engines exercise the same file.
    temp_root = '/data/local/tmp'
    shell('run-as', PACKAGE, 'mkdir', '-p', 'files')
    for media in args.fixtures.glob('*.mp4'):
        temp = temp_root + '/ysc-engine-bench-' + media.name
        su('rm', '-f', temp)
        adb('push', str(media), temp, timeout=180)
        su('chmod', '644', temp)
        assert b'' == shell('run-as', PACKAGE, 'cp', temp, 'files/' + media.name,
                            timeout=180)
        shell('run-as', PACKAGE, 'chmod', '644', 'files/' + media.name)
        su('rm', '-f', temp)
    (args.run / 'fixtures.json').write_bytes((args.fixtures / 'manifest.json').read_bytes())
    print('Prepared isolated benchmark; production backup preserved.', flush=True)

def run_case(directory, name, mode, fixture, seconds, gpu_next=False, vulkan=False):
    assert not (directory / (name + '.json')).exists(), 'Refuse overwriting previous evidence'
    # The production app may retain a task with a stale search/player Activity
    # after a prior QA run. Stop it immediately before every isolated case so it
    # cannot steal focus from the benchmark Surface during a long sample.
    shell('am', 'force-stop', PRODUCTION)
    shell('am', 'force-stop', PACKAGE)
    remote = '/sdcard/Android/data/' + PACKAGE + '/files/'
    fixture_uri = 'file:///data/user/0/' + PACKAGE + '/files/' + fixture + '.mp4'
    shell('am', 'start', '-W', '-n', PACKAGE + '/.BenchmarkActivity',
          '--es', 'mode', mode, '--es', 'run', name, '--es', 'uri', fixture_uri,
          '--es', 'fixture', fixture,
          '--ei', 'seconds', str(seconds), '--ez', 'gpuNext', str(gpu_next).lower(),
          '--ez', 'vulkan', str(vulkan).lower())
    deadline = time.monotonic() + seconds + 90
    last_print = 0
    result = None
    while time.monotonic() < deadline:
        time.sleep(5)
        raw = shell('cat', remote + name + '.json', check=False)
        try: result = json.loads(raw)
        except (ValueError, UnicodeDecodeError):
            if not shell('pidof', PACKAGE, check=False).strip(): break
            continue
        # A process can die after its last atomic report write. Do not keep
        # waiting on a stale {status: running} JSON until the full deadline.
        if result.get('status') == 'running' and not shell('pidof', PACKAGE, check=False).strip():
            break
        (directory / (name + '.json')).write_bytes(raw)
        if time.monotonic() - last_print > 45:
            samples = result.get('samples') or []
            sample = samples[-1] if samples else None
            print(json.dumps({'case': name, 'status': result['status'], 'elapsed': result['elapsedMs'],
                              'firstFrameMs': result['firstFrameMs'], 'decoder': result['decoder'],
                              'errors': result['errors'], 'last': sample}), flush=True)
            last_print = time.monotonic()
        if result['status'] != 'running': break
    pid = shell('pidof', '-s', PACKAGE, check=False).decode().strip()
    if pid: (directory / (name + '-logcat.txt')).write_bytes(shell('logcat', '-d', '--pid=' + pid, timeout=30))
    (directory / (name + '-crash.txt')).write_bytes(shell('logcat', '-b', 'crash', '-d'))
    shell('am', 'force-stop', PACKAGE)
    if not result or result['status'] == 'running':
        (directory / (name + '-incomplete.json')).write_text(json.dumps({'completed': False, 'last': result}))
        print(name + ': incomplete or crashed', flush=True)
        return False
    verdict = classify(result)
    verdict['observedFormat'] = observed_format(result)
    verdict['outputMetrics'] = output_metrics(result)
    (directory / (name + '-verdict.json')).write_text(json.dumps(verdict, indent=2), encoding='utf-8')
    print(name + ': ' + json.dumps(verdict), flush=True)
    return verdict['passed']

def cleanup(args):
    state = json.loads((args.run / 'state.json').read_text())
    shell('am', 'force-stop', PACKAGE)
    after = snapshot(args.run, 'after')
    state['productionUnchanged'] = all(after[k]['inventory'] == state['baseline'][k]['inventory']
                                       and after[k]['exists'] == state['baseline'][k]['exists'] for k in after)
    state['mappingsUnchanged'] = state['forward'] == adb('forward', '--list').decode() and state['reverse'] == adb('reverse', '--list').decode()
    (args.run / 'state.json').write_text(json.dumps(state, indent=2))
    assert state['productionUnchanged'] and state['mappingsUnchanged'], 'Review unexpected changes; backup retained'
    assert b'Success' in adb('uninstall', PACKAGE)
    state['probeRemoved'] = not shell('pm', 'path', PACKAGE, check=False).strip()
    (args.run / 'state.json').write_text(json.dumps(state, indent=2))
    shell('am', 'start', '-n', PRODUCTION + '/com.fongmi.android.tv.ui.activity.HomeActivity')
    print(json.dumps({k:state[k] for k in ('productionUnchanged','mappingsUnchanged','probeRemoved')}))

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('prepare','case','matrix','cleanup'))
    parser.add_argument('--run', type=Path, required=True)
    parser.add_argument('--apk', type=Path)
    parser.add_argument('--fixtures', type=Path)
    parser.add_argument('--name')
    parser.add_argument('--mode', choices=('exo-hard','exo-soft','mpv-hard','mpv-soft'))
    parser.add_argument('--fixture', default='hevc1080', choices=('avc1080','hevc1080','hevc4k'))
    parser.add_argument('--seconds', type=int, default=300)
    parser.add_argument('--gpu-next', action='store_true')
    parser.add_argument('--vulkan', action='store_true')
    args = parser.parse_args()
    assert 15 <= args.seconds <= 3600
    if args.action == 'prepare': prepare(args)
    elif args.action == 'cleanup': cleanup(args)
    elif args.action == 'case':
        assert args.name and args.mode
        if not run_case(args.run, args.name, args.mode, args.fixture, args.seconds, args.gpu_next, args.vulkan):
            sys.exit(1)
    else:
        passed = True
        for mode in ('exo-hard','exo-soft','mpv-hard','mpv-soft'):
            passed = run_case(args.run, (args.name or args.fixture) + '-' + mode,
                              mode, args.fixture, args.seconds) and passed
        if not passed: sys.exit(1)

if __name__ == '__main__': main()
