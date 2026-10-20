package com.retiredroca.svcweb.platform;

import java.nio.file.Path;
import java.util.UUID;

/** Loader-specific services used by the shared (common) code. */
public interface SimpleVoiceWebPlatform {
    String loaderName();

    boolean isDevelopmentEnvironment();

    /** The server's {@code config} directory, where this mod keeps its settings and certificates. */
    Path configDir();

    /**
     * Runs a task on the Minecraft server thread.
     *
     * <p>The voice chat API is not thread-safe: its player lists and level lookups are read from the
     * server thread. Every call into it from a web thread goes through here.
     */
    void execute(Runnable task);

    /**
     * True when the calling thread is the Minecraft server thread.
     *
     * <p>Voice chat events are already delivered on that thread. Without this check, marshalling a
     * call from an event handler would queue work onto the thread that is currently blocked waiting
     * for it, which deadlocks.
     */
    boolean isServerThread();

    /** The current display name of an online player, or {@code null} if they are not online. */
    String playerName(UUID playerUuid);
}
