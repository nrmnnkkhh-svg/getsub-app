package com.getsub.share;

import android.content.Context;
import android.content.SharedPreferences;

/** Tiny preference store (v2.6): remembers the last subtitle language used. */
public class Prefs {

    private static final String FILE = "getsub_prefs";
    private static final String KEY_LANG = "lang";
    private static final String DEFAULT_LANG = "en";

    public static String getLang(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        return p.getString(KEY_LANG, DEFAULT_LANG);
    }

    public static void setLang(Context ctx, String lang) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putString(KEY_LANG, lang).apply();
    }
}
