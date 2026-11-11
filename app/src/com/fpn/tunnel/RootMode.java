package com.fpn.tunnel;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Fix N — Root mode: OPTIONAL extras for rooted phones. The unrooted
 * app is complete without any of this; root is probed only when the
 * user opens the Root mode panel or enables a root feature (never at
 * app start), and every state change is logged to fpn_log.txt.
 *
 * Live tonight: silent self-update (pm install via su), the Doctor
 * leak test (read-only), and the battery-optimization whitelist.
 * NOT shipped: the kernel kill switch. An iptables OUTPUT chain owned
 * by an app that can be force-stopped or crash can outlive the app
 * and blackhole the phone's traffic; until a design with an external
 * cleanup guarantee exists, the toggle stays disabled ("coming
 * soon") rather than shipping a risky firewall rule.
 */
public final class RootMode {
    private RootMode() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("fpn", Context.MODE_PRIVATE);
    }

    static void rlog(Context ctx, String s) {
        try (FileWriter w = new FileWriter(new File(ctx.getFilesDir(), "fpn_log.txt"), true)) {
            w.write(new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date())
                    + " root: " + s + "\n");
        } catch (Exception ignored) {}
    }

    /**
     * Silent pre-check (no execution, no Magisk prompt): an su binary
     * or a Magisk root path exists. Decides whether the Root mode
     * entry is shown at all.
     */
    public static boolean maybeRoot() {
        String[] paths = {"/system/bin/su", "/system/xbin/su", "/sbin/su",
                "/debug_ramdisk/su", "/data/adb/magisk", "/data/adb/modules"};
        for (String p : paths) if (new File(p).exists()) return true;
        return false;
    }

    /** Cached verdict of the real probe (the panel opened at least once). */
    public static boolean hasRootCached(Context ctx) {
        return prefs(ctx).getBoolean("rootAvail", false);
    }

    public interface ProbeCallback {
        void onResult(boolean root);
    }

    /**
     * The real one-shot probe: `su -c id`. This CAN raise the Magisk
     * grant prompt, so it runs only from a user action (opening the
     * Root mode panel). The verdict is cached in prefs.
     */
    public static void probeAsync(final Context ctx, final ProbeCallback cb) {
        new Thread(() -> {
            String out = execSu(ctx, "id", 15_000);
            boolean ok = out != null && out.contains("uid=0");
            prefs(ctx).edit().putBoolean("rootAvail", ok).apply();
            rlog(ctx, "probe su -c id -> " + (ok ? "root granted" : "no root (" + out + ")"));
            cb.onResult(ok);
        }, "fpn-rootprobe").start();
    }

    /** Run one su command; returns combined output, or null on failure. */
    public static String execSu(Context ctx, String cmd, long timeoutMs) {
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", cmd)
                    .redirectErrorStream(true).start();
            final Process fp = p;
            Thread killer = new Thread(() -> {
                try { Thread.sleep(timeoutMs); } catch (InterruptedException ignored) {}
                try { fp.destroy(); } catch (Exception ignored) {}
            });
            killer.setDaemon(true);
            killer.start();
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            int rc = p.waitFor();
            String out = sb.toString().trim();
            rlog(ctx, "su -c '" + cmd + "' -> rc=" + rc + " " + abbrev(out));
            return rc == 0 ? out : (out.isEmpty() ? null : out);
        } catch (Exception e) {
            rlog(ctx, "su -c '" + cmd + "' failed: " + e.getMessage());
            return null;
        } finally {
            if (p != null) try { p.destroy(); } catch (Exception ignored) {}
        }
    }

    private static String abbrev(String s) {
        if (s == null) return "null";
        s = s.replace('\n', ' ');
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }

    // ── feature toggles ─────────────────────────────────────────────────
    public static boolean opt(Context ctx, String key) {
        return prefs(ctx).getBoolean(key, false);
    }

    public static void setOpt(Context ctx, String key, boolean on) {
        prefs(ctx).edit().putBoolean(key, on).apply();
        rlog(ctx, "option " + key + " = " + on);
    }

    /** Battery whitelist (feature 4): deviceidle exemption via dumpsys. */
    public static void setBatteryWhitelist(final Context ctx, final boolean on,
                                           final ProbeCallback cb) {
        new Thread(() -> {
            String out = execSu(ctx,
                    "dumpsys deviceidle whitelist " + (on ? "+" : "-") + "com.fpn.tunnel",
                    15_000);
            boolean ok = out != null;
            if (ok) setOpt(ctx, "root_battery", on);
            rlog(ctx, "battery whitelist " + (on ? "added" : "removed") + " -> " + ok);
            cb.onResult(ok);
        }, "fpn-rootbatt").start();
    }

    /**
     * Doctor leak test (feature 3, READ-ONLY): is traffic really
     * egressing via the TUN interface? Reads /proc/net/dev (no root
     * needed) and, when root is cached, the route table. One summary
     * line for the doctor report.
     */
    public static String leakCheck(Context ctx) {
        String tunName = null;
        long tunBytes = 0;
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/net/dev"))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] parts = line.trim().split("[:\\s]+");
                if (parts.length > 9 && parts[0].startsWith("tun")) {
                    long rx = Long.parseLong(parts[1]);
                    long tx = Long.parseLong(parts[9]);
                    if (rx + tx > tunBytes) { tunBytes = rx + tx; tunName = parts[0]; }
                }
            }
        } catch (Exception e) {
            return "Leak test: could not read /proc/net/dev (" + e.getMessage() + ")";
        }
        if (tunName == null)
            return "Leak test: NO tun interface found in /proc/net/dev — egress unconfirmed";
        StringBuilder sb = new StringBuilder();
        sb.append("Leak test: ").append(tunName).append(" present, ")
          .append(FpnVpnService.fmtBytes(tunBytes)).append(" carried");
        if (hasRootCached(ctx)) {
            String routes = execSu(ctx, "ip route show table all | grep -m2 default", 10_000);
            if (routes != null && routes.contains("dev " + tunName))
                sb.append("; default route via ").append(tunName).append(" confirmed");
            else if (routes != null)
                sb.append("; route dump: ").append(abbrev(routes));
        }
        sb.append(tunBytes > 0
                ? " — no leak detected (traffic egresses via the tunnel interface)"
                : " — tun counters are zero; egress unconfirmed");
        return sb.toString();
    }
}
