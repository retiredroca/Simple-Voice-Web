package com.retiredroca.svcweb.web;

import com.retiredroca.svcweb.SimpleVoiceWeb;
import com.retiredroca.svcweb.tls.Json;
import com.retiredroca.svcweb.voice.AudioBridge;
import com.retiredroca.svcweb.voice.VoiceMixer;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One browser voice session: link redemption, the WebSocket handshake, and the message loop.
 *
 * <p>World and player lookups are marshalled onto the server thread. The session's own state (the
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
    private volatile VoiceMixer mixer;
    private final Map<UUID, Float> volumes = new ConcurrentHashMap<>();
    private final Map<UUID, Long> activity = new ConcurrentHashMap<>();
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
        sessions.register(player, this);
        if (closed) {
            close();
            return;
        }

        SessionRegistry.Session session = sessions.create(player);
        String name = SimpleVoiceWeb.onServer(() -> SimpleVoiceWeb.platform().playerName(player));
        displayName = name == null ? player.toString() : name;

        SimpleVoiceWeb.LOGGER.info("Web voice connection from {} for {}",
                rawSocket.getRemoteSocketAddress(), player);

        // hello first: the page stores the session token from it, and the token is what lets the
        // tab reconnect without a new command.
        upgrade.sendText("{\"type\":\"hello\",\"uuid\":\"" + player + "\",\"name\":\""
                + escape(displayName) + "\",\"session\":\"" + session.token() + "\"}");

        boolean attached = audio.subscribe(player, this);
        upgrade.sendText("{\"type\":\"attached\",\"attached\":" + attached + "}");
        if (!attached) {
            SimpleVoiceWeb.LOGGER.warn("Web voice could not attach {}; the browser will have no audio",
                    player);
            upgrade.sendText("{\"type\":\"error\",\"message\":\"Voice chat could not attach this "
                    + "player. Make sure Simple Voice Chat is running and this client does not have "
                    + "the mod installed.\"}");
        } else {
            mixer();
        }

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
            case "volume" -> handleVolume(json);
            default -> {
                // Unknown messages are ignored: the protocol is one-way except for control.
            }
        }
    }

    private void handleVolume(String json) {
        try {
            String value = Json.string(json, "uuid");
            if (value == null) {
                return;
            }
            UUID speaker = UUID.fromString(value);
            Object raw = Json.asObject(Json.parse(json)).get("volume");
            if (!(raw instanceof Number number)) {
                return;
            }
            float volume = (float) Math.max(0D,
                    Math.min(2D, number.doubleValue() / 100D));
            volumes.put(speaker, volume);
            synchronized (this) {
                VoiceMixer current = mixer;
                if (current != null) {
                    current.setVolume(speaker, volume);
                }
            }
        } catch (RuntimeException e) {
            SimpleVoiceWeb.LOGGER.debug("Ignored invalid volume message: {}", e.getMessage());
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
    public void onAudio(AudioBridge.AudioFrame frame) {
        if (frame == null || frame.speaker() == null || socket == null) {
            return;
        }
        // Called on the voice chat thread. The mixer and socket writer do the blocking work.
        VoiceMixer current = mixer();
        if (current == null) {
            return;
        }
        current.offer(frame);
        if (frame.end()) {
            activity.remove(frame.speaker());
            sendSpeaker(frame, false);
            return;
        }
        long now = System.currentTimeMillis();
        Long last = activity.get(frame.speaker());
        if (last == null || now - last >= 250L) {
            activity.put(frame.speaker(), now);
            sendSpeaker(frame, true);
        }
    }

    /**
     * The mixer is created on first use. This also handles the case where voice chat starts after
     * the web connection was accepted.
     */
    private synchronized VoiceMixer mixer() {
        if (closed || player == null) {
            return null;
        }
        if (mixer == null) {
            VoiceMixer created = new VoiceMixer(audio, this::sendMixed);
            for (Map.Entry<UUID, Float> entry : volumes.entrySet()) {
                created.setVolume(entry.getKey(), entry.getValue());
            }
            created.start();
            mixer = created;
        }
        return mixer;
    }

    private void sendMixed(byte[] data) {
        if (data != null) {
            enqueue(Outgoing.binary(data));
        }
    }

    private void sendSpeaker(AudioBridge.AudioFrame frame, boolean speaking) {
        enqueue(Outgoing.text("{\"type\":\"speaker\",\"uuid\":\"" + frame.speaker()
                + "\",\"name\":\"" + escape(frame.speakerName() == null ? frame.speaker().toString()
                : frame.speakerName()) + "\",\"speaking\":" + speaking + "}"));
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
                        // Keep proxies from closing an idle WebSocket. Browsers answer pings; the
                        // text frame also gives proxies that ignore control frames something to
                        // count as activity.
                        current.ping();
                        current.sendText("{\"type\":\"keepalive\"}");
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

    /** Sends a final message, then closes with the player-left code. */
    public void kick(String message) {
        if (closed) {
            return;
        }
        WebSocket current = socket;
        if (current != null) {
            try {
                current.sendText("{\"type\":\"offline\",\"message\":\"" + escape(message) + "\"}");
            } catch (Exception ignored) {
                // The socket is going away; the close below is what matters.
            }
        }
        close(4001, "Player left the game");
    }

    @Override
    public void close() {
        close(1000, "");
    }

    private void close(int code, String reason) {
        VoiceMixer activeMixer;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            activeMixer = mixer;
            mixer = null;
        }
        if (activeMixer != null) {
            activeMixer.close();
        }
        activity.clear();
        outbound.clear();
        Thread draining = writer;
        if (draining != null) {
            draining.interrupt();
        }
        WebSocket current = socket;
        socket = null;
        if (current != null) {
            current.close(code, reason);
        }
        UUID owner = player;
        if (owner != null) {
            SimpleVoiceWeb.LOGGER.info("Web voice disconnected: {}", owner);
            sessions.unregister(owner, this);
            audio.unsubscribe(owner, this);
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
