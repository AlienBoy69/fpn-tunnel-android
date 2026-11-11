package com.fpn.tunnel;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Fix B — after a reboot, if the user left the VPN connected
 * (wasConnected) and a sign-in exists, bring the tunnel back without
 * waiting for the app to be opened. Skipped when the biometric session
 * lock is on: the session file is then sealed until the user unlocks
 * it in the app. The attempt and its outcome land in fpn_log.txt (the
 * service's own log lines follow it).
 *
 * Platform note: starting a foreground service from BOOT_COMPLETED is
 * on Android 12+'s exemption list, and VpnService consent persists
 * across reboots — but if either refuses on some build, the failure is
 * logged and the app-open auto-connect still covers the user.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        boolean want = ctx.getSharedPreferences("fpn", Context.MODE_PRIVATE)
                .getBoolean("wasConnected", false);
        boolean session = FpnSession.exists(ctx);
        boolean locked = ctx.getSharedPreferences("fpn", Context.MODE_PRIVATE)
                .getBoolean("biolock", false);
        log(ctx, "boot: wasConnected=" + want + " session=" + session + " biolock=" + locked);
        if (!want || !session) return;
        if (locked) {
            log(ctx, "boot: session is biometric-locked — waiting for in-app unlock");
            return;
        }
        try {
            Intent svc = new Intent(ctx, FpnVpnService.class);
            // startForegroundService is API 26+; older devices (the
            // API 25 spare) fall back to a plain startService.
            if (android.os.Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc);
            else ctx.startService(svc);
            log(ctx, "boot: service start requested");
        } catch (Exception e) {
            log(ctx, "boot: service start refused: " + e);
        }
    }

    private static void log(Context ctx, String s) {
        try (FileWriter w = new FileWriter(new File(ctx.getFilesDir(), "fpn_log.txt"), true)) {
            w.write(new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date())
                    + " " + s + "\n");
        } catch (Exception ignored) { }
    }
}
