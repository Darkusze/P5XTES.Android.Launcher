package dev.allofus.fusioncore;

import dev.allofus.fusioncore.tools.TextCustomizer;

import android.content.Context;
import android.content.SharedPreferences;

public final class FusionSettings {
    private static final String PREFS_NAME = "fusion_settings";
    private static final String KEY_DOWNLOAD_UNSTRIPPED_LIBUNITY = "download_unstripped_libunity";
    private static final String KEY_USE_IL2CPP2MONO = "use_il2cpp2mono";
    private static final String KEY_ACTIVITY_OVERRIDE = "activity_override";
    private static final String KEY_ORIENTATION_MODE = "orientation_mode";
    private static final String KEY_PROTAGONIST_FIRST = "protagonist_first";
    private static final String KEY_PROTAGONIST_LAST = "protagonist_last";
    private static final String KEY_HIDE_ID = "hide_id";

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

    /**
     * "Automatic" used to be stored as the (translated) label, which breaks when the label changes language.
     * It is now stored as an empty value; a stored "Automatic" (older versions) is still understood.
     * The caller always receives the label of the current language for the automatic case.
     */
    public static String getActivityOverrideForGame(Context context, String targetPackage) {
        String automatic = context.getString(R.string.settings_automatic);
        String stored = prefs(context).getString(targetPackage + ":" + KEY_ACTIVITY_OVERRIDE, "");
        if (stored.isEmpty() || stored.equals("Automatic") || stored.equals(automatic)) return automatic;
        return stored;
    }

    public static void setActivityOverrideForGame(Context context, String targetPackage, String activityName) {
        String value = activityName == null || activityName.equals(context.getString(R.string.settings_automatic))
                ? "" : activityName;
        prefs(context).edit().putString(targetPackage + ":" + KEY_ACTIVITY_OVERRIDE, value).apply();
    }

    public static int getOrientationMode(Context context, String targetPackage) {
        return prefs(context).getInt(targetPackage + ":" + KEY_ORIENTATION_MODE, ORIENTATION_AUTO_LANDSCAPE);
    }

    public static void setOrientationMode(Context context, String targetPackage, int mode) {
        prefs(context).edit().putInt(targetPackage + ":" + KEY_ORIENTATION_MODE, mode).apply();
    }

    public static String getProtagonistFirst(Context context, String targetPackage) {
        return prefs(context).getString(targetPackage + ":" + KEY_PROTAGONIST_FIRST, TextCustomizer.BASE_FIRST_NAME);
    }

    public static String getProtagonistLast(Context context, String targetPackage) {
        return prefs(context).getString(targetPackage + ":" + KEY_PROTAGONIST_LAST, TextCustomizer.BASE_LAST_NAME);
    }

    public static void setProtagonistName(Context context, String targetPackage, String first, String last) {
        prefs(context).edit()
                .putString(targetPackage + ":" + KEY_PROTAGONIST_FIRST, first)
                .putString(targetPackage + ":" + KEY_PROTAGONIST_LAST, last)
                .apply();
    }

    public static boolean isHideIdEnabled(Context context, String targetPackage) {
        return prefs(context).getBoolean(targetPackage + ":" + KEY_HIDE_ID, false);
    }

    public static void setHideIdEnabled(Context context, String targetPackage, boolean hidden) {
        prefs(context).edit().putBoolean(targetPackage + ":" + KEY_HIDE_ID, hidden).apply();
    }
}
