package com.fpn.tunnel;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * In-app self-update (v4.1).
 *
 * Checks the project's GitHub "latest release"; when a newer version exists,
 * offers to download the APK asset and install it with PackageInstaller.
 * Nothing is downloaded or installed without the user's tap. Once, the user
 * must allow "Install unknown apps" for FPN Tunnel (system requirement for
 * any self-updating app outside Play) — the flow opens that settings page.
 */
public class AppUpdate {

    private static final String API =
            "https://api.github.com/repos/AlienBoy69/fpn-tunnel-android/releases/latest";
    public static final String ACTION_STATUS = "com.fpn.tunnel.UPDATE_STATUS";

    public static String currentVersion(Activity a) {
        try { return a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName; }
        catch (Exception e) { return "0"; }
    }

    /** >0 if a is newer than b. Dot-separated numerics; a leading "v" is ignored. */
    static int compare(String a, String b) {
        String[] pa = a.replaceFirst("^[vV]", "").split("\\.");
        String[] pb = b.replaceFirst("^[vV]", "").split("\\.");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            int x = i < pa.length ? num(pa[i]) : 0;
            int y = i < pb.length ? num(pb[i]) : 0;
            if (x != y) return x - y;
        }
        return 0;
    }

    private static int num(String s) {
        try { return Integer.parseInt(s.replaceAll("[^0-9].*$", "")); }
        catch (Exception e) { return 0; }
    }

    public static void check(final Activity act, final boolean manual) {
        new Thread(() -> {
            String tag = null, apkUrl = null, name = null;
            long size = 0;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(API).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(15000);
                c.setRequestProperty("User-Agent", "FPN-Tunnel/" + currentVersion(act));
                c.setRequestProperty("Accept", "application/vnd.github+json");
                StringBuilder sb = new StringBuilder();
                BufferedReader r = new BufferedReader(
                        new InputStreamReader(c.getInputStream(), "UTF-8"));
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                r.close();
                c.disconnect();
                JSONObject j = new JSONObject(sb.toString());
                tag = j.optString("tag_name", "");
                name = j.optString("name", tag);
                JSONArray assets = j.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.getJSONObject(i);
                        if (a.optString("name", "").endsWith(".apk")) {
                            apkUrl = a.optString("browser_download_url", null);
                            size = a.optLong("size", 0);
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                // offline / rate-limited: stay silent unless the user asked
            }
            final String fTag = tag, fUrl = apkUrl, fName = name;
            final long fSize = size;
            act.runOnUiThread(() -> {
                if (act.isFinishing()) return;
                if (fTag == null || fTag.isEmpty()) {
                    ulog(act, "check: no result (offline?) manual=" + manual);
                    if (manual) toast(act, "Couldn't check for updates");
                    return;
                }
                ulog(act, "check: latest=" + fTag + " current=" + currentVersion(act));
                if (fUrl != null && compare(fTag, currentVersion(act)) > 0) {
                    showDialog(act, fName, fUrl, fSize);
                } else if (manual) {
                    toast(act, "You're on the latest version (" + currentVersion(act) + ")");
                }
            });
        }).start();
    }

    private static void showDialog(final Activity act, String name, final String url, long size) {
        String sizeTxt = size > 0 ? String.format(" (%.1f MB)", size / 1048576.0) : "";
        new AlertDialog.Builder(act)
                .setTitle("Update available")
                .setMessage(name + sizeTxt + "\n\nDownload and install it now?")
                .setPositiveButton("Update", (d, w) -> download(act, url, size))
                .setNegativeButton("Later", null)
                .show();
    }

    /** Append a line to the app's service log so update events show up in
     *  diagnostics bundles. */
    static void ulog(android.content.Context ctx, String msg) {
        try {
            java.text.SimpleDateFormat f =
                    new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US);
            FileWriter w = new FileWriter(new File(ctx.getFilesDir(), "fpn_log.txt"), true);
            w.write(f.format(new java.util.Date()) + " update: " + msg + "\n");
            w.close();
        } catch (Exception ignored) {}
    }

    private static void download(final Activity act, final String url, final long size) {
        boolean can = act.getPackageManager().canRequestPackageInstalls();
        ulog(act, "download tapped; canRequestPackageInstalls=" + can);
        // The permission prompt is advisory and shown at most once: on some
        // OEM builds canRequestPackageInstalls() keeps reporting false even
        // after the user allowed the source, which used to dead-end the flow.
        // After the one advisory, proceed and let PackageInstaller decide.
        android.content.SharedPreferences prefs =
                act.getSharedPreferences("fpn_update", android.content.Context.MODE_PRIVATE);
        if (!can && !prefs.getBoolean("permAsked", false)) {
            new AlertDialog.Builder(act)
                    .setTitle("One-time permission needed")
                    .setMessage("Android asks you to allow FPN Tunnel to install its own "
                            + "updates. Turn on \"Allow from this source\" on the next "
                            + "screen, come back, and tap \"Check for updates\" again.")
                    .setPositiveButton("Open settings", (d, w) -> {
                        prefs.edit().putBoolean("permAsked", true).apply();
                        try {
                            act.startActivity(new Intent(
                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + act.getPackageName())));
                        } catch (Exception e) {
                            toast(act, "Couldn't open settings");
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        final ProgressDialog pd = new ProgressDialog(act);
        pd.setTitle("Downloading update…");
        if (size > 0) {
            pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            pd.setMax(100);
        }
        pd.setCancelable(false);
        pd.show();
        new Thread(() -> {
            File out = new File(act.getCacheDir(), "update.apk");
            String error = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setRequestProperty("User-Agent", "FPN-Tunnel/" + currentVersion(act));
                InputStream in = c.getInputStream();
                FileOutputStream fos = new FileOutputStream(out);
                byte[] buf = new byte[65536];
                long got = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                    got += n;
                    if (size > 0) {
                        final int pct = (int) (got * 100 / size);
                        act.runOnUiThread(() -> pd.setProgress(pct));
                    }
                }
                fos.close();
                in.close();
                c.disconnect();
                if (out.length() < 100000) throw new Exception("short download");
            } catch (Exception e) {
                error = e.getMessage();
            }
            final String fErr = error;
            act.runOnUiThread(() -> {
                pd.dismiss();
                if (act.isFinishing()) return;
                if (fErr != null) {
                    ulog(act, "download failed: " + fErr);
                    toast(act, "Download failed — try again later");
                } else {
                    ulog(act, "download ok (" + out.length() + " B); installing");
                    install(act, out);
                }
            });
        }).start();
    }

    private static void install(Activity act, File apk) {
        try {
            PackageInstaller pi = act.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            int sessionId = pi.createSession(params);
            PackageInstaller.Session session = pi.openSession(sessionId);
            OutputStream out = session.openWrite("fpn-update", 0, apk.length());
            FileInputStream in = new FileInputStream(apk);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            session.fsync(out);
            out.close();
            in.close();
            Intent intent = new Intent(ACTION_STATUS).setPackage(act.getPackageName());
            PendingIntent pending = PendingIntent.getBroadcast(act, 4242, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            session.commit(pending.getIntentSender());
            session.close();
            ulog(act, "install session committed");
        } catch (Exception e) {
            ulog(act, "install failed: " + e.getMessage());
            toast(act, "Install failed: " + e.getMessage());
        }
    }

    private static void toast(Activity a, String s) {
        Toast.makeText(a, s, Toast.LENGTH_SHORT).show();
    }
}
