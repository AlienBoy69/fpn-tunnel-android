package com.fpn.tunnel;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Account management for Settings (4.4.0). The main screen's account
 * pill keeps its own quick-switch sheet in MainActivity — this is the
 * same store (FpnSession) driven from Settings, with service-level
 * restart semantics: switching or removing the ACTIVE account stops
 * the tunnel service first and, if the user had it connected,
 * restarts it on the new active account. Session files are only ever
 * touched through FpnSession.
 */
public final class AccountsUi {
    public static final int RC_SIGNIN = 7;

    private AccountsUi() {}

    public static void show(final Activity a, final Runnable onChanged) {
        final java.util.List<FpnSession.Account> accs = FpnSession.listAccounts(a);
        if (accs.isEmpty()) {
            Intent si = new Intent(a, SignInActivity.class);
            si.putExtra("switch_account", FpnSession.exists(a));
            a.startActivityForResult(si, RC_SIGNIN);
            return;
        }
        final String active = FpnSession.activeUid(a);
        Ui.Sheet s = Ui.sheet(a, R.string.accounts_title);
        for (int i = 0; i < accs.size(); i++) {
            final FpnSession.Account acc = accs.get(i);
            final boolean isActive = acc.uid.equals(active);
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(Ui.dp(a, 60));
            row.setPadding(Ui.dp(a, 14), Ui.dp(a, 8), Ui.dp(a, 14), Ui.dp(a, 8));
            row.setBackgroundResource(R.drawable.row_bg);
            row.setOnClickListener(v -> {
                s.dismiss();
                if (!isActive) switchTo(a, acc.uid, onChanged);
            });
            TextView check = new TextView(a);
            check.setText(isActive ? "✓" : "");
            check.setTextColor(Ui.TEAL);
            check.setTextSize(16);
            row.addView(check, new LinearLayout.LayoutParams(
                    Ui.dp(a, 30), LinearLayout.LayoutParams.WRAP_CONTENT));
            TextView email = new TextView(a);
            email.setText(acc.email);
            email.setTextColor(Ui.HI);
            email.setTextSize(15);
            email.setSingleLine(true);
            email.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(email, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            if (isActive) {
                TextView chip = new TextView(a);
                chip.setText(R.string.chip_active);
                chip.setTextColor(Ui.TEAL);
                chip.setTextSize(10.5f);
                chip.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                chip.setBackgroundResource(R.drawable.chip_active);
                chip.setPadding(Ui.dp(a, 8), Ui.dp(a, 3), Ui.dp(a, 8), Ui.dp(a, 3));
                row.addView(chip);
            }
            s.body.addView(row);
            if (i < accs.size() - 1) s.body.addView(Ui.divider(a));
        }
        s.action(a.getString(R.string.add_account), 1, v -> {
            s.dismiss();
            Intent si = new Intent(a, SignInActivity.class);
            si.putExtra("switch_account", true);
            a.startActivityForResult(si, RC_SIGNIN);
        });
        s.action(a.getString(R.string.remove_dots), 2, v -> {
            s.dismiss();
            removeFlow(a, accs, onChanged);
        });
        s.action(a.getString(R.string.btn_close), 0, v -> s.dismiss());
        s.show();
    }

    private static void removeFlow(final Activity a,
                                   final java.util.List<FpnSession.Account> accs,
                                   final Runnable onChanged) {
        Ui.Sheet s = Ui.sheet(a, R.string.remove_which);
        for (final FpnSession.Account victim : accs) {
            s.action(victim.email, 0, v -> {
                s.dismiss();
                Ui.Sheet c = Ui.sheet(a, R.string.remove_title);
                TextView msg = new TextView(a);
                msg.setText(a.getString(R.string.remove_msg, victim.email));
                msg.setTextColor(Ui.MID);
                msg.setTextSize(14.5f);
                msg.setLineSpacing(0, 1.25f);
                c.body.addView(msg);
                c.action(a.getString(R.string.btn_remove), 2, vv -> {
                    c.dismiss();
                    boolean wasActive = victim.uid.equals(FpnSession.activeUid(a));
                    boolean restart = wasActive && wasConnected(a);
                    if (wasActive) stopTunnel(a);
                    FpnSession.removeAccount(a, victim.uid);
                    if (restart && FpnSession.exists(a)) restartTunnel(a);
                    if (onChanged != null) onChanged.run();
                });
                c.action(a.getString(R.string.btn_cancel), 0, vv -> c.dismiss());
                c.show();
            });
        }
        s.action(a.getString(R.string.btn_cancel), 0, v -> s.dismiss());
        s.show();
    }

    private static void switchTo(final Activity a, String uid,
                                 final Runnable onChanged) {
        boolean restart = wasConnected(a);
        stopTunnel(a);
        FpnSession.setActive(a, uid);
        if (restart) restartTunnel(a);
        if (onChanged != null) onChanged.run();
    }

    /** "Had it connected" = tunnel up/starting now, or the persisted
     *  wasConnected flag MainActivity maintains. */
    private static boolean wasConnected(Activity a) {
        SharedPreferences p = a.getSharedPreferences("fpn", Activity.MODE_PRIVATE);
        return FpnVpnService.uiState() != FpnVpnService.ST_OFF
                || p.getBoolean("wasConnected", false);
    }

    private static void stopTunnel(Activity a) {
        a.startService(new Intent(a, FpnVpnService.class).setAction("stop"));
    }

    private static void restartTunnel(final Activity a) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                Intent svc = new Intent(a, FpnVpnService.class);
                if (android.os.Build.VERSION.SDK_INT >= 26) a.startForegroundService(svc);
                else a.startService(svc);
            } catch (Exception ignored) {}
        }, 900);
    }
}
