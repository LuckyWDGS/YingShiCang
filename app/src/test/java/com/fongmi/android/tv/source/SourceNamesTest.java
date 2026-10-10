package com.fongmi.android.tv.source;
import com.google.gson.JsonParser;
import org.junit.Test;
import static org.junit.Assert.*;
public class SourceNamesTest {
 @Test public void readsAggregateNameBeforeTitle(){assertEquals("饭太硬",SourceNames.metadata(JsonParser.parseString("{\"name\":\"饭太硬\",\"title\":\"别的标题\"}").getAsJsonObject()));}
 @Test public void neverUsesInternalSiteOrDepotEntryName(){assertEquals("",SourceNames.metadata(JsonParser.parseString("{\"sites\":[{\"name\":\"某站点\"}],\"urls\":[{\"name\":\"子线路\"}]}").getAsJsonObject()));}
 @Test public void supportsTitleAndRejectsMalformedName(){assertEquals("电影库",SourceNames.metadata(JsonParser.parseString("{\"name\":{},\"title\":\"电影库\"}").getAsJsonObject()));assertEquals("",SourceNames.metadata(JsonParser.parseString("{\"name\":123}").getAsJsonObject()));}
 @Test public void fallbackOmitsSecretsAndGenericFilenames(){assertEquals("肥猫",SourceNames.fallback("https://example.com/%E8%82%A5%E7%8C%AB.json?token=secret"));assertEquals("example.com",SourceNames.fallback("https://example.com/config.json?token=secret"));assertEquals("example.com",SourceNames.fallback("https://example.com/123456abcdef7890.json"));}
}
