package com.framematch.overlay;

import android.content.SharedPreferences;
import android.graphics.Rect;

final class FrameState {
    static final int AUTO = 0;
    static final int FREE = 4;
    static final String[] RATIOS = {"Auto", "16:9", "4:3", "1:1", "Free"};
    private static final float[] LONG_TO_SHORT = {0, 16f / 9f, 4f / 3f, 1f};

    final Rect frame = new Rect();
    final Rect screen = new Rect();
    final Rect safe = new Rect();
    int ratio = AUTO;
    boolean flipped;
    float zoom;
    float panX;
    float panY;
    float opacity;
    boolean showPhoto;
    float bubbleX;
    float bubbleY;
    private int minimum;
    private String orientation;
    private final SharedPreferences settings;

    FrameState(SharedPreferences settings) {
        this.settings = settings;
        zoom = settings.getFloat("zoom", 1f);
        panX = settings.getFloat("panX", 0f);
        panY = settings.getFloat("panY", 0f);
        opacity = settings.getFloat("opacity", 0.45f);
        showPhoto = settings.getBoolean("showPhoto", true);
    }

    void setViewport(Rect display, Rect controls, int minimum) {
        screen.set(display);
        safe.set(controls);
        this.minimum = minimum;
        orientation = screen.width() > screen.height() ? "wide." : "tall.";
        ratio = settings.getInt(orientation + "framing", AUTO);
        flipped = settings.getBoolean(orientation + "flipped", false);
        bubbleX = settings.getFloat(orientation + "bubbleX", 1f);
        bubbleY = settings.getFloat(orientation + "bubbleY", 0.70f);
        if (ratio == AUTO) {
            frame.set(screen);
            return;
        }
        int centerX = Math.round(settings.getFloat(orientation + "centerX", 0.5f) * screen.width());
        int centerY = Math.round(settings.getFloat(orientation + "centerY", 0.5f) * screen.height());
        float width = screen.width() * settings.getFloat(orientation + "width", 0.95f);
        float height = screen.height() * settings.getFloat(orientation + "height", 0.6f);
        if (fixedRatio()) height = width / aspect();
        place(centerX, centerY, width, height);
    }

    boolean fixedRatio() {
        return ratio != AUTO && ratio != FREE;
    }

    void fitAuto(Rect preview, int photoWidth, int photoHeight) {
        if (ratio != AUTO) return;
        if (preview != null) {
            frame.set(preview);
            if (frame.intersect(screen)) return;
        }
        frame.set(photoWidth > 0 && photoHeight > 0
                ? fit(screen, (float) photoWidth / photoHeight) : screen);
    }

    static Rect fit(Rect area, float aspect) {
        int width = Math.max(1, Math.min(area.width(), Math.round(area.height() * aspect)));
        int height = Math.max(1, Math.min(area.height(), Math.round(area.width() / aspect)));
        int left = area.left + (area.width() - width) / 2;
        int top = area.top + (area.height() - height) / 2;
        return new Rect(left, top, left + width, top + height);
    }

    void selectRatio(int next) {
        if (next != ratio) flipped = false;
        ratio = next;
        if (fixedRatio()) maximize();
    }

    void flipRatio() {
        if (!fixedRatio()) return;
        flipped = !flipped;
        maximize();
    }

    void maximize() {
        frame.set(fixedRatio() ? fit(screen, aspect()) : screen);
    }

    void move(Rect start, int dx, int dy) {
        position(start.left + dx, start.top + dy, start.width(), start.height());
    }

    void resize(Rect start, int dx, int dy, int horizontal, int vertical) {
        int width;
        int height;
        if (fixedRatio()) {
            float aspect = aspect();
            float changeX = horizontal * dx / aspect;
            float changeY = vertical * dy;
            // A locked corner can grow along either axis even when the other finger coordinate hits an edge.
            float change = Math.max(0, Math.max(changeX, changeY))
                    + Math.min(0, Math.min(changeX, changeY));
            Rect max = fit(screen, aspect);
            int minHeight = Math.round(Math.max(minimum, minimum / aspect));
            height = clamp(Math.round(start.height() + change), minHeight, max.height());
            width = Math.min(max.width(), Math.round(height * aspect));
        } else {
            width = clamp(start.width() + horizontal * dx, minimum, screen.width());
            height = clamp(start.height() + vertical * dy, minimum, screen.height());
        }
        position(horizontal > 0 ? start.left : start.right - width,
                vertical > 0 ? start.top : start.bottom - height, width, height);
    }

    void resetPhoto() {
        zoom = 1f;
        panX = 0f;
        panY = 0f;
    }

    void save() {
        if (orientation == null) return;
        SharedPreferences.Editor editor = settings.edit()
                .putInt(orientation + "framing", ratio)
                .putFloat(orientation + "bubbleX", bubbleX)
                .putFloat(orientation + "bubbleY", bubbleY)
                .putFloat("zoom", zoom)
                .putFloat("panX", panX)
                .putFloat("panY", panY)
                .putFloat("opacity", opacity)
                .putBoolean("showPhoto", showPhoto);
        if (ratio != AUTO) {
            editor.putBoolean(orientation + "flipped", flipped)
                    .putFloat(orientation + "centerX", (float) frame.centerX() / screen.width())
                    .putFloat(orientation + "centerY", (float) frame.centerY() / screen.height())
                    .putFloat(orientation + "width", (float) frame.width() / screen.width())
                    .putFloat(orientation + "height", (float) frame.height() / screen.height());
        }
        editor.apply();
    }

    private float aspect() {
        float longToShort = LONG_TO_SHORT[ratio];
        boolean tall = screen.height() >= screen.width();
        return tall != flipped ? 1 / longToShort : longToShort;
    }

    private void place(int x, int y, float width, float height) {
        float minScale = Math.max(minimum / width, minimum / height);
        float maxScale = Math.min(screen.width() / width, screen.height() / height);
        float scale = Math.min(maxScale, Math.max(1f, minScale));
        int w = Math.max(1, Math.round(width * scale));
        int h = Math.max(1, Math.round(height * scale));
        position(x - w / 2, y - h / 2, w, h);
    }

    private void position(int x, int y, int width, int height) {
        int left = clamp(x, screen.left, screen.right - width);
        int top = clamp(y, screen.top, screen.bottom - height);
        frame.set(left, top, left + width, top + height);
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(max, Math.max(min, value));
    }
}
