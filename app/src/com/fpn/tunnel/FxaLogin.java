package com.fpn.tunnel;

import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLSocketFactory;

/**
 * Fix H — native Firefox Accounts sign-in ("onepw" protocol), no
 * browser and no WebView. The user types their Mozilla email + password
 * into the app's own form (with Password-Manager autofill hints, so
 * Google Password Manager can offer the saved credential); the app
 * derives authPW exactly like Mozilla's own clients and calls the FxA
 * API directly:
 *
 *   v1: stretched = PBKDF2-HMAC-SHA256(password,
 *       "identity.mozilla.com/picl/v1/quickStretch:" + email,
 *       1000 iters, 32 B)
 *   v2: stretched = PBKDF2-HMAC-SHA256(password, clientSalt verbatim,
 *       650000 iters, 32 B) — the clientSalt returned by
 *       POST /account/credentials/status is ALREADY the full salt
 *       string "identity.mozilla.com/picl/v1/quickStretchV2:<hex>";
 *       it must be used exactly as returned (onepw.md §2).
 *   authPW = HKDF-SHA256(stretched, salt empty,
 *       info "identity.mozilla.com/picl/v1/authPW", 32 B), hex
 *   POST /account/login {email, authPW, reason:"login",
 *       service:"5882386c6d801776"} -> sessionToken
 *       (service = the VPN OAuth client id, so the verification email
 *       is actually sent — onepw.md §3.4)
 *
 * Session verification (onepw.md §3.3/§4) happens in-app:
 *   "email-otp"/"email-2fa" -> 6-digit code via POST /session/verify_code
 *   "email" (link)          -> poll GET /session/status until verified
 *   "totp-2fa"              -> POST /session/verify/totp, or a recovery
 *                              code via POST /session/verify/recoveryCode
 * A blocked login (errno 125) is completed with the email unblock code
 * (POST /account/login/send_unblock_code, then login + unblockCode).
 *
 * Session-authenticated calls use the current preferred scheme,
 * Authorization: Bearer fxs_<id> (id = first 32 bytes of the 96-byte
 * HKDF over the sessionToken, onepw.md §5.2), with the legacy HAWK
 * scheme as a fallback if Bearer is refused. The password and authPW
 * exist only in memory for the duration of the call, are never logged
 * and never stored. A small cookie jar is kept for the FxA host: the
 * reference client sends cookies on login "for WAF challenges"
 * (onepw.md §7), and a cookieless client is more likely to be blocked.
 *
 * Protocol reference: ~/workspace/fpn-research/onepw.md, derived from
 * mozilla/fxa source (fetched 2026-11-11). No Android dependencies in
 * this class — it is exercised off-device in tests.
 */
public final class FxaLogin {
    public static final String PROD_API = "https://api.accounts.firefox.com/v1";
    public static final String STAGE_API = "https://api-accounts.stage.mozaws.net/v1";
    /** The VPN/FPN OAuth client id; also the login "service" value. */
    public static final String VPN_CLIENT_ID = "5882386c6d801776";

    private final SSLSocketFactory factory;
    private final File logFile;
    private final String apiBase;
    /** Cookie jar for the FxA host (name -> value), WAF mitigation. */
    private final Map<String, String> cookies = new LinkedHashMap<>();

    public FxaLogin(SSLSocketFactory factory, File logFile) {
        this(factory, logFile, PROD_API);
    }

    public FxaLogin(SSLSocketFactory factory, File logFile, String apiBase) {
        this.factory = factory;
        this.logFile = logFile;
        this.apiBase = apiBase;
    }

    public static final class Result {
        public String uid;
        public String sessionToken;
        /** The account's email address itself is verified. */
        public boolean emailVerified;
        /** THIS session completed its verification step (branch on this). */
        public boolean sessionVerified;
        /** "email", "email-otp", "email-2fa", "totp-2fa", or null. */
        public String verificationMethod;
    }

    /** An FxA refusal, mapped to a plain message + a Doctor reason code. */
    public static final class FxaError extends Exception {
        public final int errno;
        public final int status;
        public final String code; // E1..E7
        FxaError(int errno, int status, String code, String message) {
            super(message);
            this.errno = errno;
            this.status = status;
            this.code = code;
        }
    }

    // ── protocol: key stretching ────────────────────────────────────────────
    private static byte[] pbkdf2(byte[] password, byte[] salt, int iterations) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(password, "HmacSHA256"));
        byte[] block = new byte[salt.length + 4];
        System.arraycopy(salt, 0, block, 0, salt.length);
        block[salt.length + 3] = 1; // block index 1, big-endian
        byte[] u = mac.doFinal(block);
        byte[] t = u.clone();
        for (int i = 1; i < iterations; i++) {
            u = mac.doFinal(u);
            for (int j = 0; j < t.length; j++) t[j] ^= u[j];
        }
        return t;
    }

    /** HKDF-SHA256 with an empty (zero) salt, as FxA uses everywhere. */
    private static byte[] hkdf(byte[] ikm, byte[] info, int len) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);
        java.io.ByteArrayOutputStream okm = new java.io.ByteArrayOutputStream();
        byte[] t = new byte[0];
        int counter = 1;
        while (okm.size() < len) {
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            mac.update(t);
            mac.update(info);
            mac.update((byte) counter++);
            t = mac.doFinal();
            okm.write(t, 0, t.length);
        }
        byte[] out = new byte[len];
        System.arraycopy(okm.toByteArray(), 0, out, 0, len);
        return out;
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++)
            out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        return out;
    }

    /**
     * authPW (hex) for an account. For version 2, clientSalt is the
     * full salt string returned by /account/credentials/status and is
     * used VERBATIM — do not prepend the namespace marker again.
     */
    static String stretchAuthPw(String email, char[] password, int version,
                                String clientSalt) throws Exception {
        byte[] pw = new String(password).getBytes(StandardCharsets.UTF_8);
        try {
            byte[] stretched;
            if (version == 2) {
                stretched = pbkdf2(pw, clientSalt.getBytes(StandardCharsets.UTF_8), 650_000);
            } else {
                String salt = "identity.mozilla.com/picl/v1/quickStretch:" + email;
                stretched = pbkdf2(pw, salt.getBytes(StandardCharsets.UTF_8), 1000);
            }
            byte[] authPw = hkdf(stretched,
                    "identity.mozilla.com/picl/v1/authPW".getBytes(StandardCharsets.UTF_8), 32);
            java.util.Arrays.fill(stretched, (byte) 0);
            return hex(authPw);
        } finally {
            java.util.Arrays.fill(pw, (byte) 0);
        }
    }

    // ── session credentials: Bearer (preferred) + HAWK (fallback) ───────────
    /** 96-byte key material derived from a raw sessionToken (onepw §5.2). */
    private static byte[] tokenMaterial(String sessionTokenHex) throws Exception {
        return hkdf(hexToBytes(sessionTokenHex),
                "identity.mozilla.com/picl/v1/sessionToken".getBytes(StandardCharsets.UTF_8), 96);
    }

    private static String bearerFor(String sessionTokenHex) throws Exception {
        byte[] m = tokenMaterial(sessionTokenHex);
        byte[] id = new byte[32];
        System.arraycopy(m, 0, id, 0, 32);
        return "Bearer fxs_" + hex(id);
    }

    private static final class Creds { String id; byte[] key; }

    private static Creds credsFor(String sessionTokenHex) throws Exception {
        byte[] m = tokenMaterial(sessionTokenHex);
        Creds c = new Creds();
        byte[] id = new byte[32];
        System.arraycopy(m, 0, id, 0, 32);
        c.id = hex(id);
        c.key = new byte[32];
        System.arraycopy(m, 32, c.key, 0, 32);
        return c;
    }

    /** Minimal Base64 (java.util.Base64 needs API 26; this class also
     *  runs on API 25 devices and off-device, so it carries its own). */
    private static String b64(byte[] in) {
        final char[] T = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
                .toCharArray();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < in.length; i += 3) {
            int n = Math.min(3, in.length - i);
            int v = (in[i] & 0xff) << 16
                    | (n > 1 ? (in[i + 1] & 0xff) << 8 : 0)
                    | (n > 2 ? in[i + 2] & 0xff : 0);
            sb.append(T[(v >> 18) & 63]).append(T[(v >> 12) & 63]);
            sb.append(n > 1 ? T[(v >> 6) & 63] : '=');
            sb.append(n > 2 ? T[v & 63] : '=');
        }
        return sb.toString();
    }

    private static String hawkHeader(String method, URL url, String body, Creds creds,
                                     long ts) throws Exception {
        byte[] nonceBytes = new byte[6];
        new SecureRandom().nextBytes(nonceBytes);
        String nonce = b64(nonceBytes);
        java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-256");
        String hash = b64(sha.digest(
                ("hawk.1.payload\napplication/json\n" + (body == null ? "" : body) + "\n")
                        .getBytes(StandardCharsets.UTF_8)));
        int port = url.getPort() != -1 ? url.getPort() : 443;
        String path = url.getPath() + (url.getQuery() != null ? "?" + url.getQuery() : "");
        String normalized = "hawk.1.header\n" + ts + "\n" + nonce + "\n" + method + "\n" + path
                + "\n" + url.getHost() + "\n" + port + "\n" + hash + "\n\n";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(creds.key, "HmacSHA256"));
        String macB64 = b64(mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8)));
        return "Hawk id=\"" + creds.id + "\", ts=\"" + ts + "\", nonce=\"" + nonce
                + "\", hash=\"" + hash + "\", mac=\"" + macB64 + "\"";
    }

    // ── transport ───────────────────────────────────────────────────────────
    private JSONObject request(String method, String path, JSONObject payload,
                               String sessionToken) throws Exception {
        URL url = new URL(apiBase + path);
        String body = payload == null ? null : payload.toString();
        if (sessionToken == null) {
            return requestOnce(method, url, body, null);
        }
        // Bearer first (documented preferred scheme); HAWK only if refused.
        JSONObject data = requestOnce(method, url, body, bearerFor(sessionToken));
        if (data.optInt("_status") != 401) return data;
        Creds creds = credsFor(sessionToken);
        long ts = System.currentTimeMillis() / 1000;
        for (int attempt = 0; ; attempt++) {
            String auth = hawkHeader(method, url, body, creds, ts);
            data = requestOnce(method, url, body, auth);
            if (data.optInt("errno", -1) == 111 && data.has("serverTime") && attempt == 0) {
                ts = data.getLong("serverTime");
                continue;
            }
            return data;
        }
    }

    private JSONObject post(String path, JSONObject payload, String sessionToken) throws Exception {
        return request("POST", path, payload, sessionToken);
    }

    private JSONObject get(String path, String sessionToken) throws Exception {
        return request("GET", path, null, sessionToken);
    }

    private JSONObject requestOnce(String method, URL url, String body, String auth)
            throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        if (body != null) headers.put("Content-Type", "application/json");
        if (auth != null) headers.put("Authorization", auth);
        if (!cookies.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : cookies.entrySet()) {
                if (sb.length() > 0) sb.append("; ");
                sb.append(e.getKey()).append('=').append(e.getValue());
            }
            headers.put("Cookie", sb.toString());
        }
        FpnNet.Resp r;
        try {
            r = FpnNet.request(factory, logFile, method, url.toString(), headers,
                    body == null ? null : body.getBytes(StandardCharsets.UTF_8));
        } catch (Exception netErr) {
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(15_000); c.setReadTimeout(15_000);
            c.setRequestProperty("Accept-Language", "en-US"); // Fix K
            c.setRequestProperty("User-Agent", "FPN-Tunnel/4.2");
            for (Map.Entry<String, String> e : headers.entrySet())
                c.setRequestProperty(e.getKey(), e.getValue());
            if (body != null) {
                c.setDoOutput(true);
                c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            }
            r = new FpnNet.Resp();
            r.status = c.getResponseCode();
            InputStream in = r.status >= 400 ? c.getErrorStream() : c.getInputStream();
            r.body = in != null ? readAll(in) : "";
            String sc = c.getHeaderField("Set-Cookie");
            if (sc != null) r.setCookies.add(sc);
        }
        for (String sc : r.setCookies) {
            int semi = sc.indexOf(';');
            String pair = (semi >= 0 ? sc.substring(0, semi) : sc).trim();
            int eq = pair.indexOf('=');
            if (eq > 0) cookies.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
        }
        JSONObject data = new JSONObject(r.body == null || r.body.isEmpty() ? "{}" : r.body);
        data.put("_status", r.status);
        return data;
    }

    private static String readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    // ── the login flow ──────────────────────────────────────────────────────
    public Result login(String email, char[] password) throws Exception {
        return login(email, password, null);
    }

    /**
     * Full sign-in. With unblockCode set (after an errno-125 block and a
     * sendUnblockCode call), the code rides along on the retry.
     */
    public Result login(String email, char[] password, String unblockCode) throws Exception {
        email = email.trim();
        try {
            return loginOnce(email, password, email, unblockCode, false);
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
    }

    private Result loginOnce(String typedEmail, char[] password, String saltEmail,
                             String unblockCode, boolean caseRetried) throws Exception {
        // Which key-stretch version does this account use? (onepw §2)
        int version = 1;
        String clientSalt = null;
        try {
            JSONObject st = post("/account/credentials/status",
                    new JSONObject().put("email", saltEmail), null);
            if (st.optInt("_status") == 200
                    && ("v2".equals(st.optString("currentVersion"))
                        || st.optBoolean("upgradeNeeded", false))) {
                version = 2;
                clientSalt = st.optString("clientSalt", null);
            }
        } catch (Exception ignored) { /* unknown account etc. — login will say so */ }
        if (version == 2 && (clientSalt == null || clientSalt.isEmpty())) version = 1;
        String authPw = stretchAuthPw(saltEmail, password, version, clientSalt);
        JSONObject payload = new JSONObject()
                .put("email", typedEmail)
                .put("authPW", authPw)
                .put("reason", "login")
                .put("service", VPN_CLIENT_ID);
        if (caseRetried) payload.put("originalLoginEmail", typedEmail);
        if (unblockCode != null && !unblockCode.isEmpty())
            payload.put("unblockCode", unblockCode.trim().toUpperCase(java.util.Locale.US));
        JSONObject data = post("/account/login", payload, null);
        authPw = null;
        if (data.optInt("_status") != 200 || !data.has("sessionToken")) {
            // errno 120: the canonical account email differs only in
            // case — re-derive with the server's spelling and retry once.
            if (data.optInt("errno", -1) == 120 && !caseRetried && data.has("email")) {
                return loginOnce(typedEmail, password, data.getString("email"),
                        unblockCode, true);
            }
            throw mapError(data);
        }
        Result r = new Result();
        r.uid = data.optString("uid", null);
        r.sessionToken = data.getString("sessionToken");
        r.emailVerified = data.optBoolean("emailVerified", false);
        r.sessionVerified = data.optBoolean("sessionVerified",
                data.optBoolean("verified", false));
        r.verificationMethod = data.has("verificationMethod")
                ? data.optString("verificationMethod", null) : null;
        return r;
    }

    /** Ask FxA to email an unblock code after an errno-125 block. */
    public void sendUnblockCode(String email) throws Exception {
        JSONObject data = post("/account/login/send_unblock_code",
                new JSONObject().put("email", email.trim()), null);
        if (data.optInt("_status") != 200) throw mapError(data);
    }

    /** Complete an email-otp/email-2fa session with the 6-digit code. */
    public void verifySigninCode(String sessionToken, String code) throws Exception {
        JSONObject data = post("/session/verify_code",
                new JSONObject().put("code", code.trim()), sessionToken);
        if (data.optInt("_status") != 200) throw mapError(data);
    }

    /** Ask FxA to send a fresh sign-in code email for this session. */
    public void resendCode(String sessionToken) throws Exception {
        JSONObject data = post("/session/resend_code", new JSONObject(), sessionToken);
        if (data.optInt("_status") != 200) throw mapError(data);
    }

    /** Complete a TOTP-protected session with the authenticator code. */
    public void verifyTotp(String sessionToken, String code) throws Exception {
        JSONObject data = post("/session/verify/totp",
                new JSONObject().put("code", code.trim()), sessionToken);
        if (data.optInt("_status") != 200) throw mapError(data);
    }

    /** Complete a TOTP-protected session with a single-use recovery code. */
    public void verifyRecoveryCode(String sessionToken, String code) throws Exception {
        JSONObject data = post("/session/verify/recoveryCode",
                new JSONObject().put("code", code.trim()), sessionToken);
        if (data.optInt("_status") != 200) throw mapError(data);
    }

    /**
     * The link-email target: completes verification from a code in an
     * emailed LINK (a long tokenVerificationId, not a typed code). The
     * app itself polls sessionStatus() to learn the link was clicked.
     */
    public void verifyEmailLinkCode(String uid, String code) throws Exception {
        JSONObject data = post("/recovery_email/verify_code", new JSONObject()
                .put("uid", uid).put("code", code.trim()), null);
        if (data.optInt("_status") != 200) throw mapError(data);
    }

    /** GET /session/status — {state, details:{sessionVerified, …}}. */
    public JSONObject sessionStatus(String sessionToken) throws Exception {
        JSONObject data = get("/session/status", sessionToken);
        if (data.optInt("_status") != 200) throw mapError(data);
        return data;
    }

    /** True once this session's verification step has completed. */
    public boolean isSessionVerified(String sessionToken) {
        try {
            JSONObject st = sessionStatus(sessionToken);
            if ("verified".equals(st.optString("state"))) return true;
            JSONObject d = st.optJSONObject("details");
            return d != null && d.optBoolean("sessionVerified", false);
        } catch (Exception e) {
            return false;
        }
    }

    static FxaError mapError(JSONObject data) {
        int errno = data.optInt("errno", -1);
        int status = data.optInt("_status", 0);
        String serverMsg = data.optString("message", "");
        switch (errno) {
            case 102:
                return new FxaError(errno, status, "E2",
                        "No Mozilla account exists for this email address.");
            case 103:
                return new FxaError(errno, status, "E5",
                        "Incorrect password. Check it and try again.");
            case 104:
                return new FxaError(errno, status, "E2",
                        "This account is not verified yet. Verify it once on Mozilla's page, then sign in here.");
            case 105:
            case 146:
            case 183:
                return new FxaError(errno, status, "E7",
                        "That code was wrong or has expired. Check it and try again.");
            case 110:
                return new FxaError(errno, status, "E2",
                        "The sign-in session has expired. Sign in again.");
            case 114:
                return new FxaError(errno, status, "E7",
                        "Too many attempts. Wait a few minutes and try again.");
            case 120:
                return new FxaError(errno, status, "E2",
                        "Email capitalization does not match the account. Type it exactly as registered.");
            case 125:
                return new FxaError(errno, status, "E7",
                        "Sign-in was blocked for security reasons. An unblock code is on its way to your email.");
            case 127:
                return new FxaError(errno, status, "E7",
                        "That unblock code was not accepted. Check it and try again.");
            case 138:
                return new FxaError(errno, status, "E2",
                        "The sign-in still needs confirmation. Finish the verification step.");
            case 149:
                return new FxaError(errno, status, "E2",
                        "You cannot sign in with this email — it is a secondary address. Use the account's primary email.");
            case 201:
            case 204:
                return new FxaError(errno, status, "E7",
                        "Mozilla's sign-in service is unavailable right now. Try again in a few minutes.");
            default:
                if (status == 429)
                    return new FxaError(errno, status, "E7",
                            "Too many attempts. Wait a few minutes and try again.");
                return new FxaError(errno, status, "E7",
                        serverMsg.isEmpty() ? ("Sign-in failed (HTTP " + status + ")")
                                : "Sign-in failed: " + serverMsg);
        }
    }
}
