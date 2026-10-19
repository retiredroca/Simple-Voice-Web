package com.retiredroca.svcweb.tls;

import com.retiredroca.svcweb.Config;
import com.retiredroca.svcweb.SimpleVoiceWeb;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Owns the TLS key material: issues it with ACME when needed, loads it, and renews it on a timer.
 *
 * <p>Renewal is a daily check rather than a timer computed from the expiry, so a clock change or a
 * restart after a long outage still converges. The check is idempotent: it renews only when the leaf
 * is inside the configured window.
 */
public final class CertificateManager {

    private final Config config;
    private final Path directory;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            runnable -> {
                Thread thread = new Thread(runnable, "SimpleVoiceWeb-ACME");
                thread.setDaemon(true);
                return thread;
            });

    private volatile List<X509Certificate> chain;
    private volatile PrivateKey privateKey;
    private volatile String lastError;

    public CertificateManager(Config config, Path directory) {
        this.config = config;
        this.directory = directory;
    }

    public Path certificatePath() {
        return config.certificatePath(directory);
    }

    public Path privateKeyPath() {
        return config.privateKeyPath(directory);
    }

    public List<X509Certificate> chain() {
        return chain;
    }

    public PrivateKey privateKey() {
        return privateKey;
    }

    public String lastError() {
        return lastError;
    }

    public boolean loaded() {
        return chain != null && privateKey != null;
    }

    /**
     * Loads an existing certificate, or issues one when there is none or it is near expiry.
     *
     * @return true when a usable certificate is loaded afterwards
     */
    public synchronized boolean ensure() {
        try {
            if (Files.exists(certificatePath()) && Files.exists(privateKeyPath())) {
                load();
                if (!Dns.needsRenewal(chain.get(0), config.acmeRenewDays)) {
                    SimpleVoiceWeb.LOGGER.info("TLS certificate valid until {}",
                            chain.get(0).getNotAfter().toInstant());
                    return true;
                }
                SimpleVoiceWeb.LOGGER.info("TLS certificate expires {}{}",
                        chain.get(0).getNotAfter().toInstant(),
                        config.tlsMode == Config.TlsMode.ACME
                                ? " - renewing"
                                : " - replace the configured certificate file");
            }
            if (config.tlsMode == Config.TlsMode.ACME) {
                issue();
                load();
            }
            return loaded();
        } catch (Exception e) {
            lastError = e.getMessage();
            SimpleVoiceWeb.LOGGER.error("Could not prepare a TLS certificate: {}", e.getMessage());
            return false;
        }
    }

    /** Starts the daily renewal check. Safe to call more than once. */
    public synchronized void startRenewal() {
        if (config.tlsMode != Config.TlsMode.ACME) {
            return;
        }
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                if (!loaded() || Dns.needsRenewal(chain.get(0), config.acmeRenewDays)) {
                    ensure();
                }
            } catch (Throwable t) {
                SimpleVoiceWeb.LOGGER.error("Certificate renewal check failed", t);
            }
        }, 1, 24, TimeUnit.HOURS);
    }

    public synchronized void stop() {
        scheduler.shutdownNow();
    }

    private void load() throws Exception {
        String certificatePem = Files.readString(certificatePath());
        String keyPem = Files.readString(privateKeyPath());
        List<X509Certificate> loadedChain = Pem.readCertificateChain(certificatePem);
        PrivateKey key = Pem.readPrivateKey(keyPem);
        loadedChain.get(0).checkValidity();
        chain = List.copyOf(loadedChain);
        privateKey = key;
        lastError = null;
        SimpleVoiceWeb.LOGGER.info("Loaded TLS certificate for {}", subject(loadedChain.get(0)));
    }

    private static String subject(X509Certificate certificate) {
        String name = certificate.getSubjectX500Principal().getName();
        int index = name.indexOf("CN=");
        return index < 0 ? name : name.substring(index + 3).split(",")[0];
    }

    private void issue() throws Exception {
        Path accountKeyFile = directory.resolve("acme-account-key.pem");
        Path accountPublicFile = directory.resolve("acme-account-public.pem");
        KeyPair accountKey = KeyPairs.loadOrCreate(accountKeyFile, accountPublicFile);

        String directoryUrl = config.acmeStaging
                ? "https://acme-staging-v02.api.letsencrypt.org/directory"
                : config.acmeDirectory;
        AcmeClient client = new AcmeClient(directoryUrl, accountKey);
        client.register(config.acmeEmail);
        SimpleVoiceWeb.LOGGER.info("Registered ACME account with {}", directoryUrl);

        DuckDns duckDns = new DuckDns(config.duckDnsSubdomain, config.duckDnsToken);
        AcmeClient.Order order = client.newOrder(config.acmeDomain);

        for (AcmeClient.Authorization authorization : order.authorizations()) {
            if ("valid".equals(authorization.status())) {
                continue;
            }
            if (authorization.challengeUrl() == null) {
                throw new IllegalStateException("ACME offered no DNS-01 challenge for "
                        + authorization.identifier());
            }
            String recordName = "_acme-challenge." + authorization.identifier();
            String value = client.dnsChallengeValue(authorization.token());
            SimpleVoiceWeb.LOGGER.info("Publishing ACME challenge for {}", authorization.identifier());
            duckDns.publish(value);
            try {
                if (!Dns.awaitTxt(recordName, config.dnsResolver, value, 180_000L)) {
                    throw new IllegalStateException("the ACME challenge TXT record for " + recordName
                            + " did not appear within 3 minutes");
                }
                client.acceptChallenge(authorization.challengeUrl());
                client.pollAuthorization(authorization.url(), recordName, value, 180_000L);
            } finally {
                duckDns.clear();
            }
        }

        // The CSR is signed with the account key, and the issued certificate carries that key. A
        // separate TLS key would need a second CSR signature and a second stored pair; reusing the
        // account key keeps one key for this deployment, which is what a single-name server wants.
        byte[] csr = Csr.create(accountKey, config.acmeDomain);
        byte[] certificatePem = client.finalize(order, csr);

        Dns.writeAtomically(privateKeyPath(), Pem.encodePrivateKey(accountKey.getPrivate()));
        Dns.writeAtomically(certificatePath(),
                new String(certificatePem, java.nio.charset.StandardCharsets.US_ASCII));
        SimpleVoiceWeb.LOGGER.info("Issued TLS certificate for {} at {}", config.acmeDomain,
                certificatePath());
    }
}
