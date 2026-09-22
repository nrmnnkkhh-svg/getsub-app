package com.getsub.share;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    // ── Palette ──────────────────────────────────────────────────────────────
    static final int C_BG      = 0xFF0F0F14;   // page background
    static final int C_SURFACE = 0xFF1A1A24;   // header / elevated surface
    static final int C_CARD    = 0xFF21212F;   // job card background
    static final int C_ACCENT  = 0xFF6C63FF;   // brand purple
    static final int C_SUCCESS = 0xFF4CAF50;   // Done  → green
    static final int C_ERROR   = 0xFFEF5350;   // Error → red
    static final int C_QUEUED  = 0xFFFF9800;   // Queued → amber
    static final int C_TEXT1   = 0xFFFFFFFF;   // primary text
    static final int C_TEXT2   = 0xFFA0A0B0;   // secondary / muted text
    static final int C_BORDER  = 0xFF2A2A3A;   // divider / hairline
    static final int C_INPUT   = 0xFF252535;   // text field background

    private ListView    listView;
    private ProgressBar topProgress;
    private JobAdapter  adapter;
    /** Signature of the job set currently rendered (v2.5.4, see loadJobs). */
    private String      renderedSig = null;

    private final Handler  handler = new Handler(Looper.getMainLooper());
    private final Runnable poller  = new Runnable() {
        @Override public void run() {
            loadJobs();
            handler.postDelayed(this, 1000);
        }
    };

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        // ── Root ─────────────────────────────────────────────────────────────
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);

        // ── Header (elevated surface) ─────────────────────────────────────────
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackgroundColor(C_SURFACE);
        header.setPadding(dp(20), dp(52), dp(20), dp(20));

        // Title row: accent dot  ●  GetSub
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        View dot = new View(this);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(C_ACCENT);
        dot.setBackground(dotBg);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(10), dp(10));
        dotLp.setMargins(0, 0, dp(10), 0);
        titleRow.addView(dot, dotLp);

        TextView appName = new TextView(this);
        appName.setText("GetSub");
        appName.setTextColor(C_TEXT1);
        appName.setTextSize(22);
        appName.setTypeface(null, Typeface.BOLD);
        titleRow.addView(appName);

        header.addView(titleRow);

        TextView tagline = new TextView(this);
        // Stage 3: CI stamps a versionName (ci-<run number>); show it so the
        // user always knows exactly which build is installed. Termux builds
        // have no versionName -> tagline stays as before.
        String tagText = "YouTube subtitle downloader";
        try {
            String vn = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            if (vn != null && !vn.isEmpty()) {
                tagText = tagText + "  \u00b7  " + vn;
            }
        } catch (Exception ignored) {
        }
        tagline.setText(tagText);
        tagline.setTextColor(C_TEXT2);
        tagline.setTextSize(13);
        LinearLayout.LayoutParams taglineLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        taglineLp.setMargins(dp(20), dp(2), 0, 0);
        header.addView(tagline, taglineLp);

        // ── Indeterminate progress bar (thin accent stripe, shown while fetching) ──
        topProgress = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        topProgress.setIndeterminate(true);
        topProgress.setProgressTintList(
                android.content.res.ColorStateList.valueOf(C_ACCENT));
        topProgress.setIndeterminateTintList(
                android.content.res.ColorStateList.valueOf(C_ACCENT));
        topProgress.setVisibility(View.GONE);
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(3));
        pbLp.setMargins(0, dp(16), 0, 0);
        header.addView(topProgress, pbLp);

        // ── URL input row ─────────────────────────────────────────────────────
        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams inputRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        inputRowLp.setMargins(0, dp(12), 0, 0);

        final EditText urlInput = new EditText(this);
        urlInput.setHint("Paste YouTube link...");
        urlInput.setHintTextColor(C_TEXT2);
        urlInput.setTextColor(C_TEXT1);
        urlInput.setTextSize(14);
        urlInput.setSingleLine(true);
        urlInput.setBackground(roundRect(C_INPUT, dp(12)));
        urlInput.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        editLp.setMargins(0, 0, dp(10), 0);

        Button fetchBtn = new Button(this);
        fetchBtn.setText("Get");
        fetchBtn.setTextColor(Color.WHITE);
        fetchBtn.setAllCaps(false);
        fetchBtn.setTypeface(null, Typeface.BOLD);
        fetchBtn.setTextSize(15);
        fetchBtn.setBackground(roundRect(C_ACCENT, dp(12)));
        fetchBtn.setPadding(dp(26), dp(14), dp(26), dp(14));
        fetchBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String text = urlInput.getText().toString().trim();
                if (text.isEmpty()) {
                    Toast.makeText(MainActivity.this,
                            "Paste a YouTube link first", Toast.LENGTH_SHORT).show();
                    return;
                }
                int jobId = JobStore.addJob(MainActivity.this, text);
                Intent si = new Intent(MainActivity.this, SubtitleDownloadService.class);
                si.putExtra(SubtitleDownloadService.EXTRA_JOB_ID, jobId);
                si.putExtra(SubtitleDownloadService.EXTRA_URL, text);
                si.putExtra(SubtitleDownloadService.EXTRA_LANG, "en");
                startForegroundService(si);
                urlInput.setText("");
                Toast.makeText(MainActivity.this,
                        "GetSub: fetching subtitles...", Toast.LENGTH_SHORT).show();
                loadJobs();
            }
        });

        inputRow.addView(urlInput, editLp);
        inputRow.addView(fetchBtn);
        header.addView(inputRow, inputRowLp);

        root.addView(header);

        // Thin hairline divider below header
        View divider = new View(this);
        divider.setBackgroundColor(C_BORDER);
        root.addView(divider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));

        // ── Section label + clear-history ─────────────────────────────────────
        LinearLayout sectionRow = new LinearLayout(this);
        sectionRow.setOrientation(LinearLayout.HORIZONTAL);
        sectionRow.setGravity(Gravity.CENTER_VERTICAL);
        sectionRow.setPadding(dp(20), dp(18), dp(20), dp(8));

        TextView sectionLabel = new TextView(this);
        sectionLabel.setText("RECENT DOWNLOADS");
        sectionLabel.setTextColor(C_TEXT2);
        sectionLabel.setTextSize(11);
        sectionLabel.setTypeface(null, Typeface.BOLD);
        sectionLabel.setLetterSpacing(0.15f);
        sectionRow.addView(sectionLabel, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView clearBtn = new TextView(this);
        clearBtn.setText("CLEAR");
        clearBtn.setTextColor(C_ERROR);
        clearBtn.setTextSize(11);
        clearBtn.setTypeface(null, Typeface.BOLD);
        clearBtn.setLetterSpacing(0.15f);
        clearBtn.setPadding(dp(12), dp(6), dp(4), dp(6));
        clearBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmClearHistory();
            }
        });
        sectionRow.addView(clearBtn);
        root.addView(sectionRow);

        // ── Empty state ───────────────────────────────────────────────────────
        LinearLayout emptyView = new LinearLayout(this);
        emptyView.setId(android.R.id.empty);
        emptyView.setOrientation(LinearLayout.VERTICAL);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(dp(40), dp(60), dp(40), dp(40));

        // Large faded arrow icon
        TextView emptyArrow = new TextView(this);
        emptyArrow.setText("\u2193"); // ↓
        emptyArrow.setTextColor(Color.argb(45, 108, 99, 255));
        emptyArrow.setTextSize(64);
        emptyArrow.setGravity(Gravity.CENTER);
        emptyView.addView(emptyArrow);

        TextView emptyTitle = new TextView(this);
        emptyTitle.setText("No downloads yet");
        emptyTitle.setTextColor(C_TEXT1);
        emptyTitle.setTextSize(17);
        emptyTitle.setTypeface(null, Typeface.BOLD);
        emptyTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams eTLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        eTLp.setMargins(0, dp(12), 0, 0);
        emptyView.addView(emptyTitle, eTLp);

        TextView emptyBody = new TextView(this);
        emptyBody.setText("Share a YouTube link to GetSub,\nor paste one above to get started.");
        emptyBody.setTextColor(C_TEXT2);
        emptyBody.setTextSize(14);
        emptyBody.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams eBLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        eBLp.setMargins(0, dp(6), 0, 0);
        emptyView.addView(emptyBody, eBLp);

        // ── Job list ──────────────────────────────────────────────────────────
        // Single adapter instance for the life of the activity: updating it
        // in place (instead of setAdapter() on every poll) is what keeps
        // the user's scroll position stable.
        listView = new ListView(this);
        listView.setBackgroundColor(C_BG);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        listView.setEmptyView(emptyView);
        adapter = new JobAdapter(this, new ArrayList<JSONObject>());
        listView.setAdapter(adapter);

        root.addView(emptyView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(listView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        loadJobs();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(poller);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(poller);
    }

    // ── Data ──────────────────────────────────────────────────────────────────

    private void loadJobs() {
        JSONArray arr = JobStore.getAllNewestFirst(this);
        List<JSONObject> jobs = new ArrayList<JSONObject>();
        boolean anyFetching = false;
        StringBuilder sig = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject j = arr.optJSONObject(i);
            if (j == null) continue;
            jobs.add(j);
            String status = j.optString("status", "");
            if (JobStore.STATUS_QUEUED.equals(status))
                anyFetching = true;
            sig.append(j.optInt("id", -1)).append(':').append(status)
               .append(':').append(j.optString("result", "")).append(';');
        }
        topProgress.setVisibility(anyFetching ? View.VISIBLE : View.GONE);
        // v2.5.4: only rebuild rows when the data actually changed. The 1s
        // poller used to notifyDataSetChanged every tick, recreating every
        // row view even when nothing changed — wasted battery, and kept the
        // window from ever reaching accessibility idle (which uiautomator
        // dumps, and therefore the CI visual checks, depend on).
        String s = sig.toString();
        if (!s.equals(renderedSig)) {
            renderedSig = s;
            adapter.updateJobs(jobs);
        }
    }

    /** Ask first: clearing history never deletes saved .txt files. */
    private void confirmClearHistory() {
        final int count = adapter != null ? adapter.getCount() : 0;
        if (count == 0) {
            Toast.makeText(this, "Nothing to clear", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Clear history?")
                .setMessage("Remove all " + count + " downloads from this list?"
                        + " Saved .txt files in Download/ are kept.")
                .setPositiveButton("Clear", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        JobStore.clearAll(MainActivity.this);
                        loadJobs();
                        Toast.makeText(MainActivity.this,
                                "History cleared", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Convert dp → px using the display's density. */
    int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Rounded rectangle drawable — used for buttons and input fields. */
    static GradientDrawable roundRect(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    // ── Job list adapter ──────────────────────────────────────────────────────

    /**
     * Non-static inner class so getView() can call the outer dp() helper.
     *
     * Each row is a card with:
     *   • A 4 dp left accent stripe whose color encodes status
     *     (amber = Queued, green = Done, red = Error).
     *   • A status badge pill, timestamp, URL, and result / filename.
     *   • A small spinner next to the content when status = Queued.
     */
    private class JobAdapter extends BaseAdapter {

        private final Context        ctx;
        private final List<JSONObject> jobs;

        JobAdapter(Context c, List<JSONObject> j) { ctx = c; jobs = j; }

        /** Refresh contents in place — preserves ListView scroll position. */
        void updateJobs(List<JSONObject> fresh) {
            jobs.clear();
            jobs.addAll(fresh);
            notifyDataSetChanged();
        }

        @Override public int     getCount()        { return jobs.size(); }
        @Override public Object  getItem(int p)    { return jobs.get(p); }
        @Override public long    getItemId(int p)  { return p; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            JSONObject job = jobs.get(position);
            String status  = job.optString("status", "");
            String url     = job.optString("url", "");
            String result  = job.optString("result", "");
            String time    = job.optString("time", "");

            boolean isQ = JobStore.STATUS_QUEUED.equals(status);
            boolean isD = JobStore.STATUS_DONE.equals(status);

            // Stripe / badge colour driven by status
            int stripe = isQ ? C_QUEUED : isD ? C_SUCCESS : C_ERROR;

            // ── Outer wrapper supplies the inter-card margin ──────────────────
            LinearLayout wrapper = new LinearLayout(ctx);
            LinearLayout.LayoutParams wLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            wLp.setMargins(dp(12), dp(4), dp(12), dp(4));

            // ── Card: horizontal split — stripe | content ─────────────────────
            LinearLayout card = new LinearLayout(ctx);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setBackground(roundRect(C_CARD, dp(12)));

            // Left accent stripe; left corners follow the card's radius,
            // right corners are flat so the join is seamless.
            View stripeView = new View(ctx);
            GradientDrawable sd = new GradientDrawable();
            sd.setShape(GradientDrawable.RECTANGLE);
            sd.setColor(stripe);
            sd.setCornerRadii(new float[]{
                    dp(12), dp(12),  // top-left
                    0,       0,      // top-right
                    0,       0,      // bottom-right
                    dp(12), dp(12)   // bottom-left
            });
            stripeView.setBackground(sd);
            card.addView(stripeView, new LinearLayout.LayoutParams(
                    dp(4), LinearLayout.LayoutParams.MATCH_PARENT));

            // ── Content area ──────────────────────────────────────────────────
            LinearLayout content = new LinearLayout(ctx);
            content.setOrientation(LinearLayout.HORIZONTAL);
            content.setGravity(Gravity.CENTER_VERTICAL);
            content.setPadding(dp(12), dp(14), dp(12), dp(14));

            // Spinner only visible while Queued
            if (isQ) {
                ProgressBar spin = new ProgressBar(ctx, null,
                        android.R.attr.progressBarStyleSmall);
                spin.setIndeterminate(true);
                LinearLayout.LayoutParams spinLp =
                        new LinearLayout.LayoutParams(dp(18), dp(18));
                spinLp.setMargins(0, 0, dp(10), 0);
                content.addView(spin, spinLp);
            }

            // ── Text column ───────────────────────────────────────────────────
            LinearLayout texts = new LinearLayout(ctx);
            texts.setOrientation(LinearLayout.VERTICAL);

            // Row 1: badge + timestamp
            LinearLayout badgeRow = new LinearLayout(ctx);
            badgeRow.setOrientation(LinearLayout.HORIZONTAL);
            badgeRow.setGravity(Gravity.CENTER_VERTICAL);

            TextView badge = new TextView(ctx);
            badge.setText(status.toUpperCase());
            badge.setTextColor(stripe);
            badge.setTextSize(10);
            badge.setTypeface(null, Typeface.BOLD);
            badge.setLetterSpacing(0.08f);
            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setShape(GradientDrawable.RECTANGLE);
            // Tinted fill at ~16 % opacity
            badgeBg.setColor(Color.argb(40,
                    Color.red(stripe), Color.green(stripe), Color.blue(stripe)));
            badgeBg.setCornerRadius(dp(4));
            badge.setBackground(badgeBg);
            badge.setPadding(dp(7), dp(3), dp(7), dp(3));
            badgeRow.addView(badge);

            if (!time.isEmpty()) {
                TextView timeTv = new TextView(ctx);
                timeTv.setText(time);
                timeTv.setTextColor(C_TEXT2);
                timeTv.setTextSize(11);
                LinearLayout.LayoutParams timeLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                timeLp.setMargins(dp(8), 0, 0, 0);
                badgeRow.addView(timeTv, timeLp);
            }

            texts.addView(badgeRow);

            // Row 2: URL (truncated with ellipsis in the middle)
            TextView urlTv = new TextView(ctx);
            urlTv.setText(url);
            urlTv.setTextColor(C_TEXT1);
            urlTv.setTextSize(13);
            urlTv.setSingleLine(true);
            urlTv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            LinearLayout.LayoutParams urlLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            urlLp.setMargins(0, dp(5), 0, 0);
            texts.addView(urlTv, urlLp);

            // Row 3: result / filename (only when non-empty)
            if (!result.isEmpty()) {
                TextView resTv = new TextView(ctx);
                if (isD) {
                    // Strip the "Saved: " prefix; the colour already signals success.
                    String fname = result.startsWith("Saved: ")
                            ? result.substring(7) : result;
                    resTv.setText("Saved: " + fname);
                    resTv.setTextColor(C_SUCCESS);
                } else {
                    resTv.setText(result);
                    resTv.setTextColor(C_ERROR);
                }
                resTv.setTextSize(12);
                resTv.setSingleLine(false);
                LinearLayout.LayoutParams resLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                resLp.setMargins(0, dp(3), 0, 0);
                texts.addView(resTv, resLp);
            }

            content.addView(texts, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            card.addView(content, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            wrapper.addView(card, wLp);
            return wrapper;
        }
    }
}
