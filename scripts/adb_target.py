"""Resolve only verified local Android emulators; USB/remote targets are rejected."""
import os
import pathlib
import re
import subprocess


def verified_adb(serial):
    sdk = pathlib.Path(os.environ.get("ANDROID_HOME", pathlib.Path.home()/"AppData/Local/Android/Sdk"))
    base = [str(sdk/"platform-tools/adb.exe"), "-s", serial]
    # AVD may bind 127.0.0.1:5555 while LDPlayer binds 0.0.0.0:5555.
    # The second loopback address reaches LDPlayer without stopping either VM.
    if not (re.fullmatch(r"emulator-\d+", serial) or re.fullmatch(r"127\.0\.0\.[12]:\d+", serial)):
        raise ValueError("Physical and remote devices are forbidden for this task")
    def prop(name):
        return subprocess.check_output(base+["shell","getprop",name],timeout=15).strip().decode()
    avd = prop("ro.kernel.qemu") == "1"
    ldplayer = prop("ro.boot.qemu") == "1" and prop("ro.dalvik.vm.native.bridge") == "libhoudini.so"
    if not avd and not ldplayer:
        raise ValueError("Target is not a verified AVD or LDPlayer emulator")
    return base
