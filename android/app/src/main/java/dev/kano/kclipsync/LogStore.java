package dev.kano.kclipsync;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Cross-process log. The service appends; the UI reads the same private file. Clipboard contents are
 * never passed to this class.
 */
public final class LogStore {
    private static final int MAX_LINES = 400;
    private static final long MAX_FILE_BYTES = 256 * 1024;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", Locale.US);
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static File file;

    private LogStore() {}

    public static synchronized void init(Context context) {
        if (file != null) return;
        file = new File(context.getFilesDir(), "kclipsync.log");
        reload();
    }

    private static void reload() {
        LINES.clear();
        if (file == null || !file.exists()) return;
        try (FileInputStream in = new FileInputStream(file)) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (String line : content.split("\n")) {
                if (!line.isEmpty()) push(line);
            }
        } catch (IOException ignored) {
        }
    }

    public static synchronized void info(String message) {
        write("I", message);
    }

    public static synchronized void warn(String message) {
        write("W", message);
    }

    private static void write(String level, String message) {
        String clean = message == null ? "" : message.replace('\n', ' ');
        String line = LocalDateTime.now().format(TS) + " " + level + " " + clean;
        push(line);
        if (file == null) return;
        try {
            if (file.length() > MAX_FILE_BYTES) {
                File old = new File(file.getPath() + ".1");
                //noinspection ResultOfMethodCallIgnored
                old.delete();
                //noinspection ResultOfMethodCallIgnored
                file.renameTo(old);
            }
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
        }
    }

    private static void push(String line) {
        while (LINES.size() >= MAX_LINES) LINES.removeFirst();
        LINES.addLast(line);
    }

    public static synchronized List<String> lines() {
        return new ArrayList<>(LINES);
    }

    public static synchronized String text() {
        refresh();
        return String.join("\n", LINES);
    }

    /** Reads the whole file. It is bounded by the same 256 KiB rotation as the writer. */
    public static synchronized void refresh() {
        if (file == null) return;
        reload();
    }

    public static synchronized void clear() {
        LINES.clear();
        if (file != null) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            //noinspection ResultOfMethodCallIgnored
            new File(file.getPath() + ".1").delete();
        }
    }
}
