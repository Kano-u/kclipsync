package dev.kano.kclipsync;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.json.JSONObject;

/**
 * Foreground sync service. The service process is separate from the UI so closing the activity does
 * not stop synchronization.
 */
public class SyncService extends Service {
    private static final String CHANNEL = "kclipsync";
    private static final int NOTIFICATION_ID = 1;
    private static final long PING_MS = 30_000;
    private static final long DISCOVERY_MS = 4_000;
    private static final int RECENT_HASHES = 256;
    private static final long DIAL_RETRY_MIN_MS = 1_000;
    private static final long DIAL_RETRY_MAX_MS = 30_000;

    public static final String ACTION_CLIP = "dev.kano.kclipsync.CLIP";
    public static final String ACTION_RELOAD = "dev.kano.kclipsync.RELOAD";
    public static final String ACTION_STOP = "dev.kano.kclipsync.STOP";
    public static final String ACTION_HOOK_STATUS = "dev.kano.kclipsync.HOOK_STATUS";
    private static final String EXTRA_KEEPALIVE = "keepalive";

    private final Object lock = new Object();
    private final Map<String, Link> links = new LinkedHashMap<>();
    private final Set<String> recentHashes = new LinkedHashSet<>();
    private final Object dialLock = new Object();
    private final Object networkLock = new Object();
    private final Set<String> dialing = new LinkedHashSet<>();
    private final Map<String, Long> nextDialAt = new LinkedHashMap<>();
    private final Map<String, Long> dialBackoff = new LinkedHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean rootAllowed = new AtomicBoolean(false);
    private final AtomicReference<Settings> settings = new AtomicReference<>();

    private ConnectivityManager connectivity;
    private ClipboardManager clipboard;
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "kclipsync-worker");
        thread.setDaemon(true);
        return thread;
    });

    private volatile Network activeNetwork;
    private volatile boolean hasLan;
    private volatile String lastLanSignature = "";
    private volatile boolean xposedHooked;
    private volatile int listenPort;
    private volatile ServerSocket listener;
    private volatile Mdns.Advertiser advertiser;
    private volatile Beacon.Advertiser beaconAdvertiser;
    private volatile Beacon.Browser beaconBrowser;

    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
            if (isLan(network, connectivity == null ? null : connectivity.getNetworkCapabilities(network))) {
                applyNetwork(network);
            }
        }

        @Override
        public void onLost(Network network) {
            if (network.equals(activeNetwork)) {
                activeNetwork = null;
                lastLanSignature = "";
                hasLan = false;
                stopListener();
                if (advertiser != null) {
                    advertiser.close();
                    advertiser = null;
                }
                clearDialState();
                dropLinks();
                publishStatus("网络连接已断开");
            }
        }

        @Override
        public void onLinkPropertiesChanged(Network network, android.net.LinkProperties properties) {
            if (isLan(network, connectivity == null ? null : connectivity.getNetworkCapabilities(network))) {
                applyNetwork(network);
            }
        }

        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
            if (network.equals(activeNetwork) || isLan(network, capabilities)) applyNetwork(network);
        }
    };

    private final ClipboardManager.OnPrimaryClipChangedListener clipboardListener = () -> {
        if (!running.get()) return;
        workers.execute(this::readLocalClipboard);
    };

    @Override
    public void onCreate() {
        super.onCreate();
        LogStore.init(this);
        if (!Settings.enabled(this)) {
            stopSelf();
            return;
        }
        settings.set(Settings.load(this));
        connectivity = getSystemService(ConnectivityManager.class);
        clipboard = getSystemService(ClipboardManager.class);
        startForeground();
        xposedHooked = new java.io.File(getFilesDir(), "xposed_hook").isFile();
        clipboard.addPrimaryClipChangedListener(clipboardListener);
        if (connectivity != null) connectivity.registerDefaultNetworkCallback(networkCallback);
        if (connectivity != null) {
            try {
                connectivity.registerNetworkCallback(
                        new android.net.NetworkRequest.Builder()
                                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                                .build(),
                        networkCallback);
            } catch (Exception ignored) {
            }
        }
        running.set(true);
        Thread root = new Thread(() -> {
            if (Root.keepAlive(getPackageName())) rootAllowed.set(true);
        }, "kclipsync-root");
        root.setDaemon(true);
        root.start();
        workers.execute(this::pingLoop);
        workers.execute(this::statusLoop);
        workers.execute(this::networkLoop);
        applyNetwork(connectivity == null ? null : connectivity.getActiveNetwork());
        LogStore.info("同步服务已启动");
        publishStatus(null);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_RELOAD.equals(action)) {
                // The UI writes settings.json just before sending this intent. Give the atomic
                // rename a moment to land before reopening the file from the service process.
                sleep(50);
                settings.set(Settings.load(this));
                restartNetwork();
                return START_STICKY;
            }
            if (ACTION_STOP.equals(action)) {
                Settings.setEnabled(this, false);
                stopSelf();
                return START_NOT_STICKY;
            }
            if (ACTION_HOOK_STATUS.equals(action)) {
                xposedHooked = true;
                if (intent.getBooleanExtra(EXTRA_KEEPALIVE, false)) rootAllowed.set(true);
                publishStatus(null);
                return START_STICKY;
            }
            if (ACTION_CLIP.equals(action)) {
                ClipData clip = intent.getClipData();
                xposedHooked = true;
                if (clip != null) workers.execute(() -> handleClip(clip));
                else if (intent.getBooleanExtra("fetch", false)) {
                    workers.execute(this::readLocalClipboard);
                }
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running.set(false);
        if (clipboard != null) clipboard.removePrimaryClipChangedListener(clipboardListener);
        if (connectivity != null) {
            try {
                connectivity.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {
            }
        }
        if (advertiser != null) advertiser.close();
        if (beaconAdvertiser != null) beaconAdvertiser.close();
        if (beaconBrowser != null) beaconBrowser.close();
        stopListener();
        dropLinks();
        workers.shutdownNow();
        publishStatus("服务已停止");
        LogStore.info("同步服务已停止");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startForeground() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "KClipSync",
                NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("KClipSync")
                .setContentText("正在同步剪贴板")
                .setContentIntent(android.app.PendingIntent.getActivity(this, 0, open,
                        android.app.PendingIntent.FLAG_IMMUTABLE))
                .setOngoing(true)
                .build();
        startForeground(NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
    }

    /** Restart discovery when the set of usable LAN interfaces changes. */
    private void networkLoop() {
        while (running.get()) {
            String signature = lanSignature();
            if (!signature.equals(lastLanSignature)) {
                lastLanSignature = signature;
                applyNetwork(connectivity == null ? null : connectivity.getActiveNetwork());
            }
            sleep(3_000);
        }
    }

    private String lanSignature() {
        List<String> names = new ArrayList<>();
        for (NetworkInterface network : Beacon.lanInterfaces()) {
            names.add(network.getName());
        }
        java.util.Collections.sort(names);
        return String.join(",", names);
    }

    /** Keep the UI's liveness timestamp fresh even while the service is idle. */
    private void statusLoop() {
        while (running.get()) {
            sleep(10_000);
            if (running.get()) publishStatus(null);
        }
    }

    private boolean isLan(Network network, NetworkCapabilities capabilities) {
        return network != null && capabilities != null && (
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
    }

    private void applyNetwork(Network network) {
        synchronized (networkLock) {
            applyNetworkLocked(network);
        }
    }

    private void applyNetworkLocked(Network network) {
        if (!running.get()) return;
        NetworkCapabilities capabilities = connectivity == null || network == null
                ? null : connectivity.getNetworkCapabilities(network);
        boolean hasInterface = Beacon.hasLanInterface(this) || isLan(network, capabilities);
        if (!hasInterface) {
            activeNetwork = network;
            hasLan = false;
            lastLanSignature = "";
            stopListener();
            dropLinks();
            clearDialState();
            if (advertiser != null) {
                advertiser.close();
                advertiser = null;
            }
            stopBeacon();
            publishStatus("没有可用的 Wi-Fi 或以太网网络");
            return;
        }
        if (java.util.Objects.equals(network, activeNetwork) && hasLan && listener != null) return;
        activeNetwork = network;
        if (!hasLan) lastLanSignature = lanSignature();
        hasLan = true;
        dropLinks();
        clearDialState();
        stopListener();
        if (advertiser != null) advertiser.close();
        Settings current = settings.get();
        startListener(current.port);
        advertiser = Mdns.advertise(this, network, deviceName(), current.port,
                Settings.nodeId(this));
        startBeacon();
        LogStore.info("已绑定局域网接口，端口 " + listenPort);
        publishStatus(null);
        workers.execute(this::discoveryLoop);
    }

    private String deviceName() {
        String name = Build.MODEL == null || Build.MODEL.trim().isEmpty()
                ? "Android" : Build.MODEL.trim();
        return "KClipSync on " + name;
    }

    private void stopBeacon() {
        if (beaconAdvertiser != null) {
            beaconAdvertiser.close();
            beaconAdvertiser = null;
        }
        if (beaconBrowser != null) {
            beaconBrowser.close();
            beaconBrowser = null;
        }
    }

    private void restartNetwork() {
        Network network = connectivity == null ? null : connectivity.getActiveNetwork();
        activeNetwork = null;
        hasLan = false;
        lastLanSignature = "";
        applyNetwork(network);
    }

    private void startBeacon() {
        stopBeacon();
        Settings current = settings.get();
        beaconAdvertiser = Beacon.advertise(Settings.nodeId(this), deviceName(), current.port);
        beaconBrowser = Beacon.browse();
        LogStore.info("UDP 广播发现已启动，端口 " + Beacon.PORT);
    }

    private void startListener(int port) {
        try {
            ServerSocket server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(port), 16);
            listener = server;
            listenPort = server.getLocalPort();
            workers.execute(() -> acceptLoop(server));
        } catch (IOException e) {
            listener = null;
            listenPort = port;
            LogStore.warn("无法监听端口 " + port + "：" + e.getMessage());
        }
    }

    private void stopListener() {
        ServerSocket server = listener;
        listener = null;
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void acceptLoop(ServerSocket server) {
        while (running.get() && listener == server && !server.isClosed()) {
            try {
                Socket socket = server.accept();
                workers.execute(() -> accept(socket));
            } catch (IOException e) {
                if (running.get() && listener == server) {
                    LogStore.warn("接受连接失败：" + e.getMessage());
                }
                return;
            }
        }
    }

    private void accept(Socket socket) {
        try {
            Link link = new Link(socket, false);
            link.readHello(Protocol.maxFrame(settings.get().maxBytes));
            link.sendHello(Settings.nodeId(this), deviceName(), settings.get().port,
                    link.nodeId);
            link.afterHandshake();
            if (Settings.nodeId(this).equals(link.nodeId)) {
                link.close();
                return;
            }
            serve(link);
        } catch (Exception e) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void discoveryLoop() {
        while (running.get() && hasLan) {
            try {
                List<Mdns.Instance> found = new ArrayList<>();
                Network network = activeNetwork;
                if (network != null) {
                    found.addAll(Mdns.discover(this, network, DISCOVERY_MS));
                }
                Beacon.Browser browser = beaconBrowser;
                if (browser != null) {
                    for (Beacon.Instance instance : browser.snapshot()) {
                        found.add(new Mdns.Instance(instance.name, instance.nodeId, instance.addresses));
                    }
                }
                for (Mdns.Instance instance : found) {
                    if (instance.identity().equals(Settings.nodeId(this))) continue;
                    if (hasLink(instance.identity()) || !beginDial(instance.identity())) continue;
                    List<InetSocketAddress> addresses = network == null
                            ? new ArrayList<>(instance.addresses)
                            : Mdns.preferOnLink(this, network, instance.addresses);
                    workers.execute(() -> dial(instance, addresses));
                }
                publishStatus(null);
            } catch (Throwable t) {
                LogStore.warn("发现循环失败：" + t.getMessage());
            }
            sleep(1_000);
        }
    }

    private void dial(Mdns.Instance instance, List<InetSocketAddress> addresses) {
        if (!running.get() || !hasLan) return;
        Network network = activeNetwork;
        boolean connected = false;
        try {
            for (InetSocketAddress address : addresses) {
                if (!running.get() || !hasLan) return;
                try {
                    Link link = connectWithFallback(network, address);
                    link.sendHello(Settings.nodeId(this), deviceName(), settings.get().port);
                    link.readHello(Protocol.maxFrame(settings.get().maxBytes));
                    link.afterHandshake();
                    if (Settings.nodeId(this).equals(link.nodeId)) {
                        link.close();
                        connected = true;
                        return;
                    }
                    connected = true;
                    serve(link);
                    return;
                } catch (Exception e) {
                    LogStore.info("连接 " + address + " 失败：" + e.getMessage());
                }
            }
        } finally {
            endDial(instance.identity(), connected);
        }
    }

    private boolean beginDial(String identity) {
        synchronized (dialLock) {
            if (dialing.contains(identity)) return false;
            long now = SystemClock.elapsedRealtime();
            Long next = nextDialAt.get(identity);
            if (next != null && now < next) return false;
            dialing.add(identity);
            return true;
        }
    }

    /**
     * Prefer the network that owns the discovered address. A phone hotspot can be non-default
     * while cellular remains the active network, so binding that active network would make the
     * LAN address unreachable. Falling back to the system route lets the OS pick the hotspot
     * interface without changing Wi-Fi/ethernet selection.
     */
    private Link connectWithFallback(Network network, InetSocketAddress address) throws IOException {
        try {
            return Link.connect(network, address, 5_000, Settings.nodeId(this));
        } catch (IOException first) {
            if (network == null) throw first;
            LogStore.info("绑定网络连接 " + address + " 失败，尝试系统路由：" + first.getMessage());
            return Link.connect(null, address, 5_000, Settings.nodeId(this));
        }
    }

    private void endDial(String identity, boolean connected) {
        synchronized (dialLock) {
            dialing.remove(identity);
            if (connected) {
                dialBackoff.remove(identity);
                nextDialAt.remove(identity);
                return;
            }
            long previous = dialBackoff.getOrDefault(identity, DIAL_RETRY_MIN_MS);
            long nextDelay = Math.min(previous * 2, DIAL_RETRY_MAX_MS);
            dialBackoff.put(identity, nextDelay);
            nextDialAt.put(identity, SystemClock.elapsedRealtime() + nextDelay);
        }
    }

    private void clearDialState() {
        synchronized (dialLock) {
            dialing.clear();
            nextDialAt.clear();
            dialBackoff.clear();
        }
    }

    private void pingLoop() {
        while (running.get()) {
            sleep(PING_MS);
            for (Link link : snapshotLinks()) {
                try {
                    link.send(Protocol.T_PING, new byte[0]);
                } catch (IOException e) {
                    closeLink(link);
                }
            }
            publishStatus(null);
        }
    }

    private void serve(Link link) {
        String nodeId = link.nodeId;
        try {
            if (dropDuplicateOtherDirection(link)) return;
            synchronized (lock) {
                Link existing = links.get(nodeId);
                if (existing != null && existing.isOpen()) {
                    if (!smallerInitiatorWins(link, existing)) {
                        link.sendBye("duplicate");
                        return;
                    }
                    existing.sendBye("duplicate");
                    existing.close();
                }
                links.put(nodeId, link);
            }
            if (!link.isOpen()) return;

            synchronized (dialLock) {
                dialing.remove(nodeId);
                dialBackoff.remove(nodeId);
                nextDialAt.remove(nodeId);
            }
            LogStore.info("已连接 " + link.device + " [" + link.remoteAddress() + "]");
            publishStatus(null);
            // A direct dial can succeed over an interface that is not the default network,
            // so the address list is tried without requiring network == activeNetwork.
            while (running.get() && link.isOpen()) {
                Link.Frame frame = link.recv(Protocol.maxFrame(settings.get().maxBytes));
                switch (frame.type) {
                    case Protocol.T_CLIP -> handleRemoteClip(link, frame.payload);
                    case Protocol.T_PING -> link.send(Protocol.T_PONG, new byte[0]);
                    case Protocol.T_PONG -> {
                    }
                    case Protocol.T_BYE -> {
                        LogStore.info("对端请求断开：" + reasonOf(frame.payload));
                        return;
                    }
                    default -> LogStore.warn("收到未知帧类型 " + frame.type);
                }
            }
        } catch (Exception e) {
            if (running.get()) LogStore.info("连接结束：" + e.getMessage());
        } finally {
            closeLink(link);
        }
    }

    private boolean smallerInitiatorWins(Link candidate, Link existing) {
        String candidateInitiator = candidate.initiatorId();
        String existingInitiator = existing.initiatorId();
        if (!candidateInitiator.equals(existingInitiator)) {
            return candidateInitiator.compareTo(existingInitiator) < 0;
        }
        return false;
    }

    /** Resolve the bidirectional race without letting both ends tear each other down. */
    private boolean dropDuplicateOtherDirection(Link candidate) {
        String local = Settings.nodeId(this);
        String peer = candidate.nodeId;
        if (local.compareTo(peer) >= 0 || candidate.isInitiator()) return false;
        // Deterministic tie-break: only the peer with the lexicographically smaller node ID
        // may replace an inbound link with an outbound one. The other side keeps the link it
        // already has, so a simultaneous dial settles on one connection instead of a dead loop.
        synchronized (lock) {
            Link existing = links.get(peer);
            if (existing != null && existing.isOpen() && existing.isInitiator()) {
                candidate.sendBye("duplicate");
                candidate.close();
                return true;
            }
        }
        return false;
    }

    private void closeLink(Link link) {
        link.close();
        synchronized (lock) {
            if (links.get(link.nodeId) == link) links.remove(link.nodeId);
        }
        publishStatus(null);
    }

    private List<Link> snapshotLinks() {
        synchronized (lock) {
            return new ArrayList<>(links.values());
        }
    }

    private boolean hasLink(String nodeId) {
        if (nodeId == null || nodeId.isEmpty()) return false;
        synchronized (lock) {
            Link link = links.get(nodeId);
            return link != null && link.isOpen();
        }
    }

    private void dropLinks() {
        for (Link link : snapshotLinks()) {
            link.close();
        }
        synchronized (lock) {
            links.clear();
        }
    }

    private void readLocalClipboard() {
        try {
            ClipData clip = clipboard.getPrimaryClip();
            if (clip != null) handleClip(clip);
        } catch (Throwable t) {
            LogStore.warn("读取本地剪贴板失败：" + t.getMessage());
        }
    }

    private void handleClip(ClipData clip) {
        if (!running.get() || clip == null || clip.getItemCount() == 0) return;
        String text = null;
        for (int i = 0; i < clip.getItemCount(); i++) {
            ClipData.Item item = clip.getItemAt(i);
            // getText() is null for image/file ClipData; coerceToText() would turn a URI into
            // text and violate the text-only contract.
            CharSequence value = item.getText();
            if (value != null) {
                text = value.toString();
                if (!text.isEmpty()) break;
            }
        }
        if (text == null) return;
        text = Protocol.normalizeText(text);
        if (text.getBytes(StandardCharsets.UTF_8).length > settings.get().maxBytes) {
            LogStore.warn("本地文本超过限制，已忽略");
            return;
        }
        String hash = Protocol.sha256Hex(text);
        if (!remember(hash)) return;
        LogStore.info("本地文本 -> " + snapshotLinks().size() + " 台设备");
        broadcast(text, null);
    }

    private void handleRemoteClip(Link source, byte[] payload) {
        try {
            JSONObject clip = new JSONObject(new String(payload, StandardCharsets.UTF_8));
            if (!"text/plain".equals(clip.optString("mime"))) return;
            String text = clip.optString("data", null);
            if (text == null) return;
            text = Protocol.normalizeText(text);
            if (text.getBytes(StandardCharsets.UTF_8).length > settings.get().maxBytes) return;
            if (!Protocol.sha256Hex(text).equals(clip.optString("sha256", ""))) return;
            String hash = Protocol.sha256Hex(text);
            if (!remember(hash)) return;
            clipboard.setPrimaryClip(ClipData.newPlainText("kclipsync", text));
            LogStore.info("收到 " + source.device + " 的文本 -> 本机剪贴板");
            broadcast(text, source);
        } catch (Exception e) {
            LogStore.warn("丢弃 CLIP：" + e.getMessage());
        }
    }

    private boolean remember(String hash) {
        synchronized (recentHashes) {
            if (recentHashes.contains(hash)) return false;
            recentHashes.add(hash);
            while (recentHashes.size() > RECENT_HASHES) {
                recentHashes.remove(recentHashes.iterator().next());
            }
            return true;
        }
    }

    private void broadcast(String text, Link except) {
        long seq = sequence.incrementAndGet();
        for (Link link : snapshotLinks()) {
            if (link == except) continue;
            try {
                link.sendClip(seq, text);
            } catch (IOException e) {
                closeLink(link);
            }
        }
    }

    private static String reasonOf(byte[] payload) {
        try {
            return new JSONObject(new String(payload, StandardCharsets.UTF_8))
                    .optString("reason", "");
        } catch (Exception e) {
            return "";
        }
    }

    private void publishStatus(String detail) {
        if (settings.get() == null) return;
        String state;
        if (!running.get()) state = StatusStore.STOPPED;
        else if (!hasLan) state = StatusStore.NO_NETWORK;
        else state = StatusStore.RUNNING;
        List<StatusStore.Peer> peers = new ArrayList<>();
        for (Link link : snapshotLinks()) {
            peers.add(StatusStore.peer(link.nodeId, link.device, link.remoteAddress(),
                    link.isInitiator()));
        }
        StatusStore.write(this, state, hasLan ? String.valueOf(activeNetwork) : null,
                detail, rootAllowed.get(), xposedHooked, peers);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
