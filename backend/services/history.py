"""历史记录服务：基于 JSON 文件管理下载历史与本地文件查询。"""
from __future__ import annotations

import json
import os
import threading
import uuid
from datetime import datetime

from ..config import DOWNLOAD_DIR, HISTORY_FILE


class HistoryManager:
    """下载历史管理器：读写 history.json，并提供本地下载文件查询。"""

    def __init__(self) -> None:
        # 保护文件读写并发安全
        self._lock = threading.Lock()

    def add_record(self, url: str, title: str, file_path: str, platform: str) -> dict:
        """新增一条下载记录并写回文件，返回新记录。"""
        record = {
            "id": uuid.uuid4().hex,
            "url": url,
            "title": title,
            "file_path": file_path,
            "platform": platform,
            "time": datetime.now().isoformat(),
        }
        with self._lock:
            records = self._read_all()
            records.append(record)
            self._write_all(records)
        return record

    def get_history(self, limit: int = 50, offset: int = 0) -> dict:
        """分页查询历史，按时间倒序返回 {total, items}。"""
        with self._lock:
            records = self._read_all()
        records.sort(key=lambda x: x.get("time", ""), reverse=True)
        total = len(records)
        if offset < 0:
            offset = 0
        if limit < 0:
            limit = 0
        items = records[offset:offset + limit]
        return {"total": total, "items": items}

    def delete_record(self, record_id: str) -> bool:
        """按 id 删除历史记录并删除对应本地文件，返回是否删除成功。"""
        with self._lock:
            records = self._read_all()
            target = None
            for i, r in enumerate(records):
                if r.get("id") == record_id:
                    target = records.pop(i)
                    break
            if target is None:
                return False
            self._write_all(records)
        # 删除对应本地文件（不存在则忽略）
        file_path = target.get("file_path")
        if file_path:
            try:
                os.remove(file_path)
            except OSError:
                pass
        return True

    def delete_records(self, record_ids: list[str]) -> int:
        """批量删除历史记录及对应本地文件，返回实际删除数量。"""
        if not record_ids:
            return 0
        id_set = set(record_ids)
        deleted_files: list[str] = []
        with self._lock:
            records = self._read_all()
            kept = []
            for r in records:
                if r.get("id") in id_set:
                    fp = r.get("file_path")
                    if fp:
                        deleted_files.append(fp)
                else:
                    kept.append(r)
            actual_deleted = len(records) - len(kept)
            if actual_deleted > 0:
                self._write_all(kept)
        # 锁外删除文件，避免长时间持锁
        for file_path in deleted_files:
            try:
                os.remove(file_path)
            except OSError:
                pass
        return actual_deleted

    def get_files(self) -> list[dict]:
        """扫描下载目录，返回文件列表 [{name, size, path, mtime}]，排除 .gitkeep。"""
        files: list[dict] = []
        if not DOWNLOAD_DIR.exists():
            return files
        for entry in DOWNLOAD_DIR.iterdir():
            if not entry.is_file():
                continue
            if entry.name == ".gitkeep":
                continue
            try:
                stat = entry.stat()
            except OSError:
                continue
            files.append({
                "name": entry.name,
                "size": stat.st_size,
                "path": str(entry),
                "mtime": stat.st_mtime,
            })
        # 按修改时间倒序
        files.sort(key=lambda x: x["mtime"], reverse=True)
        return files

    def _read_all(self) -> list:
        """读取历史 JSON，文件不存在或解析失败返回 []。"""
        if not HISTORY_FILE.exists():
            return []
        try:
            with open(HISTORY_FILE, "r", encoding="utf-8") as f:
                data = json.load(f)
        except (json.JSONDecodeError, OSError):
            return []
        if isinstance(data, list):
            return data
        return []

    def _write_all(self, records: list) -> None:
        """写入历史 JSON，ensure_ascii=False, indent=2。"""
        HISTORY_FILE.parent.mkdir(parents=True, exist_ok=True)
        with open(HISTORY_FILE, "w", encoding="utf-8") as f:
            json.dump(records, f, ensure_ascii=False, indent=2)


# 模块级单例
history_manager = HistoryManager()
