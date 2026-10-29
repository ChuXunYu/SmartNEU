package com.neulite.app;

import android.util.Base64;

import javax.crypto.Cipher;
import java.io.ByteArrayOutputStream;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

/** RSA 信封：与 App 的 ZhilinRsaHelper 一致（117 字节分块 / PKCS#1 v1.5）。 */
public final class Crypto {
    // NEU 租户密钥（SPKI / PKCS#8，base64 DER）
    private static final String PUB_B64 =
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDGxnwlytGO2nB03669B1lllpHhXVsUoDNgKIoDKm/P0cNZXs+DvFzt3DoVKj4Gw+YTm6unbWcG58zy1Yk+nsFMDhZ82r+phOL/l7r33AdWNfG2law1WGotJ9JV/v49LcxQV+14QNh6eckcjixVNE7cJz8qXgPfjzJTukEk2gat5wIDAQAB";
    private static final String PRIV_B64 =
        "MIICdgIBADANBgkqhkiG9w0BAQEFAASCAmAwggJcAgEAAoGBALYAm/bK8MAnkv5PV9e4L4oE/HyFWDJzH3JBxnwENMp+hSKfPYTxO1WoarILke9HcWs02BoMES/LbANQwaixBxoblmZpwHxXoO8X3LkCDNIZvA4FbaDrVD54xExeiuwwX+DN0w5QRll9OlD0orVdyKYWVzJCbUw6xW5fPgviMFBlAgMBAAECgYBN0o7eBULSd1mEQnsGM6yoMJ+hwwbGc7FzkqqDZu0H1PeqqTqN+PRtGRmeThI1pW/a7nqlEJncvPfPpx1Xu3KUP6Y1E01CrVD/M9UarAMAktVygwczUlBoaIEMvitkf9Qf+8h95YvRm4Rg6UZn8RGSYtI4SaD5BYt/xGYWUSVl1QJBAN1PaIkR3qcyd52gTm7kFpjOw1hp/g6xLttSHLSIIBkZOLfSPvQlfysPta8RE779fwsHEyr+FyjRZ6eChRHj4VsCQQDSh+H0k2k84/qpp6Iij0oprMr+7KX/7eZ9DELDnbey5BsqMnRVuh/WgqrcHdkO8+azR3ginRtEZNX7RYJ0N4E/AkBiQcROvxFUuFJlOGw6Tag+teYDFiQHD+GW3HEEMj/WBN2qt3zd1twMGVB35qQWsuO52K1I9EECDV7w/0DnfX5rAkEAgH/Bsac755d7Cowv0QpGiPNXn4tPsstBEHgoU/9y/TRvRC0xhpWpEyQEJ3/sWU0TNb/cmrvrxa4KUWjWvBqYvwJAHSJ36syVGVXwOtY26dnBbwIY9AhBbRoelCojOD3jf+O8DEqkTknRBUXvHes8uReqNJKvtNLTQaZg8yvHC8e5yg==";

    private static final PublicKey PUB;
    private static final PrivateKey PRIV;
    private static final int BLOCK = 117;

    static {
        try {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            PUB = kf.generatePublic(new X509EncodedKeySpec(Base64.decode(PUB_B64, Base64.DEFAULT)));
            PRIV = kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(PRIV_B64, Base64.DEFAULT)));
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

    public static byte[] decrypt(String b64) throws Exception {
        byte[] raw = Base64.decode(b64, Base64.DEFAULT);
        Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        c.init(Cipher.DECRYPT_MODE, PRIV);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < raw.length; i += 128) {
            out.write(c.doFinal(raw, i, 128));
        }
        return out.toByteArray();
    }
}
