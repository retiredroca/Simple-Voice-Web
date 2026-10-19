package com.retiredroca.svcweb.tls;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Hashtable;
import java.util.List;
import javax.naming.Context;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;

/** DNS helpers used to wait for the ACME challenge record to propagate. */
public final class Dns {

    private Dns() {}

    /**
     * Reads the TXT values for a name.
     *
     * @param resolver a resolver address such as {@code 8.8.8.8}, or blank for the system resolver
     */
    public static List<String> txt(String name, String resolver) throws Exception {
        Hashtable<String, String> environment = new Hashtable<>();
        environment.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory");
        if (resolver != null && !resolver.isBlank()) {
            environment.put(Context.PROVIDER_URL, "dns://" + resolver.trim());
        }
        environment.put("com.sun.jndi.dns.timeout.initial", "4000");
        environment.put("com.sun.jndi.dns.timeout.retries", "2");
        DirContext context = new InitialDirContext(environment);
        try {
            Attributes attributes = context.getAttributes(name, new String[] {"TXT"});
            Attribute attribute = attributes.get("TXT");
            if (attribute == null) {
                return List.of();
            }
            java.util.List<String> out = new java.util.ArrayList<>();
            for (int i = 0; i < attribute.size(); i++) {
                String value = String.valueOf(attribute.get(i));
                out.add(unquote(value));
            }
            return out;
        } finally {
            context.close();
        }
    }

    /** TXT values arrive quoted and split; joins the fragments of one record. */
    private static String unquote(String value) {
        StringBuilder out = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    public static boolean hasTxt(String name, String resolver, String expected) throws Exception {
        for (String value : txt(name, resolver)) {
            if (value.equals(expected)) {
                return true;
            }
        }
        return false;
    }

    /** Polls until the record is visible, so ACME does not see a stale answer. */
    public static boolean awaitTxt(String name, String resolver, String expected, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (hasTxt(name, resolver, expected)) {
                    return true;
                }
            } catch (Exception e) {
                // A transient lookup failure is not a failed propagation.
            }
            Thread.sleep(3_000L);
        }
        return false;
    }

    public static void writeAtomically(Path file, String content) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, content, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** True when the leaf is inside the renewal window or already expired. */
    public static boolean needsRenewal(X509Certificate certificate, int renewDays) {
        Instant notAfter = certificate.getNotAfter().toInstant();
        return Instant.now().plusSeconds(renewDays * 86_400L).isAfter(notAfter);
    }
}
