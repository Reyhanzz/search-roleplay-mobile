package com.samp.mobile.launcher.searchrp;

import java.util.HashMap;
import java.util.Map;

/**
 * Bilingual localization catalog (English / Indonesian) for Search Roleplay Mobile.
 * Defaults to 'en' with instant live switching to 'id'.
 */
public final class SrpI18n {
    private static final Map<String, String> EN = new HashMap<>();
    private static final Map<String, String> ID = new HashMap<>();

    static {
        // Brand & Navigation
        put("brand_sub", "OPEN.MP  |  MOBILE CLIENT", "OPEN.MP  |  KLIEN MOBILE");
        put("tab_home", "Home", "Beranda");
        put("tab_ucp", "UCP Guide", "Panduan UCP");
        put("tab_settings", "Settings", "Pengaturan");

        // Hero & Status
        put("status_checking", "CHECKING...", "MEMERIKSA...");
        put("status_online", "ONLINE", "ONLINE");
        put("status_maint", "MAINTENANCE", "MAINTENANCE");
        put("status_locked", "LOCKED", "TERKUNCI");
        put("hero_sub_connecting", "Checking server status...", "Memeriksa status server...");
        put("hero_sub_maint", "Server is currently under maintenance. Tap refresh to retry.", "Server sedang pemeliharaan. Ketuk perbarui untuk coba lagi.");
        put("ic_time_fmt", "IC %s", "JAM IC %s");
        put("stat_players", "PLAYERS", "PEMAIN");
        put("stat_ping", "PING", "PING");
        put("stat_gamemode", "GAMEMODE", "GAMEMODE");
        put("ip_copied", "Server address copied!", "Alamat server tersalin!");
        put("btn_refresh", "Refresh", "Perbarui");

        // Home Launch Console
        put("lbl_username", "ACCOUNT / UCP USERNAME", "USERNAME AKUN / UCP");
        put("pw_badge_home", "PASSWORD", "PASSWORD");
        put("lbl_no_ucp", "No UCP?", "Belum UCP?");
        put("ph_username", "Enter UCP Username...", "Masukkan Username UCP...");
        put("btn_play", "PLAY NOW", "MAIN SEKARANG");
        put("btn_download_data", "DOWNLOAD GAME DATA", "UNDUH DATA GAME");
        put("btn_downloading", "DOWNLOADING...", "MENGUNDUH...");
        put("btn_checking_access", "CHECKING...", "MEMERIKSA...");
        put("data_ready", "GTA SA Mobile data ready (%s)", "Data GTA SA Mobile siap (%s)");
        put("data_missing", "GTA SA Android data not installed yet", "Data GTA SA Android belum terpasang");
        put("data_partial", "Game cache incomplete (%s) - tap Download", "Cache game belum lengkap (%s) - ketuk Unduh");
        put("hint_ready", "Tap PLAY NOW to connect via OpenMP Mobile.", "Ketuk MAIN SEKARANG untuk terhubung ke server.");
        put("hint_locked", "Locked server  •  Password will be requested on join.", "Server terkunci  •  Password diminta saat masuk.");
        put("hint_need_data", "Install GTA SA Android 2.10 data pack first.", "Pasang paket data GTA SA Android 2.10 dulu.");
        put("btn_discord", "Discord", "Discord");
        put("btn_website", "Website", "Website");

        // Download / Extract Progress
        put("dl_connecting", "Connecting to download server...", "Menghubungkan ke server unduhan...");
        put("dl_downloading", "Downloading %.1f / %.1f MB (%d%%)  •  %.1f MB/s", "Mengunduh %.1f / %.1f MB (%d%%)  •  %.1f MB/s");
        put("dl_downloading_unk", "Downloading %.1f MB  •  %.1f MB/s", "Mengunduh %.1f MB  •  %.1f MB/s");
        put("dl_extracting", "Extracting game files (%d)...", "Mengekstrak file game (%d)...");
        put("dl_done", "Game data installed! Enter UCP and tap PLAY NOW.", "Data game terpasang! Isi UCP lalu ketuk MAIN SEKARANG.");
        put("dl_cancelled", "Download cancelled.", "Unduhan dibatalkan.");
        put("dl_no_url", "No Android game data URL configured yet. Set a custom link in Settings or manifest_mobile.json.", "Link data GTA Android belum diatur. Isi link di Pengaturan atau manifest_mobile.json.");
        put("dl_failed", "Download failed: %s", "Unduhan gagal: %s");
        put("btn_cancel", "Cancel", "Batal");

        // Validation & Alerts
        put("err_empty_user", "Please enter your Account / UCP Username first.", "Masukkan Username Akun / UCP terlebih dahulu.");
        put("err_bad_user", "Username must be 3-20 characters (letters, numbers, underscore only).", "Username harus 3-20 karakter (huruf, angka, atau garis bawah saja).");
        put("err_no_data_launch", "GTA San Andreas Android data is missing in this app folder. Tap DOWNLOAD GAME DATA or copy your GTA data into:\n%s", "Data GTA San Andreas Android belum ada di folder aplikasi ini. Ketuk UNDUH DATA GAME atau salin data GTA ke:\n%s");
        put("err_launch_fail", "Failed starting game: %s", "Gagal menjalankan game: %s");

        // Server Password Modal
        put("pw_eyebrow", "PRIVATE ACCESS  •  SERVER LOCKED", "AKSES TERBATAS  •  SERVER TERKUNCI");
        put("pw_title", "ENTER SERVER PASSWORD", "MASUKKAN PASSWORD SERVER");
        put("pw_field_lbl", "SERVER PASSWORD", "PASSWORD SERVER");
        put("pw_field_hint", "Required to join", "Wajib diisi");
        put("pw_ph", "Enter server password...", "Masukkan password server...");
        put("pw_remember", "Remember password for this server", "Ingat password untuk server ini");
        put("pw_clear", "Clear saved", "Hapus");
        put("pw_err_empty", "Please enter the server password first.", "Silakan masukkan password server dulu.");
        put("pw_btn_join", "UNLOCK & PLAY", "BUKA & MAIN");
        put("pw_cancelled", "Server password prompt cancelled.", "Pengisian password dibatalkan.");

        // Update & Announcement Banner
        put("upd_req_tag", "REQUIRED UPDATE %s", "UPDATE WAJIB %s");
        put("upd_opt_tag", "UPDATE %s AVAILABLE", "UPDATE %s TERSEDIA");
        put("btn_update", "Update", "Update");
        put("upd_checking", "Checking for mobile launcher updates...", "Mengecek pembaruan launcher mobile...");
        put("upd_latest", "You are on the latest version (%s).", "Sudah memakai versi terbaru (%s).");
        put("upd_downloading", "Downloading APK update (%d%%)...", "Mengunduh update APK (%d%%)...");
        put("upd_installing", "APK verified! Opening Android installer...", "APK terverifikasi! Membuka installer Android...");
        put("upd_err", "Update failed: %s", "Update gagal: %s");

        // UCP Guide Tab
        put("ucp_title", "UCP REGISTRATION GUIDE", "PANDUAN DAFTAR UCP");
        put("ucp_sub", "Follow these 4 steps to create your Search Roleplay account before playing.", "Ikuti 4 langkah ini untuk membuat akun Search Roleplay sebelum bermain.");
        put("s1_t", "01  •  Join Official Discord", "01  •  Gabung Discord Resmi");
        put("s1_d", "Tap Open Discord below to join the official Search Roleplay community server.", "Ketuk Buka Discord di bawah untuk masuk ke server resmi Search Roleplay.");
        put("s2_t", "02  •  Verify Discord", "02  •  Verifikasi Discord");
        put("s2_d", "Claim your member role inside %s so all server channels unlock.", "Ambil role member di channel %s agar seluruh channel terbuka.");
        put("s3_t", "03  •  Open UCP Panel", "03  •  Buka Panel UCP");
        put("s3_d", "Open %s and tap Register to create your UCP Username and Password.", "Buka %s lalu tekan Register untuk membuat Username dan Password UCP.");
        put("s4_t", "04  •  Play & Create IC Character", "04  •  Main & Buat Karakter IC");
        put("s4_d", "Enter your UCP Username in Home, tap PLAY NOW, then create your Firstname_Lastname IC character in-game.", "Isi Username UCP di Beranda, ketuk MAIN SEKARANG, lalu buat karakter IC Firstname_Lastname di dalam game.");
        put("btn_open_discord", "OPEN DISCORD", "BUKA DISCORD");
        put("btn_copy_discord", "Copy Link", "Salin Link");
        put("discord_copied", "Discord invite link copied!", "Link Discord tersalin!");

        // Settings Tab
        put("cfg_title", "CLIENT & DISPLAY SETTINGS", "PENGATURAN KLIEN & TAMPILAN");
        put("cfg_sec_perf", "GAMEPLAY & HUD (SAMP/SETTINGS.INI)", "GAMEPLAY & HUD (SAMP/SETTINGS.INI)");
        put("cfg_fps_limit", "FPS Limit", "Batas FPS");
        put("cfg_chat_lines", "Chat Lines", "Baris Chat");
        put("cfg_font_size", "Chat Font Size", "Ukuran Font Chat");
        put("sw_fps_counter", "Show FPS Counter", "Tampilkan Counter FPS");
        put("sw_android_kb", "Use Native Android Keyboard", "Gunakan Keyboard Bawaan HP");
        put("sw_first_person", "First-Person Camera", "Kamera First-Person");
        put("sw_cutout", "Fullscreen Display Cutout (Notch)", "Layar Penuh Notch (Cutout)");
        put("sw_hp_text", "Numeric HP & Armour Text", "Angka HP & Armour");
        put("sw_pc_money", "PC-Style Money HUD", "HUD Uang Gaya PC");
        put("sw_radar_rect", "Square / Rectangular Radar", "Radar Kotak");
        put("sw_autoaim", "Mobile Auto-Aim", "Auto-Aim Mobile");
        put("sw_voice", "In-Game Voice Chat", "Voice Chat In-Game");
        put("btn_save_cfg", "SAVE SETTINGS", "SIMPAN PENGATURAN");
        put("btn_reset_cfg", "Reset", "Reset");
        put("cfg_saved", "Saved to SAMP/settings.ini!", "Tersimpan ke SAMP/settings.ini!");
        put("cfg_reset_ok", "Default settings restored.", "Pengaturan default dipulihkan.");

        put("cfg_sec_data", "GAME DATA & STORAGE", "DATA GAME & PENYIMPANAN");
        put("cfg_data_url_lbl", "GTA SA ANDROID DATA PACK URL (.ZIP)", "LINK PAKET DATA GTA ANDROID (.ZIP)");
        put("cfg_data_url_ph", "https://... (Direct / Google Drive / Dropbox)", "https://... (Direct / Google Drive / Dropbox)");
        put("btn_save_url", "Save URL", "Simpan Link");
        put("btn_dl_now", "Download Data", "Unduh Data");
        put("btn_view_log", "View samp_log.txt", "Lihat samp_log.txt");
        put("btn_check_apk", "Check APK Update", "Cek Update APK");
        put("url_saved", "Data pack URL saved!", "Link data game tersimpan!");
        put("log_empty", "No samp_log.txt found yet. Launch the game at least once first.", "Belum ada samp_log.txt. Jalankan game minimal sekali terlebih dahulu.");
        put("log_title", "SAMP Client Log", "Log Klien SAMP");
        put("btn_close", "Close", "Tutup");
        put("perm_audio_hint", "Microphone permission allows in-game voice chat.", "Izin mikrofon digunakan untuk voice chat in-game.");
    }

    private static void put(String k, String en, String id) {
        EN.put(k, en);
        ID.put(k, id);
    }

    public static String tr(String lang, String key) {
        Map<String, String> map = "id".equals(lang) ? ID : EN;
        String val = map.get(key);
        if (val != null) return val;
        String fb = EN.get(key);
        return fb != null ? fb : key;
    }

    public static String tr(String lang, String key, Object... args) {
        String tpl = tr(lang, key);
        try {
            return String.format(java.util.Locale.US, tpl, args);
        } catch (Exception e) {
            return tpl;
        }
    }
}
