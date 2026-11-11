package com.fpn.tunnel;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * Fix P — automatic, ANONYMOUS error reporting. Crashes, Doctor
 * failures (E-code + repairs) and fatal engine errors are composed
 * into small reports, queued on disk, and sent ONLY while the VPN
 * tunnel is up — the POST rides the app's own local proxy, so the
 * user's real IP never reaches the relay directly.
 *
 * Payload discipline (strict): app version, Android version, device
 * model, error code/class, a sanitized message and the last ~30 lines
 * of fpn_log.txt run through the Diagnostics sanitizer PLUS an extra
 * pass that strips IP addresses. No emails, no tokens, no IPs, no
 * account data, and NO persistent device or install ID — each report
 * carries only a random per-report nonce. The same error signature is
 * sent at most once per day.
 *
 * Transport: HTTPS POST to a Telegram Bot API report inbox. The bot
 * token + chat id come from app/reporting.properties, a GITIGNORED
 * file baked into assets at build time (template:
 * reporting.properties.example). When the config is absent — as in
 * every public build — reporting is DISABLED and this class is inert.
 * The app never holds GitHub credentials; the maintainer triages the
 * inbox (daily fpn-issue-watch) and files real bugs as issues.
 *
 * User control: "Anonymous error reports" toggle, default ON, plus a
 * first-run notice on the main screen. Turning it off stops sending
 * and clears the queue.
 */
public final class ErrorReporter {
    private ErrorReporter() {}

    private static final String PREF = "fpn";
    private static final String KEY_ENABLED = "errReports";
    private static final String KEY_DEDUPE = "reportDedupe";

    public static boolean isEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(Context ctx, boolean on) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_ENABLED, on).apply();
        if (!on) { // opting out also drops anything still queued
            File[] fs = queueDir(ctx).listFiles();
            if (fs != null) for (File f : fs) f.delete();
        }
    }

    public static boolean configPresent(Context ctx) {
        return loadConfig(ctx) != null;
    }

    private static Properties loadConfig(Context ctx) {
        try (InputStream in = ctx.getAssets().open("reporting.properties")) {
            Properties p = new Properties();
            p.load(in);
            String tok = p.getProperty("BOT_TOKEN", "").trim();
            String chat = p.getProperty("CHAT_ID", "").trim();
            if (tok.isEmpty() || chat.isEmpty()) return null;
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    private static File queueDir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "reports");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    // ── report producers ────────────────────────────────────────────────
    /** Queue one report (dedupe: same signature at most once per day). */
    public static void report(Context ctx, String type, String signature,
                              String code, String message) {
        try {
            if (!isEnabled(ctx)) return;
            SharedPreferences prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            String day = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            String sigKey = signature + "@" + day;
            Set<String> sent = new TreeSet<>(
                    prefs.getStringSet(KEY_DEDUPE, new TreeSet<>()));
            // keep only today's keys so the set cannot grow forever
            sent.removeIf(k -> !k.endsWith("@" + day));
            if (sent.contains(sigKey)) return;
            sent.add(sigKey);
            prefs.edit().putStringSet(KEY_DEDUPE, sent).apply();

            String nonce = Long.toHexString(new java.security.SecureRandom().nextLong());
            JSONObject j = new JSONObject()
                    .put("type", type)
                    .put("code", code == null ? "" : code)
                    .put("message", stripIps(Diagnostics.sanitize(message)))
                    .put("app", appVersion(ctx))
                    .put("android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")")
                    .put("device", Build.MANUFACTURER + " " + Build.MODEL)
                    .put("nonce", nonce)
                    .put("log", stripIps(Diagnostics.sanitize(logTail(ctx))));
            File f = new File(queueDir(ctx),
                    System.currentTimeMillis() + "-" + nonce + ".json");
            try (FileOutputStream o = new FileOutputStream(f)) {
                o.write(j.toString().getBytes(StandardCharsets.UTF_8));
            }
            flushAsync(ctx);
        } catch (Exception ignored) { /* reporting must never break the app */ }
    }

    public static void reportDoctor(Context ctx, String code, String summary) {
        report(ctx, "doctor", "doctor:" + code, code, summary);
    }

    public static void reportEngineFatal(Context ctx, String where, Throwable t) {
        report(ctx, "engine", "engine:" + where + ":" + t.getClass().getSimpleName(),
                "E7", where + ": " + String.valueOf(t));
    }

    /** Install the crash handler; call once from the Application. */
    public static void installCrashHandler(final Context ctx) {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, t) -> {
            try {
                StringBuilder sb = new StringBuilder();
                sb.append(t.getClass().getName()).append(": ").append(t.getMessage());
                StackTraceElement[] st = t.getStackTrace();
                for (int i = 0; i < Math.min(6, st.length); i++)
                    sb.append("\n at ").append(st[i]);
                report(ctx, "crash", "crash:" + t.getClass().getSimpleName(),
                        "CRASH", sb.toString());
                // The sender thread may not outlive the crash — give
                // the queued file a moment; it also flushes next start.
                Thread.sleep(400);
            } catch (Exception ignored) {}
            if (prev != null) prev.uncaughtException(thread, t);
        });
    }

    // ── sending (tunnel-only) ───────────────────────────────────────────
    private static volatile boolean flushing = false;

    public static void flushAsync(final Context ctx) {
        if (flushing) return;
        if (!isEnabled(ctx) || loadConfig(ctx) == null) return;
        flushing = true;
        new Thread(() -> {
            try { flush(ctx); } catch (Exception ignored) {}
            flushing = false;
        }, "fpn-reporter").start();
    }

    private static void flush(Context ctx) throws Exception {
        if (!tunnelUp()) return; // reports ride the tunnel or wait
        Properties cfg = loadConfig(ctx);
        if (cfg == null) return;
        File[] fs = queueDir(ctx).listFiles();
        if (fs == null) return;
        java.util.Arrays.sort(fs);
        for (File f : fs) {
            try {
                String raw = readFile(f);
                JSONObject j = new JSONObject(raw);
                String text = "FPN Tunnel — " + j.optString("type") + " report\n"
                        + "code: " + j.optString("code") + "\n"
                        + "app: " + j.optString("app") + " · Android "
                        + j.optString("android") + " · " + j.optString("device") + "\n"
                        + "nonce: " + j.optString("nonce") + "\n"
                        + j.optString("message") + "\n"
                        + "```\n" + truncate(j.optString("log"), 3000) + "\n```";
                if (postTelegram(cfg, text)) f.delete();
            } catch (Exception e) {
                // keep the file; the next flush (tunnel-up event) retries
            }
        }
    }

    private static boolean tunnelUp() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(
                    "http://127.0.0.1:11081/status").openConnection();
            c.setConnectTimeout(1500);
            c.setReadTimeout(1500);
            return c.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean postTelegram(Properties cfg, String text) {
        try {
            JSONObject body = new JSONObject()
                    .put("chat_id", cfg.getProperty("CHAT_ID", "").trim())
                    .put("text", text);
            Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 11080));
            HttpURLConnection c = (HttpURLConnection) new URL(
                    "https://api.telegram.org/bot"
                            + cfg.getProperty("BOT_TOKEN", "").trim() + "/sendMessage")
                    .openConnection(proxy);
            c.setRequestMethod("POST");
            c.setConnectTimeout(15_000);
            c.setReadTimeout(15_000);
            c.setRequestProperty("Content-Type", "application/json");
            c.setDoOutput(true);
            c.getOutputStream().write(body.toString().getBytes(StandardCharsets.UTF_8));
            return c.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────
    private static final java.util.regex.Pattern IPV4 =
            java.util.regex.Pattern.compile("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b");
    private static final java.util.regex.Pattern IPV6 =
            java.util.regex.Pattern.compile("\\b[0-9a-fA-F]{0,4}(?::[0-9a-fA-F]{0,4}){4,}\\b");

    private static String stripIps(String s) {
        if (s == null) return "";
        s = IPV4.matcher(s).replaceAll("<ip>");
        s = IPV6.matcher(s).replaceAll("<ip>");
        return s;
    }

    private static String logTail(Context ctx) {
        try {
            File f = new File(ctx.getFilesDir(), "fpn_log.txt");
            if (!f.exists()) return "";
            long len = f.length();
            int want = (int) Math.min(len, 6000);
            byte[] buf = new byte[want];
            try (FileInputStream in = new FileInputStream(f)) {
                in.skip(len - want);
                int read = 0;
                while (read < want) {
                    int n = in.read(buf, read, want - read);
                    if (n < 0) break;
                    read += n;
                }
            }
            String s = new String(buf, StandardCharsets.UTF_8);
            String[] lines = s.split("\n");
            StringBuilder sb = new StringBuilder();
            for (int i = Math.max(0, lines.length - 30); i < lines.length; i++)
                sb.append(lines[i]).append("\n");
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String appVersion(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private static String readFile(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[in.available()];
            int n = in.read(buf);
            return new String(buf, 0, Math.max(0, n), StandardCharsets.UTF_8);
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(s.length() - max);
    }
}
