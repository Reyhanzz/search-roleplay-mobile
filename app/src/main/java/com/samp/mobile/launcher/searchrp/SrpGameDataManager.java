package com.samp.mobile.launcher.searchrp;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import androidx.core.content.FileProvider;

import com.samp.mobile.R;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * All-in-One Android Game Data Cache Manager, GPU Auto-Renamer, Local ZIP Importer & APK Auto-Updater.
 * Stores all GTA SA 2.10 files inside getExternalFilesDir(null) -> Android/data/com.searchroleplay.mobile/files/
 * so Android 11-15 work seamlessly with ZERO Scoped Storage errors and ZERO ZArchiver.
 */
public final class SrpGameDataManager {

    public static class CacheStatus {
        public boolean installed = false;
        public boolean partial = false;
        public long totalBytes = 0L;
        public String rootPath = "";
    }

    public interface DownloadCallback {
        void onProgress(int percent, double doneMb, double totalMb, double speedMbSec);
        void onExtracting(int fileCount);
        void onComplete();
        void onCancelled();
        void onError(String message);
    }

    public static File getGameFilesDir(Context ctx) {
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) dir = ctx.getFilesDir();
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static CacheStatus inspectCache(Context ctx) {
        CacheStatus st = new CacheStatus();
        File root = getGameFilesDir(ctx);
        st.rootPath = root.getAbsolutePath();

        File texdb = new File(root, "texdb");
        File audio = new File(root, "audio");
        File data = new File(root, "data");
        File samp = new File(root, "SAMP");

        boolean hasTex = texdb.isDirectory() && hasFiles(texdb);
        boolean hasAudio = audio.isDirectory() && hasFiles(audio);
        boolean hasData = data.isDirectory() && hasFiles(data);
        boolean hasSamp = samp.isDirectory();

        long bytes = dirSizeShallowFast(texdb) + dirSizeShallowFast(audio)
                + dirSizeShallowFast(data) + dirSizeShallowFast(samp)
                + dirSizeShallowFast(new File(root, "models"))
                + dirSizeShallowFast(new File(root, "anim"));
        st.totalBytes = bytes;

        // Valid GTA SA Android Lite/Full pack has texdb + (audio or data) and >= 50 MB total
        if (hasTex && (hasAudio || hasData) && bytes >= 50L * 1024L * 1024L) {
            st.installed = true;
        } else if (hasTex || hasAudio || hasData || (hasSamp && bytes > 1024L * 1024L)) {
            st.partial = true;
        }
        return st;
    }

    public static String formatSize(long bytes) {
        if (bytes >= 1024L * 1024L * 1024L) {
            return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
        return String.format(Locale.US, "%.0f MB", bytes / (1024.0 * 1024.0));
    }

    private static boolean hasFiles(File dir) {
        File[] list = dir.listFiles();
        return list != null && list.length > 0;
    }

    private static long dirSizeShallowFast(File dir) {
        if (dir == null || !dir.exists()) return 0L;
        if (dir.isFile()) return dir.length();
        long sum = 0L;
        File[] files = dir.listFiles();
        if (files == null) return 0L;
        for (File f : files) {
            if (f.isFile()) {
                sum += f.length();
            } else if (f.isDirectory()) {
                sum += dirSizeShallowFast(f);
            }
        }
        return sum;
    }

    public static String resolveDirectDownloadUrl(String rawUrl) {
        if (rawUrl == null) return "";
        String url = rawUrl.trim();
        if (url.contains("drive.google.com")) {
            Matcher m = Pattern.compile("/file/d/([a-zA-Z0-9_-]+)").matcher(url);
            if (!m.find()) {
                m = Pattern.compile("[?&]id=([a-zA-Z0-9_-]+)").matcher(url);
                if (!m.find()) return url;
            }
            return "https://drive.usercontent.google.com/download?id=" + m.group(1) + "&export=download&confirm=t";
        }
        if (url.contains("dropbox.com")) {
            return url.replace("?dl=0", "?dl=1").replace("&dl=0", "&dl=1");
        }
        return url;
    }

    public static void downloadAndExtractDataPack(
            Context ctx, String rawUrl, String gpuExt, AtomicBoolean cancelFlag, DownloadCallback cb) {
        File root = getGameFilesDir(ctx);
        File dlDir = new File(root, "download");
        if (!dlDir.exists()) dlDir.mkdirs();
        File zipFile = new File(dlDir, "searchrp_mobile_data.zip");

        String directUrl = resolveDirectDownloadUrl(rawUrl);
        if (directUrl.isEmpty()) {
            cb.onError("Empty download URL");
            return;
        }

        try {
            downloadFileWithProgress(directUrl, zipFile, cancelFlag, cb);
            if (cancelFlag.get()) {
                safeDelete(zipFile);
                cb.onCancelled();
                return;
            }
            try (InputStream fis = new FileInputStream(zipFile)) {
                extractZipStreamSmart(fis, root, gpuExt, cancelFlag, cb);
            }
            safeDelete(zipFile);
            if (cancelFlag.get()) {
                cb.onCancelled();
                return;
            }
            prepareAndRepairCache(ctx, gpuExt);
            cb.onComplete();
        } catch (Exception e) {
            safeDelete(zipFile);
            if (cancelFlag.get()) {
                cb.onCancelled();
            } else {
                cb.onError(e.getMessage() != null ? e.getMessage() : e.toString());
            }
        }
    }

    /**
     * Import and extract a local GTA SA Android 2.10 .zip directly from phone storage via SAF Uri.
     */
    public static void importLocalZipDataPack(
            Context ctx, Uri zipUri, String gpuExt, AtomicBoolean cancelFlag, DownloadCallback cb) {
        File root = getGameFilesDir(ctx);
        try (InputStream is = ctx.getContentResolver().openInputStream(zipUri)) {
            if (is == null) {
                cb.onError("Cannot read selected .zip file");
                return;
            }
            extractZipStreamSmart(is, root, gpuExt, cancelFlag, cb);
            if (cancelFlag.get()) {
                cb.onCancelled();
                return;
            }
            prepareAndRepairCache(ctx, gpuExt);
            cb.onComplete();
        } catch (Exception e) {
            if (cancelFlag.get()) {
                cb.onCancelled();
            } else {
                cb.onError(e.getMessage() != null ? e.getMessage() : e.toString());
            }
        }
    }

    /**
     * Auto-repairs installed GTA SA 2.10 cache for this device:
     * 1) Renames any texdb/*.RENAME.* (and mismatched single-variant GPU files) to match detected GPU (.dxt / .etc / .pvr)
     * 2) Ensures Linux case-sensitive SAMP/weapon.dat, SAMP/timecyc.dat & SAMP/samp.ide exist
     * 3) Ensures SAMP/fonts/arial_bold.ttf, Arial.ttf, and arial.ttf exist (prevents native ImGui null-widget SIGSEGV)
     */
    public static int prepareAndRepairCache(Context ctx, String gpuExt) {
        String safeGpu = (gpuExt == null || gpuExt.trim().isEmpty()) ? "etc" : gpuExt.trim().toLowerCase(Locale.US);
        File root = getGameFilesDir(ctx);
        int fixed = 0;

        // 1. Rename any leftover *.RENAME.* files in texdb/ and align single-variant databases to detected GPU
        File texdb = new File(root, "texdb");
        if (texdb.isDirectory()) {
            fixed += renameGpuPlaceholdersRecursive(texdb, safeGpu);
            String[] singleVariantDbs = new String[]{"gta3", "gta_int", "mobile", "txd", "cutscene"};
            String[] altExts = new String[]{"dxt", "etc", "pvr"};
            String[] suffixes = new String[]{"dat", "toc", "tmb"};
            for (String db : singleVariantDbs) {
                File dbDir = new File(texdb, db);
                if (!dbDir.isDirectory()) continue;
                File expectedDat = new File(dbDir, db + "." + safeGpu + ".dat");
                if (expectedDat.isFile()) continue;
                for (String alt : altExts) {
                    if (alt.equals(safeGpu)) continue;
                    File altDat = new File(dbDir, db + "." + alt + ".dat");
                    if (altDat.isFile()) {
                        for (String suf : suffixes) {
                            File srcF = new File(dbDir, db + "." + alt + "." + suf);
                            File dstF = new File(dbDir, db + "." + safeGpu + "." + suf);
                            if (srcF.isFile() && !dstF.exists() && srcF.renameTo(dstF)) {
                                fixed++;
                            }
                        }
                        break;
                    }
                }
            }
        }

        // 2. Ensure lowercase SAMP/weapon.dat, SAMP/timecyc.dat, and SAMP/samp.ide exist on case-sensitive Android FS
        File sampDir = new File(root, "SAMP");
        if (!sampDir.exists()) sampDir.mkdirs();

        File weaponLower = new File(sampDir, "weapon.dat");
        if (!weaponLower.isFile()) {
            File wUpper = new File(sampDir, "WEAPON.dat");
            File wData = new File(new File(root, "data"), "weapon.dat");
            if (wUpper.isFile() && copyFile(wUpper, weaponLower)) fixed++;
            else if (wData.isFile() && copyFile(wData, weaponLower)) fixed++;
        }

        File tcLower = new File(sampDir, "timecyc.dat");
        if (!tcLower.isFile()) {
            File tcUpper = new File(sampDir, "TIMECYC.DAT");
            if (tcUpper.isFile() && copyFile(tcUpper, tcLower)) fixed++;
        }

        File ideLower = new File(sampDir, "samp.ide");
        if (!ideLower.isFile()) {
            File ideUpper = new File(sampDir, "SAMP.ide");
            if (ideUpper.isFile() && copyFile(ideUpper, ideLower)) fixed++;
        }

        // 3. Ensure SAMP/fonts/arial_bold.ttf, Arial.ttf, and arial.ttf exist (cpp/samp/main.h loads arial_bold.ttf!)
        File fontDir = new File(sampDir, "fonts");
        if (!fontDir.exists()) fontDir.mkdirs();
        File rootFonts = new File(root, "fonts");

        File targetBold = new File(fontDir, "arial_bold.ttf");
        if (!targetBold.isFile()) {
            File srcBold = new File(rootFonts, "arial_bold.ttf");
            File srcArial = new File(rootFonts, "arial.ttf");
            File srcTargetArial = new File(fontDir, "Arial.ttf");
            if (srcBold.isFile() && copyFile(srcBold, targetBold)) fixed++;
            else if (srcArial.isFile() && copyFile(srcArial, targetBold)) fixed++;
            else if (srcTargetArial.isFile() && copyFile(srcTargetArial, targetBold)) fixed++;
            else if (copyRawResourceToFile(ctx, R.font.barlow_semibold, targetBold)) fixed++;
        }

        File targetArial = new File(fontDir, "Arial.ttf");
        if (!targetArial.isFile()) {
            File rootArial = new File(rootFonts, "arial.ttf");
            File rootArialCap = new File(rootFonts, "Arial.ttf");
            if (rootArial.isFile() && copyFile(rootArial, targetArial)) fixed++;
            else if (rootArialCap.isFile() && copyFile(rootArialCap, targetArial)) fixed++;
            else if (targetBold.isFile() && copyFile(targetBold, targetArial)) fixed++;
            else if (copyRawResourceToFile(ctx, R.font.barlow_medium, targetArial)) fixed++;
        }

        File targetArialLower = new File(fontDir, "arial.ttf");
        if (!targetArialLower.isFile() && targetArial.isFile()) {
            if (copyFile(targetArial, targetArialLower)) fixed++;
        }

        return fixed;
    }

    private static boolean copyRawResourceToFile(Context ctx, int resId, File dst) {
        try (InputStream in = ctx.getResources().openRawResource(resId);
             BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(dst))) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static int renameGpuPlaceholdersRecursive(File dir, String gpuExt) {
        int count = 0;
        File[] list = dir.listFiles();
        if (list == null) return 0;
        for (File f : list) {
            if (f.isDirectory()) {
                count += renameGpuPlaceholdersRecursive(f, gpuExt);
            } else if (f.isFile()) {
                String name = f.getName();
                String replaced = replaceRenamePlaceholder(name, gpuExt);
                if (!replaced.equals(name)) {
                    File dst = new File(f.getParentFile(), replaced);
                    if (dst.exists()) dst.delete();
                    if (f.renameTo(dst)) count++;
                }
            }
        }
        return count;
    }

    private static String replaceRenamePlaceholder(String pathOrName, String gpuExt) {
        if (pathOrName == null) return "";
        return pathOrName.replaceAll("(?i)\\.rename\\.", "." + gpuExt + ".");
    }

    private static boolean copyFile(File src, File dst) {
        try (InputStream in = new BufferedInputStream(new FileInputStream(src), 32768);
             BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(dst), 32768)) {
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void downloadFileWithProgress(
            String urlStr, File destFile, AtomicBoolean cancelFlag, DownloadCallback cb) throws IOException {
        HttpURLConnection conn = openWithRedirects(urlStr, 5);
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code);
            }
            long totalBytes = conn.getContentLengthLong();
            long downloaded = 0L;
            long lastTime = System.nanoTime();
            long lastBytes = 0L;

            try (InputStream in = new BufferedInputStream(conn.getInputStream(), 65536);
                 BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(destFile), 65536)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (cancelFlag.get()) return;
                    out.write(buf, 0, n);
                    downloaded += n;

                    long now = System.nanoTime();
                    double dt = (now - lastTime) / 1_000_000_000.0;
                    if (dt >= 0.25) {
                        double spd = ((downloaded - lastBytes) / (1024.0 * 1024.0)) / Math.max(0.01, dt);
                        lastTime = now;
                        lastBytes = downloaded;
                        int pct = totalBytes > 0 ? (int) Math.min(100L, (downloaded * 100L) / totalBytes) : -1;
                        cb.onProgress(pct, downloaded / (1024.0 * 1024.0),
                                totalBytes > 0 ? totalBytes / (1024.0 * 1024.0) : -1.0, spd);
                    }
                }
                out.flush();
            }
        } finally {
            conn.disconnect();
        }
    }

    private static HttpURLConnection openWithRedirects(String urlStr, int maxRedirects) throws IOException {
        String current = urlStr;
        for (int i = 0; i <= maxRedirects; i++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(25000);
            conn.setRequestProperty("User-Agent", "SearchRoleplayMobile/1.0");
            int code = conn.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null || loc.isEmpty()) throw new IOException("Empty redirect");
                current = new URL(new URL(current), loc).toString();
                continue;
            }
            return conn;
        }
        throw new IOException("Too many redirects");
    }

    /**
     * Extracts ZIP stream into targetRoot while:
     * - Stripping wrapper prefixes ("files/", "com.rockstargames.gtasa/files/", etc.)
     * - Automatically renaming *.RENAME.* textures to the device's GPU (.dxt / .etc / .pvr)
     * - Preserving player's SAMP/settings.ini
     */
    private static void extractZipStreamSmart(
            InputStream rawStream, File targetRoot, String gpuExt, AtomicBoolean cancelFlag, DownloadCallback cb)
            throws IOException {
        String safeGpu = (gpuExt == null || gpuExt.trim().isEmpty()) ? "etc" : gpuExt.trim().toLowerCase(Locale.US);
        String canonicalRoot = targetRoot.getCanonicalPath() + File.separator;
        int extractedCount = 0;

        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(rawStream, 65536))) {
            ZipEntry entry;
            byte[] buf = new byte[65536];
            while ((entry = zis.getNextEntry()) != null) {
                if (cancelFlag.get()) return;
                String relPath = normalizeZipEntryPath(entry.getName());
                if (relPath == null || relPath.isEmpty()) {
                    zis.closeEntry();
                    continue;
                }

                // Auto-rename Universal GTA SA Mobile *.RENAME.* textures to real device GPU extension
                relPath = replaceRenamePlaceholder(relPath, safeGpu);

                // Never overwrite player's synced SAMP/settings.ini if already configured
                if (relPath.equalsIgnoreCase("SAMP/settings.ini") && new File(targetRoot, relPath).isFile()) {
                    zis.closeEntry();
                    continue;
                }

                File outFile = new File(targetRoot, relPath);
                String canonOut = outFile.getCanonicalPath();
                if (!canonOut.startsWith(canonicalRoot)) {
                    zis.closeEntry();
                    continue;
                }

                if (entry.isDirectory()) {
                    outFile.mkdirs();
                } else {
                    File parent = outFile.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(outFile), 65536)) {
                        int len;
                        while ((len = zis.read(buf)) > 0) {
                            if (cancelFlag.get()) return;
                            bos.write(buf, 0, len);
                        }
                        bos.flush();
                    }
                    extractedCount++;
                    if (extractedCount % 10 == 0) {
                        cb.onExtracting(extractedCount);
                    }
                }
                zis.closeEntry();
            }
        }
        cb.onExtracting(extractedCount);
    }

    private static String normalizeZipEntryPath(String rawName) {
        if (rawName == null) return "";
        String norm = rawName.replace('\\', '/');
        while (norm.startsWith("/")) norm = norm.substring(1);

        String lower = norm.toLowerCase(Locale.US);
        int filesIdx = lower.indexOf("/files/");
        if (filesIdx >= 0) {
            norm = norm.substring(filesIdx + "/files/".length());
        } else if (lower.startsWith("files/")) {
            norm = norm.substring("files/".length());
        } else {
            int slash = norm.indexOf('/');
            if (slash > 0) {
                String rest = norm.substring(slash + 1);
                String rl = rest.toLowerCase(Locale.US);
                if (rl.startsWith("texdb/") || rl.startsWith("audio/") || rl.startsWith("data/")
                        || rl.startsWith("samp/") || rl.startsWith("models/") || rl.startsWith("anim/")
                        || rl.startsWith("text/") || rl.startsWith("textures/") || rl.startsWith("fonts/")) {
                    norm = rest;
                }
            }
        }
        return norm;
    }

    public static File downloadApkUpdate(
            Context ctx, String apkUrl, String expectedSha256, AtomicBoolean cancelFlag, DownloadCallback cb)
            throws Exception {
        File dlDir = new File(getGameFilesDir(ctx), "download");
        if (!dlDir.exists()) dlDir.mkdirs();
        File apkFile = new File(dlDir, "SearchRoleplayMobile_update.apk");
        safeDelete(apkFile);

        downloadFileWithProgress(apkUrl, apkFile, cancelFlag, cb);
        if (cancelFlag.get()) {
            safeDelete(apkFile);
            return null;
        }
        if (expectedSha256 != null && !expectedSha256.trim().isEmpty()) {
            String actual = computeSha256(apkFile);
            if (!actual.equalsIgnoreCase(expectedSha256.trim())) {
                safeDelete(apkFile);
                throw new IOException("APK SHA-256 verification failed!");
            }
        }
        return apkFile;
    }

    public static void triggerApkInstall(Context ctx, File apkFile) {
        Uri uri;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".provider", apkFile);
        } else {
            uri = Uri.fromFile(apkFile);
        }
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        ctx.startActivity(intent);
    }

    public static String readTailLog(Context ctx, int maxLines) {
        File root = getGameFilesDir(ctx);
        StringBuilder combined = new StringBuilder();

        File javaLog = new File(root, "samp_java_log.txt");
        if (javaLog.isFile() && javaLog.length() > 0) {
            combined.append("=== LAUNCHER / JAVA LOG ===\n");
            combined.append(readFileTail(javaLog, Math.min(40, maxLines))).append("\n\n");
        }

        File logFile = new File(root, "samp_log.txt");
        if (!logFile.isFile()) {
            logFile = new File(new File(root, "SAMP"), "samp_log.txt");
        }
        if (logFile.isFile() && logFile.length() > 0) {
            combined.append("=== NATIVE SAMP LOG ===\n");
            combined.append(readFileTail(logFile, maxLines));
        }
        return combined.toString().trim();
    }

    private static String readFileTail(File logFile, int maxLines) {
        java.util.ArrayDeque<String> deque = new java.util.ArrayDeque<>(maxLines + 2);
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(logFile), StandardCharsets.UTF_8))) {
            String ln;
            while ((ln = br.readLine()) != null) {
                if (deque.size() >= maxLines) deque.removeFirst();
                deque.addLast(ln);
            }
        } catch (Exception ignored) {
        }
        StringBuilder sb = new StringBuilder();
        for (String s : deque) sb.append(s).append("\n");
        return sb.toString().trim();
    }

    private static String computeSha256(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(new FileInputStream(file), 65536)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) sb.append(String.format(Locale.US, "%02x", b));
        return sb.toString();
    }

    private static void safeDelete(File f) {
        if (f != null && f.exists()) {
            try {
                f.delete();
            } catch (Exception ignored) {
            }
        }
    }
}
