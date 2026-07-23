"""B站登录配置管理：本地持久化 SESSDATA cookie，用于解锁 1080P 及以上清晰度。

与 history.py 同模式：JSON 文件 + threading.Lock 保护并发读写。
"""
from __future__ import annotations

import json
import threading

from ..config import BILI_CONFIG_FILE


class BiliConfigManager:
    """B站配置管理器：读写 bili_config.json。"""

    def __init__(self) -> None:
        self._lock = threading.Lock()

    def get_sessdata(self) -> str:
        """读取已保存的 SESSDATA，无配置返回空串。"""
        with self._lock:
            if not BILI_CONFIG_FILE.exists():
                return ""
            try:
                with open(BILI_CONFIG_FILE, "r", encoding="utf-8") as f:
                    data = json.load(f)
            except (json.JSONDecodeError, OSError):
                return ""
        if isinstance(data, dict):
            return str(data.get("sessdata", "") or "")
        return ""

    def set_sessdata(self, value: str) -> None:
        """保存 SESSDATA，空串等价于清除（写入空配置）。"""
        value = (value or "").strip()
        with self._lock:
            BILI_CONFIG_FILE.parent.mkdir(parents=True, exist_ok=True)
            with open(BILI_CONFIG_FILE, "w", encoding="utf-8") as f:
                json.dump({"sessdata": value}, f, ensure_ascii=False, indent=2)


# 模块级单例
bili_config = BiliConfigManager()
