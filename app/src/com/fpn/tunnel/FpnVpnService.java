package com.fpn.tunnel;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import hev.htproxy.TProxyService;

/**
 * Whole-phone tunnel for the FPN Tunnel client: Android's
 * VPN slot, filled by hev-socks5-tunnel forwarding everything to the local
 * FPN proxy (127.0.0.1:11080), served by the in-app FpnEngine in this same
 * process. A watchdog keeps it honest: if the engine or the tunnel
 * dies while the user wants it up, the dead half is rebuilt (bounded
 * retries) instead of the connection silently dropping.
 */
public class FpnVpnService extends VpnService {
    private static final String TAG = "fpntunnel";
    private static final int NOTE_ID = 1;
    private static final String CHANNEL = "fpn";
    // Never routed into the tunnel (they stay on the direct connection):
    // Termux (it runs the proxy engine itself), this app, the Muse app
    // (example: a chat app its operator wants reachable directly), and
    // Sina Bank (a foreign exit can trip the bank's fraud lock).
    private static final String[] SKIP = {
            "com.termux", "com.fpn.tunnel", "com.facebook.aura", "com.tosan.dara.sina" };

    private ParcelFileDescriptor tun;
    private FpnEngine engine;
    private volatile boolean watchdogOn;
    private volatile boolean rebuilding;
    private int restarts;

    /** Append a line to files/fpn_log.txt so failures are visible without logcat. */
    private void flog(String s) {
        try {
            File f = new File(getFilesDir(), "fpn_log.txt");
            try (FileWriter w = new FileWriter(f, true)) {
                w.write(new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + " " + s + "\n");
            }
        } catch (Exception ignored) {}
        Log.i(TAG, s);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        flog("onStartCommand action=" + (intent == null ? "null" : intent.getAction()));
        if (intent != null && "stop".equals(intent.getAction())) {
            tunnelOff();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTE_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        new Thread(this::tunnelOn).start();
        return START_STICKY;
    }

    private Notification notification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "FPN Tunnel", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, FpnVpnService.class).setAction("stop"),
                PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic)
                .setContentTitle("FPN Tunnel")
                .setContentText("Connected — routed via Mozilla MASQUE")
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(0, "Disconnect", stop).build())
                .build();
    }

    private void tunnelOn() {
        try {
            tunnelOff();
            if (engine == null) {
                engine = new FpnEngine(new File(getFilesDir(), "session.json"),
                        new File(getFilesDir(), "country.txt"),
                        new File(getFilesDir(), "engine_log.txt"),
                        FpnEngine.buildUpstreamFactory(this,
                                new File(getFilesDir(), "engine_log.txt")));
                engine.start(); // throws when signed out or the auth is refused
                flog("engine up");
            }
            Builder b = new Builder()
                    .setSession("FPN Tunnel")
                    .setMtu(8500)
                    .addAddress("198.18.0.1", 32)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("198.18.0.2")
                    .setConfigureIntent(PendingIntent.getActivity(this, 0,
                            new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                            PendingIntent.FLAG_IMMUTABLE));
            for (String pkg : SKIP) {
                try { b.addDisallowedApplication(pkg); } catch (PackageManager.NameNotFoundException ignored) {}
            }
            try { b.setMetered(false); } catch (Exception ignored) {}
            ParcelFileDescriptor fd = b.establish();
            if (fd == null) { flog("establish() returned null"); stopForeground(true); stopSelf(); return; }
            flog("establish OK fd=" + fd.getFd());
            File cfg = new File(getFilesDir(), "tunnel.yml");
            try (FileOutputStream o = new FileOutputStream(cfg)) {
                o.write(tunnelConfig().getBytes());
            }
            boolean started = TProxyService.TProxyStartService(cfg.getAbsolutePath(), fd.getFd());
            flog("TProxyStartService=" + started);
            if (!started) {
                fd.close();
                throw new Exception("tunnel didn't start");
            }
            Thread.sleep(300);
            if (!TProxyService.TProxyIsRunning()) {
                TProxyService.TProxyStopService();
                fd.close();
                throw new Exception("tunnel stopped at once");
            }
            tun = fd;
            flog("tunnel up, running=" + TProxyService.TProxyIsRunning());
            startWatchdog();
        } catch (Throwable e) {
            flog("tunnel FAILED: " + e);
            stopForeground(true);
            stopSelf();
        }
    }

    private synchronized void tunnelOff() {
        watchdogOn = false;
        if (engine != null) {
            try { engine.stop(); } catch (Exception ignored) {}
            engine = null;
            flog("engine stopped");
        }
        if (tun == null) return;
        ParcelFileDescriptor fd = tun;
        tun = null;
        try { TProxyService.TProxyStopService(); } catch (Exception ignored) {}
        try { fd.close(); } catch (Exception ignored) {}
    }

    @Override
    public void onRevoke() {
        tunnelOff();
        stopForeground(true);
        stopSelf();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        tunnelOff();
        super.onDestroy();
    }

    /**
     * Every 25 s: if the tunnel process or the engine died while the
     * user wants the VPN up, rebuild the dead half. Engine-only deaths
     * are swapped in place under the live TUN; a dead TUN triggers a
     * full tunnelOn. Gives up after 6 restarts so a hopeless network
     * cannot drain the battery.
     */
    private void startWatchdog() {
        if (watchdogOn) return;
        watchdogOn = true;
        new Thread(() -> {
            while (watchdogOn) {
                try { Thread.sleep(25000); } catch (InterruptedException e) { return; }
                if (!watchdogOn || tun == null) continue;
                boolean tunOk = false;
                try { tunOk = TProxyService.TProxyIsRunning(); } catch (Exception ignored) {}
                boolean engOk = engine != null && engine.isRunning();
                if (tunOk && engOk) { restarts = 0; continue; }
                if (restarts >= 6) {
                    flog("watchdog: giving up after " + restarts + " restarts");
                    watchdogOn = false;
                    stopForeground(true);
                    stopSelf();
                    return;
                }
                restarts++;
                flog("watchdog: tunOk=" + tunOk + " engOk=" + engOk + " — restart #" + restarts);
                if (tunOk && !engOk) {
                    try {
                        FpnEngine old = engine;
                        if (old != null) try { old.stop(); } catch (Exception ignored) {}
                        engine = new FpnEngine(new File(getFilesDir(), "session.json"),
                                new File(getFilesDir(), "country.txt"),
                                new File(getFilesDir(), "engine_log.txt"),
                                FpnEngine.buildUpstreamFactory(this,
                                        new File(getFilesDir(), "engine_log.txt")));
                        engine.start();
                        flog("watchdog: engine restarted");
                    } catch (Throwable e) {
                        flog("watchdog: engine restart failed: " + e);
                    }
                } else if (!rebuilding) {
                    rebuilding = true;
                    new Thread(() -> {
                        try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
                        tunnelOn();
                        rebuilding = false;
                    }).start();
                    return; // the fresh tunnelOn starts a fresh watchdog
                }
            }
        }, "fpn-watchdog").start();
    }

    private static String tunnelConfig() {
        return "tunnel:\n"
                + "  mtu: 8500\n"
                + "  ipv4: 198.18.0.1\n"
                + "socks5:\n"
                + "  address: 127.0.0.1\n"
                + "  port: 11080\n"
                + "mapdns:\n"
                + "  address: 198.18.0.2\n"
                + "  port: 53\n"
                + "  network: 100.64.0.0\n"
                + "  netmask: 255.192.0.0\n"
                + "  cache-size: 10000\n"
                + "misc:\n"
                + "  task-stack-size: 81920\n"
                + "  log-file: stderr\n"
                + "  log-level: warn\n";
    }
}
