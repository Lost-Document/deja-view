package com.framematch.overlay;

import android.annotation.SuppressLint;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;

public final class OverlayService extends Service implements FrameView.Listener {
    private static final String CHANNEL = "overlay";
    private static final String STOP = "com.framematch.overlay.STOP";
    private static final int WINDOW_TYPE = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
    private static WeakReference<OverlayService> active = new WeakReference<>(null);

    private final Handler main = new Handler(Looper.getMainLooper());
    private Context overlayContext;
    private WindowManager windows;
    private DisplayManager displays;
    private AppOpsManager appOps;
    private FrameView frameView;
    private ImageButton bubble;
    private ScrollView menu;
    private WindowManager.LayoutParams frameParams;
    private WindowManager.LayoutParams bubbleParams;
    private FrameState state;
    private FrameView.Mode mode = FrameView.Mode.LOCKED;
    private Bitmap photo;
    private Object suspension;
    private boolean stopping;
    private CameraLayout.Match cameraFit;
    private TextView framingStatus;

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) {}
        @Override public void onDisplayRemoved(int id) {}
        @Override public void onDisplayChanged(int id) {
            if (id == Display.DEFAULT_DISPLAY) refreshViewport();
        }
    };

    private final AppOpsManager.OnOpChangedListener permissionListener = (op, packageName) ->
            main.post(() -> {
                if (!Settings.canDrawOverlays(this)) stopSelf();
            });

    static OverlayService current() {
        return active.get();
    }

    static boolean running() {
        return current() != null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if ((intent != null && STOP.equals(intent.getAction())) || !Settings.canDrawOverlays(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (current() == this) return START_NOT_STICKY;
        startForeground(1, notification());
        try {
            showWindows();
            active = new WeakReference<>(this);
            PhotoStore.WORKER.execute(() -> {
                Bitmap saved = PhotoStore.load(getApplicationContext());
                main.post(() -> {
                    if (current() == this && photo == null && saved != null) {
                        photo = saved;
                        applyAutoFit();
                        CameraFramingService.requestScan();
                        if (menu != null) openMenu();
                    }
                });
            });
        } catch (SecurityException | WindowManager.BadTokenException error) {
            Toast.makeText(this, "Couldn't show the overlay.", Toast.LENGTH_LONG).show();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private Notification notification() {
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(
                CHANNEL, "Active overlay", NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, OverlayService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_frame)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("Reference overlay is on")
                .setOngoing(true)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(null, "Turn off", stop).build())
                .build();
    }

    @SuppressLint("ClickableViewAccessibility") // BubbleDrag calls performClick for a tap.
    private void showWindows() {
        displays = getSystemService(DisplayManager.class);
        Display display = displays.getDisplay(Display.DEFAULT_DISPLAY);
        overlayContext = createDisplayContext(display).createWindowContext(WINDOW_TYPE, null);
        windows = overlayContext.getSystemService(WindowManager.class);
        state = new FrameState(getSharedPreferences("overlay", MODE_PRIVATE));
        state.setViewport(displayArea(), safeArea(), dp(64));

        frameParams = params(getString(R.string.app_name) + " reference");
        frameView = new FrameView(overlayContext, state, this);
        configureFrame();
        windows.addView(frameView, frameParams);

        int size = dp(52);
        bubbleParams = params(getString(R.string.app_name) + " bubble");
        bubbleParams.width = size;
        bubbleParams.height = size;
        locateBubble();
        bubble = new ImageButton(overlayContext);
        bubble.setPadding(dp(14), dp(14), dp(14), dp(14));
        bubble.setImageResource(R.drawable.ic_frame);
        bubble.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        updateBubble();
        bubble.setOnClickListener(view -> {
            if (mode != FrameView.Mode.LOCKED) {
                lock();
            } else if (menu == null) {
                openMenu();
            } else {
                closeMenu();
            }
        });
        bubble.setOnTouchListener(new BubbleDrag());
        windows.addView(bubble, bubbleParams);

        displays.registerDisplayListener(displayListener, main);
        appOps = getSystemService(AppOpsManager.class);
        appOps.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, getPackageName(), permissionListener);
    }

    private Rect displayArea() {
        Rect bounds = windows.getCurrentWindowMetrics().getBounds();
        return new Rect(0, 0, bounds.width(), bounds.height());
    }

    private Rect safeArea() {
        var metrics = windows.getCurrentWindowMetrics();
        Rect area = new Rect(metrics.getBounds());
        var insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        return new Rect(insets.left, insets.top,
                area.width() - insets.right, area.height() - insets.bottom);
    }

    @Override
    public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        main.post(this::refreshViewport);
    }

    private void refreshViewport() {
        if (stopping || bubble == null || !bubble.isAttachedToWindow()) return;
        Rect display = displayArea();
        Rect area = safeArea();
        if (area.isEmpty() || (state.safe.equals(area) && state.screen.equals(display))) return;
        state.save();
        if (!state.screen.equals(display)) cameraFit = null;
        state.setViewport(display, area, dp(64));
        bubbleParams.width = dp(52);
        bubbleParams.height = dp(52);
        locateBubble();
        windows.updateViewLayout(bubble, bubbleParams);
        applyAutoFit();
        CameraFramingService.requestScan();
        if (menu != null) openMenu();
    }

    @SuppressLint("RtlHardcoded") // Display coordinates are measured from the physical left edge.
    private WindowManager.LayoutParams params(String title) {
        WindowManager.LayoutParams result = new WindowManager.LayoutParams(
                0, 0, WINDOW_TYPE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        result.gravity = Gravity.TOP | Gravity.LEFT;
        result.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        result.setFitInsetsTypes(0);
        result.setTitle(title);
        return result;
    }

    private void configureFrame() {
        boolean locked = mode == FrameView.Mode.LOCKED;
        int flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        frameParams.flags = locked ? (frameParams.flags | flags) : (frameParams.flags & ~flags);
        if (locked) {
            float limit = Build.VERSION.SDK_INT >= 31
                    ? getSystemService(InputManager.class).getMaximumObscuringOpacityForTouch() : 1f;
            frameParams.alpha = Math.min(state.opacity, limit);
        } else {
            frameParams.alpha = 1f;
        }
        frameParams.x = state.frame.left;
        frameParams.y = state.frame.top;
        frameParams.width = state.frame.width();
        frameParams.height = state.frame.height();
        frameView.setContent(photo, mode);
        frameView.setVisibility(suspension == null && (!locked || (photo != null && state.showPhoto))
                ? View.VISIBLE : View.GONE);
    }

    private void renderFrame() {
        configureFrame();
        windows.updateViewLayout(frameView, frameParams);
    }

    private void locateBubble() {
        bubbleParams.x = state.safe.left + dp(8) + Math.round(state.bubbleX * bubbleTravelX());
        bubbleParams.y = state.safe.top + dp(8) + Math.round(state.bubbleY * bubbleTravelY());
    }

    private int bubbleTravelX() {
        return Math.max(0, state.safe.width() - bubbleParams.width - dp(16));
    }

    private int bubbleTravelY() {
        return Math.max(0, state.safe.height() - bubbleParams.height - dp(16));
    }

    private void updateBubble() {
        boolean editing = mode != FrameView.Mode.LOCKED;
        bubble.setBackground(Ui.ripple(overlayContext, editing || menu != null ? Ui.ACCENT : Ui.PANEL, 26));
        bubble.setImageTintList(ColorStateList.valueOf(editing || menu != null ? Ui.ON_ACCENT : Ui.ACCENT));
        bubble.setImageResource(editing ? R.drawable.ic_done
                : menu == null ? R.drawable.ic_frame : R.drawable.ic_close);
        bubble.setContentDescription(editing ? "Lock overlay"
                : menu == null ? "Open overlay controls" : "Close overlay controls");
    }

    private final class BubbleDrag implements View.OnTouchListener {
        private float downX;
        private float downY;
        private int left;
        private int top;
        private boolean dragging;

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    downX = event.getRawX();
                    downY = event.getRawY();
                    left = bubbleParams.x;
                    top = bubbleParams.y;
                    dragging = false;
                }
                case MotionEvent.ACTION_MOVE -> {
                    float dx = event.getRawX() - downX;
                    float dy = event.getRawY() - downY;
                    int slop = ViewConfiguration.get(overlayContext).getScaledTouchSlop();
                    if (dragging || Math.hypot(dx, dy) > slop) {
                        if (!dragging) closeMenu();
                        dragging = true;
                        int x = left + Math.round(dx) - state.safe.left - dp(8);
                        int y = top + Math.round(dy) - state.safe.top - dp(8);
                        state.bubbleX = fraction(x, bubbleTravelX());
                        state.bubbleY = fraction(y, bubbleTravelY());
                        locateBubble();
                        windows.updateViewLayout(bubble, bubbleParams);
                    }
                }
                case MotionEvent.ACTION_UP -> {
                    if (dragging) state.save();
                    else view.performClick();
                }
                case MotionEvent.ACTION_CANCEL -> state.save();
                default -> { }
            }
            return true;
        }

        private float fraction(int position, int travel) {
            return Math.max(0f, Math.min(1f, (float) position / Math.max(1, travel)));
        }
    }

    private void openMenu() {
        closeMenu();
        if (suspension != null) return;

        Context context = overlayContext;
        LinearLayout content = Ui.column(context);
        content.setPadding(dp(14), dp(14), dp(14), dp(14));
        menu = new ScrollView(context);
        menu.setAccessibilityPaneTitle(getString(R.string.app_name));
        addFrameControls(content);
        addDivider(content);
        addPhotoControls(content);
        addDivider(content);

        LinearLayout actions = row();
        if (!OverlayTile.isAdded(this)) {
            cell(actions, quietButton("Add tile", () -> utility(UtilityActivity.ADD_TILE)));
        }
        cell(actions, quietButton("Turn off", this::stopSelf));
        add(content, actions, 2);

        menu.setFillViewport(false);
        menu.setClipToOutline(true);
        menu.setBackground(Ui.shape(context, Ui.PANEL, 24));
        menu.addView(content, new ScrollView.LayoutParams(-1, -2));
        WindowManager.LayoutParams layout = params(getString(R.string.app_name) + " controls");
        int margin = dp(8);
        int gap = dp(8);
        layout.width = Math.min(dp(344), state.safe.width() - 2 * margin);
        int above = bubbleParams.y - state.safe.top - gap - margin;
        int below = state.safe.bottom - bubbleParams.y - bubbleParams.height - gap - margin;
        int left = bubbleParams.x - state.safe.left - gap - margin;
        int right = state.safe.right - bubbleParams.x - bubbleParams.width - gap - margin;
        int desiredHeight = measureMenu(content, layout.width);
        if (Math.max(above, below) < desiredHeight && Math.max(left, right) >= dp(228)) {
            layout.width = Math.min(layout.width, Math.max(left, right));
            layout.height = Math.min(measureMenu(content, layout.width), state.safe.height() - 2 * margin);
            layout.x = left >= right ? bubbleParams.x - layout.width - gap : bubbleParams.x + bubbleParams.width + gap;
            layout.y = Math.min(state.safe.bottom - margin - layout.height, bubbleParams.y);
        } else {
            layout.height = Math.min(desiredHeight, Math.max(above, below));
            layout.x = Math.max(state.safe.left + margin,
                    Math.min(state.safe.right - margin - layout.width, bubbleParams.x));
            layout.y = above >= below ? bubbleParams.y - gap - layout.height : bubbleParams.y + bubbleParams.height + gap;
        }
        windows.addView(menu, layout);
        updateBubble();
    }

    private int measureMenu(LinearLayout content, int width) {
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        return content.getMeasuredHeight();
    }

    private void addFrameControls(LinearLayout content) {
        Context context = overlayContext;
        LinearLayout heading = row();
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView frame = sectionTitle("Frame");
        heading.addView(frame, new LinearLayout.LayoutParams(0, -2, 1f));
        framingStatus = Ui.text(context, framingLabel(), 12, Ui.MUTED, false);
        heading.addView(framingStatus);
        add(content, heading, 0);

        LinearLayout ratios = row();
        ratios.setPadding(dp(3), dp(3), dp(3), dp(3));
        ratios.setBackground(Ui.shape(context, Ui.BACKGROUND, 19));
        for (int index = 0; index < FrameState.RATIOS.length; index++) {
            int next = index;
            boolean selected = index == state.ratio;
            var button = Ui.button(context, FrameState.RATIOS[index], selected, () -> {
                state.selectRatio(next);
                state.save();
                if (next == FrameState.AUTO) {
                    applyAutoFit();
                    CameraFramingService.requestScan();
                    openMenu();
                } else {
                    edit(FrameView.Mode.FRAME);
                }
            });
            button.setSelected(selected);
            button.setBackground(Ui.ripple(context, selected ? Ui.ACCENT : Ui.BACKGROUND, 16));
            cell(ratios, button);
        }
        add(content, ratios, 8);

        LinearLayout frameActions = row();
        cell(frameActions, Ui.button(context, "Move / resize", false, () -> edit(FrameView.Mode.FRAME)));
        if (state.ratio != FrameState.AUTO) {
            if (state.fixedRatio() && state.ratio != 3) {
                cell(frameActions, Ui.button(context, "Rotate", false, () -> {
                    state.flipRatio();
                    state.save();
                    edit(FrameView.Mode.FRAME);
                }));
            }
            cell(frameActions, Ui.button(context, "Maximize", false, () -> {
                state.maximize();
                state.save();
                edit(FrameView.Mode.FRAME);
            }));
        } else if (!CameraFramingService.enabled(this)) {
            var connect = Ui.button(context, "Follow camera", false, () -> utility(UtilityActivity.CONNECT_CAMERA));
            connect.setContentDescription("Follow camera using Android Accessibility");
            cell(frameActions, connect);
        }
        add(content, frameActions, 5);
    }

    private TextView sectionTitle(String title) {
        TextView label = Ui.text(overlayContext, title, 14, Ui.INK, true);
        label.setAccessibilityHeading(true);
        return label;
    }

    private void addDivider(LinearLayout content) {
        View divider = new View(overlayContext);
        divider.setBackgroundColor(Ui.CONTROL);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, dp(1));
        layout.topMargin = dp(10);
        layout.bottomMargin = dp(8);
        content.addView(divider, layout);
    }

    private View quietButton(String label, Runnable action) {
        var button = Ui.button(overlayContext, label, false, action);
        button.setBackground(Ui.ripple(overlayContext, Ui.PANEL, 16));
        button.setTextColor(Ui.MUTED);
        return button;
    }

    private void addPhotoControls(LinearLayout content) {
        Context context = overlayContext;
        add(content, sectionTitle("Photo"), 0);
        if (photo == null) {
            add(content, Ui.button(context, "Choose photo", true, () -> utility(UtilityActivity.PICK)), 8);
            return;
        }

        LinearLayout editPhoto = row();
        cell(editPhoto, Ui.button(context, "Change photo", false, () -> utility(UtilityActivity.PICK)));
        cell(editPhoto, Ui.button(context, "Pan / zoom", false, () -> edit(FrameView.Mode.PHOTO)));
        add(content, editPhoto, 8);

        TextView opacity = Ui.text(context, opacityLabel(), 12, Ui.MUTED, true);
        LinearLayout opacityRow = row();
        opacityRow.setGravity(Gravity.CENTER_VERTICAL);
        opacityRow.addView(opacity, new LinearLayout.LayoutParams(dp(88), -2));
        SeekBar slider = new SeekBar(context);
        slider.setContentDescription("Photo opacity");
        slider.setMax(70);
        slider.setProgress(Math.round(state.opacity * 100) - 10);
        slider.setProgressTintList(ColorStateList.valueOf(Ui.ACCENT));
        slider.setThumbTintList(ColorStateList.valueOf(Ui.ACCENT));
        slider.setMinimumHeight(dp(44));
        slider.setPadding(dp(10), 0, dp(10), 0);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
            @Override public void onProgressChanged(SeekBar bar, int value, boolean user) {
                if (!user) return;
                state.opacity = (value + 10) / 100f;
                opacity.setText(opacityLabel());
                renderFrame();
                state.save();
            }
        });
        opacityRow.addView(slider, new LinearLayout.LayoutParams(0, -2, 1f));
        add(content, opacityRow, 4);

        LinearLayout photoActions = row();
        var visibility = Ui.button(context, state.showPhoto ? "Hide photo" : "Show photo", false, () -> {
            state.showPhoto = !state.showPhoto;
            state.save();
            applyAutoFit();
            CameraFramingService.requestScan();
            openMenu();
        });
        cell(photoActions, visibility);
        cell(photoActions, Ui.button(context, "Reset photo", false, () -> {
            state.resetPhoto();
            state.save();
            renderFrame();
        }));
        add(content, photoActions, 2);
    }

    private String opacityLabel() {
        return "Opacity " + Math.round(state.opacity * 100) + "%";
    }

    private LinearLayout row() {
        return new LinearLayout(overlayContext);
    }

    private void cell(LinearLayout row, View cell) {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(0, -2, 1f);
        if (row.getChildCount() > 0) layout.leftMargin = dp(5);
        row.addView(cell, layout);
    }

    private void add(LinearLayout parent, View child, int top) {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.topMargin = dp(top);
        parent.addView(child, layout);
    }

    private void closeMenu() {
        if (menu == null) return;
        windows.removeView(menu);
        menu = null;
        framingStatus = null;
        updateBubble();
    }

    private void edit(FrameView.Mode next) {
        closeMenu();
        mode = next;
        if (mode == FrameView.Mode.PHOTO) state.showPhoto = true;
        renderFrame();
        updateBubble();
    }

    private void lock() {
        mode = FrameView.Mode.LOCKED;
        state.save();
        applyAutoFit();
        CameraFramingService.requestScan();
        updateBubble();
    }

    private String framingLabel() {
        if (state.ratio != FrameState.AUTO) return "";
        if (photo == null) return CameraFramingService.connected() ? "Camera ready" : "";
        if (cameraFit != null) return cameraFit.label();
        return "Photo fit";
    }

    private void applyAutoFit() {
        state.fitAuto(cameraFit == null ? null : cameraFit.bounds(),
                photo == null ? 0 : photo.getWidth(), photo == null ? 0 : photo.getHeight());
        if (framingStatus != null) framingStatus.setText(framingLabel());
        renderFrame();
    }

    boolean wantsCameraFit() {
        return !stopping && state.ratio == FrameState.AUTO && mode == FrameView.Mode.LOCKED
                && suspension == null && photo != null && state.showPhoto;
    }

    Rect cameraDisplayBounds() {
        return state.screen;
    }

    void useCameraFit(CameraLayout.Match match) {
        if (!wantsCameraFit() || java.util.Objects.equals(cameraFit, match)) return;
        cameraFit = match;
        applyAutoFit();
    }

    void cameraConnectionChanged() {
        if (!CameraFramingService.connected()) cameraFit = null;
        if (mode == FrameView.Mode.LOCKED) applyAutoFit();
        if (menu != null) openMenu();
    }

    private void utility(String action) {
        startActivity(new Intent(this, UtilityActivity.class).setAction(action)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS));
    }

    void suspend(Object owner) {
        suspension = owner;
        closeMenu();
        bubble.setVisibility(View.GONE);
        renderFrame();
    }

    void resume(Object owner, boolean reopenMenu) {
        if (stopping || suspension != owner) return;
        suspension = null;
        bubble.setVisibility(View.VISIBLE);
        applyAutoFit();
        CameraFramingService.requestScan();
        if (reopenMenu) openMenu();
    }

    void setPhoto(Bitmap bitmap) {
        photo = bitmap;
        state.resetPhoto();
        state.showPhoto = true;
        lock();
    }

    @Override
    public void onFrameMoved() {
        if (state.ratio == FrameState.AUTO) state.selectRatio(FrameState.FREE);
        renderFrame();
    }
    @Override public void onGestureFinished() { state.save(); }

    private int dp(float size) {
        return Ui.dp(overlayContext, size);
    }

    @Override
    public void onDestroy() {
        stopping = true;
        if (current() == this) active.clear();
        if (state != null) state.save();
        if (appOps != null) appOps.stopWatchingMode(permissionListener);
        if (displays != null) displays.unregisterDisplayListener(displayListener);
        closeMenu();
        if (bubble != null && bubble.getParent() != null) windows.removeViewImmediate(bubble);
        if (frameView != null && frameView.getParent() != null) windows.removeViewImmediate(frameView);
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
