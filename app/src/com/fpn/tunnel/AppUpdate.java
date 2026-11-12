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
                            String u = a.optString("browser_download_url", null);
                            // Fix M: only ever download from GitHub's own
                            // release-asset hosts, over HTTPS — never from
                            // a URL some other host slipped into a response.
                            if (u != null && isGithubAssetUrl(u)) {
                                apkUrl = u;
                                size = a.optLong("size", 0);
                                break;
                            }
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
                    if (manual) toast(act, act.getString(R.string.upd_check_failed));
                    return;
                }
                ulog(act, "check: latest=" + fTag + " current=" + currentVersion(act));
                if (fUrl != null && compare(fTag, currentVersion(act)) > 0) {
                    showDialog(act, fName, fUrl, fSize);
                } else if (manual) {
                    toast(act, act.getString(R.string.upd_latest, currentVersion(act)));
                }
            });
        }).start();
    }

    private static void showDialog(final Activity act, String name, final String url, long size) {
        String sizeTxt = size > 0 ? act.getString(R.string.upd_size, size / 1048576.0) : "";
        new AlertDialog.Builder(act, R.style.FpnDialog)
                .setTitle(R.string.upd_available_title)
                .setMessage(act.getString(R.string.upd_available_msg, name, sizeTxt))
                .setPositiveButton(R.string.btn_update, (d, w) -> download(act, url, size))
                .setNegativeButton(R.string.btn_later, null)
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
        // canRequestPackageInstalls is API 26+; before that the
        // unknown-sources setting governed and PackageInstaller decides.
        boolean can = android.os.Build.VERSION.SDK_INT < 26
                || act.getPackageManager().canRequestPackageInstalls();
        ulog(act, "download tapped; canRequestPackageInstalls=" + can);
        // The permission prompt is advisory and shown at most once: on some
        // OEM builds canRequestPackageInstalls() keeps reporting false even
        // after the user allowed the source, which used to dead-end the flow.
        // After the one advisory, proceed and let PackageInstaller decide.
        android.content.SharedPreferences prefs =
                act.getSharedPreferences("fpn_update", android.content.Context.MODE_PRIVATE);
        if (!can && !prefs.getBoolean("permAsked", false)) {
            new AlertDialog.Builder(act, R.style.FpnDialog)
                    .setTitle(R.string.upd_perm_title)
                    .setMessage(R.string.upd_perm_msg)
                    .setPositiveButton(R.string.btn_open_settings, (d, w) -> {
                        prefs.edit().putBoolean("permAsked", true).apply();
                        try {
                            act.startActivity(new Intent(
                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + act.getPackageName())));
                        } catch (Exception e) {
                            toast(act, act.getString(R.string.upd_cant_open_settings));
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        final ProgressDialog pd = new ProgressDialog(act, R.style.FpnDialog);
        pd.setTitle(R.string.upd_downloading);
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
                    toast(act, act.getString(R.string.upd_download_failed));
                } else {
                    ulog(act, "download ok (" + out.length() + " B); installing");
                    install(act, out);
                }
            });
        }).start();
    }

    /** True for HTTPS URLs on GitHub's release download hosts. */
    private static boolean isGithubAssetUrl(String u) {
        try {
            URL url = new URL(u);
            if (!"https".equals(url.getProtocol())) return false;
            String h = url.getHost();
            return h.equals("github.com") || h.equals("objects.githubusercontent.com");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Fix M — verify a downloaded APK BEFORE it reaches the installer:
     * it must be this same package and carry a signing certificate
     * identical to the installed app's. PackageInstaller would refuse
     * a mismatched update anyway; checking here means a tampered or
     * wrong file is rejected with a clear message instead of a cryptic
     * install failure, and nothing unexpected is ever handed to the
     * system installer.
     */
    private static boolean verifyDownloadedApk(Activity act, File apk) {
        try {
            android.content.pm.PackageManager pm = act.getPackageManager();
            android.content.pm.Signature[] a, b;
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                android.content.pm.PackageInfo arch = pm.getPackageArchiveInfo(
                        apk.getAbsolutePath(), android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES);
                if (arch == null || !act.getPackageName().equals(arch.packageName)) {
                    ulog(act, "verify: package mismatch — rejected");
                    return false;
                }
                android.content.pm.PackageInfo mine = pm.getPackageInfo(
                        act.getPackageName(), android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES);
                a = arch.signingInfo.getApkContentsSigners();
                b = mine.signingInfo.getApkContentsSigners();
            } else {
                // API 25–27: the legacy signatures field carries the same bytes.
                android.content.pm.PackageInfo arch = pm.getPackageArchiveInfo(
                        apk.getAbsolutePath(), android.content.pm.PackageManager.GET_SIGNATURES);
                if (arch == null || !act.getPackageName().equals(arch.packageName)) {
                    ulog(act, "verify: package mismatch — rejected");
                    return false;
                }
                android.content.pm.PackageInfo mine = pm.getPackageInfo(
                        act.getPackageName(), android.content.pm.PackageManager.GET_SIGNATURES);
                a = arch.signatures;
                b = mine.signatures;
            }
            if (a == null || b == null || a.length == 0 || b.length == 0) return false;
            for (android.content.pm.Signature ca : a)
                for (android.content.pm.Signature cb : b)
                    if (java.util.Arrays.equals(ca.toByteArray(), cb.toByteArray())) return true;
            ulog(act, "verify: signing certificate mismatch — rejected");
            return false;
        } catch (Exception e) {
            ulog(act, "verify: failed (" + e + ") — rejected");
            return false;
        }
    }

    private static void install(Activity act, File apk) {
        if (!verifyDownloadedApk(act, apk)) {
            toast(act, act.getString(R.string.upd_rejected));
            try { apk.delete(); } catch (Exception ignored) {}
            return;
        }
        ulog(act, "verify: package + signing certificate match — installing");
        // Fix N: with Root mode's silent update on, install through su
        // (no system installer prompt). The signature check above has
        // already run; any root failure falls back to the normal flow.
        if (RootMode.opt(act, "root_update") && RootMode.hasRootCached(act)) {
            installViaRoot(act, apk);
            return;
        }
        installViaInstaller(act, apk);
    }

    private static void installViaRoot(final Activity act, final File apk) {
        new Thread(() -> {
            String out = RootMode.execSu(act,
                    "pm install -r " + apk.getAbsolutePath(), 120_000);
            boolean ok = out != null && out.contains("Success");
            ulog(act, "root install -> " + (ok ? "Success" : "failed: " + out));
            act.runOnUiThread(() -> {
                if (ok) {
                    toast(act, act.getString(R.string.upd_updated));
                } else {
                    toast(act, act.getString(R.string.upd_root_failed));
                    installViaInstaller(act, apk);
                }
            });
        }, "fpn-rootinstall").start();
    }

    private static void installViaInstaller(Activity act, File apk) {
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
            // Explicit component: a manifest receiver without an intent-filter
            // only receives component-targeted broadcasts — an action-only
            // intent silently never arrives (found in on-device testing).
            Intent intent = new Intent(act, UpdateReceiver.class)
                    .setAction(ACTION_STATUS);
            PendingIntent pending = PendingIntent.getBroadcast(act, 4242, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            session.commit(pending.getIntentSender());
            session.close();
            ulog(act, "install session committed");
        } catch (Exception e) {
            ulog(act, "install failed: " + e.getMessage());
            toast(act, act.getString(R.string.upd_install_failed, e.getMessage()));
        }
    }

    private static void toast(Activity a, String s) {
        Toast.makeText(a, s, Toast.LENGTH_SHORT).show();
    }
}
