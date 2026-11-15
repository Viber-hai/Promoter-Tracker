package com.promoter.tracker;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

final class SecurityStore {
    private static final String PREFS = "promoter_app_security";
    private static final String ENABLED = "biometric_lock_enabled";
    private static final String PIN_SALT = "pin_salt";
    private static final String PIN_HASH = "pin_hash";
    private static final String LOCK_TIMEOUT = "lock_timeout_minutes";
    private static final int ITERATIONS = 210000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private SecurityStore() {}

    static boolean isEnabled(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false);
    }
    static void setEnabled(Context c, boolean enabled) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, enabled).apply();
    }
    static boolean isPinConfigured(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(PIN_HASH);
    }
    static int getLockTimeout(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(LOCK_TIMEOUT, 5);
    }
    static void setLockTimeout(Context c, int minutes) {
        int safe = minutes == 0 ? 0 : (minutes == 1 ? 1 : (minutes == 15 ? 15 : 5));
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(LOCK_TIMEOUT, safe).apply();
    }
    static void setPin(Context c, String pin) throws Exception {
        if (pin == null || !pin.matches("[0-9]{6}")) throw new IllegalArgumentException("PIN must contain exactly 6 digits.");
        byte[] salt = new byte[16]; RANDOM.nextBytes(salt);
        byte[] hash = derive(pin.toCharArray(), salt);
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(PIN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString(PIN_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
                .putInt("pin_failed_attempts", 0).putLong("pin_locked_until", 0L)
                .apply();
        Arrays.fill(hash, (byte) 0);
    }
    static boolean verifyPin(Context c, String pin) {
        if (pin == null || !pin.matches("[0-9]{6}")) return false;
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (System.currentTimeMillis() < p.getLong("pin_locked_until", 0L)) return false;
        String saltText = p.getString(PIN_SALT, null), hashText = p.getString(PIN_HASH, null);
        if (saltText == null || hashText == null) return false;
        byte[] actual = null;
        try {
            byte[] expected = Base64.decode(hashText, Base64.NO_WRAP);
            actual = derive(pin.toCharArray(), Base64.decode(saltText, Base64.NO_WRAP));
            boolean valid = MessageDigest.isEqual(expected, actual);
            if (valid) p.edit().putInt("pin_failed_attempts", 0).putLong("pin_locked_until", 0L).apply();
            else {
                int failures = p.getInt("pin_failed_attempts", 0) + 1;
                SharedPreferences.Editor edit = p.edit().putInt("pin_failed_attempts", failures);
                if (failures >= 5) edit.putInt("pin_failed_attempts", 0).putLong("pin_locked_until", System.currentTimeMillis() + 30000L);
                edit.apply();
            }
            return valid;
        } catch (Exception e) { return false; }
        finally { if (actual != null) Arrays.fill(actual, (byte) 0); }
    }
    private static byte[] derive(char[] pin, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(pin, salt, ITERATIONS, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); Arrays.fill(pin, '\0'); }
    }
}
