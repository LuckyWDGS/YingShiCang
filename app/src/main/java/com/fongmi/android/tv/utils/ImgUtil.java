package com.fongmi.android.tv.utils;

import static android.widget.ImageView.ScaleType.CENTER_CROP;
import static android.widget.ImageView.ScaleType.FIT_CENTER;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.Base64;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.LazyHeaders;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.impl.CustomTarget;
import com.fongmi.android.tv.server.Server;
import com.github.catvod.utils.Crypto;
import com.github.catvod.utils.Json;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.net.HttpHeaders;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;
import java.net.URI;

import jahirfiquitiva.libs.textdrawable.TextDrawable;

public class ImgUtil {

    private static final int MAX_CACHE_SIZE = 16 * 1024 * 1024;
    private static final int MAX_DATA_URI_LENGTH = 8 * 1024 * 1024;
    private static final Pattern IMAGE_MIME = Pattern.compile("image/[a-z0-9.+-]+");
    private static final Cache<String, Boolean> failed = CacheBuilder.newBuilder().maximumSize(256).expireAfterWrite(2, TimeUnit.MINUTES).build();
    private static final Cache<String, Image> CACHE = CacheBuilder.newBuilder().maximumWeight(MAX_CACHE_SIZE).weigher((String key, Image value) -> value.data().length).build();

    public static void logo(ImageView view) {
        try {
            // Clearing the view's request cancels late callbacks from the previous source.
            var requests = Glide.with(view);
            requests.clear(view);
            view.setImageDrawable(null);
            view.setVisibility(View.GONE);
            String logo = VodConfig.get().getConfig().getLogo();
            if (TextUtils.isEmpty(logo) || logo.trim().isEmpty()) return;
            // A GONE view may never be measured. Supply its fixed layout size so loading
            // can finish while hidden; Drawable loading keeps GIF animation intact.
            int width = view.getLayoutParams().width;
            int height = view.getLayoutParams().height;
            requests.load(UrlUtil.convert(logo.trim()))
                    .override(width > 0 ? width : ResUtil.dp2px(36), height > 0 ? height : ResUtil.dp2px(36))
                    .fitCenter()
                    .listener(new RequestListener<Drawable>() {
                        @Override
                        public boolean onLoadFailed(GlideException e, Object model, Target<Drawable> target, boolean isFirstResource) {
                            view.setVisibility(View.GONE);
                            return false;
                        }

                        @Override
                        public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
                            view.setVisibility(View.VISIBLE);
                            return false;
                        }
                    })
                    .into(view);
        } catch (RuntimeException e) {
            view.setImageDrawable(null);
            view.setVisibility(View.GONE);
        }
    }

    public static void load(String url, CustomTarget<Bitmap> target) {
        try {
            Glide.with(App.get()).asBitmap().load(getUrl(url)).override(ResUtil.dp2px(96), ResUtil.dp2px(96)).error(R.drawable.artwork).into(target);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public static void load(Context context, String url, CustomTarget<Drawable> target) {
        try {
            Glide.with(context).load(getUrl(url)).override(ResUtil.getScreenWidth(), ResUtil.getScreenHeight()).error(R.drawable.artwork).into(target);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public static void load(String text, String url, ImageView view) {
        load(text, url, view, true);
    }

    public static void load(String text, String url, ImageView view, boolean vod) {
        view.setScaleType(vod ? CENTER_CROP : FIT_CENTER);
        if (!vod) view.setVisibility(TextUtils.isEmpty(url) ? View.GONE : View.VISIBLE);
        if (TextUtils.isEmpty(url) || failed.getIfPresent(url) != null) {
            Glide.with(view).clear(view);
            view.setImageDrawable(getTextDrawable(text, vod));
        }
        else try {
            RequestBuilder<Drawable> builder = Glide.with(view).load(getUrl(url)).listener(getListener(text, url, view, vod));
            if (vod) builder.placeholder(R.drawable.poster_default).error(R.drawable.poster_default);
            if (vod) builder.centerCrop().into(view);
            else builder.fitCenter().into(view);
        } catch (Throwable e) {
            if (vod) {
                Glide.with(view).clear(view);
                view.setImageResource(R.drawable.poster_default);
            }
            e.printStackTrace();
        }
    }

    public static Object getUrl(String url) {
        String param = null;
        url = UrlUtil.convert(PosterUrl.normalize(url));
        if (url.startsWith("data:")) return url;
        LazyHeaders.Builder builder = new LazyHeaders.Builder();
        builder.setHeader(HttpHeaders.USER_AGENT, "Mozilla/5.0");
        // Douban's poster CDN expects a browser referer. Apply only to its own host.
        try {
            String host = URI.create(url.split("@")[0]).getHost();
            if (host != null && host.endsWith(".doubanio.com")) {
                builder.setHeader(HttpHeaders.REFERER, "https://movie.douban.com/");
                builder.setHeader(HttpHeaders.USER_AGENT, "Mozilla/5.0");
            }
            if ("img.liangzipic.com".equals(host) || "img.ffzy888.com".equals(host)) {
                builder.setHeader(HttpHeaders.REFERER, "https://" + host + "/");
            }
        } catch (IllegalArgumentException ignored) {}
        if (url.contains("@Headers=")) addHeader(builder, param = url.split("@Headers=")[1].split("@")[0]);
        if (url.contains("@Cookie=")) builder.setHeader(HttpHeaders.COOKIE, param = url.split("@Cookie=")[1].split("@")[0]);
        if (url.contains("@Referer=")) builder.setHeader(HttpHeaders.REFERER, param = url.split("@Referer=")[1].split("@")[0]);
        if (url.contains("@User-Agent=")) builder.setHeader(HttpHeaders.USER_AGENT, param = url.split("@User-Agent=")[1].split("@")[0]);
        url = param == null ? url : url.split("@")[0];
        return TextUtils.isEmpty(url) ? null : new GlideUrl(url, builder.build());
    }

    public static String cache(String url) {
        if (!isData(url)) return url;
        if (url.length() > MAX_DATA_URI_LENGTH) return "";
        String key = Crypto.md5(url);
        if (TextUtils.isEmpty(key)) return "";
        if (CACHE.asMap().computeIfAbsent(key, ignored -> decode(url)) == null) return "";
        String address = Server.get().getAddress("/image/" + key);
        failed.invalidate(address);
        return address;
    }

    private static boolean isData(String url) {
        return !TextUtils.isEmpty(url) && url.regionMatches(true, 0, "data:", 0, 5);
    }

    private static Image decode(String url) {
        try {
            int comma = url.indexOf(',');
            if (comma == -1) return null;
            String metadata = url.substring(5, comma).toLowerCase(Locale.ROOT);
            String mime = metadata.split(";", 2)[0];
            if (!IMAGE_MIME.matcher(mime).matches() || !metadata.endsWith(";base64")) return null;
            byte[] data = Base64.decode(url.substring(comma + 1), Base64.DEFAULT);
            return data.length == 0 ? null : new Image(mime, data);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static Image getImage(String key) {
        return CACHE.getIfPresent(key);
    }

    public record Image(String mime, byte[] data) {
    }

    private static void addHeader(LazyHeaders.Builder builder, String header) {
        Map<String, String> map = Json.toMap(Json.parse(header));
        for (Map.Entry<String, String> entry : map.entrySet()) builder.setHeader(UrlUtil.fixHeader(entry.getKey()), entry.getValue());
    }

    private static Drawable getTextDrawable(String text, boolean vod) {
        if (vod) return androidx.core.content.ContextCompat.getDrawable(App.get(), R.drawable.poster_default);
        TextDrawable.Builder builder = new TextDrawable.Builder();
        text = TextUtils.isEmpty(text) ? "！" : text.substring(0, 1);
        return builder.buildRoundRect(text, ColorGenerator.get400(text), ResUtil.dp2px(4));
    }

    private static RequestListener<Drawable> getListener(String text, String url, ImageView view, boolean vod) {
        return new RequestListener<>() {
            @Override
            public boolean onLoadFailed(@Nullable GlideException e, Object model, @NonNull Target<Drawable> target, boolean isFirstResource) {
                view.setImageDrawable(getTextDrawable(text, vod));
                failed.put(url, true);
                return true;
            }

            @Override
            public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
                return false;
            }
        };
    }

    /** Real poster request with a second image size as fallback, never a cached failure glyph. */
    public static void loadPoster(String url, ImageView view) {
        String highResolution = url.replace("/s_ratio_poster/", "/m_ratio_poster/");
        RequestBuilder<Drawable> retry = Glide.with(view).load(getUrl(url)).centerCrop().error(R.drawable.poster_default);
        Glide.with(view).load(getUrl(highResolution)).centerCrop().placeholder(R.drawable.poster_default).error(retry).into(view);
    }
}
