package com.retiredroca.svcweb.tls;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * A small ACME v2 (RFC 8555) client, sufficient for a DNS-01 order with Let's Encrypt.
 *
 * <p>Only what the mod needs is implemented: account creation, order, authorization polling,
 * challenge acknowledgement, CSR finalisation and certificate download. There is no revocation,
 * no EAB, and no HTTP-01 - the mod cannot rely on port 80 being reachable.
 */
public final class AcmeClient {

    public record Order(String url, String finalizeUrl, List<Authorization> authorizations) {}

    public record Authorization(String url, String identifier, String status, String challengeType,
                                String challengeUrl, String token) {}

    private final String directoryUrl;
    private final HttpClient http;
    private final java.security.KeyPair accountKey;
    private String directory;
    private String nonce;
    private String accountUrl;
    private String kid;

    public AcmeClient(String directoryUrl, java.security.KeyPair accountKey) {
        this.directoryUrl = directoryUrl;
        this.accountKey = accountKey;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public void register(String email) throws Exception {
        directory = get(directoryUrl);
        takeNonce();
        String newAccount = string(directory, "newAccount");
        String contact = email == null || email.isEmpty()
                ? "[]"
                : "[\"mailto:" + Json.escape(email) + "\"]";
        String payload = "{\"termsOfServiceAgreed\":true,\"contact\":" + contact + "}";
        Response response = signedPostWithJwk(newAccount, payload);
        accountUrl = response.location;
        if (accountUrl == null || accountUrl.isEmpty()) {
            throw new AcmeException("ACME account response carried no Location header");
        }
        kid = accountUrl;
    }

    public Order newOrder(String domain) throws Exception {
        String payload = "{\"identifiers\":[{\"type\":\"dns\",\"value\":\"" + Json.escape(domain) + "\"}]}";
        Response response = signedPost(string(directory, "newOrder"), payload);
        String body = response.body;
        String orderUrl = response.location;
        List<Authorization> authorizations = new ArrayList<>();
        for (String authUrl : Json.stringArray(Json.object(body, "authorizations"))) {
            authorizations.add(readAuthorization(authUrl));
        }
        return new Order(orderUrl, Json.string(body, "finalize"), authorizations);
    }

    public Authorization readAuthorization(String url) throws Exception {
        Response response = signedPostAsGet(url);
        String body = response.body;
        String status = Json.string(body, "status");
        String identifier = Json.string(Json.object(body, "identifier"), "value");
        String challengeType = null;
        String challengeUrl = null;
        String token = null;
        for (String challenge : Json.array(Json.object(body, "challenges"))) {
            if ("dns-01".equals(Json.string(challenge, "type"))) {
                challengeType = "dns-01";
                challengeUrl = Json.string(challenge, "url");
                token = Json.string(challenge, "token");
                break;
            }
        }
        return new Authorization(url, identifier, status, challengeType, challengeUrl, token);
    }

    /** The DNS-01 TXT value: base64url(SHA-256(key authorization)) (RFC 8555 section 8.4). */
    public String dnsChallengeValue(String token) {
        String keyAuthorization = token + "." + Jws.thumbprint(publicKey());
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(keyAuthorization.getBytes(StandardCharsets.US_ASCII));
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }

    public void acceptChallenge(String challengeUrl) throws Exception {
        signedPost(challengeUrl, "{}");
    }

    public Authorization pollAuthorization(String url, String fqdn, String dnsValue, long timeoutMillis)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            Authorization authorization = readAuthorization(url);
            switch (authorization.status()) {
                case "valid":
                    return authorization;
                case "invalid":
                    throw new AcmeException("ACME authorization for " + authorization.identifier()
                            + " failed: " + authorization.status());
                default:
                    break;
            }
            Thread.sleep(3_000L);
        }
        throw new AcmeException("timed out waiting for the ACME authorization to validate");
    }

    public byte[] finalize(Order order, byte[] csrDer) throws Exception {
        String payload = "{\"csr\":\"" + java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(csrDer) + "\"}";
        signedPost(order.finalizeUrl(), payload);
        long deadline = System.currentTimeMillis() + 120_000L;
        while (System.currentTimeMillis() < deadline) {
            Response response = signedPostAsGet(order.url());
            String status = Json.string(response.body, "status");
            if ("valid".equals(status)) {
                return download(Json.string(response.body, "certificate"));
            }
            if ("invalid".equals(status)) {
                throw new AcmeException("ACME order became invalid while waiting for the certificate");
            }
            Thread.sleep(3_000L);
        }
        throw new AcmeException("timed out waiting for the ACME order to be issued");
    }

    public byte[] download(String certificateUrl) throws Exception {
        Response response = signedPostAsGet(certificateUrl);
        if (response.status != 200) {
            throw new AcmeException("certificate download failed with HTTP " + response.status);
        }
        return response.body.getBytes(StandardCharsets.US_ASCII);
    }

    private java.security.interfaces.ECPublicKey publicKey() {
        return (java.security.interfaces.ECPublicKey) accountKey.getPublic();
    }

    private Response signedPostWithJwk(String url, String payload) throws Exception {
        for (int attempt = 0; ; attempt++) {
            String body = Jws.flattenedWithJwk(
                    (java.security.interfaces.ECPrivateKey) accountKey.getPrivate(),
                    publicKey(), takeNonce(), url, payload);
            try {
                return post(url, body);
            } catch (AcmeException e) {
                if (attempt == 0 && retryableNonce(e)) {
                    continue;
                }
                throw e;
            }
        }
    }

    private Response signedPost(String url, String payload) throws Exception {
        for (int attempt = 0; ; attempt++) {
            String body = Jws.flattened(
                    (java.security.interfaces.ECPrivateKey) accountKey.getPrivate(),
                    kid, takeNonce(), url, payload);
            try {
                return post(url, body);
            } catch (AcmeException e) {
                if (attempt == 0 && retryableNonce(e)) {
                    continue;
                }
                throw e;
            }
        }
    }

    private Response signedPostAsGet(String url) throws Exception {
        for (int attempt = 0; ; attempt++) {
            String body = Jws.flattenedPostAsGet(
                    (java.security.interfaces.ECPrivateKey) accountKey.getPrivate(),
                    kid, takeNonce(), url);
            try {
                return post(url, body);
            } catch (AcmeException e) {
                if (attempt == 0 && retryableNonce(e)) {
                    continue;
                }
                throw e;
            }
        }
    }

    /** A rejected nonce is safe to retry once with the fresh nonce the error response carried. */
    private static boolean retryableNonce(AcmeException e) {
        String message = e.getMessage();
        return message != null && message.contains("badNonce");
    }

    private Response post(String url, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/jose+json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        captureNonce(response);
        if (response.statusCode() >= 400) {
            throw new AcmeException("ACME request to " + url + " failed with HTTP "
                    + response.statusCode() + ": " + response.body());
        }
        return new Response(response.statusCode(), response.body(),
                response.headers().firstValue("Location").orElse(null));
    }

    private void captureNonce(HttpResponse<String> response) {
        response.headers().firstValue("Replay-Nonce").ifPresent(value -> nonce = value);
    }

    private String takeNonce() throws Exception {
        if (nonce == null) {
            String newNonce = string(directory, "newNonce");
            if (newNonce == null) {
                throw new AcmeException("the ACME directory has no newNonce endpoint");
            }
            // RFC 8555 section 7.2: a HEAD (GET is also accepted) to newNonce returns the nonce.
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(newNonce)).method("HEAD",
                            HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            captureNonce(response);
        }
        if (nonce == null) {
            throw new AcmeException("ACME server did not provide a replay nonce");
        }
        String value = nonce;
        nonce = null;
        return value;
    }

    private String get(String url) throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new AcmeException("GET " + url + " failed with HTTP " + response.statusCode());
        }
        return response.body();
    }

    private static String string(String json, String key) {
        return Json.string(json, key);
    }

    public record Response(int status, String body, String location) {}

    public static final class AcmeException extends Exception {
        private static final long serialVersionUID = 1L;

        public AcmeException(String message) {
            super(message);
        }
    }
}
