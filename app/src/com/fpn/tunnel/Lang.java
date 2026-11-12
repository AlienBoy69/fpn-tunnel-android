package com.fpn.tunnel;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;

import java.util.Locale;

/**
 * Bilingual UI (English / Persian). The user's choice lives in the
 * "fpn" prefs ("lang" = "fa" | "en"); with no choice stored, the
 * first-run default follows the device locale (fa → Persian, anything
 * else → English). Activities call Lang.wrap from attachBaseContext,
 * so every getString / layout inflation below them resolves in the
 * chosen language AND the matching layout direction (fa = RTL).
 */
public final class Lang {
    public static final String FA = "fa";
    public static final String EN = "en";

    private Lang() {}

    public static String stored(Context c) {
        try {
            return c.getSharedPreferences("fpn", Context.MODE_PRIVATE)
                    .getString("lang", "");
        } catch (Exception e) {
            return "";
        }
    }

    /** The language the UI must render in right now: "fa" or "en". */
    public static String resolved(Context c) {
        String s = stored(c);
        if (FA.equals(s) || EN.equals(s)) return s;
        String sys;
        if (Build.VERSION.SDK_INT >= 24)
            sys = Resources.getSystem().getConfiguration().getLocales().get(0).getLanguage();
        else
            sys = Resources.getSystem().getConfiguration().locale.getLanguage();
        return FA.equals(sys) ? FA : EN;
    }

    public static boolean isFa(Context c) { return FA.equals(resolved(c)); }

    public static void set(Context c, String lang) {
        c.getSharedPreferences("fpn", Context.MODE_PRIVATE)
                .edit().putString("lang", lang).apply();
    }

    /** A context whose resources + layout direction match the choice. */
    public static Context wrap(Context base) {
        String lang = resolved(base);
        Locale loc = new Locale(lang);
        Locale.setDefault(loc);
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        cfg.setLocale(loc);
        cfg.setLayoutDirection(loc);
        return base.createConfigurationContext(cfg);
    }
}
