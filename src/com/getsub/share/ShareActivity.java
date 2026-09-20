package com.getsub.share;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

public class ShareActivity extends Activity {

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
        sharedText = sharedText.trim();

        int jobId = JobStore.addJob(this, sharedText);

        Intent serviceIntent = new Intent(this, SubtitleDownloadService.class);
        serviceIntent.putExtra(SubtitleDownloadService.EXTRA_JOB_ID, jobId);
        serviceIntent.putExtra(SubtitleDownloadService.EXTRA_URL, sharedText);
        serviceIntent.putExtra(SubtitleDownloadService.EXTRA_LANG, "en");
        startForegroundService(serviceIntent);

        Toast.makeText(this, "GetSub: fetching subtitles...", Toast.LENGTH_SHORT).show();
        finish();
    }
}
