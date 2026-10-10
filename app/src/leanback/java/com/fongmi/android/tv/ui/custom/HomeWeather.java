package com.fongmi.android.tv.ui.custom;

import android.app.Activity;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import android.view.View;
import com.fongmi.android.tv.weather.WeatherInfo;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Automatic coarse network location; no manual city entry or location permission. */
public final class HomeWeather {
    private static final long MAX_AGE = TimeUnit.MINUTES.toMillis(30);
    private final Activity activity;
    private final View view;
    private final TextView info;
    private final AnimatedWeatherView icon;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final OkHttpClient client = new OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).build();
    private Call call;
    private int generation;
    private boolean active;
    private final Runnable refreshTimer = this::refresh;

    public HomeWeather(Activity activity, View view, TextView info, AnimatedWeatherView icon) {
        this.activity = activity;
        this.view = view;
        this.info = info;
        this.icon = icon;
        prefs = activity.getSharedPreferences("home_weather", Activity.MODE_PRIVATE);
        // Do not reuse a manually selected city's forecast after switching to automatic location.
        if (prefs.contains("latitude") || prefs.contains("longitude") || prefs.contains("city")) {
            prefs.edit().remove("city").remove("latitude").remove("longitude")
                    .remove("weather_city").remove("weather_code").remove("temperature")
                    .remove("is_day").remove("updated").remove("display").apply();
        }
    }

    public void refresh() {
        active = true;
        icon.setActive(true);
        handler.removeCallbacks(refreshTimer);
        handler.postDelayed(refreshTimer, MAX_AGE);
        long age = System.currentTimeMillis() - prefs.getLong("updated", 0);
        boolean cached = prefs.contains("weather_code");
        if (cached) showCached(!(age < MAX_AGE && age >= 0));
        if (cached && age < MAX_AGE && age >= 0) return;
        if (call != null) call.cancel();
        int token = ++generation;
        if (!cached) { info.setText("—°C"); icon.setWeather(-1, true); view.setContentDescription("正在自动定位并获取天气"); }
        request("https://ipwho.is/?lang=zh", token, json -> {
            if (!json.has("success") || !json.get("success").getAsBoolean()) throw new IOException("City unavailable");
            forecast(token, json.get("city").getAsString(), json.get("latitude").getAsString(), json.get("longitude").getAsString());
        });
    }

    private void forecast(int token, String city, String lat, String lon) {
        request("https://api.open-meteo.com/v1/forecast?latitude=" + Uri.encode(lat) + "&longitude=" + Uri.encode(lon) + "&current=temperature_2m,weather_code,is_day&timezone=auto", token, json -> {
            JsonObject current = json.getAsJsonObject("current");
            double temperature = current.get("temperature_2m").getAsDouble();
            if (!Double.isFinite(temperature)) throw new IOException("Invalid temperature");
            int code = current.get("weather_code").getAsInt();
            boolean day = !current.has("is_day") || current.get("is_day").getAsInt() == 1;
            prefs.edit().putString("weather_city", city).putFloat("temperature", (float) temperature)
                    .putInt("weather_code", code).putBoolean("is_day", day).putLong("updated", System.currentTimeMillis()).remove("display").apply();
            showCached(false);
        });
    }

    private void showCached(boolean stale) {
        String city = prefs.getString("weather_city", "");
        int code = prefs.getInt("weather_code", -1);
        String text = String.format(Locale.CHINA, "%s  %.0f°C", city, prefs.getFloat("temperature", 0));
        info.setText(text + (stale ? " · 待更新" : ""));
        icon.setWeather(code, prefs.getBoolean("is_day", true));
        icon.setAlpha(stale ? .6f : 1f);
        view.setContentDescription(text + "，" + WeatherInfo.describe(code) + (stale ? "，待更新" : "") + "，城市自动定位");
    }

    private interface Result { void accept(JsonObject json) throws Exception; }

    private void request(String url, int token, Result result) {
        call = client.newCall(new Request.Builder().url(url).build());
        call.enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException error) { handler.post(() -> fail(token)); }
            @Override public void onResponse(Call call, Response response) {
                try (response) {
                    if (!response.isSuccessful() || response.body() == null) throw new IOException("Unavailable");
                    JsonObject json = JsonParser.parseString(response.body().string()).getAsJsonObject();
                    handler.post(() -> {
                        if (!valid(token)) return;
                        try { result.accept(json); } catch (Exception error) { fail(token); }
                    });
                } catch (Exception error) { handler.post(() -> fail(token)); }
            }
        });
    }

    private boolean valid(int token) { return active && token == generation && !activity.isFinishing() && !activity.isDestroyed(); }
    private void fail(int token) {
        if (!valid(token)) return;
        if (prefs.contains("weather_code")) showCached(true);
        else { info.setText("—°C"); icon.setWeather(-1, true); view.setContentDescription("自动定位天气暂不可用"); }
    }
    public void stop() {
        active = false;
        icon.setActive(false);
        generation++;
        handler.removeCallbacks(refreshTimer);
        if (call != null) call.cancel();
    }
}
