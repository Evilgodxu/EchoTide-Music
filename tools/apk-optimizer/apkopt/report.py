"""报告渲染：终端表格与 Markdown 输出。"""

from __future__ import annotations

import unicodedata

from .advisor import PASS, Advice
from .analyzer import Analysis
from .optimizer import OptimizeResult

_WIDTH = 78
_BAR_BLOCKS = "█▉▊▋▌▍▎▏  "
_FINDING_LIMIT = 8


def _width(text: str) -> int:
    """按终端显示宽度计算字符串长度，中日韩字符占两列。"""
    return sum(2 if unicodedata.east_asian_width(ch) in "WF" else 1 for ch in text)


def _pad(text: str, width: int, align: str = "left") -> str:
    filler = " " * max(width - _width(text), 0)
    return filler + text if align == "right" else text + filler


def _rule(title: str = "") -> str:
    if not title:
        return "─" * _WIDTH
    label = f" {title} "
    remaining = max(_WIDTH - _width(label) - 2, 0)
    return f"──{label}{'─' * remaining}"


def size(value: int) -> str:
    """以人类可读的单位表示字节数。"""
    if value >= 1024 * 1024:
        return f"{value / 1048576:.2f} MiB"
    if value >= 1024:
        return f"{value / 1024:.1f} KiB"
    return f"{value} B"


def render_analysis(analysis: Analysis) -> str:
    apk = analysis.apk
    lines = [
        _rule("APK 体检"),
        f"文件      {apk.path.name}",
        f"版本      {analysis.package_name} {analysis.version_name}"
        f"（minSdk {analysis.min_sdk if analysis.min_sdk is not None else '?'}）",
        f"体积      {size(apk.size)}   条目 {len(apk.entries)}   "
        f"容器开销 {size(apk.container_overhead)}",
        "",
        _rule("体积构成"),
    ]

    total = max(apk.content_size, 1)
    name_width = max(_width(b.label) for b in analysis.buckets)
    for bucket in analysis.buckets:
        ratio = bucket.stored_size / total
        bar = _bar(ratio)
        lines.append(
            f"  {_pad(bucket.label, name_width)}  {_pad(size(bucket.stored_size), 10, 'right')}"
            f"  {ratio * 100:5.1f}%  {bar}  ({bucket.entries})"
        )

    lines += ["", _rule("最大条目")]
    for entry in analysis.largest[:10]:
        lines.append(
            f"  {_pad(entry.name[:46], 46)}  {_pad(size(entry.stored_size), 10, 'right')}"
            f"  {entry.method_name}"
        )

    lines += ["", _rule("运行时加载条件")]
    align_verdict = "通过" if analysis.alignment.ok else "未通过"
    lines.append(
        f"  ZIP 对齐（4 字节 + 16 KiB 页）  {align_verdict}"
        + (f"  违规 {len(analysis.alignment.offenders)} 条" if analysis.alignment.offenders else "")
    )
    for name, verdict in analysis.alignment.offenders[:5]:
        lines.append(f"      {name}  →  {verdict}")

    if analysis.signature is None:
        lines.append("  签名                               未能读取")
    else:
        schemes = " ".join(
            f"{k}={'on' if v else 'off'}" for k, v in analysis.signature.schemes.items()
        )
        lines.append(f"  签名  {'通过' if analysis.signature.verified else '未通过'}  {schemes}")
        lines.append(f"      签名者  {analysis.signature.signer}")

    lines.append(
        "  Baseline Profile                   "
        + (
            f"已内置 {size(analysis.baseline_profile)}"
            if analysis.baseline_profile
            else "缺失"
        )
    )
    if analysis.profile_oversized:
        lines.append("      体积超过 1.5 MiB 上限，ART 将不会采用")

    lines += ["", _rule("后处理可回收体积")]
    lines.append(f"  {size(analysis.reclaimable)}   共 {len(analysis.findings)} 项存储方式偏差")
    ranked = sorted(analysis.findings, key=lambda f: f.saved, reverse=True)
    for finding in ranked[:_FINDING_LIMIT]:
        lines.append(
            f"      {_pad(_signed(finding.saved), 9, 'right')}  {_pad(finding.name[:38], 38)}"
            f"  {finding.reason}"
        )
    if len(ranked) > _FINDING_LIMIT:
        rest = sum(f.saved for f in ranked[_FINDING_LIMIT:])
        lines.append(f"      其余 {len(ranked) - _FINDING_LIMIT} 项合计 {_signed(rest)}")

    if analysis.stale_v1:
        lines.append(f"  v1 签名残留 {len(analysis.stale_v1)} 个条目，可在打包配置中排除")

    if analysis.duplicates:
        lines.append("")
        lines.append(_rule("疑似重复内容"))
        for raw, crc, names in analysis.duplicates[:5]:
            lines.append(f"  {size(raw)}  crc={crc:08x}  {' | '.join(names[:3])}")
    return "\n".join(lines)


def render_optimize(result: OptimizeResult) -> str:
    lines = [_rule("后处理优化"), f"输入  {result.source}"]
    if result.skipped:
        lines.append(f"结果  跳过优化：{result.skip_reason}")
        return "\n".join(lines)

    lines.append(f"输出  {result.output}")
    for step in result.steps:
        lines.append(f"  · {step}")
    lines.append(
        f"体积  {size(result.before_size)} → {size(result.after_size)}"
        f"  （减少 {size(result.saved)}，{result.saved / max(result.before_size, 1) * 100:.2f}%）"
    )
    overhead = result.overhead_delta
    lines.append(
        f"      条目数据 {_signed(result.entry_savings)}，"
        f"签名块与对齐填充 {_signed(overhead)}"
    )
    if result.verified:
        lines.append("  签名校验通过")
    if result.verified_aligned:
        lines.append("  对齐校验通过")
    if result.warning:
        lines.append(f"  告警：{result.warning}")
    return "\n".join(lines)


def _signed(value: int) -> str:
    """以带符号的形式表示增减量，负值表示体积反而增加。"""
    return f"-{size(value)}" if value > 0 else f"+{size(-value)}"


def render_advice(advices: list[Advice]) -> str:
    lines = [_rule("构建期优化清单（性能与体积的真正杠杆）")]
    marker = {"pass": "✓", "warn": "!", "miss": "✗"}
    for advice in advices:
        lines.append(f"  [{marker.get(advice.status, '?')}] {advice.title} — {advice.label}")
        lines.append(f"      {advice.detail}")
    lines.append("")
    lines.append("  说明：后处理只能改变打包形式，代码优化、资源裁剪与启动加速必须在构建期完成。")
    return "\n".join(lines)


def render_advice_markdown(advices: list[Advice]) -> str:
    lines = ["| 项 | 状态 | 说明 | 参考 |", "| --- | --- | --- | --- |"]
    for advice in advices:
        lines.append(
            f"| {advice.title} | {advice.label} | {advice.detail} | [官方文档]({advice.doc}) |"
        )
    return "\n".join(lines)


def to_markdown(analysis: Analysis, advices: list[Advice]) -> str:
    apk = analysis.apk
    lines = [
        f"# APK 体检报告：{apk.path.name}",
        "",
        f"- 应用：`{analysis.package_name}` {analysis.version_name}",
        f"- 体积：{size(apk.size)}（{apk.size} 字节）",
        f"- 条目：{len(apk.entries)}，容器开销 {size(apk.container_overhead)}",
        f"- 构建工具：build-tools {analysis.toolchain_version}",
        f"- ZIP 对齐：{'通过' if analysis.alignment.ok else '未通过'}",
        f"- 后处理可回收：{size(analysis.reclaimable)}",
        "",
        *_conclusion(analysis, advices),
        "",
        "## 体积构成",
        "",
        "| 分组 | 条目 | 原始 | 占用 | 占比 |",
        "| --- | ---: | ---: | ---: | ---: |",
    ]
    total = max(apk.content_size, 1)
    for bucket in analysis.buckets:
        lines.append(
            f"| `{bucket.label}` | {bucket.entries} | {size(bucket.raw_size)} |"
            f" {size(bucket.stored_size)} | {bucket.stored_size / total * 100:.1f}% |"
        )

    lines += ["", "## 最大条目", "", "| 条目 | 原始 | 占用 | 方式 |", "| --- | ---: | ---: | --- |"]
    for entry in analysis.largest[:15]:
        lines.append(
            f"| `{entry.name}` | {size(entry.raw_size)} | {size(entry.stored_size)}"
            f" | {entry.method_name} |"
        )

    if analysis.findings:
        lines += ["", "## 存储方式偏差", "", "| 条目 | 体积变化 | 原因 |", "| --- | ---: | --- |"]
        for finding in analysis.findings:
            lines.append(
                f"| `{finding.name}` | {_signed(finding.saved)} | {finding.reason} |"
            )

    if analysis.duplicates:
        lines += ["", "## 疑似重复内容", ""]
        for raw, crc, names in analysis.duplicates:
            lines.append(f"- {size(raw)} `crc={crc:08x}`：{'、'.join(f'`{n}`' for n in names)}")

    lines += ["", "## 构建期优化清单", "", render_advice_markdown(advices)]
    return "\n".join(lines)


def _conclusion(analysis: Analysis, advices: list[Advice]) -> list[str]:
    """给出可执行的结论，并明确后处理阶段的能力边界。"""
    lines = ["## 结论", ""]

    if analysis.reclaimable < 4096:
        lines.append(
            f"- **后处理已无空间**：可回收 {size(analysis.reclaimable)}，"
            "产物已按最优形式打包，重写只会更换签名块而不带来收益。"
        )
    else:
        lines.append(
            f"- **后处理可回收 {size(analysis.reclaimable)}**："
            "执行 `optimize` 子命令即可落实，源文件保持不变。"
        )

    lines.append(
        "- **运行性能无法由后处理提升**：R8 在编译期完成代码优化、DEX 布局与 "
        "Baseline Profile 生成，改写其产物会导致优化失效甚至性能回退。"
    )

    gaps = [a for a in advices if a.status != PASS]
    if gaps:
        lines.append(f"- **构建期缺口 {len(gaps)} 项**：")
        for gap in gaps:
            lines.append(f"  - {gap.title}（{gap.label}）：{gap.detail}")
    else:
        lines.append("- 构建期检查未发现缺口。")

    if analysis.duplicates:
        lines.append(f"- 疑似重复内容 {len(analysis.duplicates)} 组，可在依赖或资源层合并。")
    if analysis.stale_v1:
        lines.append(
            f"- 残留 v1 签名文件 {len(analysis.stale_v1)} 个，可在打包配置中排除。"
        )
    return lines


def _bar(ratio: float, width: int = 16) -> str:
    filled = ratio * width
    full = int(filled)
    remainder = filled - full
    partial = _BAR_BLOCKS[min(int(remainder * 8), 7)] if remainder > 0 else ""
    return "█" * full + partial + "·" * max(width - full - (1 if partial else 0), 0)
