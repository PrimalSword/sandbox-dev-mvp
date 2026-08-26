package com.dnv3d.scan3d;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

public class ScanOverlayView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final boolean[] bins = new boolean[24];
    private int ringIndex = 0;

    public ScanOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
    }

    public void update(boolean[] sourceBins, int ring) {
        System.arraycopy(sourceBins, 0, bins, 0, Math.min(sourceBins.length, bins.length));
        ringIndex = ring;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float cx = w / 2f;
        float cy = h * 0.46f;
        float rx = w * 0.36f;
        float ry = h * 0.23f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4f);
        paint.setColor(Color.argb(210, 255, 255, 255));
        canvas.drawOval(new RectF(cx - rx, cy - ry, cx + rx, cy + ry), paint);
        float radius = Math.min(w, h) * 0.36f;
        for (int i = 0; i < 24; i++) {
            double a = Math.toRadians((i * 15.0) - 90.0);
            float x = cx + (float) Math.cos(a) * radius;
            float y = cy + (float) Math.sin(a) * radius;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(bins[i] ? Color.rgb(80, 220, 120) : Color.argb(170, 255, 255, 255));
            canvas.drawCircle(x, y, bins[i] ? 8f : 5f, paint);
        }
        paint.setColor(Color.argb(220, 0, 0, 0));
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(cx - 90, cy + ry + 24, cx + 90, cy + ry + 76, 18, 18, paint);
        paint.setColor(Color.WHITE);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(30f);
        paint.setFakeBoldText(true);
        canvas.drawText(ringLabel(), cx, cy + ry + 60, paint);
        paint.setFakeBoldText(false);
    }

    private String ringLabel() {
        if (ringIndex == 0) return "FAIXA BAIXA";
        if (ringIndex == 1) return "FAIXA MÉDIA";
        if (ringIndex == 2) return "FAIXA ALTA";
        return "CONCLUÍDO";
    }
}
