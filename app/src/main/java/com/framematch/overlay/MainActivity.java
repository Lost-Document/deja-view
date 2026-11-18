package com.framematch.overlay;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowInsets;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private static final String CAMERA_SETUP_COMPLETE = "cameraSetupComplete";

    @Override
    protected void onResume() {
        super.onResume();
        if (!Settings.canDrawOverlays(this)) {
            showSetup(false);
        } else if (getPreferences(MODE_PRIVATE).getBoolean(CAMERA_SETUP_COMPLETE, false)) {
            startOverlay();
        } else if (CameraFramingService.enabled(this)) {
            completeCameraSetup();
        } else {
            showSetup(true);
        }
    }

    private void completeCameraSetup() {
        getPreferences(MODE_PRIVATE).edit().putBoolean(CAMERA_SETUP_COMPLETE, true).apply();
        startOverlay();
    }

    private void startOverlay() {
        startForegroundService(new Intent(this, OverlayService.class));
        finish();
    }

    private void showSetup(boolean cameraStep) {
        ScrollView page = new ScrollView(this);
        page.setFillViewport(true);
        page.setBackgroundColor(Ui.BACKGROUND);
        page.setOnApplyWindowInsetsListener((view, insets) -> {
            var safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return WindowInsets.CONSUMED;
        });

        LinearLayout content = Ui.column(this);
        content.setGravity(Gravity.CENTER);
        content.setPadding(Ui.dp(this, 24), Ui.dp(this, 28), Ui.dp(this, 24), Ui.dp(this, 28));
        page.addView(content, new ScrollView.LayoutParams(-1, -2));

        LinearLayout card = Ui.column(this);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(Ui.dp(this, 24), Ui.dp(this, 22), Ui.dp(this, 24), Ui.dp(this, 24));
        card.setBackground(Ui.shape(this, Ui.PANEL, 28));
        int cardWidth = Math.min(Ui.dp(this, 400),
                getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 56));
        content.addView(card, new LinearLayout.LayoutParams(cardWidth, -2));

        ImageView wordmark = new ImageView(this);
        wordmark.setImageResource(R.drawable.deja_view_wordmark);
        wordmark.setContentDescription(getString(R.string.app_name));
        wordmark.setAccessibilityHeading(true);
        wordmark.setAdjustViewBounds(true);
        int wordmarkWidth = Math.min(Ui.dp(this, 232), cardWidth - Ui.dp(this, 48));
        card.addView(wordmark, new LinearLayout.LayoutParams(wordmarkWidth, -2));

        TextView title = Ui.text(this, cameraStep ? "Follow your camera" : getString(R.string.tagline),
                cameraStep ? 21 : 16, Ui.INK, true);
        title.setGravity(Gravity.CENTER);
        add(card, title, 8);

        TextView description = Ui.text(this, cameraStep
                ? "Enable Déjà View camera framing in Accessibility to fit the overlay as modes change."
                : "Allow display over other apps to see your reference in the camera.", 14, Ui.MUTED, false);
        description.setGravity(Gravity.CENTER);
        description.setLineSpacing(Ui.dp(this, 3), 1);
        add(card, description, 12);

        if (cameraStep) {
            add(card, Ui.button(this, "Open Accessibility", true, () -> openSettings(
                    new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), "Open Accessibility in Settings.")), 18);
            add(card, Ui.button(this, "Not now", false, this::completeCameraSetup), 6);
        } else {
            add(card, Ui.button(this, "Allow overlay", true, () -> openSettings(
                    new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())),
                    "Open Display over other apps in Settings.")), 18);
        }
        setContentView(page);
    }

    private void openSettings(Intent intent, String fallback) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, fallback, Toast.LENGTH_LONG).show();
        }
    }

    private void add(LinearLayout parent, android.view.View child, int marginTop) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = Ui.dp(this, marginTop);
        parent.addView(child, params);
    }
}
