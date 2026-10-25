package io.github.ddedic.ringdoor;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * The TV's one connection to the bridge Worker: a minimal WebSocket client (RFC 6455, text frames only, no
 * library). It sends a ping every minute, which the Worker answers without waking up, and reads until closed.
 */
final class Bridge {
    static final int PING_MS = 60_000;

    interface Listener {
        void onOpen(Bridge bridge);
        void onText(Bridge bridge, String text);
    }

    private final SecureRandom random = new SecureRandom();
    private Socket socket;
    private OutputStream out;

    /** Connects and reads until the connection ends; throws on errors. close() from another thread ends it. */
    void run(String url, String token, Listener listener) throws IOException {
        URI uri = URI.create(url);
        int port = uri.getPort() > 0 ? uri.getPort() : 443;
        SSLSocket ssl = (SSLSocket) SSLSocketFactory.getDefault().createSocket(uri.getHost(), port);
        socket = ssl;
        try {
            ssl.setSoTimeout(PING_MS);
            ssl.startHandshake();
            out = ssl.getOutputStream();
            byte[] key = new byte[16];
            random.nextBytes(key);
            String request = "GET " + uri.getRawPath() + " HTTP/1.1\r\nHost: " + uri.getHost() + "\r\n"
                    + "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\n"
                    + "Sec-WebSocket-Key: " + Base64.getEncoder().encodeToString(key) + "\r\n"
                    + "Authorization: Bearer " + token + "\r\nUser-Agent: TechnabitTVOS/1.0\r\n\r\n";
            synchronized (this) {
                out.write(request.getBytes(StandardCharsets.US_ASCII));
                out.flush();
            }
            DataInputStream in = new DataInputStream(ssl.getInputStream());
            String status = line(in);
            if (!status.contains(" 101 ")) throw new IOException("bridge refused: " + status.trim());
            while (!line(in).isEmpty()) { /* response headers */ }
            listener.onOpen(this);
            int quiet = 0;
            ByteArrayOutputStream message = new ByteArrayOutputStream();
            while (true) {
                int b0;
                try {
                    b0 = in.read();
                } catch (SocketTimeoutException idle) {
                    if (++quiet >= 2) throw new IOException("bridge silent");
                    send("{\"t\":\"ping\"}");
                    continue;
                }
                if (b0 < 0) return;
                quiet = 0;
                int b1 = in.readUnsignedByte();
                long length = b1 & 0x7F;
                if (length == 126) length = in.readUnsignedShort();
                else if (length == 127) length = in.readLong();
                if (length > 1_000_000) throw new IOException("bridge frame too large");
                byte[] mask = (b1 & 0x80) != 0 ? new byte[4] : null;
                if (mask != null) in.readFully(mask);
                byte[] payload = new byte[(int) length];
                in.readFully(payload);
                if (mask != null) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
                int opcode = b0 & 0x0F;
                switch (opcode) {
                    case 0x8: return;                       // close
                    case 0x9: frame(0xA, payload); break;   // ping → pong
                    case 0x1: case 0x0:
                        message.write(payload, 0, payload.length);
                        if ((b0 & 0x80) != 0) {
                            listener.onText(this, message.toString(StandardCharsets.UTF_8.name()));
                            message.reset();
                        }
                        break;
                    default: break;
                }
            }
        } finally {
            close();
        }
    }

    void send(String text) throws IOException {
        frame(0x1, text.getBytes(StandardCharsets.UTF_8));
    }

    void close() {
        Socket s = socket;
        if (s != null) try { s.close(); } catch (IOException ignored) { }
    }

    /** A masked client frame, as RFC 6455 requires of clients. */
    private synchronized void frame(int opcode, byte[] payload) throws IOException {
        if (out == null) throw new IOException("bridge not open");
        ByteArrayOutputStream f = new ByteArrayOutputStream(payload.length + 14);
        f.write(0x80 | opcode);
        if (payload.length < 126) {
            f.write(0x80 | payload.length);
        } else if (payload.length < 65536) {
            f.write(0x80 | 126);
            f.write(payload.length >>> 8);
            f.write(payload.length & 0xFF);
        } else {
            f.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) f.write((int) (((long) payload.length) >>> (8 * i)) & 0xFF);
        }
        byte[] mask = new byte[4];
        random.nextBytes(mask);
        f.write(mask, 0, 4);
        for (int i = 0; i < payload.length; i++) f.write(payload[i] ^ mask[i & 3]);
        out.write(f.toByteArray());
        out.flush();
    }

    private static String line(DataInputStream in) throws IOException {
        StringBuilder s = new StringBuilder();
        for (int c; (c = in.read()) >= 0; ) {
            if (c == '\n') break;
            if (c != '\r') s.append((char) c);
            if (s.length() > 4096) throw new IOException("bridge header too long");
        }
        return s.toString();
    }
}
