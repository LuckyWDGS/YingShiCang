package com.fongmi.android.tv.api.loader;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.junit.Assert.*;

public class JarArtifactTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private void jar(File file) throws IOException {
        try(var out=new ZipOutputStream(Files.newOutputStream(file.toPath()))) {
            out.putNextEntry(new ZipEntry("classes.dex"));out.write(new byte[]{1,2,3});out.closeEntry();
        }
    }
    private File target(){ return new File(temp.getRoot(),"source.jar"); }
    private JarArtifact.Fetcher offline(){return f->{Files.writeString(f.toPath(),"partial");throw new IOException("connection closed");};}
    private void seed(File target)throws Exception{JarArtifact.load(target,"identity","",100,this::jar,f->{});}

    @Test public void failedDownloadNeverTruncatesPreviousJarAndRetryRecovers()throws Exception{
        File target=target();seed(target);byte[] old=Files.readAllBytes(target.toPath());AtomicInteger calls=new AtomicInteger();
        JarArtifact.load(target,"identity","",200,f->{assertArrayEquals(old,Files.readAllBytes(target.toPath()));if(calls.incrementAndGet()==1)throw new IOException();jar(f);},f->{});
        assertEquals(2,calls.get());assertTrue(target.isFile());assertEquals(2,temp.getRoot().list().length);
    }
    @Test public void fallbackRequiresSuccessfulActivationAndExactIdentity()throws Exception{
        File target=target();seed(target);AtomicInteger loads=new AtomicInteger();
        JarArtifact.load(target,"identity","",200,offline(),f->loads.incrementAndGet());assertEquals(1,loads.get());
        assertThrows(IOException.class,()->JarArtifact.load(target,"other-revision","",200,offline(),f->fail()));
        Files.delete(new File(target+".verified").toPath());
        assertThrows(IOException.class,()->JarArtifact.load(target,"identity","",200,offline(),f->fail()));
    }
    @Test public void expiredCorruptAndTamperedCacheCannotLoad()throws Exception{
        File target=target();seed(target);
        assertThrows(IOException.class,()->JarArtifact.load(target,"identity","",100+86400000,offline(),f->fail()));
        Files.writeString(target.toPath(),"bad");
        assertThrows(IOException.class,()->JarArtifact.load(target,"identity","",200,offline(),f->fail()));
        Files.writeString(new File(target+".verified").toPath(),"200");
        assertThrows(IOException.class,()->JarArtifact.load(target,"identity","",200,offline(),f->fail()));
    }
    @Test public void failedInitializationRollsBackAndCannotCreateSuccessMarker()throws Exception{
        File target=target();seed(target);byte[] old=Files.readAllBytes(target.toPath());
        assertThrows(IllegalStateException.class,()->JarArtifact.load(target,"new","",200,this::jar,f->{throw new IllegalStateException();}));
        assertArrayEquals(old,Files.readAllBytes(target.toPath()));
        JarArtifact.load(target,"identity","",300,offline(),f->{});
    }
    @Test public void htmlOrWrongChecksumNeverLoads()throws Exception{
        assertThrows(IOException.class,()->JarArtifact.load(target(),"identity","",100,f->Files.writeString(f.toPath(),"<html>error</html>"),f->fail()));
        assertThrows(IOException.class,()->JarArtifact.load(target(),"identity","00000000000000000000000000000000",100,this::jar,f->fail()));
        assertFalse(target().exists());
    }
    @Test public void canceledRequestCannotFallbackOrRetry()throws Exception{
        File target=target();seed(target);AtomicInteger calls=new AtomicInteger();
        assertThrows(IOException.class,()->JarArtifact.load(target,"identity","",200,f->{calls.incrementAndGet();throw new IOException("Canceled");},f->fail()));
        assertEquals(1,calls.get());
        try {Thread.currentThread().interrupt();assertThrows(CancellationException.class,()->JarArtifact.load(target,"identity","",200,this::jar,f->fail()));}
        finally {Thread.interrupted();}
    }
    @Test public void fallbackDoesNotExtendItsOwnFreshness()throws Exception{
        File target=target();seed(target);String marker=Files.readString(new File(target+".verified").toPath());
        JarArtifact.load(target,"identity","",200,offline(),f->{});
        assertEquals(marker,Files.readString(new File(target+".verified").toPath()));
    }
    @Test public void rawDexRemainsSupported()throws Exception{
        JarArtifact.load(target(),"dex","",100,f->Files.write(f.toPath(),new byte[]{'d','e','x','\n','0','3','5',0}),f->{});
        assertTrue(target().isFile());
    }
    @Test public void changedRemoteChecksumCannotUseOldVerifiedArtifact()throws Exception{
        File target=target();seed(target);
        assertThrows(IOException.class,()->JarArtifact.load(target,"identity","00000000000000000000000000000000",200,offline(),f->fail()));
    }

    private String hash(File file,String algorithm)throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(Files.readAllBytes(file.toPath())));
    }
    private JarArtifact.Bundle bundle(File file)throws Exception {
        return new JarArtifact.Bundle(hash(file,"SHA-256"),f->Files.copy(file.toPath(),f.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING));
    }
    @Test public void firstOfflineInstallUsesBundleWithoutCreatingOnlineSuccessMarker()throws Exception {
        File packaged=temp.newFile("packaged.jar");jar(packaged);AtomicInteger requests=new AtomicInteger(),loads=new AtomicInteger();
        JarArtifact.load(target(),"identity",hash(packaged,"MD5"),100,f->{requests.incrementAndGet();throw new IOException("offline");},f->loads.incrementAndGet(),bundle(packaged));
        assertEquals(2,requests.get());assertEquals(1,loads.get());assertArrayEquals(Files.readAllBytes(packaged.toPath()),Files.readAllBytes(target().toPath()));
        assertFalse(new File(target()+".verified").exists());
        JarArtifact.load(target(),"identity",hash(packaged,"MD5"),200,f->fail(),f->loads.incrementAndGet(),bundle(packaged));
        assertEquals(2,loads.get());assertFalse(new File(target()+".verified").exists());
    }
    @Test public void onlineUpdateWinsAndNeverCopiesBundle()throws Exception {
        JarArtifact.Bundle unused=new JarArtifact.Bundle("unused",f->fail("online succeeded"));
        JarArtifact.load(target(),"identity","",100,this::jar,f->{},unused);
        assertTrue(new File(target()+".verified").isFile());
    }
    @Test public void failedOnlineUpdateKeepsGoodCacheAheadOfBundle()throws Exception {
        seed(target());String marker=Files.readString(new File(target()+".verified").toPath());
        JarArtifact.load(target(),"identity","",200,offline(),f->{},new JarArtifact.Bundle("old",f->fail()));
        assertEquals(marker,Files.readString(new File(target()+".verified").toPath()));
    }
    @Test public void expiredDifferentOnlineVersionCannotDowngradeToBundle()throws Exception {
        seed(target());byte[] before=Files.readAllBytes(target().toPath());
        assertThrows(IOException.class,()->JarArtifact.load(target(),"identity","",100+86400000,offline(),f->fail(),new JarArtifact.Bundle("older",f->fail())));
        assertArrayEquals(before,Files.readAllBytes(target().toPath()));
    }
    @Test public void bundleCorruptionAndChangedProviderChecksumCannotLoad()throws Exception {
        File packaged=temp.newFile("packaged.jar");jar(packaged);
        assertThrows(IOException.class,()->JarArtifact.load(target(),"identity","",100,offline(),f->fail(),new JarArtifact.Bundle("wrong",this::jar)));
        assertThrows(IOException.class,()->JarArtifact.load(target(),"identity","00000000000000000000000000000000",100,offline(),f->fail(),bundle(packaged)));
        assertFalse(target().exists());
    }
    @Test public void canceledDownloadNeverCopiesBundle()throws Exception {
        assertThrows(IOException.class,()->JarArtifact.load(target(),"identity","",100,f->{throw new IOException("Canceled");},f->fail(),new JarArtifact.Bundle("unused",f->fail())));
    }
    @Test public void failedBundleInitializationRollsBackWithoutSuccessMarker()throws Exception {
        File packaged=temp.newFile("packaged.jar");jar(packaged);Files.writeString(target().toPath(),"old unrelated bytes");
        assertThrows(IllegalStateException.class,()->JarArtifact.load(target(),"identity","",100,offline(),f->{throw new IllegalStateException();},bundle(packaged)));
        assertEquals("old unrelated bytes",Files.readString(target().toPath()));assertFalse(new File(target()+".verified").exists());
    }
    @Test public void declaredChecksumChangeReplacesBundledArtifactOnline()throws Exception {
        File packaged=temp.newFile("packaged.jar");jar(packaged);
        JarArtifact.load(target(),"identity",hash(packaged,"MD5"),100,offline(),f->{},bundle(packaged));
        File updated=temp.newFile("updated.dex");Files.write(updated.toPath(),new byte[]{'d','e','x','\n','0','3','5',0,1,2,3});
        JarArtifact.load(target(),"updated-identity",hash(updated,"MD5"),200,f->Files.copy(updated.toPath(),f.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING),f->{},null);
        assertArrayEquals(Files.readAllBytes(updated.toPath()),Files.readAllBytes(target().toPath()));assertTrue(new File(target()+".verified").isFile());
    }
}
