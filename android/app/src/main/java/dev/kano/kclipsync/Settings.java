package dev.kano.kclipsync;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Runtime settings in private app storage. A JSON file, not SharedPreferences, because the UI
 * process and the {@code :sync} service process must observe the same values without relying on
 * process-local caches. This is intentionally not TOML; TOML is only used by the Windows client.
 */
public final class Settings {
    private static final String FILE = "settings.json";
    private static final String KEY_PORT = "port";
    private static final String KEY_MAX_BYTES = "max_bytes";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_NODE_ID = "node_id";

    public final int port;
    public final int maxBytes;

    private Settings(int port, int maxBytes) {
        this.port = port;
        this.maxBytes = maxBytes;
    }

    public static Settings load(Context context) {
        JSONObject object = read(context);
        int port = object.optInt(KEY_PORT, Protocol.DEFAULT_PORT);
        int maxBytes = object.optInt(KEY_MAX_BYTES, Protocol.DEFAULT_MAX_BYTES);
        if (!validPort(port)) port = Protocol.DEFAULT_PORT;
        if (!validMaxBytes(maxBytes)) maxBytes = Protocol.DEFAULT_MAX_BYTES;
        return new Settings(port, maxBytes);
    }

    public static void save(Context context, int port, int maxBytes) {
        if (!validPort(port) || !validMaxBytes(maxBytes)) {
            throw new IllegalArgumentException("invalid settings");
        }
        JSONObject object = read(context);
        try {
            object.put(KEY_PORT, port);
            object.put(KEY_MAX_BYTES, maxBytes);
        } catch (Exception e) {
            throw new IllegalStateException("无法保存设置", e);
        }
        write(context, object);
    }

    public static boolean enabled(Context context) {
        return read(context).optBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        JSONObject object = read(context);
        try {
            object.put(KEY_ENABLED, enabled);
        } catch (Exception e) {
            return;
        }
        write(context, object);
    }

    public static String nodeId(Context context) {
        JSONObject object = read(context);
        String id = object.optString(KEY_NODE_ID, "");
        if (!id.isEmpty()) return id;
        id = UUID.randomUUID().toString();
        try {
            object.put(KEY_NODE_ID, id);
        } catch (Exception e) {
            return id;
        }
        write(context, object);
        return id;
    }

    public static boolean validPort(int port) {
        return port >= 1 && port <= 65535;
    }

    public static boolean validMaxBytes(int bytes) {
        return bytes >= 1024 && bytes <= 64 * 1024 * 1024;
    }

    private static File file(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), FILE);
    }

    private static JSONObject read(Context context) {
        File source = file(context);
        if (!source.isFile()) return new JSONObject();
        try (FileInputStream in = new FileInputStream(source)) {
            return new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static void write(Context context, JSONObject object) {
        File target = file(context);
        File temp = new File(target.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(object.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        temp.renameTo(target);
    }
}
