package com.fongmi.android.tv.ui.custom;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import androidx.core.location.LocationManagerCompat;
import androidx.core.os.CancellationSignal;
import androidx.fragment.app.FragmentActivity;
import com.fongmi.android.tv.weather.WeatherInfo;
import com.fongmi.android.tv.weather.WeatherLocation;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Future;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Fresh foreground location; only an explicit weather tap asks for permission. */
public final class HomeWeather {
    private static final long MAX_AGE = TimeUnit.MINUTES.toMillis(30);
    // A slow system geocoder cannot occupy the shared source/configuration workers.
    private static final ThreadPoolExecutor GEOCODER = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), task -> { Thread thread = new Thread(task, "weather-city"); thread.setDaemon(true); return thread; });
    private final FragmentActivity activity;
    private final View view;
    private final TextView info;
    private final AnimatedWeatherView icon;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final OkHttpClient client = new OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).build();
    private final List<CancellationSignal> signals = new ArrayList<>();
    private Call call;
    private Future<?> geocode;
    private Runnable deadline;
    private int generation;
    private boolean active, locating;
    private long checkedAt;
    private String checkedNetwork = "";
    private String requestNetwork = "";
    private WeatherLocation resolvedLocation;
    private final Runnable refreshTimer = this::refresh;

    public HomeWeather(FragmentActivity activity, View view, TextView info, AnimatedWeatherView icon) {
        this.activity = activity; this.view = view; this.info = info; this.icon = icon;
        prefs = activity.getSharedPreferences("home_weather", Context.MODE_PRIVATE);
        if (prefs.getInt("schema", 0) != 2) prefs.edit().clear().putInt("schema", 2).apply();
        // Display only (user 2026-10-23): remote focus never stops here. Location is used when already
        // granted, otherwise the IP estimate.
    }

    public void refresh() {
        active = true; icon.setActive(true);
        handler.removeCallbacks(refreshTimer);
        handler.postDelayed(refreshTimer, MAX_AGE);
        String network = network();
        // Brief dialog returns need no new call; a new launch, network or elapsed minute rechecks location.
        if (checkedAt > 0 && SystemClock.elapsedRealtime() - checkedAt < 60000 && network.equals(checkedNetwork) && fresh()) {
            showCached(false); return;
        }
        cancel();
        int token = ++generation;
        requestNetwork = network;
        resolvedLocation = null;
        if (prefs.contains("weather_code")) showCached(true);
        else unavailable("正在确认当前位置");
        if (hasLocationPermission() && locateDevice(token)) return;
        locateIp(token);
    }

    private String network() {
        try { return String.valueOf(((ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE)).getActiveNetwork()); }
        catch (RuntimeException ignored) { return ""; }
    }
    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean locateDevice(int token) {
        LocationManager manager = (LocationManager) activity.getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) return false;
        locating = true;
        for (String provider : new String[]{LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER}) {
            if (!locating) break;
            try {
                if (!manager.isProviderEnabled(provider)) continue;
                CancellationSignal signal = new CancellationSignal(); signals.add(signal);
                LocationManagerCompat.getCurrentLocation(manager, provider, signal, command -> handler.post(command), location -> {
                    if (!valid(token) || !locating || !freshLocation(location)) return;
                    locating = false; cancelLocation();
                    reverse(token, location);
                });
            } catch (RuntimeException ignored) { }
        }
        if (signals.isEmpty()) { locating = false; return false; }
        deadline = () -> { if (valid(token) && locating) { locating = false; cancelLocation(); locateIp(token); } };
        handler.postDelayed(deadline, 12000);
        return true;
    }

    private boolean freshLocation(Location location) {
        if (location == null || !location.hasAccuracy() || location.getAccuracy() > 20000) return false;
        long age = SystemClock.elapsedRealtime() - TimeUnit.NANOSECONDS.toMillis(location.getElapsedRealtimeNanos());
        return age >= 0 && age < TimeUnit.MINUTES.toMillis(2);
    }

    private void reverse(int token, Location location) {
        WeatherLocation unnamed = new WeatherLocation("", "", location.getLatitude(), location.getLongitude(), false);
        // Device coordinates stay authoritative if the optional city-name lookup is unavailable.
        deadline = () -> { if (valid(token)) { generation++; if (geocode != null) geocode.cancel(true); forecast(generation, unnamed); } };
        handler.postDelayed(deadline, 8000);
        try { geocode = GEOCODER.submit(() -> {
            WeatherLocation result = unnamed;
            try {
                if (Geocoder.isPresent()) {
                    List<Address> rows = new Geocoder(activity.getApplicationContext(), Locale.ENGLISH).getFromLocation(location.getLatitude(), location.getLongitude(), 1);
                    if (rows != null && !rows.isEmpty()) {
                        Address address = rows.get(0);
                        String country = address.getCountryCode();
                        if (WeatherLocation.chinese(country)) {
                            rows = new Geocoder(activity.getApplicationContext(), Locale.SIMPLIFIED_CHINESE).getFromLocation(location.getLatitude(), location.getLongitude(), 1);
                            if (rows != null && !rows.isEmpty()) address = rows.get(0);
                        }
                        String city = address.getLocality();
                        if (city == null || city.isEmpty()) city = address.getSubAdminArea();
                        if (city == null || city.isEmpty()) city = address.getAdminArea();
                        result = new WeatherLocation(city, country, location.getLatitude(), location.getLongitude(), false);
                    }
                }
            } catch (Exception ignored) { }
            WeatherLocation resolved = result;
            handler.post(() -> { if (valid(token)) { handler.removeCallbacks(deadline); forecast(token, resolved); } });
        }); } catch (RejectedExecutionException ignored) {
            handler.removeCallbacks(deadline);
            forecast(token, unnamed);
        }
    }

    private void locateIp(int token) {
        request("https://ipwho.is/?lang=en", token, json -> {
            WeatherLocation location = WeatherLocation.fromIp(json);
            if (!WeatherLocation.chinese(location.country())) { forecast(token, location); return; }
            String ip = json.has("ip") ? json.get("ip").getAsString() : "";
            // Keep both language lookups bound to the same IP, not two potentially different exits.
            if (!ip.matches("[0-9a-fA-F:.]+")) throw new IOException("Invalid IP location");
            request("https://ipwho.is/" + Uri.encode(ip) + "?lang=zh-CN", token, translated -> {
                WeatherLocation localized = WeatherLocation.fromIp(translated);
                if (!location.country().equals(localized.country()) || !location.sameArea(localized.latitude(), localized.longitude()))
                    throw new IOException("Location changed during translation");
                forecast(token, localized);
            });
        });
    }

    private void forecast(int token, WeatherLocation location) {
        if (!valid(token)) return;
        resolvedLocation = location;
        double oldLat = Double.longBitsToDouble(prefs.getLong("lat_bits", Double.doubleToLongBits(Double.NaN)));
        double oldLon = Double.longBitsToDouble(prefs.getLong("lon_bits", Double.doubleToLongBits(Double.NaN)));
        if (fresh() && location.sameArea(oldLat, oldLon)) {
            saveLocation(location).apply(); checkedAt = SystemClock.elapsedRealtime(); checkedNetwork = requestNetwork; showCached(false); return;
        }
        // Do not leave the previous city's temperature beside the newly resolved city.
        info.setText(location.displayCity() + "  —°C"); icon.setWeather(-1, true);
        request("https://api.open-meteo.com/v1/forecast?latitude=" + location.latitude() + "&longitude=" + location.longitude()
                + "&current=temperature_2m,weather_code,is_day&timezone=auto", token, json -> {
            JsonObject current = json.getAsJsonObject("current");
            double temperature = current.get("temperature_2m").getAsDouble();
            if (!Double.isFinite(temperature)) throw new IOException("Invalid temperature");
            saveLocation(location).putFloat("temperature", (float) temperature).putInt("weather_code", current.get("weather_code").getAsInt())
                    .putBoolean("is_day", current.get("is_day").getAsInt() == 1).putLong("updated", System.currentTimeMillis()).apply();
            checkedAt = SystemClock.elapsedRealtime(); checkedNetwork = requestNetwork; showCached(false);
        });
    }

    private SharedPreferences.Editor saveLocation(WeatherLocation location) {
        return prefs.edit().putString("weather_city", location.displayCity()).putString("country", location.country()).putBoolean("approximate", location.approximate())
                .putLong("lat_bits", Double.doubleToLongBits(location.latitude())).putLong("lon_bits", Double.doubleToLongBits(location.longitude()));
    }
    private boolean fresh() {
        long age = System.currentTimeMillis() - prefs.getLong("updated", 0);
        return prefs.contains("weather_code") && age >= 0 && age < MAX_AGE;
    }
    private void showCached(boolean stale) {
        boolean cn = WeatherLocation.chinese(prefs.getString("country", ""));
        boolean approximate = prefs.getBoolean("approximate", true);
        String text = String.format(Locale.ROOT, "%s  %.0f°C", prefs.getString("weather_city", ""), prefs.getFloat("temperature", 0));
        info.setText((stale ? (cn ? "上次 · " : "Last · ") : "") + text);
        int code = prefs.getInt("weather_code", -1);
        icon.setWeather(code, prefs.getBoolean("is_day", true)); icon.setAlpha(stale ? .6f : 1f);
        view.setContentDescription(text + "，" + WeatherInfo.describe(code) + (stale ? "，上次天气" : approximate ? "，IP估算位置" : "，设备定位"));
    }
    private void unavailable(String description) {
        info.setText("—°C"); icon.setWeather(-1, true); view.setContentDescription(description);
    }
    private interface Result { void accept(JsonObject json) throws Exception; }
    private void request(String url, int token, Result result) {
        if (!valid(token)) return;
        call = client.newCall(new Request.Builder().url(url).build());
        call.enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException error) { handler.post(() -> fail(token)); }
            @Override public void onResponse(Call call, Response response) {
                try (response) {
                    if (!response.isSuccessful() || response.body() == null) throw new IOException("Unavailable");
                    JsonObject json = JsonParser.parseString(response.body().string()).getAsJsonObject();
                    handler.post(() -> { if (valid(token)) try { result.accept(json); } catch (Exception error) { fail(token); } });
                } catch (Exception error) { handler.post(() -> fail(token)); }
            }
        });
    }
    private boolean valid(int token) {
        if (!active || token != generation || activity.isFinishing() || activity.isDestroyed()) return false;
        if (requestNetwork.equals(network())) return true;
        // A response from the old network must not become the new network's cached location.
        checkedAt = 0;
        refresh();
        return false;
    }
    private void fail(int token) {
        if (!valid(token)) return;
        if (resolvedLocation != null && !resolvedLocation.sameArea(
                Double.longBitsToDouble(prefs.getLong("lat_bits", Double.doubleToLongBits(Double.NaN))),
                Double.longBitsToDouble(prefs.getLong("lon_bits", Double.doubleToLongBits(Double.NaN))))) {
            info.setText(resolvedLocation.displayCity() + "  —°C");
            icon.setWeather(-1, true);
            view.setContentDescription(resolvedLocation.displayCity() + "，天气暂不可用");
            return;
        }
        if (prefs.contains("weather_code")) showCached(true); else unavailable("天气暂不可用");
    }
    private void cancelLocation() {
        if (deadline != null) handler.removeCallbacks(deadline);
        List<CancellationSignal> pending = new ArrayList<>(signals);
        signals.clear();
        for (CancellationSignal signal : pending) signal.cancel();
    }
    private void cancel() {
        locating = false; cancelLocation();
        if (geocode != null) geocode.cancel(true);
        GEOCODER.purge();
        if (call != null) call.cancel();
    }
    public void stop() {
        active = false; icon.setActive(false); generation++;
        handler.removeCallbacks(refreshTimer); cancel();
    }
}
