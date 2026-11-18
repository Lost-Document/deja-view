package com.framematch.overlay;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class CameraFramingService extends AccessibilityService {
    private record Result(String packageName, boolean camera, CameraLayout.Match match) {}
    private static WeakReference<CameraFramingService> connection = new WeakReference<>(null);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> cameras = new HashSet<>();
    private final Runnable scan = this::scanCamera;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private boolean scanning;
    private int retries;
    private String lastCamera;

    static boolean connected() {
        return connection.get() != null;
    }

    static boolean enabled(Context context) {
        if (connected()) return true;
        ComponentName component = new ComponentName(context, CameraFramingService.class);
        AccessibilityManager accessibility = context.getSystemService(AccessibilityManager.class);
        for (var info : accessibility.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)) {
            var service = info.getResolveInfo().serviceInfo;
            if (component.equals(new ComponentName(service.packageName, service.name))) return true;
        }
        return false;
    }

    static void requestScan() {
        CameraFramingService service = connection.get();
        if (service != null) service.scheduleScan();
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        for (String action : new String[] {MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA,
                MediaStore.INTENT_ACTION_VIDEO_CAMERA, MediaStore.ACTION_IMAGE_CAPTURE,
                MediaStore.ACTION_VIDEO_CAPTURE}) {
            for (var match : getPackageManager().queryIntentActivities(new Intent(action), 0)) {
                cameras.add(match.activityInfo.packageName);
            }
        }
        var info = getServiceInfo();
        if (info.packageNames != null) cameras.addAll(java.util.Arrays.asList(info.packageNames));
        info.packageNames = cameras.toArray(new String[0]);
        setServiceInfo(info);

        connection = new WeakReference<>(this);
        OverlayService overlay = OverlayService.current();
        if (overlay != null) overlay.cameraConnectionChanged();
        scheduleScan();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getPackageName() != null && cameras.contains(event.getPackageName().toString())) {
            scheduleScan();
        }
    }

    private void scheduleScan() {
        OverlayService overlay = OverlayService.current();
        if (overlay == null || !overlay.wantsCameraFit()) return;
        retries = 2;
        main.removeCallbacks(scan);
        main.postDelayed(scan, 85);
    }

    private void scanCamera() {
        OverlayService overlay = OverlayService.current();
        if (scanning || overlay == null || !overlay.wantsCameraFit()) return;
        Rect screen = new Rect(overlay.cameraDisplayBounds());
        scanning = true;
        worker.execute(() -> {
            Result result = readCamera(screen);
            main.post(() -> finishScan(overlay, screen, result));
        });
    }

    private Result readCamera(Rect screen) {
        AccessibilityNodeInfo root = focusedAppRoot();
        if (root == null || root.getPackageName() == null) return null;
        String packageName = root.getPackageName().toString();
        if (!cameras.contains(packageName)) return new Result(packageName, false, null);

        Rect window = new Rect();
        root.getBoundsInScreen(window);
        if (!window.intersect(screen)) return null;
        CameraLayout layout = new CameraLayout(packageName, window);
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        Rect bounds = new Rect();
        queue.add(root);
        // Some camera UIs use large virtual trees. Inspect visible layout only.
        for (int visited = 0; !queue.isEmpty() && visited < 240; visited++) {
            AccessibilityNodeInfo node = queue.removeFirst();
            if (!node.isVisibleToUser() && node != root) continue;
            node.getBoundsInScreen(bounds);
            layout.observe(node, bounds);
            for (int child = 0; child < node.getChildCount() && queue.size() < 240; child++) {
                AccessibilityNodeInfo next = node.getChild(child);
                if (next != null) queue.addLast(next);
            }
        }
        return new Result(packageName, true, layout.match());
    }

    private AccessibilityNodeInfo focusedAppRoot() {
        // The overlay can be the active touched window while the camera still has input focus.
        for (AccessibilityWindowInfo window : getWindows()) {
            if (window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION && window.isFocused()) {
                return window.getRoot();
            }
        }
        return getRootInActiveWindow();
    }

    private void finishScan(OverlayService overlay, Rect screen, Result result) {
        scanning = false;
        if (connection.get() != this) return;
        if (overlay != OverlayService.current() || !screen.equals(overlay.cameraDisplayBounds())) {
            scheduleScan();
            return;
        }
        if (!overlay.wantsCameraFit()) return;
        if (result == null || result.packageName.equals(getPackageName())) {
            if (retries > 0 && !main.hasCallbacks(scan)) {
                retries--;
                main.postDelayed(scan, 250);
            }
            return;
        }
        if (!result.camera) {
            if (!result.packageName.equals("com.android.systemui")) {
                lastCamera = null;
                overlay.useCameraFit(null);
            }
            return;
        }
        if (result.match != null || !result.packageName.equals(lastCamera)) {
            overlay.useCameraFit(result.match);
        }
        lastCamera = result.packageName;
        // Preview surfaces can resize without emitting any accessibility event.
        if (!main.hasCallbacks(scan)) main.postDelayed(scan, 600);
    }

    @Override public void onInterrupt() {}

    @Override
    public void onDestroy() {
        main.removeCallbacks(scan);
        worker.shutdownNow();
        if (connection.get() == this) {
            connection.clear();
            OverlayService overlay = OverlayService.current();
            if (overlay != null) overlay.cameraConnectionChanged();
        }
        super.onDestroy();
    }
}
