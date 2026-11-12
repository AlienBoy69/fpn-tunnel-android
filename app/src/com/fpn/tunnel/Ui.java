package com.fpn.tunnel;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The 4.4.0 design kit: one visual language for every screen and
 * dialog — dark-navy cards, teal accents, 54dp targets, start-aligned
 * rows — built from framework widgets only (no Material dependency).
 * Sheets replace the stock white AlertDialogs, which clashed with
 * the app's identity and ignored its language/direction.
 */
public final class Ui {
    public static final int HI = Color.parseColor("#EAF1FF");
    public static final int MID = Color.parseColor("#B9C8E8");
    public static final int DIM = Color.parseColor("#8FA3CC");
    public static final int FAINT = Color.parseColor("#5F7396");
    public static final int TEAL = Color.parseColor("#2DD4BF");
    public static final int GREEN = Color.parseColor("#34D399");
    public static final int AMBER = Color.parseColor("#FFB020");
    public static final int RED = Color.parseColor("#F87171");
    public static final int ROWTEXT = Color.parseColor("#DCE7FA");

    private Ui() {}

    public static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static LinearLayout.LayoutParams lp(int w, int h, int topDp, Context c) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        if (topDp > 0) p.topMargin = dp(c, topDp);
        return p;
    }

    // ── building blocks ─────────────────────────────────────────────

    /** Rounded navy card (vertical), ready to fill. */
    public static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackgroundResource(R.drawable.card_bg);
        l.setElevation(dp(c, 6));
        return l;
    }

    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(Color.parseColor("#22355E"));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 1)));
        p.setMarginStart(dp(c, 18));
        p.setMarginEnd(dp(c, 18));
        v.setLayoutParams(p);
        return v;
    }

    public static TextView sectionLabel(Context c, int res) {
        TextView t = new TextView(c);
        t.setText(res);
        t.setTextColor(DIM);
        t.setTextSize(12);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.16f);
        return t;
    }

    /** A settings-style row: label at start, value at end, chevron. */
    public static LinearLayout row(Context c, String label, TextView valueOut,
                                   View.OnClickListener click) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(c, 56));
        r.setPadding(dp(c, 18), dp(c, 8), dp(c, 18), dp(c, 8));
        r.setBackgroundResource(R.drawable.row_bg);
        TextView lab = new TextView(c);
        lab.setText(label);
        lab.setTextColor(ROWTEXT);
        lab.setTextSize(15);
        r.addView(lab, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (valueOut != null) {
            valueOut.setTextColor(DIM);
            valueOut.setTextSize(13.5f);
            r.addView(valueOut);
        }
        TextView chev = new TextView(c);
        chev.setText("›");
        chev.setTextColor(FAINT);
        chev.setTextSize(20);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cp.setMarginStart(dp(c, 8));
        r.addView(chev, cp);
        if (click != null) r.setOnClickListener(click);
        return r;
    }

    public static Button primaryButton(Context c, int res) {
        Button b = new Button(c);
        b.setText(res);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setAllCaps(false);
        b.setBackgroundResource(R.drawable.pill_btn);
        b.setMinHeight(0);
        return b;
    }

    public static Button ghostButton(Context c, int res) {
        Button b = new Button(c);
        b.setText(res);
        b.setTextColor(MID);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setBackgroundResource(R.drawable.btn_ghost);
        b.setMinHeight(0);
        return b;
    }

    public static TextView fieldLabel(Context c, int res) {
        TextView t = new TextView(c);
        t.setText(res);
        t.setTextColor(DIM);
        t.setTextSize(12.5f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** Dark-themed text field (the stock one rendered black-on-navy). */
    public static void styleField(Context c, EditText e) {
        e.setBackgroundResource(R.drawable.field_bg);
        e.setTextColor(HI);
        e.setHintTextColor(FAINT);
        e.setTextSize(16);
        int px = dp(c, 14);
        e.setPadding(px, 0, px, 0);
        e.setMinHeight(dp(c, 54));
    }

    /** "‹ Back" affordance for the secondary screens (no title bars). */
    public static TextView backRow(final Activity a) {
        TextView t = new TextView(a);
        t.setText("‹  " + a.getString(R.string.btn_back));
        t.setTextColor(TEAL);
        t.setTextSize(15);
        t.setPadding(0, dp(a, 6), dp(a, 12), dp(a, 6));
        t.setOnClickListener(v -> a.finish());
        return t;
    }

    // ── sheets (the app's dialogs) ──────────────────────────────────

    public static final class Sheet {
        public final Activity act;
        public final Dialog dlg;
        public final LinearLayout body;
        private final LinearLayout actions;
        private boolean firstAction = true;

        private Sheet(Activity a, String title) {
            act = a;
            dlg = new Dialog(a);
            dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
            LinearLayout root = new LinearLayout(a);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundResource(R.drawable.dialog_bg);
            root.setPadding(dp(a, 22), dp(a, 20), dp(a, 22), dp(a, 12));
            TextView t = new TextView(a);
            t.setText(title);
            t.setTextColor(HI);
            t.setTextSize(18);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            root.addView(t);
            body = new LinearLayout(a);
            body.setOrientation(LinearLayout.VERTICAL);
            root.addView(body, lp(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 10, a));
            actions = new LinearLayout(a);
            actions.setOrientation(LinearLayout.VERTICAL);
            root.addView(actions, lp(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 6, a));
            dlg.setContentView(root);
            if (dlg.getWindow() != null)
                dlg.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        /** A full-width action row; role: 0 normal, 1 accent, 2 danger. */
        public Sheet action(String label, int role, View.OnClickListener l) {
            if (firstAction) {
                View d = new View(act);
                d.setBackgroundColor(Color.parseColor("#22355E"));
                actions.addView(d, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(act, 1))));
                firstAction = false;
            }
            TextView b = new TextView(act);
            b.setText(label);
            b.setTextSize(15.5f);
            b.setTextColor(role == 1 ? TEAL : role == 2 ? RED : HI);
            if (role == 1) b.setTypeface(Typeface.DEFAULT_BOLD);
            b.setGravity(Gravity.CENTER_VERTICAL);
            b.setMinimumHeight(dp(act, 50));
            b.setPadding(dp(act, 2), 0, dp(act, 2), 0);
            b.setBackgroundResource(R.drawable.row_bg);
            b.setOnClickListener(l);
            actions.addView(b);
            return this;
        }

        public void show() {
            dlg.show();
            if (dlg.getWindow() != null) {
                int w = (int) (act.getResources().getDisplayMetrics().widthPixels * 0.88);
                dlg.getWindow().setLayout(w, LinearLayout.LayoutParams.WRAP_CONTENT);
            }
        }

        public void dismiss() { dlg.dismiss(); }
    }

    public static Sheet sheet(Activity a, int titleRes) {
        return new Sheet(a, a.getString(titleRes));
    }

    // ── doctor report sheet ─────────────────────────────────────────

    /**
     * The Doctor's verdict as a product surface, not a log dump: a
     * colored verdict line + date, then the report body (checks and
     * repairs) in a bounded scroll area. Verdict comes from the prefs
     * the doctor now stamps (doctorFixed/doctorCode), with a text
     * fallback for reports written by older builds.
     */
    public static void doctorSheet(final Activity a, File report,
                                   final Runnable onDismiss) {
        String text = "";
        try {
            FileInputStream in = new FileInputStream(report);
            byte[] buf = new byte[in.available()];
            int n = in.read(buf);
            in.close();
            if (n > 0) text = new String(buf, 0, n, "UTF-8");
        } catch (Exception ignored) {}
        SharedPreferences p = a.getSharedPreferences("fpn", Context.MODE_PRIVATE);
        boolean fixed = p.getBoolean("doctorFixed", text.contains("CONNECTED"));
        String code = p.getString("doctorCode", "");
        Sheet s = sheet(a, R.string.doctor_title);
        TextView verdict = new TextView(a);
        verdict.setText(fixed ? a.getString(R.string.verdict_connected)
                : a.getString(R.string.verdict_failed)
                        + (code.isEmpty() ? "" : "  ·  " + code));
        verdict.setTextColor(fixed ? GREEN : RED);
        verdict.setTextSize(17);
        verdict.setTypeface(Typeface.DEFAULT_BOLD);
        s.body.addView(verdict);
        TextView when = new TextView(a);
        when.setText(new SimpleDateFormat("yyyy-MM-dd  HH:mm", Locale.US)
                .format(new Date(report.lastModified())));
        when.setTextColor(FAINT);
        when.setTextSize(12);
        s.body.addView(when, lp(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 4, a));
        // Body = the report minus its own header (title, date, Result
        // line) — the sheet already presents those properly.
        String body = stripReportHeader(text);
        if (!body.isEmpty()) {
            TextView tv = new TextView(a);
            tv.setText(body);
            tv.setTextColor(MID);
            tv.setTextSize(13.5f);
            tv.setLineSpacing(0, 1.3f);
            ScrollView sc = new ScrollView(a);
            sc.addView(tv);
            s.body.addView(sc, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(a, 300)));
            LinearLayout.LayoutParams bp = (LinearLayout.LayoutParams) sc.getLayoutParams();
            bp.topMargin = dp(a, 12);
        }
        s.action(a.getString(R.string.btn_ok), 1, v -> s.dismiss());
        s.dlg.setOnDismissListener(d -> { if (onDismiss != null) onDismiss.run(); });
        s.show();
    }

    private static String stripReportHeader(String text) {
        String[] lines = text.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String ln = lines[i];
            if (i < 4 && (ln.startsWith("FPN Tunnel") || ln.startsWith("نتیجه")
                    || ln.startsWith("Result") || ln.matches("\\d{4}-\\d{2}-\\d{2}.*")
                    || ln.trim().isEmpty() && sb.length() == 0)) {
                if (ln.startsWith("Result") || ln.startsWith("نتیجه")) continue;
                if (i < 2) continue;
            }
            sb.append(ln).append("\n");
        }
        return sb.toString().trim();
    }

    // ── root panel sheet ────────────────────────────────────────────

    /** The Fix N root panel, shared by Main (gone in 4.4) + Settings. */
    public static void rootPanel(final Activity a,
                                 final java.util.function.Consumer<String> status) {
        Sheet s = sheet(a, R.string.root_title);
        s.body.addView(rootSwitch(a, a.getString(R.string.root_sw_update),
                RootMode.opt(a, "root_update"),
                on -> RootMode.setOpt(a, "root_update", on)));
        final android.widget.Switch swBatt = rootSwitch(a,
                a.getString(R.string.root_sw_battery),
                RootMode.opt(a, "root_battery"), null);
        swBatt.setOnCheckedChangeListener((v, on) -> {
            swBatt.setEnabled(false);
            RootMode.setBatteryWhitelist(a, on, ok -> a.runOnUiThread(() -> {
                swBatt.setEnabled(true);
                swBatt.setChecked(RootMode.opt(a, "root_battery"));
                if (!ok && status != null)
                    status.accept(a.getString(R.string.battery_fail));
            }));
        });
        s.body.addView(swBatt);
        s.body.addView(rootSwitch(a, a.getString(R.string.root_sw_leak),
                RootMode.opt(a, "root_leak"),
                on -> RootMode.setOpt(a, "root_leak", on)));
        android.widget.Switch swKill = rootSwitch(a,
                a.getString(R.string.root_sw_kill), false, null);
        // (label set above via root_sw_kill)
        swKill.setEnabled(false);
        s.body.addView(swKill);
        s.action(a.getString(R.string.btn_done), 1, v -> s.dismiss());
        s.show();
    }

    private static android.widget.Switch rootSwitch(Context c, String label,
            boolean on, final java.util.function.Consumer<Boolean> sink) {
        android.widget.Switch s = new android.widget.Switch(c);
        s.setText(label);
        s.setTextColor(HI);
        s.setTextSize(14);
        s.setPadding(0, dp(c, 12), 0, dp(c, 12));
        s.setChecked(on);
        if (sink != null)
            s.setOnCheckedChangeListener((v, checked) -> sink.accept(checked));
        return s;
    }
}
