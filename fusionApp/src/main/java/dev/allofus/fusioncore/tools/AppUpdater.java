package dev.allofus.fusioncore.tools;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.allofus.fusioncore.UpdateInstallReceiver;

/**
 * Looks for new versions of the app in the GitHub releases of Darkusze/P5XTES.Android.Launcher, downloads the
 * APK and hands it to the system installer.
 * <p>
 * Notes for publishing: the APK must be signed with the same key as the installed app, and its versionCode must be
 * higher than the installed one, otherwise Android refuses the update.
 */
public final class AppUpdater {
    private static final String TAG = "P5XTES";
    private static final String RELEASES_API =
            "https://api.github.com/repos/Darkusze/P5XTES.Android.Launcher/releases?per_page=10";
    private static final String PREFS = "app_updates";
    private static final String KEY_LAST_CHECK = "last_check";
    private static final String KEY_LATEST_TAG = "latest_tag";
    private static final int MAX_REDIRECTS = 5;
    private static final Pattern VERSION = Pattern.compile("(\\d+(?:\\.\\d+)*)");

    public record Release(String tag, String name, String notes, String pageUrl,
                          String apkName, String apkUrl, long apkSize) {}

    public interface Listener {
        /** totalBytes is -1 when the server did not report a size. */
        void onDownloadProgress(long downloadedBytes, long totalBytes);
    }

    private AppUpdater() {}

    // ------------------------------------------------------------------------------------------------
    // Versions
    // ------------------------------------------------------------------------------------------------

    public static String currentVersionName(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionName != null ? info.versionName : "?";
        } catch (Exception e) {
            return "?";
        }
    }

    @SuppressWarnings("deprecation")
    public static long currentVersionCode(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? info.getLongVersionCode() : info.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /** True when {@code latest} ("v0.1.1", "0.2.0"...) is a higher version than {@code current} ("0.1.0"). */
    public static boolean isNewer(String latest, String current) {
        int[] a = parse(latest);
        int[] b = parse(current);
        if (a.length == 0) return false;
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int[] parse(String version) {
        if (version == null) return new int[0];
        Matcher matcher = VERSION.matcher(version);
        if (!matcher.find()) return new int[0];
        String[] parts = matcher.group(1).split("\\.");
        int[] numbers = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                numbers[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                numbers[i] = Integer.MAX_VALUE;
            }
        }
        return numbers;
    }

    // ------------------------------------------------------------------------------------------------
    // Cache of the last check (so the main screen can show a banner without hitting the network every time)
    // ------------------------------------------------------------------------------------------------

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void saveResult(Context context, Release release) {
        prefs(context).edit()
                .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                .putString(KEY_LATEST_TAG, release == null ? "" : release.tag())
                .apply();
    }

    public static long lastCheckMillis(Context context) {
        return prefs(context).getLong(KEY_LAST_CHECK, 0);
    }

    public static String cachedLatestTag(Context context) {
        return prefs(context).getString(KEY_LATEST_TAG, "");
    }

    // ------------------------------------------------------------------------------------------------
    // GitHub
    // ------------------------------------------------------------------------------------------------

    /** Newest published (non-draft, non-prerelease) release that carries an APK, or null if there is none. */
    public static Release fetchLatest() throws IOException, JSONException {
        JSONArray releases = new JSONArray(readString(RELEASES_API));
        for (int i = 0; i < releases.length(); i++) {
            JSONObject release = releases.getJSONObject(i);
            if (release.optBoolean("draft", false) || release.optBoolean("prerelease", false)) continue;
            JSONArray assets = release.optJSONArray("assets");
            if (assets == null) continue;

            for (int j = 0; j < assets.length(); j++) {
                JSONObject asset = assets.getJSONObject(j);
                String name = asset.optString("name", "");
                String url = asset.optString("browser_download_url", "");
                if (url.isEmpty() || !name.toLowerCase(Locale.ROOT).endsWith(".apk")) continue;
                return new Release(
                        release.optString("tag_name", ""),
                        release.optString("name", ""),
                        release.optString("body", ""),
                        release.optString("html_url", ""),
                        name, url, asset.optLong("size", -1));
            }
        }
        return null;
    }

    /** Downloads the release APK into cacheDir (replacing any previous download). */
    public static File downloadApk(Release release, File cacheDir, Listener listener) throws IOException {
        File apk = new File(cacheDir, "p5xtes-update.apk");
        //noinspection ResultOfMethodCallIgnored
        apk.delete();

        HttpURLConnection connection = open(release.apkUrl());
        try {
            long total = connection.getContentLengthLong();
            try (InputStream in = new BufferedInputStream(connection.getInputStream());
                 OutputStream out = new FileOutputStream(apk)) {
                byte[] buffer = new byte[64 * 1024];
                long downloaded = 0;
                long lastReport = 0;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    downloaded += read;
                    long now = System.currentTimeMillis();
                    if (listener != null && now - lastReport > 100) {
                        lastReport = now;
                        listener.onDownloadProgress(downloaded, total);
                    }
                }
                if (listener != null) listener.onDownloadProgress(downloaded, total > 0 ? total : downloaded);
            }
        } finally {
            connection.disconnect();
        }

        // An APK is a zip file: it must start with "PK".
        try (InputStream in = new FileInputStream(apk)) {
            if (in.read() != 'P' || in.read() != 'K') {
                //noinspection ResultOfMethodCallIgnored
                apk.delete();
                throw new IOException("The downloaded file is not a valid APK.");
            }
        }
        return apk;
    }

    // ------------------------------------------------------------------------------------------------
    // Install (system installer asks the user to confirm)
    // ------------------------------------------------------------------------------------------------

    public static void install(Context context, File apk) throws IOException {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setSize(apk.length());

        int sessionId = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(sessionId)) {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite("p5xtes.apk", 0, apk.length())) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                session.fsync(out);
            }

            Intent intent = new Intent(context, UpdateInstallReceiver.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent pending = PendingIntent.getBroadcast(context, sessionId, intent, flags);
            session.commit(pending.getIntentSender());
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "Failed to install the update", e);
            try {
                installer.abandonSession(sessionId);
            } catch (Exception ignored) {
                // session already gone
            }
            throw e instanceof IOException ? (IOException) e : new IOException(e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------------------------------------

    private static String readString(String url) throws IOException {
        HttpURLConnection connection = open(url);
        try (InputStream in = new BufferedInputStream(connection.getInputStream())) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString("UTF-8");
        } finally {
            connection.disconnect();
        }
    }

    /** Opens the URL following redirects (GitHub release downloads redirect to another host). */
    private static HttpURLConnection open(String url) throws IOException {
        String current = url;
        for (int i = 0; i <= MAX_REDIRECTS; i++) {
            HttpURLConnection connection = (HttpURLConnection) new URL(current).openConnection();
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(30_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "P5XTES-Android");
            connection.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream, */*");

            int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP
                    || code == HttpURLConnection.HTTP_SEE_OTHER || code == 307 || code == 308) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null) throw new IOException("Redirect without a location from " + current);
                current = new URL(new URL(current), location).toString();
                continue;
            }
            if (code != HttpURLConnection.HTTP_OK) {
                connection.disconnect();
                throw new IOException("HTTP " + code + " from " + current);
            }
            return connection;
        }
        throw new IOException("Too many redirects for " + url);
    }
}
