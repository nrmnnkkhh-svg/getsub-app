# Project Capsule: Termux YouTube Subtitle Downloader (getsub)

**Version: 1.3 — GetSub becomes a real home-screen app with live download status (Queued → Done/Error), powered by Termux's official RUN_COMMAND result callback**

## 1. Project Overview

**Description:** A custom command-line utility built for Android via Termux that downloads YouTube video subtitles and reformats them into clean, highly readable plain text (.txt) files. It can be run from the command line, triggered hands-free by sharing a link to Termux, or triggered via the **GetSub app** — an installable Android app (built 100% inside Termux) that appears in the share sheet *and*, as of v1.3, provides a home-screen icon opening a live status list of your recent downloads.

**Primary Goal:** Extract transcriptions without downloading video/audio, bypassing complex authentication (cookies) via standard public-facing requests, saving output to the Android Download folder — with zero manual typing once a link is shared, and (v1.3) visible feedback on what's downloading and whether it succeeded.

**Components:**
- **Core engine:** `getsub` — `$PREFIX/bin/getsub` (executable bash script, unchanged since v1.0).
- **Share handler:** `~/bin/termux-url-opener` — Android's native "Share to Termux" hook, **updated in v1.3** to expose its result (last log line + real exit code) to the app.
- **GetSub app (v1.2→v1.3):** `com.getsub.share` — APK built entirely from Termux CLI tools. v1.3 adds a launcher icon (`MainActivity`), on-device job storage (`JobStore`), and a result callback receiver (`ResultReceiver`). Source in `~/getsub-app/`.

## 2. Environment & Dependencies

**OS Environment:** Android (via Termux, F-Droid release). Result callback requires a reasonably recent Termux (≥ 0.109 — any current F-Droid build qualifies).

**Storage:** `termux-setup-storage` → `~/storage/downloads`.

**Runtime packages:** `python`, `python-pip`, `yt-dlp` (`pip install -U yt-dlp`).

**Optional:** `termux-api` pkg + Termux:API app (same source as Termux) for completion notifications.

**Termux config:** `~/.termux/termux.properties` must contain `allow-external-apps=true`; Termux fully restarted afterwards (read-only otherwise).

**Build-time packages (unchanged from v1.2):** `openjdk-21`, `aapt`, `aapt2`, `apksigner`, `dx`, `zip`, `unzip`, `curl`; `android.jar` (platform-33) at `~/android-sdk/android.jar`; signing key `~/.debug.keystore` (alias `debug`, passwords `android`). **No new packages needed for v1.3.**

## 3. Core Logic & Workflow

Three entry points feed the same core engine:

1. **Manual CLI:** `getsub <url> [lang]`.
2. **Share → Termux:** fires `~/bin/termux-url-opener` with the shared text.
3. **Share → GetSub (v1.2, extended in v1.3):** invisible `ShareActivity` reads the shared text, logs a Queued job, and fires Termux's `RUN_COMMAND` intent **with a callback `PendingIntent` attached**. Termux runs `termux-url-opener` in the background; when it finishes, Termux fires the `PendingIntent` back with a result bundle (`exitCode`, `stdout`, `errmsg`); `ResultReceiver` updates the job to Done/Error; `MainActivity`'s list shows it.

```
YouTube "Share"
   ├──→ [Termux] (native target)            ──→ ~/bin/termux-url-opener
   └──→ [GetSub] ShareActivity (invisible)
            │ 1. JobStore.addJob → "Queued"
            │ 2. RUN_COMMAND intent + PendingIntent callback
            v
        Termux RunCommandService (background)
            v
        ~/bin/termux-url-opener   ← v1.3: prints last log line to stdout,
            │                       exits with getsub's real exit code
            v
        getsub <url> → .txt in Download/ + notification
            │
            │ 3. Termux fires the PendingIntent with result bundle
            v
        ResultReceiver → JobStore.updateJob → "Done" / "Error"
            v
        MainActivity (home-screen icon): list auto-refreshes every 2s
```

**Design notes:** the app duplicates no download logic (the shared text passes through raw; `termux-url-opener` still extracts the URL with `grep`). Status — not a percentage progress bar — is the meaningful signal: subtitle files fetch in well under a second, so there is nothing real for a progress bar to show.

**Engine internals (unchanged):** **Download** (yt-dlp `--skip-download`, VTT only) → **Sanitize** (strip WebVTT metadata, decode HTML entities) → **Format** (merge choppy lines into paragraphs via `.?!` boundaries, 250+ char threshold, `>>` speaker tags) → **Output** (.txt named after video title in `Download/`).

## 4. The Source Code — getsub (Core Engine, unchanged)

```bash
#!/data/data/com.termux/files/usr/bin/bash
# getsub <youtube-url> [lang-code] -- clean .txt transcript, no video
set -e

URL="$1"
SUBLANG="${2:-en}"

if [ -z "$URL" ]; then
  echo "Usage: getsub <youtube-url> [lang-code]"
  exit 1
fi

OUTDIR="$HOME/storage/downloads"
mkdir -p "$OUTDIR"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

echo "Fetching '$SUBLANG' subtitles..."
yt-dlp --skip-download --write-subs --write-auto-subs \
  --sub-langs "$SUBLANG" --sub-format vtt \
  --paths "$TMP" "$URL"

VTT=$(find "$TMP" -name "*.vtt" | head -n1)
if [ -z "$VTT" ]; then
  echo "No '$SUBLANG' subtitles found. Check available languages with:"
  echo "yt-dlp --list-subs \"$URL\""
  exit 1
fi

OUT="$OUTDIR/$(basename "${VTT%.vtt}").txt"
python3 - "$VTT" "$OUT" << 'PYEOF'
import sys, html, re
vtt_file, out_file = sys.argv[1], sys.argv[2]
with open(vtt_file, 'r', encoding='utf-8', errors='ignore') as f:
    raw = f.read()
lines = []
for line in raw.splitlines():
    line = line.strip()
    if not line or line.startswith(('WEBVTT', 'Kind:', 'Language:', 'NOTE')) or '-->' in line:
        continue
    line = re.sub(r'<[^>]+>', '', line)
    line = html.unescape(line).strip()
    if line and (not lines or lines[-1] != line):
        lines.append(line)
paragraphs = []
current = []
for line in lines:
    if line.startswith('>>') and current:
        paragraphs.append(' '.join(current))
        current = []
    current.append(line)
    joined = ' '.join(current)
    if len(joined) > 250 and line[-1:] in '.?!' and not line.startswith('>>'):
        paragraphs.append(joined)
        current = []
if current:
    paragraphs.append(' '.join(current))
with open(out_file, 'w', encoding='utf-8') as f:
    f.write('\n\n'.join(paragraphs) + '\n')
PYEOF

echo "Saved: $OUT"
```

## 5. The Source Code — termux-url-opener (Share Handler, updated v1.3)

**v1.3 change:** prints the last log line to stdout and exits with getsub's real exit code. Without this, Termux's result callback would come back empty every time (the v1.2 version redirected everything into the log file and produced no stdout). Harmless for the CLI and Share→Termux paths, which never read this output.

```bash
#!/data/data/com.termux/files/usr/bin/bash
# Runs when a link is shared to Termux (e.g. YouTube's Share sheet), or via the GetSub app
SHARED="$1"
URL=$(echo "$SHARED" | grep -oE 'https?://[^[:space:]]+' | head -n1)
LOG="$HOME/.getsub-last.log"

if [ -z "$URL" ]; then
  echo "No URL found in shared text" > "$LOG"
  RC=1
else
  getsub "$URL" > "$LOG" 2>&1
  RC=$?
fi

termux-notification --title "getsub" --content "$(tail -n1 "$LOG")" 2>/dev/null

# v1.3: also print the last log line to stdout and exit with getsub's real
# status code, so the GetSub app's RUN_COMMAND result callback can show
# live status inside the app. Harmless for the plain CLI / Share->Termux
# paths, which never read this output.
tail -n1 "$LOG"
exit "$RC"
```

## 6. The Source Code — GetSub App (v1.3)

Project root `~/getsub-app/`:

```
~/getsub-app/
├── AndroidManifest.xml          (v1.3: + MainActivity launcher, + ResultReceiver)
├── build.sh                     (v1.3: compiles all .java via glob)
├── res/values/strings.xml       (unchanged)
└── src/com/getsub/share/
    ├── ShareActivity.java       (v1.3: logs Queued job, attaches callback)
    ├── MainActivity.java        (new: home-screen status list)
    ├── JobStore.java            (new: private on-device job storage)
    └── ResultReceiver.java      (new: Termux result callback)
```

### AndroidManifest.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.getsub.share">
    <uses-sdk
        android:minSdkVersion="24"
        android:targetSdkVersion="33" />
    <uses-permission android:name="com.termux.permission.RUN_COMMAND" />
    <queries>
        <package android:name="com.termux" />
    </queries>
    <application
        android:label="@string/app_name"
        android:icon="@android:drawable/stat_sys_download"
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
            android:theme="@android:style/Theme.NoDisplay"
            android:noHistory="true"
            android:excludeFromRecents="true"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.SEND" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="text/plain" />
            </intent-filter>
        </activity>

        <receiver
            android:name=".ResultReceiver"
            android:exported="false" />

    </application>
</manifest>
```

`MainActivity` + `LAUNCHER` filter = the home-screen icon. `ResultReceiver` is `exported="false"` so only this app can deliver callbacks to it. `ShareActivity` stays invisible (`Theme.NoDisplay`).

### res/values/strings.xml (unchanged)

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">GetSub</string>
</resources>
```

### src/com/getsub/share/ShareActivity.java (updated v1.3)

```java
package com.getsub.share;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

public class ShareActivity extends Activity {

    private static final String TERMUX_PACKAGE = "com.termux";
    private static final String RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService";
    private static final String ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND";
    private static final String EXTRA_RUN_COMMAND_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT";

    private static final String SCRIPT_PATH =
            "/data/data/com.termux/files/home/bin/termux-url-opener";

    private static final String WORK_DIR =
            "/data/data/com.termux/files/home";

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

        try {
            Intent resultIntent = new Intent(this, ResultReceiver.class);
            resultIntent.putExtra(ResultReceiver.EXTRA_JOB_ID, jobId);

            int flags = PendingIntent.FLAG_ONE_SHOT
                    | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    this, jobId, resultIntent, flags);

            Intent cmd = new Intent();
            cmd.setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE);
            cmd.setAction(ACTION_RUN_COMMAND);
            cmd.putExtra("com.termux.RUN_COMMAND_PATH", SCRIPT_PATH);
            cmd.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{sharedText});
            cmd.putExtra("com.termux.RUN_COMMAND_WORKDIR", WORK_DIR);
            cmd.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
            cmd.putExtra(EXTRA_RUN_COMMAND_PENDING_INTENT, pendingIntent);

            startService(cmd);

            Toast.makeText(this, "GetSub: fetching subtitles...", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            JobStore.updateJob(this, jobId, JobStore.STATUS_ERROR, e.getMessage());
            Toast.makeText(this, "GetSub error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }

        finish();
    }
}
```

Callback flags: `FLAG_ONE_SHOT` (fires once per job) plus `FLAG_MUTABLE` on Android 12+ (required for Termux to fill in the result extras).

### src/com/getsub/share/MainActivity.java (new v1.3)

```java
package com.getsub.share;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SimpleAdapter;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {

    private ListView listView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poller = new Runnable() {
        @Override
        public void run() {
            loadJobs();
            handler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 48, 24, 24);

        TextView title = new TextView(this);
        title.setText("GetSub - Downloads");
        title.setTextSize(20);
        root.addView(title);

        TextView empty = new TextView(this);
        empty.setId(android.R.id.empty);
        empty.setText("No downloads yet.\nShare a YouTube link to GetSub to get started.");
        empty.setPadding(0, 32, 0, 0);
        root.addView(empty);

        listView = new ListView(this);
        root.addView(listView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        listView.setEmptyView(empty);

        Button refresh = new Button(this);
        refresh.setText("Refresh");
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadJobs();
            }
        });
        root.addView(refresh);

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

    private void loadJobs() {
        JSONArray jobs = JobStore.getAllNewestFirst(this);
        List<Map<String, String>> rows = new ArrayList<Map<String, String>>();
        for (int i = 0; i < jobs.length(); i++) {
            try {
                JSONObject job = jobs.getJSONObject(i);
                Map<String, String> row = new HashMap<String, String>();
                String status = job.getString("status");
                row.put("text1", "[" + status + "] " + job.getString("url"));
                String result = job.optString("result", "");
                String line2 = job.getString("time");
                if (result.length() > 0) {
                    line2 = line2 + " - " + result;
                }
                row.put("text2", line2);
                rows.add(row);
            } catch (JSONException e) {
                // skip malformed entry
            }
        }

        SimpleAdapter adapter = new SimpleAdapter(
                this, rows,
                android.R.layout.simple_list_item_2,
                new String[]{"text1", "text2"},
                new int[]{android.R.id.text1, android.R.id.text2});
        listView.setAdapter(adapter);
    }
}
```

UI is built entirely in code (no XML layouts). Poller runs only while visible (`onResume`/`onPause`), so no background battery cost.

### src/com/getsub/share/JobStore.java (new v1.3)

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
        JSONArray jobs = readAll(ctx);
        int id = nextId(jobs);
        JSONObject job = new JSONObject();
        try {
            job.put("id", id);
            job.put("url", url);
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

Storage is a private `jobs.json` in the app's own data dir — no extra permissions, capped at 50 entries, cleared only by uninstall.

### src/com/getsub/share/ResultReceiver.java (new v1.3)

```java
package com.getsub.share;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

public class ResultReceiver extends BroadcastReceiver {

    public static final String EXTRA_JOB_ID = "com.getsub.share.JOB_ID";

    @Override
    public void onReceive(Context context, Intent intent) {
        int jobId = intent.getIntExtra(EXTRA_JOB_ID, -1);
        if (jobId < 0) {
            return;
        }

        Bundle result = intent.getBundleExtra("result");
        if (result == null) {
            JobStore.updateJob(context, jobId, JobStore.STATUS_ERROR, "No result from Termux");
            return;
        }

        int exitCode = result.getInt("exitCode", -1);
        String stdout = result.getString("stdout", "");
        String lastLine = lastNonEmptyLine(stdout);

        if (exitCode == 0) {
            JobStore.updateJob(context, jobId, JobStore.STATUS_DONE, lastLine);
        } else {
            String errmsg = result.getString("errmsg", "");
            String detail = lastLine.length() > 0 ? lastLine : errmsg;
            JobStore.updateJob(context, jobId, JobStore.STATUS_ERROR, detail);
        }
    }

    private String lastNonEmptyLine(String text) {
        if (text == null) {
            return "";
        }
        String[] lines = text.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.length() > 0) {
                return line;
            }
        }
        return "";
    }
}
```

Reads Termux's documented result bundle keys: `exitCode`, `stdout`, `errmsg`. `Done` shows the script's last stdout line (i.e. `Saved: <file>`); `Error` shows the last line or Termux's error message.

### build.sh (updated v1.3 — Step 3 now compiles every .java via glob)

```bash
#!/bin/bash
# Build the GetSub share-handler APK entirely from Termux
set -e

PROJECT="$(cd "$(dirname "$0")" && pwd)"
ANDROID_JAR="$HOME/android-sdk/android.jar"

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
aapt2 link \
  -I "$ANDROID_JAR" \
  --manifest "$PROJECT/AndroidManifest.xml" \
  --java "$GEN" \
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
dx --dex --output="$BUILD/classes.dex" "$OBJ"

echo "=== Step 5: Package APK ==="
cp "$APK_DIR/app-unaligned.apk" "$APK_DIR/app.apk"
cd "$BUILD" && zip -j "$APK_DIR/app.apk" classes.dex
cd "$PROJECT"

echo "=== Step 6: Sign ==="
if [ ! -f "$HOME/.debug.keystore" ]; then
  echo "ERROR: debug keystore not found at $HOME/.debug.keystore"
  echo "Run the one-time setup step first."
  exit 1
fi

apksigner sign \
  --ks "$HOME/.debug.keystore" \
  --ks-key-alias debug \
  --ks-pass pass:android \
  --key-pass pass:android \
  "$APK_DIR/app.apk"

echo ""
echo "=== BUILD SUCCESSFUL ==="
ls -lh "$APK_DIR/app.apk"
echo ""
echo "Install with:"
echo "  cp $APK_DIR/app.apk ~/storage/downloads/getsub-app.apk && termux-open ~/storage/downloads/getsub-app.apk"
```

(`dx` over `d8` remains deliberate — `d8` has a known OpenJDK 21 compatibility snag in Termux.)

## 7. Building & Installing

**One-time setup (unchanged from v1.2):** `allow-external-apps=true` + full Termux restart; `pkg install -y openjdk-21 aapt aapt2 apksigner dx zip unzip curl`; download platform-33 `android.jar` to `~/android-sdk/`; generate `~/.debug.keystore` via `keytool` (full commands in v1.2 capsule §7).

**Build:**
```bash
cd ~/getsub-app && bash build.sh
```

**Install / upgrade:**
```bash
cp ~/getsub-app/build/apk/app.apk ~/storage/downloads/getsub-app.apk
termux-open ~/storage/downloads/getsub-app.apk
```

Same keystore ⇒ installs as a clean **upgrade**: the manually granted `RUN_COMMAND` permission survives (only a full uninstall resets it).

**Manual permission (once per fresh install):** Settings → Apps → GetSub → Permissions/Additional/Other permissions → enable **"Run commands in Termux environment"**. Never auto-prompted.

## 8. Usage Instructions

- **CLI:** `getsub <url> [lang]` (e.g. `getsub https://youtu.be/EXAMPLE es`).
- **Share → Termux:** share a YouTube link, choose Termux — transcript saved to `Download`, optional notification.
- **Share → GetSub:** share a YouTube link, choose GetSub from the share sheet — subtitles download in the background; app appears in the share sheet alongside Termux.
- **Home screen (v1.3):** tap the **GetSub icon** → "GetSub - Downloads" list. Each row: `[Status] <shared url>` + timestamp and result line (saved filename on Done, error reason on Error). Auto-refreshes every ~2s while open; manual **Refresh** button also available. First run shows the "No downloads yet" hint.

## 9. Design Constraints & Maintenance

- **Status, not progress bars (v1.3):** subtitle fetches complete in under a second; the meaningful signal is status, not a percentage — no progress bar UI exists by design.
- **Result callback prerequisites (v1.3):** recent F-Droid Termux (≥ 0.109), `allow-external-apps=true` + Termux restarted. A **force-stopped** GetSub app can miss callbacks until opened again once.
- **`termux-url-opener` must be the v1.3 version** for meaningful `Done`/`Error` status (it must print output and exit with the real code).
- **Job history is app-private (v1.3):** `jobs.json` lives in GetSub's data dir; uninstalling the app clears it. 50-entry cap, oldest trimmed.
- **RUN_COMMAND permission is manual-only;** symptom of missing grant: `GetSub error: Not allowed to start service Intent { act=com.termux... }`.
- **`<queries>` block mandatory** in the manifest for keystore continuity / clean upgrades; **ShareActivity stays invisible** by design while `MainActivity` is the visible face.
- **Customizing:** `app_name` in `strings.xml`, icon in manifest; new Java files compile automatically (build.sh glob). Renaming the package requires updating `com.getsub.share` references in `build.sh`.
- **Share language limitation remains** (share intent carries only the link). Open idea: `ShareActivity` could show a tiny language picker before firing — now feasible since the callback exists to confirm completion.
- **403 bot-check runtime warnings:** ignorable; Termux:API source must match Termux's.

## 10. Troubleshooting

- **`Not allowed to start service Intent { act=com.termux... }`:** RUN_COMMAND permission not granted — check Settings → Apps → GetSub → Other/Additional permissions.
- **List stuck on `[Queued]` (v1.3):** callback never arrived — check Termux is current F-Droid; `allow-external-apps=true` set **and** Termux restarted; if GetSub was force-stopped, open it once.
- **`[Error] No result from Termux` (v1.3):** callback fired but empty — update Termux build.
- **`[Done]` but empty result line (v1.3):** `termux-url-opener` is still the v1.2 version (no stdout) — re-apply §5.
- **No home-screen icon (v1.3):** install didn't finish — check the app drawer.
- **List empty despite a working share (v1.3):** tap Refresh; if a Toast error appeared during share, that's the cause; remember history is wiped by uninstall.
- **Toast but no file / nothing happens on share / GetSub missing from share sheet:** permission not granted; launcher share-sheet cache stale — try "More".
- **"App not installed" / Build `BLOCKED` re `com.termux`:** allow install of unknown apps and retry; `<queries>` missing or mistyped in manifest.
- **Build step failure:** missing Part-5 package or `android.jar`.
- **HTTP 403:** `pip install -U yt-dlp`.

## 11. File Map

| Path | Purpose |
|---|---|
| `$PREFIX/bin/getsub` | Core engine (unchanged) |
| `~/bin/termux-url-opener` | Share handler — v1.3: exposes status to the app |
| `~/getsub-app/` | App source + `build.sh` |
| `~/getsub-app/src/com/getsub/share/*.java` | `ShareActivity`, `MainActivity`, `JobStore`, `ResultReceiver` |
| `~/getsub-app/build/apk/app.apk` | Built, signed APK |
| App-private `jobs.json` | Job history (GetSub data dir; cleared on uninstall) |
| `~/android-sdk/android.jar` | Compile-time platform stubs |
| `~/.debug.keystore` | Signing key — keep for clean upgrades |
| `~/.termux/termux.properties` | Must contain `allow-external-apps=true` |
| `~/.getsub-last.log` | Last share-run output/error |
| `~/storage/downloads/*.txt` | Final transcripts |

## 12. Changelog

- **v1.3:** GetSub becomes a home-screen app with live status. New `MainActivity` (launcher icon + auto-refreshing job list), `JobStore` (private `jobs.json`, 50-entry cap), `ResultReceiver` (Termux `RUN_COMMAND_PENDING_INTENT` result callback: `exitCode`/`stdout`/`errmsg`). `ShareActivity` now logs a `Queued` job and attaches the callback PendingIntent (`FLAG_ONE_SHOT` + `FLAG_MUTABLE` on Android 12+). `termux-url-opener` updated to print its last log line to stdout and exit with getsub's real code so callbacks carry meaningful status. Manifest registers the launcher activity and a private receiver; `build.sh` compiles all `.java` files via glob. Status model: `Queued → Done (filename)` / `Error (reason)`.
- **v1.2:** Added the GetSub share app (`com.getsub.share`) — invisible share-sheet frontend bridging to `termux-url-opener` via Termux's `RUN_COMMAND` intent; built 100% in Termux (aapt2/javac/dx/apksigner); documented `allow-external-apps`, manual RUN_COMMAND permission, build pipeline, troubleshooting, file map.
- **v1.1:** Share Sheet integration via `~/bin/termux-url-opener` + optional Termux:API notifications.
- **v1.0:** Initial `getsub` CLI — yt-dlp VTT download, Python cleanup/paragraph formatting, output to `Download/`.

---

# GetSub — Standalone App Guide (v2.0)

**Goal:** the **GetSub app** (the Share → GetSub path) no longer talks to Termux at runtime at all — no `RUN_COMMAND` intent, no shelling out to `getsub`/yt-dlp/Python. It fetches, formats, and saves subtitles using its own Java code.

**What does NOT change:** the CLI (`getsub <url>`) and **Share → Termux** paths. Those still run the existing bash+Python+yt-dlp engine in `$PREFIX/bin/getsub` and `~/bin/termux-url-opener`, untouched. Keep them — they're your fallback if the app's own fetch ever has trouble (see §9).

You still **build** the app the same way as always: on-device, via Termux's CLI toolchain (`aapt2`/`javac`/`dx`/`apksigner`). Going "Termux-independent" is about what the *installed app* needs at runtime, not how you compile it.

---

## 0. Before you start — two decisions I made for you

1. **`minSdkVersion` 24 → 29.** Saving to `Download/` without Termux needs either the modern `MediaStore` API (API 29+) or the old permission-based file API (API 24–28), and supporting both means twice the storage code. Since this is your own device and Android 10 (API 29, 2019) is a safe floor for a personal-use app, I dropped the old path entirely. If you actually need to run this on something older than Android 10, tell me and I'll add the legacy fallback back in.
2. **The fetch logic is hand-rolled, not yt-dlp.** The app now talks to YouTube's *undocumented* page data and caption endpoint directly (the same approach libraries like `youtube_transcript_api` use, and it's reportedly still working as of 2026) — but it's not backed by yt-dlp's actively-maintained extractor, so it's inherently a bit more fragile. I tested the parsing logic thoroughly against synthetic data (see below), but I can't test it against live YouTube from here. Full honesty on this trade-off is in §9.

Before writing anything, I compiled and ran the app's new parsing/formatting logic as plain Java against synthetic YouTube-shaped data — video-ID extraction from 5 URL styles, balanced-brace JSON extraction from a decoy-laden HTML sample, caption-JSON parsing, filename sanitization, and language-fallback matching all passed. I also ran the **original Python paragraph formatter** side-by-side with the new Java port on the same input and diffed the output — byte-for-byte identical. So the porting itself is solid; the only real unknown is how YouTube's servers respond in the wild.

---

## 1. Back up first

```bash
cp -r ~/getsub-app ~/getsub-app-v1.3-backup
```

If anything below doesn't work out, you can restore from this.

---

## 2. Replace `AndroidManifest.xml`

Termux permission and `<queries>` block are gone (the app no longer needs to see or call Termux). Added: `INTERNET` (the app does its own networking now — it never needed this before, since Termux did all the networking), `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` (for the background fetch), `POST_NOTIFICATIONS` (required at runtime on Android 13+), and the new `SubtitleDownloadService`.

```bash
nano ~/getsub-app/AndroidManifest.xml
```

Replace the entire contents with:

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
        android:icon="@android:drawable/stat_sys_download"
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
            android:theme="@android:style/Theme.NoDisplay"
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

`res/values/strings.xml` is unchanged — leave it as-is.

---

## 3. Add the engine: `SubtitleFetcher.java`

This is the new brain of the app: extracts the video ID, fetches the watch page, pulls `ytInitialPlayerResponse` out of the HTML (balanced-brace scan, not a fragile regex), finds the right caption track, fetches it as `json3`, and runs the same paragraph-formatting rules as the Python original.

```bash
nano ~/getsub-app/src/com/getsub/share/SubtitleFetcher.java
```

```java
package com.getsub.share;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The app's own subtitle-fetching engine (v2.0). Talks to YouTube directly
 * over HTTP -- no Termux, no yt-dlp, no Python. Mirrors the CLI's
 * fetch -> sanitize -> format pipeline in Java so the app can run fully
 * standalone.
 *
 * Relies on YouTube's undocumented ytInitialPlayerResponse page data and
 * the /api/timedtext endpoint -- the same approach used by long-running
 * lightweight caption tools. It is not as resilient as yt-dlp. See the
 * "Known limitations" section of the v2.0 guide.
 */
public class SubtitleFetcher {

    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    public static class Result {
        public final String text;
        public final String filename;

        Result(String text, String filename) {
            this.text = text;
            this.filename = filename;
        }
    }

    public static Result fetch(String sharedText, String langCode) throws Exception {
        String url = extractUrl(sharedText);
        if (url == null) {
            throw new Exception("No URL found in shared text");
        }

        String videoId = extractVideoId(url);
        if (videoId == null) {
            throw new Exception("Could not find a YouTube video ID in the link");
        }

        String html = httpGet("https://www.youtube.com/watch?v=" + videoId + "&hl=en");

        String playerJson = extractBalancedJson(html, "ytInitialPlayerResponse");
        if (playerJson == null) {
            throw new Exception("Could not read video info from YouTube (page format may have changed)");
        }

        JSONObject playerResponse = new JSONObject(playerJson);

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

        String captionJson = httpGet(baseUrl + "&fmt=json3");
        List<String> lines;
        try {
            lines = parseJson3(captionJson);
        } catch (Exception e) {
            lines = new ArrayList<String>();
        }
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

    // ---- parsing helpers (unit-tested against synthetic data before shipping) ----

    static String extractUrl(String sharedText) {
        Matcher m = Pattern.compile("https?://\\S+").matcher(sharedText);
        return m.find() ? m.group() : null;
    }

    static String extractVideoId(String url) {
        Matcher m1 = Pattern.compile("youtu\\.be/([A-Za-z0-9_-]{11})").matcher(url);
        if (m1.find()) return m1.group(1);
        Matcher m2 = Pattern.compile("[?&]v=([A-Za-z0-9_-]{11})").matcher(url);
        if (m2.find()) return m2.group(1);
        Matcher m3 = Pattern.compile("(?:shorts|live|embed)/([A-Za-z0-9_-]{11})").matcher(url);
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
        throw new Exception("No '" + wantLang + "' subtitles found. Available: " + String.join(", ", available));
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

    static List<String> formatParagraphs(List<String> lines) {
        List<String> paragraphs = new ArrayList<String>();
        List<String> current = new ArrayList<String>();
        for (String line : lines) {
            if (line.startsWith(">>") && !current.isEmpty()) {
                paragraphs.add(String.join(" ", current));
                current.clear();
            }
            current.add(line);
            String joined = String.join(" ", current);
            String lastChar = line.isEmpty() ? "" : line.substring(line.length() - 1);
            boolean endsSentence = lastChar.equals(".") || lastChar.equals("?") || lastChar.equals("!");
            if (joined.length() > 250 && endsSentence && !line.startsWith(">>")) {
                paragraphs.add(joined);
                current.clear();
            }
        }
        if (!current.isEmpty()) {
            paragraphs.add(String.join(" ", current));
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

---

## 4. Add the worker: `SubtitleDownloadService.java`

A foreground `Service` replaces Termux's `RunCommandService`. `ShareActivity` finishes instantly (same as before), but this service keeps running in the background to do the real work, because it — not the activity — is what needs to survive after the share sheet closes. It calls `SubtitleFetcher`, saves the result straight to `Download/` via `MediaStore` (no storage permission needed on API 29+), updates `JobStore` directly, and posts a notification — no `PendingIntent` callback dance needed anymore, since we're not crossing into another app.

```bash
nano ~/getsub-app/src/com/getsub/share/SubtitleDownloadService.java
```

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

public class SubtitleDownloadService extends Service {

    public static final String EXTRA_JOB_ID = "job_id";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_LANG = "lang";

    private static final String CHANNEL_ID = "getsub_downloads";
    private static final int NOTIF_ID = 1001;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final int jobId = intent.getIntExtra(EXTRA_JOB_ID, -1);
        final String sharedText = intent.getStringExtra(EXTRA_URL);
        String langExtra = intent.getStringExtra(EXTRA_LANG);
        final String lang = (langExtra == null || langExtra.trim().isEmpty()) ? "en" : langExtra;

        ensureChannel();
        startForeground(NOTIF_ID, buildNotification("Fetching subtitles...", true),
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

        NotificationManager nm = getSystemService(NotificationManager.class);
        Notification finalNotif = buildNotification((success ? "Done: " : "Error: ") + resultText, false);
        if (nm != null) {
            nm.notify(NOTIF_ID, finalNotif);
        }

        stopForeground(Service.STOP_FOREGROUND_DETACH);
        stopSelf();
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

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("GetSub")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
```

---

## 5. Replace `ShareActivity.java`

Down to the essentials: log the job, hand it to the service, get out of the way. No more `PendingIntent`, no more `com.termux.*` class names anywhere in this file.

```bash
nano ~/getsub-app/src/com/getsub/share/ShareActivity.java
```

```java
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
```

---

## 6. Small edit to `MainActivity.java`

Android 13+ requires asking for notification permission at runtime, or the completion notifications silently never show (the job still completes and shows up in the list fine either way — this only affects the notification). Add this as the very first lines inside `onCreate`, right after `super.onCreate(savedInstanceState);`:

```bash
nano ~/getsub-app/src/com/getsub/share/MainActivity.java
```

```java
if (android.os.Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
    requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
}
```

(Fully-qualified names on purpose, so you don't have to touch the imports at the top of the file — just paste those 4 lines in.) Nothing else in `MainActivity.java` changes; it still just polls `JobStore` every 2 seconds and shows the list, exactly like v1.3.

`JobStore.java` is **completely unchanged** — leave it exactly as it is.

---

## 7. Delete `ResultReceiver.java`

It existed only to catch Termux's `RUN_COMMAND` callback. Nothing calls into it anymore — the service updates `JobStore` directly now.

```bash
rm ~/getsub-app/src/com/getsub/share/ResultReceiver.java
```

---

## 8. Rebuild and install

`build.sh` needs **zero changes** — it already compiles every `.java` file under `src/com/getsub/share/` via glob, so it'll pick up the two new files and no longer find the deleted one automatically.

```bash
cd ~/getsub-app && bash build.sh
cp build/apk/app.apk ~/storage/downloads/getsub-app.apk
termux-open ~/storage/downloads/getsub-app.apk
```

Same keystore, so it installs as a clean upgrade. One difference from before: there's no more "Additional permissions" hunting in Settings — `POST_NOTIFICATIONS` is a normal runtime prompt, so Android will just ask you directly the first time you open the app.

---

## 9. Test it

1. Open GetSub once first (so it can ask for notification permission).
2. Share a YouTube link to GetSub.
3. Watch the notification go **"Fetching subtitles..."** → **"Done: `<title>.txt`"** (or **"Error: ..."**).
4. Check `Download/` (or open GetSub's list — tapping the notification takes you there) for the file.

---

## 10. Known limitations & the honest trade-off

- **Two engines now exist.** The CLI/Share→Termux path still uses the Python+yt-dlp pipeline; the app now has its own separate Java pipeline. They're not the same code anymore, so a future tweak to one (say, the paragraph-length threshold) won't automatically apply to the other. Minor, but worth knowing.
- **Undocumented endpoints.** `ytInitialPlayerResponse` and `/api/timedtext` aren't official, versioned APIs — Google can change them without notice. This is the same category of risk yt-dlp deals with constantly (that's *why* yt-dlp needs frequent updates), except now there's no upstream project doing that maintenance for the app's path. Practically: YouTube has also been rolling out stricter "proof of origin" verification on some request types, including caption fetches specifically, for some client contexts — so it's plausible (not certain) that some fetches fail with the "YouTube returned no caption data" error even when the video clearly has captions. If that becomes a regular problem rather than an occasional one, the CLI/Share→Termux path is your reliable fallback in the meantime, since yt-dlp's ecosystem actively tracks and works around this.
- **If it becomes a real problem:** the most promising fix that still avoids Termux is doing the fetch inside a hidden Android `WebView` instead of a raw HTTP request — a `WebView` actually executes YouTube's real page JavaScript, which is a much closer match to what a genuine browser does and more likely to satisfy whatever verification YouTube asks for. It's a meaningfully bigger chunk of code (async page-load handling, JS-to-Java callback bridging), so I scoped it out of this pass, but it's a natural v2.1 if you hit reliability trouble. Say the word and I'll build it out.
- **No language picker still.** Same limitation as v1.3 — the share intent only carries the link, so the app path always requests `en`. `findTrack` will tell you what languages *are* available if `en` isn't one of them.

---

## 11. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `Error: No 'en' subtitles found. Available: ...` | Video has no English captions. Available languages are listed right in the message. |
| `Error: YouTube returned no caption data...` | Likely the undocumented-endpoint fragility described in §10. Try again shortly, or use Share→Termux for that link. |
| `Error: HTTP 404/403 fetching ...` | Network hiccup, or the video is private/deleted/region-blocked. |
| `Error: YouTube: LOGIN_REQUIRED` (or similar) | Age-restricted or otherwise gated video — not fetchable without a signed-in session, same limitation the CLI has. |
| No notification appears, but the job still shows Done/Error in the app | `POST_NOTIFICATIONS` was never granted — open GetSub once and accept the prompt, or enable notifications for GetSub in system Settings. |
| Build fails on `FOREGROUND_SERVICE_TYPE_DATA_SYNC` or similar | Confirm `~/android-sdk/android.jar` is genuinely platform-33 (or newer) — that constant needs API 29+, which 33 satisfies. |
| App installs but immediately force-closes on share | Double check `AndroidManifest.xml` was replaced in full (not merged/partial) — a leftover Termux `<queries>` block combined with the removed permission is a common copy-paste slip. |
| File doesn't show up in a gallery/file app right away | Some file managers cache the `MediaStore` index — pull to refresh, or check the Downloads app directly. |

---

## 12. File map (v2.0 delta)

| Path | Status |
|---|---|
| `$PREFIX/bin/getsub` | Unchanged — still used by CLI + Share→Termux |
| `~/bin/termux-url-opener` | Unchanged — still used by Share→Termux |
| `~/getsub-app/AndroidManifest.xml` | **Updated** — Termux permission/queries removed; Internet, foreground-service, notification permissions added |
| `~/getsub-app/src/com/getsub/share/ShareActivity.java` | **Rewritten** — starts `SubtitleDownloadService` directly, no Termux involved |
| `~/getsub-app/src/com/getsub/share/SubtitleDownloadService.java` | **New** — foreground service: runs the fetch, saves the file, updates `JobStore`, posts the result notification |
| `~/getsub-app/src/com/getsub/share/SubtitleFetcher.java` | **New** — the app's own subtitle-fetching engine |
| `~/getsub-app/src/com/getsub/share/MainActivity.java` | **Small edit** — added notification-permission request |
| `~/getsub-app/src/com/getsub/share/JobStore.java` | Unchanged |
| `~/getsub-app/src/com/getsub/share/ResultReceiver.java` | **Deleted** — no longer needed |
| `~/getsub-app/build.sh` | Unchanged |
| Android `Download/` (via `MediaStore`) | Where the Share→GetSub app path now saves files |
| `~/storage/downloads/*.txt` | Where CLI / Share→Termux still save files (same folder, different write path) |

---

## 13. Changelog

- **v2.0:** The GetSub app's Share entry point is now fully independent of Termux at runtime. New `SubtitleFetcher` (Java port of the fetch/sanitize/format pipeline, talking to YouTube's page data and caption endpoint directly) and `SubtitleDownloadService` (foreground service replacing the `RUN_COMMAND` bridge to Termux — saves via `MediaStore`, updates `JobStore` directly, posts its own notification). `ShareActivity` simplified accordingly. `ResultReceiver` removed (no longer needed without a cross-app callback). `AndroidManifest.xml`: dropped `RUN_COMMAND` permission and the Termux `<queries>` entry; added `INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`. `minSdkVersion` raised 24 → 29 to use `MediaStore.Downloads` exclusively. CLI and Share→Termux paths unchanged and remain the more battle-tested fallback.
- **v1.3:** (see previous capsule)

---

## 14. v2.0.1 fix — "YouTube returned no caption data" (Sep 17, 2026)

- **Symptom:** Share → GetSub on `https://www.youtube.com/watch?v=GYo-NG7tbok` returned `[Error] YouTube returned no caption data`. Reproduced live: watch-page caption `baseUrl` returns HTTP 200 with empty body for automated requests.
- **Fix (`src/com/getsub/share/SubtitleFetcher.java`):** primary path is now Innertube `youtubei/v1/player` with the ANDROID client (same approach yt-dlp uses); watch-page scrape kept as fallback. Caption download tries `json3` + `srv1/srv3` XML + `vtt` with payload auto-detection (`parseAuto`); added `parseTimedtextXml`, `parseVtt`, `withFmt`, `httpPostJson`; hardened `extractUrl`/`extractVideoId` (handles spaced `watch?v= ID` shares).
- **Verified:** 11/11 Java checks pass incl. live fetch of `GYo-NG7tbok` → `Actually a Wild Situation.txt` (21,452 chars). Rebuilt APK (25K, `apksigner verify` OK), saved to `Download/` as `getsub-app.apk` + transcript.

## 15. README + AI maintenance rule

- Added `~/getsub-app/README.md` (project overview, build, layout).
- **Mandatory rule for any AI working on this project:** every time any change is applied, update `PROJECT_CAPSULE.md` accordingly, save the updated capsule to the phone's Download folder (`~/storage/downloads/PROJECT_CAPSULE.md`), and report the changes to the user (what/why, files touched, verification, next steps). A code change without a capsule update + Download-folder copy is incomplete.

## 16. v2.0.2 fix — result notification hung forever (Sep 17, 2026)

- **Symptom:** the GetSub notification stayed in the shade indefinitely after the download finished ("Fetching..." never cleared / Done never dismissed).
- **Root cause (`src/com/getsub/share/SubtitleDownloadService.java`):** `runJob` posted the final notification and then called `stopForeground(STOP_FOREGROUND_DETACH)` — DETACH intentionally leaves the notification posted, and nothing ever cancelled it (`setAutoCancel` only fires on tap).
- **Fix:** `runJob` now calls `stopForeground(STOP_FOREGROUND_REMOVE)` *before* posting the final result as a normal notification; result notifications also get `setTimeoutAfter(20000)` so the system auto-dismisses them after 20s (tap still dismisses immediately and opens the app).
- **Verified:** clean `build.sh` rebuild, `apksigner verify` OK, fresh `getsub-app.apk` saved to `Download/`.

## 17. v2.1 feature — manual link entry in the app (Sep 17, 2026)

- **What:** `MainActivity` now has a "Paste YouTube link" text field + "Get" button above the job list, so transcripts can be started from inside the app without using the share sheet.
- **How (`src/com/getsub/share/MainActivity.java`):** the button runs the same flow as `ShareActivity` — `JobStore.addJob` → `startForegroundService(SubtitleDownloadService)` with the pasted text (`en`) → clears the field → the 2s poller shows `Queued → Done/Error`. Empty input shows "Paste a YouTube link first". No manifest/build changes needed.
- **Verified:** clean `build.sh` rebuild, `apksigner verify` OK, fresh `getsub-app.apk` saved to `Download/`.

## 18. v2.2 feature — live downloading progress bar (Sep 17, 2026)

- **What:** `MainActivity` now shows a live progress indicator while downloads are fetching: a horizontal progress bar under the link field (visible while any job is `Queued`) plus a spinner on each actively-fetching row. Both clear automatically when jobs reach `Done`/`Error` via the poller (1s since v2.2.1).
- **Design note (reverses the old "no progress bar" constraint):** the bar is *indeterminate* on purpose — a subtitle fetch is one sub-second HTTP request with no meaningful percentage, so a 0-100% bar would be faked. It reflects real live job state instead. Implemented with a custom `BaseAdapter` (`JobAdapter`, replaces `SimpleAdapter`) in `src/com/getsub/share/MainActivity.java`; no service/manifest/build changes.
- **Verified:** clean `build.sh` rebuild, `apksigner verify` OK, fresh `getsub-app.apk` saved to `Download/`.

## 19. v2.2.1 fix — stale "downloading" notification linger (Sep 17, 2026)

- **Symptom:** the notification still showed "downloading" for a few seconds after the job finished before disappearing.
- **Causes:** (1) the final result reused the same notification id just killed by `stopForeground(REMOVE)` — some devices show the stale state or swallow the re-post; (2) the in-app list/progress bar refreshed on a 2s poller, lagging up to 2s behind `Done`.
- **Fix:** result notification now posts under a fresh id (`RESULT_NOTIF_ID = 1002`) with an explicit `cancel(ONGOING_NOTIF_ID)` first (`src/com/getsub/share/SubtitleDownloadService.java`); list poller tightened 2s → 1s (`MainActivity.java`, still only while visible — no battery impact).
- **Verified:** clean `build.sh` rebuild, `apksigner verify` OK, fresh `getsub-app.apk` saved to `Download/`.

## 20. Maintenance rule extended — full-source MD export (Sep 18, 2026)

- The README's AI maintenance rule now has 4 steps: update capsule → copy capsule to `Download/` → regenerate `Download/getsub-app-full-source.md` → report. `README.md` updated accordingly; no code changes in this step.

## 21. v2.3 redesign — dark-theme MainActivity (Sep 18, 2026)

- **What:** `MainActivity.java` replaced wholesale with a dark-theme redesign (supplied via `Download/MainActivity.java`, 461 lines). Palette: navy page `#0F0F14`, surface `#1A1A24`, cards `#21212F`, accent purple `#6C63FF` — all named constants at the top of the class. Branded header (accent dot + name + tagline) containing the progress bar (thin 3dp accent-tinted, above the input) and the URL field + Get button. Job rows are rounded cards with a 4dp status stripe (amber/green/red), pill status badge, middle-truncated URL, colour-coded result line. New centered empty state (arrow glyph + heading + body). Refresh button removed (1s poller covers it).
- **Kept intact:** notification-permission prompt, 1s poller, `JobStore`/`SubtitleDownloadService` flow (`en`), empty-view wiring.
- **Two micro-tweaks applied on top of the delivered file:** restored the "fetching subtitles..." toast on Get (parity with v2.1), and gave the empty view weight so it truly centers in remaining space. Previous version backed up at `/tmp/MainActivity.v2_2_1.bak` (not in repo, so `build.sh` ignores it).
- **Verified:** clean `build.sh` rebuild, `apksigner verify` OK, fresh `getsub-app.apk` saved to `Download/`. All APIs used are minSdk-29-safe.

## 22. v2.4 feature — custom launcher icon (Sep 18, 2026)

- **What:** new `res/drawable/ic_launcher.png` (192×192): brand-purple gradient (`#7C74FF → #4842C4`, matches the v2.3 UI accent) with a white download arrow over transcript lines. Full-bleed square so launchers can apply their own mask. Manifest `android:icon` switched from `@android:drawable/stat_sys_download` to `@drawable/ic_launcher`; no new permissions/deps. `README.md` layout table updated.
- **How:** generated on-device with a dependency-free Python script (`struct`+`zlib` only, 2× supersampled for smooth edges; Pillow unavailable) — script kept at `/tmp/getsub-icon/make_icon.py`, re-run to tweak. aapt2 picks the drawable up automatically; APK grew 25K → 33K.
- **Note:** `getsub-app-full-source.md` covers the 8 text sources; the binary PNG is excluded (rebuild needs it from the repo).
- **Verified:** clean `build.sh` rebuild, `apksigner verify` OK, fresh `getsub-app.apk` saved to `Download/`.

## 23. v2.4.1 fix — list snapped back to top while scrolling (Sep 18, 2026)

- **Symptom:** scrolling Recent Downloads jumped back to the first item on release.
- **Root cause:** `loadJobs()` called `listView.setAdapter(new JobAdapter(...))` on every 1s poll — replacing the adapter resets scroll position (and rebuilds all rows).
- **Fix (`MainActivity.java`):** one `JobAdapter` instance now lives for the whole activity (created in `onCreate`); each poll calls `adapter.updateJobs(jobs)` (clear + addAll + `notifyDataSetChanged()`), which refreshes content in place and keeps scroll state. Side benefit: far less view churn every second.
- **Verified:** clean `build.sh` rebuild, `apksigner verify` OK, fresh `getsub-app.apk` saved to `Download/`.

## 24. v2.5 feature — swipe-to-delete + clear history (Sep 18, 2026)

- **What:** rows slide left/right to delete (past 1/3 width, fades out; release early snaps back; vertical drags still scroll), plus a red CLEAR button next to RECENT DOWNLOADS that asks for confirmation first. Clearing/deleting only removes list entries — saved `.txt` files in `Download/` are kept (stated in the dialog).
- **How:** `JobStore` gained `deleteJob(ctx, id)` + `clearAll(ctx)`. `MainActivity` gained `attachSwipeToDelete()` (touch listener with horizontal-vs-vertical disambiguation, snap-back/fling-off animations) and `confirmClearHistory()` (count-aware `AlertDialog`, empty-list toast). The 1s poller skips adapter updates mid-swipe (`userSwiping` flag) so a refresh can't rip the row away mid-gesture; the release handler refreshes anyway.
- **Verified:** clean `build.sh` rebuild, `apksigner verify` OK, fresh `getsub-app.apk` saved to `Download/`.

## 25. v2.5.1 refinement — right-only swipe, hint, CLEAR ALL (Sep 18, 2026)

- **What:** swipe-to-delete now works left-to-right only (leftward drags never move the row); a muted hint line under the section header reads "Slide a row → to remove it" (hidden when the list is empty so the empty state stays clean); the button is now CLEAR ALL with dialog title "Clear all history?" and "Clear all" action — same red badge styling, UI harmony kept.
- **Verified:** clean `build.sh` rebuild, `apksigner verify` OK, fresh `getsub-app.apk` saved to `Download/`.

## 26. v2.5.2 removal — swipe-to-delete taken out, back to CLEAR (Sep 18, 2026)

- **What:** swipe-to-delete removed entirely per user request — rows no longer move; only the CLEAR button remains (wording reverted to CLEAR / "Clear history?" / "Clear"). Also removed now-unused `JobStore.deleteJob`, the swipe hint, and the `MotionEvent` plumbing. Verified zero remaining references (`grep` clean).
- **Verified:** clean `build.sh` rebuild, `apksigner verify` OK, fresh `getsub-app.apk` saved to `Download/`.

## 27. Cancelled experiment — GetSub Personal side-by-side variant (Sep 20, 2026)

- **What happened:** a full project copy (`/root/getsub-personal`, package `com.getsub.personal`, launcher name "GetSub Personal") was created so future builds would install as a separate app. The user then cancelled it before it was ever built or installed.
- **Reversal (verified):** `/root/getsub-personal` deleted; `Download/getsub-personal.apk` and `Download/getsub-personal-full-source.md` removed (neither was ever built — no separate app ever reached the phone). Original project untouched throughout (`com.getsub.share`, all 5 sources intact).
- No rebuild needed (no code changes in this step); `getsub-app-full-source.md` regenerated fresh.

## 28. v2.5.3 robustness batch + GitHub repo workflow (Sep 20, 2026)

- **Repo workflow:** the project is now maintained through GitHub (`nrmnnkkhh-svg/getsub-app`, branch `master`). Hygiene commit alongside this work: capsule synced (§27 restored, §21 moved back between §20 and §22 — pure text move, content identical), `.gitignore` added, **`build/` untracked** (artifacts regenerate via `bash build.sh`; pulling that change removes `build/` from working copies — expected). `getsub-app-full-source.md` is now regenerated into the **repo root** as the standing copy of the phone-Download export; phone-side copies happen when the user syncs.
- **`SubtitleFetcher.parseAuto` hardened (bug fix):** every parser is gated on payload markers — json3 `{`, XML `<?xml`/`<transcript`, VTT `WEBVTT` or a real cue timing (`00:00:01.234 -->`; a bare `-->` no longer qualifies because HTML comments contain it). Previously the ungated last-resort `parseVtt` accepted ANY text, so an HTTP-200 junk body (HTML error page, broken JSON, plain text) was silently saved as a garbage `.txt` marked **Done** (confirmed empirically: `<p>Sorry…</p>` became a caption line). Junk now yields an empty list → next `fmt` candidate → the honest "YouTube returned no caption data" error if none pan out. Real payloads unaffected.
- **`parseTimedtextXml` extended:** matches srv1 `<text start=…>` elements as well as srv3 `<p t=…>` (previously srv1 only survived via the accidental tag-stripping in the VTT fallback). Tag matching uses word boundary + backreference (`<(p|text)(\s[^>]*)?>…</\1>`) so look-alikes such as `<pre>` can't pair.
- **Concurrent downloads fixed (`SubtitleDownloadService`):** result notifications use per-job ids `2000+jobId` (was one shared id — two quick shares overwrote each other's results); foreground state and the ongoing notification survive until the **last** active job finishes (`AtomicInteger activeJobs`; previously the first completion called `stopForeground(REMOVE)`, stripping process protection from still-running jobs → kill risk → stuck "Queued"); ongoing text shows "(N in parallel)" when >1; teardown uses `stopSelf(latestStartId)` so a job starting mid-teardown isn't killed (race-safe).
- **Null-intent guard:** `onStartCommand` stops cleanly instead of NPE-crashing on a (theoretical, `START_NOT_STICKY`) null redelivery.
- **Verification (sandbox):** parser suite 40/40 (six new gating checks: HTML page, broken json3, srv1 `<text>`, srv1 dispatch, `<pre>` boundary, headerless-VTT timings); Java ≡ Python paragraph-formatter golden diff byte-identical (80 lines → 18 paragraphs); full local build aapt2→javac→d8→apksigner green (33K APK). Live YouTube fetch is not verifiable from the dev sandbox (datacenter IP bot-blocked on every client, probed); the engine's network paths were last live-verified on-phone Sep 17 → **after installing this build, do one share test on the phone** (ideally two links back-to-back to exercise the parallel path).

## 29. CI pipeline — GitHub Actions build + tests + emulator UI checks (Sep 21, 2026)

- **What:** `.github/workflows/ci.yml` runs on every push to `master` (plus manual "Run workflow" in the Actions tab). One job on `ubuntu-latest`: (1) pinned SDK packages (`platforms;android-33`, `build-tools;33.0.2`); (2) APK build with the project's **own `build.sh`**; (3) parser suite (`ci/run_parser_tests.sh` — 40 synthetic checks + byte-level golden diff vs the original Python CLI formatter); (4) a real **Android 13 emulator** (KVM-accelerated AOSP x86_64, API 33) where `ci/emulator_ui_test.py` drives the installed app over pure `adb` + `uiautomator dump`: launch/focus → home-UI elements → **paste flow** (type URL → Get → job row → terminal status → result line) → **share-intent flow** (ACTION_SEND → ShareActivity → second job) → **CLEAR flow** (confirm dialog → empty state) → relaunch persistence. Screenshots of every step, `report.txt`, logcat, and the APK upload as the `getsub-ci-artifacts` artifact.
- **`build.sh` became toolchain-agnostic (Termux behavior unchanged):** env overrides `GETSUB_ANDROID_JAR`, `GETSUB_KEYSTORE`, `GETSUB_KEYSTORE_ALIAS`, `GETSUB_KEYSTORE_PASS`; Step 4 auto-detects `dx` (Termux → identical to before) and falls back to `d8` (CI/desktop build-tools). CI signs with an **ephemeral per-run key** → CI APK artifacts install only over an uninstalled GetSub (signature mismatch); a stable CI signing key via GitHub secret is the noted Stage-3 option.
- **Test harness now repo-resident:** `tests/TestParsers.java` (40 checks incl. the v2.5.3 gating cases) + `ci/run_parser_tests.sh` (compiles against android.jar exactly like build.sh, runs against a real org.json jar auto-fetched from Maven Central, seeded synthetic caption lines, Java-vs-Python golden diff).
- **Emulator driver design:** no gradle, no androidx, no on-device test APK — matches the zero-dependency build philosophy. `python3 ci/emulator_ui_test.py --selftest` verifies the driver's dump-parsing/node-finding/tap-math offline against synthetic uiautomator XML (14/14 in the dev sandbox; the only way to test the driver where no KVM exists).
- **Expected CI outcome of the live-fetch steps:** GitHub runners are datacenter IPs → YouTube bot check → jobs terminate as `ERROR | YouTube: Sign in to confirm…`. The tests assert *terminal status + result line*, so DONE **or** ERROR both pass — they verify the app's wiring, not YouTube's mood. Deterministic success-path testing (mock caption server + `/etc/hosts` remap on the rooted AOSP image) is planned Stage 2.
- **Verification (sandbox, pre-push):** modified `build.sh` via d8 branch → BUILD SUCCESSFUL (33K APK, `apksigner verify` OK); parser suite complete, GOLDEN DIFF IDENTICAL; driver selftest 14/14; `ci.yml` parses (1 job, 8 steps, triggers push/pull_request/workflow_dispatch). First Actions-side run: this commit.
- **Cost & cadence:** free (public repo); ~15–25 min per run (emulator boot dominates); `concurrency` cancels superseded runs on rapid pushes.

