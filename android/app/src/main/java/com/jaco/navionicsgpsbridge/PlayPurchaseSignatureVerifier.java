package com.jaco.navionicsgpsbridge;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public final class PlayPurchaseSignatureVerifier {
    private PlayPurchaseSignatureVerifier() {}

    public static boolean verify(
            String base64PublicKey,
            String signedData,
            String base64Signature
    ) {
        if (base64PublicKey == null || base64PublicKey.isEmpty()
                || signedData == null || signedData.isEmpty()
                || base64Signature == null || base64Signature.isEmpty()) {
            return false;
        }
        try {
            byte[] decodedKey = Base64.getDecoder().decode(base64PublicKey);
            PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(
                    new X509EncodedKeySpec(decodedKey));
            Signature signature = Signature.getInstance("SHA1withRSA");
            signature.initVerify(publicKey);
            signature.update(signedData.getBytes(StandardCharsets.UTF_8));
            return signature.verify(Base64.getDecoder().decode(base64Signature));
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            return false;
        }
    }
}
