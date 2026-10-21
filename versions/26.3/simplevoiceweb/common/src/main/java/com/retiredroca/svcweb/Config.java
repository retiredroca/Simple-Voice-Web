package com.retiredroca.svcweb;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * Settings for the web voice bridge.
 *
 * <p>Stored as a properties file in the server's config directory. The file is written only when
 * it is created or when {@link #CONFIG_VERSION} increases; an existing file is migrated once,
 * renamed to {@code simplevoiceweb.properties.bak}, and replaced with the formatted current
 * layout. Unknown keys are preserved.
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

    private static final int CONFIG_VERSION = 2;
    private static final String CONFIG_VERSION_KEY = "config_version";
    private static final String FILE_NAME = "simplevoiceweb.properties";
    private static final Set<String> KNOWN_KEYS = Set.of(
            CONFIG_VERSION_KEY, "enabled", "bind_address", "port", "public_url", "redirect_url",
            "link_ttl_seconds", "session_ttl_minutes", "max_links_per_player",
            "command_requires_op", "tls_mode", "tls_certificate_file", "tls_private_key_file",
            "acme_email", "acme_directory", "acme_domain", "acme_renew_days", "acme_staging",
            "duckdns_subdomain", "duckdns_token", "dns_resolver", "audio_proximity",
            "audio_groups");

    public boolean enabled = true;
    public String bindAddress = "127.0.0.1";
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

    public TlsMode tlsMode = TlsMode.OFF;
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
        Path file = directory.resolve(FILE_NAME);
        Config config = new Config(file);
        boolean existed = Files.exists(file);
        if (existed) {
            try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                config.raw.load(in);
            }
        }
        int version = config.configVersion();
        config.read();
        if (!existed || version < CONFIG_VERSION) {
            config.migrate(version);
            config.read();
            config.write(existed);
        }
        return config;
    }

    /**
     * Applies version-to-version config changes in memory. Values already in the file always win;
     * this only adds new keys, renames old ones and removes retired keys. Every future layout or
     * schema change must bump {@link #CONFIG_VERSION} and add a step here.
     */
    private void migrate(int from) {
        if (from < 2) {
            // Version 1 had no config_version or sections; values are preserved by the rewrite.
        }
        raw.setProperty(CONFIG_VERSION_KEY, Integer.toString(CONFIG_VERSION));
    }

    private int configVersion() {
        return integer(CONFIG_VERSION_KEY, 0);
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

    private void write(boolean backup) {
        Path temp = null;
        try {
            temp = Files.createTempFile(file.getParent(), "simplevoiceweb", ".tmp");
            Files.writeString(temp, render(), StandardCharsets.UTF_8);
            if (backup && Files.exists(file)) {
                Path backupFile = file.resolveSibling(file.getFileName() + ".bak");
                Files.copy(file, backupFile, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException | FileAlreadyExistsException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            SimpleVoiceWeb.LOGGER.error("Could not write {}; using the existing settings",
                    file, e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // Best effort; the original config is still in place.
                }
            }
        }
    }

    private String render() {
        StringBuilder out = new StringBuilder();
        out.append("# ============================================================\n");
        out.append("# Simple Voice Web\n");
        out.append("# https://github.com/retiredroca/Simple-Voice-Web\n");
        out.append("# ============================================================\n");
        out.append('\n');
        line(out, CONFIG_VERSION_KEY, Integer.toString(CONFIG_VERSION));
        out.append('\n');
        out.append("# Choose ONE TLS mode:\n");
        out.append("#   off    = reverse proxy (NPM/Caddy/nginx) terminates HTTPS/WebSockets\n");
        out.append("#   acme   = built-in Let's Encrypt DNS-01 + DuckDNS\n");
        out.append("#   manual = certificate and private key files you provide\n");
        line(out, "tls_mode", tlsMode.name().toLowerCase(Locale.ROOT));
        out.append('\n');
        out.append("# ------------------------------------------------------------\n");
        out.append("# Web listener and links\n");
        out.append("# ------------------------------------------------------------\n");
        line(out, "enabled", Boolean.toString(enabled));
        line(out, "bind_address", bindAddress);
        line(out, "port", Integer.toString(port));
        line(out, "public_url", publicUrl);
        line(out, "redirect_url", redirectUrl);
        out.append('\n');
        line(out, "link_ttl_seconds", Integer.toString(linkTtlSeconds));
        line(out, "session_ttl_minutes", Integer.toString(sessionTtlMinutes));
        line(out, "max_links_per_player", Integer.toString(maxLinksPerPlayer));
        line(out, "command_requires_op", Boolean.toString(commandRequiresOp));
        out.append('\n');
        out.append("# ------------------------------------------------------------\n");
        out.append("# Reverse proxy mode (tls_mode=off)\n");
        out.append("# ------------------------------------------------------------\n");
        out.append("# Bind to localhost or a private interface; forward HTTPS and WebSockets\n");
        out.append("# here. Enable WebSockets Support in the proxy. No certificate settings\n");
        out.append("# are required in this mode.\n");
        out.append('\n');
        out.append("# ------------------------------------------------------------\n");
        out.append("# Built-in ACME + DuckDNS (tls_mode=acme)\n");
        out.append("# ------------------------------------------------------------\n");
        out.append("# Ignored when tls_mode is off or manual.\n");
        line(out, "acme_email", acmeEmail);
        line(out, "acme_domain", acmeDomain);
        line(out, "duckdns_subdomain", duckDnsSubdomain);
        line(out, "duckdns_token", duckDnsToken);
        line(out, "acme_directory", acmeDirectory);
        line(out, "acme_renew_days", Integer.toString(acmeRenewDays));
        line(out, "acme_staging", Boolean.toString(acmeStaging));
        line(out, "dns_resolver", dnsResolver);
        out.append('\n');
        out.append("# ------------------------------------------------------------\n");
        out.append("# Manual certificates (tls_mode=manual)\n");
        out.append("# ------------------------------------------------------------\n");
        out.append("# Ignored when tls_mode is off or acme.\n");
        line(out, "tls_certificate_file", tlsCertificateFile);
        line(out, "tls_private_key_file", tlsPrivateKeyFile);
        out.append('\n');
        out.append("# ------------------------------------------------------------\n");
        out.append("# Audio\n");
        out.append("# ------------------------------------------------------------\n");
        line(out, "audio_proximity", Boolean.toString(audioProximity));
        line(out, "audio_groups", Boolean.toString(audioGroups));
        out.append('\n');

        java.util.List<String> extra = raw.stringPropertyNames().stream()
                .filter(key -> !KNOWN_KEYS.contains(key))
                .sorted()
                .toList();
        if (!extra.isEmpty()) {
            out.append("# ------------------------------------------------------------\n");
            out.append("# Extra settings (preserved)\n");
            out.append("# ------------------------------------------------------------\n");
            for (String key : extra) {
                line(out, key, raw.getProperty(key, ""));
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static void line(StringBuilder out, String key, String value) {
        out.append(key).append('=').append(escapeValue(value)).append('\n');
    }

    /** Escapes a property value so Properties.load agrees with what is written here. */
    private static String escapeValue(String value) {
        String text = value == null ? "" : value;
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\f' -> out.append("\\f");
                default -> {
                    if (i == 0 && (c == ' ' || c == '#' || c == '!')) {
                        out.append('\\');
                    }
                    out.append(c);
                }
            }
        }
        return out.toString();
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
        String value = raw.getProperty(key, fallback.name()).trim().toUpperCase(Locale.ROOT);
        try {
            return TlsMode.valueOf(value);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
