package au.com.uulabs.doomscroller;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

public class SearchIconView extends View {

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);

    public SearchIconView(Context context) {
        super(context);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setColor(Color.WHITE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        float size = Math.min(w, h) * 0.48f;
        float cx = w / 2f - size * 0.08f;
        float cy = h / 2f - size * 0.08f;
        float radius = size * 0.34f;
        stroke.setStrokeWidth(Math.max(2f, size * 0.12f));
        canvas.drawCircle(cx, cy, radius, stroke);
        float handle = radius * 0.72f;
        canvas.drawLine(cx + handle, cy + handle,
                cx + size * 0.54f, cy + size * 0.54f, stroke);
    }
}
