package au.com.uulabs.doomscroller;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.text.InputFilter;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

final class SearchControl {

    interface Listener {
        void onSearch(String query);
        void onClear();
    }

    private final Activity activity;
    private final Listener listener;
    private final FrameLayout view;
    private final int collapsedWidth;
    private int expandedWidth;
    private ValueAnimator widthAnimator;
    private EditText input;
    private String query = "";
    private boolean editing;

    SearchControl(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        collapsedWidth = dp(46);
        expandedWidth = dp(300);
        view = new FrameLayout(activity);
        view.setSaveEnabled(false);
        view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xE617171A);
        background.setCornerRadius(dp(23));
        background.setStroke(dp(1), 0x29FFFFFF);
        view.setBackground(background);
        showCollapsed();
    }

    View view() {
        return view;
    }

    boolean isEditing() {
        return editing;
    }

    boolean contains(float x, float y) {
        if (view.getVisibility() != View.VISIBLE) {
            return false;
        }
        Rect bounds = new Rect();
        view.getHitRect(bounds);
        return bounds.contains((int) x, (int) y);
    }

    void setExpandedWidth(int width) {
        expandedWidth = Math.max(collapsedWidth, width);
        if (editing || !query.isEmpty()) {
            if (widthAnimator != null) {
                widthAnimator.cancel();
            }
            setWidth(expandedWidth);
        }
    }

    void cancelEditing() {
        if (!editing) {
            return;
        }
        hideKeyboard();
        editing = false;
        if (query.isEmpty()) {
            showCollapsed();
        } else {
            showActive();
        }
    }

    private void openEditor() {
        editing = true;
        view.setOnClickListener(null);
        view.removeAllViews();

        LinearLayout row = row();
        SearchIconView icon = new SearchIconView(activity);
        icon.setContentDescription("Submit search");
        icon.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                submit();
            }
        });
        row.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(46)));

        input = new EditText(activity);
        input.setSaveEnabled(false);
        input.setSingleLine(true);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0xFF8A8A8E);
        input.setHint("Search videos");
        input.setBackground(null);
        input.setPadding(0, 0, dp(4), 0);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});
        input.setText(query);
        input.setSelection(input.getText().length());
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                boolean enter = event != null
                        && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER
                        && event.getAction() == android.view.KeyEvent.ACTION_DOWN;
                if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                    submit();
                    return true;
                }
                return false;
            }
        });
        row.addView(input, new LinearLayout.LayoutParams(0, dp(46), 1f));
        row.addView(closeButton());
        view.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));
        animateWidth(expandedWidth);
        input.requestFocus();
        view.post(new Runnable() {
            @Override
            public void run() {
                if (!editing || input == null) {
                    return;
                }
                InputMethodManager keyboard = (InputMethodManager)
                        activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                keyboard.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    private void submit() {
        if (input == null) {
            return;
        }
        String submitted = input.getText().toString().trim().replaceAll("\\s+", " ");
        if (submitted.isEmpty()) {
            cancelEditing();
            return;
        }
        query = submitted;
        editing = false;
        hideKeyboard();
        showActive();
        listener.onSearch(query);
    }

    private void clearOrCancel() {
        hideKeyboard();
        editing = false;
        if (query.isEmpty()) {
            showCollapsed();
            return;
        }
        query = "";
        showCollapsed();
        listener.onClear();
    }

    private void showCollapsed() {
        view.removeAllViews();
        view.setContentDescription("Search feed");
        view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openEditor();
            }
        });
        SearchIconView icon = new SearchIconView(activity);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(46), dp(46));
        params.gravity = Gravity.RIGHT;
        view.addView(icon, params);
        animateWidth(collapsedWidth);
    }

    private void showActive() {
        view.setOnClickListener(null);
        view.setContentDescription("Search: " + query);
        view.removeAllViews();
        LinearLayout row = row();

        TextView words = new TextView(activity);
        words.setText(query);
        words.setTextColor(Color.WHITE);
        words.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        words.setSingleLine(true);
        words.setEllipsize(android.text.TextUtils.TruncateAt.END);
        words.setGravity(Gravity.CENTER_VERTICAL);
        words.setPadding(dp(16), 0, dp(4), 0);
        words.setContentDescription("Edit search: " + query);
        words.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openEditor();
            }
        });
        row.addView(words, new LinearLayout.LayoutParams(0, dp(46), 1f));
        row.addView(closeButton());
        view.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));
        setWidth(expandedWidth);
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private TextView closeButton() {
        TextView close = new TextView(activity);
        close.setText("\u00D7");
        close.setTextColor(Color.WHITE);
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 25f);
        close.setGravity(Gravity.CENTER);
        close.setContentDescription(query.isEmpty() ? "Cancel search" : "Clear search");
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clearOrCancel();
            }
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(44), dp(46));
        close.setLayoutParams(params);
        return close;
    }

    private void hideKeyboard() {
        if (input == null) {
            return;
        }
        InputMethodManager keyboard = (InputMethodManager)
                activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        keyboard.hideSoftInputFromWindow(input.getWindowToken(), 0);
        input.clearFocus();
    }

    private void animateWidth(int target) {
        if (widthAnimator != null) {
            widthAnimator.cancel();
        }
        if (view.getLayoutParams() == null) {
            return;
        }
        int start = view.getLayoutParams().width;
        if (start == target) {
            setWidth(target);
            return;
        }
        widthAnimator = ValueAnimator.ofInt(start, target);
        widthAnimator.setDuration(180);
        widthAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                setWidth((Integer) animation.getAnimatedValue());
            }
        });
        widthAnimator.start();
    }

    private void setWidth(int width) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params != null && params.width != width) {
            params.width = width;
            view.setLayoutParams(params);
        }
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                activity.getResources().getDisplayMetrics());
    }
}
