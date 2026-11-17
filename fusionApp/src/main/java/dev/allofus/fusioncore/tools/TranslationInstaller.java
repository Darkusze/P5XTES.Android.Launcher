package dev.allofus.fusioncore.tools;

import android.util.Log;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Enumeration;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Finds the P5XTES translation packs in the GitHub releases and installs them inside the game's BepInEx folder.
 * <p>
 * Packs are looked up by file name (so new versions are found without changing the app):
 * "Only.text.translation.x.y.z.zip", "Only.texture.translation.x.y.z.zip" and, if published,
 * "P5XTESAL.Android.x.y.z.zip" (the "AL" keeps it apart from the desktop P5XTES packages).
 */
public final class TranslationInstaller {
    private static final String TAG = "P5XTES";
    private static final String RELEASES_API = "https://api.github.com/repos/Darkusze/P5XTES/releases?per_page=30";
    private static final int MAX_REDIRECTS = 5;

    public enum Kind {
        /** Optional base package ("P5XTESAL.Android.1.0.0.zip"): plugins and config for the BepInEx that FusionCore ships. */
        BUNDLE("p5xtesal.android"),
        TEXT("text.translation"),
        TEXTURE("texture.translation");

        final String namePart;

        Kind(String namePart) {
            this.namePart = namePart;
        }
    }

    public record Asset(String name, String url, long size, String releaseTag) {}

    public record InstallResult(int files, Set<String> topLevelFolders) {}

    public interface Listener {
        /** totalBytes is -1 when the server did not report a size. */
        void onDownloadProgress(long downloadedBytes, long totalBytes);

        void onExtracting();
    }

    private TranslationInstaller() {}

    /** Newest release asset of each kind, looking through the latest releases of the repository. */
    public static Map<Kind, Asset> fetchLatestAssets() throws IOException, JSONException {
        String json = readString(RELEASES_API);
        JSONArray releases = new JSONArray(json);
        Map<Kind, Asset> found = new EnumMap<>(Kind.class);

        for (int i = 0; i < releases.length() && found.size() < Kind.values().length; i++) {
            JSONObject release = releases.getJSONObject(i);
            if (release.optBoolean("draft", false)) continue;
            String tag = release.optString("tag_name", "");
            JSONArray assets = release.optJSONArray("assets");
            if (assets == null) continue;

            for (int j = 0; j < assets.length(); j++) {
                JSONObject asset = assets.getJSONObject(j);
                String name = asset.optString("name", "");
                String url = asset.optString("browser_download_url", "");
                if (url.isEmpty() || !name.toLowerCase(Locale.ROOT).endsWith(".zip")) continue;

                // "Only text translation 2.9.2.zip", "Only_text-translation..." etc. all normalise the same way.
                String normalised = name.toLowerCase(Locale.ROOT).replaceAll("[\\s_-]+", ".");
                for (Kind kind : Kind.values()) {
                    if (!found.containsKey(kind) && normalised.contains(kind.namePart)) {
                        found.put(kind, new Asset(name, url, asset.optLong("size", -1), tag));
                    }
                }
            }
        }
        return found;
    }

    /** Downloads the asset and extracts it into bepInExDir, replacing files that already exist. */
    public static InstallResult downloadAndInstall(Asset asset, File bepInExDir, File cacheDir, Listener listener)
            throws IOException {
        return downloadAndInstall(asset, bepInExDir, cacheDir, null, listener);
    }

    /**
     * Same as above, but files that already exist under {@code keepExistingPrefix} (for example "config/") are left
     * untouched, so reinstalling a pack does not wipe the user's settings.
     */
    public static InstallResult downloadAndInstall(Asset asset, File bepInExDir, File cacheDir,
                                                   String keepExistingPrefix, Listener listener) throws IOException {
        if (!bepInExDir.exists() && !bepInExDir.mkdirs()) {
            throw new IOException("Cannot create " + bepInExDir.getAbsolutePath());
        }

        File zip = File.createTempFile("p5xtes-", ".zip", cacheDir);
        try {
            download(asset.url(), zip, listener);
            if (listener != null) listener.onExtracting();
            return extract(zip, bepInExDir, keepExistingPrefix);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            zip.delete();
        }
    }

    private static void download(String url, File target, Listener listener) throws IOException {
        HttpURLConnection connection = open(url);
        try {
            long total = connection.getContentLengthLong();
            try (InputStream in = new BufferedInputStream(connection.getInputStream());
                 OutputStream out = new FileOutputStream(target)) {
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
    }

    static InstallResult extract(File zipFile, File destDir, String keepExistingPrefix) throws IOException {
        String root = destDir.getCanonicalPath() + File.separator;
        Set<String> topLevel = new LinkedHashSet<>();
        int files = 0;
        int kept = 0;
        byte[] buffer = new byte[64 * 1024];

        // Commons Compress instead of java.util.zip: packs made with 7-Zip / Windows may use Deflate64
        // (compression method 9), which java.util.zip rejects with "invalid CEN header".
        try (ZipFile zip = ZipFile.builder().setFile(zipFile).get()) {
            Enumeration<ZipArchiveEntry> entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                if (!entry.isDirectory() && !zip.canReadEntryData(entry)) {
                    throw new IOException("Unsupported compression method in the zip entry " + entry.getName());
                }
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("__MACOSX/")) continue;
                // Packs may already contain the BepInEx folder; we are extracting inside it.
                if (name.regionMatches(true, 0, "BepInEx/", 0, "BepInEx/".length())) {
                    name = name.substring("BepInEx/".length());
                }
                if (name.isEmpty()) continue;

                File target = new File(destDir, name);
                if (!target.getCanonicalPath().startsWith(root)) {
                    throw new IOException("Blocked zip entry outside the install folder: " + entry.getName());
                }

                int slash = name.indexOf('/');
                topLevel.add(slash < 0 ? name : name.substring(0, slash));

                if (entry.isDirectory()) {
                    if (!target.isDirectory() && !target.mkdirs()) {
                        throw new IOException("Cannot create folder " + target.getAbsolutePath());
                    }
                    continue;
                }

                if (keepExistingPrefix != null && target.isFile()
                        && name.regionMatches(true, 0, keepExistingPrefix, 0, keepExistingPrefix.length())) {
                    Log.i(TAG, "Keeping existing " + target);
                    kept++;
                    continue;
                }

                File parent = target.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("Cannot create folder " + parent.getAbsolutePath());
                }
                try (InputStream in = zip.getInputStream(entry);
                     OutputStream out = new FileOutputStream(target)) {
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                }
                files++;
            }
        }

        if (files == 0 && kept == 0) {
            throw new IOException("The downloaded file does not contain any files.");
        }
        Log.i(TAG, "Installed " + files + " files into " + destDir + " (folders: " + topLevel + ")");
        return new InstallResult(files, topLevel);
    }

    private static String readString(String url) throws IOException {
        HttpURLConnection connection = open(url);
        try (InputStream in = new BufferedInputStream(connection.getInputStream())) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
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
