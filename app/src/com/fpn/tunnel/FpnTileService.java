package com.fpn.tunnel;

import android.content.Intent;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Fix C — Quick Settings tile: one tap from the notification shade
 * toggles the tunnel with the persisted country, no app open needed.
 * State comes from the engine's loopback status API, the same source
 * the main screen polls. When the VPN permission or the sign-in is
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
            boolean up = fetchUp();
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
        new Thread(() -> setTileState(fetchUp())).start();
    }

    private void setTileState(boolean up) {
        Tile t = getQsTile();
        if (t == null) return;
        t.setLabel("FPN Tunnel");
        t.setState(up ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.updateTile();
    }

    private boolean fetchUp() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(
                    "http://127.0.0.1:11081/status").openConnection();
            c.setConnectTimeout(1200);
            c.setReadTimeout(1200);
            BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            return new JSONObject(sb.toString()).has("country");
        } catch (Exception e) {
            return false;
        }
    }
}
