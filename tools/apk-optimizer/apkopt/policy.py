"""条目处置策略与硬性护栏。

后处理阶段只能触碰 ZIP 容器的封装形式（存储方式、对齐、填充），
一旦改写条目字节就会破坏构建工具链已经完成的优化，因此这里把
"不允许改写"的条目独立成护栏，供优化器在动手前统一拦截。
"""

from __future__ import annotations

import re
from dataclasses import dataclass

_DEX = re.compile(r"^classes\d*\.dex$")
_NATIVE_LIB = re.compile(r"^lib/[^/]+/.+\.so$")

# ---------------------------------------------------------------- 受保护条目


@dataclass(frozen=True)
class Protection:
    """受保护条目的拦截原因与处置指引。"""

    reason: str
    guidance: str


_PROTECTION_MANIFEST = Protection(
    reason="二进制 XML，改动需经 aapt2 重新编译并重编号资源引用",
    guidance="在 app/build.gradle.kts 中调整 AndroidManifest 配置后重新构建",
)

_PROTECTION_RESOURCES = Protection(
    reason="资源表为编译产物，条目顺序与字符串池偏移被运行时直接索引",
    guidance="通过资源缩减或资源限定符在构建期裁剪",
)

_PROTECTION_DEX = Protection(
    reason="R8 已按启动配置重排 DEX 布局并生成 Baseline Profile，外部改写会使优化失效甚至性能回退",
    guidance="在构建期通过 R8 配置、keep 规则与 Startup Profile 调整",
)

_PROTECTION_NATIVE = Protection(
    reason="需保持未压缩并按页对齐以便 mmap 直接加载，重新压缩会强制运行时解压并破坏 16 KiB 页对齐",
    guidance="在构建期通过 ABI 拆分与 NDK 编译参数控制体积",
)


def protection_of(name: str) -> Protection | None:
    """返回条目的保护信息；不受保护时返回 None。"""
    if name == "AndroidManifest.xml":
        return _PROTECTION_MANIFEST
    if name == "resources.arsc":
        return _PROTECTION_RESOURCES
    if _DEX.match(name):
        return _PROTECTION_DEX
    if _NATIVE_LIB.match(name):
        return _PROTECTION_NATIVE
    return None


# ---------------------------------------------------------------- 压缩策略

# 已是熵编码容器的格式：deflate 无从压缩，只会平白增加解压开销。
# 判定范围与 Android 打包工具一致，避免在这一层引入新的偏差。
_ALREADY_ENCODED_EXT = frozenset(
    {
        # 位图与矢量图
        ".png", ".jpg", ".jpeg", ".gif", ".webp", ".heic", ".heif", ".avif",
        # 音频
        ".mp3", ".m4a", ".aac", ".ogg", ".oga", ".opus", ".flac", ".amr", ".mid",
        # 视频
        ".mp4", ".m4v", ".3gp", ".mkv", ".webm", ".ts",
        # 字体
        ".ttf", ".otf", ".woff", ".woff2", ".ttc",
        # 已压缩的归档
        ".zip", ".jar", ".apk", ".gz", ".bz2", ".xz", ".7z", ".br", ".zst",
    }
)

# 运行时按固定偏移 mmap 的条目：无论扩展名如何都必须保持未压缩存储。
_MUST_STORE = re.compile(r"^(classes\d*\.dex|resources\.arsc)$")


def should_store(name: str) -> bool:
    """判断条目是否应当以未压缩方式存储。"""
    if _MUST_STORE.match(name) or _NATIVE_LIB.match(name):
        return True
    dot = name.rfind(".")
    if dot < 0:
        return False
    return name[dot:].lower() in _ALREADY_ENCODED_EXT
