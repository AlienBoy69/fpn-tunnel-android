package com.fpn.tunnel;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Settings (4.4.0) — everything that is not a connect essential,
 * moved off the main page at Majid's order ("one page, no scroll;
 * the rest in Settings"): language, accounts, Doctor, How-it-works,
 * diagnostics, full usage, biometric lock, error reports, root mode,
 * updates. Secondary screens may scroll; this one does.
 *
 * Nothing here owns connection state: the Doctor still runs in the
 * service, state still comes from FpnVpnService, accounts still go
 * through FpnSession.
 */
public class SettingsActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView statusLine, bioValue, errValue, rootValue, updValue;
    private View acctRowView;
    private TextView usageBody;
    private boolean doctorRunning = false;
    private boolean alive = false;
    private volatile long sessUp = -1, sessDown = -1;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Lang.wrap(base));
    }

    private int dp(float v) { return Ui.dp(this, v); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(0xFF0A1122);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(24));

        root.addView(Ui.backRow(this));
        TextView title = new TextView(this);
        title.setText(R.string.settings_title);
        title.setTextColor(Ui.HI);
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        tp.topMargin = dp(6);
        root.addView(title, tp);

        statusLine = new TextView(this);
        statusLine.setTextColor(Ui.AMBER);
        statusLine.setTextSize(13);
        statusLine.setMinHeight(dp(20));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(4);
        root.addView(statusLine, sp);

        // ── language ─────────────────────────────────────────────
        root.addView(section(R.string.section_language));
        root.addView(languageSwitcher());

        // ── account ──────────────────────────────────────────────
        root.addView(section(R.string.section_account));
        LinearLayout acctCard = Ui.card(this);
        acctRowView = Ui.row(this, accountLabel(), null,
                v -> AccountsUi.show(this, this::refreshAccountRow));
        acctCard.addView(acctRowView);
        acctCard.addView(Ui.divider(this));
        acctCard.addView(Ui.row(this, getString(R.string.add_account), null, v -> {
            Intent si = new Intent(this, SignInActivity.class);
            si.putExtra("switch_account", true);
            startActivityForResult(si, AccountsUi.RC_SIGNIN);
        }));
        root.addView(acctCard);

        // ── connection ───────────────────────────────────────────
        root.addView(section(R.string.section_connection));
        LinearLayout connCard = Ui.card(this);
        connCard.addView(Ui.row(this, getString(R.string.row_doctor), null,
                v -> runDoctor()));
        connCard.addView(Ui.divider(this));
        connCard.addView(Ui.row(this, getString(R.string.row_how), null,
                v -> startActivity(new Intent(this, HowItWorksActivity.class))));
        connCard.addView(Ui.divider(this));
        connCard.addView(Ui.row(this, getString(R.string.row_diag), null,
                v -> shareDiagnostics()));
        root.addView(connCard);

        // ── usage ────────────────────────────────────────────────
        root.addView(section(R.string.section_usage));
        LinearLayout usageCard = Ui.card(this);
        usageBody = new TextView(this);
        usageBody.setTextColor(Ui.MID);
        usageBody.setTextSize(14);
        usageBody.setLineSpacing(0, 1.45f);
        usageBody.setPadding(dp(18), dp(14), dp(18), dp(14));
        usageCard.addView(usageBody);
        root.addView(usageCard);

        // ── preferences ──────────────────────────────────────────
        root.addView(section(R.string.section_preferences));
        LinearLayout prefCard = Ui.card(this);
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            bioValue = new TextView(this);
            prefCard.addView(Ui.row(this, getString(R.string.row_bio), bioValue,
                    v -> toggleBiometricLock()));
            prefCard.addView(Ui.divider(this));
        }
        errValue = new TextView(this);
        prefCard.addView(Ui.row(this, getString(R.string.row_err), errValue, v -> {
            boolean on = !ErrorReporter.isEnabled(this);
            ErrorReporter.setEnabled(this, on);
            errValue.setText(getString(on ? R.string.state_on : R.string.state_off));
            if (on) ErrorReporter.flushAsync(this);
        }));
        if (RootMode.maybeRoot()) {
            prefCard.addView(Ui.divider(this));
            rootValue = new TextView(this);
            rootValue.setText(getString(RootMode.hasRootCached(this)
                    ? R.string.state_on : R.string.state_off));
            prefCard.addView(Ui.row(this, getString(R.string.row_root), rootValue,
                    v -> openRootPanel()));
        }
        root.addView(prefCard);
        TextView notice = new TextView(this);
        notice.setText(R.string.report_notice);
        notice.setTextColor(Ui.FAINT);
        notice.setTextSize(12);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        np.topMargin = dp(8);
        np.setMarginStart(dp(4));
        root.addView(notice, np);

        // ── app ──────────────────────────────────────────────────
        root.addView(section(R.string.section_app));
        LinearLayout appCard = Ui.card(this);
        updValue = new TextView(this);
        updValue.setText("v" + AppUpdate.currentVersion(this));
        appCard.addView(Ui.row(this, getString(R.string.row_update), updValue,
                v -> AppUpdate.check(this, true)));
        root.addView(appCard);

        TextView footer = new TextView(this);
        footer.setText(getString(R.string.footer, AppUpdate.currentVersion(this)));
        footer.setTextColor(Ui.FAINT);
        footer.setTextSize(11.5f);
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        fp.topMargin = dp(18);
        root.addView(footer, fp);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundResource(R.drawable.bg_main);
        scroll.setFillViewport(true);
        scroll.addView(root);
        setContentView(scroll);

        refreshAccountRow();
        refreshUsage();
        if (errValue != null)
            errValue.setText(getString(ErrorReporter.isEnabled(this)
                    ? R.string.state_on : R.string.state_off));
        if (bioValue != null)
            bioValue.setText(getString(BiometricLock.isEnabled(this)
                    ? R.string.state_on : R.string.state_off));
    }

    private TextView section(int res) {
        TextView t = Ui.sectionLabel(this, res);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(22);
        p.setMarginStart(dp(2));
        t.setLayoutParams(p);
        return t;
    }

    private View languageSwitcher() {
        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setBackgroundResource(R.drawable.seg_wrap);
        seg.setPadding(dp(4), dp(4), dp(4), dp(4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        seg.setLayoutParams(lp);
        boolean fa = Lang.isFa(this);
        seg.addView(segSide(getString(R.string.lang_name_en), !fa, v -> {
            if (fa) { Lang.set(this, Lang.EN); recreate(); }
        }));
        seg.addView(segSide(getString(R.string.lang_name_fa), fa, v -> {
            if (!fa) { Lang.set(this, Lang.FA); recreate(); }
        }));
        return seg;
    }

    private TextView segSide(String label, boolean on, View.OnClickListener click) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(14.5f);
        t.setMinHeight(dp(46));
        t.setTextColor(on ? Ui.HI : Ui.DIM);
        if (on) {
            t.setBackgroundResource(R.drawable.seg_on);
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        t.setOnClickListener(click);
        t.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return t;
    }

    private String accountLabel() {
        if (BiometricLock.isSealed(this)) return getString(R.string.acct_locked);
        String email = FpnSession.email(this);
        if (email == null) return getString(R.string.acct_signin);
        int n = FpnSession.listAccounts(this).size();
        return getString(R.string.acct_format, email,
                n > 1 ? getString(R.string.acct_more, n - 1) : "");
    }

    private void refreshAccountRow() {
        // The row's label is its first child (Ui.row contract).
        if (acctRowView instanceof LinearLayout) {
            View first = ((LinearLayout) acctRowView).getChildAt(0);
            if (first instanceof TextView) ((TextView) first).setText(accountLabel());
        }
        refreshUsage();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == AccountsUi.RC_SIGNIN) recreate();
    }

    // ── doctor ───────────────────────────────────────────────────
    private File doctorReportFile() {
        return new File(getFilesDir(), ConnectionDoctor.REPORT_FILE);
    }

    private void runDoctor() {
        if (doctorRunning) return;
        doctorRunning = true;
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
                doctorRunning = false;
                statusLine.setText(getString(R.string.diag_cant_start, e2.getMessage()));
                return;
            }
        }
        statusLine.setText(R.string.diagnosing);
        new Thread(() -> {
            for (int i = 0; i < 45; i++) {
                try { Thread.sleep(2000); } catch (InterruptedException e) { break; }
                File f = doctorReportFile();
                if (f.exists() && f.lastModified() > before) {
                    final File ff = f;
                    ui.post(() -> {
                        doctorRunning = false;
                        statusLine.setText("");
                        getSharedPreferences("fpn", MODE_PRIVATE).edit()
                                .putLong("doctorSeenMtime", ff.lastModified()).apply();
                        Ui.doctorSheet(this, ff, null);
                    });
                    return;
                }
            }
            ui.post(() -> {
                doctorRunning = false;
                statusLine.setText(R.string.diag_slow);
            });
        }).start();
    }

    // ── diagnostics share ────────────────────────────────────────
    private void shareDiagnostics() {
        int st = FpnVpnService.uiState();
        String state = "State: " + (st == FpnVpnService.ST_ON ? "connected"
                : st == FpnVpnService.ST_CONNECTING ? "connecting" : "disconnected")
                + " · requested=" + getSharedPreferences("fpn", MODE_PRIVATE)
                        .getString("country", "AUTO");
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "FPN Tunnel diagnostics");
        send.putExtra(Intent.EXTRA_TEXT, Diagnostics.build(this, state));
        startActivity(Intent.createChooser(send, getString(R.string.row_diag)));
    }

    // ── usage ────────────────────────────────────────────────────
    private void refreshUsage() {
        SharedPreferences p = getSharedPreferences("fpn", MODE_PRIVATE);
        String today = FpnVpnService.todayKey();
        long tUp = today.equals(p.getString(FpnSession.usageKey(this, "usageDate"), ""))
                ? p.getLong(FpnSession.usageKey(this, "usageTodayUp"), 0) : 0;
        long tDown = today.equals(p.getString(FpnSession.usageKey(this, "usageDate"), ""))
                ? p.getLong(FpnSession.usageKey(this, "usageTodayDown"), 0) : 0;
        long totUp = p.getLong(FpnSession.usageKey(this, "usageTotalUp"), 0);
        long totDown = p.getLong(FpnSession.usageKey(this, "usageTotalDown"), 0);
        StringBuilder sb = new StringBuilder(getString(R.string.plan_unlimited));
        if (sessUp >= 0)
            sb.append("\n").append(getString(R.string.usage_session,
                    FpnVpnService.fmtBytes(sessDown),
                    FpnVpnService.fmtBytes(sessUp)));
        sb.append("\n").append(getString(R.string.usage_today,
                FpnVpnService.fmtBytes(tDown + tUp)));
        sb.append("\n").append(getString(R.string.usage_all_time,
                FpnVpnService.fmtBytes(totDown + totUp)));
        usageBody.setText(sb.toString());
    }

    private final Runnable usageTick = new Runnable() {
        @Override public void run() {
            if (!alive) return;
            if (FpnVpnService.uiState() == FpnVpnService.ST_ON) {
                new Thread(() -> {
                    try {
                        HttpURLConnection c = (HttpURLConnection) new URL(
                                "http://127.0.0.1:11081/status").openConnection();
                        c.setConnectTimeout(1500);
                        c.setReadTimeout(1500);
                        BufferedReader br = new BufferedReader(
                                new InputStreamReader(c.getInputStream()));
                        StringBuilder b = new StringBuilder();
                        String line;
                        while ((line = br.readLine()) != null) b.append(line);
                        br.close();
                        JSONObject st = new JSONObject(b.toString());
                        sessUp = st.optLong("bytesUp", -1);
                        sessDown = st.optLong("bytesDown", -1);
                    } catch (Exception ignored) {}
                    ui.post(() -> { if (alive) refreshUsage(); });
                }).start();
            } else {
                if (sessUp != -1) { sessUp = -1; sessDown = -1; }
                refreshUsage();
            }
            ui.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        alive = true;
        refreshUsage();
        ui.post(usageTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        alive = false;
        ui.removeCallbacks(usageTick);
    }

    // ── biometric + root ─────────────────────────────────────────
    private void toggleBiometricLock() {
        if (BiometricLock.isEnabled(this)) {
            BiometricLock.disable(this, new BiometricLock.Callback() {
                @Override public void onSuccess() {
                    bioValue.setText(getString(R.string.state_off));
                }
                @Override public void onError(String m) { statusLine.setText(m); }
            });
        } else if (FpnSession.exists(this)) {
            BiometricLock.enable(this, new BiometricLock.Callback() {
                @Override public void onSuccess() {
                    bioValue.setText(getString(R.string.state_on));
                }
                @Override public void onError(String m) { statusLine.setText(m); }
            });
        } else {
            statusLine.setText(R.string.signin_first_bio);
        }
    }

    private void openRootPanel() {
        if (RootMode.hasRootCached(this)) {
            Ui.rootPanel(this, msg -> statusLine.setText(msg));
            return;
        }
        statusLine.setText(R.string.checking_root);
        RootMode.probeAsync(this, root -> ui.post(() -> {
            if (root) {
                statusLine.setText("");
                if (rootValue != null) rootValue.setText(getString(R.string.state_on));
                Ui.rootPanel(this, msg -> statusLine.setText(msg));
            } else {
                statusLine.setText(R.string.root_unavailable);
            }
        }));
    }
}
