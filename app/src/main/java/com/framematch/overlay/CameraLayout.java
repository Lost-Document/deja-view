package com.framematch.overlay;

import android.graphics.Rect;
import android.os.Build;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.Locale;
import java.util.regex.Pattern;

final class CameraLayout {
    record Match(Rect bounds, String label) {}
    private record Ratio(float longToShort, String label) {}
    private static final Pattern ASPECT = Pattern.compile("(?<![0-9])([1-9][0-9]?)\\s*[:：∶]\\s*([1-9][0-9]?)(?![0-9])");
    private final Rect window;
    private final String packageName;
    private final Rect surface = new Rect();
    private final Rect aospBounds = new Rect();
    private final Rect aospOptions = new Rect();
    private int surfaceScore;
    private int ratioScore;
    private boolean conflictingRatio;
    private Ratio ratio;
    private boolean video;
    private boolean photo;
    private boolean captureControls;

    CameraLayout(String packageName, Rect window) {
        this.packageName = packageName;
        this.window = window;
    }

    @SuppressWarnings("deprecation") // Android 11–15 expose selection as a boolean.
    void observe(AccessibilityNodeInfo node, Rect bounds) {
        String id = normalized(node.getViewIdResourceName());
        id = id.substring(id.lastIndexOf('/') + 1);
        String kind = normalized(node.getClassName());
        String text = normalized(node.getText());
        String description = normalized(node.getContentDescription());
        boolean selected = node.isSelected() || (Build.VERSION.SDK_INT >= 36
                ? node.getChecked() == AccessibilityNodeInfo.CHECKED_STATE_TRUE : node.isChecked());

        boolean knownAosp = packageName.equals("com.android.camera2");
        if (knownAosp && id.equals("progress_overlay")) {
            if (!aospBounds.setIntersect(bounds, window)) aospBounds.setEmpty();
        }
        if (knownAosp && id.equals("mode_options_overlay")) {
            if (!aospOptions.setIntersect(bounds, window)) aospOptions.setEmpty();
        }
        int priority = surfacePriority(id, kind);
        if (priority > 0 && substantial(bounds) && !almostFull(bounds)
                && (priority > surfaceScore || (priority == surfaceScore && area(bounds) > area(surface)))) {
            surfaceScore = priority;
            surface.set(bounds);
        }

        boolean ratioControl = id.contains("ratio") || description.startsWith("aspect ratio")
                || description.startsWith("ratio ");
        if (ratioControl || selected || node.isClickable()) {
            Ratio candidate = readRatio(description, ratioControl || selected);
            if (candidate == null) candidate = readRatio(text, ratioControl || selected);
            if (candidate != null) {
                int score = selected ? 3 : ratioControl ? 2 : 1;
                if (score > ratioScore) {
                    ratio = candidate;
                    ratioScore = score;
                    conflictingRatio = false;
                } else if (score == ratioScore && !ratio.label.equals(candidate.label)) {
                    conflictingRatio = true;
                }
            }
        }

        if (selected || id.contains("selected_mode") || id.endsWith("current_mode")) {
            video |= isMode(text, "video") || isMode(description, "video");
            photo |= isMode(text, "photo") || isMode(description, "photo")
                    || isMode(text, "portrait") || isMode(description, "portrait");
        }
        video |= id.contains("video_shutter") || id.equals("record_button") || id.equals("video_record_button")
                || description.startsWith("record video") || description.startsWith("start recording");
        photo |= id.equals("shutter_button") || description.startsWith("take photo")
                || description.startsWith("take picture");
        captureControls |= id.contains("shutter") || id.equals("bottom_bar") || video || photo;
    }

    Match match() {
        // Camera2 transforms its full-window TextureView and places this view over the actual preview.
        if (substantial(aospBounds) && (captureControls || !almostFull(aospBounds))) {
            return new Match(new Rect(aospBounds), ratioLabel(aospBounds));
        }

        if (captureControls && substantial(aospOptions) && !almostFull(aospOptions)) {
            return new Match(new Rect(aospOptions), ratioLabel(aospOptions));
        }

        Rect measured = substantial(surface) ? surface : null;
        if (ratio != null && !conflictingRatio && (captureControls || measured != null)) {
            if (ratio.longToShort == 0) return new Match(new Rect(window), "Camera Full");
            Rect area = measured == null ? window : measured;
            float longToShort = ratio.longToShort;
            float aspect = area.width() >= area.height() ? longToShort : 1 / longToShort;
            Rect fit = FrameState.fit(area, aspect);
            String label = "Camera " + ratio.label;
            if (measured != null && similarRatio(measured, longToShort)) {
                return new Match(new Rect(measured), label);
            }
            return new Match(fit, "Estimated " + ratio.label);
        }
        if (measured != null) return new Match(new Rect(measured), ratioLabel(measured));
        if ((video || photo) && captureControls) {
            float longToShort = video ? 16f / 9 : 4f / 3;
            Rect fit = FrameState.fit(window, window.width() >= window.height() ? longToShort : 1 / longToShort);
            return new Match(fit, video ? "Estimated 16:9" : "Estimated 4:3");
        }
        return null;
    }

    private static int surfacePriority(String id, String kind) {
        if (id.contains("thumbnail") || id.contains("cover") || id.contains("transition")) return 0;
        if (kind.endsWith("surfaceview") || kind.endsWith("textureview")) return 3;
        if (id.equals("preview") || id.equals("camera_preview") || id.endsWith("preview_area") || id.endsWith("preview_surface")
                || id.endsWith("preview_layout") || id.endsWith("preview_container")
                || id.endsWith("preview_frame") || id.endsWith("preview_view")) return 2;
        return 0;
    }

    private boolean substantial(Rect bounds) {
        return bounds.width() >= window.width() * 0.32f && bounds.height() >= window.height() * 0.32f
                && window.contains(bounds);
    }

    private boolean almostFull(Rect bounds) {
        return area(bounds) >= 0.97 * area(window);
    }

    private static long area(Rect bounds) {
        return (long) bounds.width() * bounds.height();
    }

    private static Ratio readRatio(String label, boolean allowFull) {
        if (allowFull && (label.equals("full") || label.equals("full screen") || label.equals("aspect ratio full"))) {
            return new Ratio(0, "Full");
        }
        var match = ASPECT.matcher(label);
        if (!match.find()) return null;
        int x = Integer.parseInt(match.group(1));
        int y = Integer.parseInt(match.group(2));
        float value = (float) Math.max(x, y) / Math.min(x, y);
        if (Math.abs(value - 1) < 0.02f) return new Ratio(1, "1:1");
        if (Math.abs(value - 4f / 3) < 0.02f) return new Ratio(4f / 3, "4:3");
        if (Math.abs(value - 16f / 9) < 0.02f) return new Ratio(16f / 9, "16:9");
        return null;
    }

    private static String ratioLabel(Rect bounds) {
        if (similarRatio(bounds, 1)) return "Camera 1:1";
        if (similarRatio(bounds, 4f / 3)) return "Camera 4:3";
        if (similarRatio(bounds, 16f / 9)) return "Camera 16:9";
        return "Camera Preview";
    }

    private static boolean similarRatio(Rect bounds, float ratio) {
        return Math.abs((float) Math.max(bounds.width(), bounds.height())
                / Math.min(bounds.width(), bounds.height()) - ratio) < 0.045f;
    }

    private static boolean isMode(String label, String mode) {
        return label.equals(mode) || label.equals(mode + " mode") || label.equals(mode + ", selected");
    }

    private static String normalized(CharSequence value) {
        return value == null ? "" : value.toString().trim().toLowerCase(Locale.ROOT);
    }
}
