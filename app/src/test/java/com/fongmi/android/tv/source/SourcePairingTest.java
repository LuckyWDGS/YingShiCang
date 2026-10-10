package com.fongmi.android.tv.source;
import org.junit.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;
public class SourcePairingTest {
 @Test public void requiresOpenedSessionAndExpires(){AtomicLong now=new AtomicLong(100);SourcePairing p=new SourcePairing(now::get);assertFalse(p.valid(""));assertNull(p.login("000000"));p.open();String token=p.token();assertTrue(p.valid(token));assertEquals(token,p.login(p.code()));assertFalse(p.valid("wrong"));now.addAndGet(15*60_000);assertFalse(p.valid(token));p.open();assertNotEquals(token,p.token());}
 @Test public void rateLimitsPairCodeGuesses(){AtomicLong now=new AtomicLong(100);SourcePairing p=new SourcePairing(now::get);p.open();for(int i=0;i<5;i++)assertNull(p.login("invalid"));assertNull(p.login(p.code()));now.addAndGet(60_000);assertEquals(p.token(),p.login(p.code()));}
 @Test public void reopeningWithinSessionDoesNotBreakPhone(){SourcePairing p=new SourcePairing(()->100L);p.open();String token=p.token();p.open();assertEquals(token,p.token());}
}
