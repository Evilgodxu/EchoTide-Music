"""APK 体检：体积归因、存储方式偏差、对齐与签名核验。"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path

from .apk import Apk, Bucket, Entry
from .recompress import RecompressPlan, build_plan
from .sdk import PAGE_ALIGNMENT_KIB, ZIP_ALIGNMENT, Toolchain

_PROFILE_SIZE_LIMIT = 1_500_000

_ALIGN_LINE = re.compile(r"^(\d+)\s+(.*?)\s+\((.*)\)$")
_SCHEME = re.compile(r"Verified using (v[\d.]+) scheme[^:]*:\s*(true|false)")
_BADGING_PACKAGE = re.compile(r"^package:\s+name='([^']*)'")
_BADGING_VERSION = re.compile(r"versionName='([^']*)'")
_BADGING_MIN_SDK = re.compile(r"^minSdkVersion:'(\d+)'", re.MULTILINE)


@dataclass(frozen=True)
class CompressionFinding:
    """一条与打包工具默认策略不一致的存储方式决策。"""

    name: str
    reason: str
    saved: int


@dataclass(frozen=True)
class SignatureInfo:
    verified: bool
    schemes: dict[str, bool]
    signer: str
    signers: int
    detail: str

    @property
    def effective_schemes(self) -> list[str]:
        return [name for name, enabled in self.schemes.items() if enabled]


@dataclass
class AlignmentInfo:
    ok: bool
    offenders: list[tuple[str, str]] = field(default_factory=list)


@dataclass
class Analysis:
    apk: Apk
    plan: RecompressPlan
    toolchain_version: str
    package_name: str
    version_name: str
    min_sdk: int | None
    buckets: list[Bucket]
    largest: list[Entry]
    findings: list[CompressionFinding]
    alignment: AlignmentInfo
    signature: SignatureInfo | None
    baseline_profile: int | None
    baseline_metrics: int | None
    duplicates: list[tuple[int, int, list[str]]]
    stale_v1: list[str]

    @property
    def reclaimable(self) -> int:
        return self.plan.savings

    @property
    def profile_oversized(self) -> bool:
        return (self.baseline_profile or 0) >= _PROFILE_SIZE_LIMIT

    def to_dict(self) -> dict:
        return {
            "apk": str(self.apk.path),
            "size": self.apk.size,
            "package": self.package_name,
            "versionName": self.version_name,
            "minSdk": self.min_sdk,
            "buildTools": self.toolchain_version,
            "buckets": [
                {
                    "label": b.label,
                    "entries": b.entries,
                    "rawSize": b.raw_size,
                    "storedSize": b.stored_size,
                }
                for b in self.buckets
            ],
            "largest": [
                {
                    "name": e.name,
                    "raw": e.raw_size,
                    "stored": e.stored_size,
                    "method": e.method_name,
                }
                for e in self.largest
            ],
            "compressionFindings": [
                {"name": f.name, "reason": f.reason, "saved": f.saved}
                for f in self.findings
            ],
            "reclaimable": self.reclaimable,
            "alignment": {
                "ok": self.alignment.ok,
                "offenders": [
                    {"name": n, "verdict": v} for n, v in self.alignment.offenders
                ],
            },
            "signature": None
            if self.signature is None
            else {
                "verified": self.signature.verified,
                "schemes": self.signature.schemes,
                "signer": self.signature.signer,
                "signers": self.signature.signers,
            },
            "baselineProfile": self.baseline_profile,
            "baselineProfileMetrics": self.baseline_metrics,
            "duplicates": [
                {"raw": size, "crc": f"{crc:08x}", "names": names}
                for size, crc, names in self.duplicates
            ],
            "staleV1Signature": self.stale_v1,
        }


def analyze(apk_path: Path, tool: Toolchain, plan: RecompressPlan | None = None) -> Analysis:
    """对单个 APK 做完整体检。"""
    apk = Apk.open(apk_path)
    plan = plan or build_plan(apk_path)
    badging = _badging(apk_path, tool)

    return Analysis(
        apk=apk,
        plan=plan,
        toolchain_version=tool.version,
        package_name=badging.get("package", "?"),
        version_name=badging.get("versionName", "?"),
        min_sdk=badging.get("minSdk"),
        buckets=apk.buckets(),
        largest=apk.largest(),
        findings=_findings(plan),
        alignment=_alignment(apk_path, tool),
        signature=_signature(apk_path, tool),
        baseline_profile=_entry_size(apk, "assets/dexopt/baseline.prof"),
        baseline_metrics=_entry_size(apk, "assets/dexopt/baseline.profm"),
        duplicates=apk.duplicate_groups(),
        stale_v1=_stale_v1(apk),
    )


def _findings(plan: RecompressPlan) -> list[CompressionFinding]:
    """列出所有存储方式有变动的条目。

    个条目重压后可能略微变大（zlib 级别并非单调），这类明细同样披露，
    以便解释总体积的去向。
    """
    return [
        CompressionFinding(name=d.name, reason=d.reason, saved=d.saved)
        for d in plan.changed_entries
    ]


def _entry_size(apk: Apk, name: str) -> int | None:
    for entry in apk.entries:
        if entry.name == name:
            return entry.raw_size
    return None


def _alignment(apk_path: Path, tool: Toolchain) -> AlignmentInfo:
    result = tool.run(
        [
            str(tool.zipalign),
            "-c",
            "-P",
            str(PAGE_ALIGNMENT_KIB),
            "-v",
            str(ZIP_ALIGNMENT),
            str(apk_path),
        ]
    )
    offenders: list[tuple[str, str]] = []
    for line in f"{result.stdout}\n{result.stderr}".splitlines():
        match = _ALIGN_LINE.match(line.strip().lstrip("-").strip())
        if match and not match.group(3).startswith("OK"):
            offenders.append((match.group(2), match.group(3)))
    return AlignmentInfo(ok=result.returncode == 0, offenders=offenders)


def _signature(apk_path: Path, tool: Toolchain) -> SignatureInfo | None:
    result = tool.run(
        [str(tool.apksigner), "verify", "--verbose", "--print-certs", str(apk_path)]
    )
    detail = f"{result.stdout}\n{result.stderr}".strip()

    if "DOES NOT VERIFY" in detail or "Verification failed" in detail:
        return SignatureInfo(
            verified=False, schemes={}, signer="?", signers=0, detail=detail
        )
    if not _SCHEME.search(detail):
        return None

    schemes = {m.group(1): m.group(2) == "true" for m in _SCHEME.finditer(detail)}
    signer = "?"
    for line in detail.splitlines():
        if "certificate DN:" in line:
            signer = line.split("certificate DN:", 1)[1].strip()
            break
    signers = 0
    match = re.search(r"Number of signers:\s*(\d+)", detail)
    if match:
        signers = int(match.group(1))

    return SignatureInfo(
        verified=True,
        schemes=schemes,
        signer=signer,
        signers=signers,
        detail=detail,
    )


def _badging(apk_path: Path, tool: Toolchain) -> dict:
    result = tool.run([str(tool.aapt2), "dump", "badging", str(apk_path)])
    output = result.stdout or ""
    info: dict = {}
    if match := _BADGING_PACKAGE.search(output):
        info["package"] = match.group(1)
    if match := _BADGING_VERSION.search(output):
        info["versionName"] = match.group(1)
    if match := _BADGING_MIN_SDK.search(output):
        info["minSdk"] = int(match.group(1))
    return info


def _stale_v1(apk: Apk) -> list[str]:
    """定位残留的 v1（JAR）签名文件。

    v2/v3 签名生效后这些文件不再参与校验，只会白占体积；它们仍存在
    说明打包配置没有排除第三方归档带入的签名文件。
    """
    stale: list[str] = []
    for entry in apk.entries:
        name = entry.name
        if not name.startswith("META-INF/"):
            continue
        if name == "META-INF/MANIFEST.MF" or name.rsplit(".", 1)[-1] in {
            "SF",
            "RSA",
            "DSA",
            "EC",
        }:
            stale.append(name)
    return stale
