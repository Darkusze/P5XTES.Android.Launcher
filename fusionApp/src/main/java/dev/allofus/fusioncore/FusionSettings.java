package dev.allofus.fusioncore;

import android.content.Context;
import android.content.SharedPreferences;

public final class FusionSettings {
    private static final String PREFS_NAME = "fusion_settings";
    private static final String KEY_DOWNLOAD_UNSTRIPPED_LIBUNITY = "download_unstripped_libunity";
    private static final String KEY_USE_IL2CPP2MONO = "use_il2cpp2mono";
    private static final String KEY_ACTIVITY_OVERRIDE = "activity_override";
    private static final String KEY_ORIENTATION_MODE = "orientation_mode";

    /** Landscape that follows the sensor, so it flips between both landscapes even with rotation lock on. */
    public static final int ORIENTATION_AUTO_LANDSCAPE = 0;
    /** Whatever orientation the game declares in its manifest. */
    public static final int ORIENTATION_GAME_DEFAULT = 1;
    public static final int ORIENTATION_LANDSCAPE = 2;
    public static final int ORIENTATION_REVERSE_LANDSCAPE = 3;

    private FusionSettings() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
    public static boolean getUseIl2Cpp2Mono(Context context, String targetPackage) {
        return prefs(context).getBoolean(targetPackage + ":" + KEY_USE_IL2CPP2MONO, false);
    }

    public static void setUseIl2Cpp2Mono(Context context, String targetPackage, boolean enabled) {
        prefs(context).edit().putBoolean(targetPackage + ":" + KEY_USE_IL2CPP2MONO, enabled).apply();
    }
    public static boolean getUseUnstrippedLibUnityForGame(Context context, String targetPackage) {
        return prefs(context).getBoolean(targetPackage + ":" + KEY_DOWNLOAD_UNSTRIPPED_LIBUNITY, true);
    }

    public static void setUseUnstrippedLibUnityForGame(Context context, String targetPackage, boolean enabled) {
        prefs(context).edit().putBoolean(targetPackage + ":" + KEY_DOWNLOAD_UNSTRIPPED_LIBUNITY, enabled).apply();
    }

    public static String getActivityOverrideForGame(Context context, String targetPackage) {
        return prefs(context).getString(targetPackage + ":" + KEY_ACTIVITY_OVERRIDE, context.getString(R.string.settings_automatic));
    }

    public static void setActivityOverrideForGame(Context context, String targetPackage, String activityName) {
        prefs(context).edit().putString(targetPackage + ":" + KEY_ACTIVITY_OVERRIDE, activityName).apply();
    }

    public static int getOrientationMode(Context context, String targetPackage) {
        return prefs(context).getInt(targetPackage + ":" + KEY_ORIENTATION_MODE, ORIENTATION_AUTO_LANDSCAPE);
    }

    public static void setOrientationMode(Context context, String targetPackage, int mode) {
        prefs(context).edit().putInt(targetPackage + ":" + KEY_ORIENTATION_MODE, mode).apply();
    }
}
