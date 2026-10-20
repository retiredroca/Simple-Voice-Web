package com.retiredroca.svcweb.neoforge;

import com.retiredroca.svcweb.SimpleVoiceWeb;
import com.retiredroca.svcweb.VoiceWebService;
import com.retiredroca.svcweb.voice.AudioBridge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

@Mod(SimpleVoiceWeb.MOD_ID)
public class SimpleVoiceWebNeoForge {

    private static final NeoForgePlatform PLATFORM = new NeoForgePlatform();
    private static volatile VoiceWebService service;

    public SimpleVoiceWebNeoForge(IEventBus modBus) {
        SimpleVoiceWeb.setPlatform(PLATFORM);
        AudioBridge.instance().setNameResolver(PLATFORM::playerName);

        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLoggedOut);
    }

    private void onServerStarted(ServerStartedEvent event) {
        PLATFORM.setServer(event.getServer());
        try {
            VoiceWebService created = VoiceWebService.create(PLATFORM.configDir());
            service = created;
            created.start();
        } catch (Exception e) {
            SimpleVoiceWeb.LOGGER.error("Simple Voice Web failed to start", e);
        }
    }

    private void onServerStopping(ServerStoppingEvent event) {
        VoiceWebService current = service;
        if (current != null) {
            current.stop();
            service = null;
        }
        PLATFORM.setServer(null);
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        VoiceWebCommand.register(event.getDispatcher(), () -> service);
    }

    private void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        VoiceWebService current = service;
        if (current != null) {
            current.revoke(event.getEntity().getUUID());
        }
    }
}
