package com.retiredroca.svcweb.fabric;

import com.retiredroca.svcweb.SimpleVoiceWeb;
import com.retiredroca.svcweb.VoiceWebService;
import com.retiredroca.svcweb.voice.AudioBridge;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;

public class SimpleVoiceWebFabric implements ModInitializer {

    private static final FabricPlatform PLATFORM = new FabricPlatform();
    private static volatile VoiceWebService service;

    @Override
    public void onInitialize() {
        SimpleVoiceWeb.setPlatform(PLATFORM);
        AudioBridge.instance().setNameResolver(PLATFORM::playerName);

        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            VoiceWebService current = service;
            if (current != null) {
                current.revoke(handler.player.getUUID());
            }
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
                VoiceWebCommand.register(dispatcher, () -> service));
    }

    private void onServerStarted(MinecraftServer server) {
        PLATFORM.setServer(server);
        try {
            VoiceWebService created = VoiceWebService.create(PLATFORM.configDir());
            service = created;
            created.start();
        } catch (Exception e) {
            SimpleVoiceWeb.LOGGER.error("Simple Voice Web failed to start", e);
        }
    }

    private void onServerStopping(MinecraftServer server) {
        VoiceWebService current = service;
        if (current != null) {
            current.stop();
            service = null;
        }
        PLATFORM.setServer(null);
    }
}
