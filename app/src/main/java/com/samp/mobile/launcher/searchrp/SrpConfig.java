package com.samp.mobile.launcher.searchrp;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persistent configuration & native SAMP/settings.ini sync for Search Roleplay Mobile.
 */
public final class SrpConfig {
    public static final String DEFAULT_SERVER_IP = "151.246.170.113";
    public static final int DEFAULT_SERVER_PORT = 7777;
    public static final String DEFAULT_DISCORD_URL = "https://discord.gg/f8HzxbHXDs";
    public static final String DEFAULT_WEBSITE_URL = "https://searchrp.netlify.app/";
    public static final String DEFAULT_DATA_URL = "https://drive.google.com/file/d/1Ayv5SVww8AS_2PrG9popALZLexgXn9ZJ/view?usp=drive_link";

    public static final String GITHUB_MOBILE_REPO = "Reyhanzz/search-roleplay-mobile";
    public static final String MANIFEST_MOBILE_RAW =
            "https://raw.githubusercontent.com/" + GITHUB_MOBILE_REPO + "/main/manifest_mobile.json";
    public static final String MANIFEST_MOBILE_RELEASE =
            "https://github.com/" + GITHUB_MOBILE_REPO + "/releases/latest/download/manifest_mobile.json";
    public static final String MANIFEST_PC_FALLBACK_RAW =
            "https://raw.githubusercontent.com/Reyhanzz/search-roleplay-launcher/main/manifest.json";

    private static final String PREFS_NAME = "search_roleplay_mobile_prefs";

    private final SharedPreferences prefs;

    public SrpConfig(Context ctx) {
        this.prefs = ctx.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public String getLanguage() {
        String l = prefs.getString("language", "en");
        return "id".equals(l) ? "id" : "en";
    }

    public void setLanguage(String lang) {
        prefs.edit().putString("language", "id".equals(lang) ? "id" : "en").apply();
    }

    public String getUsername() {
        return prefs.getString("username", "").trim();
    }

    public void setUsername(String name) {
        prefs.edit().putString("username", name == null ? "" : name.trim()).apply();
    }

    public String getServerIp() {
        String ip = prefs.getString("server_ip", DEFAULT_SERVER_IP);
        return (ip == null || ip.trim().isEmpty()) ? DEFAULT_SERVER_IP : ip.trim();
    }

    public void setServerIp(String ip) {
        if (ip != null && !ip.trim().isEmpty()) {
            prefs.edit().putString("server_ip", ip.trim()).apply();
        }
    }

    public int getServerPort() {
        int p = prefs.getInt("server_port", DEFAULT_SERVER_PORT);
        return (p > 0 && p <= 65535) ? p : DEFAULT_SERVER_PORT;
    }

    public void setServerPort(int port) {
        if (port > 0 && port <= 65535) {
            prefs.edit().putInt("server_port", port).apply();
        }
    }

    public String getDiscordUrl() {
        String u = prefs.getString("discord_url", DEFAULT_DISCORD_URL);
        return (u == null || u.trim().isEmpty()) ? DEFAULT_DISCORD_URL : u.trim();
    }

    public void setDiscordUrl(String url) {
        if (url != null && !url.trim().isEmpty()) {
            prefs.edit().putString("discord_url", url.trim()).apply();
        }
    }

    public String getWebsiteUrl() {
        String u = prefs.getString("website_url", DEFAULT_WEBSITE_URL);
        return (u == null || u.trim().isEmpty()) ? DEFAULT_WEBSITE_URL : u.trim();
    }

    public void setWebsiteUrl(String url) {
        if (url != null && !url.trim().isEmpty()) {
            prefs.edit().putString("website_url", url.trim()).apply();
        }
    }

    public String getDataUrl() {
        String custom = prefs.getString("custom_data_url", "");
        if (custom != null && !custom.trim().isEmpty()) return custom.trim();
        String remote = prefs.getString("remote_data_url", DEFAULT_DATA_URL);
        return remote == null ? "" : remote.trim();
    }

    public void setCustomDataUrl(String url) {
        prefs.edit().putString("custom_data_url", url == null ? "" : url.trim()).apply();
    }

    public void setRemoteDataUrl(String url) {
        if (url != null && !url.trim().isEmpty()) {
            prefs.edit().putString("remote_data_url", url.trim()).apply();
        }
    }

    public String getUcpVerifyChannel() {
        return prefs.getString("ucp_verify", "#verify-discord");
    }

    public String getUcpPanelChannel() {
        return prefs.getString("ucp_panel", "#panel-ucp");
    }

    public void setUcpChannels(String verify, String panel) {
        SharedPreferences.Editor ed = prefs.edit();
        if (verify != null && !verify.trim().isEmpty()) ed.putString("ucp_verify", verify.trim());
        if (panel != null && !panel.trim().isEmpty()) ed.putString("ucp_panel", panel.trim());
        ed.apply();
    }

    public String getSavedServerPassword() {
        return prefs.getString("server_password", "");
    }

    public boolean isRememberServerPassword() {
        return prefs.getBoolean("remember_server_password", false);
    }

    public void setSavedServerPassword(String password, boolean remember) {
        prefs.edit()
                .putBoolean("remember_server_password", remember)
                .putString("server_password", remember && password != null ? password : "")
                .apply();
    }

    // --- Game / Client Settings (Mirrored to SAMP/settings.ini) ---
    public static class ClientIniSettings {
        public int fpsLimit = 60;
        public int chatLines = 9;
        public int fontSize = 30;
        public boolean showFps = false;
        public boolean androidKeyboard = false;
        public boolean firstPerson = true;
        public boolean cutout = false;
        public boolean hpArmourText = false;
        public boolean pcMoney = false;
        public boolean radarRect = false;
        public boolean autoAim = false;
        public boolean voiceChat = true;
    }

    public ClientIniSettings loadClientSettings() {
        ClientIniSettings s = new ClientIniSettings();
        s.fpsLimit = prefs.getInt("ini_fps_limit", 60);
        s.chatLines = prefs.getInt("ini_chat_lines", 9);
        s.fontSize = prefs.getInt("ini_font_size", 30);
        s.showFps = prefs.getBoolean("ini_show_fps", false);
        s.androidKeyboard = prefs.getBoolean("ini_android_kb", false);
        s.firstPerson = prefs.getBoolean("ini_first_person", true);
        s.cutout = prefs.getBoolean("ini_cutout", false);
        s.hpArmourText = prefs.getBoolean("ini_hp_text", false);
        s.pcMoney = prefs.getBoolean("ini_pc_money", false);
        s.radarRect = prefs.getBoolean("ini_radar_rect", false);
        s.autoAim = prefs.getBoolean("ini_autoaim", false);
        s.voiceChat = prefs.getBoolean("ini_voice", true);
        return s;
    }

    public void saveClientSettings(ClientIniSettings s) {
        prefs.edit()
                .putInt("ini_fps_limit", s.fpsLimit)
                .putInt("ini_chat_lines", s.chatLines)
                .putInt("ini_font_size", s.fontSize)
                .putBoolean("ini_show_fps", s.showFps)
                .putBoolean("ini_android_kb", s.androidKeyboard)
                .putBoolean("ini_first_person", s.firstPerson)
                .putBoolean("ini_cutout", s.cutout)
                .putBoolean("ini_hp_text", s.hpArmourText)
                .putBoolean("ini_pc_money", s.pcMoney)
                .putBoolean("ini_radar_rect", s.radarRect)
                .putBoolean("ini_autoaim", s.autoAim)
                .putBoolean("ini_voice", s.voiceChat)
                .apply();
    }

    public static File getSettingsIniFile(Context ctx) {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();
        return new File(new File(base, "SAMP"), "settings.ini");
    }

    /**
     * Write exact INI sections read by native cpp/samp/settings.cpp (INIReader).
     * Preserves any extra keys the player or modpack added under [client]/[debug]/[gui].
     */
    public boolean syncSettingsIni(Context ctx, String username, String serverPassword) {
        File iniFile = getSettingsIniFile(ctx);
        File parent = iniFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        Map<String, LinkedHashMap<String, String>> sections = readExistingIni(iniFile);
        LinkedHashMap<String, String> client = getOrCreateSection(sections, "client");
        LinkedHashMap<String, String> debug = getOrCreateSection(sections, "debug");
        LinkedHashMap<String, String> gui = getOrCreateSection(sections, "gui");

        ClientIniSettings s = loadClientSettings();
        String safeName = (username != null && !username.trim().isEmpty()) ? username.trim() : getUsername();
        if (safeName.isEmpty()) safeName = "Player_SRP";

        client.put("name", safeName);
        client.put("host", getServerIp());
        client.put("port", String.valueOf(getServerPort()));
        client.put("password", serverPassword == null ? "" : serverPassword);
        client.put("version", "0.3.DL-R1");
        client.put("autoaim", s.autoAim ? "true" : "false");

        debug.put("debug", "false");
        debug.put("online", "true");

        if (!gui.containsKey("Font")) gui.put("Font", "Arial.ttf");
        gui.put("FontSize", s.fontSize + ".0");
        if (!gui.containsKey("FontOutline")) gui.put("FontOutline", "2");
        if (!gui.containsKey("ChatPosX")) gui.put("ChatPosX", "325.0");
        if (!gui.containsKey("ChatPosY")) gui.put("ChatPosY", "25.0");
        if (!gui.containsKey("ChatSizeX")) gui.put("ChatSizeX", "1150.0");
        if (!gui.containsKey("ChatSizeY")) gui.put("ChatSizeY", "220.0");
        gui.put("ChatMaxMessages", String.valueOf(s.chatLines));
        if (!gui.containsKey("HealthBarWidth")) gui.put("HealthBarWidth", "75.0");
        if (!gui.containsKey("HealthBarHeight")) gui.put("HealthBarHeight", "10.0");
        gui.put("Dialog", "true");
        gui.put("VoiceChatEnable", s.voiceChat ? "true" : "false");
        gui.put("androidkeyboard", s.androidKeyboard ? "true" : "false");
        gui.put("firstperson", s.firstPerson ? "true" : "false");
        gui.put("cutout", s.cutout ? "true" : "false");
        gui.put("fps", s.showFps ? "true" : "false");
        gui.put("FPSLimit", String.valueOf(s.fpsLimit));
        gui.put("hparmourtext", s.hpArmourText ? "true" : "false");
        gui.put("pcmoney", s.pcMoney ? "true" : "false");
        gui.put("radarrect", s.radarRect ? "true" : "false");

        StringBuilder sb = new StringBuilder();
        sb.append("# Search Roleplay Mobile - Auto-Synced Configuration\n");
        for (Map.Entry<String, LinkedHashMap<String, String>> sec : sections.entrySet()) {
            sb.append("\n[").append(sec.getKey()).append("]\n");
            for (Map.Entry<String, String> kv : sec.getValue().entrySet()) {
                sb.append(kv.getKey()).append(" = ").append(kv.getValue()).append("\n");
            }
        }

        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(iniFile), StandardCharsets.UTF_8)) {
            w.write(sb.toString());
            w.flush();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static LinkedHashMap<String, String> getOrCreateSection(
            Map<String, LinkedHashMap<String, String>> map, String name) {
        LinkedHashMap<String, String> sec = map.get(name);
        if (sec == null) {
            sec = new LinkedHashMap<>();
            map.put(name, sec);
        }
        return sec;
    }

    private static Map<String, LinkedHashMap<String, String>> readExistingIni(File file) {
        Map<String, LinkedHashMap<String, String>> out = new LinkedHashMap<>();
        if (file == null || !file.isFile()) return out;
        String currentSection = null;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) continue;
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    currentSection = trimmed.substring(1, trimmed.length() - 1).trim();
                    getOrCreateSection(out, currentSection);
                    continue;
                }
                int eq = trimmed.indexOf('=');
                if (eq > 0 && currentSection != null) {
                    String k = trimmed.substring(0, eq).trim();
                    String v = trimmed.substring(eq + 1).trim();
                    if (!k.isEmpty()) {
                        getOrCreateSection(out, currentSection).put(k, v);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }
}
