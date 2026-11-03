"""命令行入口：参数解析与命令分发。"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from . import report
from .advisor import Advice, advise, load_config
from .analyzer import Analysis, analyze
from .optimizer import (
    ALL_SIGNING_SCHEMES,
    DEFAULT_SIGNING_SCHEMES,
    MIN_SAVINGS_BYTES,
    OptimizeError,
    SigningConfig,
    optimize,
)
from .recompress import build_plan
from .sdk import Toolchain, ToolchainError, discover

# 常见的 release 产物落点，按 Gradle 默认布局排列
_DEFAULT_RELEASE_DIRS = (
    "app/build/outputs/apk/release",
    "app/release",
    "release",
)

EXIT_OK = 0
EXIT_ERROR = 1
EXIT_CHECK_FAILED = 2


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="apkopt",
        description="对已构建的 APK 做体积归因、安全重压缩、16 KiB 页对齐与重新签名。",
    )
    sub = parser.add_subparsers(dest="command")

    # 每个子命令都接受同一组通用参数，避免参数顺序影响可用性
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--sdk", type=Path, help="Android SDK 根目录")
    common.add_argument(
        "--build-tools", type=Path, help="build-tools 目录（优先于 --sdk）"
    )
    common.add_argument("--json", type=Path, help="把结构化结果写入该 JSON 文件")
    common.add_argument("--md", type=Path, help="把报告写入该 Markdown 文件")
    common.add_argument("apks", nargs="*", help="APK 文件或目录")
    common.add_argument("--dir", type=Path, help="搜索 release 产物的目录")

    sub.add_parser("analyze", parents=[common], help="体检：体积归因、对齐、签名、可回收体积")

    optimize_cmd = sub.add_parser(
        "optimize", parents=[common], help="执行后处理优化并重新签名"
    )
    optimize_cmd.add_argument("--out", type=Path, help="输出目录，默认 <APK 所在目录>/optimized")
    optimize_cmd.add_argument("--dry-run", action="store_true", help="只计算收益，不写文件")
    optimize_cmd.add_argument(
        "--force", action="store_true", help="收益低于阈值也执行重压缩"
    )
    optimize_cmd.add_argument(
        "--min-savings",
        type=int,
        default=MIN_SAVINGS_BYTES,
        help=f"触发重压缩的最小收益字节数，默认 {MIN_SAVINGS_BYTES}",
    )
    optimize_cmd.add_argument(
        "--keystore", type=Path, help="签名密钥库，默认取项目根目录下的 .jks/.keystore"
    )
    optimize_cmd.add_argument(
        "--signing-schemes",
        type=_parse_schemes,
        default=",".join(DEFAULT_SIGNING_SCHEMES),
        metavar="v1,v2,v3|auto",
        help=f"签名方案，默认 {','.join(DEFAULT_SIGNING_SCHEMES)}；auto 表示沿用源包已有方案",
    )
    optimize_cmd.add_argument("--no-realign", action="store_true", help="跳过对齐阶段")

    verify_cmd = sub.add_parser("verify", parents=[common], help="CI 断言：对齐、签名、体积预算")
    verify_cmd.add_argument("--max-size", type=int, help="单包体积上限（字节）")
    verify_cmd.add_argument(
        "--max-reclaimable",
        type=int,
        default=None,
        help="允许的后处理可回收体积上限（字节）",
    )
    verify_cmd.add_argument(
        "--require-baseline-profile", action="store_true", help="要求产物内置 baseline.prof"
    )

    advise_cmd = sub.add_parser("advise", parents=[common], help="对照官方指南检查构建期配置")
    advise_cmd.add_argument("--project", type=Path, help="项目根目录，默认为当前目录向上查找")

    audit_cmd = sub.add_parser(
        "audit", parents=[common], help="体检 + 构建期检查，一次跑完只读部分"
    )
    audit_cmd.add_argument("--project", type=Path, help="项目根目录")

    return parser


def main(argv: list[str] | None = None) -> int:
    _force_utf8_stdout()
    parser = build_parser()
    args = parser.parse_args(argv)

    if not args.command:
        parser.print_help()
        return EXIT_OK

    try:
        toolchain = discover(sdk_dir=args.sdk, build_tools_dir=args.build_tools)
    except ToolchainError as error:
        print(f"错误：{error}", file=sys.stderr)
        return EXIT_ERROR

    handlers = {
        "analyze": _cmd_analyze,
        "audit": _cmd_audit,
        "optimize": _cmd_optimize,
        "verify": _cmd_verify,
        "advise": _cmd_advise,
    }
    try:
        return handlers[args.command](args, toolchain)
    except (ToolchainError, OptimizeError, FileNotFoundError) as error:
        print(f"错误：{error}", file=sys.stderr)
        return EXIT_ERROR


def _parse_schemes(value: str) -> str:
    """校验 --signing-schemes 取值，保留原文交由分发阶段解释。"""
    text = value.strip().lower()
    if text in ("", "auto", "preserve"):
        return "auto"
    names = [part.strip() for part in text.split(",") if part.strip()]
    unknown = [name for name in names if name not in ALL_SIGNING_SCHEMES]
    if unknown:
        raise argparse.ArgumentTypeError(
            f"未知签名方案 {'、'.join(unknown)}；"
            f"可选 {'、'.join(ALL_SIGNING_SCHEMES)}，或用 auto 沿用源包"
        )
    return ",".join(dict.fromkeys(names))


def _resolve_schemes(choice: str, analysis: Analysis) -> tuple[str, ...]:
    if choice != "auto":
        return tuple(choice.split(","))
    return _source_schemes(analysis) or DEFAULT_SIGNING_SCHEMES


def _source_schemes(analysis: Analysis) -> tuple[str, ...]:
    existing = analysis.signature.effective_schemes if analysis.signature else []
    return tuple(s for s in existing if s in ALL_SIGNING_SCHEMES)


def _needs_resign(schemes: tuple[str, ...], analysis: Analysis) -> bool:
    existing = _source_schemes(analysis)
    return bool(existing) and set(schemes) != set(existing)


def _advices(root: Path, analysis: Analysis | None) -> list[Advice]:
    config = load_config(root)
    return advise(
        config,
        min_sdk=analysis.min_sdk if analysis else None,
        has_baseline_profile=bool(analysis and analysis.baseline_profile),
        has_startup_profile=config.startup_profile_generated,
        aligned=analysis.alignment.ok if analysis else None,
    )


def _cmd_analyze(args: argparse.Namespace, toolchain: Toolchain) -> int:
    apks = _resolve(args)
    analyses: list[Analysis] = []
    for apk in apks:
        analysis = analyze(apk, toolchain)
        analyses.append(analysis)
        print(report.render_analysis(analysis))
        print()

    if args.md:
        advices = _advices(_project_root(None), analyses[0] if analyses else None)
        args.md.parent.mkdir(parents=True, exist_ok=True)
        args.md.write_text(
            "\n\n".join(report.to_markdown(a, advices) for a in analyses),
            encoding="utf-8",
        )
        print(f"报告已写入 {args.md}")

    _dump_json(args, {"analyses": [a.to_dict() for a in analyses]})
    return EXIT_OK


def _cmd_audit(args: argparse.Namespace, toolchain: Toolchain) -> int:
    root = _project_root(args.project)
    apks = _resolve(args)
    analyses = [analyze(apk, toolchain) for apk in apks]

    for analysis in analyses:
        print(report.render_analysis(analysis))
        print()

    advices = _advices(root, analyses[0] if analyses else None)
    print(report.render_advice(advices))

    if args.md:
        args.md.parent.mkdir(parents=True, exist_ok=True)
        args.md.write_text(
            "\n\n".join(report.to_markdown(a, advices) for a in analyses), encoding="utf-8"
        )
        print(f"\n报告已写入 {args.md}")

    _dump_json(
        args,
        {
            "analyses": [a.to_dict() for a in analyses],
            "advice": [
                {
                    "key": a.key,
                    "title": a.title,
                    "status": a.status,
                    "detail": a.detail,
                    "doc": a.doc,
                }
                for a in advices
            ],
        },
    )
    return EXIT_OK


def _cmd_optimize(args: argparse.Namespace, toolchain: Toolchain) -> int:
    apks = _resolve(args)
    failures = 0
    for apk in apks:
        plan = build_plan(apk)
        analysis = analyze(apk, toolchain, plan)

        schemes = _resolve_schemes(args.signing_schemes, analysis)
        # 目标方案与源包不同时必须重签，否则产物仍带着旧方案
        resign = _needs_resign(schemes, analysis)

        signing = None
        if not args.dry_run:
            signing = SigningConfig.from_project(apk.parent, args.keystore, schemes)

        result = optimize(
            apk,
            args.out or apk.parent / "optimized",
            toolchain,
            signing,
            plan=plan,
            dry_run=args.dry_run,
            min_savings=args.min_savings,
            force=args.force or resign,
            realign=False if args.no_realign else None,
        )
        print(report.render_optimize(result))
        if resign:
            source = "、".join(s.upper() for s in _source_schemes(analysis)) or "无"
            print(
                f"  签名方案由 {source} 改为 {' + '.join(s.upper() for s in schemes)}，"
                "因此即使无体积收益也重新签名"
            )
        print()
        if result.warning:
            failures += 1

    return EXIT_CHECK_FAILED if failures else EXIT_OK


def _cmd_verify(args: argparse.Namespace, toolchain: Toolchain) -> int:
    apks = _resolve(args)
    problems: list[str] = []

    for apk in apks:
        analysis = analyze(apk, toolchain)
        local: list[str] = []

        if not analysis.alignment.ok:
            local.append(
                f"ZIP 未按 4 字节 / 16 KiB 页对齐（{len(analysis.alignment.offenders)} 条违规）"
            )
        if analysis.signature is None or not analysis.signature.verified:
            local.append("签名校验未通过")
        if args.max_size is not None and analysis.apk.size > args.max_size:
            local.append(
                f"体积 {report.size(analysis.apk.size)} 超过上限 {report.size(args.max_size)}"
            )
        if args.max_reclaimable is not None and analysis.reclaimable > args.max_reclaimable:
            local.append(
                f"后处理可回收 {report.size(analysis.reclaimable)}"
                f" 超过上限 {report.size(args.max_reclaimable)}"
            )
        if args.require_baseline_profile and not analysis.baseline_profile:
            local.append("缺少 baseline.prof")

        print(
            f"{apk.name}  {report.size(analysis.apk.size)}  "
            f"对齐={'ok' if analysis.alignment.ok else 'fail'}  "
            f"签名={'ok' if analysis.signature and analysis.signature.verified else 'fail'}  "
            f"→ {'通过' if not local else '未通过'}"
        )
        problems.extend(f"{apk.name}：{item}" for item in local)

    if problems:
        print()
        for problem in problems:
            print(f"  ✗ {problem}")
        return EXIT_CHECK_FAILED
    return EXIT_OK


def _cmd_advise(args: argparse.Namespace, toolchain: Toolchain) -> int:
    root = _project_root(args.project)
    apks = _resolve(args, required=False)
    analysis = analyze(apks[0], toolchain) if apks else None

    advices = _advices(root, analysis)
    print(report.render_advice(advices))

    if args.md:
        args.md.parent.mkdir(parents=True, exist_ok=True)
        args.md.write_text(report.render_advice_markdown(advices), encoding="utf-8")
        print(f"\n清单已写入 {args.md}")

    _dump_json(
        args,
        {
            "project": str(root),
            "advice": [
                {
                    "key": a.key,
                    "title": a.title,
                    "status": a.status,
                    "detail": a.detail,
                    "doc": a.doc,
                }
                for a in advices
            ],
        },
    )
    return EXIT_OK


def _resolve(args: argparse.Namespace, required: bool = True) -> list[Path]:
    """定位待处理 APK。"""
    if getattr(args, "apks", None):
        found: list[Path] = []
        for raw in args.apks:
            path = Path(raw)
            if path.is_dir():
                found.extend(sorted(path.glob("*.apk")))
            elif path.is_file():
                found.append(path)
            else:
                raise FileNotFoundError(f"路径不存在：{path}")
        if found:
            return found

    explicit_dir = getattr(args, "dir", None)
    if explicit_dir:
        found = sorted(Path(explicit_dir).glob("*.apk"))
        if not found:
            raise FileNotFoundError(f"目录中没有 APK：{explicit_dir}")
        return found

    for directory in _search_roots():
        for relative in _DEFAULT_RELEASE_DIRS:
            candidate = directory / relative
            if candidate.is_dir() and (apks := sorted(candidate.glob("*.apk"))):
                return apks

    if not required:
        return []
    searched = "、".join(_DEFAULT_RELEASE_DIRS)
    raise FileNotFoundError(
        f"未找到 release APK，已尝试 {searched}。请显式传入 APK 路径或使用 --dir。"
    )


def _search_roots() -> list[Path]:
    cwd = Path.cwd().resolve()
    return [cwd, *cwd.parents]


def _project_root(explicit: Path | None) -> Path:
    if explicit:
        return explicit.resolve()
    cwd = Path.cwd().resolve()
    for directory in (cwd, *cwd.parents):
        if (directory / "settings.gradle.kts").is_file() or (
            directory / "settings.gradle"
        ).is_file():
            return directory
    return cwd


def _dump_json(args: argparse.Namespace, payload: dict) -> None:
    target = getattr(args, "json", None)
    if not target:
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"结构化结果已写入 {target}")


def _force_utf8_stdout() -> None:
    """Windows 控制台默认代码页无法输出中文与制表符，这里统一切到 UTF-8。"""
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is not None:
            try:
                reconfigure(encoding="utf-8", errors="replace")
            except (ValueError, OSError):
                pass
