"""APK 容器的只读视图。

只解析 ZIP 中央目录，不读取条目内容，供体积归因与压缩策略判定使用。
"""

from __future__ import annotations

import zipfile
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path

STORED = zipfile.ZIP_STORED
DEFLATED = zipfile.ZIP_DEFLATED

_METHOD_NAMES = {STORED: "STORED", DEFLATED: "DEFLATE"}


@dataclass(frozen=True)
class Entry:
    """中央目录中的单条记录。"""

    name: str
    method: int
    raw_size: int
    compressed_size: int
    crc: int

    @property
    def is_deflated(self) -> bool:
        return self.method == DEFLATED

    @property
    def method_name(self) -> str:
        return _METHOD_NAMES.get(self.method, f"METHOD_{self.method}")

    @property
    def stored_size(self) -> int:
        """该条目在 APK 中实际占用的字节数。"""
        return self.raw_size if self.method == STORED else self.compressed_size


@dataclass
class Bucket:
    """按顶层路径聚合的体积分组。"""

    label: str
    entries: int = 0
    raw_size: int = 0
    stored_size: int = 0


@dataclass
class Apk:
    """一个已构建 APK 的容器视图。"""

    path: Path
    size: int
    entries: list[Entry] = field(default_factory=list)

    @classmethod
    def open(cls, path: Path) -> "Apk":
        with zipfile.ZipFile(path) as zf:
            entries = [
                Entry(
                    name=info.filename,
                    method=info.compress_type,
                    raw_size=info.file_size,
                    compressed_size=info.compress_size,
                    crc=info.CRC,
                )
                for info in zf.infolist()
                if not info.is_dir()
            ]
        return cls(path=path, size=path.stat().st_size, entries=entries)

    @property
    def content_size(self) -> int:
        return sum(e.stored_size for e in self.entries)

    @property
    def container_overhead(self) -> int:
        """文件体积中除条目数据外的部分：中央目录、本地头、签名块与填充。"""
        return max(self.size - self.content_size, 0)

    def buckets(self) -> list[Bucket]:
        grouped: dict[str, Bucket] = {}
        for entry in self.entries:
            label = _bucket_label(entry.name)
            bucket = grouped.setdefault(label, Bucket(label=label))
            bucket.entries += 1
            bucket.raw_size += entry.raw_size
            bucket.stored_size += entry.stored_size
        return sorted(grouped.values(), key=lambda b: b.stored_size, reverse=True)

    def largest(self, count: int = 15) -> list[Entry]:
        return sorted(self.entries, key=lambda e: e.stored_size, reverse=True)[:count]

    def duplicate_groups(self) -> list[tuple[int, int, list[str]]]:
        """按（原始大小, CRC32）归并出的疑似重复条目。

        同一份数据被打进多个路径在依赖合并后很常见，删除它们无需改动
        任何被引用的资源，是后处理阶段少数真正无损的体积收益来源。
        """
        grouped: dict[tuple[int, int], list[str]] = defaultdict(list)
        for entry in self.entries:
            grouped[(entry.raw_size, entry.crc)].append(entry.name)
        return [
            (size, crc, names)
            for (size, crc), names in grouped.items()
            if len(names) > 1
        ]


def _bucket_label(name: str) -> str:
    if "/" not in name:
        return name
    head, _, _ = name.partition("/")
    if head == "lib":
        return "lib/**/*.so"
    return f"{head}/"
