package com.neulite.app;

import android.util.Base64;

import javax.crypto.Cipher;
import java.io.ByteArrayOutputStream;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;

/** RSA 信封：与 App 的 ZhilinRsaHelper 一致（117 字节分块 / PKCS#1 v1.5）。 */
public final class Crypto {
    // NEU 租户公钥（SPKI，base64 DER）；客户端不保存私钥
    private static final String PUB_B64 =
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDGxnwlytGO2nB03669B1lllpHhXVsUoDNgKIoDKm/P0cNZXs+DvFzt3DoVKj4Gw+YTm6unbWcG58zy1Yk+nsFMDhZ82r+phOL/l7r33AdWNfG2law1WGotJ9JV/v49LcxQV+14QNh6eckcjixVNE7cJz8qXgPfjzJTukEk2gat5wIDAQAB";
    private static final PublicKey PUB;

    private static final int BLOCK = 117;

    static {
        try {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            PUB = kf.generatePublic(new X509EncodedKeySpec(Base64.decode(PUB_B64, Base64.DEFAULT)));
        } catch (Exception e) {
            throw new RuntimeException("RSA 初始化失败", e);
        }
    }

    private Crypto() {}

    public static String encrypt(byte[] data) throws Exception {
        Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        c.init(Cipher.ENCRYPT_MODE, PUB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < data.length; i += BLOCK) {
            out.write(c.doFinal(data, i, Math.min(BLOCK, data.length - i)));
        }
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }


}
