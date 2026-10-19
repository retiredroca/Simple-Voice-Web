package com.retiredroca.svcweb.tls;

import com.retiredroca.svcweb.SimpleVoiceWeb;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Publishes the {@code _acme-challenge} TXT record through DuckDNS's HTTP API.
 *
 * <p>DuckDNS keeps one TXT record per domain, shared by all its subdomains
 * (<a href="https://www.duckdns.org/spec.jsp">API specification</a>), so this cannot publish two
 * challenges at once. That is why {@link CertificateManager} orders one name at a time.
 */
public final class DuckDns {

    private static final String ENDPOINT = "https://www.duckdns.org/update";

    private final String subdomain;
    private final String token;
    private final HttpClient http;

    public DuckDns(String subdomain, String token) {
        this.subdomain = subdomain;
        this.token = token;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    public void publish(String value) throws Exception {
        update("txt=" + encode(value));
    }

    public void clear() throws Exception {
        update("clear=true");
    }

    private void update(String parameter) throws Exception {
        String url = ENDPOINT + "?domains=" + encode(subdomain) + "&token=" + encode(token)
                + "&" + parameter;
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        String body = response.body() == null ? "" : response.body().trim();
        if (response.statusCode() != 200 || !body.startsWith("OK")) {
            throw new IllegalStateException("DuckDNS rejected the TXT update (HTTP "
                    + response.statusCode() + ", response '" + body + "')");
        }
        SimpleVoiceWeb.LOGGER.info("DuckDNS TXT record updated for {}", subdomain);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
