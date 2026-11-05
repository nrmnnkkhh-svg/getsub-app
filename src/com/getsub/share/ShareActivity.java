package com.getsub.share;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Share-sheet entry point.
 *
 * v2.6: shows a small language picker before fetching (the share intent
 * carries only the link, so the language must be chosen here; v1.2-v2.5
 * silently used "en"). Shown from this otherwise invisible activity
 * (Theme.Translucent.NoTitleBar), so the share sheet still feels instant.
 */
public class ShareActivity extends Activity {

    private boolean started = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String sharedText = null;
        Intent in = getIntent();
        if (in != null && Intent.ACTION_SEND.equals(in.getAction())) {
            sharedText = in.getStringExtra(Intent.EXTRA_TEXT);
        }

        if (sharedText == null || sharedText.trim().length() == 0) {
            Toast.makeText(this, "GetSub: no link found in share", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        final String text = sharedText.trim();

        final Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = Lang.plainAdapter(this);
        spinner.setAdapter(adapter);
        spinner.setSelection(Lang.loadIndex(this));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(16), dp(24), dp(0));

        TextView hint = new TextView(this);
        hint.setText("Subtitle language");
        hint.setTextSize(13);
        box.addView(hint, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        box.addView(spinner, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("GetSub - fetch subtitles?")
                .setView(box)
                .setPositiveButton("Fetch", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        int idx = spinner.getSelectedItemPosition();
                        Lang.saveIndex(ShareActivity.this, idx);
                        startJob(text, Lang.codeAt(idx));
                        finish();
                    }
                })
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        finish();
                    }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface d) {
                        finish();
                    }
                });
        b.show();
    }

    /** Log the job and hand off to the download service (pre-v2.6 flow). */
    private void startJob(String sharedText, String lang) {
        if (started) {
            return;
        }
        started = true;
        int jobId = JobStore.addJob(this, sharedText);
        Intent serviceIntent = new Intent(this, SubtitleDownloadService.class);
        serviceIntent.putExtra(SubtitleDownloadService.EXTRA_JOB_ID, jobId);
        serviceIntent.putExtra(SubtitleDownloadService.EXTRA_URL, sharedText);
        serviceIntent.putExtra(SubtitleDownloadService.EXTRA_LANG, lang);
        startForegroundService(serviceIntent);
        Toast.makeText(this, "GetSub: fetching " + lang + " subtitles...",
                Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}