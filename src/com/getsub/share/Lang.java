package com.getsub.share;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

/**
 * v2.6: subtitle language catalogue + last-used persistence.
 *
 * CODES are YouTube caption language codes (what SubtitleFetcher.findTrack
 * matches exactly); LABELS are human-readable names. The fetcher accepts any
 * code and lists the codes a video actually has when the requested one is
 * missing, so this catalogue can grow freely without touching the engine.
 */
public final class Lang {

    public static final String[] CODES = {
            "en", "es", "de", "fr", "it", "pt", "pt-BR", "ru", "uk", "pl",
            "nl", "tr", "ar", "hi", "ur", "fa", "id", "ms", "vi", "th",
            "ja", "ko", "zh-Hans", "zh-Hant", "sv", "da", "fi", "no",
            "cs", "el", "he", "ro", "hu", "ta", "te", "bn"
    };

    public static final String[] LABELS = {
            "English", "Spanish", "German", "French", "Italian", "Portuguese",
            "Portuguese (BR)", "Russian", "Ukrainian", "Polish", "Dutch",
            "Turkish", "Arabic", "Hindi", "Urdu", "Persian", "Indonesian",
            "Malay", "Vietnamese", "Thai", "Japanese", "Korean",
            "Chinese (Simplified)", "Chinese (Traditional)", "Swedish",
            "Danish", "Finnish", "Norwegian", "Czech", "Greek", "Hebrew",
            "Romanian", "Hungarian", "Tamil", "Telugu", "Bengali"
    };

    private static final String PREFS = "getsub_prefs";
    private static final String KEY = "lang_index";

    private Lang() {
    }

    /** "en — English" style entries for spinners. */
    public static String[] displayLabels() {
        String[] out = new String[CODES.length];
        for (int i = 0; i < CODES.length; i++) {
            out[i] = CODES[i] + " \u2014 " + LABELS[i];
        }
        return out;
    }

    public static String codeAt(int index) {
        if (index < 0 || index >= CODES.length) {
            return "en";
        }
        return CODES[index];
    }

    public static int loadIndex(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int i = p.getInt(KEY, 0);
        return (i >= 0 && i < CODES.length) ? i : 0;
    }

    public static void saveIndex(Context ctx, int index) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY, index).apply();
    }

    /** Dark-themed adapter for the app's own UI (white text on dark surface). */
    public static ArrayAdapter<String> darkAdapter(final Context ctx) {
        return new ArrayAdapter<String>(ctx, android.R.layout.simple_spinner_item, displayLabels()) {
            {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, convertView, parent);
                tv.setTextColor(Color.WHITE);
                tv.setTextSize(13);
                return tv;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                TextView tv = (TextView) super.getDropDownView(position, convertView, parent);
                tv.setTextColor(0xFF111111);
                tv.setTextSize(15);
                return tv;
            }
        };
    }

    /** Plain adapter for the light system dialog (share-flow picker). */
    public static ArrayAdapter<String> plainAdapter(Context ctx) {
        ArrayAdapter<String> a = new ArrayAdapter<String>(
                ctx, android.R.layout.simple_spinner_item, displayLabels());
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return a;
    }
}