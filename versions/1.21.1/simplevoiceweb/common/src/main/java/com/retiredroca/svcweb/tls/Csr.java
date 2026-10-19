package com.retiredroca.svcweb.tls;

import java.security.KeyPair;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Builds a PKCS#10 certification request for the account key.
 *
 * <p>ACME validates a DNS-01 challenge by the key authorization, which is the token plus the JWK
 * thumbprint (RFC 8555 section 8.1). The account key is the only key that exists before the first
 * certificate is issued, so the CSR is signed with it. The request carries the subject alternative
 * names and no subject, which is what Let's Encrypt expects.
 */
public final class Csr {

    private Csr() {}

    /**
     * @param keyPair    the account key pair (P-256)
     * @param commonName the DNS name to request
     * @return the DER-encoded PKCS#10 request
     */
    public static byte[] create(KeyPair keyPair, String commonName) {
        ECPublicKey publicKey = (ECPublicKey) keyPair.getPublic();
        ECPrivateKey privateKey = (ECPrivateKey) keyPair.getPrivate();

        byte[] subject = Der.sequence(
                Der.set(Der.sequence(Der.oid("2.5.4.3"), Der.utf8String(commonName))));

        byte[] subjectAltName = Der.sequence(
                Der.oid("2.5.29.17"),
                Der.octetString(Der.sequence(Der.contextPrimitive(2, dnsName(commonName)))));

        byte[] extensionRequest = Der.sequence(
                Der.oid("1.2.840.113549.1.9.14"),
                Der.set(subjectAltName));

        byte[] attributes = Der.contextConstructed(0, extensionRequest);

        byte[] info = Der.sequence(
                Der.integer(0),
                subject,
                Der.sequence(
                        Der.sequence(
                                Der.oid("1.2.840.10045.2.1"),
                                Der.oid("1.2.840.10045.3.1.7")),
                        Der.bitString(publicKeyBytes(publicKey))),
                attributes);

        byte[] signature = Jws.signDer(privateKey, info);

        return Der.sequence(
                info,
                Der.sequence(Der.oid("1.2.840.10045.4.3.2")),
                Der.bitString(signature));
    }

    public static String createPem(KeyPair keyPair, String commonName) {
        return Pem.encode("CERTIFICATE REQUEST", create(keyPair, commonName));
    }

    /** The uncompressed SEC1 point, 0x04 || X || Y, each coordinate 32 bytes. */
    public static byte[] publicKeyBytes(ECPublicKey key) {
        byte[] x = fixed(key.getW().getAffineX().toByteArray(), 32);
        byte[] y = fixed(key.getW().getAffineY().toByteArray(), 32);
        byte[] out = new byte[65];
        out[0] = 0x04;
        System.arraycopy(x, 0, out, 1, 32);
        System.arraycopy(y, 0, out, 33, 32);
        return out;
    }

    public static byte[] dnsName(String name) {
        return name.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    /** Left-pads or trims a BigInteger magnitude to exactly {@code size} bytes. */
    public static byte[] fixed(byte[] value, int size) {
        byte[] out = new byte[size];
        int start = 0;
        while (start < value.length - 1 && value[start] == 0) {
            start++;
        }
        int len = value.length - start;
        if (len > size) {
            throw new IllegalArgumentException("value wider than " + size + " bytes");
        }
        System.arraycopy(value, start, out, size - len, len);
        return out;
    }
}
