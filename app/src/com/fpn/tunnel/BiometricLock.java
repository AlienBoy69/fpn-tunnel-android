package com.fpn.tunnel;

import android.app.Activity;
import android.content.Context;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.CancellationSignal;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Fix H (optional) — "Lock with biometrics": the Firefox session file
 * is sealed with an AndroidKeyStore AES key that itself requires user
 * authentication, so Android shows the system fingerprint / lockscreen
 * prompt before the session can be used to connect. Framework APIs
 * only (android.hardware.biometrics), no new dependencies.
 *
 * Sealed form: files/session.json.enc = [1-byte IV length][IV][AES-GCM
 * ciphertext]. While sealed, session.json is absent, so the engine
 * (and boot auto-connect) cannot use the session until the user
 * unlocks it in the app. Unlocking restores session.json for the rest
 * of the process; the next cold start re-seals it.
 */
public final class BiometricLock {
    private static final String ALIAS = "fpn_session_key";
    private static final String ENC_NAME = "session.json.enc";

    private BiometricLock() {}

    public interface Callback {
        void onSuccess();
        void onError(String message);
    }

    public static boolean isEnabled(Context ctx) {
        return ctx.getSharedPreferences("fpn", Context.MODE_PRIVATE)
                .getBoolean("biolock", false);
    }

    public static boolean isSealed(Context ctx) {
        return isEnabled(ctx) && encFile(ctx).exists() && !FpnSession.file(ctx).exists();
    }

    private static File encFile(Context ctx) {
        return new File(ctx.getFilesDir(), ENC_NAME);
    }

    /** Cold start: if both forms exist, the plain one is stale — re-seal. */
    public static void reseal(Context ctx) {
        if (isEnabled(ctx) && encFile(ctx).exists() && FpnSession.file(ctx).exists())
            FpnSession.file(ctx).delete();
    }

    private static SecretKey key(boolean create) throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) return (SecretKey) ks.getKey(ALIAS, null);
        if (!create) return null;
        KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true);
        if (Build.VERSION.SDK_INT >= 30) {
            b.setUserAuthenticationParameters(0,
                    KeyProperties.AUTH_BIOMETRIC_STRONG | KeyProperties.AUTH_DEVICE_CREDENTIAL);
        } else {
            b.setUserAuthenticationValidityDurationSeconds(-1);
        }
        KeyGenerator kg = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(b.build());
        return kg.generateKey();
    }

    private static byte[] readAll(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
    }

    private static void authenticate(Activity act, Cipher cipher, final Callback cb,
                                     final Runnable onAuthedCipher) {
        BiometricPrompt.AuthenticationCallback callback =
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                        onAuthedCipher.run();
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, CharSequence errString) {
                        cb.onError(String.valueOf(errString));
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        cb.onError("Not recognized. Try again.");
                    }
                };
        BiometricPrompt.CryptoObject crypto = new BiometricPrompt.CryptoObject(cipher);
        // Framework BiometricPrompt has no PromptInfo (that is an
        // AndroidX type) — the Builder is the framework route on every
        // API level this app targets. Device credential is allowed
        // where the platform supports it (API 29+), which forbids a
        // negative button; older levels get the button instead.
        BiometricPrompt.Builder b = new BiometricPrompt.Builder(act)
                .setTitle("Unlock FPN Tunnel")
                .setSubtitle("Confirm it's you to use your Mozilla sign-in");
        if (Build.VERSION.SDK_INT >= 29) {
            b.setDeviceCredentialAllowed(true);
        } else {
            b.setNegativeButton("Cancel", act.getMainExecutor(), (d, w) -> { });
        }
        b.build().authenticate(crypto, new CancellationSignal(),
                act.getMainExecutor(), callback);
    }

    /** Enable the lock: prompt once, seal the current session file. */
    public static void enable(final Activity act, final Callback cb) {
        try {
            if (!FpnSession.exists(act)) { cb.onError("Sign in first."); return; }
            final SecretKey key = key(true);
            final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            authenticate(act, cipher, cb, () -> {
                try {
                    byte[] plain = readAll(FpnSession.file(act));
                    byte[] ct = cipher.doFinal(plain);
                    byte[] iv = cipher.getIV();
                    File enc = encFile(act);
                    try (FileOutputStream o = new FileOutputStream(enc)) {
                        o.write(iv.length);
                        o.write(iv);
                        o.write(ct);
                    }
                    FpnSession.file(act).delete();
                    act.getSharedPreferences("fpn", Context.MODE_PRIVATE).edit()
                            .putBoolean("biolock", true).apply();
                    cb.onSuccess();
                } catch (Exception e) {
                    cb.onError("Could not seal the session: " + e.getMessage());
                }
            });
        } catch (Exception e) {
            cb.onError("Biometric lock unavailable: " + e.getMessage());
        }
    }

    /** Unseal the session (used before Connect while the lock is on). */
    public static void unlock(final Activity act, final Callback cb) {
        try {
            if (!isSealed(act)) { cb.onSuccess(); return; }
            byte[] blob = readAll(encFile(act));
            int ivLen = blob[0] & 0xff;
            byte[] iv = new byte[ivLen];
            System.arraycopy(blob, 1, iv, 0, ivLen);
            final byte[] ct = new byte[blob.length - 1 - ivLen];
            System.arraycopy(blob, 1 + ivLen, ct, 0, ct.length);
            SecretKey key = key(false);
            if (key == null) { cb.onError("Lock key is gone — sign in again."); return; }
            final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            authenticate(act, cipher, cb, () -> {
                try {
                    byte[] plain = cipher.doFinal(ct);
                    try (FileOutputStream o = act.openFileOutput("session.json", Context.MODE_PRIVATE)) {
                        o.write(plain);
                    }
                    cb.onSuccess();
                } catch (Exception e) {
                    cb.onError("Could not unlock the session: " + e.getMessage());
                }
            });
        } catch (Exception e) {
            cb.onError("Unlock failed: " + e.getMessage());
        }
    }

    /** Turn the lock off (unseals first when needed) and drop the key. */
    public static void disable(final Activity act, final Callback cb) {
        Runnable off = () -> {
            try {
                encFile(act).delete();
                KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
                ks.load(null);
                if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS);
            } catch (Exception ignored) { }
            act.getSharedPreferences("fpn", Context.MODE_PRIVATE).edit()
                    .putBoolean("biolock", false).apply();
            cb.onSuccess();
        };
        if (isSealed(act)) {
            unlock(act, new Callback() {
                @Override public void onSuccess() { off.run(); }
                @Override public void onError(String m) { cb.onError(m); }
            });
        } else {
            off.run();
        }
    }

    static String stateLabel(Context ctx) {
        return isEnabled(ctx) ? "🔒  Biometric lock: ON — tap to turn off"
                              : "🔓  Biometric lock: OFF — tap to turn on";
    }
}
