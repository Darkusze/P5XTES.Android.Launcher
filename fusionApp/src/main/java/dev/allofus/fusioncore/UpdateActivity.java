package dev.allofus.fusioncore;

import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.format.Formatter;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.content.Intent;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.allofus.fusioncore.tools.AppUpdater;
import dev.allofus.fusioncore.tools.Utilities;

/** Checks the GitHub releases for a newer version of the app and installs it. */
public class UpdateActivity extends AppCompatActivity {
    private static final String TAG = "P5XTES";
    private static final int MAX_NOTES_CHARS = 1500;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private TextView statusView;
    private TextView notesTitleView;
    private TextView notesView;
    private TextView messageView;
    private ProgressBar progress;
    private Button checkButton;
    private Button installButton;

    private AppUpdater.Release release;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_update);

        View root = findViewById(R.id.update_root);
        int basePadding = Math.round(getResources().getDisplayMetrics().density * 16f);
        Utilities.applyWindowInsets(root, basePadding);

        findViewById(R.id.update_back).setOnClickListener(v -> finish());

        TextView installed = findViewById(R.id.update_installed);
        installed.setText(getString(R.string.update_installed_format,
                AppUpdater.currentVersionName(this), AppUpdater.currentVersionCode(this)));

        statusView = findViewById(R.id.update_status);
        notesTitleView = findViewById(R.id.update_notes_title);
        notesView = findViewById(R.id.update_notes);
        messageView = findViewById(R.id.update_message);
        progress = findViewById(R.id.update_progress);
        checkButton = findViewById(R.id.update_check);
        installButton = findViewById(R.id.update_install);

        checkButton.setOnClickListener(v -> check());
        installButton.setOnClickListener(v -> install());
        check();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private void check() {
        if (busy) return;
        busy = true;
        release = null;
        installButton.setVisibility(View.GONE);
        notesTitleView.setVisibility(View.GONE);
        notesView.setVisibility(View.GONE);
        messageView.setText("");
        checkButton.setEnabled(false);
        statusView.setText(R.string.update_checking);

        executor.execute(() -> {
            try {
                AppUpdater.Release latest = AppUpdater.fetchLatest();
                AppUpdater.saveResult(this, latest);
                runOnUiThread(() -> {
                    busy = false;
                    showResult(latest);
                });
            } catch (Exception e) {
                Log.e(TAG, "Update check failed", e);
                runOnUiThread(() -> {
                    busy = false;
                    checkButton.setEnabled(true);
                    statusView.setText("");
                    messageView.setText(getString(R.string.update_error_format, describe(e)));
                });
            }
        });
    }

    private void showResult(AppUpdater.Release latest) {
        checkButton.setEnabled(true);
        String current = AppUpdater.currentVersionName(this);

        if (latest == null) {
            statusView.setText(R.string.update_none_published);
            return;
        }
        if (!AppUpdater.isNewer(latest.tag(), current)) {
            statusView.setText(getString(R.string.update_up_to_date_format, current));
            return;
        }

        release = latest;
        String size = latest.apkSize() > 0 ? Formatter.formatShortFileSize(this, latest.apkSize()) : "?";
        statusView.setText(getString(R.string.update_available_format, latest.tag(), size));
        installButton.setVisibility(View.VISIBLE);

        String notes = latest.notes() == null ? "" : latest.notes().trim();
        if (!notes.isEmpty()) {
            if (notes.length() > MAX_NOTES_CHARS) notes = notes.substring(0, MAX_NOTES_CHARS) + "…";
            notesTitleView.setVisibility(View.VISIBLE);
            notesView.setVisibility(View.VISIBLE);
            notesView.setText(notes);
        }
    }

    private void install() {
        AppUpdater.Release target = release;
        if (target == null || busy) return;

        // Android only lets an app install APKs after the user allowed it in its own settings page.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getPackageManager().canRequestPackageInstalls()) {
            messageView.setText(R.string.update_allow_unknown);
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                Log.e(TAG, "Could not open the install-unknown-apps settings", e);
            }
            return;
        }

        busy = true;
        installButton.setEnabled(false);
        checkButton.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        progress.setIndeterminate(true);

        executor.execute(() -> {
            try {
                File apk = AppUpdater.downloadApk(target, getCacheDir(), (downloaded, total) ->
                        runOnUiThread(() -> showProgress(downloaded, total)));
                runOnUiThread(() -> {
                    progress.setIndeterminate(true);
                    messageView.setText(R.string.update_installing);
                });
                AppUpdater.install(this, apk);
                runOnUiThread(() -> {
                    busy = false;
                    progress.setVisibility(View.GONE);
                    installButton.setEnabled(true);
                    checkButton.setEnabled(true);
                });
            } catch (Exception e) {
                Log.e(TAG, "Failed to download or install the update", e);
                runOnUiThread(() -> {
                    busy = false;
                    progress.setVisibility(View.GONE);
                    installButton.setEnabled(true);
                    checkButton.setEnabled(true);
                    messageView.setText(getString(R.string.update_download_failed_format, describe(e)));
                });
            }
        });
    }

    private void showProgress(long downloaded, long total) {
        if (total > 0) {
            int percent = (int) Math.min(100, downloaded * 100 / total);
            progress.setIndeterminate(false);
            progress.setMax(100);
            progress.setProgress(percent);
            messageView.setText(getString(R.string.update_downloading_format, percent,
                    Formatter.formatShortFileSize(this, downloaded), Formatter.formatShortFileSize(this, total)));
        } else {
            progress.setIndeterminate(true);
            messageView.setText(getString(R.string.update_downloading_unknown_format,
                    Formatter.formatShortFileSize(this, downloaded)));
        }
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return message != null && !message.isEmpty() ? message : e.getClass().getSimpleName();
    }
}
