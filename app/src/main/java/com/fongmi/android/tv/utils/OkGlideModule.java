package com.fongmi.android.tv.utils;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.Registry;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory;
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.module.AppGlideModule;
import com.github.catvod.net.OkHttp;

import java.io.InputStream;

@GlideModule
public class OkGlideModule extends AppGlideModule {

    // Glide's default is 250 MB; posters are cached at display size (ImgUtil), so 100 MB holds thousands.
    public static final long IMAGE_CACHE_BYTES = 100L * 1024 * 1024;

    @Override
    public void applyOptions(@NonNull Context context, @NonNull GlideBuilder builder) {
        builder.setLogLevel(Log.ERROR);
        // Same directory as Glide's default, so the existing cache is kept and trimmed to the new limit.
        builder.setDiskCache(new InternalCacheDiskCacheFactory(context, IMAGE_CACHE_BYTES));
    }

    @Override
    public void registerComponents(@NonNull Context context, @NonNull Glide glide, Registry registry) {
        registry.replace(GlideUrl.class, InputStream.class, new OkHttpUrlLoader.Factory(OkHttp.client()));
    }
}