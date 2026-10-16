package com.fongmi.android.tv.api.loader;

import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipFile;
import static org.junit.Assert.*;

public class BundledJarTest {
    @Test public void packagedArtifactMatchesPinnedDigestsAndContainsRealBootstrap() throws Exception {
        Path asset = Path.of("src/main/assets", BundledJar.ASSET);
        byte[] bytes = Files.readAllBytes(asset);
        assertEquals(BundledJar.SHA256, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        assertEquals(BundledJar.MD5, HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes)));
        try (ZipFile zip = new ZipFile(asset.toFile())) {
            assertTrue(zip.getEntry("classes.dex").getSize() > 1000);
            assertNotNull(zip.getEntry("assets/ftyshinidie.guard"));
        }
    }

    @Test public void unrelatedSourceCannotReceiveBundleEvenWithMatchingChecksum() {
        assertFalse(BundledJar.matches("https://unrelated.invalid/plugin.jar", BundledJar.MD5));
        assertFalse(BundledJar.matches("https://unrelated.invalid/plugin.jar", ""));
        assertFalse(BundledJar.matches(null, BundledJar.MD5));
        assertFalse(BundledJar.matches("", null));
    }
}
