package com.retiredroca.svcweb.tls;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

/**
 * Persists P-256 key pairs.
 *
 * <p>The public key is stored next to the private key rather than recomputed. The JDK has no API to
 * derive an EC public key from a private scalar, and the JWK thumbprint (RFC 7638) needs the public
 * point, so recomputing it on load is not an option.
 */
public final class KeyPairs {

    private KeyPairs() {}

    public static KeyPair generate() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    public static boolean exists(Path privateKeyFile, Path publicKeyFile) {
        return Files.exists(privateKeyFile) && Files.exists(publicKeyFile);
    }

    public static void write(Path privateKeyFile, Path publicKeyFile, KeyPair pair) throws Exception {
        Dns.writeAtomically(privateKeyFile, Pem.encodePrivateKey(pair.getPrivate()));
        Dns.writeAtomically(publicKeyFile, Pem.encode("PUBLIC KEY", pair.getPublic().getEncoded()));
    }

    public static KeyPair read(Path privateKeyFile, Path publicKeyFile) throws Exception {
        PrivateKey privateKey = KeyFactory.getInstance("EC").generatePrivate(
                new PKCS8EncodedKeySpec(Pem.decode(Files.readString(privateKeyFile), "PRIVATE KEY")));
        PublicKey publicKey = KeyFactory.getInstance("EC").generatePublic(
                new X509EncodedKeySpec(Pem.decode(Files.readString(publicKeyFile), "PUBLIC KEY")));
        return new KeyPair(publicKey, privateKey);
    }

    public static KeyPair loadOrCreate(Path privateKeyFile, Path publicKeyFile) throws Exception {
        if (exists(privateKeyFile, publicKeyFile)) {
            return read(privateKeyFile, publicKeyFile);
        }
        KeyPair pair = generate();
        write(privateKeyFile, publicKeyFile, pair);
        return pair;
    }
}
