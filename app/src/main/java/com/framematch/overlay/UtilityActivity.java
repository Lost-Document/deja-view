package com.framematch.overlay;

import android.app.Activity;
import android.app.StatusBarManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.widget.Toast;

import java.io.IOException;

public final class UtilityActivity extends Activity {
    static final String PICK = "com.framematch.overlay.PICK";
    static final String ADD_TILE = "com.framematch.overlay.ADD_TILE";
    static final String CONNECT_CAMERA = "com.framematch.overlay.CONNECT_CAMERA";
    private static final int PHOTO_REQUEST = 1;
    private static final int CAMERA_SETTINGS_REQUEST = 2;
    private OverlayService overlay;
    private boolean reopenMenu = true;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        overlay = OverlayService.current();
        if (overlay != null) overlay.suspend(this);
        if (state != null) return;
        if (ADD_TILE.equals(getIntent().getAction())) {
            addTile();
        } else if (CONNECT_CAMERA.equals(getIntent().getAction())) {
            try {
                startActivityForResult(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), CAMERA_SETTINGS_REQUEST);
            } catch (ActivityNotFoundException error) {
                finish();
            }
        } else {
            openPicker();
        }
    }

    private void openPicker() {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                startActivityForResult(new Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*"), PHOTO_REQUEST);
                return;
            } catch (ActivityNotFoundException ignored) {
                // Fall back to Android's document selector.
            }
        }
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), PHOTO_REQUEST);
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, "No Android image selector is available.", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void addTile() {
        if (Build.VERSION.SDK_INT < 33) {
            Toast.makeText(this, "Find " + getString(R.string.app_name) + " in Quick Settings.",
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        getSystemService(StatusBarManager.class).requestAddTileService(
                OverlayTile.component(this), getString(R.string.app_name),
                Icon.createWithResource(this, R.drawable.ic_frame), getMainExecutor(), result -> {
                    if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED
                            || result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
                        OverlayTile.setAdded(this, true);
                    }
                    if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
                        Toast.makeText(this, "Tile already added.", Toast.LENGTH_SHORT).show();
                    } else if (result != StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED
                            && result != StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED) {
                        Toast.makeText(this, "Find " + getString(R.string.app_name) + " in Quick Settings.",
                                Toast.LENGTH_LONG).show();
                    }
                    finish();
                });
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == CAMERA_SETTINGS_REQUEST) {
            finish();
            return;
        }
        if (request != PHOTO_REQUEST) return;
        if (result != RESULT_OK || data == null || data.getData() == null) {
            finish();
            return;
        }
        var uri = data.getData();
        var context = getApplicationContext();
        PhotoStore.WORKER.execute(() -> {
            Bitmap bitmap;
            try {
                bitmap = PhotoStore.importPhoto(context, uri);
            } catch (IOException | RuntimeException error) {
                runOnUiThread(() -> {
                    Toast.makeText(context, "Couldn't open that image. Try another photo.", Toast.LENGTH_LONG).show();
                    finish();
                });
                return;
            }
            runOnUiThread(() -> {
                OverlayService service = OverlayService.current();
                if (service != null) service.setPhoto(bitmap);
                reopenMenu = false;
                finish();
            });
        });
    }

    @Override
    protected void onDestroy() {
        if (overlay != null) overlay.resume(this, reopenMenu);
        super.onDestroy();
    }
}
