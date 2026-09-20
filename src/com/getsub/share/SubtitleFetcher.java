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

    /** Detect payload type (json3 vs timedtext XML vs VTT) and parse. */
    static List<String> parseAuto(String content) {
        String trimmed = content.trim();
        if (trimmed.length() == 0) {
            return new ArrayList<String>();
        }
        try {
            if (trimmed.startsWith("{")) {
                List<String> lines = parseJson3(trimmed);
                if (!lines.isEmpty()) return lines;
            } else if (trimmed.startsWith("<")) {
                List<String> lines = parseTimedtextXml(trimmed);
                if (!lines.isEmpty()) return lines;
            } else if (trimmed.startsWith("WEBVTT") || trimmed.contains("-->")) {
                List<String> lines = parseVtt(trimmed);
                if (!lines.isEmpty()) return lines;
            }
        } catch (Exception ignored) {
            // fall through to trying every parser below
        }
        // Last resort: try each parser regardless of sniffing.
        try {
            List<String> lines = parseJson3(content);
            if (!lines.isEmpty()) return lines;
        } catch (Exception ignored) {
        }
        try {
            List<String> lines = parseTimedtextXml(content);
            if (!lines.isEmpty()) return lines;
        } catch (Exception ignored) {
        }
        try {
            List<String> lines = parseVtt(content);
            if (!lines.isEmpty()) return lines;
        } catch (Exception ignored) {
        }
        return new ArrayList<String>();
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
     * Parse YouTube timedtext XML (srv1/srv3 — what auto-generated tracks
     * actually return even when asked for json3). Example:
     * {@code <p t="0" d="5920"><s ac="0">hello</s><s> world</s></p>}
     */
    static List<String> parseTimedtextXml(String xml) {
        List<String> lines = new ArrayList<String>();
        Matcher p = Pattern.compile("<p[^>]*>(.*?)</p>", Pattern.DOTALL).matcher(xml);
        while (p.find()) {
            String inner = p.group(1);
            // Strip inner tags (<s>, <w>, etc.) but keep their text.
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
