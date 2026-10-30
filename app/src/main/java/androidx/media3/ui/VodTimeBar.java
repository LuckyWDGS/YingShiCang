package androidx.media3.ui;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.setting.PlayerSetting;

import java.util.Arrays;

/**
 * Keeps a continuous seek track (no native chapter gaps) and, when highlight markers are enabled,
 * marks the chapters that have not been watched yet. TV draws small pointers above the track;
 * mobile draws dots on the track so the bar stays compact.
 */
public class VodTimeBar extends DefaultTimeBar {

    private static final int STYLE_POINTER = 0;
    // Remote scrubbing lands this long after the last Left/Right is released; Media3 waits a full second
    // after every press (user 2026-10-30: a press took long to take effect).
    private static final long KEY_COMMIT_MS = 400;

    private final boolean dotStyle;
    private final Paint chapterPaint;
    private final Paint chapterOutlinePaint;
    private final Path chapterPointer;
    private final float pointerHalfWidth;
    private final float pointerHeight;
    private final float pointerGap;
    private final float minimumPointerSpacing;
    private final float dotRadius;
    private final float barHeight;
    private long[] chapters = new long[0];
    private long mediaDuration;
    private long position;
    private boolean scrubbing;
    private final Runnable keyCommit = this::commitKeyScrub;

    public VodTimeBar(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        TypedArray style = context.obtainStyledAttributes(attrs, R.styleable.VodTimeBar);
        dotStyle = style.getInt(R.styleable.VodTimeBar_chapter_marker_style, STYLE_POINTER) != STYLE_POINTER;
        style.recycle();
        TypedArray bar = context.obtainStyledAttributes(attrs, androidx.media3.ui.R.styleable.DefaultTimeBar);
        barHeight = bar.getDimension(androidx.media3.ui.R.styleable.DefaultTimeBar_bar_height, DEFAULT_BAR_HEIGHT_DP * density);
        bar.recycle();
        pointerHalfWidth = 3f * density;
        pointerHeight = 5f * density;
        pointerGap = 7f * density;
        minimumPointerSpacing = 10f * density;
        dotRadius = Math.max(barHeight * 0.75f, 2f * density);
        chapterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        chapterPaint.setColor(0xE6F2F2F2);
        chapterOutlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        chapterOutlinePaint.setColor(0x99080808);
        chapterOutlinePaint.setStyle(Paint.Style.STROKE);
        chapterOutlinePaint.setStrokeWidth(density);
        chapterOutlinePaint.setStrokeJoin(Paint.Join.ROUND);
        chapterPointer = new Path();
        addListener(new OnScrubListener() {
            @Override
            public void onScrubStart(TimeBar timeBar, long position) {
                scrubbing = true;
            }

            @Override
            public void onScrubMove(TimeBar timeBar, long position) {
            }

            @Override
            public void onScrubStop(TimeBar timeBar, long position, boolean canceled) {
                scrubbing = false;
                removeCallbacks(keyCommit);
            }
        });
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (isSeekKey(keyCode)) removeCallbacks(keyCommit);
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (isSeekKey(keyCode) && scrubbing) postDelayed(keyCommit, KEY_COMMIT_MS);
        return super.onKeyUp(keyCode, event);
    }

    private static boolean isSeekKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    /** OK while scrubbing is Media3's own way to stop and commit, the same as its timeout. */
    private void commitKeyScrub() {
        if (scrubbing) super.onKeyDown(KeyEvent.KEYCODE_DPAD_CENTER, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER));
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(keyCommit);
        super.onDetachedFromWindow();
    }

    @Override
    public void setDuration(long duration) {
        boolean changed = mediaDuration != duration;
        mediaDuration = duration;
        super.setDuration(duration);
        // Pointers sit above native seekBounds, so duration changes must redraw the whole view.
        if (changed) invalidate();
    }

    @Override
    public void setPosition(long position) {
        this.position = position;
        super.setPosition(position);
    }

    @Override
    public void setChapterTimesMs(@Nullable long[] chapterTimesMs, int chapterCount) {
        setChapterTimesMs(chapterTimesMs, null, chapterCount);
    }

    @Override
    public void setChapterTimesMs(@Nullable long[] chapterTimesMs,
                                  @Nullable String[] chapterLabels, int chapterCount) {
        if (chapterCount < 0
                || (chapterCount > 0 && (chapterTimesMs == null || chapterTimesMs.length < chapterCount))
                || (chapterLabels != null && chapterLabels.length < chapterCount)) {
            throw new IllegalArgumentException("Invalid chapter markers");
        }
        chapters = chapterCount == 0 ? new long[0] : Arrays.copyOf(chapterTimesMs, chapterCount);
        Arrays.sort(chapters);
        // Chapter labels and scrub bubbles remain owned by PlayerSeekView. The native bar receives
        // no chapter gaps, so played/buffered/unplayed stay whole.
        super.setChapterTimesMs(null, null, 0);
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean handled = super.onTouchEvent(event);
        // Keep the native seek view focused when returning from mouse/touch to a remote.
        if (handled && event.getActionMasked() == MotionEvent.ACTION_DOWN) requestFocus();
        return handled;
    }

    @Override
    public void onDraw(Canvas canvas) {
        boolean markers = mediaDuration > 0 && chapters.length > 0 && PlayerSetting.isChapterMarker();
        if (markers && !dotStyle) drawChapterPointers(canvas);
        // Draw the native bar/scrubber, retaining its focus/drag geometry and visual priority.
        super.onDraw(canvas);
        if (markers && dotStyle) drawChapterDots(canvas);
    }

    private boolean isWatched(long chapter) {
        return chapter <= position;
    }

    private void drawChapterPointers(Canvas canvas) {
        // These package helpers use the exact same bounds as native mouse/remote scrubbing.
        int barLeft = getPositionXInView(0);
        int barRight = getPositionXInView(mediaDuration);
        if (barRight <= barLeft) return;
        float tipY = getProgressBarTopInView() - pointerGap;
        float topY = tipY - pointerHeight;
        if (topY < getPaddingTop()) return;
        float previousX = Float.NEGATIVE_INFINITY;
        for (long chapter : chapters) {
            if (chapter <= 0 || chapter >= mediaDuration || isWatched(chapter)) continue;
            float x = getPositionXInView(chapter);
            // Coalesce only visually overlapping pointers; original chapter times/labels still
            // drive the existing scrub bubble, including every densely spaced chapter.
            if (x - previousX < minimumPointerSpacing) continue;
            previousX = x;
            chapterPointer.rewind();
            chapterPointer.moveTo(x - pointerHalfWidth, topY);
            chapterPointer.lineTo(x + pointerHalfWidth, topY);
            chapterPointer.lineTo(x, tipY);
            chapterPointer.close();
            canvas.drawPath(chapterPointer, chapterPaint);
            canvas.drawPath(chapterPointer, chapterOutlinePaint);
        }
    }

    private void drawChapterDots(Canvas canvas) {
        int barLeft = getPositionXInView(0);
        int barRight = getPositionXInView(mediaDuration);
        if (barRight <= barLeft) return;
        float centerY = getProgressBarTopInView() + barHeight / 2f;
        float scrubberX = getPositionXInView(position);
        float previousX = Float.NEGATIVE_INFINITY;
        for (long chapter : chapters) {
            if (chapter <= 0 || chapter >= mediaDuration || isWatched(chapter)) continue;
            float x = getPositionXInView(chapter);
            // Skip dots hidden under the scrubber thumb or overlapping the previous dot.
            if (x - previousX < dotRadius * 4 || Math.abs(x - scrubberX) < dotRadius * 3) continue;
            previousX = x;
            canvas.drawCircle(x, centerY, dotRadius, chapterPaint);
            canvas.drawCircle(x, centerY, dotRadius, chapterOutlinePaint);
        }
    }
}
