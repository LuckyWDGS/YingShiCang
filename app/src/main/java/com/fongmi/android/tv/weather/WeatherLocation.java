package com.fongmi.android.tv.weather;

import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.Set;

/** Location identity and language are independent of the phone's UI language. */
public record WeatherLocation(String city, String country, double latitude, double longitude, boolean approximate) {
    public WeatherLocation {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude) || Math.abs(latitude) > 90 || Math.abs(longitude) > 180)
            throw new IllegalArgumentException("Invalid coordinates");
        country = country == null ? "" : country.toUpperCase(Locale.ROOT);
        city = city == null ? "" : city.trim();
    }
    public static boolean chinese(String country) { return country != null && Set.of("CN", "HK", "MO", "TW").contains(country.toUpperCase(Locale.ROOT)); }
    public static String language(String country) { return chinese(country) ? "zh-CN" : "en"; }
    public static WeatherLocation fromIp(JsonObject data) {
        if (!data.has("success") || !data.get("success").getAsBoolean()) throw new IllegalArgumentException("Location unavailable");
        return new WeatherLocation(data.get("city").getAsString(), data.get("country_code").getAsString(),
                data.get("latitude").getAsDouble(), data.get("longitude").getAsDouble(), true);
    }
    public String displayCity() {
        if (chinese(country) && city.codePoints().noneMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)) return "当前位置";
        return city.isEmpty() ? (country.isEmpty() || chinese(country) ? "当前位置" : "Current location") : city;
    }
    public boolean sameArea(double lat, double lon) {
        if (!Double.isFinite(lat) || !Double.isFinite(lon)) return false;
        double a = Math.toRadians(latitude - lat), b = Math.toRadians(longitude - lon);
        double h = Math.sin(a / 2) * Math.sin(a / 2) + Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(latitude)) * Math.sin(b / 2) * Math.sin(b / 2);
        return 6371 * 2 * Math.asin(Math.sqrt(Math.min(1, h))) < 10;
    }
}
