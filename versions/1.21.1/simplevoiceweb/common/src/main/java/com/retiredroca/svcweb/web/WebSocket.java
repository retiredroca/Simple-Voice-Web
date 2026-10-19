package com.retiredroca.svcweb.web;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * One server-side WebSocket connection (RFC 6455).
 *
 * <p>Deliberately minimal: no extensions, no subprotocols, no compression. It handles what a browser
 * actually sends - masked client frames, fragmentation, ping/pong and close - and enforces the
 * masking rule, since an unmasked client frame is a protocol error.
 */
public final class WebSocket implements AutoCloseable {

    private static final String MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int OP_CONTINUATION = 0x0;
    private static final int OP_TEXT = 0x1;
    private static final int OP_BINARY = 0x2;
    private static final int OP_CLOSE = 0x8;
    private static final int OP_PING = 0x9;
    private static final int OP_PONG = 0xA;
    private static final int MAX_MESSAGE = 1 << 20;

    private final InputStream in;
    private final OutputStream out;
    private final Object writeLock = new Object();
    private volatile boolean open = true;

    private WebSocket(InputStream in, OutputStream out) {
        this.in = in;
        this.out = out;
    }

    /**
     * Completes the opening handshake.
     *
     * @return the connection, or {@code null} when the request was not a valid upgrade
     */
    public static WebSocket handshake(InputStream in, OutputStream out, String requestHead)
            throws IOException {
        String key = header(requestHead, "sec-websocket-key");
        String upgrade = header(requestHead, "upgrade");
        if (key == null || upgrade == null || !upgrade.equalsIgnoreCase("websocket")) {
            return null;
        }
        String accept;
        try {
            accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((key.trim() + MAGIC).getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            throw new IOException("SHA-1 is required by the platform", e);
        }
        String response = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
        synchronized (out) {
            out.write(response.getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }
        return new WebSocket(in, out);
    }

    /**
     * Reads the next complete application message, or {@code null} when the peer closed.
     *
     * <p>The frame type is reported, not guessed. Audio is binary and control messages are text;
     * an Opus packet can legitimately begin with the byte {@code '{'}, so inspecting the payload
     * would misclassify it.
     */
    public Message read() throws IOException {
        ByteArrayOutputStream message = null;
        boolean text = false;
        while (open) {
            Frame frame = readFrame();
            if (frame == null) {
                return null;
            }
            switch (frame.opcode) {
                case OP_CLOSE -> {
                    close(1000, "");
                    return null;
                }
                case OP_PING -> send(OP_PONG, frame.payload);
                case OP_PONG -> {
                    // Unsolicited pongs are legal and ignored.
                }
                case OP_CONTINUATION -> {
                    if (message == null) {
                        throw new IOException("continuation frame without a message");
                    }
                    message.writeBytes(frame.payload);
                    if (message.size() > MAX_MESSAGE) {
                        throw new IOException("message exceeds " + MAX_MESSAGE + " bytes");
                    }
                    if (frame.finalFrame) {
                        return new Message(text, message.toByteArray());
                    }
                }
                case OP_TEXT, OP_BINARY -> {
                    if (message != null) {
                        throw new IOException("new message started before the previous one finished");
                    }
                    text = frame.opcode == OP_TEXT;
                    if (frame.finalFrame) {
                        return new Message(text, frame.payload);
                    }
                    message = new ByteArrayOutputStream();
                    message.writeBytes(frame.payload);
                }
                default -> throw new IOException("unsupported opcode " + frame.opcode);
            }
        }
        return null;
    }

    public void ping() throws IOException {
        send(OP_PING, new byte[0]);
    }

    public void sendText(String text) throws IOException {
        send(OP_TEXT, text.getBytes(StandardCharsets.UTF_8));
    }

    public void sendBinary(byte[] data) throws IOException {
        send(OP_BINARY, data);
    }

    public void close(int code, String reason) {
        if (!open) {
            return;
        }
        open = false;
        try {
            ByteArrayOutputStream payload = new ByteArrayOutputStream();
            payload.write((code >>> 8) & 0xFF);
            payload.write(code & 0xFF);
            payload.writeBytes(reason.getBytes(StandardCharsets.UTF_8));
            sendRaw(OP_CLOSE, payload.toByteArray());
        } catch (IOException ignored) {
            // The socket is going away either way.
        }
    }

    @Override
    public void close() {
        close(1000, "");
    }

    public boolean isOpen() {
        return open;
    }

    private void send(int opcode, byte[] payload) throws IOException {
        if (!open) {
            throw new IOException("connection is closed");
        }
        sendRaw(opcode, payload);
    }

    private void sendRaw(int opcode, byte[] payload) throws IOException {
        synchronized (writeLock) {
            out.write(0x80 | opcode);
            int length = payload.length;
            if (length < 126) {
                out.write(length);
            } else if (length <= 0xFFFF) {
                out.write(126);
                out.write((length >>> 8) & 0xFF);
                out.write(length & 0xFF);
            } else {
                out.write(127);
                for (int i = 7; i >= 0; i--) {
                    out.write((int) (((long) length >>> (8 * i)) & 0xFF));
                }
            }
            // Server frames are never masked.
            out.write(payload);
            out.flush();
        }
    }

    private Frame readFrame() throws IOException {
        int first = in.read();
        if (first < 0) {
            open = false;
            return null;
        }
        int second = in.read();
        if (second < 0) {
            throw new EOFException("truncated frame header");
        }
        boolean finalFrame = (first & 0x80) != 0;
        int opcode = first & 0x0F;
        boolean masked = (second & 0x80) != 0;
        long length = second & 0x7F;
        if (length == 126) {
            length = ((long) readByte() << 8) | readByte();
        } else if (length == 127) {
            length = 0;
            for (int i = 0; i < 8; i++) {
                length = (length << 8) | readByte();
            }
        }
        if (!masked) {
            throw new IOException("client frames must be masked");
        }
        if (length > MAX_MESSAGE) {
            throw new IOException("frame exceeds " + MAX_MESSAGE + " bytes");
        }
        byte[] mask = new byte[4];
        readFully(mask);
        byte[] payload = new byte[(int) length];
        readFully(payload);
        for (int i = 0; i < payload.length; i++) {
            payload[i] ^= mask[i & 3];
        }
        return new Frame(opcode, finalFrame, payload);
    }

    private int readByte() throws IOException {
        int value = in.read();
        if (value < 0) {
            throw new EOFException("truncated frame");
        }
        return value;
    }

    private void readFully(byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int read = in.read(buffer, offset, buffer.length - offset);
            if (read < 0) {
                throw new EOFException("truncated frame payload");
            }
            offset += read;
        }
    }

    private static String header(String head, String name) {
        for (String line : head.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }

    /** One complete application message and whether the peer sent it as text. */
    public record Message(boolean text, byte[] payload) {}

    private record Frame(int opcode, boolean finalFrame, byte[] payload) {}
}
