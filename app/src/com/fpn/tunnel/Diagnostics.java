package com.fpn.tunnel;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Composes the diagnostics bundle a user can share when a connection
 * fails. The bundle is built ONLY when the user taps "Send
 * diagnostics" and leaves the device ONLY through the share sheet the
 * user picks — the app never sends it anywhere by itself.
 *
 * The text is sanitized before it is shown: email addresses, JWTs,
 * long hex strings (session material) and token-looking key=value
 * pairs are redacted, so a pasted log cannot leak an account.
 */
public final class Diagnostics {

    private static final Pattern EMAIL =
            Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");
    private static final Pattern JWT =
            Pattern.compile("eyJ[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+");
    private static final Pattern LONG_HEX =
            Pattern.compile("\\b[0-9a-fA-F]{32,}\\b");
    private static final Pattern SECRET_KV = Pattern.compile(
            "(?i)(sessiontoken|keyfetchtoken|refreshtoken|access_token|refresh_token"
                    + "|password|secret|authorization)[\"' :=]+[^\\s\"',}\\]]+");

    private Diagnostics() {}

    public static String sanitize(String s) {
        if (s == null) return "";
        s = JWT.matcher(s).replaceAll("<token>");
        s = EMAIL.matcher(s).replaceAll("<email>");
        s = SECRET_KV.matcher(s).replaceAll("$1=<redacted>");
        s = LONG_HEX.matcher(s).replaceAll("<hex>");
        return s;
    }

    /** Full bundle: header + state line + sanitized tails of both log files. */
    public static String build(Context ctx, String stateLine) {
        StringBuilder sb = new StringBuilder();
        sb.append("FPN Tunnel diagnostics\n");
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            sb.append("App: ").append(pi.versionName)
              .append(" (").append(pi.versionCode).append(")\n");
        } catch (Exception ignored) {}
        sb.append("Android: ").append(Build.VERSION.RELEASE)
          .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append("\n");
        sb.append("Time: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US)
                .format(new Date())).append("\n");
        sb.append(stateLine).append("\n");
        sb.append("Report: https://github.com/msgornami/fpn-tunnel-android/issues\n");
        File dir = ctx.getFilesDir();
        sb.append("\n--- service log (fpn_log.txt) ---\n");
        sb.append(tail(new File(dir, "fpn_log.txt"), 3000));
        sb.append("\n--- engine log (engine_log.txt) ---\n");
        sb.append(tail(new File(dir, "engine_log.txt"), 9000));
        String out = sanitize(sb.toString());
        if (out.length() > 16000) out = out.substring(out.length() - 16000);
        return out;
    }

    private static String tail(File f, int maxChars) {
        try {
            if (!f.exists()) return "(none)\n";
            long len = f.length();
            int want = (int) Math.min(len, maxChars);
            byte[] buf = new byte[want];
            try (FileInputStream in = new FileInputStream(f)) {
                long skipped = in.skip(len - want);
                int read = 0;
                while (read < want) {
                    int n = in.read(buf, read, want - read);
                    if (n < 0) break;
                    read += n;
                }
                String s = new String(buf, 0, read, "UTF-8");
                int nl = s.indexOf('\n');
                if (len > want && nl > 0) s = s.substring(nl + 1); // drop partial first line
                return s.endsWith("\n") ? s : s + "\n";
            }
        } catch (Exception e) {
            return "(unreadable: " + e.getClass().getSimpleName() + ")\n";
        }
    }
}
