package dev.kano.kclipsync;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Service state shared across the UI and :sync processes through private app storage.
 */
public final class StatusStore {
    public static final String STOPPED = "stopped";
    public static final String STARTING = "starting";
    public static final String RUNNING = "running";
    public static final String NO_NETWORK = "no_network";

    public static final class Snapshot {
        public final String state;
        public final String network;
        public final String detail;
        public final long timestamp;
        public final boolean rootAllowed;
        public final boolean xposedHooked;
        public final List<Peer> peers;

        Snapshot(String state, String network, String detail, long timestamp,
                 boolean rootAllowed, boolean xposedHooked, List<Peer> peers) {
            this.state = state;
            this.network = network;
            this.detail = detail;
            this.timestamp = timestamp;
            this.rootAllowed = rootAllowed;
            this.xposedHooked = xposedHooked;
            this.peers = peers;
        }

        public boolean alive() {
            return !STOPPED.equals(state) && System.currentTimeMillis() - timestamp < 120_000;
        }
    }

    public static final class Peer {
        public final String id;
        public final String name;
        public final String address;
        public final boolean outgoing;

        Peer(String id, String name, String address, boolean outgoing) {
            this.id = id;
            this.name = name;
            this.address = address;
            this.outgoing = outgoing;
        }
    }

    private StatusStore() {}

    private static File file(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), "status.json");
    }

    /**
     * True when the system_server hook installed. It writes a global setting because
     * system_server cannot reliably create files inside this app's private data directory.
     */
    public static boolean hookPresent(Context context) {
        try {
            if (android.provider.Settings.Global.getInt(
                    context.getContentResolver(), "kclipsync_hook", 0) == 1) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        File marker = new File(context.getApplicationContext().getFilesDir(), "xposed_hook");
        return marker.isFile();
    }

    public static synchronized void write(Context context, String state, String network, String detail,
                                          boolean rootAllowed, boolean xposedHooked, List<Peer> peers) {
        try {
            JSONArray array = new JSONArray();
            for (Peer peer : peers) {
                array.put(new JSONObject()
                        .put("id", peer.id)
                        .put("name", peer.name)
                        .put("address", peer.address)
                        .put("outgoing", peer.outgoing));
            }
            JSONObject object = new JSONObject()
                    .put("state", state)
                    .put("network", network == null ? JSONObject.NULL : network)
                    .put("detail", detail == null ? JSONObject.NULL : detail)
                    .put("timestamp", System.currentTimeMillis())
                    .put("root", rootAllowed)
                    .put("xposed", xposedHooked)
                    .put("peers", array);
            File target = file(context);
            File temp = new File(target.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(object.toString().getBytes(StandardCharsets.UTF_8));
            }
            //noinspection ResultOfMethodCallIgnored
            temp.renameTo(target);
        } catch (Exception ignored) {
        }
    }

    public static Snapshot read(Context context) {
        boolean marker = hookPresent(context);
        try (FileInputStream in = new FileInputStream(file(context))) {
            JSONObject object = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            List<Peer> peers = new ArrayList<>();
            JSONArray array = object.optJSONArray("peers");
            if (array == null) array = new JSONArray();
            for (int i = 0; array != null && i < array.length(); i++) {
                JSONObject peer = array.optJSONObject(i);
                if (peer == null) continue;
                peers.add(new Peer(
                        peer.optString("id", ""),
                        peer.optString("name", ""),
                        peer.optString("address", ""),
                        peer.optBoolean("outgoing", false)));
            }
            return new Snapshot(
                    object.optString("state", STOPPED),
                    object.isNull("network") ? null : object.optString("network"),
                    object.isNull("detail") ? null : object.optString("detail"),
                    object.optLong("timestamp", 0),
                    object.optBoolean("root", false),
                    object.optBoolean("xposed", false) || marker,
                    peers);
        } catch (Exception e) {
            return new Snapshot(STOPPED, null, null, 0, false, false, new ArrayList<>());
        }
    }

    public static Peer peer(String id, String name, String address, boolean outgoing) {
        return new Peer(id, name, address, outgoing);
    }
}
