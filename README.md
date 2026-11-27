# Search Roleplay Mobile (Official Android Launcher & Client)

All-in-One Android Launcher & SA-MP / OpenMP Mobile Client untuk **Search Roleplay** (`151.246.170.113:7777`).

## Fitur Utama
- **All-in-One APK (`com.searchroleplay.mobile`):** Launcher Noir + native GTA SA 2.10 client (`armeabi-v7a` + `arm64-v8a`) dalam 1 aplikasi, aman dari pembatasan Scoped Storage Android 11 sampai 15 (tanpa ZArchiver).
- **Live Server Query & Auto Password Gate:** Deteksi status online, jumlah pemain, ping, jam IC, dan modal password GTA Noir otomatis jika server terkunci.
- **Live EN / ID Language Toggle:** Default English (`EN`) dengan tombol ganti cepat ke Bahasa Indonesia (`ID`).
- **Built-in GTA Data Pack Downloader & Smart Extractor:** Cukup isi `android_data_url` di `manifest_mobile.json` (support Direct Link, Google Drive, atau Dropbox), pemain tinggal ketuk **Download Game Data**.
- **Real `SAMP/settings.ini` HUD Configurator:** Atur FPS Limit (`30/60/90/120`), baris chat, ukuran font, FPS counter, keyboard Android, kamera first-person, cutout notch, HP/Armour angka, uang gaya PC, radar kotak, auto-aim, dan voice chat langsung dari tab Settings.
- **Auto-Update APK & Remote Config:** Sinkron IP/Port, link Discord, link data GTA, pengumuman, dan update APK dari GitHub Releases dengan verifikasi SHA-256.

## Cara Build APK Otomatis (GitHub Actions)
1. Buat repository GitHub baru bernama `search-roleplay-mobile` (Public) di akun `Reyhanzz`.
2. Push isi folder ini ke branch `main`.
3. Buka tab **Actions** -> workflow **Build & Release Search Roleplay Mobile APK** berjalan otomatis dan menghasilkan artifact `SearchRoleplayMobile.apk`.
4. Untuk merilis ke halaman Releases (supaya pemain dapat auto-update), buat tag `v1.0.0` dan push ke GitHub.
