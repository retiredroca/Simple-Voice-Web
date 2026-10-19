package com.retiredroca.svcweb.web;

import com.retiredroca.svcweb.SimpleVoiceWeb;
import com.retiredroca.svcweb.tls.Json;
import com.retiredroca.svcweb.voice.AudioBridge;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One browser voice session: link redemption, the WebSocket handshake, and the message loop.
 *
 * <p>All voice chat calls are marshalled onto the server thread. The session's own state (the
 * socket, the sink) is guarded by this object, but it never holds a lock while calling into voice
 * chat: that would deadlock against the server thread the call is waiting on.
 */
public final class Session implements AudioBridge.Sink, AutoCloseable {

    private final LinkRegistry links;
    private final SessionRegistry sessions;
    private final AudioBridge audio;

    private volatile WebSocket socket;
    private volatile UUID player;
    private volatile String displayName;
    private volatile boolean closed;
    private final Set<UUID> announced = ConcurrentHashMap.newKeySet();
    private final BlockingQueue<Outgoing> outbound = new ArrayBlockingQueue<>(256);
    private volatile Thread writer;

    public Session(LinkRegistry links, SessionRegistry sessions, AudioBridge audio) {
        this.links = links;
        this.sessions = sessions;
        this.audio = audio;
    }

    public void handle(Socket rawSocket, InputStream in, OutputStream out, String requestHead,
                       Map<String, String> query) throws Exception {
        // A live session may reconnect with its token; otherwise a single-use link is redeemed.
        String token = query.get("session");
        SessionRegistry.Session existing = sessions.touch(token);
        if (existing != null) {
            player = existing.player();
        } else {
            String key = query.get("link");
            UUID fromLink = key == null ? null : links.consume(key);
            if (fromLink == null) {
                reject(out, 1008, "This link is invalid or has already been used.");
                return;
            }
            player = fromLink;
        }

        WebSocket upgrade = WebSocket.handshake(in, out, requestHead);
        if (upgrade == null) {
            reject(out, 1008, "Expected a WebSocket upgrade.");
            return;
        }
        socket = upgrade;

        SessionRegistry.Session session = sessions.create(player);
        String name = SimpleVoiceWeb.onServer(() -> SimpleVoiceWeb.platform().playerName(player));
        displayName = name == null ? player.toString() : name;

        // hello first: the page stores the session token from it, and the token is what lets the
        // tab reconnect without a new command.
        upgrade.sendText("{\"type\":\"hello\",\"uuid\":\"" + player + "\",\"name\":\""
                + escape(displayName) + "\",\"session\":\"" + session.token() + "\"}");

        boolean attached = audio.attach(player);
        if (!attached) {
            upgrade.sendText("{\"type\":\"error\",\"message\":\"Voice chat is not available yet. "
                    + "Make sure Simple Voice Chat is installed and you do not have the mod.\"}");
        }

        audio.subscribe(player, this);
        startWriter();

        try {
            while (!closed) {
                WebSocket.Message message = upgrade.read();
                if (message == null) {
                    break;
                }
                byte[] payload = message.payload();
                if (payload.length == 0) {
                    continue;
                }
                if (message.text()) {
                    handleText(new String(payload, java.nio.charset.StandardCharsets.UTF_8));
                } else {
                    audio.send(player, payload);
                }
            }
        } finally {
            close();
        }
    }

    private void handleText(String json) {
        String type = Json.string(json, "type");
        if (type == null) {
            return;
        }
        switch (type) {
            case "end", "mute" -> audio.endStream(player);
            default -> {
                // Unknown messages are ignored: the protocol is one-way except for control.
            }
        }
    }

    private void reject(OutputStream out, int code, String reason) throws Exception {
        String body = reason;
        String response = "HTTP/1.1 403 Forbidden\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                + "\r\nConnection: close\r\n\r\n" + body;
        out.write(response.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        out.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        out.flush();
    }

    @Override
    public void onProximity(UUID speaker, String speakerName, byte[] opus, float gain,
                            boolean whispering) {
        if (opus.length == 0 || socket == null) {
            return;
        }
        // Called on the voice chat thread. Never write to the socket here: one slow browser would
        // stall audio for every listener. The queue is bounded and drops the oldest frame, which is
        // the right behaviour for real-time audio.
        if (announced.add(speaker)) {
            enqueue(Outgoing.text("{\"type\":\"speaker\",\"uuid\":\"" + speaker
                    + "\",\"name\":\"" + escape(speakerName == null ? speaker.toString() : speakerName)
                    + "\",\"whispering\":" + whispering + "}"));
        }
        enqueue(Outgoing.binary(audioFrame(speaker, gain, opus)));
    }

    /**
     * One audio packet: {@code [0x01][speaker UUID 16][gain float32 big-endian][opus]}.
     *
     * <p>The gain is computed on the server from positions it already holds, so the browser never
     * receives a coordinate.
     */
    private static byte[] audioFrame(UUID speaker, float gain, byte[] opus) {
        ByteBuffer buffer = ByteBuffer.allocate(1 + 16 + 4 + opus.length)
                .order(ByteOrder.BIG_ENDIAN);
        buffer.put((byte) 0x01);
        buffer.putLong(speaker.getMostSignificantBits());
        buffer.putLong(speaker.getLeastSignificantBits());
        buffer.putFloat(gain);
        buffer.put(opus);
        return buffer.array();
    }

    @Override
    public void onGroup(UUID speaker, String speakerName, byte[] opus) {
        onProximity(speaker, speakerName, opus, 1F, false);
    }

    @Override
    public void onSpeaking(boolean speaking) {
        enqueue(Outgoing.text("{\"type\":\"speaking\",\"speaking\":" + speaking + "}"));
    }

    private void enqueue(Outgoing outgoing) {
        if (closed) {
            return;
        }
        if (!outbound.offer(outgoing)) {
            outbound.poll();
            outbound.offer(outgoing);
        }
    }

    private void startWriter() {
        Thread thread = new Thread(() -> {
            try {
                while (!closed) {
                    Outgoing outgoing = outbound.poll(20, java.util.concurrent.TimeUnit.SECONDS);
                    WebSocket current = socket;
                    if (current == null || !current.isOpen()) {
                        return;
                    }
                    if (outgoing == null) {
                        // Keep proxies from closing an idle WebSocket. Browsers answer pings.
                        current.ping();
                        continue;
                    }
                    if (outgoing.text) {
                        current.sendText(outgoing.textValue);
                    } else {
                        current.sendBinary(outgoing.data);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                close();
            }
        }, "SimpleVoiceWeb-Writer");
        thread.setDaemon(true);
        thread.start();
        writer = thread;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        announced.clear();
        outbound.clear();
        Thread draining = writer;
        if (draining != null) {
            draining.interrupt();
        }
        WebSocket current = socket;
        socket = null;
        if (current != null) {
            current.close();
        }
        UUID owner = player;
        if (owner != null) {
            audio.unsubscribe(owner);
        }
    }

    private static String escape(String value) {
        return Json.escape(value);
    }

    private record Outgoing(boolean text, String textValue, byte[] data) {
        static Outgoing text(String value) {
            return new Outgoing(true, value, null);
        }

        static Outgoing binary(byte[] value) {
            return new Outgoing(false, null, value);
        }
    }
}
