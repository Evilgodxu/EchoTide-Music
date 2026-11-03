"""逐条决定存储方式，并在内存中实测目标体积。

体检与优化共用同一份决策结果，避免"报告说能省、执行后却没省"这类
预估与实现脱节的情况。
"""

from __future__ import annotations

import zipfile
import zlib
from dataclasses import dataclass
from pathlib import Path

from . import policy
from .apk import DEFLATED, STORED

# zlib 9 为最高压缩级别，收益递减但解压开销不变，适合作为交付产物的目标级别
DEFLATE_LEVEL = 9


@dataclass(frozen=True)
class EntryDecision:
    """单个条目的存储方式决策。"""

    name: str
    protected: bool
    current_method: int
    current_size: int
    target_method: int
    target_size: int
    reason: str

    @property
    def changed(self) -> bool:
        return self.target_size != self.current_size

    @property
    def saved(self) -> int:
        return self.current_size - self.target_size


@dataclass
class RecompressPlan:
    """整包的重压缩方案。"""

    path: Path
    decisions: list[EntryDecision]

    @property
    def current_total(self) -> int:
        return sum(d.current_size for d in self.decisions)

    @property
    def target_total(self) -> int:
        return sum(d.target_size for d in self.decisions)

    @property
    def savings(self) -> int:
        return max(self.current_total - self.target_total, 0)

    @property
    def changed_entries(self) -> list[EntryDecision]:
        return [d for d in self.decisions if d.changed]


def deflate_size(data: bytes, level: int = DEFLATE_LEVEL) -> int:
    """测量一段数据经 deflate 后的体积，使用与 ZIP 相同的原始 deflate 流。"""
    compressor = zlib.compressobj(level, zlib.DEFLATED, -15)
    return len(compressor.compress(data)) + len(compressor.flush())


def build_plan(apk_path: Path) -> RecompressPlan:
    """读取每个条目并给出目标存储方式。"""
    decisions: list[EntryDecision] = []
    with zipfile.ZipFile(apk_path) as zf:
        for info in zf.infolist():
            if info.is_dir():
                continue
            decisions.append(_decide(zf, info))
    return RecompressPlan(path=apk_path, decisions=decisions)


def _decide(zf: zipfile.ZipFile, info: zipfile.ZipInfo) -> EntryDecision:
    name = info.filename
    current_size = info.file_size if info.compress_type == STORED else info.compress_size

    protection = policy.protection_of(name)
    if protection is not None:
        return EntryDecision(
            name=name,
            protected=True,
            current_method=info.compress_type,
            current_size=current_size,
            target_method=info.compress_type,
            target_size=current_size,
            reason=protection.reason,
        )

    raw = zf.read(name)
    stored_size = len(raw)

    if policy.should_store(name):
        reason = "已是熵编码格式，保持未压缩存储"
        if info.compress_type == DEFLATED and current_size > stored_size:
            reason = "已编码格式不应 deflate，改回未压缩存储"
        return EntryDecision(
            name=name,
            protected=False,
            current_method=info.compress_type,
            current_size=current_size,
            target_method=STORED,
            target_size=stored_size,
            reason=reason,
        )

    deflated_size = deflate_size(raw)
    if deflated_size < stored_size:
        target_method, target_size = DEFLATED, deflated_size
        reason = "deflate 有明显收益"
    else:
        target_method, target_size = STORED, stored_size
        reason = "deflate 无收益，改回未压缩存储以减少解压开销"

    if info.compress_type == DEFLATED and target_method == DEFLATED:
        reason = f"已 deflate，压缩级别提升至 {DEFLATE_LEVEL}"

    return EntryDecision(
        name=name,
        protected=False,
        current_method=info.compress_type,
        current_size=current_size,
        target_method=target_method,
        target_size=target_size,
        reason=reason,
    )
