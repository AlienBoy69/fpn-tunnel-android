package com.fpn.tunnel;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Split tunneling: the user's own "apps outside the VPN" list.
 * The set lives in the device-level "fpn" prefs — it is a tunnel
 * property, NOT a per-account one (accounts share the same tunnel).
 *
 * 4.5.1: there are NO built-in bypasses any more. 4.5.0 force-
 * excluded Termux, this app, the Muse app and Sina Bank and showed
 * them pinned and locked; on the owner's order every one of them is
 * now an ordinary, user-controlled row. So an upgrade changes no
 * routing by itself, the old effective state (the user's picks ∪
 * the old built-ins that are installed) is seeded into the user set
 * exactly once — the defaults become pre-checked suggestions, and
 * from then on the stored set is the whole truth.
 *
 * The one structural exception is invisible here on purpose: the
 * service always keeps this app itself out of its own tunnel
 * (FpnVpnService.SELF — its engine sockets are unprotected, so its
 * own traffic would loop). This package's row below toggles the
 * stored set like any other; the service-side exclusion stands
 * regardless, for the reason documented there.
 */
public final class SplitTunnel {
    static final String PREF_KEY = "splitExcludedApps";
    private static final String SEED_KEY = "splitSeeded451";

    /** What 4.5.0 force-excluded; used once as the migration seed. */
    private static final String[] LEGACY_DEFAULTS = {
            "com.termux", "com.fpn.tunnel", "com.facebook.aura", "com.tosan.dara.sina" };

    /** In-memory icon cache only — icons are never written to disk. */
    private static final Map<String, Drawable> ICON_CACHE = new HashMap<>();

    private SplitTunnel() {}

    /** One-time 4.5.1 migration: seed the user set from the previous
     *  effective exclusion state (stored picks ∪ installed legacy
     *  defaults), so nobody's routing flips until they edit the list
     *  themselves. Guarded by SEED_KEY; runs inside userExcluded so
     *  every reader (service, picker, settings count) sees it done. */
    static void migrateSeed(Context c) {
        SharedPreferences p = c.getSharedPreferences("fpn", Context.MODE_PRIVATE);
        if (p.getBoolean(SEED_KEY, false)) return;
        Set<String> seed = new HashSet<>();
        Set<String> raw = p.getStringSet(PREF_KEY, null);
        if (raw != null) seed.addAll(raw);
        PackageManager pm = c.getPackageManager();
        for (String pkg : LEGACY_DEFAULTS) {
            try {
                pm.getApplicationInfo(pkg, 0);
                seed.add(pkg);
            } catch (PackageManager.NameNotFoundException ignored) {
                // Not installed — nothing to seed.
            }
        }
        p.edit().putStringSet(PREF_KEY, seed).putBoolean(SEED_KEY, true).apply();
    }

    /** The user's excluded packages (a mutable copy of the store;
     *  since 4.5.1 the store is the whole truth — nothing is added
     *  or filtered anywhere). */
    public static Set<String> userExcluded(Context c) {
        migrateSeed(c);
        Set<String> raw = c.getSharedPreferences("fpn", Context.MODE_PRIVATE)
                .getStringSet(PREF_KEY, null);
        Set<String> out = new HashSet<>();
        if (raw != null) out.addAll(raw);
        return out;
    }

    static void setUserExcluded(Context c, Set<String> set) {
        c.getSharedPreferences("fpn", Context.MODE_PRIVATE).edit()
                .putStringSet(PREF_KEY, new HashSet<>(set)).apply();
    }

    // ── picker sheet ─────────────────────────────────────────────

    private static final class Entry {
        final String pkg;
        final String label;
        final Drawable icon;
        Entry(String pkg, String label, Drawable icon) {
            this.pkg = pkg; this.label = label; this.icon = icon;
        }
    }

    public static void show(final Activity a,
                            final java.util.function.Consumer<String> status,
                            final Runnable onClose) {
        final Ui.Sheet s = Ui.sheet(a, R.string.split_title);
        TextView hint = new TextView(a);
        hint.setText(R.string.split_hint);
        hint.setTextColor(Ui.MID);
        hint.setTextSize(13.5f);
        hint.setLineSpacing(0, 1.3f);
        s.body.addView(hint);

        final EditText search = new EditText(a);
        search.setHint(R.string.split_search_hint);
        search.setSingleLine(true);
        Ui.styleField(a, search);
        LinearLayout.LayoutParams shp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        shp.topMargin = Ui.dp(a, 12);
        s.body.addView(search, shp);

        final TextView loading = new TextView(a);
        loading.setText(R.string.split_loading);
        loading.setTextColor(Ui.DIM);
        loading.setTextSize(14);
        LinearLayout.LayoutParams lop = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lop.topMargin = Ui.dp(a, 14);
        s.body.addView(loading, lop);

        final LinearLayout listBox = new LinearLayout(a);
        listBox.setOrientation(LinearLayout.VERTICAL);
        ScrollView sc = new ScrollView(a);
        sc.addView(listBox);
        s.body.addView(sc, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(a, 360)));

        final Set<String> initial = userExcluded(a);
        final Set<String> working = new HashSet<>(initial);
        final List<Entry> apps = new ArrayList<>();
        final boolean[] loaded = { false };

        final Runnable rebuild = () -> {
            if (!loaded[0]) return;
            String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            listBox.removeAllViews();
            boolean any = false;
            for (final Entry e : apps) {
                if (!matches(e, q)) continue;
                any = true;
                listBox.addView(appRow(a, e, working,
                        () -> setUserExcluded(a, working)));
            }
            if (!any) {
                TextView none = new TextView(a);
                none.setText(R.string.split_none);
                none.setTextColor(Ui.DIM);
                none.setTextSize(14);
                none.setPadding(0, Ui.dp(a, 14), 0, 0);
                listBox.addView(none);
            }
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s2, int i, int j, int k) {}
            @Override public void onTextChanged(CharSequence s2, int i, int j, int k) { rebuild.run(); }
            @Override public void afterTextChanged(Editable e) {}
        });

        // Lazy load on open: installed-app enumeration + icon decode
        // can take a moment on a full phone — keep it off the UI thread.
        new Thread(() -> {
            PackageManager pm = a.getPackageManager();
            List<ApplicationInfo> all = pm.getInstalledApplications(0);
            List<Entry> found = new ArrayList<>();
            for (ApplicationInfo ai : all) {
                boolean userApp = (ai.flags & ApplicationInfo.FLAG_SYSTEM) == 0
                        || (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
                if (!userApp) continue;
                // 4.5.1: no exclusions from the list either — the
                // former built-ins (Termux, Muse, Sina Bank, this
                // app) are ordinary rows, toggled like everything else.
                found.add(new Entry(ai.packageName,
                        pm.getApplicationLabel(ai).toString(), icon(pm, ai)));
            }
            final Collator col = Collator.getInstance(
                    new Locale(Lang.resolved(a)));
            found.sort((x, y) -> col.compare(x.label, y.label));
            apps.addAll(found);
            loaded[0] = true;
            new Handler(Looper.getMainLooper()).post(() -> {
                loading.setVisibility(View.GONE);
                rebuild.run();
            });
        }, "fpn-applist").start();

        s.action(a.getString(R.string.btn_done), 1, v -> s.dismiss());
        s.dlg.setOnDismissListener(d -> {
            if (!working.equals(initial)) applyIfNeeded(a, status);
            if (onClose != null) onClose.run();
        });
        s.show();
    }

    private static boolean matches(Entry e, String q) {
        return q.isEmpty()
                || e.label.toLowerCase(Locale.ROOT).contains(q)
                || e.pkg.toLowerCase(Locale.ROOT).contains(q);
    }

    private static Drawable icon(PackageManager pm, ApplicationInfo ai) {
        Drawable d = ICON_CACHE.get(ai.packageName);
        if (d == null) {
            try { d = pm.getApplicationIcon(ai); } catch (Exception ignored) {}
            if (d != null) ICON_CACHE.put(ai.packageName, d);
        }
        return d;
    }

    /** One picker row: icon + label + checkbox. Every row toggles. */
    private static LinearLayout appRow(final Activity a, final Entry e,
                                       final Set<String> working,
                                       final Runnable onPersist) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Ui.dp(a, 58));
        row.setPadding(Ui.dp(a, 12), Ui.dp(a, 6), Ui.dp(a, 12), Ui.dp(a, 6));
        row.setBackgroundResource(R.drawable.row_bg);
        ImageView iv = new ImageView(a);
        if (e.icon != null) iv.setImageDrawable(e.icon);
        row.addView(iv, new LinearLayout.LayoutParams(
                Ui.dp(a, 40), Ui.dp(a, 40)));
        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams txp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        txp.setMarginStart(Ui.dp(a, 12));
        TextView lab = new TextView(a);
        lab.setText(e.label);
        lab.setTextColor(Ui.HI);
        lab.setTextSize(15);
        lab.setSingleLine(true);
        lab.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(lab);
        row.addView(texts, txp);
        final CheckBox cb = new CheckBox(a);
        cb.setChecked(working.contains(e.pkg));
        cb.setOnCheckedChangeListener((v, on) -> {
            if (on) working.add(e.pkg); else working.remove(e.pkg);
            if (onPersist != null) onPersist.run();
        });
        row.setOnClickListener(v -> cb.toggle());
        row.addView(cb);
        return row;
    }

    // ── apply semantics ──────────────────────────────────────────

    /** If the tunnel is up (or was left connected), restart it so
     *  the new exclusion set takes effect immediately; when it is
     *  down the set simply applies on the next connect. Same
     *  stop → 900 ms → start pattern the account switch uses. */
    private static void applyIfNeeded(final Activity a,
                                      final java.util.function.Consumer<String> status) {
        SharedPreferences p = a.getSharedPreferences("fpn", Activity.MODE_PRIVATE);
        boolean up = FpnVpnService.uiState() != FpnVpnService.ST_OFF
                || p.getBoolean("wasConnected", false);
        if (!up) return;
        String msg = a.getString(R.string.split_restarting);
        if (status != null) status.accept(msg);
        Toast.makeText(a, msg, Toast.LENGTH_SHORT).show();
        a.startService(new Intent(a, FpnVpnService.class).setAction("stop"));
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                Intent svc = new Intent(a, FpnVpnService.class);
                if (android.os.Build.VERSION.SDK_INT >= 26) a.startForegroundService(svc);
                else a.startService(svc);
            } catch (Exception ignored) {}
        }, 900);
    }
}
