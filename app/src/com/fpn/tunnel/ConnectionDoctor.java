package com.fpn.tunnel;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fix G — the connection doctor. When a connect attempt fails to reach
 * "tunnel up" (or the user taps "Diagnose connection"), the doctor runs
 * ordered checks, tries repairs in order of increasing invasiveness,
 * and leaves a plain-language report with a stable reason code in
 * files/doctor_report.txt for the UI to show:
 *
 *   E1 no internet on this device
 *   E2 sign-in missing or expired
 *   E3 exit server unreachable (likely network filtering)
 *   E4 Mozilla gateway error (5xx)
 *   E5 sign-in token rejected
 *   E6 VPN permission not granted
 *   E7 unknown — send diagnostics
 *
 * Every step is also logged to fpn_log.txt with a "doctor:" prefix.
 * Repairs: refresh pass -> re-pick server -> AUTO fallback -> Smart
 * Connect retest -> restart engine -> restart the service once. The
 * first repair that yields a healthy real CONNECT dial wins.
 */
public final class ConnectionDoctor {
    public static final String REPORT_FILE = "doctor_report.txt";
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private final FpnVpnService svc;
    private final List<String> checks = new ArrayList<>();
    private final List<String> repairs = new ArrayList<>();

    private ConnectionDoctor(FpnVpnService svc) { this.svc = svc; }

    public static boolean isRunning() { return RUNNING.get(); }

    public static void runAutoAsync(FpnVpnService svc) { start(svc, false); }

    public static void runManualAsync(FpnVpnService svc) { start(svc, true); }

    private static void start(final FpnVpnService svc, final boolean manual) {
        if (!RUNNING.compareAndSet(false, true)) return;
        new Thread(() -> {
            try { new ConnectionDoctor(svc).run(manual); }
            catch (Throwable t) { svc.flog("doctor: crashed: " + t); }
            finally { RUNNING.set(false); }
        }, "fpn-doctor").start();
    }

    public static String reasonFor(String code) {
        switch (code) {
            case "E1": return "No internet connection on this device. Check Wi-Fi or mobile data, then try again.";
            case "E2": return "Your Mozilla sign-in is missing or has expired. Sign in again.";
            case "E3": return "The exit server could not be reached — your network may be filtering it. Try another country or Auto.";
            case "E4": return "Mozilla's gateway returned an error. The app tried switching servers automatically.";
            case "E5": return "Mozilla rejected the sign-in token. Sign in again.";
            case "E6": return "VPN permission was not granted. Tap Connect and allow the VPN connection.";
            default:   return "Something unexpected went wrong. Please send diagnostics so we can look into it.";
        }
    }

    /** Write a minimal report (used for failures the doctor doesn't run, e.g. E6). */
    public static void writeSimpleReport(Context ctx, String code, String reason,
                                         List<String> repairLines) {
        StringBuilder sb = new StringBuilder();
        sb.append("FPN Tunnel — connection diagnosis\n");
        sb.append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())).append("\n\n");
        sb.append("Result: could not connect\n");
        sb.append("Reason (").append(code).append("): ").append(reason).append("\n");
        if (repairLines != null && !repairLines.isEmpty()) {
            sb.append("\nRepairs tried:\n");
            for (String r : repairLines) sb.append(" • ").append(r).append("\n");
        }
        writeReportFile(ctx, sb.toString());
    }

    private static void writeReportFile(Context ctx, String text) {
        try (FileWriter w = new FileWriter(new File(ctx.getFilesDir(), REPORT_FILE))) {
            w.write(text);
        } catch (Exception ignored) { }
    }

    private void dlog(String s) { svc.flog("doctor: " + s); }

    private void run(boolean manual) {
        dlog("start (" + (manual ? "manual" : "auto") + ")");
        // Check 1 — session present
        if (!FpnSession.exists(svc)) {
            checks.add("Sign-in: MISSING");
            dlog("check: session missing");
            finish("E2", false);
            return;
        }
        checks.add("Sign-in: present");
        // Check 2 — device network up
        if (!networkUp()) {
            checks.add("Device network: DOWN");
            dlog("check: network down");
            finish("E1", false);
            return;
        }
        checks.add("Device network: up");
        // Engine: reuse the live one, or start a diagnostic instance.
        FpnEngine eng = svc.engineRef();
        boolean ownEngine = false;
        if (eng == null || !eng.isRunning()) {
            try {
                eng = svc.newEngine();
                eng.start();
                ownEngine = true;
                checks.add("Engine: started for diagnosis");
                dlog("engine started for diagnosis");
            } catch (Exception e) {
                checks.add("Engine start: FAILED — " + e.getMessage());
                dlog("check: engine start failed: " + e.getMessage());
                finish(classifyStartError(e), false);
                return;
            }
        } else {
            checks.add("Engine: running");
        }
        // Check 3 — FxA pass fetch
        boolean passOk = true;
        try {
            String st = eng.passCheck();
            checks.add("Mozilla pass: OK (" + st + ")");
        } catch (Exception e) {
            passOk = false;
            String code = eng.classifyPassError(e);
            checks.add("Mozilla pass: FAILED — " + e.getMessage());
            dlog("check: pass failed (" + code + "): " + e.getMessage());
            if ("E2".equals(code) || "E5".equals(code)) {
                finish(code, false);
                if (ownEngine) stopQuietly(eng);
                return;
            }
        }
        // Check 5 — DNS sinkhole (logged with the checks; the engine's
        // own resolver is the standing repair for it)
        String dns = eng.dnsCheck();
        checks.add("DNS: " + dns);
        dlog("check: " + dns);
        // Check 4 — real CONNECT health dial
        FpnEngine.Health h = eng.healthDial();
        if (h.ok) {
            checks.add("Exit health: OK — " + h.detail);
            dlog("check: health dial OK via " + h.host);
            if (!svc.tunnelRunning()) {
                svc.startTunnelInternal();
                if (svc.tunnelRunning()) {
                    repairs.add("Started the VPN tunnel — worked");
                    finish(null, true);
                } else if (svc.lastEstablishNull) {
                    finish("E6", false);
                } else {
                    finish("E7", false);
                }
            } else {
                finish(null, true);
            }
            return;
        }
        checks.add("Exit health: FAILED (" + h.code + ") — " + h.detail);
        dlog("check: health dial failed (" + h.code + "): " + h.detail);
        final String failCode = h.code;

        // Repairs, in order; a fresh health dial after each.
        // R1 — refresh the pass
        try {
            eng.forcePassRefresh();
            eng.passCheck();
            if (healthOk(eng)) { repairs.add("Refreshed the Mozilla pass — worked"); finish(null, true); return; }
            repairs.add("Refreshed the Mozilla pass — did not fix it");
        } catch (Exception e) {
            repairs.add("Refreshed the Mozilla pass — failed (" + e.getMessage() + ")");
            if (!passOk && ("E2".equals(eng.classifyPassError(e)) || "E5".equals(eng.classifyPassError(e)))) {
                finish(eng.classifyPassError(e), false);
                return;
            }
        }
        // R2 — re-pick the server (sick hosts excluded by Fix A)
        boolean switched = eng.repickNow();
        if (healthOk(eng)) {
            repairs.add("Switched the exit server" + (switched ? " to " + eng.currentUpstreamHost() : "") + " — worked");
            finish(null, true);
            return;
        }
        repairs.add("Re-picked the exit server — did not fix it");
        // R3 — AUTO fallback
        if (!"AUTO".equals(eng.requestedCountry())) {
            try { eng.changeCountry("AUTO"); } catch (Exception ignored) { }
            if (healthOk(eng)) {
                repairs.add("Fell back to Auto (fastest healthy country) — worked");
                finish(null, true);
                return;
            }
            repairs.add("Fell back to Auto — did not fix it");
        }
        // R3.5 — Smart Connect (Fix I): real CONNECT race across countries
        String sc = eng.smartConnect();
        if (sc != null) {
            if (healthOk(eng)) {
                repairs.add("Smart Connect found a healthy server (" + sc + ") — worked");
                finish(null, true);
                return;
            }
            repairs.add("Smart Connect found " + sc + " but the retest still failed");
        } else {
            repairs.add("Smart Connect: no candidate passed a real CONNECT");
        }
        // R4 — restart the engine
        try {
            eng = svc.restartEngineOnly();
            if (healthOk(eng)) {
                repairs.add("Restarted the connection engine — worked");
                finish(null, true);
                return;
            }
            repairs.add("Restarted the connection engine — did not fix it");
        } catch (Exception e) {
            repairs.add("Restarted the connection engine — failed (" + e.getMessage() + ")");
        }
        // R5 — restart the service once
        svc.startTunnelInternal();
        if (svc.lastEstablishNull) {
            repairs.add("Restarted the VPN service — VPN permission is missing");
            finish("E6", false);
            return;
        }
        FpnEngine eng2 = svc.engineRef();
        if (svc.tunnelRunning() && (eng2 == null || healthOk(eng2))) {
            repairs.add("Restarted the VPN service — worked");
            finish(null, true);
            return;
        }
        repairs.add("Restarted the VPN service — did not fix it");
        finish(failCode, false);
    }

    private boolean healthOk(FpnEngine eng) {
        try { return eng.healthDial().ok; } catch (Exception e) { return false; }
    }

    private void stopQuietly(FpnEngine eng) {
        try { eng.stop(); } catch (Exception ignored) { }
    }

    private boolean networkUp() {
        try {
            ConnectivityManager cm = svc.getSystemService(ConnectivityManager.class);
            if (cm == null) return true; // cannot tell — don't block
            Network net = cm.getActiveNetwork();
            if (net == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            return caps == null || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) {
            return true;
        }
    }

    private static String classifyStartError(Exception e) {
        String m = String.valueOf(e.getMessage());
        if (m.contains("no session token") || m.contains("errno 110")
                || m.contains("sign-in has ended")) return "E2";
        if (m.contains("FxA refused") || m.contains("proxy pass: HTTP 401")
                || m.contains("proxy pass: HTTP 403")) return "E5";
        if (m.contains("proxy pass: HTTP 5")) return "E4";
        return "E3";
    }

    private void finish(String code, boolean fixed) {
        StringBuilder sb = new StringBuilder();
        sb.append("FPN Tunnel — connection diagnosis\n");
        sb.append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())).append("\n\n");
        if (fixed) {
            sb.append("Result: CONNECTED");
            if (!repairs.isEmpty()) sb.append(" — fixed by a repair step");
            sb.append("\n");
        } else {
            sb.append("Result: could not connect\n");
            sb.append("Reason (").append(code).append("): ").append(reasonFor(code)).append("\n");
        }
        if (!repairs.isEmpty()) {
            sb.append("\nRepairs tried:\n");
            for (String r : repairs) sb.append(" • ").append(r).append("\n");
        }
        sb.append("\nChecks:\n");
        for (String c : checks) sb.append(" • ").append(c).append("\n");
        // Fix N: read-only leak test when Root mode's doctor extra is on.
        if (fixed && RootMode.opt(svc, "root_leak")
                && (RootMode.hasRootCached(svc) || RootMode.maybeRoot())) {
            try {
                String leak = RootMode.leakCheck(svc);
                sb.append(" • ").append(leak).append("\n");
                dlog(leak);
            } catch (Exception e) {
                sb.append(" • Leak test: failed (").append(e.getMessage()).append(")\n");
            }
        }
        if (!fixed)
            sb.append("\nIf this keeps happening, tap “Send diagnostics” and quote code ")
              .append(code).append(".\n");
        writeReportFile(svc, sb.toString());
        dlog("report written: " + (fixed ? "CONNECTED" : "code " + code));
        // Fix P: failed diagnoses are reported automatically and
        // anonymously (E-code + repairs tried; sanitized by the reporter).
        if (!fixed) {
            StringBuilder sum = new StringBuilder();
            sum.append("Reason (").append(code).append("): ").append(reasonFor(code));
            if (!repairs.isEmpty()) {
                sum.append("\nRepairs tried:");
                for (String r : repairs) sum.append("\n • ").append(r);
            }
            ErrorReporter.reportDoctor(svc, code, sum.toString());
        }
    }
}
