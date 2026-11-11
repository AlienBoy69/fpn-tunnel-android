package com.fpn.tunnel;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.regex.Pattern;

/**
 * Sign in with Firefox — Mozilla's own sign-in page, opened the way
 * Firefox opens it for its VPN (context=oauth_webchannel_v1). The page
 * talks to "the browser" in WebChannel messages; the injected script
 * plays Firefox's side of that channel (the WebChannel protocol
 * Mozilla's own mobile clients use), and the page hands the signed-in
 * session to the app.
 *
 * The password is typed into Mozilla's real page inside the WebView —
 * the app never sees it. What the app receives is the session token,
 * stored in its private files (FpnSession) and used only against
 * Mozilla's own servers.
 */
public class SignInActivity extends Activity {
    private static final String CLIENT_ID = "5882386c6d801776";
    private static final String B64URL =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    private static final Pattern SIGN_IN_HOSTS = Pattern.compile(
            "^https://([\\w-]+\\.)*(firefox\\.com|mozilla\\.org|mozilla\\.com|google\\.com|gstatic\\.com|apple\\.com)(/|$)");

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
                    FpnSession.save(SignInActivity.this, m.getJSONObject("account"));
                    runOnUiThread(() -> { setResult(RESULT_OK); finish(); });
                } else if ("done".equals(t) && FpnSession.exists(SignInActivity.this)) {
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

    private static String signInUrl() {
        return "https://accounts.firefox.com/?context=oauth_webchannel_v1&service=vpn"
                + "&client_id=" + CLIENT_ID
                + "&state=" + random43()
                + "&code_challenge=" + random43()
                + "&code_challenge_method=S256&access_type=offline&response_type=code"
                + "&action=email&entrypoint=fpntunnel";
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout lay = new LinearLayout(this);
        lay.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setTextSize(16);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        title.setPadding(pad, pad, pad, pad);
        title.setText("Sign in to your Mozilla account");
        lay.addView(title);

        // Switching accounts starts clean. Otherwise the WebView keeps its
        // cookies, so a Mozilla/Google sign-in done here is remembered and
        // nobody has to log in again on the next visit.
        if (getIntent().getBooleanExtra("switch_account", false)) {
            CookieManager.getInstance().removeAllCookies(null);
            CookieManager.getInstance().flush();
            WebStorage.getInstance().deleteAllData();
        }

        WebView web = new WebView(this);
        WebSettings st = web.getSettings();
        st.setJavaScriptEnabled(true);
        st.setDomStorageEnabled(true);
        st.setSupportMultipleWindows(false);
        st.setJavaScriptCanOpenWindowsAutomatically(false);
        st.setUserAgentString(firefoxUa());
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
        lay.addView(web, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(lay);
        web.loadUrl(signInUrl());
    }
}
