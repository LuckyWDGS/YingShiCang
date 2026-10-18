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
}
