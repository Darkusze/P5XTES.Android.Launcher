package dev.allofus.fusioncore.tools;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import androidx.annotation.Nullable;

import java.util.List;

/** The only game P5XTES supports: Persona 5: The Phantom X. */
public final class P5xGame {
    /** Global (English) release. */
    public static final String DEFAULT_PACKAGE = "com.sega.persona5.the.phantomx.en";

    /** Regional builds of the same game share this prefix. */
    private static final String PACKAGE_PREFIX = "com.sega.persona5.the.phantomx";

    private P5xGame() {}

    public static boolean isSupported(@Nullable String packageName) {
        return packageName != null && packageName.startsWith(PACKAGE_PREFIX);
    }

    /** First installed P5X package (the global build is preferred), or null if none is installed. */
    @Nullable
    public static String findInstalledPackage(PackageManager pm) {
        try {
            pm.getApplicationInfo(DEFAULT_PACKAGE, 0);
            return DEFAULT_PACKAGE;
        } catch (PackageManager.NameNotFoundException ignored) {
            // fall through and look for regional builds
        }
        List<ApplicationInfo> installed = pm.getInstalledApplications(0);
        for (ApplicationInfo info : installed) {
            if (isSupported(info.packageName)) {
                return info.packageName;
            }
        }
        return null;
    }
}
