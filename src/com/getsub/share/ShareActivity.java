package com.getsub.share;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Share-sheet entry point. v2.6: instead of firing immediately with a
 * hardcoded language, shows a tiny language picker (the activity itself is
 * dialog-themed in the manifest) and starts the download in the chosen
 * language. The hands-free flow now costs exactly one tap.
 */
public class ShareActivity extends Activity {

    private static final String[][] LANGS = {
            {"en", "English"}, {"es", "Spanish"}, {"de", "German"}, {"fr", "French"},
            {"pt", "Portuguese"}, {"ru", "Russian"}, {"ja", "Japanese"}, {"ko", "Korean"},
            {"hi", "Hindi"}, {"ar", "Arabic"}, {"id", "Indonesian"}, {"tr", "Turkish"},
            {"it", "Italian"}, {"nl", "Dutch"}, {"pl", "Polish"}, {"sv", "Swedish"},
    };

    private String sharedText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent in = getIntent();
        if (in != null && Intent.ACTION_SEND.equals(in.getAction())) {
            sharedText = in.getStringExtra(Intent.EXTRA_TEXT);
        }
        if (sharedText == null || sharedText.trim().length() == 0) {
            Toast.makeText(this, "GetSub: no link found in share", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        sharedText = sharedText.trim();

        String defaultLang = Prefs.getLang(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 12);

        TextView title = new TextView(this);
        title.setText("Get subtitles in:");
        title.setTextSize(16);
        root.addView(title);

        final List<String> codes = new ArrayList<String>();
        List<String> labels = new ArrayList<String>();
        if (!knownCode(defaultLang)) {
            codes.add(defaultLang);
            labels.add(defaultLang + "  (default)");
        }
        for (String[] pair : LANGS) {
            codes.add(pair[0]);
            labels.add(pair[0] + "  " + pair[1]
                    + (pair[0].equals(defaultLang) ? "  (default)" : ""));
        }

        ListView list = new ListView(this);
        list.setAdapter(new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, labels));
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                startJob(codes.get(pos));
            }
        });
        root.addView(list);

        setContentView(root);
    }

    private static boolean knownCode(String code) {
        for (String[] pair : LANGS) {
            if (pair[0].equals(code)) {
                return true;
            }
        }
        return false;
    }

    private void startJob(String lang) {
        int jobId = JobStore.addJob(this, sharedText, lang);
        Intent si = new Intent(this, SubtitleDownloadService.class);
        si.putExtra(SubtitleDownloadService.EXTRA_JOB_ID, jobId);
        si.putExtra(SubtitleDownloadService.EXTRA_URL, sharedText);
        si.putExtra(SubtitleDownloadService.EXTRA_LANG, lang);
        startForegroundService(si);
        Toast.makeText(this, "GetSub: fetching " + lang + " subtitles...",
                Toast.LENGTH_SHORT).show();
        finish();
    }
}
