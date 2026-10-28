package com.neulite.app;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** 导出文件 AES-256-GCM + PBKDF2 加密。只在导入/导出时运行，不影响日常速度。 */
final class CryptoBox {
    private static final byte[] MAGIC = "NEUL1".getBytes(StandardCharsets.US_ASCII);
    private static final int SALT = 16;
    private static final int IV = 12;
    private static final int ITER = 120000;

    private CryptoBox() {}

    static boolean isEncrypted(byte[] data) {
        if (data == null || data.length < MAGIC.length + SALT + IV + 16) return false;
        for (int i = 0; i < MAGIC.length; i++) if (data[i] != MAGIC[i]) return false;
        return true;
    }

    static byte[] encrypt(byte[] plain, String password) throws Exception {
        if (password == null || password.isEmpty()) throw new Exception("密码不能为空");
        byte[] salt = new byte[SALT];
        byte[] iv = new byte[IV];
        SecureRandom r = new SecureRandom();
        r.nextBytes(salt);
        r.nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key(password, salt), new GCMParameterSpec(128, iv));
        byte[] body = c.doFinal(plain);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(MAGIC);
        out.write(salt);
        out.write(iv);
        out.write(body);
        return out.toByteArray();
    }

    static byte[] decrypt(byte[] data, String password) throws Exception {
        if (!isEncrypted(data)) throw new Exception("不是加密文件");
        if (password == null || password.isEmpty()) throw new Exception("需要密码");
        int p = MAGIC.length;
        byte[] salt = Arrays.copyOfRange(data, p, p + SALT);
        p += SALT;
        byte[] iv = Arrays.copyOfRange(data, p, p + IV);
        p += IV;
        byte[] body = Arrays.copyOfRange(data, p, data.length);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key(password, salt), new GCMParameterSpec(128, iv));
        return c.doFinal(body);
    }

    private static SecretKey key(String password, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITER, 256);
        SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
        return new SecretKeySpec(f.generateSecret(spec).getEncoded(), "AES");
    }
}
