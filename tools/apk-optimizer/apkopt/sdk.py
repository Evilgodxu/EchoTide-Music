"""Android SDK 定位与原生工具封装。

对齐、签名、资源解析等能力一律委托 SDK 自带工具，避免在工具内部
重复实现与平台语义等价但难以同步的逻辑。
"""

from __future__ import annotations

import os
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

_VERSION_DIR = re.compile(r"^(\d+)\.(\d+)\.(\d+)$")
# 与官方推荐值一致：APK 条目基础对齐 4 字节，未压缩 .so 按 16 KiB 页对齐，
# 该组合同时兼容 4 KiB 与 16 KiB 页设备。
ZIP_ALIGNMENT = 4
PAGE_ALIGNMENT_KIB = 16


class ToolchainError(RuntimeError):
    """SDK 或构建工具不可用。"""


@dataclass(frozen=True)
class Toolchain:
    sdk_dir: Path
    build_tools: Path
    version: str
    zipalign: Path
    apksigner: Path
    aapt2: Path

    def run(
        self, args: list[str], *, env: dict[str, str] | None = None
    ) -> subprocess.CompletedProcess[str]:
        """执行工具并返回结果，输出统一解码为文本。"""
        result = subprocess.run(
            _executable(args),
            capture_output=True,
            check=False,
            env={**os.environ, **env} if env else None,
        )
        return subprocess.CompletedProcess(
            result.args,
            result.returncode,
            _decode(result.stdout or b""),
            _decode(result.stderr or b""),
        )


def _executable(args: list[str]) -> list[str]:
    """在 Windows 上以 cmd 包装批处理脚本，否则直接执行。"""
    head = str(args[0])
    if sys.platform == "win32" and head.lower().endswith(".bat"):
        return [os.environ.get("COMSPEC", "cmd.exe"), "/c", *[str(a) for a in args]]
    return [str(a) for a in args]


def _candidate_sdk_dirs(explicit: Path | None, start: Path) -> list[Path]:
    candidates: list[Path] = []
    if explicit:
        candidates.append(explicit)

    for key in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        value = os.environ.get(key)
        if value:
            candidates.append(Path(value))

    # 沿项目目录上溯读取 local.properties，与 Gradle 的解析范围保持一致
    for directory in (start, *start.parents):
        props = directory / "local.properties"
        sdk = _sdk_from_local_properties(props)
        if sdk:
            candidates.append(sdk)
            break

    home = Path.home()
    candidates.extend(
        [
            home / "AppData/Local/Android/Sdk",
            home / "Library/Android/sdk",
            home / "Android/Sdk",
        ]
    )
    return candidates


def _sdk_from_local_properties(path: Path) -> Path | None:
    if not path.is_file():
        return None
    try:
        content = path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return None
    for line in content.splitlines():
        key, sep, value = line.partition("=")
        if sep and key.strip() == "sdk.dir":
            # Properties 转义：冒号与反斜杠在 Windows 路径中会被转义
            raw = value.strip().replace("\\:", ":").replace("\\\\", "\\")
            return Path(raw)
    return None


def _build_tools_versions(root: Path) -> list[tuple[tuple[int, int, int], Path]]:
    if not root.is_dir():
        return []
    versions: list[tuple[tuple[int, int, int], Path]] = []
    for child in root.iterdir():
        match = _VERSION_DIR.match(child.name)
        if match and child.is_dir():
            versions.append((tuple(int(g) for g in match.groups()), child))
    return sorted(versions, key=lambda item: item[0], reverse=True)


def discover(
    sdk_dir: Path | None = None,
    build_tools_dir: Path | None = None,
    start_dir: Path | None = None,
) -> Toolchain:
    """定位可用的 build-tools，优先使用版本号最高的一个。"""
    start = (start_dir or Path.cwd()).resolve()

    if build_tools_dir:
        resolved = _materialize(build_tools_dir, sdk_dir, start)
    elif sdk_dir:
        resolved = _pick_build_tools(sdk_dir)
    else:
        resolved = None
        for candidate in _candidate_sdk_dirs(None, start):
            if candidate.is_dir():
                resolved = _pick_build_tools(candidate)
                if resolved:
                    break

    if resolved is None:
        raise ToolchainError(
            "未找到 Android SDK build-tools。请通过 --sdk 或 --build-tools 指定，"
            "或设置 ANDROID_HOME / 在 local.properties 中声明 sdk.dir。"
        )

    build_tools, sdk_root = resolved
    return Toolchain(
        sdk_dir=sdk_root,
        build_tools=build_tools,
        version=build_tools.name,
        zipalign=_locate(build_tools, "zipalign"),
        apksigner=_locate(build_tools, "apksigner"),
        aapt2=_locate(build_tools, "aapt2"),
    )


def _materialize(
    build_tools_dir: Path, sdk_dir: Path | None, start: Path
) -> tuple[Path, Path] | None:
    if not build_tools_dir.is_dir():
        raise ToolchainError(f"build-tools 目录不存在：{build_tools_dir}")
    if sdk_dir is None:
        for candidate in _candidate_sdk_dirs(None, start):
            if candidate.is_dir():
                sdk_dir = candidate
                break
    return build_tools_dir, sdk_dir or build_tools_dir.parent


def _pick_build_tools(sdk_root: Path) -> tuple[Path, Path] | None:
    versions = _build_tools_versions(sdk_root / "build-tools")
    if not versions:
        return None
    return versions[0][1], sdk_root


def _decode(raw: bytes) -> str:
    """按工具实际使用的编码解码输出。

    构建工具会混用 UTF-8 与平台区域编码（例如 apksigner 以系统 ANSI 代码页
    输出证书主题），因此按 UTF-8 → 平台编码 → Latin-1 依次回退。
    """
    for encoding in ("utf-8", "mbcs", "latin-1"):
        try:
            return raw.decode(encoding)
        except (UnicodeDecodeError, LookupError):
            continue
    return raw.decode("utf-8", "replace")


def _locate(directory: Path, stem: str) -> Path:
    """按当前平台的扩展名习惯查找工具。

    Windows 上 zipalign/aapt2 是可执行文件，apksigner 则是批处理包装脚本。
    """
    suffixes = (".exe", ".bat", ".cmd", "") if sys.platform == "win32" else ("",)
    for suffix in suffixes:
        candidate = directory / f"{stem}{suffix}"
        if candidate.is_file():
            return candidate
    raise ToolchainError(f"缺少构建工具：{directory / stem}")
