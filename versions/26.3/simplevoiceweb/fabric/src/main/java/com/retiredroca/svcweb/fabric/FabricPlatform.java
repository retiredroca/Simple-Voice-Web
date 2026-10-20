package com.retiredroca.svcweb.fabric;

import com.retiredroca.svcweb.platform.SimpleVoiceWebPlatform;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Path;
import java.util.UUID;

/** The platform implementation for Fabric. */
final class FabricPlatform implements SimpleVoiceWebPlatform {

    private volatile MinecraftServer server;

    void setServer(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public String loaderName() {
        return "Fabric";
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }

    @Override
    public Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public void execute(Runnable task) {
        MinecraftServer current = server;
        if (current == null) {
            throw new IllegalStateException("the server is not running");
        }
        current.execute(task);
    }

    @Override
    public boolean isServerThread() {
        MinecraftServer current = server;
        return current != null && current.isSameThread();
    }

    @Override
    public String playerName(UUID playerUuid) {
        MinecraftServer current = server;
        if (current == null) {
            return null;
        }
        net.minecraft.server.level.ServerPlayer player =
                current.getPlayerList().getPlayer(playerUuid);
        return player == null ? null : player.getGameProfile().name();
    }
}
