package dev.kano.kclipsync;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * UDP broadcast discovery that enumerates every usable local interface.
 *
 * <p>Android's {@link android.net.nsd.NsdManager} follows the system's chosen multicast interfaces,
 * which misses a hotspot interface when cellular is the default route. LocalSend handles that by
 * binding discovery per interface; this class is the small equivalent. It is additive: callers can
 * merge these instances with the mDNS results.
 */
public final class Beacon {
    public static final int PORT = 47632;
    private static final byte[] MAGIC = "KCLIPSYNC1".getBytes(StandardCharsets.US_ASCII);
    private static final long TTL_MS = 12_000;
    private static final int MAX_PACKET = 2048;

    private Beacon() {}

    public static final class Instance {
        public final String nodeId;
        public final String name;
        public final List<InetSocketAddress> addresses;

        Instance(String nodeId, String name, List<InetSocketAddress> addresses) {
            this.nodeId = nodeId;
            this.name = name;
            this.addresses = addresses;
        }

        public String identity() {
            return nodeId == null || nodeId.isEmpty() ? name : nodeId;
        }
    }

    /** Returns the LAN interfaces worth broadcasting on, without consulting the default network. */
    public static List<NetworkInterface> lanInterfaces() {
        List<NetworkInterface> out = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
            while (all != null && all.hasMoreElements()) {
                NetworkInterface network = all.nextElement();
                try {
                    if (network.isLoopback() || !network.isUp() || !isLanName(network.getName())) continue;
                } catch (Exception e) {
                    continue;
                }
                boolean hasAddress = false;
                for (InterfaceAddress address : network.getInterfaceAddresses()) {
                    InetAddress ip = address.getAddress();
                    if (ip == null || ip.isLoopbackAddress() || ip.isMulticastAddress()) continue;
                    hasAddress = true;
                    break;
                }
                if (hasAddress) out.add(network);
            }
        } catch (Exception e) {
            LogStore.warn("接口枚举失败：" + e.getMessage());
        }
        return out;
    }

    /** True when at least one non-loopback interface can carry a local connection. */
    public static boolean hasLanInterface(Context context) {
        if (!lanInterfaces().isEmpty()) return true;
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        if (manager == null) return false;
        for (Network network : manager.getAllNetworks()) {
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
            if (capabilities != null && (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLanName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return !(lower.startsWith("rmnet") || lower.startsWith("tun")
                || lower.startsWith("ppp") || lower.startsWith("dummy")
                || lower.startsWith("ifb") || lower.startsWith("gre")
                || lower.startsWith("sit") || lower.startsWith("ip6")
                || lower.startsWith("ovnet") || lower.startsWith("radio"));
    }

    /** Advertises this node on every interface until closed. */
    public static Advertiser advertise(String nodeId, String device, int port) {
        Advertiser advertiser = new Advertiser(nodeId, device, port);
        advertiser.start();
        return advertiser;
    }

    public static final class Advertiser implements AutoCloseable {
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final String nodeId;
        private final String device;
        private final int port;
        private Thread thread;

        Advertiser(String nodeId, String device, int port) {
            this.nodeId = nodeId;
            this.device = device;
            this.port = port;
        }

        void start() {
            thread = new Thread(this::loop, "kclipsync-beacon-advertise");
            thread.setDaemon(true);
            thread.start();
        }

        private void loop() {
            byte[] payload = encode(nodeId, device, port);
            while (!closed.get()) {
                for (NetworkInterface network : lanInterfaces()) {
                    for (InterfaceAddress address : network.getInterfaceAddresses()) {
                        InetAddress broadcast = address.getBroadcast();
                        if (broadcast == null) continue;
                        } catch (Exception e) {
                            LogStore.warn("广播发送失败 " + network.getName() + "：" + e.getMessage());
                        }
                    }
                }
                sleep(2_000);
            }
        }

        @Override
        public void close() {
            closed.set(true);
            Thread current = thread;
            if (current != null) current.interrupt();
        }
    }

    /** Receives advertisements until closed, then returns the latest set from {@link #snapshot()}. */
    public static Browser browse() {
        Browser browser = new Browser();
        browser.start();
        return browser;
    }

    public static final class Browser implements AutoCloseable {
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final Map<String, Entry> peers = new LinkedHashMap<>();
        private Thread thread;

        void start() {
            thread = new Thread(this::loop, "kclipsync-beacon-browse");
            thread.setDaemon(true);
            thread.start();
        }

        private void loop() {
            try (DatagramSocket socket = new DatagramSocket(null)) {
                socket.setReuseAddress(true);
                socket.setBroadcast(true);
                socket.bind(new InetSocketAddress(PORT));
                socket.setSoTimeout(500);
                byte[] buffer = new byte[MAX_PACKET];
                while (!closed.get()) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    try {
                        socket.receive(packet);
                    } catch (java.net.SocketTimeoutException e) {
                        continue;
                    } catch (Exception e) {
                        if (closed.get()) return;
                        LogStore.warn("广播接收失败：" + e.getMessage());
                        sleep(250);
                        continue;
                    }
                    Entry entry = decode(packet.getData(), packet.getLength(), packet.getAddress());
                    if (entry == null) continue;
                    synchronized (peers) {
                        peers.put(entry.nodeId, entry);
                    }
                }
            } catch (Exception e) {
                LogStore.warn("广播监听不可用：" + e.getMessage());
            }
        }

        public List<Instance> snapshot() {
            List<Instance> out = new ArrayList<>();
            long now = System.currentTimeMillis();
            synchronized (peers) {
                peers.entrySet().removeIf(entry -> now - entry.getValue().lastSeen > TTL_MS);
                for (Entry entry : peers.values()) {
                    out.add(new Instance(entry.nodeId, entry.device,
                            Collections.singletonList(
                                    new InetSocketAddress(entry.host, entry.port))));
                }
            }
            return out;
        }

        @Override
        public void close() {
            closed.set(true);
            Thread current = thread;
            if (current != null) current.interrupt();
        }
    }

    private static final class Entry {
        final String nodeId;
        final String device;
        final InetAddress host;
        final int port;
        final long lastSeen;

        Entry(String nodeId, String device, InetAddress host, int port) {
            this.nodeId = nodeId;
            this.device = device;
            this.host = host;
            this.port = port;
            this.lastSeen = System.currentTimeMillis();
        }
    }

    private static byte[] encode(String nodeId, String device, int port) {
        byte[] id = nodeId.getBytes(StandardCharsets.UTF_8);
        byte[] name = device.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[MAGIC.length + 1 + 2 + id.length + 2 + name.length + 2];
        int offset = 0;
        System.arraycopy(MAGIC, 0, out, offset, MAGIC.length);
        offset += MAGIC.length;
        out[offset++] = 1;
        out[offset++] = (byte) (id.length >> 8);
        out[offset++] = (byte) id.length;
        System.arraycopy(id, 0, out, offset, id.length);
        offset += id.length;
        out[offset++] = (byte) (name.length >> 8);
        out[offset++] = (byte) name.length;
        System.arraycopy(name, 0, out, offset, name.length);
        offset += name.length;
        out[offset++] = (byte) (port >> 8);
        out[offset] = (byte) port;
        return out;
    }

    private static Entry decode(byte[] data, int length, InetAddress from) {
        if (length < MAGIC.length + 3) return null;
        for (int i = 0; i < MAGIC.length; i++) {
            if (data[i] != MAGIC[i]) return null;
        }
        int offset = MAGIC.length;
        if (data[offset++] != 1) return null;
        int idLength = ((data[offset++] & 0xff) << 8) | (data[offset++] & 0xff);
        if (length < offset + idLength + 2) return null;
        String nodeId = new String(data, offset, idLength, StandardCharsets.UTF_8);
        offset += idLength;
        int deviceLength = ((data[offset++] & 0xff) << 8) | (data[offset++] & 0xff);
        if (length < offset + deviceLength + 2) return null;
        String device = new String(data, offset, deviceLength, StandardCharsets.UTF_8);
        offset += deviceLength;
        int port = ((data[offset++] & 0xff) << 8) | (data[offset] & 0xff);
        if (port <= 0) return null;
        return new Entry(nodeId, device, from, port);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
