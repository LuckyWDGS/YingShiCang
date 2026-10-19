# Isolated playback benchmark

This application links the same local Media3 modules as the product, under the
separate package `com.yingshicang.enginebench`. It does not load production
preferences, sources, playback history or account credentials.

Only the root agent/operator may run device commands. `run.py` uses the project's
guarded ADB wrapper and the authorized LDPlayer serial `127.0.0.2:5555` exclusively.
Run `cleanup` even when a playback case fails. Never uninstall the production app.

```powershell
./gradlew -p scripts/engine-benchmark assembleDebug --console=plain
python scripts/engine-benchmark/generate_media.py --help
python scripts/engine-benchmark/run.py prepare --run PATH_TO_NEW_PRIVATE_RUN --apk PATH_TO_BENCHMARK_APK --fixtures PATH_TO_FIXTURES
python scripts/engine-benchmark/run.py matrix --run PATH_TO_RUN --name smoke-hevc --fixture hevc1080 --seconds 30
python scripts/engine-benchmark/run.py matrix --run PATH_TO_RUN --name sustained-hevc --fixture hevc1080 --seconds 600
python scripts/engine-benchmark/run.py case --run PATH_TO_RUN --name gpu-next --mode mpv-hard --fixture hevc1080 --seconds 60 --gpu-next
python scripts/engine-benchmark/summarize.py PATH_TO_RUN
python scripts/engine-benchmark/run.py cleanup --run PATH_TO_RUN
```

Pause native/Gradle builds before collecting performance samples. Reports identify
the requested mode separately from the decoder actually selected and the platform
codec capability list. MPV `hwdec-current` reports its API path; a MediaCodec path
alone does not establish silicon hardware acceleration on an emulator. Exo includes
`MediaCodecInfo.isHardwareAccelerated` when Android exposes it.

The summary's stationary intervals and PSS changes are screening signals, not proof
of a playback stall or memory leak. Decoder counters can reset at fixture loop
boundaries, so the summary labels final counter values accordingly. Check the full
timeline, errors and logcat before selecting a default. Report exact elapsed time,
codec/fixture, actual decoder and device; do not generalize emulator results to an
ARM TV or claim indefinite playback stability.

Reports now include schema version 2, the requested fixture separately from the
observed video MIME/codec/dimensions, lifecycle and Surface events, and sampled
resumed/focus/Surface validity. Rebuild the benchmark APK before collecting new
runs. The runner writes a separate verdict and rejects backgrounding, lost focus
or Surface, incomplete duration, playback/codec errors, unverified or mismatched
fixture format, and Exo output frozen for at least five seconds while skipped
buffers grow. Advancing audio/playback position with zero dropped frames cannot
pass that check. Counter resets split measured intervals; short recovered skips
are reported without being classified as a persistent freeze.

The summary includes final/total skipped buffers, measured Exo rendered-buffer fps
and the longest frozen-render interval with skipped output. These counters describe
submission to the display Surface, not physical display presentation. MPV has no
equivalent rendered counter, so its output fps remains unavailable. Its format
comes from observed track data or sampled codec/video parameters. Case names never
establish the actual format. Historical reports remain readable, but missing
lifecycle/format evidence cannot earn a current strict pass.

Run the pure report-classification tests without a device or build:

```powershell
python -m unittest discover -s scripts/engine-benchmark -p test_report_checks.py -v
```

`prepare` snapshots every production data file while the product is stopped.
`cleanup` checks the full inventory and SHA-256 plus ADB mappings, removes only the
benchmark package and returns the product to Home. Reports/backups belong in ignored
`.codex/session/qa/` and must never be committed or published.
