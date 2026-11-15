package com.promoter.tracker;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import javax.crypto.KeyGenerator;

/** Authenticated backup encryption. Raw backup JSON is never written to exported backup files. */
final class BackupCrypto {
    private static final String PREFS = "promoter_backup_crypto";
    private static final String MASTER_BLOB = "device_master_blob";
    private static final String MASTER_NONCE = "device_master_nonce";
    private static final String RECOVERY_SALT = "recovery_salt";
    private static final String RECOVERY_NONCE = "recovery_nonce";
    private static final String RECOVERY_WRAP = "recovery_wrap";
    private static final String KEY_ALIAS = "promoter_tracker_backup_wrap_v1";
    private static final byte[] MAGIC = "PTBACKUP".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] LOCAL_MAGIC = "PTLOCAL1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] BACKUP_AAD = "PromoterTracker.Backup.v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] WRAP_AAD = "PromoterTracker.RecoveryKey.v1".getBytes(StandardCharsets.UTF_8);
    private static final int ITERATIONS = 250000;
    private static final int KEY_BITS = 256;
    private static final int GCM_TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private BackupCrypto() {}

    static boolean hasRecoveryPassword(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(RECOVERY_WRAP);
    }

    static void setRecoveryPassword(Context context, char[] password) throws Exception {
        if (password == null || password.length < 10) throw new IllegalArgumentException("Use a recovery password with at least 10 characters.");
        byte[] master = getOrCreateMasterKey(context);
        byte[] salt = randomBytes(16);
        byte[] nonce = randomBytes(12);
        byte[] wrappingKey = deriveKey(password, salt);
        byte[] wrapped = aesEncrypt(wrappingKey, nonce, master, WRAP_AAD);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(RECOVERY_SALT, b64(salt))
                .putString(RECOVERY_NONCE, b64(nonce))
                .putString(RECOVERY_WRAP, b64(wrapped))
                .apply();
        Arrays.fill(master, (byte) 0);
        Arrays.fill(wrappingKey, (byte) 0);
        Arrays.fill(password, '\0');
    }

    static byte[] encryptBackup(Context context, String json) throws Exception {
        if (json == null || json.trim().isEmpty()) throw new IllegalArgumentException("No backup data.");
        byte[] master = getOrCreateMasterKey(context);
        byte[] nonce = randomBytes(12);
        byte[] cipherText = aesEncrypt(master, nonce, json.getBytes(StandardCharsets.UTF_8), BACKUP_AAD);
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean hasRecovery = p.contains(RECOVERY_WRAP);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.write(MAGIC);
        out.writeByte(1);
        out.writeByte(hasRecovery ? 1 : 0);
        out.write(nonce);
        if (hasRecovery) {
            out.write(fromB64(p.getString(RECOVERY_SALT, "")));
            out.write(fromB64(p.getString(RECOVERY_NONCE, "")));
            byte[] wrapped = fromB64(p.getString(RECOVERY_WRAP, ""));
            out.writeShort(wrapped.length);
            out.write(wrapped);
        }
        out.writeInt(cipherText.length);
        out.write(cipherText);
        out.flush();
        Arrays.fill(master, (byte) 0);
        return bytes.toByteArray();
    }

    static String decryptBackup(Context context, byte[] file, char[] recoveryPassword) throws Exception {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(file));
        byte[] magic = new byte[MAGIC.length];
        in.readFully(magic);
        if (!Arrays.equals(magic, MAGIC)) throw new IllegalArgumentException("This is not a Promoter Tracker encrypted backup.");
        int version = in.readUnsignedByte();
        if (version != 1) throw new IllegalArgumentException("This backup version is not supported by this app.");
        boolean hasRecovery = in.readUnsignedByte() == 1;
        byte[] dataNonce = new byte[12];
        in.readFully(dataNonce);
        byte[] salt = null, wrapNonce = null, wrapped = null;
        if (hasRecovery) {
            salt = new byte[16]; wrapNonce = new byte[12];
            in.readFully(salt); in.readFully(wrapNonce);
            int wrapLength = in.readUnsignedShort();
            if (wrapLength != 48) throw new IllegalArgumentException("Invalid recovery key data.");
            wrapped = new byte[wrapLength]; in.readFully(wrapped);
        }
        int cipherLength = in.readInt();
        if (cipherLength < 16 || cipherLength > 6 * 1024 * 1024 || cipherLength != in.available())
            throw new IllegalArgumentException("The backup file is incomplete or invalid.");
        byte[] cipherText = new byte[cipherLength]; in.readFully(cipherText);

        byte[] master = getExistingMasterKey(context);
        if (master != null) {
            try {
                String plain = new String(aesDecrypt(master, dataNonce, cipherText, BACKUP_AAD), StandardCharsets.UTF_8);
                Arrays.fill(master, (byte) 0);
                return plain;
            } catch (Exception wrongDeviceKey) {
                Arrays.fill(master, (byte) 0);
                master = null;
            }
        }
        if (master == null && hasRecovery) {
            if (recoveryPassword == null || recoveryPassword.length == 0)
                throw new RecoveryPasswordRequiredException();
            byte[] wrappingKey = deriveKey(recoveryPassword, salt);
            try { master = aesDecrypt(wrappingKey, wrapNonce, wrapped, WRAP_AAD); }
            finally { Arrays.fill(wrappingKey, (byte) 0); Arrays.fill(recoveryPassword, '\0'); }
        }
        if (master == null && !hasRecovery) throw new IllegalStateException("This backup has no new-phone recovery key. Restore it on the original phone, or create a new backup after setting a recovery password.");
        if (master == null) throw new RecoveryPasswordRequiredException();
        try {
            return new String(aesDecrypt(master, dataNonce, cipherText, BACKUP_AAD), StandardCharsets.UTF_8);
        } finally { Arrays.fill(master, (byte) 0); }
    }

    static byte[] encryptLocal(Context context, String plainText) throws Exception {
        byte[] master = getOrCreateMasterKey(context);
        byte[] nonce = randomBytes(12);
        byte[] encrypted = aesEncrypt(master, nonce, plainText.getBytes(StandardCharsets.UTF_8), LOCAL_MAGIC);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(LOCAL_MAGIC); bytes.write(nonce); bytes.write(encrypted);
        Arrays.fill(master, (byte) 0);
        return bytes.toByteArray();
    }

    static String decryptLocal(Context context, byte[] bytes) throws Exception {
        if (bytes == null || bytes.length < 36) throw new IllegalArgumentException("Encrypted app snapshot is invalid.");
        byte[] magic = Arrays.copyOfRange(bytes, 0, 8);
        if (!Arrays.equals(magic, LOCAL_MAGIC)) throw new IllegalArgumentException("Encrypted app snapshot format is not supported.");
        byte[] nonce = Arrays.copyOfRange(bytes, 8, 20);
        byte[] encrypted = Arrays.copyOfRange(bytes, 20, bytes.length);
        byte[] master = getOrCreateMasterKey(context);
        try { return new String(aesDecrypt(master, nonce, encrypted, LOCAL_MAGIC), StandardCharsets.UTF_8); }
        finally { Arrays.fill(master, (byte) 0); }
    }

    private static byte[] getOrCreateMasterKey(Context context) throws Exception {
        byte[] existing = getExistingMasterKey(context);
        if (existing != null) return existing;
        byte[] master = randomBytes(32);
        // Android Keystore keys with randomized encryption must generate their own GCM IV.
        // Supplying a caller-generated IV here causes "Caller-provided IV not permitted".
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getDeviceKey());
        cipher.updateAAD(WRAP_AAD);
        byte[] wrapped = cipher.doFinal(master);
        byte[] nonce = cipher.getIV();
        if (nonce == null || nonce.length != 12) {
            Arrays.fill(master, (byte) 0);
            throw new IllegalStateException("Android Keystore returned an invalid backup encryption IV.");
        }
        boolean saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(MASTER_NONCE, b64(nonce)).putString(MASTER_BLOB, b64(wrapped)).commit();
        if (!saved) {
            Arrays.fill(master, (byte) 0);
            throw new IllegalStateException("Could not securely save the backup encryption key.");
        }
        return master;
    }

    private static byte[] getExistingMasterKey(Context context) throws Exception {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String blob = p.getString(MASTER_BLOB, null);
        String nonce = p.getString(MASTER_NONCE, null);
        if (blob == null || nonce == null) return null;
        try { return aesDecrypt(getDeviceKey(), fromB64(nonce), fromB64(blob), WRAP_AAD); }
        catch (Exception e) { return null; }
    }

    private static SecretKey getDeviceKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        java.security.Key existing = store.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }

    private static byte[] deriveKey(char[] password, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, KEY_BITS);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
    }

    private static byte[] aesEncrypt(byte[] key, byte[] nonce, byte[] plain, byte[] aad) throws Exception {
        return aesEncrypt(new SecretKeySpec(key, "AES"), nonce, plain, aad);
    }
    private static byte[] aesEncrypt(SecretKey key, byte[] nonce, byte[] plain, byte[] aad) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
        cipher.updateAAD(aad);
        return cipher.doFinal(plain);
    }
    private static byte[] aesDecrypt(byte[] key, byte[] nonce, byte[] cipherText, byte[] aad) throws Exception {
        return aesDecrypt(new SecretKeySpec(key, "AES"), nonce, cipherText, aad);
    }
    private static byte[] aesDecrypt(SecretKey key, byte[] nonce, byte[] cipherText, byte[] aad) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
        cipher.updateAAD(aad);
        return cipher.doFinal(cipherText);
    }
    private static byte[] randomBytes(int length) { byte[] b = new byte[length]; RANDOM.nextBytes(b); return b; }
    private static String b64(byte[] b) { return Base64.encodeToString(b, Base64.NO_WRAP); }
    private static byte[] fromB64(String s) { return Base64.decode(s, Base64.NO_WRAP); }

    static final class RecoveryPasswordRequiredException extends Exception {
        RecoveryPasswordRequiredException() { super("A recovery password is required to restore this backup on this device."); }
    }
}
