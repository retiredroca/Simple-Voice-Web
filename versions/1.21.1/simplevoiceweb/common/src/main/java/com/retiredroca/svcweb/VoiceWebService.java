package com.retiredroca.svcweb;

import com.retiredroca.svcweb.tls.CertificateManager;
import com.retiredroca.svcweb.voice.AudioBridge;
import com.retiredroca.svcweb.web.LinkRegistry;
import com.retiredroca.svcweb.web.Session;
import com.retiredroca.svcweb.web.SessionRegistry;
import com.retiredroca.svcweb.web.WebServer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Owns the lifetime of the web voice service.
 *
 * <p>Started on server start and stopped on server stop. A failure to bind or to obtain a
 * certificate is logged and disables the feature; it never prevents the Minecraft server itself from
 * starting, because a voice addon must not take the game down with it.
 */
public final class VoiceWebService {

    private final Config config;
    private final Path configDir;
    private final AudioBridge audio = AudioBridge.instance();
    private final LinkRegistry links;
    private final SessionRegistry sessions;
    private final CertificateManager certificates;
    private WebServer server;
    private volatile String status = "not started";
    private volatile Thread starter;
    private volatile boolean stopping;

    public VoiceWebService(Config config, Path configDir) {
        this.config = config;
        this.configDir = configDir;
        this.links = new LinkRegistry(config.linkTtlSeconds * 1000L, config.maxLinksPerPlayer);
        this.sessions = new SessionRegistry(config.sessionTtlMinutes * 60_000L);
        this.certificates = new CertificateManager(config, configDir);
        this.audio.setOptions(config.audioProximity, config.audioGroups);
    }

    public AudioBridge audio() {
        return audio;
    }

    public Config config() {
        return config;
    }

    public String status() {
        return status;
    }

    public int port() {
        return server == null ? -1 : server.port();
    }

    public synchronized void start() {
        if (server != null && server.isRunning()) {
            return;
        }
        String error = config.validationError();
        if (error != null) {
            status = error;
            SimpleVoiceWeb.LOGGER.warn("Simple Voice Web is not starting: {}", error);
            return;
        }

        boolean tls = config.tlsMode != Config.TlsMode.OFF;
        // Obtaining a certificate can take minutes (DNS propagation), so it must not run on the
        // server thread: that would stall the tick loop while the server is starting. The listener
        // is bound once the material is ready; until then /svcweb link reports the status.
        stopping = false;
        status = "starting";
        Thread thread = new Thread(() -> prepareAndBind(tls), "SimpleVoiceWeb-Startup");
        thread.setDaemon(true);
        starter = thread;
        thread.start();
    }

    private void prepareAndBind(boolean tls) {
        try {
            if (tls) {
                if (!certificates.ensure()) {
                    status = "no TLS certificate: " + certificates.lastError();
                    SimpleVoiceWeb.LOGGER.warn("Simple Voice Web is not starting: {}", status);
                    return;
                }
                certificates.startRenewal();
            }
            if (stopping || Thread.currentThread().isInterrupted()) {
                return;
            }
            WebServer created = new WebServer(config, links,
                    (socket, in, out, requestHead, query) ->
                            new Session(links, sessions, audio)
                                    .handle(socket, in, out, requestHead, query));
            created.start(tls, certificates.privateKey(), certificates.chain());
            server = created;
            status = "listening on " + config.bindAddress + ":" + created.port()
                    + (tls ? " (https)" : " (http)");
            SimpleVoiceWeb.LOGGER.info("Simple Voice Web ready at {}", config.publicUrl);
        } catch (Exception e) {
            status = "could not start: " + e.getMessage();
            SimpleVoiceWeb.LOGGER.error("Simple Voice Web could not start: {}", e.getMessage());
        }
    }

    public synchronized void stop() {
        stopping = true;
        Thread pending = starter;
        starter = null;
        if (pending != null) {
            pending.interrupt();
        }
        if (server != null) {
            server.stop();
            server = null;
        }
        certificates.stop();
        sessions.clear();
        links.clear();
        status = "stopped";
    }

    /** Issues a keyed link for a player and returns the full URL. */
    public String issueLink(UUID player) {
        if (server == null || !server.isRunning()) {
            throw new IllegalStateException(status);
        }
        String key = links.issue(player);
        return config.publicUrl + "/join/" + key;
    }

    /** Joins the web player to a group by name; returns the outcome for the command. */
    public String joinGroup(UUID player, String name) {
        java.util.List<de.maxhenkel.voicechat.api.Group> groups = audio.groups();
        de.maxhenkel.voicechat.api.Group match = null;
        for (de.maxhenkel.voicechat.api.Group group : groups) {
            if (group.getName().equalsIgnoreCase(name)) {
                match = group;
                break;
            }
        }
        if (match == null) {
            return "No group named '" + name + "'. Use /svcweb groups to list them.";
        }
        if (match.hasPassword()) {
            return "Group '" + match.getName() + "' has a password; joining it from the browser is "
                    + "not supported.";
        }
        return audio.joinGroup(player, match.getId().toString())
                ? "Joined group '" + match.getName() + "'."
                : "Could not join the group.";
    }

    public String leaveGroup(UUID player) {
        return audio.leaveGroup(player) ? "Left your group." : "You are not in a group.";
    }

    public String listGroups() {
        java.util.List<de.maxhenkel.voicechat.api.Group> groups = audio.groups();
        if (groups.isEmpty()) {
            return "No voice chat groups are active.";
        }
        StringBuilder out = new StringBuilder("Active groups:");
        for (de.maxhenkel.voicechat.api.Group group : groups) {
            if (group.hasPassword()) {
                continue;
            }
            out.append("\n  ").append(group.getName());
        }
        return out.toString();
    }

    /** Invalidates a player's links and sessions, called when they disconnect. */
    public void revoke(UUID player) {
        links.revoke(player);
        sessions.revoke(player);
        sessions.closeAll(player, "You left the game. Rejoin and run /svcweb link again.");
        audio.revoke(player);
    }

    /** Creates the service from the server's config directory. */
    public static VoiceWebService create(Path configDir) throws IOException {
        Config config = Config.load(configDir);
        return new VoiceWebService(config, configDir);
    }
}
