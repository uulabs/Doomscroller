package au.com.uulabs.doomscroller;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * The conventional share arrow: a curved tail from the lower left into an
 * arrowhead on the right. Drawn rather than typed, so it looks the same on
 * every device regardless of which glyphs the system font happens to carry.
 */
public class ShareIconView extends View {

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    public ShareIconView(Context context) {
        super(context);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setColor(Color.WHITE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        // Work inside a centred square so the icon never distorts.
        float size = Math.min(w, h) * 0.56f;
        float cx = w / 2f;
        float cy = h / 2f;
        float left = cx - size / 2f;
        float right = cx + size / 2f;
        float top = cy - size / 2f;
        float bottom = cy + size / 2f;

        stroke.setStrokeWidth(Math.max(2f, size * 0.21f));

        path.reset();
        // Tail sweeping up from the lower left, then across to the head.
        path.moveTo(left, bottom);
        path.cubicTo(left + size * 0.05f, cy + size * 0.10f,
                cx - size * 0.05f, top + size * 0.18f,
                right, top + size * 0.18f);
        canvas.drawPath(path, stroke);

        // Arrowhead.
        path.reset();
        float headX = right;
        float headY = top + size * 0.18f;
        path.moveTo(headX - size * 0.30f, headY - size * 0.26f);
        path.lineTo(headX, headY);
        path.lineTo(headX - size * 0.30f, headY + size * 0.26f);
        canvas.drawPath(path, stroke);
    }
}
