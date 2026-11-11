package com.fpn.tunnel;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * FPN Tunnel — v3 UI: a dark, card-based single screen.
 *
 * A status card with a big round connect button, the live exit info
 * (country + IP + pass countdown, polled from the engine's loopback
 * control API), a flag-prefixed location picker, and the account row.
 * All engine/service behaviour is unchanged from v2.
 */
public class MainActivity extends Activity {
    private TextView statusDot, statusText, exitInfo;
    private Button connectBtn, acctBtn;
    private android.view.View glow;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean connected = false;
    private volatile boolean connecting = false;
    private volatile String connCountry = null;
    private volatile String reqCountry = null;
    private volatile String srvName = null;
    private volatile long srvRtt = -1;
    private volatile String exitIp = null;
    private volatile String exitLoc = null;
    private boolean polling = false;
    private Button diagBtn;
    private Button updateBtn;
    private volatile long connectStartMs = 0;
    private volatile boolean connectFailed = false;
    private volatile long connectedSince = 0;

    private static final String[][] COUNTRIES = {
        {"Germany", "DE"}, {"United States", "US"}, {"United Kingdom", "GB"},
        {"France", "FR"}, {"Netherlands", "NL"}, {"Canada", "CA"},
        {"Japan", "JP"}, {"Australia", "AU"}, {"Argentina", "AR"},
        {"South Africa", "ZA"}, {"Austria", "AT"}, {"Belgium", "BE"},
        {"Brazil", "BR"}, {"Bulgaria", "BG"}, {"Chile", "CL"},
        {"Colombia", "CO"}, {"Denmark", "DK"}, {"Finland", "FI"},
        {"Ghana", "GH"}, {"Ireland", "IE"}, {"Italy", "IT"},
        {"Malaysia", "MY"}, {"Mexico", "MX"}, {"New Zealand", "NZ"},
        {"Norway", "NO"}, {"Peru", "PE"}, {"Philippines", "PH"},
        {"Portugal", "PT"}, {"Singapore", "SG"}, {"South Korea", "KR"},
        {"Spain", "ES"}, {"Sweden", "SE"}, {"Thailand", "TH"}
    };

    static String flag(String code) {
        if (code == null || code.length() != 2) return "";
        return new String(new int[]{0x1F1E6 + (code.charAt(0) - 'A'),
                0x1F1E6 + (code.charAt(1) - 'A')}, 0, 2) + " ";
    }

    static String countryName(String code) {
        for (String[] c : COUNTRIES) if (c[1].equals(code)) return c[0];
        return code;
    }

    private void refreshAccount() {
        String email = FpnSession.email(this);
        acctBtn.setText(email != null ? ("👤  " + email + "  ·  tap to switch")
                                      : "Sign in with Firefox");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_main);
        getWindow().setStatusBarColor(Color.parseColor("#0A1122"));
        statusDot = findViewById(R.id.statusDot);
        statusText = findViewById(R.id.statusText);
        exitInfo = findViewById(R.id.exitInfo);
        connectBtn = findViewById(R.id.connectBtn);
        acctBtn = findViewById(R.id.acctBtn);
        glow = findViewById(R.id.glowView);
        diagBtn = findViewById(R.id.diagBtn);
        diagBtn.setOnClickListener(v -> shareDiagnostics());
        updateBtn = findViewById(R.id.updateBtn);
        updateBtn.setText("Check for updates · v" + AppUpdate.currentVersion(this));
        updateBtn.setOnClickListener(v -> AppUpdate.check(this, true));
        AppUpdate.check(this, false);

        acctBtn.setOnClickListener(v -> {
            Intent si = new Intent(this, SignInActivity.class);
            si.putExtra("switch_account", FpnSession.exists(this));
            startActivityForResult(si, 2);
        });
        refreshAccount();

        connectBtn.setOnClickListener(v -> {
            if (connected || connecting) {
                startService(new Intent(this, FpnVpnService.class).setAction("stop"));
                connecting = false;
                connectedSince = 0;
                connectFailed = false;
                setStateUi(false, false, null, -1);
                return;
            }
            if (!FpnSession.exists(this)) {
                statusText.setText("Sign in with Firefox first");
                return;
            }
            Intent prep = VpnService.prepare(this);
            if (prep != null) startActivityForResult(prep, 1);
            else startTunnel();
        });

        final SharedPreferences prefs = getSharedPreferences("fpn", MODE_PRIVATE);
        final String saved = prefs.getString("country", "DE");
        Spinner spin = findViewById(R.id.countrySpinner);
        List<String> names = new ArrayList<>();
        final List<String> codes = new ArrayList<>();
        names.add("⚡ Auto (fastest)");
        codes.add("AUTO");
        int sel = 0;
        for (int i = 0; i < COUNTRIES.length; i++) {
            names.add(flag(COUNTRIES[i][1]) + COUNTRIES[i][0]);
            codes.add(COUNTRIES[i][1]);
            if (COUNTRIES[i][1].equals(saved)) sel = i + 1;
        }
        if ("AUTO".equals(saved)) sel = 0;
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, names) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, convertView, parent);
                tv.setTextColor(Color.parseColor("#E6EDF3"));
                tv.setTextSize(15);
                return tv;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spin.setAdapter(adapter);
        spin.setSelection(sel);
        spin.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            String lastCode = saved;
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                final String code = codes.get(pos);
                prefs.edit().putString("country", code).apply();
                try {
                    FileWriter w = new FileWriter(new File(getFilesDir(), "country.txt"));
                    w.write(code + "\n"); w.close();
                } catch (Exception ignored) {}
                if (code.equals(lastCode)) return;
                lastCode = code;
                new Thread(() -> pushCountry(code)).start();
            }
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (acctBtn != null) refreshAccount();
        polling = true;
        ui.post(pollTask);
    }

    @Override
    protected void onPause() {
        super.onPause();
        polling = false;
        ui.removeCallbacks(pollTask);
    }

    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            if (!polling) return;
            pollOnce();
            ui.postDelayed(this, 3000);
        }
    };

    private void pollOnce() {
        new Thread(() -> {
            JSONObject st = fetchStatus();
            if (st != null) {
                String cc = st.optString("country", "");
                int pass = st.optInt("passExpiresInSec", -1);
                reqCountry = st.optString("requestedCountry", cc);
                srvName = st.optString("server", null);
                srvRtt = st.optLong("rttMs", -1);
                boolean was = connected;
                connected = true; connecting = false; connCountry = cc;
                if (connectedSince == 0) connectedSince = System.currentTimeMillis();
                ui.post(() -> setStateUi(true, false, cc, pass));
                if (!was || exitIp == null) fetchExitInfo();
            } else {
                boolean was = connected;
                connected = false; connCountry = null; exitIp = null; exitLoc = null;
                connectedSince = 0;
                if (connecting && connectStartMs > 0
                        && System.currentTimeMillis() - connectStartMs > 30000 && !connectFailed) {
                    connectFailed = true;
                    connecting = false;
                    ui.post(() -> {
                        setStateUi(false, false, null, -1);
                        statusText.setText("Connection failed");
                        diagBtn.setText("⚠  Send diagnostics — help improve FPN Tunnel");
                        diagBtn.setTextColor(Color.parseColor("#FFB020"));
                    });
                } else if (was || !connecting) {
                    ui.post(() -> setStateUi(false, false, null, -1));
                }
            }
        }).start();
    }

    private void setStateUi(boolean on, boolean busy, String cc, int passSec) {
        if (busy) {
            statusDot.setTextColor(Color.parseColor("#FFB020"));
            statusText.setText("Connecting…");
            connectBtn.setText("Cancel");
            if (glow != null) glow.setBackgroundResource(R.drawable.glow_off);
            connectBtn.setBackgroundResource(R.drawable.circle_off);
            exitInfo.setText("Exit: —");
            return;
        }
        if (on) {
            statusDot.setTextColor(Color.parseColor("#34D399"));
            statusText.setText("Connected · " + ("AUTO".equals(reqCountry) ? "⚡ → " : "")
                    + flag(cc) + countryName(cc));
            connectBtn.setText("Disconnect");
            if (glow != null) glow.setBackgroundResource(R.drawable.glow_on);
            connectBtn.setBackgroundResource(R.drawable.circle_on);
            StringBuilder sb = new StringBuilder();
            if (exitIp != null) {
                sb.append("Exit: ").append(exitIp);
                if (exitLoc != null) sb.append(" · ").append(flag(exitLoc)).append(countryName(exitLoc));
            } else {
                sb.append("Exit: checking…");
            }
            if (srvName != null) sb.append("  ·  ")
                    .append(srvName.replace(".m1.fastly-masque.net", ""));
            if (srvRtt >= 0) sb.append(" · ").append(srvRtt).append(" ms");
            if (passSec >= 0) sb.append("  ·  pass ").append(passSec / 60)
                    .append(":").append(String.format("%02d", passSec % 60));
            if (connectedSince > 0) {
                long s = (System.currentTimeMillis() - connectedSince) / 1000;
                sb.append("  ·  up ").append(s / 60).append(":")
                        .append(String.format("%02d", s % 60));
            }
            exitInfo.setText(sb.toString());
        } else {
            statusDot.setTextColor(Color.parseColor("#64748B"));
            statusText.setText("Disconnected");
            connectBtn.setText("Connect");
            if (glow != null) glow.setBackgroundResource(R.drawable.glow_off);
            connectBtn.setBackgroundResource(R.drawable.circle_off);
            exitInfo.setText("Exit: —");
        }
    }

    private JSONObject fetchStatus() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(
                    "http://127.0.0.1:11081/status").openConnection();
            c.setConnectTimeout(1500);
            c.setReadTimeout(1500);
            return new JSONObject(readAll(c));
        } catch (Exception e) {
            return null;
        }
    }

    /** Exit IP + location, asked through the tunnel itself. */
    private void fetchExitInfo() {
        try {
            Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 11080));
            HttpURLConnection c = (HttpURLConnection) new URL(
                    "https://www.cloudflare.com/cdn-cgi/trace").openConnection(proxy);
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            String body = readAll(c);
            for (String line : body.split("\n")) {
                if (line.startsWith("ip=")) exitIp = line.substring(3).trim();
                if (line.startsWith("loc=")) exitLoc = line.substring(4).trim();
            }
        } catch (Exception ignored) { }
    }

    private static String readAll(HttpURLConnection c) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return sb.toString();
    }

    /**
     * Compose a sanitized diagnostics bundle (Diagnostics) and offer it
     * through the system share sheet. Nothing is ever sent automatically —
     * the user picks where it goes (GitHub issue, email, messenger…).
     */
    private void shareDiagnostics() {
        String state = "State: " + (connected ? "connected"
                : connectFailed ? "CONNECT FAILED" : "disconnected")
                + " · requested=" + reqCountry + " effective=" + connCountry
                + " · server=" + srvName + " · rttMs=" + srvRtt;
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "FPN Tunnel diagnostics");
        send.putExtra(Intent.EXTRA_TEXT, Diagnostics.build(this, state));
        startActivity(Intent.createChooser(send, "Send diagnostics"));
    }

    /** Best-effort: tell the engine which country to exit from. Returns its reply or null. */
    private String pushCountry(String code) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(
                "http://127.0.0.1:11081/country?c=" + code).openConnection();
            c.setConnectTimeout(2500);
            c.setReadTimeout(2500);
            return readAll(c);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1 && resultCode == RESULT_OK) startTunnel();
        if (requestCode == 2) {
            refreshAccount();
            if (resultCode == RESULT_OK) statusText.setText("Signed in — tap Connect");
        }
    }

    private void startTunnel() {
        final String code = getSharedPreferences("fpn", MODE_PRIVATE).getString("country", "DE");
        connecting = true;
        connectStartMs = System.currentTimeMillis();
        connectFailed = false;
        diagBtn.setText("Send diagnostics");
        diagBtn.setTextColor(Color.parseColor("#8FA3CC"));
        setStateUi(false, true, null, -1);
        new Thread(() -> {
            pushCountry(code); // best effort; the engine also remembers its last country
            try {
                startForegroundService(new Intent(this, FpnVpnService.class));
            } catch (Exception e) {
                connecting = false;
                ui.post(() -> statusText.setText("Start FAILED: " + e));
            }
        }).start();
    }
}
