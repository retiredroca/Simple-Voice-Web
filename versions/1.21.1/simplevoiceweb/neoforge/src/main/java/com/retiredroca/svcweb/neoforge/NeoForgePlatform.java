package com.retiredroca.svcweb.neoforge;

import com.retiredroca.svcweb.platform.SimpleVoiceWebPlatform;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;
import java.util.UUID;

/** The platform implementation for NeoForge. */
final class NeoForgePlatform implements SimpleVoiceWebPlatform {

    private volatile MinecraftServer server;

    void setServer(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public String loaderName() {
        return "NeoForge";
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return !net.neoforged.fml.loading.FMLEnvironment.production;
    }

    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
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
        ServerPlayer player = current.getPlayerList().getPlayer(playerUuid);
        return player == null ? null : player.getGameProfile().getName();
    }
}
