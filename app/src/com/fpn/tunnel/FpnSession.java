package com.fpn.tunnel;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The signed-in Firefox accounts (Fix Q — multi-account).
 *
 * Store layout (all inside the app's private files dir):
 *   accounts/<uid>.json   one file per account, same
 *                         {accountData:{...}} shape as a desktop
 *                         Firefox signedInUser.json — what the engine
 *                         reads.
 *   session.json          legacy path; kept as a live MIRROR of the
 *                         active account so every older reader (and a
 *                         downgrade) sees the same session it always
 *                         did.
 *   session.json.bak-v420 one-time backup of the pre-multi-account
 *                         session.json, written by the migration
 *                         before anything could overwrite the mirror.
 *
 * The active account is the "activeUid" pref. Migration is sacred:
 * the first run after upgrading copies the existing session.json
 * into the store as account #1 and leaves the original file in
 * place; the app must connect with that account exactly as before.
 */
public final class FpnSession {
    private FpnSession() {}

    /** One stored account (uid + display email). */
    public static final class Account {
        public final String uid;
        public final String email;
        Account(String uid, String email) { this.uid = uid; this.email = email; }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("fpn", Context.MODE_PRIVATE);
    }

    // ── paths ─────────────────────────────────────────────────────────

    private static File accountsDir(Context ctx) {
        return new File(ctx.getFilesDir(), "accounts");
    }

    private static File accountFile(Context ctx, String uid) {
        return new File(accountsDir(ctx), uid + ".json");
    }

    private static File legacyFile(Context ctx) {
        return new File(ctx.getFilesDir(), "session.json");
    }

    /**
     * The session file the engine reads: the active account's store
     * file once migrated; session.json before migration, or whenever
     * the active file is unavailable (never break a working account).
     */
    public static File file(Context ctx) {
        String uid = activeUid(ctx);
        if (uid != null) {
            File f = accountFile(ctx, uid);
            if (f.isFile()) return f;
        }
        return legacyFile(ctx);
    }

    public static String activeUid(Context ctx) {
        return prefs(ctx).getString("activeUid", null);
    }

    // ── low-level file helpers ────────────────────────────────────────

    private static byte[] readAll(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
    }

    private static void writeFile(File f, byte[] bytes) throws Exception {
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(bytes);
        }
    }

    private static void copy(File from, File to) throws Exception {
        writeFile(to, readAll(from));
    }

    /** Parse a session file into its accountData object (or null). */
    private static JSONObject readAccount(File f) {
        try {
            if (!f.isFile() || f.length() == 0) return null;
            JSONObject doc = new JSONObject(
                    new String(readAll(f), StandardCharsets.UTF_8));
            return doc.optJSONObject("accountData");
        } catch (Exception e) {
            return null;
        }
    }

    // ── migration ─────────────────────────────────────────────────────

    /**
     * One-time, copy-only migration of the legacy session.json into
     * the account store (account #1). Idempotent; safe to call on
     * every process start. A sealed store belongs to BiometricLock —
     * it migrates when the user unlocks, not here.
     */
    public static synchronized void ensureMigrated(Context ctx) {
        try {
            SharedPreferences p = prefs(ctx);
            // Per-account usage scoping rides along with the migration:
            // existing counters are attributed to account #1 once.
            if (activeUid(ctx) != null && !p.getBoolean("usageScoped", false)) {
                String uid = activeUid(ctx);
                SharedPreferences.Editor e = p.edit();
                if (p.contains("usageTodayUp"))
                    e.putLong("usageTodayUp_" + uid, p.getLong("usageTodayUp", 0));
                if (p.contains("usageTodayDown"))
                    e.putLong("usageTodayDown_" + uid, p.getLong("usageTodayDown", 0));
                if (p.contains("usageTotalUp"))
                    e.putLong("usageTotalUp_" + uid, p.getLong("usageTotalUp", 0));
                if (p.contains("usageTotalDown"))
                    e.putLong("usageTotalDown_" + uid, p.getLong("usageTotalDown", 0));
                if (p.contains("usageDate"))
                    e.putString("usageDate_" + uid, p.getString("usageDate", ""));
                e.putBoolean("usageScoped", true).apply();
            }
            if (activeUid(ctx) != null) return;
            if (p.getBoolean("biolock", false)
                    && (new File(ctx.getFilesDir(), "accounts.enc").exists()
                        || new File(ctx.getFilesDir(), "session.json.enc").exists()))
                return; // sealed — BiometricLock owns the data until unlock
            File legacy = legacyFile(ctx);
            JSONObject acct = readAccount(legacy);
            if (acct == null) return;
            String uid = acct.optString("uid", "");
            if (uid.isEmpty()) return; // unparsable: keep using session.json as-is
            File bak = new File(ctx.getFilesDir(), "session.json.bak-v420");
            if (!bak.exists()) copy(legacy, bak);
            accountsDir(ctx).mkdirs();
            copy(legacy, accountFile(ctx, uid));
            p.edit().putString("activeUid", uid).apply();
        } catch (Exception ignored) {}
    }

    // ── store operations ──────────────────────────────────────────────

    public static boolean exists(Context ctx) {
        File f = file(ctx);
        return f.isFile() && f.length() > 0;
    }

    /** All stored accounts, active one first, then by email. */
    public static List<Account> listAccounts(Context ctx) {
        List<Account> out = new ArrayList<>();
        File[] files = accountsDir(ctx).listFiles(
                (d, name) -> name.endsWith(".json"));
        if (files != null) {
            for (File f : files) {
                JSONObject acct = readAccount(f);
                if (acct == null) continue;
                String uid = acct.optString("uid", "");
                if (uid.isEmpty())
                    uid = f.getName().replace(".json", "");
                out.add(new Account(uid, acct.optString("email", uid)));
            }
        }
        final String active = activeUid(ctx);
        out.sort((a, b) -> {
            if (a.uid.equals(active)) return -1;
            if (b.uid.equals(active)) return 1;
            return a.email.compareToIgnoreCase(b.email);
        });
        return out;
    }

    /**
     * Sign-in entry point (native + WebView): the signed-in account is
     * ADDED to the store (or refreshed, if its uid is already stored)
     * and becomes the active account. The legacy mirror follows.
     */
    public static void save(Context ctx, JSONObject account) throws Exception {
        JSONObject doc = new JSONObject().put("accountData", account);
        byte[] bytes = doc.toString().getBytes(StandardCharsets.UTF_8);
        String uid = account.optString("uid", "");
        if (uid.isEmpty()) {
            writeFile(legacyFile(ctx), bytes);
            return;
        }
        accountsDir(ctx).mkdirs();
        writeFile(accountFile(ctx, uid), bytes);
        prefs(ctx).edit().putString("activeUid", uid).apply();
        writeFile(legacyFile(ctx), bytes); // mirror = the active account
    }

    /** Make an already-stored account the active one (mirror follows). */
    public static boolean setActive(Context ctx, String uid) {
        File f = accountFile(ctx, uid);
        if (!f.isFile()) return false;
        try {
            prefs(ctx).edit().putString("activeUid", uid).apply();
            copy(f, legacyFile(ctx));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Remove a stored account from this device. If it was the active
     * one, the first remaining account becomes active; if none remain,
     * the device is signed out (active pointer + mirror removed).
     * Returns true if the removed account was the active one.
     */
    public static boolean removeAccount(Context ctx, String uid) {
        boolean wasActive = uid != null && uid.equals(activeUid(ctx));
        try { accountFile(ctx, uid).delete(); } catch (Exception ignored) {}
        if (wasActive) {
            List<Account> rest = listAccounts(ctx);
            if (!rest.isEmpty()) {
                setActive(ctx, rest.get(0).uid);
            } else {
                prefs(ctx).edit().remove("activeUid").apply();
                try { legacyFile(ctx).delete(); } catch (Exception ignored) {}
            }
        }
        return wasActive;
    }

    /** Sign out of the ACTIVE account (removes it from the store). */
    public static void clear(Context ctx) {
        String uid = activeUid(ctx);
        if (uid != null) removeAccount(ctx, uid);
        else try { legacyFile(ctx).delete(); } catch (Exception ignored) {}
    }

    public static String email(Context ctx) {
        JSONObject acct = readAccount(file(ctx));
        return acct != null ? acct.optString("email", null) : null;
    }

    public static String emailOf(Context ctx, String uid) {
        JSONObject acct = readAccount(accountFile(ctx, uid));
        return acct != null ? acct.optString("email", null) : null;
    }

    // ── per-account usage counters ────────────────────────────────────

    /** Prefs key for a usage counter, scoped to the active account. */
    public static String usageKey(Context ctx, String base) {
        String uid = activeUid(ctx);
        return uid == null ? base : base + "_" + uid;
    }

    // ── biometric bundle (whole-store seal) ───────────────────────────

    /** Serialize the whole store for BiometricLock's sealed bundle. */
    static JSONObject storeBundle(Context ctx) throws Exception {
        JSONObject bundle = new JSONObject();
        bundle.put("activeUid", activeUid(ctx) == null ? "" : activeUid(ctx));
        JSONObject accounts = new JSONObject();
        for (Account a : listAccounts(ctx)) {
            JSONObject acct = readAccount(accountFile(ctx, a.uid));
            if (acct != null) accounts.put(a.uid, acct);
        }
        bundle.put("accounts", accounts);
        return bundle;
    }

    /** Restore a store bundle written by storeBundle(). */
    static void restoreStore(Context ctx, JSONObject bundle) throws Exception {
        accountsDir(ctx).mkdirs();
        JSONObject accounts = bundle.optJSONObject("accounts");
        if (accounts != null) {
            java.util.Iterator<String> uids = accounts.keys();
            while (uids.hasNext()) {
                String uid = uids.next();
                JSONObject acct = accounts.optJSONObject(uid);
                if (acct == null) continue;
                JSONObject doc = new JSONObject().put("accountData", acct);
                writeFile(accountFile(ctx, uid),
                        doc.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
        String active = bundle.optString("activeUid", "");
        if (!active.isEmpty() && accountFile(ctx, active).isFile()) {
            prefs(ctx).edit().putString("activeUid", active).apply();
            copy(accountFile(ctx, active), legacyFile(ctx));
        }
    }

    /** Delete every plaintext store file (used when sealing). */
    static void deletePlainStore(Context ctx) {
        File[] files = accountsDir(ctx).listFiles();
        if (files != null) for (File f : files) { try { f.delete(); } catch (Exception ignored) {} }
        try { legacyFile(ctx).delete(); } catch (Exception ignored) {}
    }

    /** True when any plaintext store file exists. */
    static boolean hasPlainStore(Context ctx) {
        if (legacyFile(ctx).isFile()) return true;
        File[] files = accountsDir(ctx).listFiles(
                (d, name) -> name.endsWith(".json"));
        return files != null && files.length > 0;
    }
}
