package com.ayaanshaikh.offline;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

final class DownloadRing extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int color;
    private final int trackColor;
    private int progress = -1;

    DownloadRing(Context context, int color, int trackColor) {
        super(context);
        this.color = color;
        this.trackColor = trackColor;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2 * getResources().getDisplayMetrics().density);
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    void setProgress(int percent) {
        if (progress == percent) return;
        progress = percent;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float x = getWidth() / 2f;
        float y = getHeight() / 2f;
        float radius = Math.min(x, y) - paint.getStrokeWidth();
        if (radius <= 0) return;
        paint.setColor(trackColor);
        canvas.drawCircle(x, y, radius, paint);
        paint.setColor(color);
        float start = progress < 0 ? (SystemClock.uptimeMillis() % 1_000) * .36f : -90;
        float sweep = progress < 0 ? 90 : progress * 3.6f;
        canvas.drawArc(x - radius, y - radius, x + radius, y + radius, start, sweep, false, paint);
        if (progress < 0) postInvalidateOnAnimation();
    }
}
