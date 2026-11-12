package com.fpn.tunnel;

import android.content.Intent;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * Fix C — Quick Settings tile: one tap from the notification shade
 * toggles the tunnel with the persisted country, no app open needed.
 * State comes from FpnVpnService's published tunnel state — the same
 * single source of truth the main screen renders (the engine's
 * loopback status API only proves an engine exists, not a tunnel).
 * The service also pokes onStartListening via requestListeningState
 * on every transition. When the VPN permission or the sign-in is
 * missing, the tap opens the app instead of failing silently.
 */
public class FpnTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        refresh();
    }

    @Override
    public void onClick() {
        super.onClick();
        new Thread(() -> {
            // CONNECTING counts as up: a tap then means "cancel",
            // matching the main screen's button semantics.
            boolean up = FpnVpnService.uiState() != FpnVpnService.ST_OFF;
            if (up) {
                startService(new Intent(this, FpnVpnService.class).setAction("stop"));
                setTileState(false);
            } else if (!FpnSession.exists(this)) {
                openApp();
            } else if (android.net.VpnService.prepare(this) != null) {
                openApp(); // consent must be granted in the UI
            } else {
                try {
                    Intent svc = new Intent(this, FpnVpnService.class);
                    if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
                    else startService(svc);
                } catch (Exception e) { openApp(); return; }
                setTileState(true);
            }
        }).start();
    }

    private void openApp() {
        Intent i = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivityAndCollapse(i);
    }

    private void refresh() {
        setTileState(FpnVpnService.uiState() == FpnVpnService.ST_ON);
    }

    private void setTileState(boolean up) {
        Tile t = getQsTile();
        if (t == null) return;
        t.setLabel("FPN Tunnel");
        t.setState(up ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.updateTile();
    }

}
