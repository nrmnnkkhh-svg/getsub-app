package com.getsub.share;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.IBinder;
import android.provider.MediaStore;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicInteger;

public class SubtitleDownloadService extends Service {

    public static final String EXTRA_JOB_ID = "job_id";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_LANG = "lang";

    private static final String CHANNEL_ID = "getsub_downloads";
    private static final int ONGOING_NOTIF_ID = 1001;
    // v2.5.3: result notifications get a PER-JOB id (base + jobId) so two
    // downloads finishing close together don't overwrite each other's result.
    private static final int RESULT_NOTIF_BASE = 2000;

    // v2.5.3: concurrent-job bookkeeping. Foreground state must survive until
    // the LAST active job finishes; tearing it down on the first completion
    // stripped process protection from still-running downloads (kill risk →
    // job stuck at "Queued" forever).
    private final AtomicInteger activeJobs = new AtomicInteger(0);
    private int latestStartId = 0;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // v2.5.3: defensive guard — a null intent would NPE here. With
        // START_NOT_STICKY no real redelivery is expected, so just stop.
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        final int jobId = intent.getIntExtra(EXTRA_JOB_ID, -1);
        final String sharedText = intent.getStringExtra(EXTRA_URL);
        String langExtra = intent.getStringExtra(EXTRA_LANG);
        final String lang = (langExtra == null || langExtra.trim().isEmpty()) ? "en" : langExtra;

        ensureChannel();

        int active;
        synchronized (this) {
            latestStartId = startId;
            active = activeJobs.incrementAndGet();
        }
        startForeground(ONGOING_NOTIF_ID,
                buildNotification(active > 1
                        ? "Fetching subtitles... (" + active + " in parallel)"
                        : "Fetching subtitles...", true),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);

        new Thread(new Runnable() {
            @Override
            public void run() {
                runJob(jobId, sharedText, lang);
            }
        }).start();

        return START_NOT_STICKY;
    }

    private void runJob(int jobId, String sharedText, String lang) {
        String resultText;
        boolean success;
        try {
            try {
                SubtitleFetcher.Result result = SubtitleFetcher.fetch(sharedText, lang);
                saveToDownloads(result.filename, result.text);
                JobStore.updateJob(this, jobId, JobStore.STATUS_DONE, "Saved: " + result.filename);
                resultText = "Saved: " + result.filename;
                success = true;
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                JobStore.updateJob(this, jobId, JobStore.STATUS_ERROR, msg);
                resultText = msg;
                success = false;
            }

            // Post the result under a per-job id, separate from the ongoing
            // foreground id (v2.2.1: reusing the just-removed foreground id can
            // leave a stale state visible on some devices; v2.5.3: one shared
            // result id also made concurrent results overwrite each other).
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                Notification finalNotif = buildNotification(
                        (success ? "Done: " : "Error: ") + resultText, false);
                nm.notify(RESULT_NOTIF_BASE + Math.max(jobId, 0), finalNotif);
            }
        } finally {
            // v2.5.3: only the LAST finished job tears down foreground state.
            // stopSelf(latestStartId) is race-safe: if a new job started in the
            // meantime, its larger startId makes the system ignore this stop.
            boolean last;
            int stopId;
            synchronized (this) {
                last = activeJobs.decrementAndGet() <= 0;
                stopId = latestStartId;
            }
            if (last) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE);
                stopSelf(stopId);
            }
        }
    }

    private void saveToDownloads(String filename, String content) throws IOException {
        ContentResolver resolver = getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
        values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
        values.put(MediaStore.Downloads.IS_PENDING, 1);

        Uri item = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (item == null) {
            throw new IOException("Could not create file in Downloads");
        }

        OutputStream out = resolver.openOutputStream(item);
        try {
            if (out == null) {
                throw new IOException("Could not open output stream for new file");
            }
            out.write(content.getBytes("UTF-8"));
        } finally {
            if (out != null) {
                out.close();
            }
        }

        values.clear();
        values.put(MediaStore.Downloads.IS_PENDING, 0);
        resolver.update(item, values, null, null);
    }

    private void ensureChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "GetSub downloads", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Subtitle fetch progress and results");
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text, boolean ongoing) {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("GetSub")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setContentIntent(pi);
        if (!ongoing) {
            // Result notifications must never hang: tapping dismisses
            // (autoCancel above) and the system removes them after 20s.
            builder.setTimeoutAfter(20000);
        }
        return builder.build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
