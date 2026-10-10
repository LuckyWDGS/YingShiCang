package com.fongmi.android.tv.weather;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

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
}
