package com.retiredroca.svcweb.neoforge;

import com.retiredroca.svcweb.voice.VoiceWebPlugin;

import de.maxhenkel.voicechat.api.ForgeVoicechatPlugin;

/**
 * NeoForge's voice chat plugin discovery scans for this annotation, so the class has to be a
 * top-level type in the mod's own package - not a nested one.
 */
@ForgeVoicechatPlugin
public final class NeoForgeVoiceWebPlugin extends VoiceWebPlugin {
}
