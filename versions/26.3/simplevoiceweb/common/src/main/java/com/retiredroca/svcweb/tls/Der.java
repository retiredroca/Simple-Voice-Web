package com.retiredroca.svcweb.tls;

import java.io.ByteArrayOutputStream;

/**
 * Minimal DER encoder, just enough to build a PKCS#10 certification request.
 *
 * <p>Only writing is implemented: everything this mod reads (certificates, private keys, ACME
 * responses) is handled by {@link java.security.cert.CertificateFactory}, PKCS#8 and JSON. Adding a
 * reader without a caller would be dead code.
 */
public final class Der {

    private Der() {}

    public static byte[] tlv(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length + 6);
        out.write(tag);
        int len = content.length;
        if (len < 0x80) {
            out.write(len);
        } else {
            int bytes = 0;
            for (int v = len; v > 0; v >>>= 8) {
                bytes++;
            }
            out.write(0x80 | bytes);
            for (int i = bytes - 1; i >= 0; i--) {
                out.write((len >>> (8 * i)) & 0xFF);
            }
        }
        out.writeBytes(content);
        return out.toByteArray();
    }

    public static byte[] sequence(byte[]... parts) {
        return tlv(0x30, concat(parts));
    }

    public static byte[] set(byte[]... parts) {
        return tlv(0x31, concat(parts));
    }

    public static byte[] integer(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("negative DER integers are not used here");
        }
        int len = 1;
        while (len < 8 && (value >>> (8 * len)) != 0) {
            len++;
        }
        // A leading zero is required when the top bit would otherwise make it negative.
        if (len < 8 && (value & (1L << (8 * len - 1))) != 0) {
            len++;
        }
        byte[] bytes = new byte[len];
        for (int i = 0; i < len; i++) {
            bytes[len - 1 - i] = (byte) (value >>> (8 * i));
        }
        return tlv(0x02, bytes);
    }

    public static byte[] oid(String dotted) {
        String[] parts = dotted.split("\\.");
        if (parts.length < 2) {
            throw new IllegalArgumentException("not an OID: " + dotted);
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeBase128(body, Long.parseLong(parts[0]) * 40 + Long.parseLong(parts[1]));
        for (int i = 2; i < parts.length; i++) {
            writeBase128(body, Long.parseLong(parts[i]));
        }
        return tlv(0x06, body.toByteArray());
    }

    public static byte[] octetString(byte[] content) {
        return tlv(0x04, content);
    }

    public static byte[] utf8String(String value) {
        return tlv(0x0C, value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public static byte[] printableString(String value) {
        return tlv(0x13, value.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    public static byte[] bitString(byte[] content) {
        byte[] body = new byte[content.length + 1];
        body[0] = 0;
        System.arraycopy(content, 0, body, 1, content.length);
        return tlv(0x03, body);
    }

    /** A context-specific constructed element, e.g. {@code [0] IMPLICIT SET}. */
    public static byte[] contextConstructed(int tag, byte[] content) {
        return tlv(0xA0 | tag, content);
    }

    /** A context-specific primitive element, e.g. {@code [2] IMPLICIT IA5String}. */
    public static byte[] contextPrimitive(int tag, byte[] content) {
        return tlv(0x80 | tag, content);
    }

    private static void writeBase128(ByteArrayOutputStream out, long value) {
        if (value < 0) {
            throw new IllegalArgumentException("negative OID arc");
        }
        int groups = 1;
        for (long v = value; v >= 0x80; v >>>= 7) {
            groups++;
        }
        for (int i = groups - 1; i >= 0; i--) {
            int b = (int) ((value >>> (7 * i)) & 0x7F);
            out.write(i == 0 ? b : (b | 0x80));
        }
    }

    private static byte[] concat(byte[]... parts) {
        int len = 0;
        for (byte[] part : parts) {
            len += part.length;
        }
        byte[] out = new byte[len];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
    }
}
