package dev.allofus.fusioncore.tools;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Edits the P5XTES translation files (BepInEx/Translation/es/Text). Same behaviour as the Windows P5XTES Patcher:
 * <ul>
 *   <li><b>Protagonist name</b>: replaces the default name in General.txt and lists the new name in the
 *   "custom protagonist name" block of _Substitutions.txt so the auto-translator leaves it untouched.</li>
 *   <li><b>Hide ID</b>: turns the "ID:12345" rule of UI.txt into an empty translation (and back).</li>
 * </ul>
 * The text pack overwrites these files, so {@link #reapplyAfterTextInstall} must run after installing it.
 */
public final class TextCustomizer {
    /** Name the translation pack ships with. */
    public static final String BASE_FIRST_NAME = "Nagisa";
    public static final String BASE_LAST_NAME = "Kamisiro";

    private static final String NAME_MARKER = "+---Nombre Protagonista Personalizado---+";
    private static final String ID_VISIBLE = "r:\"^ID:(\\d+)$\"=\"ID:$1\"";
    private static final String ID_HIDDEN = "r:\"^ID:(\\d+)$\"=\"\"";

    private TextCustomizer() {}

    /** BepInEx/Translation/es/Text */
    public static File textDir(File bepInExDir) {
        return new File(new File(new File(bepInExDir, "Translation"), "es"), "Text");
    }

    public static boolean isTextPackInstalled(File textDir) {
        return new File(textDir, "General.txt").isFile();
    }

    public static boolean hasIdRule(File textDir) {
        File ui = new File(textDir, "UI.txt");
        if (!ui.isFile()) return false;
        try {
            String text = readText(ui);
            return text.contains(ID_VISIBLE) || text.contains(ID_HIDDEN);
        } catch (IOException e) {
            return false;
        }
    }

    /** True when UI.txt currently maps the player ID to an empty text. */
    public static boolean isIdHidden(File textDir) throws IOException {
        File ui = new File(textDir, "UI.txt");
        return ui.isFile() && readText(ui).contains(ID_HIDDEN);
    }

    /** Hides or shows the player ID (HUD and profile screen). */
    public static void setIdHidden(File textDir, boolean hidden) throws IOException {
        File ui = new File(textDir, "UI.txt");
        if (!ui.isFile()) throw new IOException("UI.txt not found in " + textDir);
        String text = readText(ui);
        if (!text.contains(ID_VISIBLE) && !text.contains(ID_HIDDEN)) {
            throw new IOException("UI.txt does not contain the player ID rule");
        }
        writeText(ui, hidden ? text.replace(ID_VISIBLE, ID_HIDDEN) : text.replace(ID_HIDDEN, ID_VISIBLE));
    }

    /**
     * Replaces the name currently in the files (oldFirst oldLast) by the new one and updates the substitutions block.
     * Names must not contain spaces.
     */
    public static void applyName(File textDir, String newFirst, String newLast, String oldFirst, String oldLast)
            throws IOException {
        File general = new File(textDir, "General.txt");
        if (!general.isFile()) throw new IOException("General.txt not found in " + textDir);

        String text = readText(general);
        writeText(general, text.replace(oldFirst + " " + oldLast, newFirst + " " + newLast));

        File substitutions = new File(textDir, "_Substitutions.txt");
        if (!substitutions.isFile()) return;

        String sub = readText(substitutions);
        int marker = sub.indexOf(NAME_MARKER);
        if (marker < 0) return;

        String nl = sub.contains("\r\n") ? "\r\n" : "\n";
        String header = sub.substring(0, marker + NAME_MARKER.length());
        int start = marker + NAME_MARKER.length();
        while (start < sub.length() && (sub.charAt(start) == '\r' || sub.charAt(start) == '\n')) start++;
        int end = sub.indexOf("++", start);
        String rest = end >= 0 ? sub.substring(end) : "";

        StringBuilder lines = new StringBuilder(nl);
        boolean isDefault = newFirst.equals(BASE_FIRST_NAME) && newLast.equals(BASE_LAST_NAME);
        if (!isDefault) {
            lines.append(newFirst).append(' ').append(newLast).append('=').append(newFirst).append(' ').append(newLast).append(nl);
            if (!newFirst.equals(BASE_FIRST_NAME)) lines.append(newFirst).append('=').append(newFirst).append(nl);
            if (!newLast.equals(BASE_LAST_NAME)) lines.append(newLast).append('=').append(newLast).append(nl);
        }
        writeText(substitutions, header + lines + rest);
    }

    /** The text pack ships the default name and a visible ID: put the user's choices back. */
    public static void reapplyAfterTextInstall(File textDir, String first, String last, boolean hideId)
            throws IOException {
        applyName(textDir, first, last, BASE_FIRST_NAME, BASE_LAST_NAME);
        if (hideId) setIdHidden(textDir, true);
    }

    private static String readText(File file) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        }
        String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
        return text.startsWith("\uFEFF") ? text.substring(1) : text;   // UTF-8 without BOM, like the Windows patcher
    }

    private static void writeText(File file, String text) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}
