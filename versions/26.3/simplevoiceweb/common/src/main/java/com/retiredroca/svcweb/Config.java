package com.retiredroca.svcweb;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Settings for the web voice bridge.
 *
 * <p>Stored as a properties file in the server's config directory. Unknown keys are preserved on
 * save so a newer build's settings are not silently dropped by an older one.
 */
public final class Config {

    /** How TLS is provided. */
    public enum TlsMode {
        /** Obtain and renew a certificate with ACME DNS-01 (DuckDNS). */
        ACME,
        /** Use certificate files the operator supplies. */
        MANUAL,
        /** Serve plain HTTP; a reverse proxy terminates TLS. */
        OFF
    }

    public boolean enabled = true;
    public String bindAddress = "0.0.0.0";
    public int port = 8080;

    /**
     * The address players reach this server on, used to build link URLs. Must be the external
     * scheme and host, e.g. {@code https://voice.example.com} - not the bind address.
     */
    public String publicUrl = "";

    /** Where {@code /} redirects. The mod never serves anything at the root. */
    public String redirectUrl = "https://github.com/retiredroca/Simple-Voice-Web";

    public int linkTtlSeconds = 600;
    public int sessionTtlMinutes = 720;
    public int maxLinksPerPlayer = 3;

    public TlsMode tlsMode = TlsMode.ACME;
    public String tlsCertificateFile = "certs/fullchain.pem";
    public String tlsPrivateKeyFile = "certs/privkey.pem";

    public String acmeEmail = "";
    public String acmeDirectory = "https://acme-v02.api.letsencrypt.org/directory";
    public String acmeDomain = "";
    public int acmeRenewDays = 30;
    /** Optional: use the Let's Encrypt staging directory while testing. */
    public boolean acmeStaging = false;

    public String duckDnsSubdomain = "";
    public String duckDnsToken = "";

    /** Resolver for DNS propagation checks; blank uses the system resolver. */
    public String dnsResolver = "";

    public boolean audioProximity = true;
    public boolean audioGroups = true;
    public boolean commandRequiresOp = false;

    private final Path file;
    private final Properties raw = new Properties();

    private Config(Path file) {
        this.file = file;
    }

    public static Config load(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path file = directory.resolve("simplevoiceweb.properties");
        Config config = new Config(file);
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                config.raw.load(in);
            }
        }
        config.read();
        config.save();
        return config;
    }

    private void read() {
        enabled = bool("enabled", enabled);
        bindAddress = string("bind_address", bindAddress);
        port = integer("port", port);
        publicUrl = string("public_url", publicUrl).trim();
        while (publicUrl.endsWith("/")) {
            publicUrl = publicUrl.substring(0, publicUrl.length() - 1);
        }
        redirectUrl = string("redirect_url", redirectUrl);
        linkTtlSeconds = integer("link_ttl_seconds", linkTtlSeconds);
        sessionTtlMinutes = integer("session_ttl_minutes", sessionTtlMinutes);
        maxLinksPerPlayer = integer("max_links_per_player", maxLinksPerPlayer);
        tlsMode = tlsMode("tls_mode", tlsMode);
        tlsCertificateFile = string("tls_certificate_file", tlsCertificateFile);
        tlsPrivateKeyFile = string("tls_private_key_file", tlsPrivateKeyFile);
        acmeEmail = string("acme_email", acmeEmail);
        acmeDirectory = string("acme_directory", acmeDirectory);
        acmeDomain = string("acme_domain", acmeDomain);
        acmeRenewDays = integer("acme_renew_days", acmeRenewDays);
        acmeStaging = bool("acme_staging", acmeStaging);
        duckDnsSubdomain = string("duckdns_subdomain", duckDnsSubdomain);
        duckDnsToken = string("duckdns_token", duckDnsToken);
        dnsResolver = string("dns_resolver", dnsResolver);
        audioProximity = bool("audio_proximity", audioProximity);
        audioGroups = bool("audio_groups", audioGroups);
        commandRequiresOp = bool("command_requires_op", commandRequiresOp);
    }

    private void save() throws IOException {
        raw.setProperty("enabled", Boolean.toString(enabled));
        raw.setProperty("bind_address", bindAddress);
        raw.setProperty("port", Integer.toString(port));
        raw.setProperty("public_url", publicUrl);
        raw.setProperty("redirect_url", redirectUrl);
        raw.setProperty("link_ttl_seconds", Integer.toString(linkTtlSeconds));
        raw.setProperty("session_ttl_minutes", Integer.toString(sessionTtlMinutes));
        raw.setProperty("max_links_per_player", Integer.toString(maxLinksPerPlayer));
        raw.setProperty("tls_mode", tlsMode.name().toLowerCase(java.util.Locale.ROOT));
        raw.setProperty("tls_certificate_file", tlsCertificateFile);
        raw.setProperty("tls_private_key_file", tlsPrivateKeyFile);
        raw.setProperty("acme_email", acmeEmail);
        raw.setProperty("acme_directory", acmeDirectory);
        raw.setProperty("acme_domain", acmeDomain);
        raw.setProperty("acme_renew_days", Integer.toString(acmeRenewDays));
        raw.setProperty("acme_staging", Boolean.toString(acmeStaging));
        raw.setProperty("duckdns_subdomain", duckDnsSubdomain);
        raw.setProperty("duckdns_token", duckDnsToken);
        raw.setProperty("dns_resolver", dnsResolver);
        raw.setProperty("audio_proximity", Boolean.toString(audioProximity));
        raw.setProperty("audio_groups", Boolean.toString(audioGroups));
        raw.setProperty("command_requires_op", Boolean.toString(commandRequiresOp));
        try (OutputStream out = Files.newOutputStream(file)) {
            raw.store(out, "Simple Voice Web - https://github.com/retiredroca/Simple-Voice-Web");
        }
    }

    /** Resolves a configured path against the config directory unless it is already absolute. */
    public Path resolve(Path directory, String value) {
        Path path = Path.of(value);
        return path.isAbsolute() ? path : directory.resolve(path);
    }

    public Path certificatePath(Path directory) {
        return resolve(directory, tlsCertificateFile);
    }

    public Path privateKeyPath(Path directory) {
        return resolve(directory, tlsPrivateKeyFile);
    }

    /** True when this configuration can actually serve links to players. */
    public String validationError() {
        if (!enabled) {
            return "Simple Voice Web is disabled in simplevoiceweb.properties";
        }
        if (publicUrl.isEmpty()) {
            return "public_url is not set in simplevoiceweb.properties";
        }
        if (!publicUrl.startsWith("https://") && tlsMode != TlsMode.OFF) {
            return "public_url must start with https:// unless tls_mode=off";
        }
        if (tlsMode == TlsMode.ACME) {
            if (acmeEmail.isEmpty()) {
                return "acme_email is not set";
            }
            if (acmeDomain.isEmpty()) {
                return "acme_domain is not set";
            }
            if (duckDnsSubdomain.isEmpty() || duckDnsToken.isEmpty()) {
                return "duckdns_subdomain and duckdns_token are required for tls_mode=acme "
                        + "(use tls_mode=off when a reverse proxy terminates TLS)";
            }
        }
        if (tlsMode == TlsMode.MANUAL) {
            Path cert = Path.of(tlsCertificateFile);
            Path key = Path.of(tlsPrivateKeyFile);
            if (cert.toString().isEmpty() || key.toString().isEmpty()) {
                return "tls_certificate_file and tls_private_key_file are required for tls_mode=manual";
            }
        }
        return null;
    }

    private String string(String key, String fallback) {
        return raw.getProperty(key, fallback);
    }

    private int integer(String key, int fallback) {
        try {
            return Integer.parseInt(raw.getProperty(key, Integer.toString(fallback)).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private boolean bool(String key, boolean fallback) {
        return Boolean.parseBoolean(raw.getProperty(key, Boolean.toString(fallback)).trim());
    }

    private TlsMode tlsMode(String key, TlsMode fallback) {
        String value = raw.getProperty(key, fallback.name()).trim().toUpperCase(java.util.Locale.ROOT);
        try {
            return TlsMode.valueOf(value);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
