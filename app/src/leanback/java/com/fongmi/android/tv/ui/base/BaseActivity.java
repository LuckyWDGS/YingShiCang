package com.fongmi.android.tv.ui.base;

import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.View;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.utils.Util;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import me.jessyan.autosize.AutoSizeCompat;

public abstract class BaseActivity extends AppCompatActivity {

    private float convertedDensity;
    private float convertedScaledDensity;
    private int convertedDensityDpi;

    protected abstract ViewBinding getBinding();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(getBinding().getRoot());
        EventBus.getDefault().register(this);
        initView(savedInstanceState);
        Util.hideSystemUI(this);
        setBackCallback();
        initEvent();
    }

    protected FragmentActivity getActivity() {
        return this;
    }

    /** Mobile's wallpaper hook (the shared CrashActivity opts out); TV paints its backdrop with the theme's windowBackground. */
    protected boolean customWall() {
        return true;
    }

    protected void initView(Bundle savedInstanceState) {
    }

    protected void initEvent() {
    }

    protected boolean isVisible(View view) {
        return view.getVisibility() == View.VISIBLE;
    }

    protected boolean isGone(View view) {
        return view.getVisibility() == View.GONE;
    }

    protected void notifyItemChanged(RecyclerView view, RecyclerView.Adapter<?> adapter) {
        view.post(() -> adapter.notifyDataSetChanged());
    }

    private void setBackCallback() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                onBackInvoked();
            }
        });
    }

    private Resources hackResources(Resources resources) {
        // getResources() runs constantly (inflation, colors, text); AutoSize only needs to run again when
        // something (e.g. WebView, a configuration change) has reset the activity or application metrics.
        // It refuses to run off the main thread anyway, throwing an exception each time.
        if (Looper.myLooper() != Looper.getMainLooper()) return resources;
        DisplayMetrics metrics = resources.getDisplayMetrics();
        DisplayMetrics app = App.get().getResources().getDisplayMetrics();
        if (convertedDensity != 0 && metrics.density == convertedDensity && metrics.scaledDensity == convertedScaledDensity
                && metrics.densityDpi == convertedDensityDpi && app.density == convertedDensity) return resources;
        try {
            AutoSizeCompat.autoConvertDensityOfGlobal(resources);
        } catch (Exception ignored) {
            return resources;
        }
        convertedDensity = metrics.density;
        convertedScaledDensity = metrics.scaledDensity;
        convertedDensityDpi = metrics.densityDpi;
        return resources;
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onSubscribe(Object o) {
    }

    @Override
    public Resources getResources() {
        return hackResources(super.getResources());
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Util.hideSystemUI(this);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Util.hideSystemUI(this);
    }

    protected void onBackInvoked() {
        finish();
    }

    @Override
    protected void onDestroy() {
        EventBus.getDefault().unregister(this);
        super.onDestroy();
    }
}
