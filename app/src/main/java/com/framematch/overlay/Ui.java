package com.framematch.overlay;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    static final int BACKGROUND = 0xff181625;
    static final int PANEL = 0xff252236;
    static final int CONTROL = 0xff373149;
    static final int INK = 0xfff8f5ff;
    static final int MUTED = 0xffaca6ba;
    static final int ACCENT = 0xffc9b6ff;
    static final int ON_ACCENT = 0xff23183d;

    private Ui() {}

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable shape(Context context, int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(context, radius));
        return shape;
    }

    static RippleDrawable ripple(Context context, int color, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x30ffffff),
                shape(context, color, radius), shape(context, Color.WHITE, radius));
    }

    static TextView text(Context context, CharSequence value, int sp, int color, boolean medium) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        view.setIncludeFontPadding(false);
        return view;
    }

    static Button button(Context context, String label, boolean highlighted, Runnable action) {
        Button button = new Button(context);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(14);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(highlighted ? ON_ACCENT : INK);
        button.setBackground(ripple(context, highlighted ? ACCENT : CONTROL, 16));
        button.setStateListAnimator(null);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setMinimumHeight(dp(context, 48));
        button.setMinimumWidth(0);
        button.setPadding(dp(context, 6), dp(context, 6), dp(context, 6), dp(context, 6));
        button.setGravity(Gravity.CENTER);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    static LinearLayout column(Context context) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }
}
