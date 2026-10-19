"""Pure evidence checks shared by the runner and summary; no device access."""
import re


def _codec(value):
    text = str(value or '').lower()
    if re.search(r'hevc|h[. ]?265|hev1|hvc1', text):
        return 'HEVC'
    if re.search(r'(^|[./ _-])avc(?:[. /_(-]|$)|h[. ]?264|avc1', text):
        return 'AVC'
    if re.search(r'av01|\bav1\b', text):
        return 'AV1'
    return None


def _positive(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and value > 0


def observed_format(data):
    """Identify the stream from observed format/decoder, never from a case name."""
    video = data.get('videoFormat') or {}
    samples = data.get('samples') or []
    codecs = {_codec(video.get('sampleMimeType')), _codec(video.get('codecs')),
              _codec(data.get('decoder'))}
    codecs.update(_codec(sample.get('decoder')) for sample in samples)
    codecs.discard(None)
    codec = next(iter(codecs)) if len(codecs) == 1 else 'conflict' if codecs else None
    dimensions = [(video.get('width'), video.get('height'))]
    dimensions += [(sample.get('videoWidth'), sample.get('videoHeight')) for sample in samples]
    dimensions = [(int(w), int(h)) for w, h in dimensions if _positive(w) and _positive(h)]
    width, height = dimensions[-1] if dimensions else (None, None)
    rates = [video.get('frameRate')] + [sample.get('containerFps') for sample in samples]
    rates = [rate for rate in rates if _positive(rate)]
    fixture = None
    if codec in ('HEVC', 'AVC') and (width, height) in ((1920, 1080), (3840, 2160)):
        fixture = codec.lower() + ('1080' if height == 1080 else '4k')
    expected = data.get('requestedFixture')
    expected_codec = {'avc1080': 'AVC', 'hevc1080': 'HEVC', 'hevc4k': 'HEVC'}.get(expected)
    expected_size = {'avc1080': (1920, 1080), 'hevc1080': (1920, 1080),
                     'hevc4k': (3840, 2160)}.get(expected)
    matches = None
    if expected_codec and codec:
        if codec != expected_codec:
            matches = False
        elif expected_size and dimensions:
            matches = all(size == expected_size for size in dimensions)
    return {'codec': codec, 'width': width, 'height': height,
            'frameRate': rates[-1] if rates else None, 'fixture': fixture,
            'matchesRequested': matches}


def output_metrics(data):
    """Counter resets split intervals; output starvation differs from audio progress."""
    active_after = max(5000, data.get('firstFrameMs', 0))
    samples = [s for s in data.get('samples', []) if s.get('ms', 0) >= active_after]
    rendered_frames = measured_ms = 0
    skipped_total = None
    frozen_ms = longest_frozen_ms = 0
    for sample in data.get('samples', []):
        if isinstance(sample.get('skipped'), (int, float)):
            if skipped_total is None:
                skipped_total = sample['skipped']
                previous_skipped = sample['skipped']
            else:
                skipped_total += (sample['skipped'] - previous_skipped
                                  if sample['skipped'] >= previous_skipped else sample['skipped'])
                previous_skipped = sample['skipped']
    for a, b in zip(samples, samples[1:]):
        elapsed = b['ms'] - a['ms']
        if elapsed <= 0 or not all(isinstance(s.get('rendered'), (int, float)) for s in (a, b)):
            frozen_ms = 0
            continue
        rendered_delta = b['rendered'] - a['rendered']
        skipped_delta = b.get('skipped', 0) - a.get('skipped', 0)
        # A loop/decoder reset is not an output stall or negative frame rate.
        if rendered_delta < 0 or skipped_delta < 0:
            frozen_ms = 0
            continue
        measured_ms += elapsed
        rendered_frames += rendered_delta
        if rendered_delta == 0 and skipped_delta > 0:
            frozen_ms += elapsed
            longest_frozen_ms = max(longest_frozen_ms, frozen_ms)
        else:
            frozen_ms = 0
    return {'renderedFps': round(rendered_frames * 1000 / measured_ms, 2) if measured_ms else None,
            'renderedFramesMeasured': rendered_frames if measured_ms else None,
            'renderedSecondsMeasured': round(measured_ms / 1000, 3),
            'skippedTotal': skipped_total,
            'maxFrozenRenderedWithSkipsSeconds': round(longest_frozen_ms / 1000, 3)}


def classify(data):
    """A finished timer is insufficient: require visible output for the full run."""
    reasons = []
    samples = data.get('samples') or []
    requested = data.get('requestedMs', 0)
    first_frame = data.get('firstFrameMs', -1)
    if data.get('status') != 'finished':
        reasons.append('not_finished')
    if requested <= 0 or data.get('elapsedMs', 0) < requested:
        reasons.append('duration_incomplete')
    if not samples or samples[-1].get('ms', 0) < requested:
        reasons.append('sample_duration_incomplete')
    if first_frame < 0:
        reasons.append('no_first_frame')
    if data.get('errors', 0) or any(s.get('errors', 0) for s in samples):
        reasons.append('playback_error')
    if data.get('codecErrors', 0) or any(s.get('codecErrors', 0) for s in samples):
        reasons.append('codec_error')
    active = [s for s in samples if s.get('ms', 0) >= max(5000, first_frame)]
    evidence = data.get('lifecycleEvidence') or {}
    keys = ('resumed', 'windowFocused', 'surfaceAvailable', 'surfaceValid')
    if (data.get('schemaVersion', 0) < 2 or not active
            or not all(all(isinstance(sample.get(key), bool) for key in keys) for sample in active)
            or not all(key in evidence for key in ('backgroundEvents', 'surfaceLosses', 'focusLosses'))):
        reasons.append('lifecycle_unverified')
    if evidence.get('backgroundEvents', 0) or any(s.get('resumed') is False for s in active):
        reasons.append('backgrounded')
    if evidence.get('surfaceLosses', 0) or any(s.get('surfaceAvailable') is False
                                             or s.get('surfaceValid') is False for s in active):
        reasons.append('surface_lost')
    if evidence.get('focusLosses', 0) or any(s.get('windowFocused') is False for s in active):
        reasons.append('focus_lost')
    if data.get('mode', '').startswith('exo'):
        rendered = [s for s in active if 'rendered' in s and 'skipped' in s]
        if len(rendered) < 2:
            reasons.append('missing_render_evidence')
        metrics = output_metrics(data)
        if metrics['maxFrozenRenderedWithSkipsSeconds'] >= 5:
            reasons.append('rendered_frozen')
        if metrics['renderedFps'] == 0 and metrics['renderedSecondsMeasured'] >= 5:
            reasons.append('no_render_progress')
    observed = observed_format(data)
    if observed['codec'] == 'conflict':
        reasons.append('codec_conflict')
    if observed['matchesRequested'] is False:
        reasons.append('fixture_mismatch')
    elif observed['matchesRequested'] is None:
        reasons.append('fixture_unverified')
    return {'passed': not reasons, 'reasons': reasons}
