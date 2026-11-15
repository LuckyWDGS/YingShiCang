package com.fongmi.android.tv.weather;

import com.google.gson.JsonObject;

public final class WeatherInfo {
    public enum Kind { CLEAR, PARTLY_CLOUDY, CLOUDY, FOG, RAIN, SLEET, SNOW, THUNDER, UNKNOWN }
    private WeatherInfo() {}
    public static Kind kind(int code) {
        return switch (code) {
            case 0 -> Kind.CLEAR;
            case 1, 2 -> Kind.PARTLY_CLOUDY;
            case 3 -> Kind.CLOUDY;
            case 45, 48 -> Kind.FOG;
            case 51, 53, 55, 61, 63, 65, 80, 81, 82 -> Kind.RAIN;
            case 56, 57, 66, 67 -> Kind.SLEET;
            case 71, 73, 75, 77, 85, 86 -> Kind.SNOW;
            case 95, 96, 99 -> Kind.THUNDER;
            default -> Kind.UNKNOWN;
        };
    }
    public static String describe(int code) {
        return switch (code) {
            case 0 -> "晴";
            case 1, 2 -> "多云";
            case 3 -> "阴";
            case 45, 48 -> "雾";
            case 51, 53, 55 -> "毛毛雨";
            case 56, 57, 66, 67 -> "冻雨";
            case 61, 63, 65 -> "雨";
            case 71, 73, 75, 77 -> "雪";
            case 80, 81, 82 -> "阵雨";
            case 85, 86 -> "阵雪";
            case 95, 96, 99 -> "雷雨";
            default -> "天气未知";
        };
    }

    /** What a forecast says about now, in the app's own (WMO) codes. */
    public record Current(double temperature, int code) {}

    /**
     * The backup forecast (wttr.in ?format=j1), asked when Open-Meteo does not answer.
     * Null unless it carries a believable temperature.
     */
    public static Current wttr(JsonObject json) {
        try {
            JsonObject now = json.getAsJsonArray("current_condition").get(0).getAsJsonObject();
            double temperature = Double.parseDouble(now.get("temp_C").getAsString().trim());
            if (!Double.isFinite(temperature) || temperature < -90 || temperature > 60) return null;
            int code = -1;
            try { code = fromWwo(Integer.parseInt(now.get("weatherCode").getAsString().trim())); } catch (RuntimeException ignored) { }
            return new Current(temperature, code);
        } catch (RuntimeException error) {
            return null;
        }
    }

    /** World Weather Online condition codes (wttr.in) as the WMO codes the icon and description know. */
    public static int fromWwo(int code) {
        return switch (code) {
            case 113 -> 0;
            case 116 -> 2;
            case 119, 122 -> 3;
            case 143, 248 -> 45;
            case 260 -> 48;
            case 263, 266 -> 51;
            case 185, 281 -> 56;
            case 284 -> 57;
            case 293, 296 -> 61;
            case 299, 302 -> 63;
            case 305, 308 -> 65;
            case 182, 311, 317, 362 -> 66;
            case 314, 320, 365 -> 67;
            case 323, 326 -> 71;
            case 227, 329, 332 -> 73;
            case 230, 335, 338 -> 75;
            case 350, 374, 377 -> 77;
            case 176, 353 -> 80;
            case 356 -> 81;
            case 359 -> 82;
            case 179, 368 -> 85;
            case 371 -> 86;
            case 200, 386, 389, 392, 395 -> 95;
            default -> -1;
        };
    }

    /**
     * Whether the sun is up at a place (the backup forecast does not say). Low-precision solar position,
     * good to a few minutes around sunrise and sunset.
     */
    public static boolean daylight(double latitude, double longitude, long utcMillis) {
        double days = utcMillis / 86400000.0 + 2440587.5 - 2451545.0;
        double mean = Math.toRadians(normal(357.528 + 0.9856003 * days));
        double ecliptic = Math.toRadians(normal(280.460 + 0.9856474 * days) + 1.915 * Math.sin(mean) + 0.020 * Math.sin(2 * mean));
        double tilt = Math.toRadians(23.439 - 0.0000004 * days);
        double declination = Math.asin(Math.sin(tilt) * Math.sin(ecliptic));
        double ascension = Math.atan2(Math.cos(tilt) * Math.sin(ecliptic), Math.cos(ecliptic));
        double sidereal = Math.toRadians(normal((18.697374558 + 24.06570982441908 * days) * 15 + longitude));
        double hour = sidereal - ascension;
        double lat = Math.toRadians(latitude);
        double elevation = Math.asin(Math.sin(lat) * Math.sin(declination) + Math.cos(lat) * Math.cos(declination) * Math.cos(hour));
        return Math.toDegrees(elevation) > -0.833;
    }

    private static double normal(double degrees) {
        double value = degrees % 360;
        return value < 0 ? value + 360 : value;
    }
}
