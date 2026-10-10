package com.fongmi.android.tv.weather;

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
}
