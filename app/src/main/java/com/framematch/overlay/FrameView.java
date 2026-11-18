package com.framematch.overlay;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

@SuppressLint("ViewConstructor") // Created by OverlayService with its frame state.
final class FrameView extends View {
    enum Mode { LOCKED, FRAME, PHOTO }

    interface Listener {
        void onFrameMoved();
        void onGestureFinished();
    }

    private final FrameState state;
    private final Listener listener;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF image = new RectF();
    private final Rect dragStart = new Rect();
    private final ScaleGestureDetector pinch;
    private final int corner;
    private Bitmap bitmap;
    private Mode mode = Mode.LOCKED;
    private float initialX;
    private float initialY;
    private float lastX;
    private float lastY;
    private int horizontalCorner;
    private int verticalCorner;
    private boolean panning;

    FrameView(Context context, FrameState state, Listener listener) {
        super(context);
        this.state = state;
        this.listener = listener;
        corner = Ui.dp(context, 44);
        pinch = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector gesture) {
                float oldZoom = state.zoom;
                state.zoom = Math.max(0.35f, Math.min(8f, oldZoom * gesture.getScaleFactor()));
                float factor = state.zoom / oldZoom;
                float focusX = gesture.getFocusX() / getWidth() - 0.5f;
                float focusY = gesture.getFocusY() / getHeight() - 0.5f;
                state.panX = focusX + (state.panX - focusX) * factor;
                state.panY = focusY + (state.panY - focusY) * factor;
                clampPan();
                invalidate();
                return true;
            }
        });
        pinch.setQuickScaleEnabled(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setContent(Bitmap bitmap, Mode mode) {
        this.bitmap = bitmap;
        this.mode = mode;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (bitmap != null && width > 0 && height > 0) clampPan();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float width = getWidth();
        float height = getHeight();
        if (bitmap != null && (state.showPhoto || mode != Mode.LOCKED)) {
            float scale = imageScale() * state.zoom;
            float halfWidth = bitmap.getWidth() * scale / 2;
            float halfHeight = bitmap.getHeight() * scale / 2;
            float centerX = width * (0.5f + state.panX);
            float centerY = height * (0.5f + state.panY);
            image.set(centerX - halfWidth, centerY - halfHeight, centerX + halfWidth, centerY + halfHeight);
            paint.setAlpha(mode == Mode.LOCKED ? 255 : Math.round(state.opacity * 255));
            canvas.drawBitmap(bitmap, null, image, paint);
        }
        if (mode == Mode.LOCKED) return;

        float stroke = Ui.dp(getContext(), 2);
        paint.setColor(Ui.ACCENT);
        paint.setStrokeWidth(stroke);
        paint.setStyle(Paint.Style.STROKE);
        canvas.drawRect(stroke / 2, stroke / 2, width - stroke / 2, height - stroke / 2, paint);
        paint.setStyle(Paint.Style.FILL);

        if (mode == Mode.FRAME) {
            drawGrip(canvas, gripCenter(true, -1), gripCenter(false, -1), 1);
            drawGrip(canvas, gripCenter(true, 1), gripCenter(false, -1), -1);
            drawGrip(canvas, gripCenter(true, -1), gripCenter(false, 1), -1);
            drawGrip(canvas, gripCenter(true, 1), gripCenter(false, 1), 1);
        }
    }

    @SuppressLint("ClickableViewAccessibility") // The frame handles drag and pinch gestures only.
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mode == Mode.FRAME) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    initialX = event.getRawX();
                    initialY = event.getRawY();
                    dragStart.set(state.frame);
                    horizontalCorner = cornerFor(event.getX(), true);
                    verticalCorner = cornerFor(event.getY(), false);
                }
                case MotionEvent.ACTION_MOVE -> {
                    int dx = Math.round(event.getRawX() - initialX);
                    int dy = Math.round(event.getRawY() - initialY);
                    if (horizontalCorner != 0 && verticalCorner != 0) {
                        state.resize(dragStart, dx, dy, horizontalCorner, verticalCorner);
                    } else {
                        state.move(dragStart, dx, dy);
                    }
                    if (state.ratio != FrameState.AUTO || !state.frame.equals(dragStart)) {
                        listener.onFrameMoved();
                    }
                }
                case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> listener.onGestureFinished();
                default -> { }
            }
            return true;
        }
        if (mode != Mode.PHOTO || bitmap == null) return false;

        pinch.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                lastX = event.getX();
                lastY = event.getY();
                panning = true;
            }
            case MotionEvent.ACTION_POINTER_DOWN -> panning = false;
            case MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerCount() == 2) {
                    int remaining = 1 - event.getActionIndex();
                    lastX = event.getX(remaining);
                    lastY = event.getY(remaining);
                    panning = true;
                }
            }
            case MotionEvent.ACTION_MOVE -> {
                if (panning && event.getPointerCount() == 1 && !pinch.isInProgress()) {
                    state.panX += (event.getX() - lastX) / getWidth();
                    state.panY += (event.getY() - lastY) / getHeight();
                    lastX = event.getX();
                    lastY = event.getY();
                    clampPan();
                    invalidate();
                }
            }
            case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                panning = false;
                listener.onGestureFinished();
            }
            default -> { }
        }
        return true;
    }

    private void drawGrip(Canvas canvas, float x, float y, int slope) {
        float radius = Ui.dp(getContext(), 12);
        float mark = radius / 3;
        paint.setColor(Ui.ACCENT);
        canvas.drawRoundRect(x - radius, y - radius, x + radius, y + radius,
                radius / 2, radius / 2, paint);
        paint.setColor(Ui.ON_ACCENT);
        canvas.drawLine(x - mark, y - slope * mark, x + mark, y + slope * mark, paint);
    }

    private float gripCenter(boolean horizontal, int side) {
        int length = horizontal ? getWidth() : getHeight();
        int origin = horizontal ? state.frame.left : state.frame.top;
        int safeStart = horizontal ? state.safe.left : state.safe.top;
        int safeEnd = horizontal ? state.safe.right : state.safe.bottom;
        int inset = Ui.dp(getContext(), 14);
        if (side < 0) return Math.min(length / 3f, Math.max(inset, safeStart - origin + inset));
        return Math.max(length * 2 / 3f, Math.min(length - inset, safeEnd - origin - inset));
    }

    private int cornerFor(float coordinate, boolean horizontal) {
        int length = horizontal ? getWidth() : getHeight();
        int side = coordinate < length / 2f ? -1 : 1;
        return Math.abs(coordinate - gripCenter(horizontal, side)) <= corner / 2f ? side : 0;
    }

    private float imageScale() {
        return Math.min((float) getWidth() / bitmap.getWidth(), (float) getHeight() / bitmap.getHeight());
    }

    private void clampPan() {
        float scale = imageScale() * state.zoom;
        float width = bitmap.getWidth() * scale / getWidth();
        float height = bitmap.getHeight() * scale / getHeight();
        float maxX = (width + 1) / 2 - Math.min(width, 1) * 0.15f;
        float maxY = (height + 1) / 2 - Math.min(height, 1) * 0.15f;
        state.panX = Math.min(maxX, Math.max(-maxX, state.panX));
        state.panY = Math.min(maxY, Math.max(-maxY, state.panY));
    }
}
