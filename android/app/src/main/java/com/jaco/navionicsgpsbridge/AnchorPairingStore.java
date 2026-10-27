package com.jaco.navionicsgpsbridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Persists host and shore pairing secrets encrypted by the Android Keystore. */
final class AnchorPairingStore {
    private static final String PREFERENCES = "anchor_pairing";
    private static final String KEYSTORE_ALIAS =
            "com.jaco.navionicsgpsbridge.anchor-pairing-storage";
    private static final String HOST_ID = "host_id";
    private static final String HOST_SECRET = "host_secret";
    private static final String MONITOR_URI = "monitor_uri";

    private final SharedPreferences preferences;

    AnchorPairingStore(Context context) {
        preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    synchronized HostConfiguration loadHost() throws GeneralSecurityException {
        String deviceId = preferences.getString(HOST_ID, null);
        byte[] key = readSecret(HOST_SECRET);
        if (deviceId == null || key == null
                || key.length != AnchorSyncCrypto.PAIRING_KEY_BYTES) {
            return null;
        }
        return new HostConfiguration(deviceId, key);
    }

    synchronized HostConfiguration loadOrCreateHost() throws GeneralSecurityException {
        HostConfiguration existing = loadHost();
        if (existing != null) return existing;
        return createHost();
    }

    synchronized HostConfiguration rotateHost() throws GeneralSecurityException {
        return createHost();
    }

    synchronized MonitorConfiguration loadMonitor() throws GeneralSecurityException {
        String encryptedUri = readStringSecret(MONITOR_URI);
        return encryptedUri == null ? null : parsePairingUri(Uri.parse(encryptedUri));
    }

    synchronized MonitorConfiguration saveMonitor(Uri uri) throws GeneralSecurityException {
        MonitorConfiguration configuration = parsePairingUri(uri);
        writeStringSecret(MONITOR_URI, configuration.uri.toString());
        return configuration;
    }

    synchronized void clearMonitor() {
        preferences.edit()
                .remove(MONITOR_URI + "_ciphertext")
                .remove(MONITOR_URI + "_iv")
                .apply();
    }

    Uri pairingUri(HostConfiguration configuration, List<BridgeState.NetworkAddress> addresses) {
        List<String> hosts = new ArrayList<>();
        for (BridgeState.NetworkAddress address : addresses) {
            if (!hosts.contains(address.address)) hosts.add(address.address);
        }
        return new Uri.Builder()
                .scheme("navionicsgpsbridge")
                .authority("pair")
                .appendQueryParameter("v", String.valueOf(AnchorSyncProtocol.VERSION))
                .appendQueryParameter("id", configuration.deviceId)
                .appendQueryParameter("key", AnchorSyncCrypto.encode(configuration.pairingKey))
                .appendQueryParameter("hosts", String.join(",", hosts))
                .appendQueryParameter("port", String.valueOf(AnchorSyncProtocol.PORT))
                .build();
    }

    static MonitorConfiguration parsePairingUri(Uri uri) {
        if (!"navionicsgpsbridge".equals(uri.getScheme()) || !"pair".equals(uri.getHost())) {
            throw new IllegalArgumentException("This is not an anchor-monitor pairing link.");
        }
        int version;
        int port;
        try {
            version = Integer.parseInt(required(uri, "v"));
            port = Integer.parseInt(required(uri, "port"));
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("The pairing link contains an invalid number.");
        }
        if (version != AnchorSyncProtocol.VERSION) {
            throw new IllegalArgumentException("This pairing-link version is not supported.");
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("The pairing link contains an invalid port.");
        }
        byte[] pairingKey;
        try {
            pairingKey = AnchorSyncCrypto.decode(required(uri, "key"));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("The pairing link contains an invalid key.");
        }
        if (pairingKey.length != AnchorSyncCrypto.PAIRING_KEY_BYTES) {
            throw new IllegalArgumentException("The pairing link contains an invalid key.");
        }
        List<String> hosts = new ArrayList<>();
        for (String host : required(uri, "hosts").split(",")) {
            String trimmed = host.trim();
            if (!trimmed.isEmpty() && !hosts.contains(trimmed)) hosts.add(trimmed);
        }
        if (hosts.isEmpty()) {
            throw new IllegalArgumentException("The pairing link does not contain a boat address.");
        }
        String deviceId = required(uri, "id");
        return new MonitorConfiguration(
                deviceId,
                pairingKey,
                hosts,
                port,
                uri);
    }

    private HostConfiguration createHost() throws GeneralSecurityException {
        String deviceId = UUID.randomUUID().toString().toLowerCase(Locale.ROOT);
        byte[] key = AnchorSyncCrypto.randomBytes(AnchorSyncCrypto.PAIRING_KEY_BYTES);
        preferences.edit().putString(HOST_ID, deviceId).apply();
        writeSecret(HOST_SECRET, key);
        return new HostConfiguration(deviceId, key);
    }

    private void writeStringSecret(String name, String value) throws GeneralSecurityException {
        writeSecret(name, value.getBytes(StandardCharsets.UTF_8));
    }

    private String readStringSecret(String name) throws GeneralSecurityException {
        byte[] value = readSecret(name);
        return value == null ? null : new String(value, StandardCharsets.UTF_8);
    }

    private void writeSecret(String name, byte[] plaintext) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, storageKey());
        byte[] encrypted = cipher.doFinal(plaintext);
        preferences.edit()
                .putString(name + "_ciphertext", AnchorSyncCrypto.encode(encrypted))
                .putString(name + "_iv", AnchorSyncCrypto.encode(cipher.getIV()))
                .apply();
    }

    private byte[] readSecret(String name) throws GeneralSecurityException {
        String encryptedValue = preferences.getString(name + "_ciphertext", null);
        String ivValue = preferences.getString(name + "_iv", null);
        if (encryptedValue == null || ivValue == null) return null;
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, storageKey(),
                new GCMParameterSpec(128, AnchorSyncCrypto.decode(ivValue)));
        return cipher.doFinal(AnchorSyncCrypto.decode(encryptedValue));
    }

    private SecretKey storageKey() throws GeneralSecurityException {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        try {
            keyStore.load(null);
        } catch (java.io.IOException error) {
            throw new GeneralSecurityException(error);
        }
        SecretKey existing = (SecretKey) keyStore.getKey(KEYSTORE_ALIAS, null);
        if (existing != null) return existing;

        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }

    private static String required(Uri uri, String name) {
        String value = uri.getQueryParameter(name);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("The pairing link is missing " + name + ".");
        }
        return value;
    }

    static final class HostConfiguration {
        final String deviceId;
        final byte[] pairingKey;

        HostConfiguration(String deviceId, byte[] pairingKey) {
            this.deviceId = deviceId;
            this.pairingKey = Arrays.copyOf(pairingKey, pairingKey.length);
        }
    }

    static final class MonitorConfiguration {
        final String deviceId;
        final byte[] pairingKey;
        final List<String> hosts;
        final int port;
        final Uri uri;

        MonitorConfiguration(String deviceId, byte[] pairingKey, List<String> hosts,
                int port, Uri uri) {
            this.deviceId = deviceId;
            this.pairingKey = Arrays.copyOf(pairingKey, pairingKey.length);
            this.hosts = Collections.unmodifiableList(new ArrayList<>(hosts));
            this.port = port;
            this.uri = uri;
        }
    }
}
