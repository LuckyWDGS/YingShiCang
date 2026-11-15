package com.fongmi.android.tv.weather;

import com.google.gson.JsonParser;
import org.junit.Test;
import static org.junit.Assert.*;

public class WeatherLocationTest {
    @Test public void movedFromDanzhouToBaodingInvalidatesForecastEvenWithinThirtyMinutes() {
        WeatherLocation baoding = new WeatherLocation("保定市", "CN", 38.87, 115.48, false);
        assertFalse(baoding.sameArea(19.52, 109.58));
        assertTrue(baoding.sameArea(38.88, 115.49));
        assertFalse(baoding.sameArea(Double.NaN, Double.NaN));
    }
    @Test public void languageFollowsCountryRatherThanSystemLocale() {
        assertEquals("zh-CN", WeatherLocation.language("cn"));
        assertEquals("zh-CN", WeatherLocation.language("HK"));
        assertEquals("en", WeatherLocation.language("DE"));
        assertEquals("en", WeatherLocation.language("US"));
        assertEquals("en", WeatherLocation.language(null));
    }
    @Test public void neverPretendsAnIpResultIsDeviceLocationOrInventsChineseCity() {
        WeatherLocation location = WeatherLocation.fromIp(JsonParser.parseString("{\"success\":true,\"city\":\"Danzhou\",\"country_code\":\"CN\",\"latitude\":19.52,\"longitude\":109.58}").getAsJsonObject());
        assertTrue(location.approximate());
        assertEquals("当前位置", location.displayCity());
        assertEquals("保定市", new WeatherLocation("保定市", "CN", 38.87, 115.48, false).displayCity());
        assertEquals("Berlin", new WeatherLocation("Berlin", "DE", 52.52, 13.4, true).displayCity());
    }
    @Test public void rejectsFailedAndImpossibleLocationData() {
        assertThrows(IllegalArgumentException.class, () -> WeatherLocation.fromIp(JsonParser.parseString("{\"success\":false}").getAsJsonObject()));
        assertThrows(IllegalArgumentException.class, () -> new WeatherLocation("bad", "CN", Double.NaN, 115, true));
        assertThrows(IllegalArgumentException.class, () -> new WeatherLocation("bad", "CN", 91, 115, true));
        assertThrows(IllegalArgumentException.class, () -> new WeatherLocation("bad", "CN", 38, -181, true));
    }

    private static com.google.gson.JsonObject json(String text) {
        return com.google.gson.JsonParser.parseString(text).getAsJsonObject();
    }

    @org.junit.Test
    public void aMainlandIpNamesProvinceAndCity() {
        WeatherLocation.Place place = WeatherLocation.ipip(json("{\"ret\":\"ok\",\"data\":{\"ip\":\"x\",\"location\":[\"中国\",\"广东\",\"深圳\",\"\",\"电信\"]}}"));
        org.junit.Assert.assertEquals(new WeatherLocation.Place("广东", "深圳"), place);
        org.junit.Assert.assertEquals("深圳", place.query());
        org.junit.Assert.assertNull(WeatherLocation.ipip(json("{\"ret\":\"ok\",\"data\":{\"location\":[\"美国\",\"加利福尼亚州\",\"洛杉矶\"]}}")));
        org.junit.Assert.assertNull(WeatherLocation.ipip(json("{\"ret\":\"ok\",\"data\":{\"location\":[\"中国\",\"香港\",\"\"]}}")));
        org.junit.Assert.assertNull(WeatherLocation.ipip(json("{\"ret\":\"err\"}")));
        org.junit.Assert.assertEquals("吉林", WeatherLocation.ipip(json("{\"ret\":\"ok\",\"data\":{\"location\":[\"中国\",\"吉林\",\"\"]}}")).query());
    }

    @org.junit.Test
    public void theGeocoderAnswerMustBeThatCityInThatProvince() {
        String beijing = "{\"results\":[{\"name\":\"北京\",\"latitude\":39.9,\"longitude\":116.4,\"feature_code\":\"PPLC\",\"country_code\":\"CN\",\"admin1\":\"北京市\",\"population\":18960744},"
                + "{\"name\":\"北京\",\"latitude\":30.7,\"longitude\":108.7,\"feature_code\":\"PPL\",\"country_code\":\"CN\",\"admin1\":\"重庆市\"}]}";
        WeatherLocation location = WeatherLocation.pick(json(beijing), new WeatherLocation.Place("北京", "北京"));
        org.junit.Assert.assertEquals(39.9, location.latitude(), 0.001);
        org.junit.Assert.assertEquals("北京", location.displayCity());
        org.junit.Assert.assertTrue(location.approximate());
        // The same name in another province is not this city.
        org.junit.Assert.assertNull(WeatherLocation.pick(json(beijing), new WeatherLocation.Place("四川", "北京")));
        String guangdong = "{\"results\":[{\"name\":\"广州\",\"latitude\":23.1,\"longitude\":113.3,\"feature_code\":\"PPLA\",\"country_code\":\"CN\",\"admin1\":\"广东\"},"
                + "{\"name\":\"广东村\",\"latitude\":24.0,\"longitude\":115.0,\"feature_code\":\"PPL\",\"country_code\":\"CN\",\"admin1\":\"广东\"}]}";
        // By province: its capital.
        WeatherLocation capital = WeatherLocation.pick(json(guangdong), new WeatherLocation.Place("广东省", ""));
        org.junit.Assert.assertEquals(23.1, capital.latitude(), 0.001);
        org.junit.Assert.assertEquals("广东省", capital.displayCity());
        org.junit.Assert.assertNull(WeatherLocation.pick(json("{}"), new WeatherLocation.Place("广东", "深圳")));
        org.junit.Assert.assertEquals("延边朝鲜族", WeatherLocation.bare("延边朝鲜族自治州"));
        org.junit.Assert.assertEquals("新疆", WeatherLocation.bare("新疆维吾尔自治区"));
    }
}
