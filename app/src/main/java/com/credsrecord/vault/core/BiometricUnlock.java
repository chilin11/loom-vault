package com.credsrecord.vault.core;

import android.app.Activity;
import android.app.Application;
import android.hardware.biometrics.BiometricPrompt;
import android.content.Context;
import android.content.DialogInterface;
import android.hardware.biometrics.BiometricManager;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import android.view.WindowManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.UnrecoverableKeyException;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Optional, device-local biometric storage for the vault master passphrase.
 *
 * <p>Call {@link #isAvailable(Context)} before offering enrollment. The Activity window must have
 * {@link WindowManager.LayoutParams#FLAG_SECURE} set before calling {@link #enroll} or
 * {@link #unlock}. The encrypted record is kept in no-backup internal storage; it is never
 * included in the vault envelope or sent to a server. Authentication uses a strong-biometric-only,
 * per-use Android Keystore AES-256-GCM key.</p>
 *
 * <p>On API 28-29 the platform prompt has no authenticator-selection API. Its CryptoObject and
 * per-use Keystore key restrict use to biometrics accepted for cryptographic operations; API 30+
 * additionally requests BIOMETRIC_STRONG explicitly. If biometrics are changed and the key is
 * invalidated, the encrypted record is retained and the caller should fall back to the master
 * password.</p>
 */
public final class BiometricUnlock {
    private static final String KEY_ALIAS = "com.credsrecord.vault.biometric.unlock.v1";
    private static final String FILE_NAME = "biometric-master.v1";
    private static final int FILE_MAGIC = 0x43524249; // CRBI
    private static final int FILE_VERSION = 1;
    private static final int IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int MAX_PASSPHRASE_BYTES = 64 * 1024;
    private static final int MAX_RECORD_BYTES = MAX_PASSPHRASE_BYTES + 128;
    private static final byte[] AAD =
            "com.credsrecord.vault.biometric.unlock:v1".getBytes(StandardCharsets.UTF_8);

    private static final Object OPERATIONS_LOCK = new Object();
    private static final Map<Activity, WeakReference<Operation>> OPERATIONS = new WeakHashMap<>();

    private BiometricUnlock() { }

    /** Returns whether a crypto-capable biometric is enrolled and available on this device. */
    @SuppressWarnings("deprecation")
    public static boolean isAvailable(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return false;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                BiometricManager manager = context.getSystemService(BiometricManager.class);
                return manager != null && manager.canAuthenticate(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG)
                        == BiometricManager.BIOMETRIC_SUCCESS;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                BiometricManager manager = context.getSystemService(BiometricManager.class);
                return manager != null
                        && manager.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS;
            }
            FingerprintManager manager = context.getSystemService(FingerprintManager.class);
            return manager != null && manager.isHardwareDetected()
                    && manager.hasEnrolledFingerprints();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Returns true when a biometric passphrase record is present, even if its key was invalidated. */
    public static boolean isEnabled(Context context) {
        if (context == null) {
            return false;
        }
        File file = getRecordFile(context);
        return file.exists() || new File(file.getPath() + ".bak").exists();
    }

    /** Shows the system prompt and atomically enables biometric unlock for {@code master}. */
    public static void enroll(Activity activity, String master, Callback callback) {
        request(activity, callback, master, true);
    }

    /** Shows the system prompt and returns the stored master passphrase after successful auth. */
    public static void unlock(Activity activity, Callback callback) {
        request(activity, callback, null, false);
    }

    /** Removes only the biometric record and its Keystore key; the local vault is not modified. */
    public static void disable(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("Context must not be null.");
        }
        new AtomicFile(getRecordFile(context)).delete();
        try {
            KeyStore keyStore = loadKeyStore();
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS);
            }
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("Could not remove the biometric Keystore key.", e);
        }
    }

    /** Cancels this Activity's pending prompt and reports an error if the Activity is still alive. */
    public static void cancel(Activity activity) {
        if (activity == null) {
            return;
        }
        Operation operation;
        synchronized (OPERATIONS_LOCK) {
            WeakReference<Operation> reference = OPERATIONS.get(activity);
            operation = reference == null ? null : reference.get();
        }
        if (operation != null) {
            operation.cancelWithError("Biometric authentication was canceled. Use your master password.");
        }
    }

    /** Callback methods are delivered on the Activity's main thread when it remains alive. */
    public interface Callback {
        /** Called with the master passphrase after enrollment or successful unlock. */
        void onSuccess(String passphrase);

        /** Called when enrollment or unlock cannot complete; the vault remains untouched. */
        void onError(String message);
    }

    private static void request(Activity activity, Callback callback, String master, boolean enrolling) {
        if (callback == null) {
            return;
        }
        if (activity == null) {
            callback.onError("An Activity is required for biometric authentication.");
            return;
        }
        activity.runOnUiThread(() -> {
            if (!isActivityAlive(activity)) {
                return;
            }
            if (!hasSecureWindow(activity)) {
                callback.onError("Set FLAG_SECURE on the Activity window before biometric authentication.");
                return;
            }
            if (!isAvailable(activity)) {
                callback.onError("A strong enrolled biometric is not available. Use your master password.");
                return;
            }
            if (enrolling && (master == null || master.isEmpty())) {
                callback.onError("The master passphrase must not be empty.");
                return;
            }
            if (enrolling && isEnabled(activity)) {
                callback.onError("Biometric unlock is already enabled. Disable it before enrolling again.");
                return;
            }
            if (!enrolling && !isEnabled(activity)) {
                callback.onError("Biometric unlock is not enabled. Use your master password.");
                return;
            }

            Operation previous;
            synchronized (OPERATIONS_LOCK) {
                WeakReference<Operation> reference = OPERATIONS.get(activity);
                previous = reference == null ? null : reference.get();
            }
            if (previous != null) {
                previous.cancelWithError("A previous biometric request was canceled.");
            }

            Operation operation = new Operation(activity, callback);
            synchronized (OPERATIONS_LOCK) {
                OPERATIONS.put(activity, new WeakReference<>(operation));
            }
            activity.getApplication().registerActivityLifecycleCallbacks(operation);
            try {
                if (enrolling) {
                    operation.startEnrollment(master);
                } else {
                    operation.startUnlock();
                }
            } catch (Exception e) {
                operation.finishError(errorMessage(e, enrolling));
            }
        });
    }

    private static boolean hasSecureWindow(Activity activity) {
        return activity.getWindow() != null
                && (activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0;
    }

    private static boolean isActivityAlive(Activity activity) {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed();
    }

    private static File getRecordFile(Context context) {
        Context app = context.getApplicationContext();
        if (app == null) {
            app = context;
        }
        return new File(app.getNoBackupFilesDir(), FILE_NAME);
    }

    private static KeyStore loadKeyStore() throws GeneralSecurityException, IOException {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        return keyStore;
    }

    private static SecretKey generateKey() throws GeneralSecurityException {
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG);
        } else {
            // -1 means authenticate separately for every cryptographic operation.
            builder.setUserAuthenticationValidityDurationSeconds(-1);
        }
        generator.init(builder.build());
        return generator.generateKey();
    }

    private static Cipher newEncryptionCipher() throws Exception {
        KeyStore keyStore = loadKeyStore();
        SecretKey key = getKey(keyStore);
        if (key == null) {
            key = generateKey();
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        try {
            cipher.init(Cipher.ENCRYPT_MODE, key);
        } catch (GeneralSecurityException e) {
            // There is no saved biometric record during enrollment, so a stale orphan key is safe to replace.
            if (!isKeyInvalidated(e)) {
                throw e;
            }
            keyStore.deleteEntry(KEY_ALIAS);
            key = generateKey();
            cipher.init(Cipher.ENCRYPT_MODE, key);
        }
        cipher.updateAAD(AAD);
        return cipher;
    }

    private static Cipher newDecryptionCipher(Record record) throws Exception {
        KeyStore keyStore = loadKeyStore();
        SecretKey key = getKey(keyStore);
        if (key == null) {
            throw new KeyStoreException("The biometric Keystore key is missing.");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, record.iv));
        cipher.updateAAD(AAD);
        return cipher;
    }

    private static SecretKey getKey(KeyStore keyStore) throws GeneralSecurityException {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            return null;
        }
        try {
            return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        } catch (UnrecoverableKeyException e) {
            throw new KeyStoreException("The biometric Keystore key is unavailable.", e);
        }
    }

    private static boolean isKeyInvalidated(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof KeyPermanentlyInvalidatedException) {
                return true;
            }
        }
        return false;
    }

    private static String errorMessage(Throwable error, boolean enrolling) {
        if (isKeyInvalidated(error)) {
            return "Biometric enrollment changed and invalidated its key. Use the master password; the vault was not changed. Disable and re-enroll biometric unlock if desired.";
        }
        return enrolling
                ? "Could not enable biometric unlock. The local vault was not changed."
                : "Biometric unlock failed. Use your master password; the local vault was not changed.";
    }

    private static void writeRecord(Context context, byte[] iv, byte[] ciphertext) throws IOException {
        if (iv == null || iv.length != IV_BYTES || ciphertext == null
                || ciphertext.length < GCM_TAG_BITS / 8
                || ciphertext.length > MAX_PASSPHRASE_BYTES + GCM_TAG_BITS / 8) {
            throw new IOException("Invalid biometric record data.");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(FILE_MAGIC);
            output.writeInt(FILE_VERSION);
            output.writeInt(iv.length);
            output.write(iv);
            output.writeInt(ciphertext.length);
            output.write(ciphertext);
        }
        byte[] serialized = bytes.toByteArray();
        AtomicFile atomicFile = new AtomicFile(getRecordFile(context));
        FileOutputStream output = null;
        try {
            output = atomicFile.startWrite();
            output.write(serialized);
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

    private static Record readRecord(Context context) throws IOException {
        AtomicFile atomicFile = new AtomicFile(getRecordFile(context));
        try (FileInputStream input = atomicFile.openRead();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_RECORD_BYTES) {
                    throw new IOException("Biometric record is too large.");
                }
                output.write(buffer, 0, count);
            }
            return parseRecord(output.toByteArray());
        }
    }

    private static Record parseRecord(byte[] serialized) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(serialized))) {
            if (input.readInt() != FILE_MAGIC || input.readInt() != FILE_VERSION) {
                throw new IOException("Unsupported biometric record format.");
            }
            int ivLength = input.readInt();
            if (ivLength != IV_BYTES) {
                throw new IOException("Invalid biometric record IV.");
            }
            byte[] iv = new byte[ivLength];
            input.readFully(iv);
            int ciphertextLength = input.readInt();
            if (ciphertextLength < GCM_TAG_BITS / 8
                    || ciphertextLength > MAX_PASSPHRASE_BYTES + GCM_TAG_BITS / 8
                    || ciphertextLength != input.available()) {
                throw new IOException("Invalid biometric record ciphertext.");
            }
            byte[] ciphertext = new byte[ciphertextLength];
            input.readFully(ciphertext);
            return new Record(iv, ciphertext);
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IOException("Invalid biometric record.", e);
        }
    }

    private static String decodePassphrase(byte[] cleartext) throws CharacterCodingException, IOException {
        if (cleartext.length == 0 || cleartext.length > MAX_PASSPHRASE_BYTES) {
            throw new IOException("Invalid decrypted passphrase.");
        }
        String passphrase = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(cleartext)).toString();
        if (passphrase.isEmpty()) {
            throw new IOException("Invalid decrypted passphrase.");
        }
        return passphrase;
    }

    private static final class Record {
        final byte[] iv;
        final byte[] ciphertext;

        Record(byte[] iv, byte[] ciphertext) {
            this.iv = iv;
            this.ciphertext = ciphertext;
        }
    }

    private static final class Operation implements Application.ActivityLifecycleCallbacks {
        private final WeakReference<Activity> activityReference;
        private final Callback callback;
        private final AtomicBoolean finished = new AtomicBoolean();
        private Application application;
        private BiometricPrompt prompt;
        private CancellationSignal cancellationSignal;
        private byte[] enrollmentBytes;
        private String enrollmentPassphrase;

        Operation(Activity activity, Callback callback) {
            activityReference = new WeakReference<>(activity);
            this.callback = callback;
            application = activity.getApplication();
        }

        void startEnrollment(String master) throws Exception {
            enrollmentPassphrase = master;
            enrollmentBytes = master.getBytes(StandardCharsets.UTF_8);
            if (enrollmentBytes.length == 0 || enrollmentBytes.length > MAX_PASSPHRASE_BYTES) {
                throw new IllegalArgumentException("Master passphrase is too long.");
            }
            authenticate(newEncryptionCipher(), true);
        }

        void startUnlock() throws Exception {
            Activity activity = activityReference.get();
            if (!isActivityAlive(activity)) {
                finishSilently();
                return;
            }
            Record record = readRecord(activity);
            authenticate(newDecryptionCipher(record), false);
        }

        private void authenticate(Cipher cipher, boolean enrolling) {
            Activity activity = activityReference.get();
            if (!isActivityAlive(activity)) {
                finishSilently();
                return;
            }
            Executor mainExecutor = command -> new Handler(Looper.getMainLooper()).post(command);
            BiometricPrompt.Builder builder = new BiometricPrompt.Builder(activity)
                    .setTitle("Unlock LoomVault")
                    .setSubtitle(enrolling ? "Enable biometric unlock" : "Unlock your vault")
                    .setDescription("Authenticate with a strong biometric on this device.")
                    .setNegativeButton("Use master password", mainExecutor,
                            (DialogInterface dialog, int which) -> cancelWithError(
                                    "Biometric authentication was canceled. Use your master password."));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG);
            }
            prompt = builder.build();
            cancellationSignal = new CancellationSignal();
            prompt.authenticate(new BiometricPrompt.CryptoObject(cipher), cancellationSignal,
                    mainExecutor, new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(
                                BiometricPrompt.AuthenticationResult result) {
                            if (finished.get()) {
                                return;
                            }
                            Activity current = activityReference.get();
                            if (!isActivityAlive(current)) {
                                finishSilently();
                                return;
                            }
                            Cipher authenticatedCipher = result.getCryptoObject() == null
                                    ? null : result.getCryptoObject().getCipher();
                            if (authenticatedCipher == null) {
                                finishError("Biometric authentication did not return a secure operation. Use your master password.");
                                return;
                            }
                            if (enrolling) {
                                finishEnrollment(current, authenticatedCipher);
                            } else {
                                finishUnlock(authenticatedCipher);
                            }
                        }

                        @Override
                        public void onAuthenticationError(int errorCode, CharSequence errString) {
                            finishError("Biometric authentication was unavailable or canceled. Use your master password.");
                        }

                        @Override
                        public void onAuthenticationFailed() {
                            // A non-match is not terminal; the system prompt remains available for another attempt.
                        }
                    });
        }

        private void finishEnrollment(Activity activity, Cipher authenticatedCipher) {
            byte[] cleartext = enrollmentBytes;
            try {
                byte[] iv = authenticatedCipher.getIV();
                byte[] ciphertext = authenticatedCipher.doFinal(cleartext);
                writeRecord(activity, iv, ciphertext);
                String passphrase = enrollmentPassphrase;
                finish(passphrase, null, false);
            } catch (Exception e) {
                finishError(errorMessage(e, true));
            } finally {
                wipe(enrollmentBytes);
                enrollmentBytes = null;
            }
        }

        private void finishUnlock(Cipher authenticatedCipher) {
            byte[] cleartext = null;
            try {
                cleartext = authenticatedCipher.doFinal(readRecord(activityReference.get()).ciphertext);
                String passphrase = decodePassphrase(cleartext);
                finish(passphrase, null, false);
            } catch (Exception e) {
                finishError(errorMessage(e, false));
            } finally {
                wipe(cleartext);
            }
        }

        void finishError(String message) {
            finish(null, message, false);
        }

        void cancelWithError(String message) {
            finish(null, message, true);
        }

        private void finishSilently() {
            finish(null, null, true);
        }

        private void finish(String passphrase, String message, boolean cancelPrompt) {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            if (cancelPrompt) {
                try {
                    if (cancellationSignal != null) {
                        cancellationSignal.cancel();
                    }
                } catch (RuntimeException ignored) {
                    // Prompt cancellation is best effort during Activity teardown.
                }
            }
            cleanup();
            Activity activity = activityReference.get();
            if (!isActivityAlive(activity)) {
                return;
            }
            if (message == null) {
                callback.onSuccess(passphrase);
            } else {
                callback.onError(message);
            }
        }

        private void cleanup() {
            Activity activity = activityReference.get();
            if (application != null) {
                try {
                    application.unregisterActivityLifecycleCallbacks(this);
                } catch (RuntimeException ignored) {
                    // The Application may already be shutting down.
                }
            }
            if (activity != null) {
                synchronized (OPERATIONS_LOCK) {
                    WeakReference<Operation> reference = OPERATIONS.get(activity);
                    if (reference != null && reference.get() == this) {
                        OPERATIONS.remove(activity);
                    }
                }
            }
            prompt = null;
            cancellationSignal = null;
            application = null;
            wipe(enrollmentBytes);
            enrollmentBytes = null;
            enrollmentPassphrase = null;
        }

        @Override
        public void onActivityDestroyed(Activity destroyed) {
            if (activityReference.get() == destroyed) {
                finishSilently();
            }
        }

        @Override public void onActivityCreated(Activity activity, android.os.Bundle state) { }
        @Override public void onActivityStarted(Activity activity) { }
        @Override public void onActivityResumed(Activity activity) { }
        @Override public void onActivityPaused(Activity activity) { }
        @Override public void onActivityStopped(Activity activity) { }
        @Override public void onActivitySaveInstanceState(Activity activity, android.os.Bundle state) { }
    }

    private static void wipe(byte[] bytes) {
        if (bytes != null) {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }
}
