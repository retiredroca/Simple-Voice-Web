package com.retiredroca.svcweb.voice;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;

/**
 * The Simple Voice Chat plugin entry point.
 *
 * <p>Simple Voice Chat instantiates this class itself - through the {@code voicechat} entrypoint on
 * Fabric, or by scanning for {@code @ForgeVoicechatPlugin} on NeoForge - so it must have a public
 * no-argument constructor. The bridge is therefore reached through {@link AudioBridge#instance()}
 * rather than injected.
 */
public class VoiceWebPlugin implements VoicechatPlugin {

    @Override
    public String getPluginId() {
        return "simplevoiceweb";
    }

    @Override
    public void initialize(VoicechatApi api) {
        // Events are the only thing this plugin needs; the API arrives in the start event.
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        AudioBridge.instance().registerEvents(registration);
    }
}
