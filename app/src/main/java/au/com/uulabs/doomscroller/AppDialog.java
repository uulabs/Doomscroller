package au.com.uulabs.doomscroller;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A dialog drawn in the app's own style.
 *
 * The platform AlertDialog follows whatever theme the device happens to default
 * to, which is why it looks nothing like the rest of this app. This builds the
 * same dark card and accent pill the settings panel uses.
 */
final class AppDialog {

    private static final int CARD = 0xFF17171A;
    private static final int TEXT = 0xFFF2F2F2;
    private static final int MUTED = 0xFFB4B4B8;
    private static final int ACCENT = 0xFF66CCFF;
    private static final int ON_ACCENT = 0xFF04121B;

    private AppDialog() {
    }

    /**
     * @param negative label of the dismissing action, or null for none
     */
    static void show(Activity activity, String title, String message,
                     String positive, final Runnable onPositive, String negative) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable background = new GradientDrawable();
        background.setColor(CARD);
        background.setCornerRadius(dp(activity, 22));
        card.setBackground(background);
        int pad = dp(activity, 22);
        card.setPadding(pad, pad, pad, dp(activity, 16));

        TextView heading = new TextView(activity);
        heading.setText(title);
        heading.setTextColor(TEXT);
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        card.addView(heading);

        TextView body = new TextView(activity);
        body.setText(message);
        body.setTextColor(MUTED);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        body.setLineSpacing(dp(activity, 4), 1f);
        body.setPadding(0, dp(activity, 10), 0, 0);
        card.addView(body);

        TextView accept = new TextView(activity);
        accept.setText(positive);
        accept.setTextColor(ON_ACCENT);
        accept.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        accept.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        accept.setGravity(Gravity.CENTER);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(ACCENT);
        pill.setCornerRadius(dp(activity, 14));
        accept.setBackground(pill);
        accept.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                if (onPositive != null) {
                    onPositive.run();
                }
            }
        });
        LinearLayout.LayoutParams acceptParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 48));
        acceptParams.topMargin = dp(activity, 20);
        card.addView(accept, acceptParams);

        if (negative != null) {
            TextView dismiss = new TextView(activity);
            dismiss.setText(negative);
            dismiss.setTextColor(MUTED);
            dismiss.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
            dismiss.setGravity(Gravity.CENTER);
            dismiss.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                }
            });
            LinearLayout.LayoutParams dismissParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 44));
            dismissParams.topMargin = dp(activity, 6);
            card.addView(dismiss, dismissParams);
        }

        dialog.setContentView(card);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            // A dark card on a dim scrim, inset from the screen edges.
            window.setDimAmount(0.6f);
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            int margin = dp(activity, 28);
            window.setLayout(activity.getResources().getDisplayMetrics().widthPixels
                    - margin * 2, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    private static int dp(Activity activity, int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density);
    }
}
