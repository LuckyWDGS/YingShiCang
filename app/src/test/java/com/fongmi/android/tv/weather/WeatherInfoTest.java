package com.fongmi.android.tv.weather;
import com.google.gson.JsonParser;
import java.time.Instant;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class WeatherInfoTest {
    @Test public void distinguishesWeatherFamiliesForAnimation() {
        assertEquals(WeatherInfo.Kind.CLEAR, WeatherInfo.kind(0));
        for (int code : new int[]{1,2}) assertEquals(WeatherInfo.Kind.PARTLY_CLOUDY, WeatherInfo.kind(code));
        assertEquals(WeatherInfo.Kind.CLOUDY, WeatherInfo.kind(3));
        for (int code : new int[]{45,48}) assertEquals(WeatherInfo.Kind.FOG, WeatherInfo.kind(code));
        for (int code : new int[]{51,53,55,61,63,65,80,81,82}) assertEquals(WeatherInfo.Kind.RAIN, WeatherInfo.kind(code));
        for (int code : new int[]{56,57,66,67}) assertEquals(WeatherInfo.Kind.SLEET, WeatherInfo.kind(code));
        for (int code : new int[]{71,73,75,77,85,86}) assertEquals(WeatherInfo.Kind.SNOW, WeatherInfo.kind(code));
        for (int code : new int[]{95,96,99}) assertEquals(WeatherInfo.Kind.THUNDER, WeatherInfo.kind(code));
    }
    @Test public void unknownCodesDoNotInventSunnyWeather() {
        assertEquals(WeatherInfo.Kind.UNKNOWN, WeatherInfo.kind(-1));
        assertEquals(WeatherInfo.Kind.UNKNOWN, WeatherInfo.kind(100));
    }

    private static WeatherInfo.Current wttr(String json) {
        return WeatherInfo.wttr(JsonParser.parseString(json).getAsJsonObject());
    }

    @Test public void theBackupForecastGivesTemperatureAndCondition() {
        // wttr.in, Beijing 2026-11-15 (the fields the app reads).
        WeatherInfo.Current current = wttr("{\"current_condition\":[{\"FeelsLikeC\":\"9\",\"temp_C\":\"12\",\"weatherCode\":\"122\",\"weatherDesc\":[{\"value\":\"Overcast \"}]}],\"weather\":[]}");
        assertEquals(12, current.temperature(), 0);
        assertEquals(3, current.code());
        assertEquals("阴", WeatherInfo.describe(current.code()));
        assertEquals(-5, wttr("{\"current_condition\":[{\"temp_C\":\"-5\",\"weatherCode\":\"338\"}]}").temperature(), 0);
    }

    @Test public void aBackupConditionTheAppDoesNotKnowKeepsTheTemperature() {
        WeatherInfo.Current current = wttr("{\"current_condition\":[{\"temp_C\":\"21\",\"weatherCode\":\"999\"}]}");
        assertEquals(21, current.temperature(), 0);
        assertEquals(-1, current.code());
        assertEquals(-1, wttr("{\"current_condition\":[{\"temp_C\":\"21\"}]}").code());
    }

    @Test public void aBackupAnswerWithoutABelievableTemperatureIsNoForecast() {
        assertNull(wttr("{}"));
        assertNull(wttr("{\"current_condition\":[]}"));
        assertNull(wttr("{\"current_condition\":[{\"weatherCode\":\"113\"}]}"));
        assertNull(wttr("{\"current_condition\":[{\"temp_C\":\"\",\"weatherCode\":\"113\"}]}"));
        assertNull(wttr("{\"current_condition\":[{\"temp_C\":\"NaN\"}]}"));
        assertNull(wttr("{\"current_condition\":[{\"temp_C\":\"999\"}]}"));
    }

    @Test public void backupConditionsFallInTheSameFamilies() {
        assertEquals(WeatherInfo.Kind.CLEAR, WeatherInfo.kind(WeatherInfo.fromWwo(113)));
        assertEquals(WeatherInfo.Kind.PARTLY_CLOUDY, WeatherInfo.kind(WeatherInfo.fromWwo(116)));
        for (int code : new int[]{119, 122}) assertEquals(WeatherInfo.Kind.CLOUDY, WeatherInfo.kind(WeatherInfo.fromWwo(code)));
        for (int code : new int[]{143, 248, 260}) assertEquals(WeatherInfo.Kind.FOG, WeatherInfo.kind(WeatherInfo.fromWwo(code)));
        for (int code : new int[]{176, 263, 266, 293, 296, 299, 302, 305, 308, 353, 356, 359}) assertEquals(WeatherInfo.Kind.RAIN, WeatherInfo.kind(WeatherInfo.fromWwo(code)));
        for (int code : new int[]{182, 185, 281, 284, 311, 314, 317, 320, 362, 365}) assertEquals(WeatherInfo.Kind.SLEET, WeatherInfo.kind(WeatherInfo.fromWwo(code)));
        for (int code : new int[]{179, 227, 230, 323, 326, 329, 332, 335, 338, 350, 368, 371, 374, 377}) assertEquals(WeatherInfo.Kind.SNOW, WeatherInfo.kind(WeatherInfo.fromWwo(code)));
        for (int code : new int[]{200, 386, 389, 392, 395}) assertEquals(WeatherInfo.Kind.THUNDER, WeatherInfo.kind(WeatherInfo.fromWwo(code)));
        assertEquals(WeatherInfo.Kind.UNKNOWN, WeatherInfo.kind(WeatherInfo.fromWwo(0)));
    }

    private static boolean daylight(double latitude, double longitude, String utc) {
        return WeatherInfo.daylight(latitude, longitude, Instant.parse(utc).toEpochMilli());
    }

    @Test public void daylightFollowsTheSunAtThePlace() {
        // Beijing 2026-11-15: sunrise 06:59, sunset 16:59 local (UTC+8).
        assertFalse(daylight(39.9, 116.4, "2026-11-14T22:40:00Z"));
        assertTrue(daylight(39.9, 116.4, "2026-11-14T23:20:00Z"));
        assertTrue(daylight(39.9, 116.4, "2026-11-15T04:00:00Z"));
        assertTrue(daylight(39.9, 116.4, "2026-11-15T08:40:00Z"));
        assertFalse(daylight(39.9, 116.4, "2026-11-15T09:20:00Z"));
        assertFalse(daylight(39.9, 116.4, "2026-11-15T16:00:00Z"));
        // Urumqi keeps Beijing time but its sun is two hours later: still dark at 08:30, light at 18:30 local.
        assertFalse(daylight(43.8, 87.6, "2026-11-15T00:30:00Z"));
        assertTrue(daylight(43.8, 87.6, "2026-11-15T10:30:00Z"));
        // Sydney in June: sunrise 07:00, sunset 16:54 local (UTC+10).
        assertFalse(daylight(-33.87, 151.21, "2026-06-20T20:30:00Z"));
        assertTrue(daylight(-33.87, 151.21, "2026-06-21T02:00:00Z"));
        assertFalse(daylight(-33.87, 151.21, "2026-06-21T07:30:00Z"));
    }
}
