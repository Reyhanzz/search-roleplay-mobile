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
        JSONObject mobileJson = fetchJson(SrpConfig.MANIFEST_MOBILE_RAW);
        if (mobileJson == null) {
            mobileJson = fetchJson(SrpConfig.MANIFEST_MOBILE_RELEASE);
        }
        if (mobileJson != null) {
            return parseMobileManifest(mobileJson);
        }
        // Fallback: read shared server IP/links/announcement from PC repo manifest.json
        JSONObject pcJson = fetchJson(SrpConfig.MANIFEST_PC_FALLBACK_RAW);
        if (pcJson != null) {
            return parseMobileManifest(pcJson);
        }
        return null;
    }

    private static JSONObject fetchJson(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(7000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "SearchRoleplayMobile/1.0");
            conn.setRequestProperty("Cache-Control", "no-cache");
            if (conn.getResponseCode() != 200) return null;

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
