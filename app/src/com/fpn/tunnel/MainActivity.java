package com.fpn.tunnel;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
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
    private TextView faHint;
    // 4.4.0: localized country names (aligned with COUNTRIES codes),
    // the language this instance was created in (a Settings switch
    // recreates us on return), and the last rendered service state
    // (transitions get a subtle fade — steady states never flicker).
    private String[] countryNames;
    private String createdLang;
    private int lastState = -1;
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

    /** Localized display name for a country code (English fallback). */
    String countryName(String code) {
        if (countryNames != null)
            for (int i = 0; i < COUNTRIES.length; i++)
                if (COUNTRIES[i][1].equals(code)) return countryNames[i];
        for (String[] c : COUNTRIES) if (c[1].equals(code)) return c[0];
        return code;
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Lang.wrap(base));
    }

    private void refreshAccount() {
        if (BiometricLock.isSealed(this)) {
            acctBtn.setText(R.string.acct_locked);
            return;
        }
        String email = FpnSession.email(this);
        if (email == null) {
            acctBtn.setText(R.string.acct_signin);
            return;
        }
        int n = FpnSession.listAccounts(this).size();
        acctBtn.setText(getString(R.string.acct_format, email,
                (n > 1 ? getString(R.string.acct_more, n - 1) : "")
                        + getString(R.string.acct_hint_switch)));
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
        Ui.Sheet s = Ui.sheet(this, R.string.accounts_title);
        for (int i = 0; i < accs.size(); i++) {
            final FpnSession.Account a = accs.get(i);
            final boolean isActive = a.uid.equals(active);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(Ui.dp(this, 60));
            row.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
            row.setBackgroundResource(R.drawable.row_bg);
            row.setOnClickListener(v -> {
                s.dismiss();
                if (!isActive) switchAccount(a.uid);
            });
            TextView check = new TextView(this);
            check.setText(isActive ? "✓" : "");
            check.setTextColor(Ui.TEAL);
            check.setTextSize(16);
            row.addView(check, new LinearLayout.LayoutParams(
                    Ui.dp(this, 30), LinearLayout.LayoutParams.WRAP_CONTENT));
            TextView email = new TextView(this);
            email.setText(a.email);
            email.setTextColor(Ui.HI);
            email.setTextSize(15);
            email.setSingleLine(true);
            email.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(email, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            if (isActive) {
                TextView chip = new TextView(this);
                chip.setText(R.string.chip_active);
                chip.setTextColor(Ui.TEAL);
                chip.setTextSize(10.5f);
                chip.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                chip.setBackgroundResource(R.drawable.chip_active);
                chip.setPadding(Ui.dp(this, 8), Ui.dp(this, 3), Ui.dp(this, 8), Ui.dp(this, 3));
                row.addView(chip);
            }
            s.body.addView(row);
            if (i < accs.size() - 1) s.body.addView(Ui.divider(this));
        }
        s.action(getString(R.string.add_account), 1, v -> {
            s.dismiss();
            Intent si = new Intent(this, SignInActivity.class);
            si.putExtra("switch_account", true);
            startActivityForResult(si, 2);
        });
        s.action(getString(R.string.remove_dots), 2, v -> {
            s.dismiss();
            openRemoveAccount(accs);
        });
        s.action(getString(R.string.btn_close), 0, v -> s.dismiss());
        s.show();
    }

    private void openRemoveAccount(final java.util.List<FpnSession.Account> accs) {
        Ui.Sheet s = Ui.sheet(this, R.string.remove_which);
        for (final FpnSession.Account victim : accs) {
            s.action(victim.email, 0, v -> {
                s.dismiss();
                Ui.Sheet c = Ui.sheet(this, R.string.remove_title);
                TextView msg = new TextView(this);
                msg.setText(getString(R.string.remove_msg, victim.email));
                msg.setTextColor(Ui.MID);
                msg.setTextSize(14.5f);
                msg.setLineSpacing(0, 1.25f);
                c.body.addView(msg);
                c.action(getString(R.string.btn_remove), 2, vv -> {
                    c.dismiss();
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
                });
                c.action(getString(R.string.btn_cancel), 0, vv -> c.dismiss());
                c.show();
            });
        }
        s.action(getString(R.string.btn_cancel), 0, v -> s.dismiss());
        s.show();
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
        createdLang = Lang.resolved(this);
        countryNames = getResources().getStringArray(R.array.country_names);
        statusDot = findViewById(R.id.statusDot);
        statusText = findViewById(R.id.statusText);
        exitInfo = findViewById(R.id.exitInfo);
        connectBtn = findViewById(R.id.connectBtn);
        acctBtn = findViewById(R.id.acctBtn);
        orb = findViewById(R.id.orbView);
        faHint = findViewById(R.id.faHint);
        // 4.4.0: everything that is not a connect essential moved
        // behind the gear — Doctor, How-it-works, accounts detail,
        // usage detail, language, lock, root, error reports, updates
        // all live in SettingsActivity now.
        findViewById(R.id.gearBtn).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        ((TextView) findViewById(R.id.footerText)).setText(
                getString(R.string.footer, AppUpdate.currentVersion(this)));
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

        // One-line Auto tip for Persian users, first run only (the
        // text itself is @string/fa_hint in the layout).
        if (Lang.isFa(this) && !prefs.getBoolean("faHintSeen", false)) {
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
        names.add(getString(R.string.auto_fastest));
        codes.add("AUTO");
        int sel = 0;
        for (int i = 0; i < COUNTRIES.length; i++) {
            names.add(flag(COUNTRIES[i][1]) + countryName(COUNTRIES[i][1]));
            codes.add(COUNTRIES[i][1]);
            if (COUNTRIES[i][1].equals(saved)) sel = i + 1;
        }
        if ("AUTO".equals(saved)) sel = 0;
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, R.layout.spinner_item, names);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
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
        // A subtle fade on real transitions only — this runs on every
        // poll tick, and steady states must never flicker.
        if (lastState != -1 && lastState != s) {
            for (View v : new View[]{statusText, orb}) {
                if (v == null) continue;
                v.setAlpha(0.45f);
                v.animate().alpha(1f).setDuration(220).start();
            }
        }
        lastState = s;
        if (s == FpnVpnService.ST_ON) {
            connected = true; connecting = false; connectFailed = false;
            if (connectedSince == 0) connectedSince = FpnVpnService.uiStateMs();
            if (connCountry != null) {
                setStateUi(true, false, connCountry, lastPassSec);
            } else {
                statusDot.setTextColor(Color.parseColor("#34D399"));
                statusText.setText(R.string.status_connected);
                connectBtn.setText(R.string.btn_disconnect);
                if (orb != null) orb.setState(OrbView.STATE_ON);
                exitInfo.setText(R.string.exit_checking);
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
                statusText.setText(R.string.status_failed_diagnosing);
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
            statusText.setText(R.string.signin_first);
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

    // Biometric toggle + Root mode panel live in Settings since
    // 4.4.0 (Ui.rootPanel holds the panel); Main keeps only the
    // unlock gate (unlockThen) and the sealed-account behavior.

    // ── Fix G: ConnectionDoctor UI ──────────────────────────────────────
    private File doctorReportFile() {
        return new File(getFilesDir(), ConnectionDoctor.REPORT_FILE);
    }

    // Manual Doctor runs start from Settings (same service action);
    // Main only waits for reports — its own auto-diagnosis after a
    // failed connect, or one that finished while it was away.

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
            ui.post(() -> statusText.setText(R.string.diag_slow));
        }).start();
    }

    private void showDoctorReport() {
        if (doctorShown) return;
        File f = doctorReportFile();
        if (!f.exists()) return;
        getSharedPreferences("fpn", MODE_PRIVATE).edit()
                .putLong("doctorSeenMtime", f.lastModified()).apply();
        doctorShown = true;
        Ui.doctorSheet(this, f, () -> doctorShown = false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Language switched in Settings while we were away: rebuild
        // in the new language/direction before anything renders.
        if (createdLang != null && !Lang.resolved(this).equals(createdLang)) {
            recreate();
            return;
        }
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
        // 4.4.0: the full readout (session, rate, all-time) lives in
        // Settings. The main page keeps ONE slim line under the fox:
        // while connected, setStateUi owns it (exit · server · rtt);
        // otherwise it shows plan + today's total for the ACTIVE
        // account (Fix Q counters, unchanged).
        if (FpnVpnService.uiState() == FpnVpnService.ST_ON) return;
        SharedPreferences p = getSharedPreferences("fpn", MODE_PRIVATE);
        String today = FpnVpnService.todayKey();
        long tUp = today.equals(p.getString(FpnSession.usageKey(this, "usageDate"), ""))
                ? p.getLong(FpnSession.usageKey(this, "usageTodayUp"), 0) : 0;
        long tDown = today.equals(p.getString(FpnSession.usageKey(this, "usageDate"), ""))
                ? p.getLong(FpnSession.usageKey(this, "usageTodayDown"), 0) : 0;
        exitInfo.setText(getString(R.string.plan_unlimited)
                + getString(R.string.value_sep,
                        getString(R.string.usage_today,
                                FpnVpnService.fmtBytes(tDown + tUp))));
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
            statusText.setText(R.string.status_connecting);
            connectBtn.setText(R.string.btn_cancel);
            if (orb != null) orb.setState(OrbView.STATE_CONNECTING);
            exitInfo.setText(R.string.exit_none);
            return;
        }
        if (on) {
            statusDot.setTextColor(Color.parseColor("#34D399"));
            statusText.setText(getString(R.string.status_connected_to,
                    ("AUTO".equals(reqCountry) ? "⚡ → " : "")
                            + flag(cc) + countryName(cc)));
            connectBtn.setText(R.string.btn_disconnect);
            if (orb != null) orb.setState(OrbView.STATE_ON);
            StringBuilder sb = new StringBuilder();
            if (exitIp != null) {
                sb.append(getString(R.string.exit_line, exitIp));
                if (exitLoc != null) sb.append(getString(R.string.exit_at,
                        flag(exitLoc) + countryName(exitLoc)));
            } else {
                sb.append(getString(R.string.exit_checking));
            }
            if (srvName != null) sb.append(getString(R.string.exit_at,
                    srvName.replace(".m1.fastly-masque.net", "")));
            if (srvRtt >= 0) sb.append(getString(R.string.frag_rtt, srvRtt));
            if (passSec >= 0) sb.append(getString(R.string.frag_pass,
                    (passSec / 60) + ":" + String.format("%02d", passSec % 60)));
            if (connectedSince > 0) {
                long s = (System.currentTimeMillis() - connectedSince) / 1000;
                sb.append(getString(R.string.frag_uptime,
                        (s / 60) + ":" + String.format("%02d", s % 60)));
            }
            exitInfo.setText(sb.toString());
        } else {
            statusDot.setTextColor(Color.parseColor("#64748B"));
            statusText.setText(R.string.status_disconnected);
            connectBtn.setText(R.string.btn_connect);
            if (orb != null) orb.setState(OrbView.STATE_OFF);
            exitInfo.setText(R.string.exit_none);
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

    // "Send diagnostics" lives in Settings since 4.4.0.

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
        setStateUi(false, true, null, -1);
        new Thread(() -> {
            pushCountry(code); // best effort; the engine also remembers its last country
            try {
                Intent svc = new Intent(this, FpnVpnService.class);
                if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
                else startService(svc);
            } catch (Exception e) {
                connecting = false;
                ui.post(() -> statusText.setText(
                        getString(R.string.start_failed, String.valueOf(e))));
            }
        }).start();
    }
}
