package dev.allofus.fusioncore;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.format.Formatter;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.allofus.fusioncore.tools.P5xGame;
import dev.allofus.fusioncore.tools.TranslationInstaller;
import dev.allofus.fusioncore.tools.TranslationInstaller.Asset;
import dev.allofus.fusioncore.tools.TranslationInstaller.Kind;
import dev.allofus.fusioncore.tools.Utilities;

/** Downloads the P5XTES text and texture translation packs into the game's BepInEx folder. */
public class TranslationsActivity extends AppCompatActivity {
    public static final String EXTRA_PACKAGE_NAME = "package_name";
    private static final String TAG = "P5XTES";
    private static final String PREFS = "translations";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Map<Kind, Section> sections = new EnumMap<>(Kind.class);
    private final Map<Kind, Asset> latest = new EnumMap<>(Kind.class);

    private SharedPreferences prefs;
    private String targetPackage;
    private TextView messageView;
    private Button retryButton;
    private boolean busy;
    private boolean checked;

    private static final class Section {
        final Kind kind;
        final TextView status;
        final ProgressBar progress;
        final Button button;
        final View card;

        Section(Kind kind, View card, TextView status, ProgressBar progress, Button button) {
            this.kind = kind;
            this.card = card;
            this.status = status;
            this.progress = progress;
            this.button = button;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_translations);

        View root = findViewById(R.id.translations_root);
        int basePadding = Math.round(getResources().getDisplayMetrics().density * 16f);
        Utilities.applyWindowInsets(root, basePadding);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        targetPackage = getIntent().getStringExtra(EXTRA_PACKAGE_NAME);
        if (targetPackage == null) targetPackage = P5xGame.findInstalledPackage(getPackageManager());
        if (targetPackage == null) targetPackage = P5xGame.DEFAULT_PACKAGE;

        findViewById(R.id.translations_back).setOnClickListener(v -> finish());

        TextView target = findViewById(R.id.translations_target);
        target.setText(getString(R.string.translations_target_format, targetPackage));
        TextView destination = findViewById(R.id.translations_destination);
        destination.setText(getString(R.string.translations_destination_format, bepInExPath()));

        messageView = findViewById(R.id.translations_message);
        retryButton = findViewById(R.id.translations_retry);
        retryButton.setOnClickListener(v -> checkLatest());

        sections.put(Kind.BUNDLE, bindSection(Kind.BUNDLE, R.id.bundle_card, R.id.bundle_status, R.id.bundle_progress, R.id.bundle_button));
        sections.put(Kind.TEXT, bindSection(Kind.TEXT, R.id.text_card, R.id.text_status, R.id.text_progress, R.id.text_button));
        sections.put(Kind.TEXTURE, bindSection(Kind.TEXTURE, R.id.texture_card, R.id.texture_status, R.id.texture_progress, R.id.texture_button));

        checkLatest();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private Section bindSection(Kind kind, int cardId, int statusId, int progressId, int buttonId) {
        Section section = new Section(kind, findViewById(cardId), findViewById(statusId), findViewById(progressId),
                findViewById(buttonId));
        section.button.setOnClickListener(v -> install(kind));
        section.button.setEnabled(false);
        return section;
    }

    /** Where the packs are installed: /storage/emulated/0/FusionCore/&lt;package&gt;/BepInEx (no folder is created here). */
    private String bepInExPath() {
        return new File(new File(new File(Environment.getExternalStorageDirectory(), "FusionCore"), targetPackage),
                "BepInEx").getAbsolutePath();
    }

    private void checkLatest() {
        checked = false;
        retryButton.setVisibility(View.GONE);
        messageView.setText(R.string.translations_checking);
        for (Section section : sections.values()) {
            section.status.setText(R.string.translations_checking);
            section.button.setEnabled(false);
        }

        executor.execute(() -> {
            try {
                Map<Kind, Asset> assets = TranslationInstaller.fetchLatestAssets();
                runOnUiThread(() -> {
                    latest.clear();
                    latest.putAll(assets);
                    checked = true;
                    messageView.setText("");
                    refreshSections();
                });
            } catch (Exception e) {
                Log.e(TAG, "Failed to read the P5XTES releases", e);
                runOnUiThread(() -> {
                    messageView.setText(getString(R.string.translations_error_format, describe(e)));
                    retryButton.setVisibility(View.VISIBLE);
                    for (Section section : sections.values()) {
                        section.status.setText(R.string.translations_not_found);
                    }
                });
            }
        });
    }

    private void refreshSections() {
        for (Section section : sections.values()) {
            Asset asset = latest.get(section.kind);
            String installed = prefs.getString(prefKey(section.kind), null);

            if (section.kind == Kind.BUNDLE) {
                section.card.setVisibility(asset == null ? View.GONE : View.VISIBLE);
            }

            if (asset == null) {
                section.status.setText(R.string.translations_not_found);
                section.button.setEnabled(false);
                section.button.setText(R.string.translations_action_download);
                continue;
            }

            String latestLine = getString(R.string.translations_latest_format, asset.name(),
                    asset.size() > 0 ? Formatter.formatShortFileSize(this, asset.size()) : "?");
            String installedLine = installed == null
                    ? getString(R.string.translations_not_installed)
                    : getString(R.string.translations_installed_format, installed);
            section.status.setText(latestLine + "\n" + installedLine);

            boolean upToDate = asset.name().equals(installed);
            section.button.setText(upToDate ? R.string.translations_action_reinstall : R.string.translations_action_download);
            section.button.setEnabled(!busy);
        }
    }

    private void install(Kind kind) {
        Asset asset = latest.get(kind);
        if (asset == null || busy || !checked) return;

        if (!hasStorageAccess()) {
            Toast.makeText(this, R.string.translations_storage_required, Toast.LENGTH_LONG).show();
            requestStorageAccess();
            return;
        }

        busy = true;
        Section section = sections.get(kind);
        for (Section s : sections.values()) s.button.setEnabled(false);
        section.progress.setVisibility(View.VISIBLE);
        section.progress.setIndeterminate(true);
        messageView.setText(R.string.translations_starting_download);

        File bepInExDir = Utilities.getExternalFusionCoreDirectory(targetPackage, "BepInEx");
        executor.execute(() -> {
            try {
                String keepExisting = kind == Kind.BUNDLE ? "config/" : null;
                TranslationInstaller.InstallResult result = TranslationInstaller.downloadAndInstall(
                        asset, bepInExDir, getCacheDir(), keepExisting, new TranslationInstaller.Listener() {
                            @Override
                            public void onDownloadProgress(long downloaded, long total) {
                                runOnUiThread(() -> showProgress(section, downloaded, total));
                            }

                            @Override
                            public void onExtracting() {
                                runOnUiThread(() -> {
                                    section.progress.setIndeterminate(true);
                                    messageView.setText(R.string.translations_extracting);
                                });
                            }
                        });

                prefs.edit().putString(prefKey(kind), asset.name()).apply();
                runOnUiThread(() -> finishInstall(section, getString(R.string.translations_done_format,
                        result.files(), String.join(", ", result.topLevelFolders()))));
            } catch (Exception e) {
                Log.e(TAG, "Failed to install " + asset.name(), e);
                runOnUiThread(() -> finishInstall(section, getString(R.string.translations_error_format, describe(e))));
            }
        });
    }

    private void showProgress(Section section, long downloaded, long total) {
        if (total > 0) {
            int percent = (int) Math.min(100, downloaded * 100 / total);
            section.progress.setIndeterminate(false);
            section.progress.setMax(100);
            section.progress.setProgress(percent);
            messageView.setText(getString(R.string.translations_downloading_format, percent,
                    Formatter.formatShortFileSize(this, downloaded), Formatter.formatShortFileSize(this, total)));
        } else {
            section.progress.setIndeterminate(true);
            messageView.setText(getString(R.string.translations_downloading_unknown_size_format,
                    Formatter.formatShortFileSize(this, downloaded)));
        }
    }

    private void finishInstall(Section section, String message) {
        busy = false;
        section.progress.setVisibility(View.GONE);
        messageView.setText(message);
        refreshSections();
    }

    private String prefKey(Kind kind) {
        return targetPackage + "." + kind.name();
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return message != null && !message.isEmpty() ? message : e.getClass().getSimpleName();
    }

    private boolean hasStorageAccess() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager();
    }

    private void requestStorageAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
        intent.setData(Uri.parse("package:" + getPackageName()));
        try {
            startActivity(intent);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (Exception inner) {
                Log.e(TAG, "Failed to open all-files access settings", inner);
                Toast.makeText(this, R.string.selector_storage_permission_open_failed, Toast.LENGTH_LONG).show();
            }
        }
    }
}
