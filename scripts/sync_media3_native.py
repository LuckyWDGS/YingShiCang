"""Verify and import one complete MPV/FFmpeg native build into the local Media3 fork.

Requires both ARM ABIs from the same source build. Keeps a backup, rejects mixed
split/aggregate FFmpeg packages, validates ELF dependencies/alignment and JNI, and
writes a hash manifest. Does not install an APK or contact a device.
"""
from pathlib import Path
import argparse
import hashlib
import json
import re
import shutil
import struct
import subprocess
from datetime import datetime, timezone

ABIS = {'armeabi-v7a': ('armv7l', 40), 'arm64-v8a': ('arm64', 183)}
NAMES = {'libffmpeg.so', 'libmpv.so', 'libplayer.so', 'libc++_shared.so'}
SYSTEM = {'libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libandroid.so', 'libz.so',
          'libEGL.so', 'libGLESv2.so', 'libGLESv3.so', 'libOpenSLES.so', 'libvulkan.so',
          'libmediandk.so', 'libaaudio.so', 'libjnigraphics.so'}

def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b''):
            result.update(chunk)
    return result.hexdigest()

def command(*parts):
    return subprocess.check_output([str(x) for x in parts], text=True, encoding='utf-8',
                                   errors='replace', stderr=subprocess.DEVNULL).strip()

def elf(path, machine, readelf):
    header = path.read_bytes()[:64]
    assert header[:4] == b'\x7fELF' and header[5] == 1, path.name + ': invalid ELF'
    assert struct.unpack_from('<H', header, 18)[0] == machine, path.name + ': wrong ABI'
    dynamic = command(readelf, '-d', path)
    needed = re.findall(r'\(NEEDED\).*?\[(.*?)\]', dynamic)
    assert set(needed) <= SYSTEM | NAMES, f'{path.name}: unexpected dependency {needed}'
    assert f'[{path.name}]' in dynamic, path.name + ': SONAME missing'
    segments = command(readelf, '-l', '--wide', path)
    aligns = [int(line.split()[-1], 16) for line in segments.splitlines()
              if line.strip().startswith('LOAD ')]
    # Android's 16 KiB page-size requirement applies to the shipped 64-bit
    # variant. The NDK's armeabi-v7a libc++_shared remains 4 KiB aligned and is
    # valid for 32-bit compatibility; record its alignment without rejecting it.
    if machine == 183:
        assert aligns and min(aligns) >= 16384, path.name + ': requires 16 KiB ELF alignment'
    if path.name in ('libmpv.so', 'libplayer.so'):
        assert 'libffmpeg.so' in needed, path.name + ': not linked to aggregate FFmpeg'
    return {'bytes': path.stat().st_size, 'sha256': digest(path), 'needed': needed, 'loadAlignment': aligns}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--producer', type=Path, required=True)
    parser.add_argument('--media', type=Path, required=True)
    parser.add_argument('--readelf', type=Path, required=True)
    parser.add_argument('--backup', type=Path, required=True)
    args = parser.parse_args()
    producer, media, backup = (x.resolve() for x in (args.producer, args.media, args.backup))
    assert not backup.exists(), 'Use a new backup directory'
    assert (media / 'libraries/mpvplayer').is_dir()
    prefix = producer / 'buildscripts/prefix'
    libraries = producer / 'app/src/main/libs'
    ff = media / 'libraries/decoder_ffmpeg/src/main'
    mpv = media / 'libraries/mpvplayer/src/main'
    report = {'createdUtc': datetime.now(timezone.utc).isoformat(), 'libraries': {}, 'sources': {}}
    java = (mpv / 'java/is/xyz/mpv/MPVLib.java').read_text(encoding='utf-8')
    methods = set(re.findall(r'public static native [^;\n]+?\s+(\w+)\(', java))
    assert methods, 'Cannot read MPV JNI declarations'
    for abi, (arch, machine) in ABIS.items():
        package = libraries / abi
        assert {f.name for f in package.glob('*.so')} == NAMES, 'Incomplete native package: ' + abi
        report['libraries'][abi] = {name: elf(package / name, machine, args.readelf) for name in sorted(NAMES)}
        symbols = command(args.readelf, '--dyn-syms', '--wide', package / 'libplayer.so')
        missing = [method for method in methods if 'Java_is_xyz_mpv_MPVLib_' + method not in symbols]
        assert not missing, f'{abi}: missing MPV JNI exports {missing}'
        for file in ('lib/libplacebo.a', 'lib/libshaderc.a', 'include/libplacebo/config.h'):
            assert (prefix / arch / file).is_file(), f'Missing renderer SDK: {arch}/{file}'
    for name in ('mpv', 'ffmpeg', 'libplacebo', 'uavs3d', 'dav1d', 'libass'):
        source = producer / 'buildscripts/deps' / name
        report['sources'][name] = {'commit': command('git', '-C', source, 'rev-parse', 'HEAD'),
                                   'trackedDirty': bool(command('git', '-C', source, 'diff', '--name-only'))}
    report['sources']['mpv-android'] = {'commit': command('git', '-C', producer, 'rev-parse', 'HEAD'),
                                       'trackedDirty': bool(command('git', '-C', producer, 'diff', '--name-only'))}
    backup.mkdir(parents=True)
    for folder in (ff / 'jniLibs', ff / 'ffmpeg/include', ff / 'renderer-sdk', ff / 'resources', mpv / 'jniLibs'):
        if folder.exists():
            shutil.copytree(folder, backup / folder.relative_to(media), dirs_exist_ok=True)
    # Keep only the aggregate FFmpeg library. Retire exact old split library files after backup.
    old_split = ('avcodec', 'avdevice', 'avfilter', 'avformat', 'avutil', 'swresample', 'swscale')
    for abi, (arch, _) in ABIS.items():
        (ff / 'jniLibs' / abi).mkdir(parents=True, exist_ok=True)
        (mpv / 'jniLibs' / abi).mkdir(parents=True, exist_ok=True)
        shutil.copy2(libraries / abi / 'libffmpeg.so', ff / 'jniLibs' / abi / 'libffmpeg.so')
        for name in old_split:
            old = ff / 'jniLibs' / abi / ('lib' + name + '.so')
            if old.exists(): old.unlink()
        for name in sorted(NAMES - {'libffmpeg.so'}):
            shutil.copy2(libraries / abi / name, mpv / 'jniLibs' / abi / name)
        sdk = ff / 'renderer-sdk' / abi
        (sdk / 'lib').mkdir(parents=True, exist_ok=True)
        shutil.copytree(prefix / arch / 'include/libplacebo', sdk / 'include/libplacebo', dirs_exist_ok=True)
        for name in ('libplacebo.a', 'libshaderc.a'):
            shutil.copy2(prefix / arch / 'lib' / name, sdk / 'lib' / name)
        files = sorted((sdk / 'include/libplacebo').rglob('*'))
        (sdk / 'headers.sha256').write_text(''.join(f'{digest(p)}  {p.relative_to(sdk / "include").as_posix()}\n'
                                                   for p in files if p.is_file()), encoding='utf-8')
    for name in old_split:
        shutil.copytree(prefix / 'arm64/include' / ('lib' + name), ff / 'ffmpeg/include' / ('lib' + name), dirs_exist_ok=True)
    manifest = {'schema.version': '4', 'manifest.kind': 'decoder-ffmpeg-consumer',
                'artifact.created.utc': report['createdUtc'], 'android.ndk.version': '30.0.16248370',
                'abi.list': ','.join(ABIS), 'library.list': 'libffmpeg.so',
                'renderer-sdk.abi.list': ','.join(ABIS), 'libplacebo.opengl': 'true', 'libplacebo.vulkan': 'true',
                'renderer-sdk.link.libraries': 'shaderc,vulkan,android,mediandk,log,dl,m,c++'}
    for name, source in report['sources'].items():
        manifest[name + '.commit'] = source['commit']
        manifest[name + '.source.dirty'] = str(source['trackedDirty']).lower()
    for abi, details in report['libraries'].items():
        for name, details in details.items(): manifest[f'sha256.{abi}.{name}'] = details['sha256']
    props = ff / 'resources/META-INF/androidx.media3.decoder.ffmpeg/native-dependencies.properties'
    props.parent.mkdir(parents=True, exist_ok=True)
    props.write_text('# Verified local build; see native-build.json for complete provenance.\n' +
                     ''.join(f'{key}={value}\n' for key, value in manifest.items()), encoding='utf-8')
    (backup / 'native-build.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    (props.parent / 'native-build.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps(report, indent=2))

if __name__ == '__main__': main()
