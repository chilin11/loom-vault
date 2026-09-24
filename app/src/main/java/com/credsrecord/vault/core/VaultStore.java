package com.credsrecord.vault.core;

import android.content.Context;
import android.util.AtomicFile;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Iterator;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** Encrypted, passphrase-protected storage for a JSON vault. */
public final class VaultStore {
    public static final int ENVELOPE_VERSION = 1;
    public static final int KDF_ITERATIONS = 210000;
    public static final String KDF_NAME = "PBKDF2WithHmacSHA256";
    public static final int MAX_ENVELOPE_BYTES = 2 * 1024 * 1024;

    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int KEY_BITS = 256;
    private static final int GCM_TAG_BITS = 128;
    private static final String FILE_NAME = "vault.envelope";

    private final AtomicFile atomicFile;
    private final File baseFile;

    public VaultStore(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("Context must not be null.");
        }
        Context appContext = context.getApplicationContext();
        if (appContext == null) {
            appContext = context;
        }
        this.baseFile = new File(appContext.getFilesDir(), FILE_NAME);
        this.atomicFile = new AtomicFile(baseFile);
    }

    /** Decrypts the local vault. An incorrect passphrase raises a clear security exception. */
    public synchronized JSONObject load(String passphrase) throws Exception {
        return decryptEnvelope(passphrase, readEnvelope());
    }

    /** Encrypts and atomically saves the vault; neither plaintext nor passphrase is persisted. */
    public synchronized void save(String passphrase, JSONObject plaintext) throws Exception {
        writeEnvelope(encryptEnvelope(passphrase, plaintext));
    }

    /** Returns the validated encrypted envelope for remote synchronization or backup. */
    public synchronized String exportEnvelope() throws Exception {
        String envelope = readEnvelope();
        parseEnvelope(envelope);
        return envelope;
    }

    /** Validates and atomically installs an encrypted envelope without decrypting it. */
    public synchronized void importEnvelope(String envelope) throws Exception {
        parseEnvelope(envelope);
        writeEnvelope(envelope);
    }

    public synchronized boolean exists() {
        return baseFile.exists() || new File(baseFile.getPath() + ".bak").exists();
    }

    /** Removes the local encrypted vault and any AtomicFile backup. */
    public synchronized void remove() {
        atomicFile.delete();
    }

    /** Encrypts a JSON vault as the version-1 AES-256-GCM envelope used by local and remote storage. */
    public static String encryptEnvelope(String passphrase, JSONObject plaintext) throws Exception {
        requirePassphrase(passphrase);
        if (plaintext == null) {
            throw new IllegalArgumentException("Vault plaintext must not be null.");
        }

        byte[] salt = new byte[SALT_BYTES];
        byte[] iv = new byte[IV_BYTES];
        SecureRandom random = new SecureRandom();
        random.nextBytes(salt);
        random.nextBytes(iv);

        byte[] cleartext = plaintext.toString().getBytes(StandardCharsets.UTF_8);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] ciphertext = cipher.doFinal(cleartext);

        JSONObject envelope = new JSONObject();
        envelope.put("version", ENVELOPE_VERSION);
        envelope.put("kdf", KDF_NAME);
        envelope.put("iterations", KDF_ITERATIONS);
        envelope.put("salt", Base64.getEncoder().encodeToString(salt));
        envelope.put("iv", Base64.getEncoder().encodeToString(iv));
        envelope.put("ciphertext", Base64.getEncoder().encodeToString(ciphertext));

        String serialized = envelope.toString();
        ensureWithinLimit(serialized.getBytes(StandardCharsets.UTF_8).length);
        return serialized;
    }

    /** Decrypts and parses a validated version-1 envelope. */
    public static JSONObject decryptEnvelope(String passphrase, String envelope) throws Exception {
        requirePassphrase(passphrase);
        ParsedEnvelope parsed = parseEnvelope(envelope);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, parsed.salt),
                new GCMParameterSpec(GCM_TAG_BITS, parsed.iv));
        final byte[] cleartext;
        try {
            cleartext = cipher.doFinal(parsed.ciphertext);
        } catch (BadPaddingException e) {
            throw new GeneralSecurityException("Incorrect passphrase or vault data is corrupted.", e);
        }

        try {
            return new JSONObject(new String(cleartext, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new GeneralSecurityException("Decrypted vault is not valid JSON.", e);
        }
    }

    private String readEnvelope() throws IOException {
        try (FileInputStream input = atomicFile.openRead();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                ensureWithinLimit(total);
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private void writeEnvelope(String envelope) throws IOException {
        byte[] bytes = envelope.getBytes(StandardCharsets.UTF_8);
        ensureWithinLimit(bytes.length);
        FileOutputStream output = null;
        try {
            output = atomicFile.startWrite();
            output.write(bytes);
            output.flush();
            output.getFD().sync();
            atomicFile.finishWrite(output);
            output = null;
        } catch (IOException | RuntimeException e) {
            if (output != null) {
                atomicFile.failWrite(output);
            }
            throw e;
        }
    }

    private static ParsedEnvelope parseEnvelope(String serialized) throws Exception {
        if (serialized == null) {
            throw new IllegalArgumentException("Vault envelope must not be null.");
        }
        byte[] serializedBytes = serialized.getBytes(StandardCharsets.UTF_8);
        ensureWithinLimit(serializedBytes.length);

        final JSONObject json;
        try {
            json = new JSONObject(serialized);
        } catch (JSONException e) {
            throw new IllegalArgumentException("Vault envelope is not valid JSON.", e);
        }

        String[] expectedKeys = {"version", "kdf", "iterations", "salt", "iv", "ciphertext"};
        int keyCount = 0;
        for (Iterator<String> it = json.keys(); it.hasNext();) {
            String key = it.next();
            keyCount++;
            boolean known = false;
            for (String expected : expectedKeys) {
                if (expected.equals(key)) {
                    known = true;
                    break;
                }
            }
            if (!known) {
                throw new IllegalArgumentException("Vault envelope contains an unexpected field.");
            }
        }
        if (keyCount != expectedKeys.length) {
            throw new IllegalArgumentException("Vault envelope is missing required fields.");
        }

        Object version = json.opt("version");
        Object iterations = json.opt("iterations");
        if (!(version instanceof Number) || ((Number) version).doubleValue() != ENVELOPE_VERSION) {
            throw new IllegalArgumentException("Unsupported vault envelope version.");
        }
        if (!(iterations instanceof Number) || ((Number) iterations).doubleValue() != KDF_ITERATIONS) {
            throw new IllegalArgumentException("Unsupported vault envelope KDF iterations.");
        }
        if (!KDF_NAME.equals(json.optString("kdf", null))) {
            throw new IllegalArgumentException("Unsupported vault envelope KDF.");
        }

        String saltText = requireString(json, "salt");
        String ivText = requireString(json, "iv");
        String ciphertextText = requireString(json, "ciphertext");
        byte[] salt = decodeBase64(saltText, "salt");
        byte[] iv = decodeBase64(ivText, "iv");
        byte[] ciphertext = decodeBase64(ciphertextText, "ciphertext");
        if (salt.length != SALT_BYTES) {
            throw new IllegalArgumentException("Vault envelope salt has an invalid length.");
        }
        if (iv.length != IV_BYTES) {
            throw new IllegalArgumentException("Vault envelope IV has an invalid length.");
        }
        if (ciphertext.length < GCM_TAG_BITS / 8) {
            throw new IllegalArgumentException("Vault envelope ciphertext is too short.");
        }
        return new ParsedEnvelope(salt, iv, ciphertext);
    }

    private static String requireString(JSONObject object, String key) {
        Object value = object.opt(key);
        if (!(value instanceof String) || ((String) value).isEmpty()) {
            throw new IllegalArgumentException("Vault envelope field '" + key + "' must be a non-empty string.");
        }
        return (String) value;
    }

    private static byte[] decodeBase64(String value, String field) {
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            if (!Base64.getEncoder().encodeToString(decoded).equals(value)) {
                throw new IllegalArgumentException("Vault envelope field '" + field + "' is not canonical Base64.");
            }
            return decoded;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Vault envelope field '" + field + "' is invalid Base64.", e);
        }
    }

    private static SecretKey deriveKey(String passphrase, byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, KDF_ITERATIONS, KEY_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(KDF_NAME);
            byte[] keyBytes = factory.generateSecret(spec).getEncoded();
            try {
                return new SecretKeySpec(keyBytes, "AES");
            } finally {
                java.util.Arrays.fill(keyBytes, (byte) 0);
            }
        } finally {
            spec.clearPassword();
        }
    }

    private static void requirePassphrase(String passphrase) {
        if (passphrase == null) {
            throw new IllegalArgumentException("Passphrase must not be null.");
        }
    }

    private static void ensureWithinLimit(int byteCount) {
        if (byteCount > MAX_ENVELOPE_BYTES) {
            throw new IllegalArgumentException("Vault envelope exceeds the 2 MiB size limit.");
        }
    }

    private static final class ParsedEnvelope {
        final byte[] salt;
        final byte[] iv;
        final byte[] ciphertext;

        ParsedEnvelope(byte[] salt, byte[] iv, byte[] ciphertext) {
            this.salt = salt;
            this.iv = iv;
            this.ciphertext = ciphertext;
        }
    }
}
