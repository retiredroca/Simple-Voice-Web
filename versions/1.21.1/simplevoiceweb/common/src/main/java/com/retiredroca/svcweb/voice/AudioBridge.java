package com.retiredroca.svcweb.voice;

import com.retiredroca.svcweb.SimpleVoiceWeb;
import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.Position;
import de.maxhenkel.voicechat.api.ServerPlayer;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiolistener.PlayerAudioListener;
import de.maxhenkel.voicechat.api.audiosender.AudioSender;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.PlayerDisconnectedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;
import de.maxhenkel.voicechat.api.packets.EntitySoundPacket;
import de.maxhenkel.voicechat.api.packets.SoundPacket;
import de.maxhenkel.voicechat.api.packets.StaticSoundPacket;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Bridges Simple Voice Chat to the web session.
 *
 * <p>Two SVC primitives do the work. {@link AudioSender} injects browser audio as if the player had
 * spoken, so proximity, permissions and groups all behave normally. {@link PlayerAudioListener}
 * receives every packet the player would have heard, including when the player has no voice chat
 * connection at all - {@code Server.sendSoundPacket} calls the listeners before it checks for one.
 *
 * <p><b>Privacy:</b> positions never leave the server. {@link EntitySoundPacket} carries only the
 * speaker's UUID and a distance, so the audible volume is computed here, from the web player's real
 * position and the speaker's, and sent as a single scalar. No coordinates, no direction.
 */
public final class AudioBridge {

    private static final AudioBridge INSTANCE = new AudioBridge();

    /**
     * The process-wide bridge. Simple Voice Chat creates the plugin itself, so the plugin and the
     * web service have to agree on one instance without either constructing the other.
     */
    public static AudioBridge instance() {
        return INSTANCE;
    }

    private AudioBridge() {}

    /** Receives decoded audio for one web listener. Implemented by the session. */
    public interface Sink {
        /** Opus-encoded audio from another player, with a precomputed linear gain in [0, 1]. */
        void onProximity(UUID speaker, String speakerName, byte[] opus, float gain, boolean whispering);

        /** Opus-encoded group audio, which SVC plays at full volume. */
        void onGroup(UUID speaker, String speakerName, byte[] opus);

        /** The web player is speaking, so the browser can show it. */
        void onSpeaking(boolean speaking);
    }

    private final Map<UUID, Sink> sinks = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerAudioListener> listeners = new ConcurrentHashMap<>();
    private final Map<UUID, AudioSender> senders = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastAudio = new ConcurrentHashMap<>();

    private volatile VoicechatServerApi api;
    private volatile boolean proximity = true;
    private volatile boolean groups = true;
    private volatile Function<UUID, String> nameResolver = UUID::toString;

    public void setOptions(boolean proximity, boolean groups) {
        this.proximity = proximity;
        this.groups = groups;
    }

    /** Resolves a display name; supplied by the loader seam so no server lookup happens here. */
    public void setNameResolver(Function<UUID, String> resolver) {
        this.nameResolver = resolver;
    }

    public boolean available() {
        return api != null;
    }

    /** Registers the SVC events. Called once, from the voice chat plugin. */
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class, this::onServerStarted);
        registration.registerEvent(VoicechatServerStoppedEvent.class, this::onServerStopped);
        registration.registerEvent(PlayerDisconnectedEvent.class, this::onPlayerDisconnected);
    }

    private void onServerStarted(VoicechatServerStartedEvent event) {
        api = event.getVoicechat();
        SimpleVoiceWeb.LOGGER.info("Simple Voice Web attached to the voice chat server");
        for (UUID player : sinks.keySet()) {
            attach(player);
        }
    }

    private void onServerStopped(VoicechatServerStoppedEvent event) {
        listeners.clear();
        senders.clear();
        api = null;
    }

    private void onPlayerDisconnected(PlayerDisconnectedEvent event) {
        detach(event.getPlayerUuid());
    }

    /**
     * Starts listening for a web player and enables their microphone.
     *
     * @return true when the bridge is ready; false when voice chat is not running or the player is
     *         using the mod (in which case their own client already owns the audio)
     */
    public boolean attach(UUID player) {
        return SimpleVoiceWeb.onServer(() -> attachOnServer(player));
    }

    private synchronized boolean attachOnServer(UUID player) {
        VoicechatServerApi serverApi = api;
        if (serverApi == null) {
            SimpleVoiceWeb.LOGGER.warn("Voice chat API is not ready for {}; cannot attach", player);
            return false;
        }
        VoicechatConnection connection = serverApi.getConnectionOf(player);
        if (connection == null) {
            SimpleVoiceWeb.LOGGER.warn("No voice chat connection or player state for {}; cannot attach",
                    player);
            return false;
        }
        if (connection.isInstalled()) {
            SimpleVoiceWeb.LOGGER.warn("Player {} has Simple Voice Chat installed - the browser "
                    + "cannot act as their microphone", player);
            return false;
        }

        if (!listeners.containsKey(player)) {
            PlayerAudioListener listener = serverApi.playerAudioListenerBuilder()
                    .setPlayer(player)
                    .setPacketListener(packet -> deliver(player, packet))
                    .build();
            if (serverApi.registerAudioListener(listener)) {
                listeners.put(player, listener);
            } else {
                SimpleVoiceWeb.LOGGER.warn("Could not register an audio listener for {}", player);
                return false;
            }
        }

        if (!senders.containsKey(player)) {
            AudioSender sender = serverApi.createAudioSender(connection);
            if (serverApi.registerAudioSender(sender)) {
                senders.put(player, sender);
            } else {
                // A listener without a sender would forward audio but never let the player speak.
                PlayerAudioListener orphan = listeners.remove(player);
                if (orphan != null) {
                    serverApi.unregisterAudioListener(orphan);
                }
                SimpleVoiceWeb.LOGGER.warn("Could not register an audio sender for {}", player);
                return false;
            }
        }

        // SVC defaults a mod-less player to "disconnected"; the browser is their connection.
        connection.setConnected(true);
        SimpleVoiceWeb.LOGGER.info("Attached {} to voice chat for web audio", player);
        return true;
    }

    public void detach(UUID player) {
        try {
            SimpleVoiceWeb.runOnServer(() -> detachOnServer(player));
        } catch (RuntimeException e) {
            SimpleVoiceWeb.LOGGER.debug("Could not detach {} from voice chat: {}", player,
                    e.getMessage());
        }
    }

    private synchronized void detachOnServer(UUID player) {
        VoicechatServerApi serverApi = api;
        PlayerAudioListener listener = listeners.remove(player);
        if (serverApi != null && listener != null) {
            serverApi.unregisterAudioListener(listener);
        }
        AudioSender sender = senders.remove(player);
        if (serverApi != null && sender != null) {
            serverApi.unregisterAudioSender(sender);
        }
        lastAudio.remove(player);
        if (serverApi != null) {
            VoicechatConnection connection = serverApi.getConnectionOf(player);
            if (connection != null && !connection.isInstalled()) {
                connection.setConnected(false);
            }
        }
    }

    /**
     * Starts delivering to a sink and attaches the player if needed.
     *
     * @return true when voice chat is ready for this player
     */
    public boolean subscribe(UUID player, Sink sink) {
        sinks.put(player, sink);
        try {
            return attach(player);
        } catch (RuntimeException e) {
            SimpleVoiceWeb.LOGGER.warn("Could not attach {} to voice chat: {}", player,
                    e.getMessage());
            return false;
        }
    }

    /**
     * Removes a sink only if it is still the current one. A reconnect can replace the sink before
     * the old connection closes; the old close must not detach the new connection's audio.
     */
    public void unsubscribe(UUID player, Sink sink) {
        if (sinks.remove(player, sink)) {
            detach(player);
        }
    }

    /** Removes every sink for a player and detaches them, used when the player leaves the game. */
    public void revoke(UUID player) {
        sinks.remove(player);
        detach(player);
    }

    /** Feeds browser microphone audio to voice chat. */
    public boolean send(UUID player, byte[] opus) {
        // Simple Voice Chat's Opus MTU is 1275 bytes (AudioUtils.MAX_OPUS_PAYLOAD_SIZE); anything
        // larger would be truncated on the way to clients.
        if (opus == null || opus.length == 0 || opus.length > 1275) {
            return false;
        }
        Boolean sent = SimpleVoiceWeb.onServer(() -> {
            AudioSender sender = senders.get(player);
            if (sender == null || !sender.canSend()) {
                return Boolean.FALSE;
            }
            boolean ok = sender.send(opus);
            if (ok) {
                lastAudio.put(player, System.currentTimeMillis());
                Sink sink = sinks.get(player);
                if (sink != null) {
                    sink.onSpeaking(true);
                }
            }
            return ok;
        });
        return Boolean.TRUE.equals(sent);
    }

    /** Tells clients the stream ended, so the speaker icon clears. */
    public void endStream(UUID player) {
        Sink sink = sinks.get(player);
        if (sink != null) {
            sink.onSpeaking(false);
        }
        try {
            SimpleVoiceWeb.runOnServer(() -> {
                AudioSender sender = senders.get(player);
                if (sender != null && sender.canSend()) {
                    sender.reset();
                }
            });
        } catch (RuntimeException e) {
            SimpleVoiceWeb.LOGGER.debug("Could not end the audio stream for {}: {}", player,
                    e.getMessage());
        }
    }

    /** Joins the web player to a group, so group audio flows both ways. */
    public boolean joinGroup(UUID player, String groupId) {
        return SimpleVoiceWeb.onServer(() -> {
            VoicechatServerApi serverApi = api;
            if (serverApi == null) {
                return false;
            }
            VoicechatConnection connection = serverApi.getConnectionOf(player);
            if (connection == null) {
                return false;
            }
            Group group = serverApi.getGroup(UUID.fromString(groupId));
            if (group == null) {
                return false;
            }
            connection.setGroup(group);
            return true;
        });
    }

    public boolean leaveGroup(UUID player) {
        return SimpleVoiceWeb.onServer(() -> {
            VoicechatServerApi serverApi = api;
            if (serverApi == null) {
                return false;
            }
            VoicechatConnection connection = serverApi.getConnectionOf(player);
            if (connection == null) {
                return false;
            }
            connection.setGroup(null);
            return true;
        });
    }

    /** Every group the voice chat server knows about. */
    public java.util.List<Group> groups() {
        return SimpleVoiceWeb.onServer(() -> {
            VoicechatServerApi serverApi = api;
            return serverApi == null ? java.util.List.<Group>of()
                    : new java.util.ArrayList<>(serverApi.getGroups());
        });
    }

    /**
     * Decides what a web listener hears. Runs on the voice chat thread.
     *
     * <p>The gain is computed from positions that stay on the server: the listener's eye position
     * and the speaker's. It mirrors the client's own linear falloff
     * ({@code 1 - distance / maxDistance}), so a web listener hears the same loudness curve as a
     * player running the mod.
     */
    private void deliver(UUID listener, SoundPacket packet) {
        Sink sink = sinks.get(listener);
        if (sink == null || packet == null) {
            return;
        }
        byte[] opus = packet.getOpusEncodedData();
        if (opus == null || opus.length == 0) {
            // An empty payload ends a stream; forward it so the browser stops cleanly.
            sink.onProximity(packet.getSender(), null, new byte[0], 0F, false);
            return;
        }
        UUID speaker = packet.getSender();
        if (speaker == null || speaker.equals(listener)) {
            return;
        }
        String name = nameOf(speaker);

        if (packet instanceof StaticSoundPacket) {
            if (groups) {
                sink.onGroup(speaker, name, opus);
            }
            return;
        }
        if (!proximity) {
            return;
        }
        float gain = 1F;
        boolean whispering = false;
        if (packet instanceof EntitySoundPacket entity) {
            whispering = entity.isWhispering();
            gain = gain(listener, speaker, entity.getDistance());
        }
        if (gain <= 0F) {
            return;
        }
        sink.onProximity(speaker, name, opus, gain, whispering);
    }

    /**
     * Linear distance gain in [0, 1].
     *
     * <p>Returns 1 when either player cannot be located: SVC already decided this packet was audible
     * for this listener, so suppressing it would be wrong. It never leaves the server.
     */
    private float gain(UUID listener, UUID speaker, float maxDistance) {
        if (maxDistance <= 0F) {
            return 1F;
        }
        try {
            VoicechatServerApi serverApi = api;
            if (serverApi == null) {
                return 1F;
            }
            VoicechatConnection listenerConnection = serverApi.getConnectionOf(listener);
            VoicechatConnection speakerConnection = serverApi.getConnectionOf(speaker);
            if (listenerConnection == null || speakerConnection == null) {
                return 1F;
            }
            ServerPlayer listenerPlayer = listenerConnection.getPlayer();
            ServerPlayer speakerPlayer = speakerConnection.getPlayer();
            if (listenerPlayer == null || speakerPlayer == null) {
                return 1F;
            }
            // The client attenuates from eye to eye (AudioChannel uses entity.getEyePosition() and
            // the camera position). The API's Position is feet, so use the raw entities where
            // possible; fall back to Position when the loader returns a wrapper.
            double distance = eyeDistance(listenerPlayer, speakerPlayer);
            distance = Math.min(distance, maxDistance);
            return (float) (1D - distance / maxDistance);
        } catch (RuntimeException e) {
            return 1F;
        }
    }

    private static double eyeDistance(ServerPlayer listener, ServerPlayer speaker) {
        try {
            Object a = listener.getPlayer();
            Object b = speaker.getPlayer();
            if (a instanceof net.minecraft.world.entity.Entity ea
                    && b instanceof net.minecraft.world.entity.Entity eb) {
                return ea.getEyePosition().distanceTo(eb.getEyePosition());
            }
        } catch (RuntimeException ignored) {
            // Fall back to the API position below.
        }
        Position pa = listener.getPosition();
        Position pb = speaker.getPosition();
        if (pa == null || pb == null) {
            return 0D;
        }
        double dx = pa.getX() - pb.getX();
        double dy = pa.getY() - pb.getY();
        double dz = pa.getZ() - pb.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private String nameOf(UUID player) {
        try {
            String name = nameResolver.apply(player);
            return name == null || name.isBlank() ? player.toString() : name;
        } catch (RuntimeException e) {
            return player.toString();
        }
    }
}
