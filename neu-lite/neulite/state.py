"""可移植状态：imei（设备指纹）+ 会话票据 + Cookie。

把 state.json 复制到新设备即可保持同一设备指纹与会话，
不会触发短信二次验证（服务端认的是 imei 与 TGT）。
"""
from __future__ import annotations

import json
import os
import tempfile
import uuid
from dataclasses import asdict, dataclass, field

from . import config


@dataclass
class State:
    version: int = 1
    imei: str = ""
    ticket: str = ""            # TGT，同时作为 authorization-str 的 ticket
    temp_token: str = ""        # 短信二次验证的临时令牌（login_code != 1 时）
    ck_lc: str = ""
    ck_vl: str = ""
    profile: dict = field(default_factory=dict)
    cookies: list = field(default_factory=list)   # [{name,value,domain,path}]
    remember: dict = field(default_factory=dict)  # {username,password} 可选
    endpoints: dict = field(default_factory=dict) # 运行时发现的教务/网费端点
    updated_at: str = ""

    def ensure_imei(self) -> str:
        if not self.imei:
            self.imei = str(uuid.uuid4())
        return self.imei

    @property
    def logged_in(self) -> bool:
        return bool(self.ticket)


def load(path: str = config.STATE_FILE) -> State:
    if not os.path.exists(path):
        st = State()
        st.ensure_imei()
        return st
    with open(path, encoding="utf-8") as f:
        raw = json.load(f)
    st = State(**{k: v for k, v in raw.items() if k in State.__dataclass_fields__})
    st.ensure_imei()
    return st


def save(st: State, path: str = config.STATE_FILE) -> None:
    st.updated_at = __import__("datetime").datetime.now().isoformat(timespec="seconds")
    data = json.dumps(asdict(st), ensure_ascii=False, indent=2)
    d = os.path.dirname(os.path.abspath(path)) or "."
    os.makedirs(d, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=d, prefix=".state.", suffix=".json")
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as f:
            f.write(data)
        os.replace(tmp, path)
        try:
            os.chmod(path, 0o600)
        except OSError:
            pass
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)


def export_bytes(st: State) -> bytes:
    return json.dumps(asdict(st), ensure_ascii=False, indent=2).encode("utf-8")


def import_bytes(blob: bytes) -> State:
    raw = json.loads(blob.decode("utf-8"))
    st = State(**{k: v for k, v in raw.items() if k in State.__dataclass_fields__})
    st.ensure_imei()
    return st
