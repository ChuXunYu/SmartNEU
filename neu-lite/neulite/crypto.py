"""RSA 信封：与 App 的 ZhilinRsaHelper 完全一致。

- 请求：JSON(UTF-8) -> 117 字节分块 -> RSA/PKCS#1 v1.5 -> 拼接 -> Base64
- 响应：Base64 -> 128 字节分块 -> RSA 解密 -> 拼接
"""
from __future__ import annotations

import base64
import json
import time
from urllib.parse import urlencode

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import padding

from . import config

# NEU 租户密钥（app_environment.dart configNetWork()，tenant index 4）
PUBLIC_PEM = b"""-----BEGIN PUBLIC KEY-----
MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDGxnwlytGO2nB03669B1lllpHhXVsUoDNgKIoDKm/P0cNZXs+DvFzt3DoVKj4Gw+YTm6unbWcG58zy1Yk+nsFMDhZ82r+phOL/l7r33AdWNfG2law1WGotJ9JV/v49LcxQV+14QNh6eckcjixVNE7cJz8qXgPfjzJTukEk2gat5wIDAQAB
-----END PUBLIC KEY-----"""
PRIVATE_PEM = b"""-----BEGIN PRIVATE KEY-----
MIICdgIBADANBgkqhkiG9w0BAQEFAASCAmAwggJcAgEAAoGBALYAm/bK8MAnkv5PV9e4L4oE/HyFWDJzH3JBxnwENMp+hSKfPYTxO1WoarILke9HcWs02BoMES/LbANQwaixBxoblmZpwHxXoO8X3LkCDNIZvA4FbaDrVD54xExeiuwwX+DN0w5QRll9OlD0orVdyKYWVzJCbUw6xW5fPgviMFBlAgMBAAECgYBN0o7eBULSd1mEQnsGM6yoMJ+hwwbGc7FzkqqDZu0H1PeqqTqN+PRtGRmeThI1pW/a7nqlEJncvPfPpx1Xu3KUP6Y1E01CrVD/M9UarAMAktVygwczUlBoaIEMvitkf9Qf+8h95YvRm4Rg6UZn8RGSYtI4SaD5BYt/xGYWUSVl1QJBAN1PaIkR3qcyd52gTm7kFpjOw1hp/g6xLttSHLSIIBkZOLfSPvQlfysPta8RE779fwsHEyr+FyjRZ6eChRHj4VsCQQDSh+H0k2k84/qpp6Iij0oprMr+7KX/7eZ9DELDnbey5BsqMnRVuh/WgqrcHdkO8+azR3ginRtEZNX7RYJ0N4E/AkBiQcROvxFUuFJlOGw6Tag+teYDFiQHD+GW3HEEMj/WBN2qt3zd1twMGVB35qQWsuO52K1I9EECDV7w/0DnfX5rAkEAgH/Bsac755d7Cowv0QpGiPNXn4tPsstBEHgoU/9y/TRvRC0xhpWpEyQEJ3/sWU0TNb/cmrvrxa4KUWjWvBqYvwJAHSJ36syVGVXwOtY26dnBbwIY9AhBbRoelCojOD3jf+O8DEqkTknRBUXvHes8uReqNJKvtNLTQaZg8yvHC8e5yg==
-----END PRIVATE KEY-----"""

PUB = serialization.load_pem_public_key(PUBLIC_PEM)
PRIV = serialization.load_pem_private_key(PRIVATE_PEM, password=None)

# 3.1.8 实际发送字符串秒级时间戳；如需兼容 3.1.5 写法可改为 "millis"
TIMESTAMP_MODE = "seconds"


def encrypt_with(key, plain: bytes) -> str:
    block = key.key_size // 8 - 11
    out = bytearray()
    for i in range(0, len(plain), block):
        out += key.encrypt(plain[i:i + block], padding.PKCS1v15())
    return base64.b64encode(bytes(out)).decode()


def encrypt(plain: bytes) -> str:
    return encrypt_with(PUB, plain)


def decrypt(b64: str) -> bytes:
    raw = base64.b64decode(b64)
    size = PRIV.key_size // 8
    if not raw or len(raw) % size:
        raise ValueError("密文长度不是 %d 的倍数: %d" % (size, len(raw)))
    return b"".join(PRIV.decrypt(raw[i:i + size], padding.PKCS1v15()) for i in range(0, len(raw), size))


def now_ts() -> str:
    micros = int(time.time() * 1_000_000)
    return str(micros // 1000 // 1000 if TIMESTAMP_MODE == "seconds" else micros // 1000)


def content_field(data: dict) -> str:
    """rsaPost 的请求体：content=<urlencode(base64)>。"""
    return urlencode({"content": encrypt(json.dumps(data, separators=(",", ":")).encode())})


def auth_headers(ticket: str = "", ts: str | None = None) -> dict:
    ts = now_ts() if ts is None else ts
    inner = json.dumps({"timestamp": ts, "ticket": ticket}, separators=(",", ":")).replace("\n", "").replace(" ", "")
    headers = dict(config.COMMON_HEADERS)
    headers["authorization-str"] = encrypt(inner.encode())
    return headers
