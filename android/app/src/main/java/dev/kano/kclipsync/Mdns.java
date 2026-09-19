package dev.kano.kclipsync;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Advertises and browses {@code _kclipsync._tcp} with platform NsdManager, pinned to the current
 * Wi-Fi/Ethernet network when one exists. No external mDNS implementation is needed.
 */
public final class Mdns {
    public static final String SERVICE_TYPE = "_kclipsync._tcp.";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "kclipsync-mdns");
        thread.setDaemon(true);
        return thread;
    });

    private Mdns() {}

    public static Advertiser advertise(Context context, Network network, String name, int port, String nodeId) {
        NsdManager nsd = context.getSystemService(NsdManager.class);
        if (nsd == null) return null;
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(name);
        info.setServiceType(SERVICE_TYPE);
        info.setPort(port);
        info.setAttribute("v", String.valueOf(Protocol.VERSION));
        info.setAttribute("id", nodeId);
        Advertiser advertiser = new Advertiser(nsd);
        try {
            // API 35's network-scoped overload is not available everywhere yet. The default
            // registration follows the active default network, which is the Wi-Fi network while
            // this service is running. Discovery below remains explicitly scoped.
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, EXECUTOR, advertiser);
            return advertiser;
        } catch (Exception e) {
            LogStore.warn("mDNS advertise failed: " + e.getMessage());
            return null;
        }
    }

    public static final class Advertiser implements NsdManager.RegistrationListener, AutoCloseable {
        private final NsdManager nsd;

        Advertiser(NsdManager nsd) {
            this.nsd = nsd;
        }

        @Override
        public void onServiceRegistered(NsdServiceInfo info) {
            LogStore.info("mDNS advertising as " + info.getServiceName());
        }

        @Override
        public void onRegistrationFailed(NsdServiceInfo info, int errorCode) {
            LogStore.warn("mDNS advertise failed: " + errorCode);
        }

        @Override
        public void onServiceUnregistered(NsdServiceInfo info) {
        }

        @Override
        public void onUnregistrationFailed(NsdServiceInfo info, int errorCode) {
        }

        @Override
        public void close() {
            try {
                nsd.unregisterService(this);
            } catch (Exception ignored) {
            }
        }
    }

    public static final class Instance {
        public final String name;
        public final String nodeId;
        public final List<InetSocketAddress> addresses;

        Instance(String name, String nodeId, List<InetSocketAddress> addresses) {
            this.name = name;
            this.nodeId = nodeId;
            this.addresses = addresses;
        }

        public String identity() {
            return nodeId == null || nodeId.isEmpty() ? name : nodeId;
        }
    }

    public static List<Instance> discover(Context context, Network network, long timeoutMs) {
        NsdManager nsd = context.getSystemService(NsdManager.class);
        if (nsd == null) return new ArrayList<>();
        Session session = new Session(nsd);
        try {
            // Prefer the network-scoped overload when present so multicast cannot leak to a
            // cellular default. Fall back to the unscoped overload on builds that lack it.
            if (network != null) {
                nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, network,
                        session.executor, session.listener);
            } else {
                nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, session.listener);
            }
            synchronized (session.done) {
                session.done.wait(timeoutMs);
            }
        } catch (Exception e) {
            LogStore.warn("mDNS discovery failed: " + e.getMessage());
        } finally {
            session.close();
        }
        return session.instances();
    }

    private static final class Session {
        final NsdManager nsd;
        final ExecutorService executor = EXECUTOR;
        final Object done = new Object();
        final Map<String, Service> found = new LinkedHashMap<>();
        final List<NsdManager.ServiceInfoCallback> callbacks = new ArrayList<>();
        boolean closed;

        Session(NsdManager nsd) {
            this.nsd = nsd;
        }

        final NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                LogStore.warn("mDNS discovery start failed: " + errorCode);
                synchronized (done) {
                    done.notifyAll();
                }
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
            }

            @Override
            public void onDiscoveryStarted(String serviceType) {
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                if (!serviceInfo.getServiceType().contains("_kclipsync._tcp")) return;
                NsdManager.ServiceInfoCallback callback = new NsdManager.ServiceInfoCallback() {
                    @Override
                    public void onServiceInfoCallbackRegistrationFailed(int errorCode) {
                    }

                    @Override
                    public void onServiceUpdated(NsdServiceInfo info) {
                        List<InetSocketAddress> addresses = addressesOf(info);
                        if (addresses.isEmpty()) return;
                        synchronized (found) {
                            Service service = found.computeIfAbsent(info.getServiceName(), ignored ->
                                    new Service(info.getServiceName(), nodeIdOf(info)));
                            service.addresses.clear();
                            service.addresses.addAll(addresses);
                            service.nodeId = nodeIdOf(info);
                            service.lastSeen = System.currentTimeMillis();
                        }
                    }

                    @Override
                    public void onServiceLost() {
                    }

                    @Override
                    public void onServiceInfoCallbackUnregistered() {
                    }
                };
                synchronized (callbacks) {
                    if (closed) return;
                    callbacks.add(callback);
                }
                try {
                    nsd.registerServiceInfoCallback(serviceInfo, executor, callback);
                } catch (Exception e) {
                    LogStore.warn("mDNS resolve failed: " + e.getMessage());
                }
            }
        };

        void close() {
            synchronized (callbacks) {
                closed = true;
                for (NsdManager.ServiceInfoCallback callback : callbacks) {
                    try {
                        nsd.unregisterServiceInfoCallback(callback);
                    } catch (Exception ignored) {
                    }
                }
                callbacks.clear();
            }
            try {
                nsd.stopServiceDiscovery(listener);
            } catch (Exception ignored) {
            }
        }

        List<Instance> instances() {
            List<Instance> out = new ArrayList<>();
            long now = System.currentTimeMillis();
            synchronized (found) {
                for (Service service : found.values()) {
                    if (service.addresses.isEmpty() || now - service.lastSeen > 30_000) continue;
                    out.add(new Instance(service.name, service.nodeId,
                            new ArrayList<>(service.addresses)));
                }
            }
            return out;
        }
    }

    private static final class Service {
        final String name;
        volatile String nodeId;
        final List<InetSocketAddress> addresses = new ArrayList<>();
        volatile long lastSeen = System.currentTimeMillis();

        Service(String name, String nodeId) {
            this.name = name;
            this.nodeId = nodeId;
        }
    }

    private static String nodeIdOf(NsdServiceInfo info) {
        try {
            byte[] value = info.getAttributes().get("id");
            return value == null ? "" : new String(value, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /** IPv4 first, then global IPv6, then link-local IPv6. */
    private static List<InetSocketAddress> addressesOf(NsdServiceInfo info) {
        int port = info.getPort();
        List<InetSocketAddress> v4 = new ArrayList<>();
        List<InetSocketAddress> v6 = new ArrayList<>();
        List<InetSocketAddress> linkLocal = new ArrayList<>();
        for (InetAddress address : info.getHostAddresses()) {
            if (address == null || address.isLoopbackAddress() || address.isMulticastAddress() || port <= 0) {
                continue;
            }
            InetSocketAddress socket = new InetSocketAddress(address, port);
            if (address instanceof Inet4Address) {
                v4.add(socket);
            } else if (address instanceof Inet6Address && address.isLinkLocalAddress()) {
                linkLocal.add(socket);
            } else {
                v6.add(socket);
            }
        }
        List<InetSocketAddress> out = new ArrayList<>(v4);
        out.addAll(v6);
        out.addAll(linkLocal);
        return out;
    }

    /** Orders addresses that are on one of the phone's own prefixes first. */
    public static List<InetSocketAddress> preferOnLink(Context context, Network network,
                                                       List<InetSocketAddress> addresses) {
        List<LinkAddress> mine = new ArrayList<>();
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        if (manager != null && network != null) {
            LinkProperties properties = manager.getLinkProperties(network);
            if (properties != null) mine.addAll(properties.getLinkAddresses());
        }
        List<InetSocketAddress> first = new ArrayList<>();
        List<InetSocketAddress> rest = new ArrayList<>();
        for (InetSocketAddress address : addresses) {
            (onLink(address.getAddress(), mine) ? first : rest).add(address);
        }
        first.addAll(rest);
        return first;
    }

    private static boolean onLink(InetAddress address, List<LinkAddress> mine) {
        if (address instanceof Inet6Address && address.isLinkLocalAddress()) return true;
        byte[] x = address.getAddress();
        for (LinkAddress link : mine) {
            byte[] y = link.getAddress().getAddress();
            if (x.length != y.length) continue;
            int bits = link.getPrefixLength();
            boolean same = true;
            for (int i = 0; i < bits && same; i++) {
                int mask = 0x80 >> (i & 7);
                same = ((x[i >> 3] ^ y[i >> 3]) & mask) == 0;
            }
            if (same) return true;
        }
        return false;
    }
}
