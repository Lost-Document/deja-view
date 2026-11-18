package com.framematch.overlay;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public final class OverlayTile extends TileService {
    static ComponentName component(Context context) {
        return new ComponentName(context, OverlayTile.class);
    }

    static boolean isAdded(Context context) {
        return context.getSharedPreferences("tile", MODE_PRIVATE).getBoolean("added", false);
    }

    static void setAdded(Context context, boolean added) {
        context.getSharedPreferences("tile", MODE_PRIVATE).edit().putBoolean("added", added).apply();
    }

    @Override
    public void onTileAdded() {
        setAdded(this, true);
    }

    @Override
    public void onTileRemoved() {
        setAdded(this, false);
    }

    @Override
    public void onStartListening() {
        updateTile();
    }

    @Override
    public void onClick() {
        if (isLocked()) {
            unlockAndRun(this::toggle);
        } else {
            toggle();
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated") // Older Android releases require the Intent overload.
    @SuppressWarnings("deprecation")
    private void toggle() {
        if (OverlayService.running()) {
            stopService(new Intent(this, OverlayService.class));
            Tile tile = getQsTile();
            if (tile != null) {
                tile.setState(Tile.STATE_INACTIVE);
                tile.updateTile();
            }
            return;
        }
        Intent launch = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE));
        } else {
            startActivityAndCollapse(launch);
        }
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setLabel(getString(R.string.app_name));
        tile.setState(OverlayService.running() ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }
}
