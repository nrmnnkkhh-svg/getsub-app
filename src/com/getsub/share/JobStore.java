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
