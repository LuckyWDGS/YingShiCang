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
    /** Province and city a mainland IP belongs to, as ipip.net (a Chinese IP library) states them. */
    public record Place(String province, String city) {
        public String query() { return city.isEmpty() ? province : city; }
    }

    /**
     * ipip.net is reached over the domestic route and knows Chinese IPs; ipwho.is, abroad, often sees a
     * proxy's exit or a wrong city (LDPlayer host 2026-11-14: ipip 北京, ipwho 洛杉矶). Null for anything
     * but the mainland, which keeps the ipwho.is path.
     */
    public static Place ipip(JsonObject data) {
        if (data == null || !"ok".equals(text(data, "ret")) || !data.has("data") || !data.get("data").isJsonObject()) return null;
        JsonObject body = data.getAsJsonObject("data");
        if (!body.has("location") || !body.get("location").isJsonArray()) return null;
        var location = body.getAsJsonArray("location");
        String country = location.size() > 0 ? location.get(0).getAsString().trim() : "";
        String province = location.size() > 1 ? location.get(1).getAsString().trim() : "";
        String city = location.size() > 2 ? location.get(2).getAsString().trim() : "";
        if (!"中国".equals(country) || province.isEmpty() || Set.of("香港", "澳门", "台湾").contains(province)) return null;
        return new Place(province, city);
    }

    /** The Open-Meteo geocoding result for that place: same province, the city itself, else its capital. */
    public static WeatherLocation pick(JsonObject data, Place place) {
        if (data == null || !data.has("results") || !data.get("results").isJsonArray()) return null;
        String province = bare(place.province());
        String city = bare(place.city());
        JsonObject best = null;
        int bestScore = 0;
        long bestPopulation = -1;
        for (var element : data.getAsJsonArray("results")) {
            if (!element.isJsonObject()) continue;
            JsonObject row = element.getAsJsonObject();
            if (!"CN".equalsIgnoreCase(text(row, "country_code")) || !row.has("latitude") || !row.has("longitude")) continue;
            if (!bare(text(row, "admin1")).equals(province)) continue;
            String name = bare(text(row, "name"));
            String feature = text(row, "feature_code");
            int score = 1;
            if (!city.isEmpty() && (name.equals(city) || bare(text(row, "admin2")).equals(city))) score += 4;
            if (feature.startsWith("PPLA") || feature.equals("PPLC")) score += 2;
            long population = row.has("population") ? row.get("population").getAsLong() : 0;
            if (city.isEmpty() && !(feature.startsWith("PPLA") || feature.equals("PPLC"))) continue;
            if (score > bestScore || (score == bestScore && population > bestPopulation)) {
                best = row; bestScore = score; bestPopulation = population;
            }
        }
        // A lookup by city name must find that city; a lookup by province takes its capital.
        if (best == null || (!city.isEmpty() && bestScore < 5)) return null;
        String shown = city.isEmpty() ? place.province() : place.city();
        return new WeatherLocation(shown, "CN", best.get("latitude").getAsDouble(), best.get("longitude").getAsDouble(), true);
    }

    private static String text(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString().trim() : "";
    }

    /** 广东省 / 北京市 / 延边朝鲜族自治州 compare as 广东 / 北京 / 延边朝鲜族. */
    static String bare(String name) {
        String value = name == null ? "" : name.trim();
        for (String suffix : new String[]{"维吾尔自治区", "壮族自治区", "回族自治区", "自治区", "自治州", "特别行政区", "地区", "省", "市", "盟"})
            if (value.length() > suffix.length() && value.endsWith(suffix)) return value.substring(0, value.length() - suffix.length());
        return value;
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
