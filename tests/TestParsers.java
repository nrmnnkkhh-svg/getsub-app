package com.getsub.share;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/** Sandbox-only verification harness (never shipped in src/). Exercises every
 *  parsing helper of SubtitleFetcher against synthetic YouTube-shaped data. */
public class TestParsers {

    static int pass = 0, fail = 0;

    static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("PASS  " + name); }
        else    { fail++; System.out.println("FAIL  " + name); }
    }

    public static void main(String[] args) throws Exception {
        // ---- extractUrl ----
        check("extractUrl: plain", "https://youtu.be/dQw4w9WgXcQ".equals(
                SubtitleFetcher.extractUrl("watch this https://youtu.be/dQw4w9WgXcQ ok")));
        check("extractUrl: trailing dot stripped", "https://youtu.be/dQw4w9WgXcQ".equals(
                SubtitleFetcher.extractUrl("https://youtu.be/dQw4w9WgXcQ.")));
        check("extractUrl: wrapped in parens", "https://www.youtube.com/watch?v=dQw4w9WgXcQ".equals(
                SubtitleFetcher.extractUrl("(https://www.youtube.com/watch?v=dQw4w9WgXcQ)")));
        check("extractUrl: none -> null", SubtitleFetcher.extractUrl("no link here") == null);

        // ---- extractVideoId ----
        check("videoId: watch?v=", "dQw4w9WgXcQ".equals(
                SubtitleFetcher.extractVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42s")));
        check("videoId: youtu.be + query", "dQw4w9WgXcQ".equals(
                SubtitleFetcher.extractVideoId("https://youtu.be/dQw4w9WgXcQ?si=abc")));
        check("videoId: shorts", "dQw4w9WgXcQ".equals(
                SubtitleFetcher.extractVideoId("https://www.youtube.com/shorts/dQw4w9WgXcQ")));
        check("videoId: live", "dQw4w9WgXcQ".equals(
                SubtitleFetcher.extractVideoId("https://www.youtube.com/live/dQw4w9WgXcQ")));
        check("videoId: embed", "dQw4w9WgXcQ".equals(
                SubtitleFetcher.extractVideoId("https://www.youtube.com/embed/dQw4w9WgXcQ")));
        check("videoId: spaced share (v2.0.1)", "GYo-NG7tbok".equals(
                SubtitleFetcher.extractVideoId("https://www.youtube.com/watch?v= GYo-NG7tbok")));
        check("videoId: non-youtube -> null",
                SubtitleFetcher.extractVideoId("https://example.com/nothing") == null);

        // ---- extractBalancedJson: marker decoy + braces/escapes inside strings ----
        String realJson = "{\"a\":{\"b\":\"}decoy{\",\"c\":\"quote\\\"esc\"},\"d\":[1,2]}";
        String html = "<script>var s=\"ytInitialPlayerResponse decoy\";"
                + "var ytInitialPlayerResponse = " + realJson + ";</script>";
        String extracted = SubtitleFetcher.extractBalancedJson(html, "ytInitialPlayerResponse");
        boolean jsonOk = false;
        try {
            JSONObject o = new JSONObject(extracted);
            jsonOk = "}decoy{".equals(o.getJSONObject("a").getString("b"))
                    && "quote\"esc".equals(o.getJSONObject("a").getString("c"))
                    && o.getJSONArray("d").length() == 2;
        } catch (Exception ignored) {
        }
        check("balancedJson: decoys + string braces/escapes", jsonOk);
        check("balancedJson: missing marker -> null",
                SubtitleFetcher.extractBalancedJson("<html>nothing</html>", "ytInitialPlayerResponse") == null);

        // ---- parseJson3 ----
        String j3 = "{\"events\":["
                + "{\"tStartMs\":0,\"segs\":[{\"utf8\":\"Hello \"},{\"utf8\":\"world.\\n\"}]},"
                + "{\"tStartMs\":500,\"dDurationMs\":100},"
                + "{\"tStartMs\":600,\"segs\":[{\"utf8\":\"Hello world.\\n\"}]},"
                + "{\"tStartMs\":900,\"segs\":[{\"utf8\":\"Second\"},{\"utf8\":\" line!\"}]}"
                + "]}";
        List<String> j3Lines = SubtitleFetcher.parseJson3(j3);
        check("json3: segs joined, newline->space, skip no-seg, dedupe",
                j3Lines.size() == 2 && "Hello world.".equals(j3Lines.get(0))
                        && "Second line!".equals(j3Lines.get(1)));

        // ---- parseTimedtextXml ----
        String xml = "<?xml version=\"1.0\"?><transcript>"
                + "<p t=\"0\" d=\"5920\" w=\"0\"><s ac=\"0\">hello</s><s> world</s></p>"
                + "<p t=\"6000\" d=\"3000\">second &amp; more</p>"
                + "<p t=\"9000\" d=\"1000\">   </p>"
                + "</transcript>";
        List<String> xmlLines = SubtitleFetcher.parseTimedtextXml(xml);
        check("timedtextXml: inner tags stripped, entities decoded, blank skipped",
                xmlLines.size() == 2 && "hello world".equals(xmlLines.get(0))
                        && "second & more".equals(xmlLines.get(1)));

        // ---- parseVtt ----
        String vtt = "WEBVTT\nKind: captions\nLanguage: en\n\n"
                + "00:00:00.000 --> 00:00:02.000\n<c>hello</c> world\n\n"
                + "00:00:02.000 --> 00:00:04.000 align:middle\nhello world\n\n"
                + "00:00:04.000 --> 00:00:06.000\nSecond &amp; line!\n";
        List<String> vttLines = SubtitleFetcher.parseVtt(vtt);
        check("vtt: headers/cues stripped, tags+entities handled, dedupe",
                vttLines.size() == 2 && "hello world".equals(vttLines.get(0))
                        && "Second & line!".equals(vttLines.get(1)));

        // ---- parseAuto dispatch ----
        check("parseAuto: json3", SubtitleFetcher.parseAuto(j3).equals(j3Lines));
        check("parseAuto: xml", SubtitleFetcher.parseAuto(xml).equals(xmlLines));
        check("parseAuto: vtt", SubtitleFetcher.parseAuto(vtt).equals(vttLines));
        check("parseAuto: empty -> empty", SubtitleFetcher.parseAuto("  ").isEmpty());
        check("parseAuto: garbage -> empty", SubtitleFetcher.parseAuto("total garbage").isEmpty());

        // ---- v2.5.3 gating: junk must never become "captions" ----
        check("parseAuto: HTML page -> empty", SubtitleFetcher.parseAuto(
                "<!DOCTYPE html><html><body><!-- nav --> <p>Sorry, an error occurred.</p>"
                        + "<pre>trace</pre></body></html>").isEmpty());
        check("parseAuto: broken json3 -> empty",
                SubtitleFetcher.parseAuto("{\"events\": [ broken").isEmpty());
        String srv1 = "<?xml version=\"1.0\" encoding=\"utf-8\" ?><transcript>"
                + "<text start=\"0\" dur=\"2.5\">hello <b>world</b></text>"
                + "<text start=\"3\" dur=\"2\">second &amp; more</text>"
                + "<text start=\"6\" dur=\"1\" />"
                + "</transcript>";
        List<String> srv1Lines = SubtitleFetcher.parseTimedtextXml(srv1);
        check("timedtextXml: srv1 <text> elements",
                srv1Lines.size() == 2 && "hello world".equals(srv1Lines.get(0))
                        && "second & more".equals(srv1Lines.get(1)));
        check("parseAuto: srv1 dispatch", SubtitleFetcher.parseAuto(srv1).equals(srv1Lines));
        check("timedtextXml: <pre> not matched as <p>",
                SubtitleFetcher.parseTimedtextXml("<transcript><pre>nope</pre></transcript>").isEmpty());
        check("parseAuto: headerless VTT with real timings",
                SubtitleFetcher.parseAuto("00:00:01.234 --> 00:00:02.000\nhello there\n")
                        .equals(java.util.Collections.singletonList("hello there")));

        // ---- withFmt ----
        check("withFmt: appends to query",
                "https://x/api?v=1&fmt=json3".equals(SubtitleFetcher.withFmt("https://x/api?v=1", "json3")));
        check("withFmt: adds query when none",
                "https://x/t?fmt=json3".equals(SubtitleFetcher.withFmt("https://x/t", "json3")));
        check("withFmt: replaces existing fmt",
                "https://x/t?a=1&fmt=vtt".equals(SubtitleFetcher.withFmt("https://x/t?a=1&fmt=srv1", "vtt")));

        // ---- findTrack ----
        JSONArray asrFirst = new JSONArray();
        asrFirst.put(new JSONObject("{\"languageCode\":\"en\",\"kind\":\"asr\",\"baseUrl\":\"u1\"}"));
        asrFirst.put(new JSONObject("{\"languageCode\":\"en\",\"baseUrl\":\"u2\"}"));
        JSONArray manualFirst = new JSONArray();
        manualFirst.put(new JSONObject("{\"languageCode\":\"en\",\"baseUrl\":\"u2\"}"));
        manualFirst.put(new JSONObject("{\"languageCode\":\"en\",\"kind\":\"asr\",\"baseUrl\":\"u1\"}"));
        JSONArray asrOnly = new JSONArray();
        asrOnly.put(new JSONObject("{\"languageCode\":\"en\",\"kind\":\"asr\",\"baseUrl\":\"u1\"}"));
        check("findTrack: manual beats asr (asr first)",
                "u2".equals(SubtitleFetcher.findTrack(asrFirst, "en").getString("baseUrl")));
        check("findTrack: manual wins (manual first)",
                "u2".equals(SubtitleFetcher.findTrack(manualFirst, "en").getString("baseUrl")));
        check("findTrack: asr used when only option",
                "u1".equals(SubtitleFetcher.findTrack(asrOnly, "en").getString("baseUrl")));
        JSONArray noEn = new JSONArray();
        noEn.put(new JSONObject("{\"languageCode\":\"es\",\"baseUrl\":\"u3\"}"));
        noEn.put(new JSONObject("{\"languageCode\":\"fr\",\"baseUrl\":\"u4\"}"));
        String msg = "";
        try {
            SubtitleFetcher.findTrack(noEn, "en");
        } catch (Exception e) {
            msg = e.getMessage();
        }
        check("findTrack: missing lang lists available",
                msg.contains("No 'en'") && msg.contains("es") && msg.contains("fr"));

        // ---- sanitizeFilename ----
        check("filename: forbidden chars -> spaces",
                "My Video a b c d e f".equals(SubtitleFetcher.sanitizeFilename("My/Video: a*b?\"c<d>e|f")));
        StringBuilder longTitle = new StringBuilder();
        for (int i = 0; i < 40; i++) longTitle.append("word ");
        check("filename: capped at 150", SubtitleFetcher.sanitizeFilename(longTitle.toString()).length() <= 150);
        check("filename: empty -> subtitles", "subtitles".equals(SubtitleFetcher.sanitizeFilename("   ")));
        check("filename: all-invalid -> subtitles", "subtitles".equals(SubtitleFetcher.sanitizeFilename("///:::")));

        // ---- unescapeHtml ----
        check("unescapeHtml: named + numeric entities",
                "&<>\"'AB ".equals(SubtitleFetcher.unescapeHtml("&amp;&lt;&gt;&quot;&#39;&#x41;&#66;&nbsp;")));

        // ---- fetch(): no video id error path (no network hit) ----
        String fetchMsg = "";
        try {
            SubtitleFetcher.fetch("hello world, no link", "en");
        } catch (Exception e) {
            fetchMsg = e.getMessage();
        }
        check("fetch: no-id error message",
                "Could not find a YouTube video ID in the link".equals(fetchMsg));

        // ---- formatParagraphs golden diff (args: lines.txt java_out.txt) ----
        if (args.length >= 2) {
            List<String> lines = new ArrayList<String>();
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(args[0]), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) lines.add(line);
            r.close();
            List<String> paras = SubtitleFetcher.formatParagraphs(lines);
            Writer w = new OutputStreamWriter(new FileOutputStream(args[1]), "UTF-8");
            for (String p : paras) w.write(p + "\n");
            w.close();
            System.out.println("INFO  formatParagraphs: " + lines.size() + " lines -> "
                    + paras.size() + " paragraphs (golden diff runs in shell)");
        }

        System.out.println();
        System.out.println("RESULT: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
