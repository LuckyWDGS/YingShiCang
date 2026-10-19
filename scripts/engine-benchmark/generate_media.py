"""Create deterministic moving AVC/HEVC clips for the isolated player comparison."""
from pathlib import Path
import argparse
import hashlib
import json
import subprocess

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('output', type=Path)
    parser.add_argument('--seconds', type=int, default=60,
                        help='duration of each fixture; use a value longer than the longest case')
    args = parser.parse_args()
    if not 15 <= args.seconds <= 3600:
        parser.error('--seconds must be between 15 and 3600')
    args.output.mkdir(parents=True, exist_ok=True)
    manifest = {}
    for name, codec, size in [('avc1080', 'libx264', '1920x1080'),
                              ('hevc1080', 'libx265', '1920x1080'),
                              ('hevc4k', 'libx265', '3840x2160')]:
        path = args.output / (name + '.mp4')
        if path.exists():
            raise FileExistsError(path)
        command = ['ffmpeg', '-hide_banner', '-loglevel', 'error', '-n',
                   '-f', 'lavfi', '-i', f'testsrc2=size={size}:rate=25',
                   '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=48000',
                   '-t', str(args.seconds), '-c:v', codec, '-preset', 'ultrafast', '-crf', '30',
                   '-pix_fmt', 'yuv420p', '-g', '50', '-threads', '4',
                   '-c:a', 'aac', '-b:a', '96k', '-movflags', '+faststart']
        if codec == 'libx265': command += ['-x265-params', 'pools=4:frame-threads=2:log-level=error']
        subprocess.run(command + [str(path)], check=True)
        probe = json.loads(subprocess.check_output(['ffprobe', '-v', 'error', '-of', 'json',
                    '-show_entries', 'format=duration,size:stream=codec_name,codec_type,width,height,avg_frame_rate', str(path)]))
        manifest[name] = {'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
                          'requestedSeconds': args.seconds, 'probe': probe}
        print(name + ' ready', flush=True)
    (args.output / 'manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')

if __name__ == '__main__': main()
