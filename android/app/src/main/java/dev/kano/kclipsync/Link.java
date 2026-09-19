package dev.kano.kclipsync;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import android.net.Network;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * One plaintext TCP link. Both endpoints may send frames at the same time, so reads and writes
 * use separate locks on the socket streams.
 */
public final class Link implements Closeable {
    public static final int READ_TIMEOUT_MS = 90_000;
    public static final int HANDSHAKE_TIMEOUT_MS = 10_000;

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private final Object sendLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final boolean initiator;
    private final String localNodeId;
    private final String remoteAddress;

    public volatile String nodeId = "";
    public volatile String device = "";

    public Link(Socket socket, boolean initiator) throws IOException {
        this(socket, initiator, "");
    }

    public Link(Socket socket, boolean initiator, String localNodeId) throws IOException {
        this.socket = socket;
        this.initiator = initiator;
        this.localNodeId = localNodeId == null ? "" : localNodeId;
        this.remoteAddress = String.valueOf(socket.getRemoteSocketAddress());
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
        this.in = new DataInputStream(socket.getInputStream());
        this.out = socket.getOutputStream();
    }

    public static Link connect(Network network, InetSocketAddress address, int timeoutMs)
            throws IOException {
        return connect(network, address, timeoutMs, "");
    }

    public static Link connect(Network network, InetSocketAddress address, int timeoutMs,
                              String localNodeId) throws IOException {
        Socket socket = new Socket();
        try {
            if (network != null) network.bindSocket(socket);
            socket.connect(address, timeoutMs);
            return new Link(socket, true, localNodeId);
        } catch (IOException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    public boolean isInitiator() {
        return initiator;
    }

    public String initiatorId() {
        return initiator ? localNodeId : nodeId;
    }

    public boolean isOpen() {
        return !closed.get() && !socket.isClosed();
    }

    public String remoteAddress() {
        return remoteAddress;
    }

    public void afterHandshake() throws IOException {
        socket.setSoTimeout(READ_TIMEOUT_MS);
    }

    public void send(int type, byte[] payload) throws IOException {
        if (closed.get()) throw new IOException("link closed");
        int length = payload.length + 1;
        ByteBuffer frame = ByteBuffer.allocate(4 + length);
        frame.putInt(length);
        frame.put((byte) type);
        frame.put(payload);
        synchronized (sendLock) {
            out.write(frame.array());
            out.flush();
        }
    }

    public void sendJson(int type, JSONObject payload) throws IOException {
        send(type, payload.toString().getBytes(StandardCharsets.UTF_8));
    }

    public Frame recv(int maxFrame) throws IOException {
        int length = in.readInt();
        if (length <= 0 || length > maxFrame) {
            throw new IOException("invalid frame length " + length);
        }
        byte[] frame = new byte[length];
        in.readFully(frame);
        byte[] payload = new byte[length - 1];
        System.arraycopy(frame, 1, payload, 0, payload.length);
        return new Frame(frame[0] & 0xff, payload);
    }

    public JSONObject recvJson(int maxFrame) throws IOException, JSONException {
        Frame frame = recv(maxFrame);
        return new JSONObject(new String(frame.payload, StandardCharsets.UTF_8));
    }

    public void sendHello(String id, String device, int port) throws IOException {
        sendHello(id, device, port, id);
    }

    public void sendHello(String id, String device, int port, String initiator) throws IOException {
        try {
            JSONObject hello = new JSONObject()
                    .put("v", Protocol.VERSION)
                    .put("id", id)
                    .put("device", device)
                    .put("port", port)
                    .put("initiator", initiator);
            sendJson(Protocol.T_HELLO, hello);
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    public JSONObject readHello(int maxFrame) throws IOException, JSONException {
        Frame frame = recv(maxFrame);
        if (frame.type != Protocol.T_HELLO) {
            throw new IOException("expected HELLO, got " + frame.type);
        }
        JSONObject hello = new JSONObject(new String(frame.payload, StandardCharsets.UTF_8));
        if (hello.optInt("v", -1) != Protocol.VERSION) {
            throw new IOException("protocol version mismatch");
        }
        String id = hello.optString("id", "");
        if (id.isEmpty() || hello.optString("initiator", "").isEmpty()) {
            throw new IOException("HELLO missing node id");
        }
        nodeId = id;
        device = hello.optString("device", remoteAddress);
        return hello;
    }

    public void sendClip(long seq, String text) throws IOException {
        try {
            sendJson(Protocol.T_CLIP, new JSONObject()
                    .put("seq", seq)
                    .put("mime", "text/plain")
                    .put("sha256", Protocol.sha256Hex(text))
                    .put("data", text));
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    public void sendBye(String reason) {
        try {
            sendJson(Protocol.T_BYE, new JSONObject().put("reason", reason));
        } catch (Exception ignored) {
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            socket.shutdownInput();
        } catch (IOException ignored) {
        }
        try {
            socket.shutdownOutput();
        } catch (IOException ignored) {
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    public static final class Frame {
        public final int type;
        public final byte[] payload;

        Frame(int type, byte[] payload) {
            this.type = type;
            this.payload = payload;
        }
    }
}
