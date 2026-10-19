package com.retiredroca.svcweb.tls;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** PEM read/write for the private key and the certificate chain. */
public final class Pem {

    private static final String KEY_TYPE = "PRIVATE KEY";
    private static final String CERT_TYPE = "CERTIFICATE";

    private Pem() {}

    public static String encode(String type, byte[] der) {
        String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }

    public static byte[] decode(String pem, String type) {
        String begin = "-----BEGIN " + type + "-----";
        String end = "-----END " + type + "-----";
        int from = pem.indexOf(begin);
        if (from < 0) {
            throw new IllegalArgumentException("no " + type + " block in PEM");
        }
        int to = pem.indexOf(end, from);
        if (to < 0) {
            throw new IllegalArgumentException("unterminated " + type + " block in PEM");
        }
        String body = pem.substring(from + begin.length(), to);
        return Base64.getMimeDecoder().decode(body.getBytes(StandardCharsets.US_ASCII));
    }

    /** All certificate blocks, in file order (leaf first for a Let's Encrypt chain). */
    public static List<byte[]> decodeAll(String pem, String type) {
        List<byte[]> out = new ArrayList<>();
        String begin = "-----BEGIN " + type + "-----";
        String end = "-----END " + type + "-----";
        int offset = 0;
        while (true) {
            int from = pem.indexOf(begin, offset);
            if (from < 0) {
                return out;
            }
            int to = pem.indexOf(end, from);
            if (to < 0) {
                throw new IllegalArgumentException("unterminated " + type + " block in PEM");
            }
            String body = pem.substring(from + begin.length(), to);
            out.add(Base64.getMimeDecoder().decode(body.getBytes(StandardCharsets.US_ASCII)));
            offset = to + end.length();
        }
    }

    public static String encodePrivateKey(PrivateKey key) {
        return encode(KEY_TYPE, key.getEncoded());
    }

    public static PrivateKey readPrivateKey(String pem) throws Exception {
        byte[] der = decode(pem, KEY_TYPE);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        // The key type is not encoded in a form KeyFactory can infer, and operators routinely
        // supply RSA keys (the Let's Encrypt default is EC, but a proxy's key may be either).
        Exception failure = null;
        for (String algorithm : new String[] {"EC", "RSA"}) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(spec);
            } catch (Exception e) {
                failure = e;
            }
        }
        throw new IllegalArgumentException("unsupported private key type (expected EC or RSA)",
                failure);
    }

    public static String encodeCertificateChain(List<X509Certificate> chain) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (X509Certificate certificate : chain) {
            out.writeBytes(encode(CERT_TYPE, certificate.getEncoded()).getBytes(StandardCharsets.US_ASCII));
        }
        return out.toString(StandardCharsets.US_ASCII);
    }

    public static List<X509Certificate> readCertificateChain(String pem) throws Exception {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        List<X509Certificate> chain = new ArrayList<>();
        for (byte[] der : decodeAll(pem, CERT_TYPE)) {
            chain.add((X509Certificate) factory.generateCertificate(new java.io.ByteArrayInputStream(der)));
        }
        if (chain.isEmpty()) {
            throw new IllegalArgumentException("no certificates in PEM");
        }
        return chain;
    }
}
