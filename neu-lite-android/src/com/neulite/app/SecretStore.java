package com.neulite.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** SMTP 专用密码：Android Keystore AES-GCM，只在本机可解，不写 SD 卡。 */
final class SecretStore {
    private static final String ALIAS = "neu-lite-smtp";
    private static final String PREFS = "neu-lite-secure";
    private static final String KEY = "smtp_password";
    private static final String RUN_KEY = "campus_run_url";

    private SecretStore() {}

    static void save(Context ctx, String password) throws Exception {
        put(ctx, KEY, password);
    }

    static String get(Context ctx) {
        return get(ctx, KEY);
    }

    static void saveRunUrl(Context ctx, String url) throws Exception {
        put(ctx, RUN_KEY, url);
    }

    static String getRunUrl(Context ctx) {
        return get(ctx, RUN_KEY);
    }

    static void clearRunUrl(Context ctx) {
        prefs(ctx).edit().remove(RUN_KEY).apply();
    }

    private static void put(Context ctx, String keyName, String value) throws Exception {
        if (value == null || value.isEmpty()) return;
        SecretKey key = key();
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key);
        byte[] iv = c.getIV();
        byte[] ct = c.doFinal(value.getBytes(StandardCharsets.UTF_8));
        byte[] all = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, all, 0, iv.length);
        System.arraycopy(ct, 0, all, iv.length, ct.length);
        prefs(ctx).edit().putString(keyName, Base64.encodeToString(all, Base64.NO_WRAP)).apply();
    }

    private static String get(Context ctx, String keyName) {
        try {
            String raw = prefs(ctx).getString(keyName, "");
            if (raw == null || raw.isEmpty()) return "";
            byte[] all = Base64.decode(raw, Base64.NO_WRAP);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, all, 0, 12));
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    static boolean has(Context ctx) {
        return !get(ctx).isEmpty();
    }

    static void clear(Context ctx) {
        prefs(ctx).edit().remove(KEY).apply();
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS);
        } catch (Exception ignored) {}
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) return (SecretKey) ks.getKey(ALIAS, null);
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }
}
