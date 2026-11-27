package com.samp.mobile.launcher.searchrp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Remote Config & APK Auto-Update Manifest fetcher.
 * Reads manifest_mobile.json from GitHub, with fallback to PC manifest.json for shared server config.
 */
public final class SrpRemoteManifest {

    public static class ApkUpdateInfo {
        public String version = "";
        public int versionCode = 0;
        public boolean mandatory = false;
        public String downloadUrl = "";
        public String sha256 = "";
        public List<String> changelog = new ArrayList<>();
    }

    public static class AnnouncementInfo {
        public boolean enabled = false;
        public String level = "info";
        public String title = "";
        public String message = "";
        public String url = "";
    }

    public static class ManifestBundle {
        public ApkUpdateInfo apk = null;
        public String serverIp = "";
        public int serverPort = 0;
        public String discordUrl = "";
        public String websiteUrl = "";
        public String androidDataUrl = "";
        public String ucpVerify = "";
        public String ucpPanel = "";
        public AnnouncementInfo announcement = new AnnouncementInfo();
    }

    public static ManifestBundle fetch() {
        String bust = "?_t=" + System.currentTimeMillis();
        JSONObject releaseJson = fetchJson(SrpConfig.MANIFEST_MOBILE_RELEASE);
        JSONObject rawJson = fetchJson(SrpConfig.MANIFEST_MOBILE_RAW + bust);

        ManifestBundle relBundle = releaseJson != null ? parseMobileManifest(releaseJson) : null;
        ManifestBundle rawBundle = rawJson != null ? parseMobileManifest(rawJson) : null;

        ManifestBundle best = pickBestBundle(relBundle, rawBundle);
        if (best != null) {
            return best;
        }

        // Fallback: GitHub Releases API directly (works even if manifest_mobile.json asset is missing)
        ManifestBundle apiBundle = fetchFromGithubReleasesApi();
        if (apiBundle != null) {
            return apiBundle;
        }

        // Fallback: read shared server IP/links/announcement from PC repo manifest.json
        JSONObject pcJson = fetchJson(SrpConfig.MANIFEST_PC_FALLBACK_RAW + bust);
        if (pcJson != null) {
            return parseMobileManifest(pcJson);
        }
        return null;
    }

    private static ManifestBundle pickBestBundle(ManifestBundle a, ManifestBundle b) {
        if (a == null) return b;
        if (b == null) return a;
        int codeA = (a.apk != null) ? a.apk.versionCode : 0;
        int codeB = (b.apk != null) ? b.apk.versionCode : 0;
        if (codeB > codeA) return b;
        if (codeA > codeB) return a;
        if (a.apk != null && b.apk != null && compareSemver(b.apk.version, a.apk.version) > 0) {
            return b;
        }
        return a;
    }

    private static ManifestBundle fetchFromGithubReleasesApi() {
        String apiUrl = "https://api.github.com/repos/" + SrpConfig.GITHUB_MOBILE_REPO + "/releases/latest";
        JSONObject rel = fetchJson(apiUrl);
        if (rel == null) return null;
        String tag = rel.optString("tag_name", "").trim();
        if (tag.isEmpty()) return null;

        String apkUrl = "";
        JSONArray assets = rel.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.optJSONObject(i);
                if (asset == null) continue;
                String name = asset.optString("name", "");
                if (name.toLowerCase(java.util.Locale.US).endsWith(".apk")) {
                    apkUrl = asset.optString("browser_download_url", "").trim();
                    break;
                }
            }
        }
        if (apkUrl.isEmpty()) return null;

        ManifestBundle bundle = new ManifestBundle();
        ApkUpdateInfo info = new ApkUpdateInfo();
        info.version = tag.replaceFirst("^[vV]", "");
        info.versionCode = 0;
        info.mandatory = true;
        info.downloadUrl = apkUrl;
        String body = rel.optString("body", "").trim();
        if (!body.isEmpty()) {
            for (String line : body.split("\\r?\\n")) {
                String clean = line.trim().replaceFirst("^[-*•]+\\s*", "");
                if (!clean.isEmpty()) info.changelog.add(clean);
            }
        }
        bundle.apk = info;
        return bundle;
    }

    private static JSONObject fetchJson(String urlStr) {
        String current = urlStr;
        for (int redirect = 0; redirect <= 5; redirect++) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(current);
                conn = (HttpURLConnection) url.openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setUseCaches(false);
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("User-Agent", "SearchRoleplayMobile/1.0");
                conn.setRequestProperty("Cache-Control", "no-cache, no-store");
                conn.setRequestProperty("Pragma", "no-cache");
                int code = conn.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = conn.getHeaderField("Location");
                    if (loc == null || loc.isEmpty()) return null;
                    current = new URL(url, loc).toString();
                    continue;
                }
                if (code != 200) return null;

                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String ln;
                    while ((ln = br.readLine()) != null) sb.append(ln);
                }
                return new JSONObject(sb.toString());
            } catch (Exception ignored) {
                return null;
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return null;
    }

    private static ManifestBundle parseMobileManifest(JSONObject root) {
        ManifestBundle b = new ManifestBundle();

        JSONObject apkObj = root.optJSONObject("apk");
        if (apkObj != null) {
            ApkUpdateInfo a = new ApkUpdateInfo();
            a.version = apkObj.optString("version", "").trim();
            a.versionCode = apkObj.optInt("version_code", 0);
            a.mandatory = apkObj.optBoolean("mandatory", false);
            a.downloadUrl = apkObj.optString("download_url", "").trim();
            a.sha256 = apkObj.optString("sha256", "").trim().toLowerCase();
            JSONArray cl = apkObj.optJSONArray("changelog");
            if (cl != null) {
                for (int i = 0; i < cl.length(); i++) {
                    String item = cl.optString(i, "").trim();
                    if (!item.isEmpty()) a.changelog.add(item);
                }
            }
            if (!a.version.isEmpty() || a.versionCode > 0) {
                b.apk = a;
            }
        }

        JSONObject rc = root.optJSONObject("remote_config");
        if (rc != null) {
            b.serverIp = rc.optString("server_ip", "").trim();
            b.serverPort = rc.optInt("server_port", 0);
            b.discordUrl = rc.optString("discord_url", "").trim();
            b.websiteUrl = rc.optString("website_url", "").trim();
            b.androidDataUrl = rc.optString("android_data_url", "").trim();

            JSONObject ucp = rc.optJSONObject("ucp_channels");
            if (ucp != null) {
                b.ucpVerify = ucp.optString("verify", "").trim();
                b.ucpPanel = ucp.optString("panel", "").trim();
            }

            JSONObject ann = rc.optJSONObject("announcement");
            if (ann != null) {
                b.announcement.enabled = ann.optBoolean("enabled", false);
                b.announcement.level = ann.optString("level", "info").trim();
                b.announcement.title = ann.optString("title", "").trim();
                b.announcement.message = ann.optString("message", "").trim();
                b.announcement.url = ann.optString("url", "").trim();
            }
        }
        return b;
    }

    public static boolean isNewerApk(ApkUpdateInfo remote, int currentVersionCode, String currentVersionName) {
        if (remote == null) return false;
        if (remote.versionCode > 0 && remote.versionCode > currentVersionCode) return true;
        return compareSemver(remote.version, currentVersionName) > 0;
    }

    private static int compareSemver(String v1, String v2) {
        int[] a = parseParts(v1);
        int[] b = parseParts(v2);
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) return Integer.compare(a[i], b[i]);
        }
        return 0;
    }

    private static int[] parseParts(String v) {
        int[] res = new int[]{0, 0, 0};
        if (v == null) return res;
        String clean = v.trim().replaceFirst("^[vV]", "");
        String[] parts = clean.split("\\.");
        for (int i = 0; i < Math.min(3, parts.length); i++) {
            try {
                res[i] = Integer.parseInt(parts[i].replaceAll("[^0-9].*", ""));
            } catch (Exception ignored) {
            }
        }
        return res;
    }
}
