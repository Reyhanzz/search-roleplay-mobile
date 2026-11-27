package com.samp.mobile.launcher.searchrp;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.samp.mobile.BuildConfig;
import com.samp.mobile.R;
import com.samp.mobile.game.SAMP;
import com.samp.mobile.launcher.util.ConfigValidator;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Official Search Roleplay Mobile All-in-One Launcher & OpenMP/SA-MP Client Entrypoint.
 * Features:
 * - GTA San Andreas Noir UI with live EN / ID switching
 * - Real SA-MP/OpenMP UDP status query + automatic Server Password gate
 * - Zero-ZArchiver in-app GTA SA 2.10 data cache downloader & smart ZIP extractor
 * - Real SAMP/settings.ini gameplay & HUD configurator
 * - Remote Config & GitHub Releases APK Auto-Updater with SHA-256 verification
 */
public class SearchRpLauncherActivity extends AppCompatActivity {

    private static final long AUTO_REFRESH_MS = 30_000L;
    private static final int REQ_RECORD_AUDIO = 401;
    private static final int REQ_PICK_ZIP = 402;

    private SrpConfig cfg;
    private String lang = "en";
    private int activeTab = 0; // 0=home, 1=ucp, 2=settings
    private SrpGpuHelper.GpuInfo gpuInfo = new SrpGpuHelper.GpuInfo("etc", "Detecting...", "ETC (.etc)");

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService bgExec = Executors.newCachedThreadPool();

    private SrpUdpQuery.ServerInfo serverInfo = null;
    private long lastServerQueryMs = 0L;
    private boolean isQuerying = false;
    private boolean isCheckingLock = false;
    private boolean isDownloading = false;
    private final AtomicBoolean cancelDlFlag = new AtomicBoolean(false);
    private SrpGameDataManager.CacheStatus cacheStatus = new SrpGameDataManager.CacheStatus();
    private SrpRemoteManifest.ApkUpdateInfo pendingApkUpdate = null;
    private SrpRemoteManifest.AnnouncementInfo activeAnnouncement = null;

    // Header & Navigation
    private TextView tvBrandSub, btnLangEn, btnLangId;
    private ScrollView mainScroll;
    private View tabHome, tabUcp, tabSettings;
    private ImageView navHomeIco, navUcpIco, navSettingsIco;
    private TextView navHomeLbl, navUcpLbl, navSettingsLbl;

    // Home views
    private View bannerNotice;
    private TextView bannerNoticeTag, bannerNoticeMsg, bannerNoticeAction;
    private LinearLayout pillStatus, pillHeroLocked, btnRefresh, rowCopyIp, badgePwHome, btnPlay;
    private View dotStatus;
    private TextView tvStatusPill, tvHeroLocked, tvIcClock, tvBtnRefresh;
    private TextView tvHostname, tvSubtitle;
    private TextView lblStatPlayers, tvStatPlayers, lblStatPing, tvStatPing, lblStatGamemode, tvStatGamemode;
    private ProgressBar barCapacity;
    private TextView tvServerIp;
    private ImageView icoCopyIp, icoBtnPlay;
    private TextView lblUsername, tvBadgePwHome, linkNoUcp, tvBtnPlay, tvDataStatus, tvLaunchHint;
    private EditText editUsername;
    private View panelDownload;
    private ProgressBar barDownload;
    private TextView tvDlStats, btnDlCancel;
    private TextView tvHomeDiscord, tvHomeWebsite;

    // UCP Guide views
    private TextView tvUcpTitle, tvUcpSub, tvS1T, tvS1D, tvS2T, tvS2D, tvS3T, tvS3D, tvS4T, tvS4D;
    private TextView btnUcpCopy, btnUcpDiscord;

    // Settings views
    private TextView tvCfgTitle, tvCfgSecPerf, lblCfgFps, lblCfgChat, lblCfgFont, tvChatVal, tvFontVal;
    private TextView fps30, fps60, fps90, fps120;
    private SwitchCompat swFpsCounter, swAndroidKb, swFirstPerson, swCutout, swHpText, swPcMoney, swRadarRect, swAutoAim, swVoice;
    private TextView btnCfgReset, btnCfgSave;
    private TextView tvCfgSecData, tvCfgStoragePath, tvCfgGpuInfo, lblCfgDataUrl, btnSaveUrl, btnCfgDlData, btnImportZip, btnViewLog, btnCheckApk, tvVersionFooter;
    private EditText editDataUrl;
    private int selectedFps = 60;
    private int selectedChatLines = 9;
    private int selectedFontSize = 30;

    private final Runnable autoRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshServerStatus(false);
            mainHandler.postDelayed(this, AUTO_REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_srp_launcher);

        cfg = new SrpConfig(this);
        lang = cfg.getLanguage();

        // Ensure default SAMP/settings.ini exists in Android/data/com.searchroleplay.mobile/files/
        ConfigValidator.validateConfigFiles(this);

        bindViews();
        loadStoredValuesIntoUi();
        wireEvents();
        applyLanguageToUi();
        switchTab(0);
        refreshCacheStatus();

        // Start GPU detection + live server query + remote manifest sync
        detectGpuAsync();
        refreshServerStatus(true);
        fetchRemoteManifestAsync(false);
        mainHandler.postDelayed(autoRefreshRunnable, AUTO_REFRESH_MS);
    }

    private void detectGpuAsync() {
        bgExec.execute(() -> {
            SrpGpuHelper.GpuInfo detected = SrpGpuHelper.detect();
            mainHandler.post(() -> {
                gpuInfo = detected;
                if (tvCfgGpuInfo != null) {
                    tvCfgGpuInfo.setText(t("gpu_detected_fmt", detected.label, detected.renderer));
                }
            });
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshCacheStatus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacks(autoRefreshRunnable);
        cancelDlFlag.set(true);
        bgExec.shutdownNow();
    }

    private void bindViews() {
        tvBrandSub = findViewById(R.id.tv_brand_sub);
        btnLangEn = findViewById(R.id.btn_lang_en);
        btnLangId = findViewById(R.id.btn_lang_id);
        mainScroll = findViewById(R.id.main_scroll);

        tabHome = findViewById(R.id.tab_home);
        tabUcp = findViewById(R.id.tab_ucp);
        tabSettings = findViewById(R.id.tab_settings);

        navHomeIco = findViewById(R.id.nav_home_ico);
        navUcpIco = findViewById(R.id.nav_ucp_ico);
        navSettingsIco = findViewById(R.id.nav_settings_ico);
        navHomeLbl = findViewById(R.id.nav_home_lbl);
        navUcpLbl = findViewById(R.id.nav_ucp_lbl);
        navSettingsLbl = findViewById(R.id.nav_settings_lbl);

        bannerNotice = findViewById(R.id.banner_notice);
        bannerNoticeTag = findViewById(R.id.banner_notice_tag);
        bannerNoticeMsg = findViewById(R.id.banner_notice_msg);
        bannerNoticeAction = findViewById(R.id.banner_notice_action);

        pillStatus = findViewById(R.id.pill_status);
        dotStatus = findViewById(R.id.dot_status);
        tvStatusPill = findViewById(R.id.tv_status_pill);
        pillHeroLocked = findViewById(R.id.pill_hero_locked);
        tvHeroLocked = findViewById(R.id.tv_hero_locked);
        tvIcClock = findViewById(R.id.tv_ic_clock);
        btnRefresh = findViewById(R.id.btn_refresh);
        tvBtnRefresh = findViewById(R.id.tv_btn_refresh);
        tvHostname = findViewById(R.id.tv_hostname);
        tvSubtitle = findViewById(R.id.tv_subtitle);

        lblStatPlayers = findViewById(R.id.lbl_stat_players);
        tvStatPlayers = findViewById(R.id.tv_stat_players);
        lblStatPing = findViewById(R.id.lbl_stat_ping);
        tvStatPing = findViewById(R.id.tv_stat_ping);
        lblStatGamemode = findViewById(R.id.lbl_stat_gamemode);
        tvStatGamemode = findViewById(R.id.tv_stat_gamemode);
        barCapacity = findViewById(R.id.bar_capacity);
        rowCopyIp = findViewById(R.id.row_copy_ip);
        tvServerIp = findViewById(R.id.tv_server_ip);
        icoCopyIp = findViewById(R.id.ico_copy_ip);

        lblUsername = findViewById(R.id.lbl_username);
        badgePwHome = findViewById(R.id.badge_pw_home);
        tvBadgePwHome = findViewById(R.id.tv_badge_pw_home);
        linkNoUcp = findViewById(R.id.link_no_ucp);
        editUsername = findViewById(R.id.edit_username);
        btnPlay = findViewById(R.id.btn_play);
        icoBtnPlay = findViewById(R.id.ico_btn_play);
        tvBtnPlay = findViewById(R.id.tv_btn_play);
        tvDataStatus = findViewById(R.id.tv_data_status);
        tvLaunchHint = findViewById(R.id.tv_launch_hint);

        panelDownload = findViewById(R.id.panel_download);
        barDownload = findViewById(R.id.bar_download);
        tvDlStats = findViewById(R.id.tv_dl_stats);
        btnDlCancel = findViewById(R.id.btn_dl_cancel);
        tvHomeDiscord = findViewById(R.id.tv_home_discord);
        tvHomeWebsite = findViewById(R.id.tv_home_website);

        tvUcpTitle = findViewById(R.id.tv_ucp_title);
        tvUcpSub = findViewById(R.id.tv_ucp_sub);
        tvS1T = findViewById(R.id.tv_s1_t);
        tvS1D = findViewById(R.id.tv_s1_d);
        tvS2T = findViewById(R.id.tv_s2_t);
        tvS2D = findViewById(R.id.tv_s2_d);
        tvS3T = findViewById(R.id.tv_s3_t);
        tvS3D = findViewById(R.id.tv_s3_d);
        tvS4T = findViewById(R.id.tv_s4_t);
        tvS4D = findViewById(R.id.tv_s4_d);
        btnUcpCopy = findViewById(R.id.btn_ucp_copy);
        btnUcpDiscord = findViewById(R.id.btn_ucp_discord);

        tvCfgTitle = findViewById(R.id.tv_cfg_title);
        tvCfgSecPerf = findViewById(R.id.tv_cfg_sec_perf);
        lblCfgFps = findViewById(R.id.lbl_cfg_fps);
        lblCfgChat = findViewById(R.id.lbl_cfg_chat);
        lblCfgFont = findViewById(R.id.lbl_cfg_font);
        tvChatVal = findViewById(R.id.tv_chat_val);
        tvFontVal = findViewById(R.id.tv_font_val);
        fps30 = findViewById(R.id.fps_30);
        fps60 = findViewById(R.id.fps_60);
        fps90 = findViewById(R.id.fps_90);
        fps120 = findViewById(R.id.fps_120);

        swFpsCounter = findViewById(R.id.sw_fps_counter);
        swAndroidKb = findViewById(R.id.sw_android_kb);
        swFirstPerson = findViewById(R.id.sw_first_person);
        swCutout = findViewById(R.id.sw_cutout);
        swHpText = findViewById(R.id.sw_hp_text);
        swPcMoney = findViewById(R.id.sw_pc_money);
        swRadarRect = findViewById(R.id.sw_radar_rect);
        swAutoAim = findViewById(R.id.sw_autoaim);
        swVoice = findViewById(R.id.sw_voice);

        btnCfgReset = findViewById(R.id.btn_cfg_reset);
        btnCfgSave = findViewById(R.id.btn_cfg_save);
        tvCfgSecData = findViewById(R.id.tv_cfg_sec_data);
        tvCfgStoragePath = findViewById(R.id.tv_cfg_storage_path);
        tvCfgGpuInfo = findViewById(R.id.tv_cfg_gpu_info);
        lblCfgDataUrl = findViewById(R.id.lbl_cfg_data_url);
        editDataUrl = findViewById(R.id.edit_data_url);
        btnSaveUrl = findViewById(R.id.btn_save_url);
        btnCfgDlData = findViewById(R.id.btn_cfg_dl_data);
        btnImportZip = findViewById(R.id.btn_import_zip);
        btnViewLog = findViewById(R.id.btn_view_log);
        btnCheckApk = findViewById(R.id.btn_check_apk);
        tvVersionFooter = findViewById(R.id.tv_version_footer);
    }

    private void loadStoredValuesIntoUi() {
        String uname = cfg.getUsername();
        if (!uname.isEmpty()) {
            editUsername.setText(uname);
        }
        tvServerIp.setText(cfg.getServerIp() + ":" + cfg.getServerPort());
        editDataUrl.setText(cfg.getDataUrl());
        tvVersionFooter.setText("Search Roleplay Mobile v" + BuildConfig.VERSION_NAME + "  •  ARM32 + ARM64");

        SrpConfig.ClientIniSettings s = cfg.loadClientSettings();
        applyClientSettingsToWidgets(s);
    }

    private void applyClientSettingsToWidgets(SrpConfig.ClientIniSettings s) {
        selectedFps = s.fpsLimit;
        selectedChatLines = Math.max(6, Math.min(14, s.chatLines));
        selectedFontSize = Math.max(22, Math.min(42, s.fontSize));
        updateFpsChips();
        tvChatVal.setText(String.valueOf(selectedChatLines));
        tvFontVal.setText(String.valueOf(selectedFontSize));

        swFpsCounter.setChecked(s.showFps);
        swAndroidKb.setChecked(s.androidKeyboard);
        swFirstPerson.setChecked(s.firstPerson);
        swCutout.setChecked(s.cutout);
        swHpText.setChecked(s.hpArmourText);
        swPcMoney.setChecked(s.pcMoney);
        swRadarRect.setChecked(s.radarRect);
        swAutoAim.setChecked(s.autoAim);
        swVoice.setChecked(s.voiceChat);
    }

    private void wireEvents() {
        btnLangEn.setOnClickListener(v -> switchLanguage("en"));
        btnLangId.setOnClickListener(v -> switchLanguage("id"));

        findViewById(R.id.nav_home).setOnClickListener(v -> switchTab(0));
        findViewById(R.id.nav_ucp).setOnClickListener(v -> switchTab(1));
        findViewById(R.id.nav_settings).setOnClickListener(v -> switchTab(2));
        linkNoUcp.setOnClickListener(v -> switchTab(1));

        btnRefresh.setOnClickListener(v -> refreshServerStatus(true));
        rowCopyIp.setOnClickListener(v -> copyToClipboard("Server IP", cfg.getServerIp() + ":" + cfg.getServerPort(), "ip_copied"));

        editUsername.setOnFocusChangeListener((v, hasFocus) ->
                editUsername.setBackgroundResource(hasFocus ? R.drawable.srp_input_gold_bg : R.drawable.srp_input_bg));
        editUsername.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                onPlayPrimaryClicked();
                return true;
            }
            return false;
        });

        btnPlay.setOnClickListener(v -> onPlayPrimaryClicked());
        btnDlCancel.setOnClickListener(v -> cancelDlFlag.set(true));

        findViewById(R.id.btn_home_discord).setOnClickListener(v -> openUrl(cfg.getDiscordUrl()));
        findViewById(R.id.btn_home_website).setOnClickListener(v -> openUrl(cfg.getWebsiteUrl()));
        btnUcpDiscord.setOnClickListener(v -> openUrl(cfg.getDiscordUrl()));
        btnUcpCopy.setOnClickListener(v -> copyToClipboard("Discord", cfg.getDiscordUrl(), "discord_copied"));

        fps30.setOnClickListener(v -> { selectedFps = 30; updateFpsChips(); });
        fps60.setOnClickListener(v -> { selectedFps = 60; updateFpsChips(); });
        fps90.setOnClickListener(v -> { selectedFps = 90; updateFpsChips(); });
        fps120.setOnClickListener(v -> { selectedFps = 120; updateFpsChips(); });

        findViewById(R.id.btn_chat_minus).setOnClickListener(v -> {
            if (selectedChatLines > 6) selectedChatLines--;
            tvChatVal.setText(String.valueOf(selectedChatLines));
        });
        findViewById(R.id.btn_chat_plus).setOnClickListener(v -> {
            if (selectedChatLines < 14) selectedChatLines++;
            tvChatVal.setText(String.valueOf(selectedChatLines));
        });
        findViewById(R.id.btn_font_minus).setOnClickListener(v -> {
            if (selectedFontSize > 22) selectedFontSize -= 2;
            tvFontVal.setText(String.valueOf(selectedFontSize));
        });
        findViewById(R.id.btn_font_plus).setOnClickListener(v -> {
            if (selectedFontSize < 42) selectedFontSize += 2;
            tvFontVal.setText(String.valueOf(selectedFontSize));
        });

        btnCfgSave.setOnClickListener(v -> saveSettingsFromUi());
        btnCfgReset.setOnClickListener(v -> {
            SrpConfig.ClientIniSettings def = new SrpConfig.ClientIniSettings();
            applyClientSettingsToWidgets(def);
            cfg.saveClientSettings(def);
            cfg.syncSettingsIni(this, editUsername.getText().toString().trim(), cfg.getSavedServerPassword());
            toast(t("cfg_reset_ok"));
        });

        btnSaveUrl.setOnClickListener(v -> {
            cfg.setCustomDataUrl(editDataUrl.getText().toString().trim());
            toast(t("url_saved"));
        });
        btnCfgDlData.setOnClickListener(v -> {
            cfg.setCustomDataUrl(editDataUrl.getText().toString().trim());
            switchTab(0);
            startDataDownload();
        });
        btnImportZip.setOnClickListener(v -> openLocalZipPicker());
        btnViewLog.setOnClickListener(v -> showSampLogDialog());
        btnCheckApk.setOnClickListener(v -> {
            toast(t("upd_checking"));
            fetchRemoteManifestAsync(true);
        });
    }

    private String t(String key) {
        return SrpI18n.tr(lang, key);
    }

    private String t(String key, Object... args) {
        return SrpI18n.tr(lang, key, args);
    }

    private void switchLanguage(String newLang) {
        if (newLang.equals(lang)) return;
        lang = newLang;
        cfg.setLanguage(newLang);
        applyLanguageToUi();
        renderServerInfo();
        updatePlayAndDataUi();
        renderNoticeBanner();
    }

    private void applyLanguageToUi() {
        boolean isEn = "en".equals(lang);
        btnLangEn.setBackgroundResource(isEn ? R.drawable.srp_pill_lang_active : R.drawable.srp_pill_lang_idle);
        btnLangEn.setTextColor(ContextCompat.getColor(this, isEn ? R.color.srp_text_main : R.color.srp_text_muted));
        btnLangId.setBackgroundResource(!isEn ? R.drawable.srp_pill_lang_active : R.drawable.srp_pill_lang_idle);
        btnLangId.setTextColor(ContextCompat.getColor(this, !isEn ? R.color.srp_text_main : R.color.srp_text_muted));

        tvBrandSub.setText(t("brand_sub"));
        navHomeLbl.setText(t("tab_home"));
        navUcpLbl.setText(t("tab_ucp"));
        navSettingsLbl.setText(t("tab_settings"));

        tvBtnRefresh.setText(t("btn_refresh"));
        tvHeroLocked.setText(t("status_locked"));
        lblStatPlayers.setText(t("stat_players"));
        lblStatPing.setText(t("stat_ping"));
        lblStatGamemode.setText(t("stat_gamemode"));

        lblUsername.setText(t("lbl_username"));
        tvBadgePwHome.setText(t("pw_badge_home"));
        linkNoUcp.setText(t("lbl_no_ucp"));
        editUsername.setHint(t("ph_username"));
        btnDlCancel.setText(t("btn_cancel"));
        tvHomeDiscord.setText(t("btn_discord"));
        tvHomeWebsite.setText(t("btn_website"));

        tvUcpTitle.setText(t("ucp_title"));
        tvUcpSub.setText(t("ucp_sub"));
        tvS1T.setText(t("s1_t"));
        tvS1D.setText(t("s1_d"));
        tvS2T.setText(t("s2_t"));
        tvS2D.setText(t("s2_d", cfg.getUcpVerifyChannel()));
        tvS3T.setText(t("s3_t"));
        tvS3D.setText(t("s3_d", cfg.getUcpPanelChannel()));
        tvS4T.setText(t("s4_t"));
        tvS4D.setText(t("s4_d"));
        btnUcpDiscord.setText(t("btn_open_discord"));
        btnUcpCopy.setText(t("btn_copy_discord"));

        tvCfgTitle.setText(t("cfg_title"));
        tvCfgSecPerf.setText(t("cfg_sec_perf"));
        lblCfgFps.setText(t("cfg_fps_limit"));
        lblCfgChat.setText(t("cfg_chat_lines"));
        lblCfgFont.setText(t("cfg_font_size"));
        swFpsCounter.setText(t("sw_fps_counter"));
        swAndroidKb.setText(t("sw_android_kb"));
        swFirstPerson.setText(t("sw_first_person"));
        swCutout.setText(t("sw_cutout"));
        swHpText.setText(t("sw_hp_text"));
        swPcMoney.setText(t("sw_pc_money"));
        swRadarRect.setText(t("sw_radar_rect"));
        swAutoAim.setText(t("sw_autoaim"));
        swVoice.setText(t("sw_voice"));
        btnCfgSave.setText(t("btn_save_cfg"));
        btnCfgReset.setText(t("btn_reset_cfg"));

        tvCfgSecData.setText(t("cfg_sec_data"));
        lblCfgDataUrl.setText(t("cfg_data_url_lbl"));
        editDataUrl.setHint(t("cfg_data_url_ph"));
        btnSaveUrl.setText(t("btn_save_url"));
        btnCfgDlData.setText(t("btn_dl_now"));
        btnImportZip.setText(t("btn_import_zip"));
        tvCfgGpuInfo.setText(t("gpu_detected_fmt", gpuInfo.label, gpuInfo.renderer));
        btnViewLog.setText(t("btn_view_log"));
        btnCheckApk.setText(t("btn_check_apk"));
    }

    private void switchTab(int idx) {
        activeTab = idx;
        tabHome.setVisibility(idx == 0 ? View.VISIBLE : View.GONE);
        tabUcp.setVisibility(idx == 1 ? View.VISIBLE : View.GONE);
        tabSettings.setVisibility(idx == 2 ? View.VISIBLE : View.GONE);
        mainScroll.scrollTo(0, 0);

        int colGold = ContextCompat.getColor(this, R.color.srp_gold);
        int colMuted = ContextCompat.getColor(this, R.color.srp_text_muted);

        navHomeIco.setColorFilter(idx == 0 ? colGold : colMuted);
        navHomeLbl.setTextColor(idx == 0 ? colGold : colMuted);
        navUcpIco.setColorFilter(idx == 1 ? colGold : colMuted);
        navUcpLbl.setTextColor(idx == 1 ? colGold : colMuted);
        navSettingsIco.setColorFilter(idx == 2 ? colGold : colMuted);
        navSettingsLbl.setTextColor(idx == 2 ? colGold : colMuted);

        if (idx == 2) {
            refreshCacheStatus();
        }
    }

    private void updateFpsChips() {
        TextView[] chips = new TextView[]{fps30, fps60, fps90, fps120};
        int[] vals = new int[]{30, 60, 90, 120};
        for (int i = 0; i < chips.length; i++) {
            boolean active = (vals[i] == selectedFps);
            chips[i].setBackgroundResource(active ? R.drawable.srp_pill_lang_active : R.drawable.srp_btn_ghost);
            chips[i].setTextColor(ContextCompat.getColor(this, active ? R.color.srp_text_main : R.color.srp_text_sec));
        }
    }

    private void saveSettingsFromUi() {
        SrpConfig.ClientIniSettings s = new SrpConfig.ClientIniSettings();
        s.fpsLimit = selectedFps;
        s.chatLines = selectedChatLines;
        s.fontSize = selectedFontSize;
        s.showFps = swFpsCounter.isChecked();
        s.androidKeyboard = swAndroidKb.isChecked();
        s.firstPerson = swFirstPerson.isChecked();
        s.cutout = swCutout.isChecked();
        s.hpArmourText = swHpText.isChecked();
        s.pcMoney = swPcMoney.isChecked();
        s.radarRect = swRadarRect.isChecked();
        s.autoAim = swAutoAim.isChecked();
        s.voiceChat = swVoice.isChecked();
        cfg.saveClientSettings(s);
        cfg.syncSettingsIni(this, editUsername.getText().toString().trim(), cfg.getSavedServerPassword());
        toast(t("cfg_saved"));
    }

    // ==================== LIVE UDP SERVER QUERY ====================
    private void refreshServerStatus(boolean showChecking) {
        if (isQuerying) return;
        isQuerying = true;
        if (showChecking) {
            pillStatus.setBackgroundResource(R.drawable.srp_pill_gold);
            dotStatus.setBackgroundResource(R.drawable.srp_dot_gold);
            tvStatusPill.setText(t("status_checking"));
            tvStatusPill.setTextColor(ContextCompat.getColor(this, R.color.srp_gold));
        }
        final String ip = cfg.getServerIp();
        final int port = cfg.getServerPort();
        bgExec.execute(() -> {
            SrpUdpQuery.ServerInfo info = SrpUdpQuery.queryServer(ip, port, 2400);
            mainHandler.post(() -> {
                isQuerying = false;
                serverInfo = info;
                lastServerQueryMs = System.currentTimeMillis();
                renderServerInfo();
                updatePlayAndDataUi();
            });
        });
    }

    private boolean isServerLocked() {
        return serverInfo != null && serverInfo.online && serverInfo.passworded;
    }

    private void renderServerInfo() {
        tvServerIp.setText(cfg.getServerIp() + ":" + cfg.getServerPort());
        if (serverInfo == null) {
            tvSubtitle.setText(t("hero_sub_connecting"));
            return;
        }
        if (serverInfo.online) {
            pillStatus.setBackgroundResource(R.drawable.srp_pill_online);
            dotStatus.setBackgroundResource(R.drawable.srp_dot_green);
            tvStatusPill.setText(t("status_online"));
            tvStatusPill.setTextColor(ContextCompat.getColor(this, R.color.srp_emerald));

            pillHeroLocked.setVisibility(serverInfo.passworded ? View.VISIBLE : View.GONE);
            tvIcClock.setText(serverInfo.worldTime.isEmpty() ? "" : t("ic_time_fmt", serverInfo.worldTime));
            tvHostname.setText(serverInfo.hostname.toUpperCase());
            tvSubtitle.setText(serverInfo.gamemode + "  •  " + serverInfo.mapName + "  •  " + serverInfo.serverVersion);

            tvStatPlayers.setText(serverInfo.players + " / " + serverInfo.maxPlayers);
            if (serverInfo.pingMs >= 0) {
                tvStatPing.setText(serverInfo.pingMs + " MS");
                int pcol = serverInfo.pingMs < 85 ? R.color.srp_emerald
                        : (serverInfo.pingMs < 165 ? R.color.srp_gold : R.color.srp_crimson_hover);
                tvStatPing.setTextColor(ContextCompat.getColor(this, pcol));
            } else {
                tvStatPing.setText("-- MS");
            }
            tvStatGamemode.setText(serverInfo.gamemode.toUpperCase());
            int pct = serverInfo.maxPlayers > 0 ? Math.min(100, (serverInfo.players * 100) / serverInfo.maxPlayers) : 0;
            barCapacity.setProgress(serverInfo.players > 0 ? Math.max(3, pct) : 0);
        } else {
            pillStatus.setBackgroundResource(R.drawable.srp_pill_maint);
            dotStatus.setBackgroundResource(R.drawable.srp_dot_red);
            tvStatusPill.setText(t("status_maint"));
            tvStatusPill.setTextColor(ContextCompat.getColor(this, R.color.srp_crimson_hover));
            pillHeroLocked.setVisibility(View.GONE);
            tvIcClock.setText("");
            tvHostname.setText("SERVER MAINTENANCE");
            tvSubtitle.setText(t("hero_sub_maint"));
            tvStatPlayers.setText("0 / 0");
            tvStatPing.setText("MT");
            tvStatPing.setTextColor(ContextCompat.getColor(this, R.color.srp_crimson_hover));
            tvStatGamemode.setText("MAINTENANCE");
            barCapacity.setProgress(0);
        }
    }

    // ==================== CACHE STATUS & PLAY LAUNCH FLOW ====================
    private void refreshCacheStatus() {
        bgExec.execute(() -> {
            SrpGameDataManager.CacheStatus st = SrpGameDataManager.inspectCache(this);
            mainHandler.post(() -> {
                cacheStatus = st;
                tvCfgStoragePath.setText(st.rootPath);
                updatePlayAndDataUi();
            });
        });
    }

    private void updatePlayAndDataUi() {
        boolean locked = isServerLocked();
        badgePwHome.setVisibility(locked ? View.VISIBLE : View.GONE);

        if (isDownloading) {
            btnPlay.setEnabled(false);
            btnPlay.setBackgroundResource(R.drawable.srp_btn_crimson);
            icoBtnPlay.setImageResource(R.drawable.ic_srp_download);
            icoBtnPlay.setColorFilter(ContextCompat.getColor(this, R.color.srp_text_main));
            tvBtnPlay.setText(t("btn_downloading"));
            tvBtnPlay.setTextColor(ContextCompat.getColor(this, R.color.srp_text_main));
            return;
        }
        if (isCheckingLock) {
            btnPlay.setEnabled(false);
            tvBtnPlay.setText(t("btn_checking_access"));
            return;
        }

        btnPlay.setEnabled(true);
        if (cacheStatus.installed) {
            tvDataStatus.setText(t("data_ready", SrpGameDataManager.formatSize(cacheStatus.totalBytes)));
            tvDataStatus.setTextColor(ContextCompat.getColor(this, R.color.srp_emerald));

            btnPlay.setBackgroundResource(R.drawable.srp_btn_crimson);
            icoBtnPlay.setImageResource(locked ? R.drawable.ic_srp_lock : R.drawable.ic_srp_play);
            icoBtnPlay.setColorFilter(ContextCompat.getColor(this, R.color.srp_text_main));
            tvBtnPlay.setText(t("btn_play"));
            tvBtnPlay.setTextColor(ContextCompat.getColor(this, R.color.srp_text_main));

            if (locked) {
                tvLaunchHint.setText(t("hint_locked"));
                tvLaunchHint.setTextColor(ContextCompat.getColor(this, R.color.srp_gold));
            } else {
                tvLaunchHint.setText(t("hint_ready"));
                tvLaunchHint.setTextColor(ContextCompat.getColor(this, R.color.srp_text_muted));
            }
        } else {
            if (cacheStatus.partial) {
                tvDataStatus.setText(t("data_partial", SrpGameDataManager.formatSize(cacheStatus.totalBytes)));
                tvDataStatus.setTextColor(ContextCompat.getColor(this, R.color.srp_gold));
            } else {
                tvDataStatus.setText(t("data_missing"));
                tvDataStatus.setTextColor(ContextCompat.getColor(this, R.color.srp_crimson_hover));
            }
            btnPlay.setBackgroundResource(R.drawable.srp_btn_gold);
            icoBtnPlay.setImageResource(R.drawable.ic_srp_download);
            icoBtnPlay.setColorFilter(ContextCompat.getColor(this, R.color.srp_bg));
            tvBtnPlay.setText(t("btn_download_data"));
            tvBtnPlay.setTextColor(ContextCompat.getColor(this, R.color.srp_bg));
            tvLaunchHint.setText(t("hint_need_data"));
            tvLaunchHint.setTextColor(ContextCompat.getColor(this, R.color.srp_text_muted));
        }
    }

    private void onPlayPrimaryClicked() {
        if (isDownloading || isCheckingLock) return;

        if (pendingApkUpdate != null && pendingApkUpdate.mandatory) {
            startApkUpdateDownload(pendingApkUpdate);
            return;
        }

        if (!cacheStatus.installed) {
            startDataDownload();
            return;
        }

        String rawUser = editUsername.getText().toString().trim().replace(' ', '_');
        if (rawUser.isEmpty()) {
            toast(t("err_empty_user"));
            editUsername.requestFocus();
            return;
        }
        if (!rawUser.matches("[A-Za-z0-9_]{3,20}")) {
            toast(t("err_bad_user"));
            editUsername.requestFocus();
            return;
        }
        editUsername.setText(rawUser);
        cfg.setUsername(rawUser);

        // Request microphone permission once if voice chat is enabled (optional, won't block play)
        if (cfg.loadClientSettings().voiceChat
                && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
        }

        boolean cacheFresh = serverInfo != null && serverInfo.online
                && (System.currentTimeMillis() - lastServerQueryMs) <= 4500L;
        if (cacheFresh) {
            continueLaunchWithLockState(rawUser, serverInfo.passworded);
            return;
        }

        // Real-time fast UDP 'i' probe right before launching
        isCheckingLock = true;
        updatePlayAndDataUi();
        final String ip = cfg.getServerIp();
        final int port = cfg.getServerPort();
        bgExec.execute(() -> {
            SrpUdpQuery.PasswordProbeResult probe = SrpUdpQuery.queryPasswordFlag(ip, port, 1100);
            mainHandler.post(() -> {
                isCheckingLock = false;
                boolean locked = probe.reachable ? probe.passworded : isServerLocked();
                if (probe.reachable) {
                    lastServerQueryMs = System.currentTimeMillis();
                    if (serverInfo == null) serverInfo = new SrpUdpQuery.ServerInfo();
                    serverInfo.online = true;
                    serverInfo.passworded = locked;
                    renderServerInfo();
                }
                updatePlayAndDataUi();
                continueLaunchWithLockState(rawUser, locked);
            });
        });
    }

    private void continueLaunchWithLockState(String username, boolean locked) {
        if (locked) {
            SearchRpPasswordDialog.show(this, cfg, serverInfo, lang, new SearchRpPasswordDialog.Callback() {
                @Override
                public void onSubmit(String password, boolean remember) {
                    cfg.setSavedServerPassword(password, remember);
                    launchNativeGame(username, password);
                }

                @Override
                public void onCancel() {
                    toast(t("pw_cancelled"));
                }
            });
        } else {
            launchNativeGame(username, "");
        }
    }

    private String getResolvedGpuExt() {
        if (gpuInfo == null || "Detecting...".equals(gpuInfo.renderer)) {
            gpuInfo = SrpGpuHelper.detect();
        }
        return gpuInfo != null ? gpuInfo.ext : "etc";
    }

    private void launchNativeGame(String username, String serverPassword) {
        if (!SrpGameDataManager.inspectCache(this).installed) {
            new AlertDialog.Builder(this)
                    .setTitle("Search Roleplay")
                    .setMessage(t("err_no_data_launch", SrpGameDataManager.getGameFilesDir(this).getAbsolutePath()))
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        // Ensure GPU *.RENAME.* textures, SAMP/fonts/arial_bold.ttf, and case-sensitive files are repaired before launch
        SrpGameDataManager.prepareAndRepairCache(this, getResolvedGpuExt());
        cfg.syncSettingsIni(this, username, serverPassword);
        try {
            Intent intent = new Intent(this, SAMP.class);
            startActivity(intent);
        } catch (Exception e) {
            toast(t("err_launch_fail", e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }

    // ==================== GAME DATA DOWNLOADER & LOCAL ZIP IMPORTER ====================
    private void openLocalZipPicker() {
        if (isDownloading) return;
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/octet-stream", "*/*"});
            startActivityForResult(intent, REQ_PICK_ZIP);
        } catch (Exception e) {
            toast(t("import_failed", e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_ZIP && resultCode == RESULT_OK && data != null && data.getData() != null) {
            startLocalZipImport(data.getData());
        }
    }

    private void startLocalZipImport(Uri zipUri) {
        if (isDownloading) return;
        isDownloading = true;
        cancelDlFlag.set(false);
        switchTab(0);
        panelDownload.setVisibility(View.VISIBLE);
        barDownload.setIndeterminate(true);
        tvDlStats.setText(t("importing_zip", 0));
        updatePlayAndDataUi();

        final String gpuExt = getResolvedGpuExt();
        bgExec.execute(() -> SrpGameDataManager.importLocalZipDataPack(
                this,
                zipUri,
                gpuExt,
                cancelDlFlag,
                new SrpGameDataManager.DownloadCallback() {
                    @Override public void onProgress(int p, double d, double tot, double spd) {}

                    @Override
                    public void onExtracting(int fileCount) {
                        mainHandler.post(() -> tvDlStats.setText(t("importing_zip", fileCount)));
                    }

                    @Override
                    public void onComplete() {
                        mainHandler.post(() -> {
                            isDownloading = false;
                            panelDownload.setVisibility(View.GONE);
                            cfg.syncSettingsIni(SearchRpLauncherActivity.this, editUsername.getText().toString().trim(), "");
                            refreshCacheStatus();
                            toast(t("import_done"));
                        });
                    }

                    @Override
                    public void onCancelled() {
                        mainHandler.post(() -> {
                            isDownloading = false;
                            panelDownload.setVisibility(View.GONE);
                            refreshCacheStatus();
                            toast(t("dl_cancelled"));
                        });
                    }

                    @Override
                    public void onError(String message) {
                        mainHandler.post(() -> {
                            isDownloading = false;
                            panelDownload.setVisibility(View.GONE);
                            refreshCacheStatus();
                            toast(t("import_failed", message));
                        });
                    }
                }
        ));
    }

    private void startDataDownload() {
        String url = cfg.getDataUrl();
        if (url.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Search Roleplay")
                    .setMessage(t("dl_no_url"))
                    .setPositiveButton(t("btn_import_zip"), (d, w) -> openLocalZipPicker())
                    .setNeutralButton(t("tab_settings"), (d, w) -> switchTab(2))
                    .setNegativeButton(t("btn_close"), null)
                    .show();
            return;
        }

        isDownloading = true;
        cancelDlFlag.set(false);
        panelDownload.setVisibility(View.VISIBLE);
        barDownload.setIndeterminate(true);
        tvDlStats.setText(t("dl_connecting"));
        updatePlayAndDataUi();

        final String gpuExt = getResolvedGpuExt();
        bgExec.execute(() -> SrpGameDataManager.downloadAndExtractDataPack(
                this,
                url,
                gpuExt,
                cancelDlFlag,
                new SrpGameDataManager.DownloadCallback() {
                    @Override
                    public void onProgress(int pct, double doneMb, double totalMb, double spd) {
                        mainHandler.post(() -> {
                            if (pct >= 0) {
                                barDownload.setIndeterminate(false);
                                barDownload.setProgress(pct);
                                tvDlStats.setText(t("dl_downloading", doneMb, totalMb, pct, spd));
                            } else {
                                barDownload.setIndeterminate(true);
                                tvDlStats.setText(t("dl_downloading_unk", doneMb, spd));
                            }
                        });
                    }

                    @Override
                    public void onExtracting(int fileCount) {
                        mainHandler.post(() -> {
                            barDownload.setIndeterminate(true);
                            tvDlStats.setText(t("dl_extracting", fileCount));
                        });
                    }

                    @Override
                    public void onComplete() {
                        mainHandler.post(() -> {
                            isDownloading = false;
                            panelDownload.setVisibility(View.GONE);
                            cfg.syncSettingsIni(SearchRpLauncherActivity.this, editUsername.getText().toString().trim(), "");
                            refreshCacheStatus();
                            toast(t("dl_done"));
                        });
                    }

                    @Override
                    public void onCancelled() {
                        mainHandler.post(() -> {
                            isDownloading = false;
                            panelDownload.setVisibility(View.GONE);
                            refreshCacheStatus();
                            toast(t("dl_cancelled"));
                        });
                    }

                    @Override
                    public void onError(String message) {
                        mainHandler.post(() -> {
                            isDownloading = false;
                            panelDownload.setVisibility(View.GONE);
                            refreshCacheStatus();
                            toast(t("dl_failed", message));
                        });
                    }
                }
        ));
    }

    // ==================== REMOTE MANIFEST & APK AUTO-UPDATER ====================
    private void fetchRemoteManifestAsync(boolean manualCheck) {
        bgExec.execute(() -> {
            SrpRemoteManifest.ManifestBundle bundle = SrpRemoteManifest.fetch();
            mainHandler.post(() -> {
                if (bundle == null) {
                    if (manualCheck) toast(t("upd_latest", "v" + BuildConfig.VERSION_NAME));
                    return;
                }
                boolean ipChanged = false;
                if (!bundle.serverIp.isEmpty() && !bundle.serverIp.equals(cfg.getServerIp())) {
                    cfg.setServerIp(bundle.serverIp);
                    ipChanged = true;
                }
                if (bundle.serverPort > 0 && bundle.serverPort != cfg.getServerPort()) {
                    cfg.setServerPort(bundle.serverPort);
                    ipChanged = true;
                }
                if (!bundle.discordUrl.isEmpty()) cfg.setDiscordUrl(bundle.discordUrl);
                if (!bundle.websiteUrl.isEmpty()) cfg.setWebsiteUrl(bundle.websiteUrl);
                if (!bundle.androidDataUrl.isEmpty()) {
                    cfg.setRemoteDataUrl(bundle.androidDataUrl);
                    if (editDataUrl.getText().toString().trim().isEmpty()) {
                        editDataUrl.setText(bundle.androidDataUrl);
                    }
                }
                cfg.setUcpChannels(bundle.ucpVerify, bundle.ucpPanel);
                applyLanguageToUi();

                if (bundle.announcement != null && bundle.announcement.enabled) {
                    activeAnnouncement = bundle.announcement;
                } else {
                    activeAnnouncement = null;
                }

                if (SrpRemoteManifest.isNewerApk(bundle.apk, BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME)) {
                    pendingApkUpdate = bundle.apk;
                } else {
                    pendingApkUpdate = null;
                    if (manualCheck) toast(t("upd_latest", "v" + BuildConfig.VERSION_NAME));
                }
                renderNoticeBanner();
                if (ipChanged) {
                    refreshServerStatus(true);
                }
            });
        });
    }

    private void renderNoticeBanner() {
        if (pendingApkUpdate != null) {
            bannerNotice.setVisibility(View.VISIBLE);
            boolean req = pendingApkUpdate.mandatory;
            bannerNotice.setBackgroundResource(req ? R.drawable.srp_card_crimson_bg : R.drawable.srp_card_gold_bg);
            String verTag = pendingApkUpdate.version.startsWith("v") ? pendingApkUpdate.version : "v" + pendingApkUpdate.version;
            bannerNoticeTag.setText(t(req ? "upd_req_tag" : "upd_opt_tag", verTag));
            bannerNoticeTag.setTextColor(ContextCompat.getColor(this, req ? R.color.srp_crimson_hover : R.color.srp_gold));
            String msg = pendingApkUpdate.changelog.isEmpty() ? verTag : pendingApkUpdate.changelog.get(0);
            bannerNoticeMsg.setText(msg);
            bannerNoticeAction.setText(t("btn_update"));
            bannerNoticeAction.setVisibility(View.VISIBLE);
            bannerNoticeAction.setOnClickListener(v -> startApkUpdateDownload(pendingApkUpdate));
        } else if (activeAnnouncement != null && activeAnnouncement.enabled) {
            bannerNotice.setVisibility(View.VISIBLE);
            bannerNotice.setBackgroundResource(R.drawable.srp_card_gold_bg);
            bannerNoticeTag.setText(activeAnnouncement.title.isEmpty() ? "SEARCH ROLEPLAY" : activeAnnouncement.title);
            bannerNoticeTag.setTextColor(ContextCompat.getColor(this, R.color.srp_gold));
            bannerNoticeMsg.setText(activeAnnouncement.message);
            if (!activeAnnouncement.url.isEmpty()) {
                bannerNoticeAction.setText("Open");
                bannerNoticeAction.setVisibility(View.VISIBLE);
                bannerNoticeAction.setOnClickListener(v -> openUrl(activeAnnouncement.url));
            } else {
                bannerNoticeAction.setVisibility(View.GONE);
            }
        } else {
            bannerNotice.setVisibility(View.GONE);
        }
    }

    private void startApkUpdateDownload(SrpRemoteManifest.ApkUpdateInfo info) {
        if (info == null || info.downloadUrl.isEmpty() || isDownloading) return;
        isDownloading = true;
        cancelDlFlag.set(false);
        switchTab(0);
        panelDownload.setVisibility(View.VISIBLE);
        barDownload.setIndeterminate(true);
        tvDlStats.setText(t("upd_downloading", 0));
        updatePlayAndDataUi();

        bgExec.execute(() -> {
            try {
                File apk = SrpGameDataManager.downloadApkUpdate(
                        this, info.downloadUrl, info.sha256, cancelDlFlag,
                        new SrpGameDataManager.DownloadCallback() {
                            @Override public void onProgress(int pct, double d, double tot, double spd) {
                                mainHandler.post(() -> {
                                    barDownload.setIndeterminate(pct < 0);
                                    if (pct >= 0) barDownload.setProgress(pct);
                                    tvDlStats.setText(t("upd_downloading", Math.max(0, pct)));
                                });
                            }
                            @Override public void onExtracting(int c) {}
                            @Override public void onComplete() {}
                            @Override public void onCancelled() {}
                            @Override public void onError(String m) {}
                        }
                );
                mainHandler.post(() -> {
                    isDownloading = false;
                    panelDownload.setVisibility(View.GONE);
                    updatePlayAndDataUi();
                    if (apk != null && apk.isFile()) {
                        toast(t("upd_installing"));
                        SrpGameDataManager.triggerApkInstall(SearchRpLauncherActivity.this, apk);
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    isDownloading = false;
                    panelDownload.setVisibility(View.GONE);
                    updatePlayAndDataUi();
                    toast(t("upd_err", e.getMessage() != null ? e.getMessage() : e.toString()));
                });
            }
        });
    }

    // ==================== HELPERS ====================
    private void showSampLogDialog() {
        bgExec.execute(() -> {
            String log = SrpGameDataManager.readTailLog(this, 60);
            mainHandler.post(() -> new AlertDialog.Builder(this)
                    .setTitle(t("log_title"))
                    .setMessage(log.isEmpty() ? t("log_empty") : log)
                    .setPositiveButton(t("btn_close"), null)
                    .show());
        });
    }

    private void copyToClipboard(String label, String text, String toastKey) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText(label, text));
                toast(t(toastKey));
                icoCopyIp.setImageResource(R.drawable.ic_srp_check);
                icoCopyIp.setColorFilter(ContextCompat.getColor(this, R.color.srp_emerald));
                mainHandler.postDelayed(() -> {
                    icoCopyIp.setImageResource(R.drawable.ic_srp_copy);
                    icoCopyIp.setColorFilter(ContextCompat.getColor(this, R.color.srp_gold));
                }, 1500L);
            }
        } catch (Exception ignored) {
        }
    }

    private void openUrl(String url) {
        if (url == null || url.trim().isEmpty()) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url.trim())));
        } catch (Exception ignored) {
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
