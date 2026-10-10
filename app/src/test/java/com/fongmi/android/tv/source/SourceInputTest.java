package com.fongmi.android.tv.source;
import org.junit.Test;
import static org.junit.Assert.*;
public class SourceInputTest {
 @Test public void preservesQueriesAndDefaultsName(){String url=SourceInput.url("  https://example.com/config?key=a%2Bb  ");assertEquals("https://example.com/config?key=a%2Bb",url);assertEquals("example.com",SourceInput.name("",url));assertEquals("饭太硬",SourceInput.name(" 饭太硬 ",url));}
 @Test public void rejectsMalformedAndUnsafeAddresses(){for(String url:new String[]{"","javascript:alert(1)","file:///tmp/config","https://","http://user:pass@host/a","https://host/a b","https://host/#x"}){try{SourceInput.url(url);fail(url);}catch(IllegalArgumentException expected){}}}
 @Test public void validatesNames(){try{SourceInput.name("a".repeat(61),"https://example.com");fail();}catch(IllegalArgumentException expected){}try{SourceInput.name("a\nb","https://example.com");fail();}catch(IllegalArgumentException expected){}}
}
