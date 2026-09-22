# GetSub App — Complete Source Bundle

- Generated (UTC): 2026-09-22 13:21:19
- Version: v2.5.3 + CI tooling (see `PROJECT_CAPSULE.md` §28–29)
- Project root: `~/getsub-app` | Package: `com.getsub.share`
- Rebuild: `cd ~/getsub-app && bash build.sh`
- Note: `res/drawable/ic_launcher.png` is binary and excluded; copy it from the repo when rebuilding.
- CI/test tooling lives in `.github/`, `ci/`, `tests/` (repo-only; not needed to rebuild the app).

## Contents

1. `AndroidManifest.xml`
2. `build.sh`
3. `res/values/strings.xml`
4. `src/com/getsub/share/JobStore.java`
5. `src/com/getsub/share/MainActivity.java`
6. `src/com/getsub/share/Prefs.java`
7. `src/com/getsub/share/ShareActivity.java`
8. `src/com/getsub/share/SubtitleDownloadService.java`
9. `src/com/getsub/share/SubtitleFetcher.java`

---

## FILE: `AndroidManifest.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.getsub.share">
    <uses-sdk
        android:minSdkVersion="29"
        android:targetSdkVersion="33" />

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application
        android:label="@string/app_name"
        android:icon="@drawable/ic_launcher"
        android:allowBackup="false">

        <activity
            android:name=".MainActivity"
            android:label="@string/app_name"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <activity
            android:name=".ShareActivity"
            android:label="@string/app_name"
            android:theme="@android:style/Theme.DeviceDefault.Dialog.NoActionBar"
            android:noHistory="true"
            android:excludeFromRecents="true"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.SEND" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="text/plain" />
            </intent-filter>
        </activity>

        <service
            android:name=".SubtitleDownloadService"
            android:exported="false"
            android:foregroundServiceType="dataSync" />

    </application>
</manifest>
```

---

## FILE: `build.sh`

```bash
#!/bin/bash
# Build the GetSub share-handler APK entirely from Termux
set -e

PROJECT="$(cd "$(dirname "$0")" && pwd)"
# Env overrides let CI (GitHub Actions) reuse this exact script:
#   GETSUB_ANDROID_JAR     path to android.jar (default: Termux setup location)
#   GETSUB_KEYSTORE        signing keystore    (default: ~/.debug.keystore)
#   GETSUB_KEYSTORE_ALIAS  key alias           (default: debug)
#   GETSUB_KEYSTORE_PASS   store+key password  (default: android)
# The d8 fallback in Step 4 keeps non-Termux toolchains working; on Termux
# (where dx exists) behavior is exactly what it always was.
ANDROID_JAR="${GETSUB_ANDROID_JAR:-$HOME/android-sdk/android.jar}"

BUILD="$PROJECT/build"
GEN="$BUILD/gen"
OBJ="$BUILD/obj"
APK_DIR="$BUILD/apk"
COMPILED_RES="$BUILD/compiled_res"

if [ ! -f "$ANDROID_JAR" ]; then
  echo "ERROR: android.jar not found at $ANDROID_JAR"
  echo "Run the one-time setup step first."
  exit 1
fi

echo "=== Cleaning build artifacts ==="
rm -rf "$GEN" "$OBJ" "$APK_DIR" "$COMPILED_RES" "$BUILD/classes.dex"
mkdir -p "$GEN" "$OBJ" "$APK_DIR" "$COMPILED_RES"

echo "=== Step 1: Compile resources ==="
aapt2 compile --dir "$PROJECT/res" -o "$COMPILED_RES/"

echo "=== Step 2: Link resources + generate R.java ==="
# Optional version stamping (CI sets these; Termux builds leave them empty):
VNAME="${GETSUB_VERSION_NAME:-}"
VCODE="${GETSUB_VERSION_CODE:-}"
VFLAGS=()
[ -n "$VNAME" ] && VFLAGS+=(--version-name "$VNAME")
[ -n "$VCODE" ] && VFLAGS+=(--version-code "$VCODE")
aapt2 link \
  -I "$ANDROID_JAR" \
  --manifest "$PROJECT/AndroidManifest.xml" \
  --java "$GEN" \
  ${VFLAGS[@]+"${VFLAGS[@]}"} \
  -o "$APK_DIR/app-unaligned.apk" \
  "$COMPILED_RES"/*.flat

echo "=== Step 3: Compile Java ==="
javac \
  -source 1.8 -target 1.8 \
  -classpath "$ANDROID_JAR" \
  -d "$OBJ" \
  "$GEN/com/getsub/share/R.java" \
  "$PROJECT"/src/com/getsub/share/*.java

echo "=== Step 4: Convert to DEX ==="
if command -v dx >/dev/null 2>&1; then
  dx --dex --output="$BUILD/classes.dex" "$OBJ"
else
  # d8 fallback (CI/desktop toolchains): dx is a Termux package, d8 ships
  # with the official Android build-tools. Same DEX output, different
  # launcher. On Termux the dx branch above always wins (dx is installed),
  # so the known d8+OpenJDK21 Termux snag stays avoided.
  ( cd "$OBJ" && jar cf "$BUILD/classes.jar" . )
  d8 --lib "$ANDROID_JAR" --min-api 29 --output "$BUILD" "$BUILD/classes.jar"
fi

echo "=== Step 5: Package APK ==="
cp "$APK_DIR/app-unaligned.apk" "$APK_DIR/app.apk"
cd "$BUILD" && zip -j "$APK_DIR/app.apk" classes.dex
cd "$PROJECT"

echo "=== Step 6: Sign ==="
KS="${GETSUB_KEYSTORE:-$HOME/.debug.keystore}"
KS_ALIAS="${GETSUB_KEYSTORE_ALIAS:-debug}"
KS_PASS="${GETSUB_KEYSTORE_PASS:-android}"
if [ ! -f "$KS" ]; then
  echo "ERROR: keystore not found at $KS"
  echo "Run the one-time setup step first (or set GETSUB_KEYSTORE)."
  exit 1
fi

apksigner sign \
  --ks "$KS" \
  --ks-key-alias "$KS_ALIAS" \
  --ks-pass "pass:$KS_PASS" \
  --key-pass "pass:$KS_PASS" \
  "$APK_DIR/app.apk"

echo ""
echo "=== BUILD SUCCESSFUL ==="
ls -lh "$APK_DIR/app.apk"
echo ""
echo "Install with:"
echo "  cp $APK_DIR/app.apk ~/storage/downloads/getsub-app.apk && termux-open ~/storage/downloads/getsub-app.apk"
```

---

## FILE: `res/values/strings.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">GetSub</string>
</resources>
```

---

## FILE: `src/com/getsub/share/JobStore.java`

```java
package com.getsub.share;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class JobStore {

    private static final String FILE_NAME = "jobs.json";
    private static final int MAX_JOBS = 50;

    public static final String STATUS_QUEUED = "Queued";
    public static final String STATUS_DONE = "Done";
    public static final String STATUS_ERROR = "Error";

    public static synchronized int addJob(Context ctx, String url) {
        return addJob(ctx, url, "");
    }

    /** v2.6: jobs remember which language was requested. */
    public static synchronized int addJob(Context ctx, String url, String lang) {
        JSONArray jobs = readAll(ctx);
        int id = nextId(jobs);
        JSONObject job = new JSONObject();
        try {
            job.put("id", id);
            job.put("url", url);
            job.put("lang", lang == null ? "" : lang);
            job.put("status", STATUS_QUEUED);
            job.put("result", "");
            job.put("time", timestamp());
        } catch (JSONException e) {
            // fields are all simple strings/ints; this shouldn't happen
        }
        jobs.put(job);
        trim(jobs);
        writeAll(ctx, jobs);
        return id;
    }

    public static synchronized void updateJob(Context ctx, int id, String status, String result) {
        JSONArray jobs = readAll(ctx);
        for (int i = 0; i < jobs.length(); i++) {
            try {
                JSONObject job = jobs.getJSONObject(i);
                if (job.getInt("id") == id) {
                    job.put("status", status);
                    job.put("result", result == null ? "" : result);
                    job.put("time", timestamp());
                    break;
                }
            } catch (JSONException e) {
                // skip malformed entry
            }
        }
        writeAll(ctx, jobs);
    }

    public static synchronized void clearAll(Context ctx) {
        writeAll(ctx, new JSONArray());
    }

    public static synchronized JSONArray getAllNewestFirst(Context ctx) {
        JSONArray jobs = readAll(ctx);
        JSONArray reversed = new JSONArray();
        for (int i = jobs.length() - 1; i >= 0; i--) {
            try {
                reversed.put(jobs.getJSONObject(i));
            } catch (JSONException e) {
                // skip malformed entry
            }
        }
        return reversed;
    }

    private static int nextId(JSONArray jobs) {
        int max = 0;
        for (int i = 0; i < jobs.length(); i++) {
            try {
                int id = jobs.getJSONObject(i).getInt("id");
                if (id > max) {
                    max = id;
                }
            } catch (JSONException e) {
                // skip malformed entry
            }
        }
        return max + 1;
    }

    private static void trim(JSONArray jobs) {
        while (jobs.length() > MAX_JOBS) {
            jobs.remove(0);
        }
    }

    private static String timestamp() {
        return new SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(new Date());
    }

    private static JSONArray readAll(Context ctx) {
        File f = new File(ctx.getFilesDir(), FILE_NAME);
        if (!f.exists()) {
            return new JSONArray();
        }
        StringBuilder sb = new StringBuilder();
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
            }
        } catch (IOException e) {
            return new JSONArray();
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (IOException ignored) {
                }
            }
        }
        try {
            return new JSONArray(sb.toString());
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private static void writeAll(Context ctx, JSONArray jobs) {
        File f = new File(ctx.getFilesDir(), FILE_NAME);
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(f);
            out.write(jobs.toString().getBytes("UTF-8"));
        } catch (IOException e) {
            // best-effort persistence; nothing more we can do here
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
```

---

## FILE: `src/com/getsub/share/MainActivity.java`

```java
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

        // v2.6: narrow language field; empty means "use my saved default".
        final EditText langInput = new EditText(this);
        langInput.setHint(Prefs.getLang(this));
        langInput.setHintTextColor(C_TEXT2);
        langInput.setTextColor(C_TEXT1);
        langInput.setTextSize(14);
        langInput.setSingleLine(true);
        langInput.setBackground(roundRect(C_INPUT, dp(12)));
        langInput.setPadding(dp(10), dp(14), dp(10), dp(14));
        LinearLayout.LayoutParams langLp = new LinearLayout.LayoutParams(
                dp(52), LinearLayout.LayoutParams.WRAP_CONTENT);
        langLp.setMargins(0, 0, dp(10), 0);

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
                String lang = langInput.getText().toString().trim();
                if (lang.isEmpty()) {
                    lang = Prefs.getLang(MainActivity.this);
                } else {
                    Prefs.setLang(MainActivity.this, lang);
                }
                int jobId = JobStore.addJob(MainActivity.this, text, lang);
                Intent si = new Intent(MainActivity.this, SubtitleDownloadService.class);
                si.putExtra(SubtitleDownloadService.EXTRA_JOB_ID, jobId);
                si.putExtra(SubtitleDownloadService.EXTRA_URL, text);
                si.putExtra(SubtitleDownloadService.EXTRA_LANG, lang);
                startForegroundService(si);
                urlInput.setText("");
                Toast.makeText(MainActivity.this,
                        "GetSub: fetching " + lang + " subtitles...", Toast.LENGTH_SHORT).show();
                loadJobs();
            }
        });

        inputRow.addView(urlInput, editLp);
        inputRow.addView(langInput, langLp);
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
```

---

## FILE: `src/com/getsub/share/Prefs.java`

```java
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
```

---

## FILE: `src/com/getsub/share/ShareActivity.java`

```java
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
```

---

## FILE: `src/com/getsub/share/SubtitleDownloadService.java`

```java
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
```

---

## FILE: `src/com/getsub/share/SubtitleFetcher.java`

```java
package com.getsub.share;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The app's own subtitle-fetching engine (v2.0, fixed v2.0.1). Talks to
 * YouTube directly over HTTP -- no Termux, no yt-dlp, no Python. Mirrors
 * the CLI's fetch -> sanitize -> format pipeline in Java so the app can
 * run fully standalone.
 *
 * Strategy (v2.0.1 fix for "YouTube returned no caption data"):
 *  1. Primary: Innertube player API with the ANDROID client. The old
 *     watch-page scrape returns caption baseUrls that now come back HTTP
 *     200 with an EMPTY body for automated requests (YouTube's
 *     proof-of-origin tightening). The ANDROID player endpoint still
 *     returns working caption URLs (same approach yt-dlp's default
 *     'visionos'/'android' clients use).
 *  2. Fallback: the original watch-page ytInitialPlayerResponse scrape.
 *  Caption download itself tries json3 first but also parses the
 *  timedtext XML (srv1/srv3) YouTube actually returns for most
 *  auto-generated tracks, plus VTT as a last resort.
 */
public class SubtitleFetcher {

    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private static final String ANDROID_UA =
            "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip";

    // Public YouTube web/Innertube key, also embedded in YouTube's own
    // web client and used by many open-source caption tools. If Google
    // ever rotates it, the watch-page fallback below still applies.
    private static final String INNERTUBE_KEY =
            "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8";

    private static final String ANDROID_CLIENT_VERSION = "21.26.364";

    public static class Result {
        public final String text;
        public final String filename;

        Result(String text, String filename) {
            this.text = text;
            this.filename = filename;
        }
    }

    public static Result fetch(String sharedText, String langCode) throws Exception {
        if (sharedText == null || sharedText.trim().length() == 0) {
            throw new Exception("No URL found in shared text");
        }
        String url = extractUrl(sharedText);
        String videoId = url != null ? extractVideoId(url) : null;
        if (videoId == null) {
            // Fallback: the shared text may contain a spaced/broken URL
            // (e.g. "watch?v= GYo-NG7tbok"); search the raw text directly.
            videoId = extractVideoId(sharedText);
        }
        if (videoId == null) {
            throw new Exception("Could not find a YouTube video ID in the link");
        }

        Exception innertubeError = null;
        try {
            return fetchViaInnertube(videoId, langCode);
        } catch (Exception e) {
            innertubeError = e;
        }

        // Fallback: original watch-page scrape (kept for resilience; its
        // caption URLs are currently often empty-bodied, but may work for
        // some videos/clients).
        try {
            return fetchViaWatchPage(videoId, langCode);
        } catch (Exception watchError) {
            // Prefer the more informative error. If Innertube already told
            // us what's wrong (no captions, bad lang, login required),
            // surface that instead of a generic watch-page failure.
            if (innertubeError != null && isSpecificError(innertubeError)) {
                throw innertubeError;
            }
            throw watchError;
        }
    }

    private static boolean isSpecificError(Exception e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        return m.startsWith("No '") || m.startsWith("No captions")
                || m.startsWith("YouTube: ");
    }

    // ---- primary path: Innertube ANDROID player API ----

    private static Result fetchViaInnertube(String videoId, String langCode) throws Exception {
        String endpoint = "https://www.youtube.com/youtubei/v1/player?key="
                + INNERTUBE_KEY + "&prettyPrint=false";

        JSONObject client = new JSONObject();
        client.put("clientName", "ANDROID");
        client.put("clientVersion", ANDROID_CLIENT_VERSION);
        client.put("hl", "en");
        client.put("timeZone", "UTC");
        client.put("utcOffsetMinutes", 0);

        JSONObject context = new JSONObject();
        context.put("client", client);

        JSONObject body = new JSONObject();
        body.put("context", context);
        body.put("videoId", videoId);

        String responseJson = httpPostJson(endpoint, body.toString(), ANDROID_UA);
        JSONObject playerResponse = new JSONObject(responseJson);

        return buildResultFromPlayerResponse(playerResponse, langCode);
    }

    // ---- fallback path: watch-page scrape (v2.0 original) ----

    private static Result fetchViaWatchPage(String videoId, String langCode) throws Exception {
        String html = httpGet("https://www.youtube.com/watch?v=" + videoId + "&hl=en");

        String playerJson = extractBalancedJson(html, "ytInitialPlayerResponse");
        if (playerJson == null) {
            throw new Exception("Could not read video info from YouTube (page format may have changed)");
        }

        JSONObject playerResponse = new JSONObject(playerJson);
        return buildResultFromPlayerResponse(playerResponse, langCode);
    }

    private static Result buildResultFromPlayerResponse(
            JSONObject playerResponse, String langCode) throws Exception {
        JSONObject playability = playerResponse.optJSONObject("playabilityStatus");
        if (playability != null && !"OK".equals(playability.optString("status"))) {
            throw new Exception("YouTube: " + playability.optString("reason", playability.optString("status")));
        }

        JSONArray tracks = null;
        JSONObject captions = playerResponse.optJSONObject("captions");
        if (captions != null) {
            JSONObject trackList = captions.optJSONObject("playerCaptionsTracklistRenderer");
            if (trackList != null) {
                tracks = trackList.optJSONArray("captionTracks");
            }
        }
        if (tracks == null || tracks.length() == 0) {
            throw new Exception("No captions available for this video");
        }

        JSONObject track = findTrack(tracks, langCode);
        String baseUrl = track.getString("baseUrl");

        List<String> lines = downloadCaptionLines(baseUrl);
        if (lines.isEmpty()) {
            throw new Exception("YouTube returned no caption data (it may be temporarily "
                    + "rate-limiting automated requests) -- try again in a bit, or use the "
                    + "getsub CLI / Share-to-Termux path");
        }

        List<String> paragraphs = formatParagraphs(lines);
        StringBuilder textBuilder = new StringBuilder();
        for (int i = 0; i < paragraphs.size(); i++) {
            if (i > 0) textBuilder.append("\n\n");
            textBuilder.append(paragraphs.get(i));
        }
        textBuilder.append("\n");

        String title = "subtitles";
        JSONObject videoDetails = playerResponse.optJSONObject("videoDetails");
        if (videoDetails != null) {
            title = videoDetails.optString("title", "subtitles");
        }
        String filename = sanitizeFilename(title) + ".txt";

        return new Result(textBuilder.toString(), filename);
    }

    // ---- caption download with format fallbacks ----

    static List<String> downloadCaptionLines(String baseUrl) throws IOException {
        String[] candidates = new String[]{
                baseUrl,
                withFmt(baseUrl, "json3"),
                withFmt(baseUrl, "srv1"),
                withFmt(baseUrl, "vtt"),
        };
        for (String candidate : candidates) {
            String content;
            try {
                content = httpGet(candidate);
            } catch (IOException e) {
                continue;
            }
            if (content == null || content.trim().length() == 0) {
                continue;
            }
            List<String> lines = parseAuto(content);
            if (!lines.isEmpty()) {
                return lines;
            }
        }
        return new ArrayList<String>();
    }

    static String withFmt(String url, String fmt) {
        if (url.matches("(?i).*[?&]fmt=[^&]*.*")) {
            return url.replaceAll("(?i)([?&]fmt=)[^&]*", "$1" + fmt);
        }
        return url + (url.contains("?") ? "&" : "?") + "fmt=" + fmt;
    }

    /** Detect payload type (json3 vs timedtext XML vs VTT) and parse.
     *
     * v2.5.3: every parser is strictly gated on payload markers —
     *   json3 : body starts with '{'
     *   XML   : body starts with '&lt;?xml' or contains '&lt;transcript'
     *   VTT   : body starts with 'WEBVTT' or contains a real cue timing
     *           ('00:00:01.234 --&gt;'); a bare '--&gt;' no longer counts because
     *           HTML comments contain it.
     * Previously the ungated "last resort" pass ran parseVtt on ANY content,
     * so a 200-with-junk caption response (HTML error page, broken JSON,
     * arbitrary text) was accepted as captions and saved as a garbage .txt
     * marked Done. Junk now yields an empty list → downloadCaptionLines moves
     * to the next format candidate → the honest "YouTube returned no caption
     * data" error surfaces if none pan out. */
    static List<String> parseAuto(String content) {
        String trimmed = content.trim();
        if (trimmed.length() == 0) {
            return new ArrayList<String>();
        }
        if (trimmed.startsWith("{")) {
            try {
                List<String> lines = parseJson3(trimmed);
                if (!lines.isEmpty()) return lines;
            } catch (Exception ignored) {
            }
        }
        if (trimmed.startsWith("<?xml") || trimmed.contains("<transcript")) {
            try {
                List<String> lines = parseTimedtextXml(trimmed);
                if (!lines.isEmpty()) return lines;
            } catch (Exception ignored) {
            }
        }
        if (looksLikeVtt(trimmed)) {
            try {
                List<String> lines = parseVtt(trimmed);
                if (!lines.isEmpty()) return lines;
            } catch (Exception ignored) {
            }
        }
        return new ArrayList<String>();
    }

    /** VTT gate (v2.5.3): WEBVTT header, or at least one real cue-timing line. */
    static boolean looksLikeVtt(String content) {
        if (content.startsWith("WEBVTT")) {
            return true;
        }
        return Pattern.compile("\\d{1,2}:\\d{2}:\\d{2}[.,]\\d{3}\\s*-->")
                .matcher(content).find();
    }

    // ---- networking ----

    private static String httpGet(String urlStr) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestProperty("User-Agent", DESKTOP_UA);
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            conn.setRequestProperty("Cookie", "CONSENT=YES+1");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(true);

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) {
                throw new IOException("HTTP " + code + " with no response body from " + urlStr);
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] data = new byte[8192];
            int n;
            while ((n = is.read(data)) != -1) {
                buffer.write(data, 0, n);
            }
            is.close();

            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + " fetching " + urlStr);
            }
            return buffer.toString("UTF-8");
        } finally {
            conn.disconnect();
        }
    }

    private static String httpPostJson(String urlStr, String jsonBody, String userAgent) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            conn.setRequestProperty("User-Agent", userAgent);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(true);

            byte[] body = jsonBody.getBytes("UTF-8");
            conn.setFixedLengthStreamingMode(body.length);
            OutputStream out = conn.getOutputStream();
            try {
                out.write(body);
            } finally {
                out.close();
            }

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) {
                throw new IOException("HTTP " + code + " with no response body from " + urlStr);
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] data = new byte[8192];
            int n;
            while ((n = is.read(data)) != -1) {
                buffer.write(data, 0, n);
            }
            is.close();

            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + " fetching " + urlStr);
            }
            return buffer.toString("UTF-8");
        } finally {
            conn.disconnect();
        }
    }

    // ---- parsing helpers (unit-tested against synthetic data before shipping) ----

    static String extractUrl(String sharedText) {
        Matcher m = Pattern.compile("https?://\\S+").matcher(sharedText);
        if (!m.find()) {
            return null;
        }
        // Strip trailing punctuation the share text often glues on.
        return m.group().replaceAll("[\\)\\].,!?\\\"';:]+$", "");
    }

    static String extractVideoId(String url) {
        // Lenient: allow whitespace after separators (handles broken
        // shares like "watch?v= GYo-NG7tbok").
        Matcher m1 = Pattern.compile("youtu\\.be/\\s*([A-Za-z0-9_-]{11})").matcher(url);
        if (m1.find()) return m1.group(1);
        Matcher m2 = Pattern.compile("[?&]v=\\s*([A-Za-z0-9_-]{11})").matcher(url);
        if (m2.find()) return m2.group(1);
        Matcher m3 = Pattern.compile("(?:shorts|live|embed)/\\s*([A-Za-z0-9_-]{11})").matcher(url);
        if (m3.find()) return m3.group(1);
        return null;
    }

    static String extractBalancedJson(String html, String marker) {
        int idx = html.indexOf(marker);
        if (idx < 0) return null;
        int braceStart = html.indexOf('{', idx);
        if (braceStart < 0) return null;
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = braceStart; i < html.length(); i++) {
            char c = html.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (c == '\\') {
                escape = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) continue;
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return html.substring(braceStart, i + 1);
                }
            }
        }
        return null;
    }

    static JSONObject findTrack(JSONArray tracks, String wantLang) throws Exception {
        JSONObject autoMatch = null;
        List<String> available = new ArrayList<String>();
        for (int i = 0; i < tracks.length(); i++) {
            JSONObject t = tracks.getJSONObject(i);
            String lc = t.optString("languageCode", "");
            if (!available.contains(lc)) available.add(lc);
            if (lc.equals(wantLang)) {
                if (!"asr".equals(t.optString("kind", ""))) {
                    return t; // prefer a manual/uploaded track over auto-generated
                } else if (autoMatch == null) {
                    autoMatch = t;
                }
            }
        }
        if (autoMatch != null) return autoMatch;
        throw new Exception("No '" + wantLang + "' subtitles found. Available: " + joinList(available, ", "));
    }

    static String joinList(List<String> items, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    static List<String> parseJson3(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        JSONArray events = root.optJSONArray("events");
        List<String> lines = new ArrayList<String>();
        if (events == null) return lines;
        for (int i = 0; i < events.length(); i++) {
            JSONObject ev = events.getJSONObject(i);
            JSONArray segs = ev.optJSONArray("segs");
            if (segs == null) continue; // styling/window-only event, no text
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < segs.length(); j++) {
                sb.append(segs.getJSONObject(j).optString("utf8", ""));
            }
            String text = sb.toString().replace("\n", " ").trim();
            if (!text.isEmpty() && (lines.isEmpty() || !lines.get(lines.size() - 1).equals(text))) {
                lines.add(text);
            }
        }
        return lines;
    }

    /**
     * Parse YouTube timedtext XML — both srv3 ({@code <p t="0" d="5920">} with
     * inner {@code <s>} word tags) and srv1 ({@code <text start="0" dur="2.5">})
     * shapes; auto-generated tracks may return either even when json3 was
     * requested. v2.5.3: tag names match on a word boundary with a
     * backreference, so look-alikes (e.g. {@code <pre>}) can never pair up,
     * and srv1 no longer depends on the (now gated) VTT fallback.
     */
    static List<String> parseTimedtextXml(String xml) {
        List<String> lines = new ArrayList<String>();
        Matcher p = Pattern.compile("<(p|text)(?:\\s[^>]*)?>(.*?)</\\1>", Pattern.DOTALL).matcher(xml);
        while (p.find()) {
            String inner = p.group(2);
            // Strip inner tags (<s>, <w>, <b>, etc.) but keep their text.
            inner = inner.replaceAll("<[^>]+>", "");
            inner = unescapeHtml(inner).replace("\n", " ").replaceAll("\\s+", " ").trim();
            if (!inner.isEmpty() && (lines.isEmpty() || !lines.get(lines.size() - 1).equals(inner))) {
                lines.add(inner);
            }
        }
        return lines;
    }

    /** Parse WebVTT payloads (same rules as the CLI's Python cleaner). */
    static List<String> parseVtt(String vtt) {
        List<String> lines = new ArrayList<String>();
        String[] rawLines = vtt.split("\n");
        for (String raw : rawLines) {
            String line = raw.trim();
            if (line.length() == 0) continue;
            if (line.startsWith("WEBVTT") || line.startsWith("Kind:")
                    || line.startsWith("Language:") || line.startsWith("NOTE")) continue;
            if (line.contains("-->")) continue;
            line = line.replaceAll("<[^>]+>", "");
            line = unescapeHtml(line).trim();
            if (!line.isEmpty() && (lines.isEmpty() || !lines.get(lines.size() - 1).equals(line))) {
                lines.add(line);
            }
        }
        return lines;
    }

    /** Minimal HTML-entity decoder (no extra deps on Android). */
    static String unescapeHtml(String s) {
        if (s == null || s.indexOf('&') < 0) return s;
        String out = s.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&#x27;", "'")
                .replace("&#x2F;", "/")
                .replace("&nbsp;", " ");
        // Decimal numeric entities: &#123;
        Matcher dm = Pattern.compile("&#(\\d+);").matcher(out);
        StringBuffer sb = new StringBuffer();
        while (dm.find()) {
            try {
                int code = Integer.parseInt(dm.group(1));
                dm.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf((char) code)));
            } catch (Exception e) {
                dm.appendReplacement(sb, Matcher.quoteReplacement(dm.group(0)));
            }
        }
        dm.appendTail(sb);
        out = sb.toString();
        // Hex numeric entities: &#x1F;
        Matcher hm = Pattern.compile("&#x([0-9a-fA-F]+);").matcher(out);
        sb = new StringBuffer();
        while (hm.find()) {
            try {
                int code = Integer.parseInt(hm.group(1), 16);
                hm.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf((char) code)));
            } catch (Exception e) {
                hm.appendReplacement(sb, Matcher.quoteReplacement(hm.group(0)));
            }
        }
        hm.appendTail(sb);
        return sb.toString();
    }

    static List<String> formatParagraphs(List<String> lines) {
        List<String> paragraphs = new ArrayList<String>();
        List<String> current = new ArrayList<String>();
        for (String line : lines) {
            if (line.startsWith(">>") && !current.isEmpty()) {
                paragraphs.add(joinList(current, " "));
                current.clear();
            }
            current.add(line);
            String joined = joinList(current, " ");
            String lastChar = line.isEmpty() ? "" : line.substring(line.length() - 1);
            boolean endsSentence = lastChar.equals(".") || lastChar.equals("?") || lastChar.equals("!");
            if (joined.length() > 250 && endsSentence && !line.startsWith(">>")) {
                paragraphs.add(joined);
                current.clear();
            }
        }
        if (!current.isEmpty()) {
            paragraphs.add(joinList(current, " "));
        }
        return paragraphs;
    }

    static String sanitizeFilename(String title) {
        if (title == null || title.trim().isEmpty()) title = "subtitles";
        String cleaned = title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.isEmpty()) cleaned = "subtitles";
        if (cleaned.length() > 150) cleaned = cleaned.substring(0, 150).trim();
        return cleaned;
    }
}
```
