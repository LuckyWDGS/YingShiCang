package com.fongmi.android.tv.source;
import org.junit.Test;
import static org.junit.Assert.*;
public class SourceInputTest {
 @Test public void acceptsUnicodeDnsWithoutChangingSignedPathOrQuery(){String value=SourceInput.url("http://www.饭太硬.cc/tv?key=a%2Bb&path=%2f");assertTrue(value.startsWith("http://www.xn--"));assertTrue(value.endsWith("/tv?key=a%2Bb&path=%2f"));assertEquals("www.饭太硬.cc",SourceInput.name("",value));assertEquals(value,SourceInput.url(value));}
 @Test public void preservesIpv6AndUnicodePaths(){assertEquals("http://[::1]:9978/tv",SourceInput.url("http://[::1]:9978/tv"));assertTrue(SourceInput.url("https://饭太硬.cc:443/中文.json?a=%2F").endsWith(":443/中文.json?a=%2F"));}
 @Test public void unicodeHostDoesNotRelaxAuthorityRules(){for(String url:new String[]{"https://user@饭太硬.cc/tv","https://饭太硬.cc:99999/tv","https://饭太硬.cc:/tv","https://饭太硬.cc:abc/tv","https://饭太硬.cc/a\nb","https://饭太硬.cc/\u00a0b","https://饭太硬.cc/#token","http://饭太硬.cc\\evil/tv"}){try{SourceInput.url(url);fail(url);}catch(IllegalArgumentException expected){}}}
 @Test public void preservesQueriesAndDefaultsName(){String url=SourceInput.url("  https://example.com/config?key=a%2Bb  ");assertEquals("https://example.com/config?key=a%2Bb",url);assertEquals("example.com",SourceInput.name("",url));assertEquals("饭太硬",SourceInput.name(" 饭太硬 ",url));}
 @Test public void rejectsMalformedAndUnsafeAddresses(){for(String url:new String[]{"","javascript:alert(1)","file:///tmp/config","https://","http://user:pass@host/a","https://host/a b","https://host/#x"}){try{SourceInput.url(url);fail(url);}catch(IllegalArgumentException expected){}}}
 @Test public void validatesNames(){try{SourceInput.name("a".repeat(61),"https://example.com");fail();}catch(IllegalArgumentException expected){}try{SourceInput.name("a\nb","https://example.com");fail();}catch(IllegalArgumentException expected){}}
}
