package com.fpn.tunnel;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.security.SecureRandom;
import java.util.regex.Pattern;

/**
 * Sign in to Mozilla — two routes, native first:
 *
 * 1) NATIVE (Fix H, beta): the app's own email+password form speaks
 *    the FxA "onepw" API directly (FxaLogin) — no browser, no WebView,
 *    no JavaScript fingerprint (a privacy property, Fix K). Email
 *    codes, TOTP, recovery codes and unblock codes are completed
 *    in-app. Shipped as beta: the KDF is byte-verified against
 *    Mozilla's reference algorithm and the endpoints were live-checked
 *    against the FxA stage environment, but the stage edge WAF refused
 *    scripted logins (HTTP 406), so the first full end-to-end run is
 *    the user's own real-account sign-in.
 *
 * 2) WEB (fallback): Mozilla's own sign-in page in a WebView, the way
 *    Firefox opens it for its VPN (context=oauth_webchannel_v1). The
 *    page talks to "the browser" in WebChannel messages; the injected
 *    script plays Firefox's side of that channel, and the page hands
 *    the signed-in session to the app. This is also the only route
 *    where "Sign in with Google" exists — Google federation lives
 *    inside Mozilla's web page; there is no native Google→FxA token
 *    exchange. The password there is typed into Mozilla's real page —
 *    the app never sees it.
 *
 * Either way the app receives only the session token, stored in its
 * private files (FpnSession) and used only against Mozilla's servers.
 */
public class SignInActivity extends Activity {
    private static final String CLIENT_ID = "5882386c6d801776";
    private static final String B64URL =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    private static final Pattern SIGN_IN_HOSTS = Pattern.compile(
            "^https://([\\w-]+\\.)*(firefox\\.com|mozilla\\.org|mozilla\\.com|google\\.com|gstatic\\.com|apple\\.com)(/|$)");

    // ── native form state ───────────────────────────────────────────────
    private EditText emailEt, passwordEt, codeEt;
    private TextView statusTv, codeLabelTv;
    private Button signInBtn, verifyBtn, resendBtn, recoveryBtn;
    private LinearLayout codeBox;
    private volatile boolean alive = true;
    private FxaLogin.Result pendingResult;
    private String pendingEmail;
    private char[] pendingPassword; // memory only; zeroed when the flow ends
    private String codeMode = "otp"; // otp | totp | recovery | unblock | link

    private FxaLogin fx() {
        File log = new File(getFilesDir(), "engine_log.txt");
        return new FxaLogin(FpnEngine.buildUpstreamFactory(this, log), log);
    }

    @Override
    protected void onDestroy() {
        alive = false;
        if (pendingPassword != null) java.util.Arrays.fill(pendingPassword, '\0');
        super.onDestroy();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(android.graphics.Color.parseColor("#0A1122"));

        TextView title = new TextView(this);
        title.setText("Sign in to your Mozilla account");
        title.setTextSize(18);
        title.setTextColor(android.graphics.Color.parseColor("#EAF1FF"));
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Native sign-in (beta) — no browser needed. Your password "
                + "goes only to Mozilla's API and is never stored or logged.");
        sub.setTextSize(13);
        sub.setTextColor(android.graphics.Color.parseColor("#9FB2D8"));
        sub.setPadding(0, pad / 2, 0, pad);
        root.addView(sub);

        emailEt = new EditText(this);
        emailEt.setHint("Email");
        emailEt.setSingleLine(true);
        emailEt.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        String savedEmail = FpnSession.email(this);
        if (savedEmail != null) emailEt.setText(savedEmail);
        root.addView(emailEt);

        passwordEt = new EditText(this);
        passwordEt.setHint("Password");
        passwordEt.setSingleLine(true);
        passwordEt.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(passwordEt);

        // Autofill hints let Google Password Manager (and other
        // providers) offer the saved Mozilla credential — the realistic
        // form of "sign in with Google" for a native client.
        if (Build.VERSION.SDK_INT >= 26) {
            emailEt.setAutofillHints(View.AUTOFILL_HINT_USERNAME);
            passwordEt.setAutofillHints(View.AUTOFILL_HINT_PASSWORD);
            emailEt.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_YES);
            passwordEt.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_YES);
        }

        signInBtn = new Button(this);
        signInBtn.setText("Sign in");
        root.addView(signInBtn);

        statusTv = new TextView(this);
        statusTv.setTextSize(13);
        statusTv.setTextColor(android.graphics.Color.parseColor("#FFB020"));
        statusTv.setPadding(0, pad / 2, 0, 0);
        root.addView(statusTv);

        // Verification step (email code / TOTP / recovery / unblock).
        codeBox = new LinearLayout(this);
        codeBox.setOrientation(LinearLayout.VERTICAL);
        codeBox.setVisibility(View.GONE);
        codeBox.setPadding(0, pad, 0, 0);
        codeLabelTv = new TextView(this);
        codeLabelTv.setTextSize(14);
        codeLabelTv.setTextColor(android.graphics.Color.parseColor("#EAF1FF"));
        codeBox.addView(codeLabelTv);
        codeEt = new EditText(this);
        codeEt.setHint("Code");
        codeEt.setSingleLine(true);
        codeBox.addView(codeEt);
        verifyBtn = new Button(this);
        verifyBtn.setText("Verify");
        codeBox.addView(verifyBtn);
        LinearLayout codeRow = new LinearLayout(this);
        codeRow.setOrientation(LinearLayout.HORIZONTAL);
        resendBtn = new Button(this);
        resendBtn.setText("Resend code");
        codeRow.addView(resendBtn);
        recoveryBtn = new Button(this);
        recoveryBtn.setText("Use a recovery code");
        recoveryBtn.setVisibility(View.GONE);
        codeRow.addView(recoveryBtn);
        codeBox.addView(codeRow);
        root.addView(codeBox);

        TextView googleNote = new TextView(this);
        googleNote.setText("Google sign-in works only on Mozilla's own page — "
                + "use the web sign-in below if you signed up with Google.");
        googleNote.setTextSize(12);
        googleNote.setTextColor(android.graphics.Color.parseColor("#8FA3CC"));
        googleNote.setPadding(0, pad, 0, 0);
        root.addView(googleNote);

        Button webBtn = new Button(this);
        webBtn.setText("Use the Mozilla page instead (web sign-in)");
        root.addView(webBtn);
        webBtn.setOnClickListener(v -> {
            // The WebView gets its own full-screen layout: a WebView
            // inside the form's ScrollView cannot size itself.
            LinearLayout webBox = new LinearLayout(SignInActivity.this);
            webBox.setOrientation(LinearLayout.VERTICAL);
            webBox.addView(buildWebView(), new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT));
            setContentView(webBox);
        });

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        signInBtn.setOnClickListener(v -> doNativeLogin());
        verifyBtn.setOnClickListener(v -> doVerify());
        resendBtn.setOnClickListener(v -> doResend());
        recoveryBtn.setOnClickListener(v -> {
            codeMode = "recovery";
            codeLabelTv.setText("Enter one of your 10-character recovery codes:");
            codeEt.setInputType(InputType.TYPE_CLASS_TEXT);
            codeEt.setText("");
            recoveryBtn.setVisibility(View.GONE);
        });
    }

    private void setBusy(boolean busy) {
        signInBtn.setEnabled(!busy);
        verifyBtn.setEnabled(!busy);
    }

    private void showStatus(String s) {
        runOnUiThread(() -> statusTv.setText(s == null ? "" : s));
    }

    private void doNativeLogin() {
        final String email = emailEt.getText().toString().trim();
        final char[] pw = passwordEt.getText().toString().toCharArray();
        if (email.isEmpty() || pw.length == 0) {
            showStatus("Enter your email and password.");
            java.util.Arrays.fill(pw, '\0');
            return;
        }
        setBusy(true);
        showStatus("Signing in…");
        new Thread(() -> {
            try {
                FxaLogin.Result r = fx().login(email, pw.clone());
                pendingEmail = email;
                pendingPassword = pw;
                handleResult(r);
            } catch (FxaLogin.FxaError e) {
                java.util.Arrays.fill(pw, '\0');
                if (e.errno == 125) {
                    pendingEmail = email;
                    pendingPassword = pw.clone();
                    try { fx().sendUnblockCode(email); } catch (Exception ignored) {}
                    runOnUiThread(() -> {
                        setBusy(false);
                        codeMode = "unblock";
                        codeLabelTv.setText("Sign-in was blocked for security. "
                                + "Enter the 8-character unblock code from your email:");
                        codeEt.setInputType(InputType.TYPE_CLASS_TEXT);
                        codeBox.setVisibility(View.VISIBLE);
                        showStatus("");
                    });
                } else {
                    showStatus(e.getMessage());
                    runOnUiThread(() -> setBusy(false));
                }
            } catch (Exception e) {
                java.util.Arrays.fill(pw, '\0');
                showStatus("Sign-in failed: " + e.getMessage());
                runOnUiThread(() -> setBusy(false));
            } finally {
                java.util.Arrays.fill(pw, '\0');
            }
        }).start();
    }

    /** Route a fresh login result into save / verification steps. */
    private void handleResult(FxaLogin.Result r) {
        if (r.sessionVerified) {
            finishSave(r);
            return;
        }
        pendingResult = r;
        final String method = r.verificationMethod == null ? "email" : r.verificationMethod;
        runOnUiThread(() -> {
            setBusy(false);
            codeBox.setVisibility(View.VISIBLE);
            recoveryBtn.setVisibility(View.GONE);
            resendBtn.setVisibility(View.VISIBLE);
            if ("totp-2fa".equals(method)) {
                codeMode = "totp";
                codeLabelTv.setText("Enter the 6-digit code from your authenticator app:");
                codeEt.setInputType(InputType.TYPE_CLASS_NUMBER);
                recoveryBtn.setVisibility(View.VISIBLE);
            } else if ("email-otp".equals(method) || "email-2fa".equals(method)) {
                codeMode = "otp";
                codeLabelTv.setText("Enter the 6-digit code we emailed you:");
                codeEt.setInputType(InputType.TYPE_CLASS_NUMBER);
            } else {
                codeMode = "link";
                codeLabelTv.setText("We emailed you a sign-in link. Tap it in "
                        + "your mail app — this screen finishes by itself.");
                codeEt.setVisibility(View.GONE);
                verifyBtn.setVisibility(View.GONE);
                resendBtn.setVisibility(View.GONE);
                startLinkPolling(r);
            }
            showStatus("");
        });
    }

    /** Link-method verification completes outside the app — poll for it. */
    private void startLinkPolling(final FxaLogin.Result r) {
        new Thread(() -> {
            for (int i = 0; i < 40 && alive; i++) {
                try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
                if (!alive) return;
                if (fx().isSessionVerified(r.sessionToken)) {
                    r.sessionVerified = true;
                    finishSave(r);
                    return;
                }
            }
            if (alive) showStatus("Still waiting for the email link — or use the web sign-in below.");
        }).start();
    }

    private void doVerify() {
        final String code = codeEt.getText().toString().trim();
        if ("unblock".equals(codeMode)) {
            if (pendingPassword == null) { showStatus("Sign in again first."); return; }
            setBusy(true);
            showStatus("Retrying with the unblock code…");
            new Thread(() -> {
                try {
                    FxaLogin.Result r = fx().login(pendingEmail, pendingPassword, code);
                    handleResult(r);
                } catch (Exception e) {
                    showStatus(e instanceof FxaLogin.FxaError
                            ? e.getMessage() : "Sign-in failed: " + e.getMessage());
                    runOnUiThread(() -> setBusy(false));
                }
            }).start();
            return;
        }
        if (pendingResult == null) { showStatus("Sign in again first."); return; }
        if (code.isEmpty()) { showStatus("Enter the code."); return; }
        setBusy(true);
        final String token = pendingResult.sessionToken;
        new Thread(() -> {
            try {
                FxaLogin fx = fx();
                if ("totp".equals(codeMode)) fx.verifyTotp(token, code);
                else if ("recovery".equals(codeMode)) fx.verifyRecoveryCode(token, code);
                else fx.verifySigninCode(token, code);
                if (fx.isSessionVerified(token)) {
                    pendingResult.sessionVerified = true;
                    finishSave(pendingResult);
                } else {
                    showStatus("Code accepted, but the session is still unverified. Try again.");
                    runOnUiThread(() -> setBusy(false));
                }
            } catch (Exception e) {
                showStatus(e instanceof FxaLogin.FxaError
                        ? e.getMessage() : "Verification failed: " + e.getMessage());
                runOnUiThread(() -> setBusy(false));
            }
        }).start();
    }

    private void doResend() {
        new Thread(() -> {
            try {
                if ("unblock".equals(codeMode)) fx().sendUnblockCode(pendingEmail);
                else if (pendingResult != null) fx().resendCode(pendingResult.sessionToken);
                showStatus("A fresh code is on its way.");
            } catch (Exception e) {
                showStatus(e instanceof FxaLogin.FxaError
                        ? e.getMessage() : "Resend failed: " + e.getMessage());
            }
        }).start();
    }

    private void finishSave(final FxaLogin.Result r) {
        try {
            JSONObject account = new JSONObject()
                    .put("email", pendingEmail != null ? pendingEmail
                            : emailEt.getText().toString().trim())
                    .put("sessionToken", r.sessionToken)
                    .put("uid", r.uid)
                    .put("verified", true);
            FpnSession.save(this, account);
        } catch (Exception e) {
            showStatus("Signed in, but saving the session failed: " + e.getMessage());
            runOnUiThread(() -> setBusy(false));
            return;
        }
        if (pendingPassword != null) {
            java.util.Arrays.fill(pendingPassword, '\0');
            pendingPassword = null;
        }
        runOnUiThread(() -> { setResult(RESULT_OK); finish(); });
    }

    // ── web sign-in (fallback, Fix E) ───────────────────────────────────
    /** The bridge shim + Firefox's side of the WebChannel. */
    private static final String INJECT =
            "(() => {\n"
            + "  window.ReactNativeWebView = { postMessage: function(s) { FpnBridge.postMessage(s); } };\n"
            + "  if (location.hostname !== \"accounts.firefox.com\" || window.fpnChannel) return;\n"
            + "  window.fpnChannel = true;\n"
            + "  const post = (m) => window.ReactNativeWebView.postMessage(JSON.stringify(m));\n"
            + "  const reply = (command, messageId, data) =>\n"
            + "    window.dispatchEvent(new CustomEvent(\"WebChannelMessageToContent\", { detail: { id: \"account_updates\", message: { command, messageId, data } } }));\n"
            + "  window.addEventListener(\"WebChannelMessageToChrome\", (e) => {\n"
            + "    let d;\n"
            + "    try { d = typeof e.detail === \"string\" ? JSON.parse(e.detail) : e.detail; } catch { return; }\n"
            + "    if (!d || d.id !== \"account_updates\" || !d.message) return;\n"
            + "    const { command, messageId, data } = d.message;\n"
            + "    if (command === \"fxaccounts:fxa_status\")\n"
            + "      reply(command, messageId, { clientId: \"" + CLIENT_ID + "\", capabilities: { engines: [], multiService: true, pairing: false, choose_what_to_sync: false, keys_optional: true, can_link_account_uid: true } });\n"
            + "    else if (command === \"fxaccounts:can_link_account\") reply(command, messageId, { ok: true });\n"
            + "    else if (command === \"fxaccounts:oauth_flow_is_active\") reply(command, messageId, { isActive: true });\n"
            + "    else if (command === \"fxaccounts:login\" && data && data.sessionToken)\n"
            + "      post({ t: \"login\", account: { email: data.email, sessionToken: data.sessionToken, uid: data.uid, verified: !!data.verified } });\n"
            + "    else if (command === \"fxaccounts:oauth_login\") post({ t: \"done\" });\n"
            + "  });\n"
            + "})();\ntrue;";

    public class Bridge {
        @JavascriptInterface
        public void postMessage(String s) {
            try {
                JSONObject m = new JSONObject(s);
                String t = m.optString("t");
                if ("login".equals(t)) {
                    // Fix E: persist the WebView's cookies so this
                    // sign-in is one-time across app restarts.
                    CookieManager.getInstance().flush();
                    FpnSession.save(SignInActivity.this, m.getJSONObject("account"));
                    runOnUiThread(() -> { setResult(RESULT_OK); finish(); });
                } else if ("done".equals(t) && FpnSession.exists(SignInActivity.this)) {
                    CookieManager.getInstance().flush();
                    runOnUiThread(() -> { setResult(RESULT_OK); finish(); });
                }
            } catch (Exception ignored) {}
        }
    }

    private static String random43() {
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder(43);
        for (int i = 0; i < 43; i++) sb.append(B64URL.charAt(r.nextInt(64)));
        return sb.toString();
    }

    /** A current Firefox desktop UA — the page serves the VPN sign-in to Firefox. */
    private static String firefoxUa() {
        long epochOct142025 = 1760400000000L; // 2025-10-14 UTC, Firefox 144's release
        int v = 144 + (int) ((System.currentTimeMillis() - epochOct142025) / (28L * 86_400_000L));
        if (v < 144) v = 144;
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:" + v + ".0) Gecko/20100101 Firefox/" + v + ".0";
    }

    private String signInUrl() {
        String url = "https://accounts.firefox.com/?context=oauth_webchannel_v1&service=vpn"
                + "&client_id=" + CLIENT_ID
                + "&state=" + random43()
                + "&code_challenge=" + random43()
                + "&code_challenge_method=S256&access_type=offline&response_type=code"
                + "&action=email&entrypoint=fpntunnel";
        // Fix E: pre-fill the account email so a returning user types
        // only the password on Mozilla's page.
        String email = FpnSession.email(this);
        if (email != null && !email.isEmpty())
            url += "&email=" + Uri.encode(email);
        return url;
    }

    private WebView buildWebView() {
        // Switching accounts starts clean. Otherwise the WebView keeps
        // its cookies (Fix E: one-time sign-in), so a Mozilla/Google
        // sign-in done here is remembered on the next visit.
        if (getIntent().getBooleanExtra("switch_account", false)) {
            CookieManager.getInstance().removeAllCookies(null);
            CookieManager.getInstance().flush();
            WebStorage.getInstance().deleteAllData();
        }
        WebView web = new WebView(this);
        WebSettings st = web.getSettings();
        st.setJavaScriptEnabled(true);
        st.setDomStorageEnabled(true);
        st.setDatabaseEnabled(true);
        st.setSaveFormData(true);
        st.setSupportMultipleWindows(false);
        st.setJavaScriptCanOpenWindowsAutomatically(false);
        st.setUserAgentString(firefoxUa());
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);
        web.addJavascriptInterface(new Bridge(), "FpnBridge");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                view.evaluateJavascript(INJECT, null);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript(INJECT, null);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (SIGN_IN_HOSTS.matcher(url).find()) return false; // Mozilla/Google/Apple stay inside
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
                catch (Exception ignored) {}
                return true;
            }
        });
        web.loadUrl(signInUrl());
        return web;
    }
}
