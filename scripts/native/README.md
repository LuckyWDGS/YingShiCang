# Rebuilding the Android native engines

`sources.lock.json` pins the tested source revisions. The MPV Android commit also
pins release tarball versions and the NDK. `mpv-android.patch` pins the newer
uavs3d commit and allows reuse of architecture-independent Boost/EAR source.
`uavs3d.patch` preserves Android ARMv7's softfp ABI with the latest NEON code.
Keep the original upstream licenses and source notices with redistributed builds.

Use Linux or WSL with Git, C/C++ build tools, CMake, Meson, Ninja, pkg-config,
Python, NASM, autotools and the dependencies listed by MPV Android. Prefer a Linux
filesystem for the producer; unpacking Boost on `/mnt/e` is considerably slower.

```sh
python3 /path/to/YingShiCang/scripts/native/prepare.py --destination /path/to/new-producer
cd /path/to/new-producer/buildscripts
./download.sh
cores=8 ./buildall.sh --arch armv7l mpv
# Optional: reuse the already downloaded Boost and EAR sources for the second ABI.
export ARCDAV3A_SHARED_DEPS="$PWD/deps/ffmpeg/_build_arcdav3a_armv7l/_deps"
cores=8 ./buildall.sh --arch arm64 mpv
MPV_ANDROID_NATIVE_ONLY=1 cores=8 ./buildall.sh -n
```

Prepare Media3 from its verified archive and overlay, then import both ABIs:

```sh
python3 scripts/prepare_media.py --destination /path/to/media
python3 scripts/sync_media3_native.py --producer /path/to/new-producer \
  --media /path/to/media --readelf /path/to/ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf \
  --backup /path/to/new-private-backup
```

The import verifies ELF ABI, SONAME, shared dependencies, MPV JNI exports and
16 KiB segment alignment before replacing files, and backs up existing libraries.
Set `media3.dir` in ignored `local.properties` (or `MEDIA3_DIR`) to the prepared
Media3 directory. FFmpeg is packaged once as `libffmpeg.so`; the app's FFmpeg JNI
renderer and MPV share this library. Do not mix it with the former split libraries.

Successful compilation establishes build compatibility. Device decoder-path,
seek, repeated open/close, long-playback, HDR and audio tests are separate gates.
Do not use an ARM translation emulator as proof of physical ARM TV stability.
