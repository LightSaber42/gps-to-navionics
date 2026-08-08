package com.jaco.navionicsgpsbridge;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Cryptographic framing shared with the iOS anchor-sync implementation. */
final class AnchorSyncCrypto {
    static final int PAIRING_KEY_BYTES = 32;
    static final int NONCE_BYTES = 16;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final byte[] CLIENT_PROOF_LABEL = bytes("anchor-sync-client-v1");
    private static final byte[] SERVER_PROOF_LABEL = bytes("anchor-sync-server-v1");
    private static final byte[] SESSION_KEY_LABEL = bytes("anchor-sync-key-v1");

    private AnchorSyncCrypto() {
    }

    static byte[] randomBytes(int count) {
        byte[] result = new byte[count];
        RANDOM.nextBytes(result);
        return result;
    }

    static byte[] clientProof(byte[] pairingKey, byte[] clientNonce)
            throws GeneralSecurityException {
        return hmac(pairingKey, concatenate(CLIENT_PROOF_LABEL, clientNonce));
    }

    static byte[] serverProof(byte[] pairingKey, byte[] clientNonce, byte[] serverNonce)
            throws GeneralSecurityException {
        return hmac(pairingKey, concatenate(SERVER_PROOF_LABEL, clientNonce, serverNonce));
    }

    static byte[] sessionKey(byte[] pairingKey, byte[] clientNonce, byte[] serverNonce)
            throws GeneralSecurityException {
        return hmac(pairingKey, concatenate(SESSION_KEY_LABEL, clientNonce, serverNonce));
    }

    static String seal(byte[] sessionKey, long sequence, String plaintext, String direction)
            throws GeneralSecurityException {
        byte[] nonce = randomBytes(12);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(sessionKey, "AES"),
                new GCMParameterSpec(128, nonce));
        cipher.updateAAD(associatedData(direction, sequence));
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        ByteBuffer frame = ByteBuffer.allocate(Long.BYTES + nonce.length + ciphertext.length);
        frame.putLong(sequence);
        frame.put(nonce);
        frame.put(ciphertext);
        return Base64.getEncoder().withoutPadding().encodeToString(frame.array());
    }

    static OpenedFrame open(byte[] sessionKey, String encodedFrame, String direction)
            throws GeneralSecurityException {
        byte[] frame = Base64.getDecoder().decode(encodedFrame);
        if (frame.length < Long.BYTES + 12 + 16) {
            throw new GeneralSecurityException("Encrypted anchor-sync frame is too short");
        }
        ByteBuffer buffer = ByteBuffer.wrap(frame);
        long sequence = buffer.getLong();
        byte[] nonce = new byte[12];
        buffer.get(nonce);
        byte[] ciphertext = new byte[buffer.remaining()];
        buffer.get(ciphertext);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(sessionKey, "AES"),
                new GCMParameterSpec(128, nonce));
        cipher.updateAAD(associatedData(direction, sequence));
        byte[] plaintext = cipher.doFinal(ciphertext);
        return new OpenedFrame(sequence, new String(plaintext, StandardCharsets.UTF_8));
    }

    static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    static byte[] decode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }

    static boolean constantTimeEquals(byte[] expected, byte[] actual) {
        return MessageDigest.isEqual(expected, actual);
    }

    private static byte[] associatedData(String direction, long sequence) {
        byte[] label = bytes("anchor-sync-" + direction + "-v1");
        return concatenate(label, ByteBuffer.allocate(Long.BYTES).putLong(sequence).array());
    }

    private static byte[] hmac(byte[] key, byte[] message) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(message);
    }

    private static byte[] concatenate(byte[]... values) {
        int count = 0;
        for (byte[] value : values) count += value.length;
        byte[] result = new byte[count];
        int offset = 0;
        for (byte[] value : values) {
            System.arraycopy(value, 0, result, offset, value.length);
            offset += value.length;
        }
        return result;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    static final class OpenedFrame {
        final long sequence;
        final String plaintext;

        OpenedFrame(long sequence, String plaintext) {
            this.sequence = sequence;
            this.plaintext = plaintext;
        }
    }
}
