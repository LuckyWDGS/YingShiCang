package com.fongmi.android.tv.maintenance;

/**
 * The package GitHubRelease.select picked for this device from a release's manifest, already checked against
 * GitHub's declared size and SHA-256. Updates come from GitHub only, so nothing parses a manifest of its own.
 */
public final class UpdateManifest {
    public int schemaVersion;
    public String packageName, flavor, versionName, url, sha256, notes;
    public int versionCode, minSdk;
    public long size;
    public String[] abis;
}
