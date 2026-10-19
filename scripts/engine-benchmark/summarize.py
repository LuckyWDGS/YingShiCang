"""Summarize playback reports without treating a requested mode as an actual decoder."""
from pathlib import Path
import argparse
import json
from report_checks import classify, observed_format, output_metrics


def summarize(data):
    samples = data.get('samples', [])
    memory = [s['pssKb'] / 1024 for s in samples if 'pssKb' in s]
    active = [s for s in samples if s.get('ms', 0) >= max(5000, data.get('firstFrameMs', 0))]
    stalled = 0
    progressed = 0
    for a, b in zip(active, active[1:]):
        elapsed = b['ms'] - a['ms']
        delta = b['positionMs'] - a['positionMs']
        if delta < -30000:  # the fixture loops every 60 seconds
            delta += 60000
        progressed += max(0, delta)
        if delta < 100:
            stalled += elapsed
    cpu = ((samples[-1]['cpuMs'] - samples[0]['cpuMs']) /
           max(1, samples[-1]['ms'] - samples[0]['ms']) * 100) if len(samples) > 1 else None
    last = samples[-1] if samples else {}
    return {
        'run': data['run'], 'requestedMode': data['mode'], 'status': data['status'],
        'elapsedSeconds': round(data['elapsedMs'] / 1000, 1),
        'firstFrameMs': data['firstFrameMs'], 'decoder': data.get('decoder'),
        'requestedFixture': data.get('requestedFixture'),
        'observedFormat': observed_format(data),
        'validation': classify(data),
        'hardwareCodec': data.get('hardwareCodec'),
        'mpvHwdec': sorted({s['hwdec'] for s in samples if s.get('hwdec')}),
        'errors': data['errors'], 'codecErrors': data['codecErrors'],
        'progressedSeconds': round(progressed / 1000, 1),
        'nearStationarySeconds': round(stalled / 1000, 1),
        'processCpuPercentOfOneCore': round(cpu, 1) if cpu is not None else None,
        'pssMiB': {'first': round(memory[0], 1), 'last': round(memory[-1], 1),
                   'peak': round(max(memory), 1)} if memory else None,
        'decoderDropsLastLoop': last.get('decoderDrops'),
        'outputDropsLastLoop': last.get('outputDrops'),
        'renderedLastLoop': last.get('rendered'),
        'skippedLastLoop': last.get('skipped'),
        'outputMetrics': output_metrics(data),
        'lifecycleEvidence': data.get('lifecycleEvidence'),
        'ffmpegVersion': data.get('ffmpegVersion'),
        'mpvVersion': last.get('mpvVersion'),
    }


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    args = parser.parse_args()
    rows = []
    for path in sorted(args.directory.glob('*.json')):
        data = json.loads(path.read_text())
        if 'samples' in data and 'requestedMs' in data:
            rows.append(summarize(data))
    text = json.dumps(rows, indent=2, ensure_ascii=False)
    (args.directory / 'summary.json').write_text(text, encoding='utf-8')
    print(text)
