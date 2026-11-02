package au.com.uulabs.doomscroller;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The settings panel.
 *
 * Everything is drawn here rather than themed, so the look does not depend on
 * the device's widget styling: rounded cards, a small drawn switch, letterspaced
 * section labels and a filled Done button.
 */
final class SettingsUi {

    private static final int BG = 0xFF0C0C0D;
    private static final int CARD = 0xFF17171A;
    private static final int DIVIDER = 0x1FFFFFFF;
    private static final int ACCENT = 0xFF66CCFF;
    private static final int TEXT = 0xFFF2F2F2;
    private static final int MUTED = 0xFF8A8A8E;

    private SettingsUi() {
    }

    static View build(final Activity activity, final Runnable close,
                      final Runnable checkUpdates) {
        final Context context = activity;

        final ScrollView scroll = new ScrollView(context);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        // The panel draws edge to edge, so its content stops above the
        // navigation bar rather than putting Done underneath it.
        scroll.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public android.view.WindowInsets onApplyWindowInsets(View v,
                                                                 android.view.WindowInsets insets) {
                int bottom;
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    bottom = insets.getInsets(
                            android.view.WindowInsets.Type.navigationBars()).bottom;
                } else {
                    bottom = insets.getSystemWindowInsetBottom();
                }
                v.setPadding(0, 0, 0, bottom);
                return insets;
            }
        });

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(context, 22);
        column.setPadding(pad, dp(context, 30), pad, dp(context, 34));
        scroll.addView(column);

        // ---- header
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titles = new LinearLayout(context);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(context);
        title.setText("UU Doomscroller");
        title.setTextColor(TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titles.addView(title);

        TextView sub = new TextView(context);
        sub.setText("Random video, no account, no algorithm");
        sub.setTextColor(MUTED);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        sub.setPadding(0, dp(context, 3), 0, 0);
        titles.addView(sub);

        header.addView(titles, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView closeButton = new TextView(context);
        closeButton.setText("\u2715");
        closeButton.setTextColor(MUTED);
        closeButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        closeButton.setGravity(Gravity.CENTER);
        closeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                close.run();
            }
        });
        LinearLayout.LayoutParams closeParams =
                new LinearLayout.LayoutParams(dp(context, 44), dp(context, 44));
        header.addView(closeButton, closeParams);
        column.addView(header);

        TextView about = new TextView(context);
        about.setText("A doomscrolling app that supports multiple social media channels "
                + "with no login required, no algorithm is ever built on you and the output "
                + "is completely random.");
        about.setTextColor(MUTED);
        about.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        about.setLineSpacing(dp(context, 3), 1f);
        about.setPadding(0, dp(context, 14), 0, 0);
        column.addView(about);

        // ---- sources
        column.addView(sectionLabel(context, "Sources", "Only a source with a working "
                + "feed can be switched on"));
        final Set<String> enabled = Prefs.sources(context);
        LinearLayout sourceCard = card(context);
        int j = 0;
        for (final String source : Sources.ALL) {
            boolean playable = true;   // every listed source has a playback path
            Toggle toggle = new Toggle(context);
            toggle.setEnabled(playable);
            toggle.setChecked(playable && enabled.contains(source));
            toggle.setOnChangeListener(new Toggle.OnChange() {
                @Override
                public void onChanged(boolean on) {
                    Set<String> next = new LinkedHashSet<String>(Prefs.sources(context));
                    if (on) {
                        next.add(source);
                    } else {
                        next.remove(source);
                    }
                    Prefs.setSources(context, next);
                }
            });
            sourceCard.addView(row(context, source, null, toggle,
                    j < Sources.ALL.length - 1));
            j++;
        }
        column.addView(sourceCard);

        // ---- formats
        column.addView(sectionLabel(context, "Formats", "Landscape is letterboxed, never cropped"));
        LinearLayout formatCard = card(context);
        final Toggle landscape = new Toggle(context);
        landscape.setChecked(Prefs.landscapeAllowed(context));
        landscape.setOnChangeListener(new Toggle.OnChange() {
            @Override
            public void onChanged(boolean on) {
                Prefs.setLandscapeAllowed(context, on);
            }
        });
        formatCard.addView(row(context, "Allow landscape videos",
                "Off means only portrait clips appear", landscape, false));
        column.addView(formatCard);

        // ---- languages, last and collapsed: fifteen rows is a long list
        column.addView(sectionLabel(context, "Languages", "Words the feed searches for"));
        LinearLayout languageCard = card(context);

        final LinearLayout languageRows = new LinearLayout(context);
        languageRows.setOrientation(LinearLayout.VERTICAL);
        languageRows.setVisibility(View.GONE);

        final TextView summary = new TextView(context);
        summary.setTextColor(TEXT);
        summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        summary.setPadding(dp(context, 18), dp(context, 15), dp(context, 18), dp(context, 15));

        final Runnable refreshSummary = new Runnable() {
            @Override
            public void run() {
                Set<String> chosen = Prefs.languages(context);
                StringBuilder names = new StringBuilder();
                for (Vocab.Lang l : Vocab.ALL) {
                    if (chosen.contains(l.code)) {
                        if (names.length() > 0) {
                            names.append(", ");
                        }
                        names.append(l.name);
                    }
                }
                int count = chosen.size();
                summary.setText((names.length() == 0 ? "None" : names.toString())
                        + "   ›  " + count + " selected");
            }
        };
        refreshSummary.run();

        int k = 0;
        for (final Vocab.Lang language : Vocab.ALL) {
            Toggle toggle = new Toggle(context);
            toggle.setChecked(Prefs.languages(context).contains(language.code));
            toggle.setOnChangeListener(new Toggle.OnChange() {
                @Override
                public void onChanged(boolean on) {
                    Set<String> next = new LinkedHashSet<String>(Prefs.languages(context));
                    if (on) {
                        next.add(language.code);
                    } else {
                        next.remove(language.code);
                    }
                    Prefs.setLanguages(context, next);
                    refreshSummary.run();
                }
            });
            languageRows.addView(row(context, language.name, null, toggle,
                    k < Vocab.ALL.length - 1));
            k++;
        }

        summary.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                languageRows.setVisibility(languageRows.getVisibility() == View.VISIBLE
                        ? View.GONE : View.VISIBLE);
            }
        });
        languageCard.addView(summary);
        View rule = new View(context);
        rule.setBackgroundColor(DIVIDER);
        languageCard.addView(rule, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));
        languageCard.addView(languageRows);
        column.addView(languageCard);



        // ---- about and updates
        column.addView(sectionLabel(context, "APP", null));

        LinearLayout aboutCard = card(context);
        aboutCard.setOrientation(LinearLayout.HORIZONTAL);
        aboutCard.setGravity(Gravity.CENTER_VERTICAL);
        int cardPad = dp(context, 16);
        aboutCard.setPadding(cardPad, cardPad, cardPad, cardPad);

        TextView aboutTitle = new TextView(context);
        aboutTitle.setText("Version " + BuildConfig.VERSION_NAME);
        aboutTitle.setTextColor(TEXT);
        aboutTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        aboutCard.addView(aboutTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView check = new TextView(context);
        check.setText("Check for updates");
        check.setTextColor(ACCENT);
        check.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        check.setPadding(dp(context, 10), dp(context, 6), 0, dp(context, 6));
        check.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkUpdates.run();
            }
        });
        aboutCard.addView(check);
        column.addView(aboutCard);

        // ---- about
        column.addView(sectionLabel(context, "ABOUT", null));

        LinearLayout aboutBody = card(context);
        aboutBody.setOrientation(LinearLayout.VERTICAL);
        int bodyPad = dp(context, 16);
        aboutBody.setPadding(bodyPad, bodyPad, bodyPad, bodyPad);

        TextView created = new TextView(context);
        created.setText("Created by UU Labs");
        created.setTextColor(TEXT);
        created.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        created.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        aboutBody.addView(created);

        TextView experimental = new TextView(context);
        experimental.setText("Experimental, and just for a laugh. This exists to see what "
                + "a feed with no algorithm at all would look like: no profile, no "
                + "history, no ranking. It is a toy, not a product. There is no "
                + "support, no roadmap and no promise it keeps working. Every source "
                + "it uses is an undocumented surface that the platform it belongs to "
                + "can close whenever it likes.");
        experimental.setTextColor(MUTED);
        experimental.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        experimental.setLineSpacing(dp(context, 3), 1f);
        experimental.setPadding(0, dp(context, 10), 0, 0);
        aboutBody.addView(experimental);

        TextView noResponsibility = new TextView(context);
        noResponsibility.setText("UU Labs takes no responsibility for material viewed "
                + "using this application.");
        noResponsibility.setTextColor(MUTED);
        noResponsibility.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        noResponsibility.setLineSpacing(dp(context, 3), 1f);
        noResponsibility.setPadding(0, dp(context, 10), 0, 0);
        aboutBody.addView(noResponsibility);

        column.addView(aboutBody);

        // ---- done
        TextView done = new TextView(context);
        done.setText("Done");
        done.setTextColor(0xFF04121B);
        done.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        done.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        done.setGravity(Gravity.CENTER);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(ACCENT);
        pill.setCornerRadius(dp(context, 14));
        done.setBackground(pill);
        done.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                close.run();
            }
        });
        LinearLayout.LayoutParams doneParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 52));
        doneParams.topMargin = dp(context, 26);
        column.addView(done, doneParams);

        return scroll;
    }

    // ---------------------------------------------------------------- pieces

    private static TextView sectionLabel(Context context, String text, String hint) {
        LinearLayout holder = new LinearLayout(context);
        TextView label = new TextView(context);
        label.setText(text.toUpperCase());
        label.setTextColor(ACCENT);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        label.setLetterSpacing(0.12f);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setPadding(0, dp(context, 26), 0, dp(context, 6));
        return label;
    }

    private static LinearLayout card(Context context) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable background = new GradientDrawable();
        background.setColor(CARD);
        background.setCornerRadius(dp(context, 18));
        card.setBackground(background);
        return card;
    }

    private static View row(Context context, String label, String hint, View trailing,
                            boolean divider) {
        LinearLayout wrapper = new LinearLayout(context);
        wrapper.setOrientation(LinearLayout.VERTICAL);

        LinearLayout line = new LinearLayout(context);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(dp(context, 18), dp(context, 13), dp(context, 14), dp(context, 13));

        LinearLayout text = new LinearLayout(context);
        text.setOrientation(LinearLayout.VERTICAL);

        TextView name = new TextView(context);
        name.setText(label);
        name.setTextColor(trailing.isEnabled() ? TEXT : MUTED);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        text.addView(name);

        if (hint != null) {
            TextView note = new TextView(context);
            note.setText(hint);
            note.setTextColor(MUTED);
            note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            note.setPadding(0, dp(context, 2), 0, 0);
            text.addView(note);
        }
        line.addView(text, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        line.addView(trailing);
        wrapper.addView(line);

        if (divider) {
            View rule = new View(context);
            rule.setBackgroundColor(DIVIDER);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1);
            params.leftMargin = dp(context, 18);
            params.rightMargin = dp(context, 18);
            wrapper.addView(rule, params);
        }
        return wrapper;
    }

    private static int dp(Context context, int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics());
    }

    /** A small drawn switch: rounded track, travelling knob, no theme dependency. */
    static final class Toggle extends View {

        interface OnChange {
            void onChanged(boolean on);
        }

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF track = new RectF();
        private boolean checked;
        private OnChange listener;

        Toggle(Context context) {
            super(context);
        }

        void setChecked(boolean value) {
            checked = value;
            invalidate();
        }

        void setOnChangeListener(OnChange l) {
            listener = l;
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            setMeasuredDimension(dp(getContext(), 46), dp(getContext(), 28));
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (!isEnabled()) {
                return false;
            }
            if (event.getAction() == MotionEvent.ACTION_UP) {
                checked = !checked;
                invalidate();
                if (listener != null) {
                    listener.onChanged(checked);
                }
                performClick();
            }
            return true;
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float inset = dp(getContext(), 2);
            float radius = (getHeight() - inset * 2) / 2f;
            track.set(inset, inset, getWidth() - inset, getHeight() - inset);
            paint.setColor(checked ? ACCENT : 0xFF3A3A3E);
            if (!isEnabled()) {
                paint.setAlpha(70);
            }
            canvas.drawRoundRect(track, radius, radius, paint);

            float knobRadius = radius
                    - 2.5f * getContext().getResources().getDisplayMetrics().density;
            float centreX = checked
                    ? track.right - radius : track.left + radius;
            paint.setColor(Color.WHITE);
            if (!isEnabled()) {
                paint.setAlpha(110);
            }
            canvas.drawCircle(centreX, track.centerY(), knobRadius, paint);
        }
    }
}
