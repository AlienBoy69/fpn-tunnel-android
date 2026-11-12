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
    private OrbView orb;
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
    private Button doctorBtn, howBtn, bioBtn, rootBtn, errBtn;
    private TextView usageInfo, faHint, reportNotice;
    private volatile long connectStartMs = 0;
    private volatile boolean connectFailed = false;
    private volatile long connectedSince = 0;
    private volatile int lastPassSec = -1;
    // State-sync fix: the fox/label/button render from the SERVICE's
    // published tunnel state (FpnVpnService.uiState), pushed live via
    // this receiver and re-read on every poll tick — never from the
    // engine's status API, which only proves an engine process exists.
    private final android.content.BroadcastReceiver stateRx =
            new android.content.BroadcastReceiver() {
                @Override public void onReceive(android.content.Context c, Intent i) {
                    renderServiceState();
                }
            };
    // Fix L: live usage sampling for the ~2 s smoothed rate.
    private volatile long sessUp = -1, sessDown = -1;
    private long lastSampleUp = -1, lastSampleDown = -1, lastSampleMs = 0;
    private double smoothedRate = 0;
    // Fix G: doctor report dialog bookkeeping.
    private volatile boolean doctorShown = false;
    private volatile boolean doctorWaiting = false;

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
        if (BiometricLock.isSealed(this)) {
            acctBtn.setText("🔒  Session locked — tap Connect to unlock");
            return;
        }
        String email = FpnSession.email(this);
        if (email == null) {
            acctBtn.setText("Sign in with Firefox");
            return;
        }
        int n = FpnSession.listAccounts(this).size();
        acctBtn.setText("👤  " + email + (n > 1 ? "  (+" + (n - 1) + " more)" : "")
                + "  ·  tap to switch");
    }

    // ── Fix Q: multi-account switcher ────────────────────────────────
    // The account button opens the store: tap an account to make it
    // active (the tunnel restarts on it if it was up), Add signs in
    // another account (it is added, never overwriting the others),
    // Remove deletes one from this device after a confirm.

    private void openAccounts() {
        final java.util.List<FpnSession.Account> accs = FpnSession.listAccounts(this);
        if (accs.isEmpty()) {
            Intent si = new Intent(this, SignInActivity.class);
            si.putExtra("switch_account", FpnSession.exists(this));
            startActivityForResult(si, 2);
            return;
        }
        final String active = FpnSession.activeUid(this);
        String[] labels = new String[accs.size()];
        for (int i = 0; i < accs.size(); i++)
            labels[i] = (accs.get(i).uid.equals(active) ? "●  " : "○  ") + accs.get(i).email;
        new android.app.AlertDialog.Builder(this)
                .setTitle("Accounts")
                .setItems(labels, (d, w) -> {
                    if (!accs.get(w).uid.equals(active)) switchAccount(accs.get(w).uid);
                })
                .setNeutralButton("Add account", (d, w) -> {
                    Intent si = new Intent(this, SignInActivity.class);
                    si.putExtra("switch_account", true);
                    startActivityForResult(si, 2);
                })
                .setNegativeButton("Remove…", (d, w) -> openRemoveAccount(accs))
                .setPositiveButton("Close", null)
                .show();
    }

    private void openRemoveAccount(final java.util.List<FpnSession.Account> accs) {
        String[] labels = new String[accs.size()];
        for (int i = 0; i < accs.size(); i++) labels[i] = accs.get(i).email;
        new android.app.AlertDialog.Builder(this)
                .setTitle("Remove which account?")
                .setItems(labels, (d, w) -> {
                    final FpnSession.Account victim = accs.get(w);
                    new android.app.AlertDialog.Builder(this)
                            .setTitle("Remove account")
                            .setMessage("Remove " + victim.email + " from this device? "
                                    + "Its saved sign-in here will be deleted. "
                                    + "The account itself is not affected.")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Remove", (dd, ww) -> {
                                boolean wasActive = victim.uid.equals(FpnSession.activeUid(this));
                                boolean restart = wasActive
                                        && (connected || connecting
                                            || getSharedPreferences("fpn", MODE_PRIVATE)
                                                .getBoolean("wasConnected", false));
                                if (wasActive)
                                    startService(new Intent(this, FpnVpnService.class).setAction("stop"));
                                FpnSession.removeAccount(this, victim.uid);
                                refreshAccount();
                                if (!FpnSession.exists(this)) {
                                    setStateUi(false, false, null, -1);
                                    recreate();
                                } else if (restart) {
                                    ui.postDelayed(this::attemptConnect, 900);
                                } else {
                                    recreate();
                                }
                            })
                            .show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Switch the active account; a running tunnel restarts on it. */
    private void switchAccount(String uid) {
        boolean restart = connected || connecting
                || getSharedPreferences("fpn", MODE_PRIVATE).getBoolean("wasConnected", false);
        startService(new Intent(this, FpnVpnService.class).setAction("stop"));
        connecting = false;
        FpnSession.setActive(this, uid);
        refreshAccount();
        if (restart) {
            setStateUi(false, false, null, -1);
            ui.postDelayed(this::attemptConnect, 900);
        } else {
            recreate();
        }
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
        orb = findViewById(R.id.orbView);
        diagBtn = findViewById(R.id.diagBtn);
        diagBtn.setOnClickListener(v -> shareDiagnostics());
        doctorBtn = findViewById(R.id.doctorBtn);
        doctorBtn.setOnClickListener(v -> runDoctor());
        howBtn = findViewById(R.id.howBtn);
        howBtn.setOnClickListener(v ->
                startActivity(new Intent(this, HowItWorksActivity.class)));
        bioBtn = findViewById(R.id.bioBtn);
        // BiometricPrompt exists only on API 28+; below that the lock
        // feature is cleanly absent (the button stays hidden).
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            bioBtn.setVisibility(View.VISIBLE);
            bioBtn.setText(BiometricLock.stateLabel(this));
            bioBtn.setOnClickListener(v -> toggleBiometricLock());
        }
        rootBtn = findViewById(R.id.rootBtn);
        // Fix N: the Root mode entry appears only when a root path
        // plausibly exists (silent pre-check — no su is executed and
        // no Magisk prompt can appear until the user opens the panel).
        if (RootMode.maybeRoot()) {
            rootBtn.setVisibility(View.VISIBLE);
            rootBtn.setOnClickListener(v -> openRootPanel());
        }
        usageInfo = findViewById(R.id.usageInfo);
        faHint = findViewById(R.id.faHint);
        reportNotice = findViewById(R.id.reportNotice);
        errBtn = findViewById(R.id.errBtn);
        // Fix P: disclosure — the toggle is labeled on the main screen
        // and a one-line notice shows on first run.
        errBtn.setText("Anonymous error reports: "
                + (ErrorReporter.isEnabled(this) ? "ON" : "OFF"));
        errBtn.setOnClickListener(v -> {
            boolean on = !ErrorReporter.isEnabled(this);
            ErrorReporter.setEnabled(this, on);
            errBtn.setText("Anonymous error reports: " + (on ? "ON" : "OFF"));
            if (on) ErrorReporter.flushAsync(this);
        });
        if (!getSharedPreferences("fpn", MODE_PRIVATE).contains("errReports")) {
            reportNotice.setVisibility(View.VISIBLE);
            getSharedPreferences("fpn", MODE_PRIVATE).edit()
                    .putBoolean("errReports", true).apply();
        }
        updateBtn = findViewById(R.id.updateBtn);
        updateBtn.setText("Check for updates · v" + AppUpdate.currentVersion(this));
        updateBtn.setOnClickListener(v -> AppUpdate.check(this, true));
        AppUpdate.check(this, false);

        // Cold start with a sealed session: if both the sealed and the
        // plain session exist (crash mid-toggle), keep only the sealed
        // one — the plain copy must not linger unprotected.
        BiometricLock.reseal(this);

        acctBtn.setOnClickListener(v -> {
            if (BiometricLock.isSealed(this)) { unlockThen(() -> refreshAccount()); return; }
            openAccounts();
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
            attemptConnect();
        });

        final SharedPreferences prefs = getSharedPreferences("fpn", MODE_PRIVATE);
        final String saved = prefs.getString("country", "AUTO");

        // Fix I: one-line Persian hint for fa-IR users, first run only.
        if ("fa".equals(java.util.Locale.getDefault().getLanguage())
                && !prefs.getBoolean("faHintSeen", false)) {
            faHint.setText("💡 برای اتصال راحت‌تر، حالت «خودکار ⚡» را انتخاب کنید؛ برنامه خودش سالم‌ترین سرور را پیدا می‌کند.");
            faHint.setVisibility(View.VISIBLE);
            prefs.edit().putBoolean("faHintSeen", true).apply();
        }

        // First frame renders from the service's actual state, not
        // from the layout defaults — opening the app on a live tunnel
        // must say Connected immediately.
        renderServiceState();
        // Fix B: cold start — no session goes straight to sign-in; a
        // session the user left connected reconnects with no tap.
        // Only from a genuinely OFF service: if the tunnel is already
        // up (or starting), a redundant start would bounce it.
        if (savedInstanceState == null) {
            if (!FpnSession.exists(this) && !BiometricLock.isSealed(this)) {
                Intent si = new Intent(this, SignInActivity.class);
                startActivityForResult(si, 2);
            } else if (prefs.getBoolean("wasConnected", false)
                    && FpnVpnService.uiState() == FpnVpnService.ST_OFF) {
                ui.postDelayed(this::attemptConnect, 800);
            }
        }
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

    /**
     * Render the status dot, label, button and fox from the SERVICE's
     * tunnel state — the single source of truth. Data details (exit,
     * server, pass countdown) layer on top via the poll when ON.
     */
    private void renderServiceState() {
        int s = FpnVpnService.uiState();
        if (s == FpnVpnService.ST_ON) {
            connected = true; connecting = false; connectFailed = false;
            if (connectedSince == 0) connectedSince = FpnVpnService.uiStateMs();
            if (connCountry != null) {
                setStateUi(true, false, connCountry, lastPassSec);
            } else {
                statusDot.setTextColor(Color.parseColor("#34D399"));
                statusText.setText("Connected");
                connectBtn.setText("Disconnect");
                if (orb != null) orb.setState(OrbView.STATE_ON);
                exitInfo.setText("Exit: checking…");
            }
        } else if (s == FpnVpnService.ST_CONNECTING) {
            connected = false; connecting = true;
            if (connectStartMs == 0) connectStartMs = FpnVpnService.uiStateMs();
            setStateUi(false, true, null, -1);
        } else {
            boolean wasConnecting = connecting;
            connected = false; connecting = false; connectedSince = 0;
            setStateUi(false, false, null, -1);
            if (wasConnecting && !connectFailed) {
                // The service fell back to OFF out of a connect attempt:
                // it already queued its own automatic diagnosis.
                connectFailed = true;
                statusText.setText("Connection failed — diagnosing…");
                diagBtn.setText("⚠  Send diagnostics — help improve FPN Tunnel");
                diagBtn.setTextColor(Color.parseColor("#FFB020"));
                final long before = doctorReportFile().exists()
                        ? doctorReportFile().lastModified() : 0;
                waitForDoctorReport(before);
            }
        }
        updateUsageUi();
    }

    /** Connect path shared by the button and Fix B auto-connect. */
    private void attemptConnect() {
        // Never issue a fresh start over a live (or starting) tunnel:
        // the service's tunnelOn tears down before it rebuilds, so a
        // redundant start visibly bounces a healthy connection.
        int ss = FpnVpnService.uiState();
        if (ss == FpnVpnService.ST_ON || ss == FpnVpnService.ST_CONNECTING) {
            renderServiceState();
            return;
        }
        if (connected || connecting) return;
        if (BiometricLock.isSealed(this)) {
            unlockThen(this::attemptConnect);
            return;
        }
        if (!FpnSession.exists(this)) {
            statusText.setText("Sign in with Firefox first");
            Intent si = new Intent(this, SignInActivity.class);
            startActivityForResult(si, 2);
            return;
        }
        Intent prep = VpnService.prepare(this);
        if (prep != null) startActivityForResult(prep, 1);
        else startTunnel();
    }

    /** Run r after the biometric prompt unseals the session. */
    private void unlockThen(final Runnable r) {
        BiometricLock.unlock(this, new BiometricLock.Callback() {
            @Override public void onSuccess() { refreshAccount(); r.run(); }
            @Override public void onError(String m) { statusText.setText(m); }
        });
    }

    private void toggleBiometricLock() {
        if (BiometricLock.isEnabled(this)) {
            BiometricLock.disable(this, new BiometricLock.Callback() {
                @Override public void onSuccess() {
                    bioBtn.setText(BiometricLock.stateLabel(MainActivity.this));
                    refreshAccount();
                }
                @Override public void onError(String m) { statusText.setText(m); }
            });
        } else if (FpnSession.exists(this)) {
            BiometricLock.enable(this, new BiometricLock.Callback() {
                @Override public void onSuccess() {
                    bioBtn.setText(BiometricLock.stateLabel(MainActivity.this));
                    refreshAccount();
                }
                @Override public void onError(String m) { statusText.setText(m); }
            });
        } else {
            statusText.setText("Sign in first, then enable the biometric lock");
        }
    }

    // ── Fix N: Root mode panel ──────────────────────────────────────────
    private void openRootPanel() {
        if (RootMode.hasRootCached(this)) {
            showRootPanel();
            return;
        }
        statusText.setText("Checking for root (Magisk may ask)…");
        RootMode.probeAsync(this, root -> ui.post(() -> {
            if (root) showRootPanel();
            else statusText.setText("Root not available for this app");
        }));
    }

    private android.widget.Switch rootSwitch(String label, boolean on) {
        android.widget.Switch s = new android.widget.Switch(this);
        s.setText(label);
        s.setTextColor(Color.parseColor("#E6EDF3"));
        s.setTextSize(14);
        s.setPadding(0, 14, 0, 14);
        s.setChecked(on);
        return s;
    }

    private void showRootPanel() {
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (18 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);

        final android.widget.Switch swUpdate = rootSwitch(
                "Silent self-update (root)", RootMode.opt(this, "root_update"));
        swUpdate.setOnCheckedChangeListener((v, on) ->
                RootMode.setOpt(this, "root_update", on));
        box.addView(swUpdate);

        final android.widget.Switch swBatt = rootSwitch(
                "Protect from battery optimization (root)",
                RootMode.opt(this, "root_battery"));
        swBatt.setOnCheckedChangeListener((v, on) -> {
            swBatt.setEnabled(false);
            RootMode.setBatteryWhitelist(this, on, ok -> ui.post(() -> {
                swBatt.setEnabled(true);
                swBatt.setChecked(RootMode.opt(this, "root_battery"));
                if (!ok) statusText.setText("Battery whitelist change failed");
            }));
        });
        box.addView(swBatt);

        final android.widget.Switch swLeak = rootSwitch(
                "Doctor leak test (root)", RootMode.opt(this, "root_leak"));
        swLeak.setOnCheckedChangeListener((v, on) ->
                RootMode.setOpt(this, "root_leak", on));
        box.addView(swLeak);

        final android.widget.Switch swKill = rootSwitch(
                "Kernel kill switch — coming soon (not shipped: an app-owned "
                        + "firewall chain could outlive the app and block all traffic)",
                false);
        swKill.setEnabled(false);
        box.addView(swKill);

        new android.app.AlertDialog.Builder(this)
                .setTitle("Root mode")
                .setView(box)
                .setPositiveButton("Done", null)
                .show();
    }

    // ── Fix G: ConnectionDoctor UI ──────────────────────────────────────
    private File doctorReportFile() {
        return new File(getFilesDir(), ConnectionDoctor.REPORT_FILE);
    }

    private void runDoctor() {
        final long before = doctorReportFile().exists()
                ? doctorReportFile().lastModified() : 0;
        try {
            startService(new Intent(this, FpnVpnService.class).setAction("diagnose"));
        } catch (Exception e) {
            try {
                Intent svc = new Intent(this, FpnVpnService.class).setAction("diagnose");
                if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
                else startService(svc);
            } catch (Exception e2) {
                statusText.setText("Diagnosis could not start: " + e2.getMessage());
                return;
            }
        }
        statusText.setText("Diagnosing the connection…");
        waitForDoctorReport(before);
    }

    /** Poll for a fresh doctor report (≤ ~90 s), then show it. */
    private void waitForDoctorReport(final long beforeMs) {
        if (doctorWaiting) return;
        doctorWaiting = true;
        new Thread(() -> {
            for (int i = 0; i < 45; i++) {
                try { Thread.sleep(2000); } catch (InterruptedException e) { break; }
                File f = doctorReportFile();
                if (f.exists() && f.lastModified() > beforeMs) {
                    doctorWaiting = false;
                    ui.post(() -> showDoctorReport());
                    return;
                }
            }
            doctorWaiting = false;
            ui.post(() -> statusText.setText("Diagnosis is taking long — check again shortly"));
        }).start();
    }

    private void showDoctorReport() {
        if (doctorShown) return;
        File f = doctorReportFile();
        if (!f.exists()) return;
        String text;
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            byte[] buf = new byte[in.available()];
            int n = in.read(buf);
            in.close();
            text = n > 0 ? new String(buf, 0, n, "UTF-8") : "";
        } catch (Exception e) {
            return;
        }
        getSharedPreferences("fpn", MODE_PRIVATE).edit()
                .putLong("doctorSeenMtime", f.lastModified()).apply();
        doctorShown = true;
        new android.app.AlertDialog.Builder(this)
                .setTitle("Connection diagnosis")
                .setMessage(text)
                .setPositiveButton("OK", (d, w) -> doctorShown = false)
                .setOnDismissListener(d -> doctorShown = false)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (acctBtn != null) refreshAccount();
        if (orb != null) orb.startAnim(); // Fix O: animate only while visible
        // Live state pushes from the service while foregrounded…
        android.content.IntentFilter sf =
                new android.content.IntentFilter(FpnVpnService.ACTION_STATE);
        if (android.os.Build.VERSION.SDK_INT >= 33)
            registerReceiver(stateRx, sf, android.content.Context.RECEIVER_NOT_EXPORTED);
        else
            registerReceiver(stateRx, sf);
        // …and an immediate re-read, in case a transition (tile, boot,
        // Doctor, watchdog) happened while this screen was away.
        renderServiceState();
        polling = true;
        ui.post(pollTask);
        // A doctor run that finished while the user was away still
        // gets its report shown, once.
        File f = doctorReportFile();
        long seen = getSharedPreferences("fpn", MODE_PRIVATE)
                .getLong("doctorSeenMtime", 0);
        if (f.exists() && f.lastModified() > seen && !doctorShown)
            ui.postDelayed(this::showDoctorReport, 600);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (orb != null) orb.stopAnim();
        try { unregisterReceiver(stateRx); } catch (Exception ignored) {}
        polling = false;
        ui.removeCallbacks(pollTask);
    }

    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            if (!polling) return;
            pollOnce();
            // 1 s cadence: the usage readout (Fix L) updates live.
            ui.postDelayed(this, 1000);
        }
    };

    /**
     * Fix L readout: live session ↓/↑ + smoothed rate while connected,
     * persisted today / all-time totals underneath, and the plan line.
     * All numbers are this device's own traffic — Mozilla exposes no
     * usage meter for FPN and the plan is Unlimited.
     */
    private void updateUsageUi() {
        SharedPreferences p = getSharedPreferences("fpn", MODE_PRIVATE);
        String today = FpnVpnService.todayKey();
        // Fix Q: the readout shows the ACTIVE account's counters.
        long tUp = today.equals(p.getString(FpnSession.usageKey(this, "usageDate"), ""))
                ? p.getLong(FpnSession.usageKey(this, "usageTodayUp"), 0) : 0;
        long tDown = today.equals(p.getString(FpnSession.usageKey(this, "usageDate"), ""))
                ? p.getLong(FpnSession.usageKey(this, "usageTodayDown"), 0) : 0;
        long totUp = p.getLong(FpnSession.usageKey(this, "usageTotalUp"), 0);
        long totDown = p.getLong(FpnSession.usageKey(this, "usageTotalDown"), 0);
        StringBuilder sb = new StringBuilder("Plan: Unlimited");
        if (connected && sessUp >= 0) {
            sb.append("  ·  ↓ ").append(FpnVpnService.fmtBytes(sessDown))
              .append(" · ↑ ").append(FpnVpnService.fmtBytes(sessUp)).append(" this session");
            if (smoothedRate > 0)
                sb.append(" · ").append(FpnVpnService.fmtBytes((long) smoothedRate)).append("/s");
            sb.append("\n");
        } else {
            sb.append("  ·  ");
        }
        sb.append("Today: ").append(FpnVpnService.fmtBytes(tDown + tUp))
          .append("  ·  All time: ").append(FpnVpnService.fmtBytes(totDown + totUp))
          .append("  (this device)");
        usageInfo.setText(sb.toString());
    }

    private void pollOnce() {
        // State first, from the service — synchronously, every tick,
        // so tile/boot/Doctor/watchdog transitions are reflected even
        // if the data fetch below is slow or fails outright.
        ui.post(this::renderServiceState);
        if (FpnVpnService.uiState() != FpnVpnService.ST_ON) {
            if (FpnVpnService.uiState() == FpnVpnService.ST_OFF) {
                connCountry = null; exitIp = null; exitLoc = null;
                sessUp = -1; sessDown = -1; lastSampleMs = 0; smoothedRate = 0;
            }
            return;
        }
        // ON: the engine's status API is a DATA source only (exit
        // country, server, pass countdown, byte counters). Its failure
        // must never flip the displayed state — the service owns that.
        new Thread(() -> {
            JSONObject st = fetchStatus();
            if (st == null) return;
            String cc = st.optString("country", "");
            int pass = st.optInt("passExpiresInSec", -1);
            reqCountry = st.optString("requestedCountry", cc);
            srvName = st.optString("server", null);
            srvRtt = st.optLong("rttMs", -1);
            sessUp = st.optLong("bytesUp", -1);
            sessDown = st.optLong("bytesDown", -1);
            long now = System.currentTimeMillis();
            if (sessUp >= 0 && lastSampleMs > 0 && now > lastSampleMs) {
                double instant = (double) ((sessUp - lastSampleUp)
                        + (sessDown - lastSampleDown)) * 1000.0 / (now - lastSampleMs);
                if (instant >= 0)
                    smoothedRate = smoothedRate == 0 ? instant
                            : 0.5 * smoothedRate + 0.5 * instant;
            }
            if (sessUp >= 0) {
                lastSampleUp = sessUp; lastSampleDown = sessDown; lastSampleMs = now;
            }
            connCountry = cc;
            lastPassSec = pass;
            ui.post(() -> {
                if (FpnVpnService.uiState() == FpnVpnService.ST_ON) {
                    setStateUi(true, false, cc, pass);
                    updateUsageUi();
                }
            });
            if (exitIp == null) fetchExitInfo();
        }).start();
    }

    private void setStateUi(boolean on, boolean busy, String cc, int passSec) {
        if (busy) {
            statusDot.setTextColor(Color.parseColor("#FFB020"));
            statusText.setText("Connecting…");
            connectBtn.setText("Cancel");
            if (orb != null) orb.setState(OrbView.STATE_CONNECTING);
            exitInfo.setText("Exit: —");
            return;
        }
        if (on) {
            statusDot.setTextColor(Color.parseColor("#34D399"));
            statusText.setText("Connected · " + ("AUTO".equals(reqCountry) ? "⚡ → " : "")
                    + flag(cc) + countryName(cc));
            connectBtn.setText("Disconnect");
            if (orb != null) orb.setState(OrbView.STATE_ON);
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
            if (orb != null) orb.setState(OrbView.STATE_OFF);
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
            // Fix E/H: a completed sign-in connects immediately —
            // no extra tap between login and the tunnel.
            if (resultCode == RESULT_OK) attemptConnect();
        }
    }

    private void startTunnel() {
        final String code = getSharedPreferences("fpn", MODE_PRIVATE).getString("country", "AUTO");
        connecting = true;
        connectStartMs = System.currentTimeMillis();
        connectFailed = false;
        diagBtn.setText("Send diagnostics");
        diagBtn.setTextColor(Color.parseColor("#8FA3CC"));
        setStateUi(false, true, null, -1);
        new Thread(() -> {
            pushCountry(code); // best effort; the engine also remembers its last country
            try {
                Intent svc = new Intent(this, FpnVpnService.class);
                if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
                else startService(svc);
            } catch (Exception e) {
                connecting = false;
                ui.post(() -> statusText.setText("Start FAILED: " + e));
            }
        }).start();
    }
}
