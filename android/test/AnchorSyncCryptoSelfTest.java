package com.jaco.navionicsgpsbridge;

import java.util.Arrays;

/** Dependency-free JVM check for the Swift/Java handshake vectors and AES framing. */
public final class AnchorSyncCryptoSelfTest {
    public static void main(String[] arguments) throws Exception {
        byte[] pairingKey = repeated(0x41, AnchorSyncCrypto.PAIRING_KEY_BYTES);
        byte[] clientNonce = repeated(0x42, AnchorSyncCrypto.NONCE_BYTES);
        byte[] serverNonce = repeated(0x43, AnchorSyncCrypto.NONCE_BYTES);
        require("BUVzSnonV4yK7x8xd7kQtCfcFvp0IPlntX7Y5WlQKnY".equals(
                AnchorSyncCrypto.encode(AnchorSyncCrypto.clientProof(pairingKey, clientNonce))));
        require("d2bKxGA0iQKsccMawRbZuUJu1I5uzag3RjD2cuwK2lc".equals(
                AnchorSyncCrypto.encode(AnchorSyncCrypto.serverProof(
                        pairingKey, clientNonce, serverNonce))));
        byte[] sessionKey = AnchorSyncCrypto.sessionKey(
                pairingKey, clientNonce, serverNonce);
        require("MCbfxCzLsIiZ2bs4xaPJxgBtIkTyXmvISKx6PGtXg4k".equals(
                AnchorSyncCrypto.encode(sessionKey)));

        String plaintext = "{\"type\":\"state\",\"v\":1}";
        String frame = AnchorSyncCrypto.seal(sessionKey, 42L, plaintext, "s2c");
        AnchorSyncCrypto.OpenedFrame opened = AnchorSyncCrypto.open(
                sessionKey, frame, "s2c");
        require(opened.sequence == 42L);
        require(plaintext.equals(opened.plaintext));
        System.out.println("AnchorSyncCryptoSelfTest passed");
    }

    private static byte[] repeated(int value, int count) {
        byte[] result = new byte[count];
        Arrays.fill(result, (byte) value);
        return result;
    }

    private static void require(boolean condition) {
        if (!condition) throw new AssertionError("Anchor-sync crypto vector mismatch");
    }
}
