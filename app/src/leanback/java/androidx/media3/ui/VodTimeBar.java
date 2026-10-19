package androidx.media3.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.MotionEvent;

import androidx.annotation.Nullable;

import java.util.Arrays;

/** TV VOD keeps a continuous seek track and shows chapter starts as small pointers above it. */
public class VodTimeBar extends DefaultTimeBar {

    private final Paint chapterPaint;
    private final Paint chapterOutlinePaint;
    private final Path chapterPointer;
    private final float pointerHalfWidth;
    private final float pointerHeight;
    private final float pointerGap;
    private final float minimumPointerSpacing;
    private long[] chapters = new long[0];
    private long mediaDuration;

    public VodTimeBar(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        pointerHalfWidth = 3f * density;
        pointerHeight = 5f * density;
        pointerGap = 7f * density;
        minimumPointerSpacing = 10f * density;
        chapterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        chapterPaint.setColor(0xE6F2F2F2);
        chapterOutlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        chapterOutlinePaint.setColor(0x99080808);
        chapterOutlinePaint.setStyle(Paint.Style.STROKE);
        chapterOutlinePaint.setStrokeWidth(density);
        chapterOutlinePaint.setStrokeJoin(Paint.Join.ROUND);
        chapterPointer = new Path();
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
        // Chapter labels and scrub bubbles remain owned by PlayerSeekView. Only this TV drawing
        // changes: the native bar receives no chapter gaps, so played/buffered/unplayed stay whole.
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
        if (mediaDuration > 0 && chapters.length > 0) {
            drawChapterPointers(canvas);
        }
        // Draw the native scrubber last, retaining its focus/drag geometry and visual priority.
        super.onDraw(canvas);
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
            if (chapter <= 0 || chapter >= mediaDuration) continue;
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
}
