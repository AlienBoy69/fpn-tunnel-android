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

    // ── UI state: the service is the single source of truth ──────────
    // The tunnel's real state lives HERE, never in the engine's
    // loopback status API: an engine can be running with no tunnel at
    // all (the ConnectionDoctor's diagnostic engine binds the same
    // ports and answers /status), and the tunnel can be up while a
    // status fetch times out. MainActivity and the QS tile run in this
    // same process and read these statics; every transition is also
    // broadcast, mirrored to prefs, and pushed to the tile.
    public static final int ST_OFF = 0;
    public static final int ST_CONNECTING = 1;
    public static final int ST_ON = 2;
    public static final String ACTION_STATE = "com.fpn.tunnel.STATE_CHANGED";
    private static volatile int uiState = ST_OFF;
    private static volatile long uiStateMs = 0;

    public static int uiState() { return uiState; }
    public static long uiStateMs() { return uiStateMs; }

    /** Record + publish a tunnel-state transition. */
    void publishState(int s) {
        uiState = s;
        uiStateMs = System.currentTimeMillis();
        try {
            getSharedPreferences("fpn", MODE_PRIVATE).edit()
                    .putInt("vpnState", s).putLong("vpnStateMs", uiStateMs).apply();
            sendBroadcast(new Intent(ACTION_STATE).setPackage(getPackageName()));
            android.service.quicksettings.TileService.requestListeningState(this,
                    new android.content.ComponentName(this, FpnTileService.class));
        } catch (Exception ignored) {}
    }

    private ParcelFileDescriptor tun;
    private FpnEngine engine;
    private volatile boolean watchdogOn;
    private volatile boolean rebuilding;
    private int restarts;
    /** Set when establish() last returned null (VPN permission missing). */
    volatile boolean lastEstablishNull;

    // Fix L: persisted usage counters. The engine counts the live
    // session; the service folds deltas into prefs (today, keyed by
    // date, + all-time) every few seconds and on shutdown, and mirrors
    // the session totals into the foreground notification. Local,
    // this-device numbers only — Mozilla exposes no usage meter for
    // FPN and the plan is Unlimited, so there is no quota to show.
    private long snapUp = -1, snapDown = -1;
    private volatile boolean noteUpdaterOn;

    private android.content.SharedPreferences usagePrefs() {
        return getSharedPreferences("fpn", MODE_PRIVATE);
    }

    static String todayKey() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    /** Fold the engine's session counters into the persisted totals. */
    synchronized void persistUsage() {
        FpnEngine e = engine;
        if (e == null) return;
        long up = e.sessionBytesUp(), down = e.sessionBytesDown();
        if (snapUp < 0) { snapUp = up; snapDown = down; return; }
        // An in-place engine restart zeroes the counters: count what
        // the fresh engine has carried as new traffic.
        long dUp = up >= snapUp ? up - snapUp : up;
        long dDown = down >= snapDown ? down - snapDown : down;
        snapUp = up; snapDown = down;
        if (dUp == 0 && dDown == 0) return;
        android.content.SharedPreferences p = usagePrefs();
        // Fix Q: counters are per-account (keyed by the active uid);
        // pre-multi-account totals were attributed to account #1 by
        // the FpnSession migration.
        String kDate = FpnSession.usageKey(this, "usageDate");
        String kTUp = FpnSession.usageKey(this, "usageTodayUp");
        String kTDown = FpnSession.usageKey(this, "usageTodayDown");
        String kTotUp = FpnSession.usageKey(this, "usageTotalUp");
        String kTotDown = FpnSession.usageKey(this, "usageTotalDown");
        String today = todayKey();
        long tUp = today.equals(p.getString(kDate, "")) ? p.getLong(kTUp, 0) : 0;
        long tDown = today.equals(p.getString(kDate, "")) ? p.getLong(kTDown, 0) : 0;
        p.edit()
                .putString(kDate, today)
                .putLong(kTUp, tUp + dUp)
                .putLong(kTDown, tDown + dDown)
                .putLong(kTotUp, p.getLong(kTotUp, 0) + dUp)
                .putLong(kTotDown, p.getLong(kTotDown, 0) + dDown)
                .apply();
    }

    static String fmtBytes(long b) {
        if (b < 1024) return b + " B";
        String[] u = {"KB", "MB", "GB", "TB"};
        double v = b;
        int i = -1;
        do { v /= 1024; i++; } while (v >= 1024 && i < u.length - 1);
        return String.format(Locale.US, v >= 100 ? "%.0f %s" : "%.1f %s", v, u[i]);
    }

    /** Refresh the foreground notification with live session totals. */
    private void startNoteUpdater() {
        if (noteUpdaterOn) return;
        noteUpdaterOn = true;
        new Thread(() -> {
            while (noteUpdaterOn && tun != null) {
                try { Thread.sleep(4000); } catch (InterruptedException e) { return; }
                if (!noteUpdaterOn || tun == null) return;
                persistUsage();
                FpnEngine e = engine;
                if (e == null) continue;
                String text = Lang.wrap(this).getString(R.string.notif_session,
                        fmtBytes(e.sessionBytesDown()), fmtBytes(e.sessionBytesUp()));
                try {
                    getSystemService(NotificationManager.class)
                            .notify(NOTE_ID, notification(text));
                } catch (Exception ignored) {}
            }
        }, "fpn-note").start();
    }

    /** Append a line to files/fpn_log.txt so failures are visible without logcat. */
    void flog(String s) {
        try {
            File f = new File(getFilesDir(), "fpn_log.txt");
            try (FileWriter w = new FileWriter(f, true)) {
                w.write(new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + " " + s + "\n");
            }
        } catch (Exception ignored) {}
        Log.i(TAG, s);
    }

    // Fix B: remember that the user wants the VPN up, so the app can
    // reconnect on open / after boot without another tap.
    void setWasConnected(boolean on) {
        getSharedPreferences("fpn", MODE_PRIVATE).edit()
                .putBoolean("wasConnected", on).apply();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        flog("onStartCommand action=" + (intent == null ? "null" : intent.getAction()));
        if (intent != null && "stop".equals(intent.getAction())) {
            setWasConnected(false);
            tunnelOff();
            publishState(ST_OFF);
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && "diagnose".equals(intent.getAction())) {
            // Fix G: manual self-diagnosis; runs alongside (or without) the tunnel.
            ConnectionDoctor.runManualAsync(this);
            return START_NOT_STICKY;
        }
        // The 3-arg startForeground (with a service type) is API 29+;
        // API 25–28 use the classic 2-arg form.
        if (android.os.Build.VERSION.SDK_INT >= 29)
            startForeground(NOTE_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else
            startForeground(NOTE_ID, notification());
        new Thread(this::tunnelOn).start();
        return START_STICKY;
    }

    private Notification notification() {
        return notification(Lang.wrap(this).getString(R.string.notif_connected));
    }

    private Notification notification(String text) {
        Notification.Builder b;
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "FPN Tunnel", NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, CHANNEL);
        } else {
            // API 25: no notification channels — pre-channel builder.
            b = new Notification.Builder(this).setPriority(Notification.PRIORITY_LOW);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, FpnVpnService.class).setAction("stop"),
                PendingIntent.FLAG_IMMUTABLE);
        return b.setSmallIcon(R.drawable.ic)
                .setContentTitle("FPN Tunnel")
                .setContentText(text)
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(0,
                        Lang.wrap(this).getString(R.string.notif_disconnect), stop).build())
                .build();
    }

    /** Build (but do not start) an engine bound to this app's files. */
    FpnEngine newEngine() {
        return new FpnEngine(FpnSession.file(this),
                new File(getFilesDir(), "country.txt"),
                new File(getFilesDir(), "engine_log.txt"),
                FpnEngine.buildUpstreamFactory(this,
                        new File(getFilesDir(), "engine_log.txt")));
    }

    FpnEngine engineRef() { return engine; }

    boolean tunnelRunning() {
        if (tun == null) return false;
        try { return TProxyService.TProxyIsRunning(); } catch (Exception e) { return false; }
    }

    /** Doctor repair step: run one full tunnelOn synchronously. */
    void startTunnelInternal() { tunnelOn(); }

    /** Doctor/watchdog repair step: swap the engine under the live TUN. */
    FpnEngine restartEngineOnly() throws Exception {
        FpnEngine old = engine;
        if (old != null) try { old.stop(); } catch (Exception ignored) {}
        engine = newEngine();
        engine.start();
        flog("engine restarted in place");
        return engine;
    }

    // Serialized: the Doctor (R5), the watchdog and onStartCommand can
    // all ask for a tunnel start; interleaved tunnelOn calls race the
    // engine bind. One at a time.
    private synchronized void tunnelOn() {
        publishState(ST_CONNECTING);
        try {
            lastEstablishNull = false;
            tunnelOff();
            if (engine == null) {
                engine = newEngine();
                try {
                    engine.start(); // throws when signed out or the auth is refused
                } catch (java.net.BindException be) {
                    // A previous engine may still be releasing the
                    // proxy ports — give it a beat and retry once
                    // instead of wedging every later connect.
                    flog("engine bind busy, retrying once: " + be.getMessage());
                    try { engine.stop(); } catch (Exception ignored) {}
                    try { Thread.sleep(700); } catch (InterruptedException ignored) {}
                    engine = newEngine();
                    engine.start();
                }
                flog("engine up");
                // Fix I: on the first connect, prove a real MASQUE
                // CONNECT works and keep the server that passes it.
                File smartMarker = new File(getFilesDir(), "smart1.done");
                if (!smartMarker.exists()) {
                    String winner = engine.smartConnect();
                    flog("smartconnect first-run: " + winner);
                    try { new FileOutputStream(smartMarker).close(); }
                    catch (Exception ignored) {}
                }
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
            if (fd == null) {
                flog("establish() returned null");
                lastEstablishNull = true;
                ConnectionDoctor.writeSimpleReport(this, "E6",
                        ConnectionDoctor.reasonFor(this, "E6"), null);
                publishState(ST_OFF);
                stopForeground(true); stopSelf(); return;
            }
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
            setWasConnected(true);
            publishState(ST_ON);
            // Fix L: a fresh tunnel is a fresh usage session.
            if (engine != null) engine.resetSessionCounters();
            snapUp = 0; snapDown = 0;
            startWatchdog();
            startNoteUpdater();
            // Fix P: queued error reports may leave now, through the tunnel.
            ErrorReporter.flushAsync(this);
        } catch (Throwable e) {
            flog("tunnel FAILED: " + e);
            ErrorReporter.reportEngineFatal(this, "tunnelOn", e);
            publishState(ST_OFF);
            stopForeground(true);
            stopSelf();
            // Fix G: a failed connect attempt gets an automatic diagnosis
            // (+ repair attempts) instead of a silent death.
            ConnectionDoctor.runAutoAsync(this);
        }
    }

    private synchronized void tunnelOff() {
        persistUsage();
        noteUpdaterOn = false;
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
        setWasConnected(false);
        tunnelOff();
        publishState(ST_OFF);
        stopForeground(true);
        stopSelf();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        tunnelOff();
        // The process (and with it the TUN) is going away; a sticky
        // restart that actually rebuilds the tunnel republishes
        // CONNECTING/ON from tunnelOn.
        publishState(ST_OFF);
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
                persistUsage();
                boolean tunOk = false;
                try { tunOk = TProxyService.TProxyIsRunning(); } catch (Exception ignored) {}
                boolean engOk = engine != null && engine.isRunning();
                if (tunOk && engOk) { restarts = 0; continue; }
                if (restarts >= 6) {
                    flog("watchdog: giving up after " + restarts + " restarts");
                    watchdogOn = false;
                    // The app has abandoned the tunnel: the persisted
                    // intent must stop claiming the user is connected.
                    setWasConnected(false);
                    publishState(ST_OFF);
                    stopForeground(true);
                    stopSelf();
                    return;
                }
                restarts++;
                flog("watchdog: tunOk=" + tunOk + " engOk=" + engOk + " — restart #" + restarts);
                publishState(ST_CONNECTING);
                if (tunOk && !engOk) {
                    try {
                        restartEngineOnly();
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
