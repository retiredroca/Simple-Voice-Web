package com.retiredroca.svcweb;

import com.retiredroca.svcweb.platform.SimpleVoiceWebPlatform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Loader-neutral entry point. Each loader's initializer installs a {@link SimpleVoiceWebPlatform};
 * the shared code then uses {@link #platform()} for loader-specific operations.
 */
public final class SimpleVoiceWeb {
    public static final String MOD_ID = "simplevoiceweb";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final long SERVER_CALL_TIMEOUT_SECONDS = 5L;

    private static SimpleVoiceWebPlatform platform;

    private SimpleVoiceWeb() {}

    public static void setPlatform(SimpleVoiceWebPlatform value) {
        platform = value;
    }

    public static SimpleVoiceWebPlatform platform() {
        if (platform == null) {
            throw new IllegalStateException("SimpleVoiceWeb platform not set - was a loader entrypoint loaded?");
        }
        return platform;
    }

    /**
     * Runs a task on the Minecraft server thread and waits for its result.
     *
     * <p>The voice chat API reads world and player state that is only valid on the server thread.
     * Web connections are handled on their own threads, so every call into it is marshalled here.
     * A timeout is a hard failure rather than a silent {@code null}: a web request that cannot reach
     * the server thread must not appear to have succeeded.
     */
    public static <T> T onServer(Supplier<T> task) {
        SimpleVoiceWebPlatform current = platform();
        if (current.isServerThread()) {
            return task.get();
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        current.execute(() -> {
            try {
                future.complete(task.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            return future.get(SERVER_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new IllegalStateException("the server thread did not respond within "
                    + SERVER_CALL_TIMEOUT_SECONDS + "s", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the server thread", e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(cause);
        }
    }

    public static void runOnServer(Runnable task) {
        onServer(() -> {
            task.run();
            return Boolean.TRUE;
        });
    }
}
