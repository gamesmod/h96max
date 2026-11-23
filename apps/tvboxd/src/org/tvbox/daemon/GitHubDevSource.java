package org.tvbox.daemon;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONArray;
import org.json.JSONObject;

/** Resolves the virtual dev source to an immutable published release directory. */
final class GitHubDevSource {
    static String repository(String base) throws Exception {
        URL url = new URL(base);
        if (!"https".equalsIgnoreCase(url.getProtocol())
                || !"github.com".equalsIgnoreCase(url.getHost())
                || url.getPort() != -1 || url.getUserInfo() != null
                || url.getQuery() != null || url.getRef() != null
                || !url.getPath().matches("/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/releases/dev/")) return null;
        return url.getPath().substring(1, url.getPath().length() - "/releases/dev/".length());
    }

    static boolean eligible(String tag, boolean draft, boolean prerelease, String published) {
        return !draft && prerelease && tag.matches("dev-v[A-Za-z0-9_.-]+")
                && published.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z");
    }

    static String resolve(String base) throws Exception {
        String repo = repository(base);
        if (repo == null) return base;
        String bestTag = null, bestDate = "";
        for (int page = 1; ; page++) {
            HttpURLConnection c = (HttpURLConnection) new URL(
                    "https://api.github.com/repos/" + repo + "/releases?per_page=100&page=" + page).openConnection();
            c.setConnectTimeout(8_000); c.setReadTimeout(15_000);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("User-Agent", "Panadonik-OTA");
            JSONArray releases;
            try {
                int status = c.getResponseCode();
                if (status != 200) throw new IOException("GitHub API HTTP " + status);
                try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    for (int n; (n = in.read(buffer)) != -1; ) {
                        if (out.size() + n > 16 * 1024 * 1024) throw new IOException("GitHub API: ответ слишком большой");
                        out.write(buffer, 0, n);
                    }
                    releases = new JSONArray(out.toString("UTF-8"));
                }
            } finally { c.disconnect(); }
            for (int i = 0; i < releases.length(); i++) {
                JSONObject r = releases.getJSONObject(i);
                String tag = r.optString("tag_name", ""), date = r.optString("published_at", "");
                if (eligible(tag, r.optBoolean("draft", true), r.optBoolean("prerelease", false), date)
                        && date.compareTo(bestDate) > 0) { bestTag = tag; bestDate = date; }
            }
            if (releases.length() < 100) break;
        }
        if (bestTag == null) throw new IOException("Опубликованных dev-релизов не найдено");
        return "https://github.com/" + repo + "/releases/download/" + bestTag + "/";
    }
}
