"""APK 后处理优化流水线。

顺序与官方要求一致：先重压缩并重新对齐，再签名；任何改动之后都必须
重新签名，否则产物无法安装。
"""

from __future__ import annotations

import copy
import shutil
import zipfile
from dataclasses import dataclass, field
from pathlib import Path

from .recompress import DEFLATE_LEVEL, RecompressPlan, build_plan
from .sdk import PAGE_ALIGNMENT_KIB, ZIP_ALIGNMENT, Toolchain

# 收益低于该阈值时不值得重写产物：重打包会更换签名块，收益必须盖过风险
MIN_SAVINGS_BYTES = 4096

# v2 兼容 Android 7.0（API 24）及以上，可覆盖本项目 minSdk；v1 仅服务于
# Android 6.0 及以下，v3 提供密钥轮换能力。默认只签 v2。
ALL_SIGNING_SCHEMES = ("v1", "v2", "v3")
DEFAULT_SIGNING_SCHEMES = ("v2",)

_KS_PASS_ENV = "APKOPT_KS_PASS"
_KEY_PASS_ENV = "APKOPT_KEY_PASS"


class OptimizeError(RuntimeError):
    """优化流程失败。"""


@dataclass(frozen=True)
class SigningConfig:
    """签名材料。密码只经环境变量传递，不出现在命令行参数中。"""

    keystore: Path
    store_password: str
    key_alias: str
    key_password: str
    schemes: tuple[str, ...] = DEFAULT_SIGNING_SCHEMES

    @classmethod
    def from_project(
        cls,
        start_dir: Path,
        keystore: Path | None = None,
        schemes: tuple[str, ...] = DEFAULT_SIGNING_SCHEMES,
    ) -> "SigningConfig":
        root = _find_project_root(start_dir)
        if root is None:
            raise OptimizeError(
                "未找到包含 local.properties 的项目根目录，无法读取签名配置。"
                "请用 --keystore 指定密钥库。"
            )
        properties = _read_properties(root / "local.properties")
        store = keystore or _find_keystore(root)
        if store is None:
            raise OptimizeError(f"在 {root} 下未找到 .jks/.keystore 文件，请用 --keystore 指定。")

        store_password = properties.get("KEYSTORE_PASSWORD")
        key_password = properties.get("KEY_PASSWORD")
        if not store_password or not key_password:
            raise OptimizeError(
                "local.properties 缺少 KEYSTORE_PASSWORD / KEY_PASSWORD，无法签名。"
            )
        return cls(
            keystore=store,
            store_password=store_password,
            key_alias=properties.get("KEY_ALIAS", "release"),
            key_password=key_password,
            schemes=schemes,
        )

    def apksigner_args(self) -> tuple[list[str], dict[str, str]]:
        """返回签名参数与需要注入的环境变量。

        v1/v2/v3 全部显式声明，结果不随 apksigner 默认策略变化；v4 只服务于
        增量安装，普通 APK 不需要，开启会额外产出一个 .idsig 文件。
        """
        args = [
            "sign",
            "--ks",
            str(self.keystore),
            "--ks-key-alias",
            self.key_alias,
            "--ks-pass",
            f"env:{_KS_PASS_ENV}",
            "--key-pass",
            f"env:{_KEY_PASS_ENV}",
        ]
        for name in ALL_SIGNING_SCHEMES:
            enabled = "true" if name in self.schemes else "false"
            args.append(f"--{name}-signing-enabled={enabled}")
        args.append("--v4-signing-enabled=false")

        env = {
            _KS_PASS_ENV: self.store_password,
            _KEY_PASS_ENV: self.key_password,
        }
        return args, env


@dataclass
class OptimizeResult:
    source: Path
    output: Path
    skipped: bool
    skip_reason: str
    before_size: int
    after_size: int
    plan: RecompressPlan
    steps: list[str] = field(default_factory=list)
    verified: bool = False
    verified_aligned: bool = False
    warning: str = ""

    @property
    def saved(self) -> int:
        return max(self.before_size - self.after_size, 0)

    @property
    def entry_savings(self) -> int:
        return self.plan.savings

    @property
    def overhead_delta(self) -> int:
        """条目数据之外的体积变化：签名块与对齐填充。

        重签后签名块通常变小，而条目尺寸改变会让对齐填充重新分配，
        两者方向相反，单独列出才能如实解释总体积变化。
        """
        return self.saved - self.entry_savings


def optimize(
    apk_path: Path,
    out_dir: Path,
    tool: Toolchain,
    signing: SigningConfig | None,
    *,
    plan: RecompressPlan | None = None,
    dry_run: bool = False,
    min_savings: int = MIN_SAVINGS_BYTES,
    force: bool = False,
    realign: bool | None = None,
) -> OptimizeResult:
    """生成优化后的 APK。

    始终写入独立的输出目录，源文件保持原样，便于随时回退比较。
    realign 为 None 时先核验源包对齐状态，仅在对齐不合格时才重新对齐。
    """
    plan = plan or build_plan(apk_path)
    before = apk_path.stat().st_size

    if realign is None:
        realign = not _verify_alignment(tool, apk_path)

    needs_repack = plan.savings >= min_savings or force
    if not needs_repack and not realign:
        return OptimizeResult(
            source=apk_path,
            output=apk_path,
            skipped=True,
            skip_reason=(
                f"可回收体积 {plan.savings} 字节，低于阈值 {min_savings} 字节，"
                "且产物已按 4 字节与 16 KiB 页对齐，无需重写"
            ),
            before_size=before,
            after_size=before,
            plan=plan,
        )

    if signing is None:
        raise OptimizeError("重新打包后必须重新签名，请提供签名配置。")

    steps: list[str] = []
    work_dir = out_dir / ".work"
    staged = work_dir / "staged.apk"
    aligned = work_dir / "aligned.apk"

    steps.append(f"重新打包：改写 {len(plan.changed_entries)} 个条目的存储方式")
    steps.append(
        f"对齐：zipalign -P {PAGE_ALIGNMENT_KIB} 4（未压缩 .so 按 "
        f"{PAGE_ALIGNMENT_KIB} KiB 页边界，其余按 {ZIP_ALIGNMENT} 字节边界）"
    )
    steps.append(
        f"签名：apksigner {' + '.join(s.upper() for s in signing.schemes)} 方案"
    )

    if dry_run:
        return OptimizeResult(
            source=apk_path,
            output=out_dir / f"{apk_path.stem}-optimized.apk",
            skipped=False,
            skip_reason="试运行，未写入任何文件",
            before_size=before,
            after_size=max(before - plan.savings, 0),
            plan=plan,
            steps=steps,
        )

    out_dir.mkdir(parents=True, exist_ok=True)
    output = out_dir / f"{apk_path.stem}-optimized.apk"
    work_dir.mkdir(exist_ok=True)

    # 无需重压缩时直接在源包上对齐：zipalign 只调整本地头 extra 字段，
    # 数据布局保持不变，是改动面最小的路径
    align_source = apk_path
    if needs_repack:
        _repack(apk_path, staged, plan)
        align_source = staged
    _zipalign(tool, align_source, aligned)
    _sign(tool, aligned, output, signing)

    after = output.stat().st_size
    if after > before:
        # 重新打包反而更大：保留源产物，避免把用户推向更差的结果
        output.unlink(missing_ok=True)
        shutil.rmtree(work_dir, ignore_errors=True)
        return OptimizeResult(
            source=apk_path,
            output=apk_path,
            skipped=True,
            skip_reason=f"优化后体积 {after} 字节大于原始 {before} 字节，已放弃输出",
            before_size=before,
            after_size=before,
            plan=plan,
            steps=steps,
        )

    verified = _verify_signature(tool, output)
    verified_aligned = _verify_alignment(tool, output)
    steps.append(
        "校验：apksigner verify 与 zipalign -c 均通过"
        if verified and verified_aligned
        else "校验：未全部通过，详见告警"
    )

    warning = ""
    if not verified:
        warning = "签名校验未通过，产物不可发布"
    elif not verified_aligned:
        warning = "对齐校验未通过，运行时将无法 mmap 未压缩条目"

    shutil.rmtree(work_dir, ignore_errors=True)

    return OptimizeResult(
        source=apk_path,
        output=output,
        skipped=False,
        skip_reason="",
        before_size=before,
        after_size=after,
        plan=plan,
        steps=steps,
        verified=verified,
        verified_aligned=verified_aligned,
        warning=warning,
    )


def _repack(source: Path, target: Path, plan: RecompressPlan) -> None:
    """按方案重写 ZIP 容器，条目顺序与权限属性保持与原包一致。"""
    decisions = {d.name: d for d in plan.decisions}
    with zipfile.ZipFile(source) as src, zipfile.ZipFile(
        target, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=DEFLATE_LEVEL
    ) as dst:
        for info in src.infolist():
            if info.is_dir():
                continue
            decision = decisions[info.filename]
            data = src.read(info.filename)

            entry = copy.copy(info)
            entry.compress_type = decision.target_method
            # 传入 ZipInfo 时 zipfile 不再采用归档级 compresslevel，
            # 必须逐条声明目标级别，否则会静默回退到 zlib 默认级别
            entry._compresslevel = DEFLATE_LEVEL
            entry.flag_bits = 0
            # 原有 extra 字段可能带有旧的对齐填充，交由 zipalign 统一重写
            entry.extra = b""
            dst.writestr(entry, data)


def _zipalign(tool: Toolchain, source: Path, target: Path) -> None:
    result = tool.run(
        [
            str(tool.zipalign),
            "-P",
            str(PAGE_ALIGNMENT_KIB),
            "-f",
            str(ZIP_ALIGNMENT),
            str(source),
            str(target),
        ]
    )
    if result.returncode != 0:
        raise OptimizeError(f"zipalign 失败：{result.stderr.strip() or result.stdout.strip()}")


def _sign(tool: Toolchain, source: Path, target: Path, signing: SigningConfig) -> None:
    args, env = signing.apksigner_args()
    result = tool.run(
        [str(tool.apksigner), *args, "--out", str(target), str(source)],
        env=env,
    )
    if result.returncode != 0:
        raise OptimizeError(f"apksigner 失败：{result.stderr.strip() or result.stdout.strip()}")


def _verify_signature(tool: Toolchain, apk: Path) -> bool:
    return tool.run([str(tool.apksigner), "verify", str(apk)]).returncode == 0


def _verify_alignment(tool: Toolchain, apk: Path) -> bool:
    return (
        tool.run(
            [
                str(tool.zipalign),
                "-c",
                "-P",
                str(PAGE_ALIGNMENT_KIB),
                str(ZIP_ALIGNMENT),
                str(apk),
            ]
        ).returncode
        == 0
    )


def _find_project_root(start: Path) -> Path | None:
    for directory in (start, *start.parents):
        if (directory / "local.properties").is_file():
            return directory
    return None


def _find_keystore(root: Path) -> Path | None:
    for pattern in ("*.jks", "*.keystore"):
        for candidate in sorted(root.glob(pattern)):
            if candidate.is_file():
                return candidate
    return None


def _read_properties(path: Path) -> dict[str, str]:
    if not path.is_file():
        return {}
    values: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        key, sep, value = line.partition("=")
        if sep:
            values[key.strip()] = value.strip()
    return values
