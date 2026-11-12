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
    /** Language-wrapped context: the report is a user surface since
     *  4.4.0 and renders in the app's chosen language. Logs (dlog)
     *  stay English. */
    private final Context ctx;
    private final List<String> checks = new ArrayList<>();
    private final List<String> repairs = new ArrayList<>();

    private String S(int res, Object... args) {
        return args.length == 0 ? ctx.getString(res) : ctx.getString(res, args);
    }
    // An engine this run created for diagnosis. It is NOT the service's
    // engine, so nothing else will ever stop it — if it is left running
    // it holds the proxy/control ports forever and every later connect
    // dies with EADDRINUSE (the 4.2.0 wedge). It must be released
    // before any service-level tunnel start and when the run ends.
    private FpnEngine diagEngine;

    private ConnectionDoctor(FpnVpnService svc) {
        this.svc = svc;
        this.ctx = Lang.wrap(svc);
    }

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

    public static String reasonFor(Context ctx, String code) {
        Context c = Lang.wrap(ctx);
        switch (code) {
            case "E1": return c.getString(R.string.reason_e1);
            case "E2": return c.getString(R.string.reason_e2);
            case "E3": return c.getString(R.string.reason_e3);
            case "E4": return c.getString(R.string.reason_e4);
            case "E5": return c.getString(R.string.reason_e5);
            case "E6": return c.getString(R.string.reason_e6);
            default:   return c.getString(R.string.reason_e7);
        }
    }

    private String reason(String code) { return reasonFor(ctx, code); }

    /** Write a minimal report (used for failures the doctor doesn't run, e.g. E6). */
    public static void writeSimpleReport(Context ctx, String code, String reason,
                                         List<String> repairLines) {
        Context c = Lang.wrap(ctx);
        StringBuilder sb = new StringBuilder();
        sb.append(c.getString(R.string.report_title)).append("\n");
        sb.append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())).append("\n\n");
        sb.append(c.getString(R.string.result_failed)).append("\n");
        sb.append(c.getString(R.string.reason_line, code, reason)).append("\n");
        if (repairLines != null && !repairLines.isEmpty()) {
            sb.append("\n").append(c.getString(R.string.repairs_tried)).append("\n");
            for (String r : repairLines) sb.append(" • ").append(r).append("\n");
        }
        writeReportFile(ctx, sb.toString());
        stampVerdict(ctx, code, false);
    }

    /** The report sheet reads its verdict from prefs (doctorFixed /
     *  doctorCode) instead of parsing report text. */
    private static void stampVerdict(Context ctx, String code, boolean fixed) {
        ctx.getSharedPreferences("fpn", Context.MODE_PRIVATE).edit()
                .putBoolean("doctorFixed", fixed)
                .putString("doctorCode", fixed || code == null ? "" : code)
                .apply();
    }

    private static void writeReportFile(Context ctx, String text) {
        try (FileWriter w = new FileWriter(new File(ctx.getFilesDir(), REPORT_FILE))) {
            w.write(text);
        } catch (Exception ignored) { }
    }

    private void dlog(String s) { svc.flog("doctor: " + s); }

    private void run(boolean manual) {
        try {
            runInternal(manual);
        } finally {
            releaseDiagEngine();
        }
    }

    /** Stop the diagnostic engine if this run created one and it was
     *  not adopted as the service's engine. Safe to call repeatedly. */
    private void releaseDiagEngine() {
        if (diagEngine != null && diagEngine != svc.engineRef()) {
            stopQuietly(diagEngine);
            dlog("diagnostic engine released");
        }
        diagEngine = null;
    }

    private void runInternal(boolean manual) {
        dlog("start (" + (manual ? "manual" : "auto") + ")");
        // Check 1 — session present
        if (!FpnSession.exists(svc)) {
            checks.add(S(R.string.chk_signin_missing));
            dlog("check: session missing");
            finish("E2", false);
            return;
        }
        checks.add(S(R.string.chk_signin_present));
        // Check 2 — device network up
        if (!networkUp()) {
            checks.add(S(R.string.chk_network_down));
            dlog("check: network down");
            finish("E1", false);
            return;
        }
        checks.add(S(R.string.chk_network_up));
        // Engine: reuse the live one, or start a diagnostic instance.
        FpnEngine eng = svc.engineRef();
        boolean ownEngine = false;
        if (eng == null || !eng.isRunning()) {
            try {
                eng = svc.newEngine();
                eng.start();
                ownEngine = true;
                diagEngine = eng;
                checks.add(S(R.string.chk_engine_started));
                dlog("engine started for diagnosis");
            } catch (Exception e) {
                checks.add(S(R.string.chk_engine_start_failed, e.getMessage()));
                dlog("check: engine start failed: " + e.getMessage());
                finish(classifyStartError(e), false);
                return;
            }
        } else {
            checks.add(S(R.string.chk_engine_running));
        }
        // Check 3 — FxA pass fetch
        boolean passOk = true;
        try {
            String st = eng.passCheck();
            checks.add(S(R.string.chk_pass_ok, st));
        } catch (Exception e) {
            passOk = false;
            String code = eng.classifyPassError(e);
            checks.add(S(R.string.chk_pass_failed, e.getMessage()));
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
        checks.add(S(R.string.chk_dns, dns));
        dlog("check: " + dns);
        // Check 4 — real CONNECT health dial
        FpnEngine.Health h = eng.healthDial();
        if (h.ok) {
            checks.add(S(R.string.chk_exit_ok, h.detail));
            dlog("check: health dial OK via " + h.host);
            if (!svc.tunnelRunning()) {
                // The service binds its own engine — release the
                // diagnostic one first or the bind collides (EADDRINUSE).
                releaseDiagEngine();
                svc.startTunnelInternal();
                if (svc.tunnelRunning()) {
                    repairs.add(S(R.string.rep_tunnel_started));
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
        checks.add(S(R.string.chk_exit_failed, h.code, h.detail));
        dlog("check: health dial failed (" + h.code + "): " + h.detail);
        final String failCode = h.code;

        // Repairs, in order; a fresh health dial after each.
        // R1 — refresh the pass
        try {
            eng.forcePassRefresh();
            eng.passCheck();
            if (healthOk(eng)) { repairs.add(S(R.string.rep_pass_ok)); finish(null, true); return; }
            repairs.add(S(R.string.rep_pass_no));
        } catch (Exception e) {
            repairs.add(S(R.string.rep_pass_fail, e.getMessage()));
            if (!passOk && ("E2".equals(eng.classifyPassError(e)) || "E5".equals(eng.classifyPassError(e)))) {
                finish(eng.classifyPassError(e), false);
                return;
            }
        }
        // R2 — re-pick the server (sick hosts excluded by Fix A)
        boolean switched = eng.repickNow();
        if (healthOk(eng)) {
            repairs.add(S(R.string.rep_switch_ok,
                    switched ? S(R.string.rep_switch_to, eng.currentUpstreamHost()) : ""));
            finish(null, true);
            return;
        }
        repairs.add(S(R.string.rep_switch_no));
        // R3 — AUTO fallback
        if (!"AUTO".equals(eng.requestedCountry())) {
            try { eng.changeCountry("AUTO"); } catch (Exception ignored) { }
            if (healthOk(eng)) {
                repairs.add(S(R.string.rep_auto_ok));
                finish(null, true);
                return;
            }
            repairs.add(S(R.string.rep_auto_no));
        }
        // R3.5 — Smart Connect (Fix I): real CONNECT race across countries
        String sc = eng.smartConnect();
        if (sc != null) {
            if (healthOk(eng)) {
                repairs.add(S(R.string.rep_smart_ok, sc));
                finish(null, true);
                return;
            }
            repairs.add(S(R.string.rep_smart_no, sc));
        } else {
            repairs.add(S(R.string.rep_smart_none));
        }
        // R4 — restart the engine. The repair ladder above ran on the
        // diagnostic engine if we created one; release it before the
        // service-level restarts (R4/R5) bind their own engine.
        releaseDiagEngine();
        try {
            eng = svc.restartEngineOnly();
            if (healthOk(eng)) {
                repairs.add(S(R.string.rep_engine_ok));
                finish(null, true);
                return;
            }
            repairs.add(S(R.string.rep_engine_no));
        } catch (Exception e) {
            repairs.add(S(R.string.rep_engine_fail, e.getMessage()));
        }
        // R5 — restart the service once
        svc.startTunnelInternal();
        if (svc.lastEstablishNull) {
            repairs.add(S(R.string.rep_service_perm));
            finish("E6", false);
            return;
        }
        FpnEngine eng2 = svc.engineRef();
        if (svc.tunnelRunning() && (eng2 == null || healthOk(eng2))) {
            repairs.add(S(R.string.rep_service_ok));
            finish(null, true);
            return;
        }
        repairs.add(S(R.string.rep_service_no));
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
        // A locally malformed stored credential (e.g. a session token
        // that fails hex parsing) is a sign-in problem, not a network
        // one — the remedy is signing in again, so say E2, not E3.
        if (e instanceof NumberFormatException || m.contains("For input string"))
            return "E2";
        if (m.contains("no session token") || m.contains("errno 110")
                || m.contains("sign-in has ended")) return "E2";
        if (m.contains("FxA refused") || m.contains("proxy pass: HTTP 401")
                || m.contains("proxy pass: HTTP 403")) return "E5";
        if (m.contains("proxy pass: HTTP 5")) return "E4";
        return "E3";
    }

    private void finish(String code, boolean fixed) {
        StringBuilder sb = new StringBuilder();
        sb.append(S(R.string.report_title)).append("\n");
        sb.append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())).append("\n\n");
        if (fixed) {
            sb.append(S(R.string.result_connected));
            if (!repairs.isEmpty()) sb.append(S(R.string.result_connected_fixed));
            sb.append("\n");
        } else {
            sb.append(S(R.string.result_failed)).append("\n");
            sb.append(S(R.string.reason_line, code, reason(code))).append("\n");
        }
        if (!repairs.isEmpty()) {
            sb.append("\n").append(S(R.string.repairs_tried)).append("\n");
            for (String r : repairs) sb.append(" • ").append(r).append("\n");
        }
        sb.append("\n").append(S(R.string.checks_label)).append("\n");
        for (String c : checks) sb.append(" • ").append(c).append("\n");
        // Fix N: read-only leak test when Root mode's doctor extra is on.
        if (fixed && RootMode.opt(svc, "root_leak")
                && (RootMode.hasRootCached(svc) || RootMode.maybeRoot())) {
            try {
                String leak = RootMode.leakCheck(svc);
                sb.append(" • ").append(leak).append("\n");
                dlog(leak);
            } catch (Exception e) {
                sb.append(" • ").append(S(R.string.leak_failed, e.getMessage())).append("\n");
            }
        }
        if (!fixed)
            sb.append("\n").append(S(R.string.report_advice, code)).append("\n");
        writeReportFile(svc, sb.toString());
        stampVerdict(svc, code, fixed);
        dlog("report written: " + (fixed ? "CONNECTED" : "code " + code));
        // Fix P: failed diagnoses are reported automatically and
        // anonymously (E-code + repairs tried; sanitized by the reporter).
        if (!fixed) {
            StringBuilder sum = new StringBuilder();
            sum.append(S(R.string.reason_line, code, reason(code)));
            if (!repairs.isEmpty()) {
                sum.append("\n").append(S(R.string.repairs_tried));
                for (String r : repairs) sum.append("\n • ").append(r);
            }
            ErrorReporter.reportDoctor(svc, code, sum.toString());
        }
    }
}
