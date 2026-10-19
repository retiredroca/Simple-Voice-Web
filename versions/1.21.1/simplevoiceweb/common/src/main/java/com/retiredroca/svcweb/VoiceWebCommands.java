package com.retiredroca.svcweb;

import java.util.UUID;

/**
 * The text behind the {@code /svcweb} command.
 *
 * <p>Kept free of Minecraft types so both loaders share it; each loader registers the Brigadier
 * command and prints these lines. Every method is called on the server thread.
 */
public final class VoiceWebCommands {

    private final VoiceWebService service;

    public VoiceWebCommands(VoiceWebService service) {
        this.service = service;
    }

    /** Issues a link for a player. The caller shows the returned line. */
    public String link(UUID player) {
        try {
            String url = service.issueLink(player);
            return "Voice link (single use, expires in " + service.config().linkTtlSeconds
                    + "s): " + url;
        } catch (IllegalStateException e) {
            return "Simple Voice Web is not available: " + e.getMessage();
        }
    }

    public String status() {
        Config config = service.config();
        StringBuilder out = new StringBuilder();
        out.append("Simple Voice Web: ").append(service.status());
        out.append("\n  public_url: ").append(config.publicUrl.isEmpty() ? "(not set)" : config.publicUrl);
        out.append("\n  tls_mode: ").append(config.tlsMode.name().toLowerCase(java.util.Locale.ROOT));
        out.append("\n  listening: ").append(service.port() < 0 ? "no" : service.port());
        out.append("\n  voice chat attached: ").append(service.audio().available() ? "yes" : "no");
        if (config.tlsMode == Config.TlsMode.ACME) {
            out.append("\n  acme_domain: ").append(config.acmeDomain);
            out.append("\n  duckdns_subdomain: ").append(config.duckDnsSubdomain);
        }
        return out.toString();
    }

    /** Drops every link and session for a player. */
    public String revoke(UUID player) {
        service.revoke(player);
        return "Revoked voice links and sessions for " + player + ".";
    }
}
