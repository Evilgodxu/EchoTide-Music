"""构建期优化体检。

运行性能与大部分体积由构建期决定：后处理只能收拾打包形式，真正的
代码优化、资源裁剪与启动加速必须落在这里检查的配置上。
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path

PASS = "pass"
WARN = "warn"
MISS = "miss"

_STATUS_LABEL = {PASS: "已启用", WARN: "待确认", MISS: "未启用"}

DOC_SIZE = "https://developer.android.com/topic/performance/reduce-apk-size"
DOC_R8 = "https://developer.android.com/topic/performance/app-optimization/enable-app-optimization"
DOC_ZIPALIGN = "https://developer.android.com/tools/zipalign"
DOC_BASELINE = "https://developer.android.com/topic/performance/baselineprofiles/overview"
DOC_STARTUP = "https://developer.android.com/topic/performance/startupprofiles/dex-layout-optimizations"
DOC_PAGE_SIZE = "https://developer.android.com/guide/practices/page-sizes"
DOC_SIGN = "https://developer.android.com/tools/apksigner"


@dataclass(frozen=True)
class Advice:
    """一条构建期建议。"""

    key: str
    title: str
    status: str
    detail: str
    doc: str

    @property
    def label(self) -> str:
        return _STATUS_LABEL.get(self.status, self.status)


@dataclass
class ProjectConfig:
    """项目构建配置的文本视图。"""

    module_gradle: str
    root_gradle: str
    gradle_properties: str
    profile_module_gradle: str = ""
    profile_module_sources: str = ""
    startup_profile: str = ""

    @property
    def all(self) -> str:
        return "\n".join(
            (
                self.module_gradle,
                self.root_gradle,
                self.gradle_properties,
                self.profile_module_gradle,
            )
        )

    @property
    def startup_profile_generated(self) -> bool:
        """生成器是否已产出启动配置。

        启动配置在编译期被 R8 消费，不会作为独立文件进入 APK，
        因此只能从项目源码目录判断是否已经跑过一次生成。
        """
        return bool(self.startup_profile.strip())


def load_config(project_root: Path) -> ProjectConfig:
    profile_dir = project_root / "baselineprofile"
    src_dir = project_root / "app" / "src"
    startup = sorted(src_dir.glob("**/generated/baselineProfiles/startup-prof.txt")) if src_dir.is_dir() else []
    return ProjectConfig(
        module_gradle=_read(project_root / "app" / "build.gradle.kts"),
        root_gradle=_read(project_root / "build.gradle.kts"),
        gradle_properties=_read(project_root / "gradle.properties"),
        profile_module_gradle=_read(profile_dir / "build.gradle.kts"),
        profile_module_sources=_read_tree(profile_dir / "src", "*.kt"),
        startup_profile=_read(startup[0]) if startup else "",
    )


def advise(
    config: ProjectConfig,
    *,
    min_sdk: int | None = None,
    has_baseline_profile: bool = False,
    has_startup_profile: bool = False,
    aligned: bool | None = None,
) -> list[Advice]:
    """按官方优化清单逐项核对构建配置。"""
    text = config.all
    return [
        _r8(text),
        _resource_shrinking(text),
        _r8_full_mode(config),
        _resource_qualifiers(config),
        _abi(config),
        _native_packaging(config),
        _page_alignment(aligned),
        _baseline_profile(config, has_baseline_profile),
        _startup_profile(config, has_startup_profile),
        _signing_scheme(config, min_sdk),
        _packaging_excludes(config),
    ]


def _page_alignment(aligned: bool | None) -> Advice:
    if aligned is None:
        return Advice(
            key="alignment",
            title="16 KiB 页对齐",
            status=WARN,
            detail="没有可用于核验的 APK，无法确认未压缩 .so 是否可按 16 KiB 页加载",
            doc=DOC_ZIPALIGN,
        )
    if aligned:
        return Advice(
            key="alignment",
            title="16 KiB 页对齐",
            status=PASS,
            detail="未压缩条目已按 4 字节与 16 KiB 页双重要求对齐，兼容 4 KiB 与 16 KiB 页设备",
            doc=DOC_ZIPALIGN,
        )
    return Advice(
        key="alignment",
        title="16 KiB 页对齐",
        status=MISS,
        detail="存在未对齐的未压缩条目，运行时将无法 mmap 加载，且在 16 KiB 页设备上可能无法安装",
        doc=DOC_PAGE_SIZE,
    )


def _r8(text: str) -> Advice:
    # AGP 9 起由 optimization{} 统一开启代码优化与优化型资源缩减
    unified = re.search(r"optimization\s*\{[^}]*enable\s*=\s*true", text, re.DOTALL)
    legacy = re.search(r"isMinifyEnabled\s*=\s*true", text)
    if unified or legacy:
        mode = "optimization { enable = true }" if unified else "isMinifyEnabled = true"
        return Advice(
            key="r8",
            title="R8 代码缩减与优化",
            status=PASS,
            detail=f"已通过 {mode} 启用，R8 会移除未引用代码并重写热点逻辑",
            doc=DOC_R8,
        )
    return Advice(
        key="r8",
        title="R8 代码缩减与优化",
        status=MISS,
        detail="未启用，未引用代码与资源会全部留在包内",
        doc=DOC_R8,
    )


def _resource_shrinking(text: str) -> Advice:
    unified = re.search(r"optimization\s*\{[^}]*enable\s*=\s*true", text, re.DOTALL)
    legacy = re.search(r"isShrinkResources\s*=\s*true", text)
    if unified:
        return Advice(
            key="shrinkResources",
            title="资源缩减",
            status=PASS,
            detail="新 DSL 下优化型资源缩减随 optimization{} 默认启用，代码与资源引用会被合并裁剪",
            doc=DOC_R8,
        )
    if legacy:
        return Advice(
            key="shrinkResources",
            title="资源缩减",
            status=WARN,
            detail="已启用，但未使用 AGP 9 的优化型资源缩减；后者可跨代码与资源边界裁剪",
            doc=DOC_R8,
        )
    return Advice(
        key="shrinkResources",
        title="资源缩减",
        status=MISS,
        detail="未启用，未被引用的资源会完整打包",
        doc=DOC_R8,
    )


def _r8_full_mode(config: ProjectConfig) -> Advice:
    disabled = re.search(r"android\.enableR8\.fullMode\s*=\s*false", config.gradle_properties)
    if disabled:
        return Advice(
            key="r8FullMode",
            title="R8 完整模式",
            status=MISS,
            detail="gradle.properties 中显式关闭了 fullMode，会限制可用的优化集合",
            doc=DOC_R8,
        )
    return Advice(
        key="r8FullMode",
        title="R8 完整模式",
        status=PASS,
        detail="未显式关闭，R8 使用完整优化集合",
        doc=DOC_R8,
    )


def _resource_qualifiers(config: ProjectConfig) -> Advice:
    pattern = re.compile(r"(localeFilters|resConfigs|resourceConfigurations)\s*\+=?")
    if pattern.search(config.module_gradle):
        return Advice(
            key="qualifiers",
            title="资源限定符裁剪",
            status=PASS,
            detail="已声明语言限定符，未支持语言的库资源不会进入包内",
            doc=DOC_SIZE,
        )
    return Advice(
        key="qualifiers",
        title="资源限定符裁剪",
        status=MISS,
        detail="未声明 localeFilters，依赖库附带的全部语言字符串都会被打包",
        doc=DOC_SIZE,
    )


def _abi(config: ProjectConfig) -> Advice:
    if re.search(r"abiFilters", config.module_gradle):
        return Advice(
            key="abi",
            title="ABI 维度收敛",
            status=PASS,
            detail="已限定 ABI，避免多套原生库同时入包",
            doc=DOC_SIZE,
        )
    if re.search(r"splits\s*\{", config.module_gradle):
        return Advice(
            key="abi",
            title="ABI 维度收敛",
            status=PASS,
            detail="已按 ABI 拆分，用户只下载本机架构的原生库",
            doc=DOC_SIZE,
        )
    return Advice(
        key="abi",
        title="ABI 维度收敛",
        status=MISS,
        detail="未限定 ABI，原生库会全部入包",
        doc=DOC_SIZE,
    )


def _native_packaging(config: ProjectConfig) -> Advice:
    # useLegacyPackaging 在 jniLibs 与 dex 两处各自生效：dex 块常用 true 压缩 DEX，
    # 与原生库加载方式无关，必须限定在 jniLibs 块内判定
    jni_block = re.search(r"jniLibs\s*\{([^{}]*)\}", config.module_gradle)
    legacy = re.search(r"useLegacyPackaging\s*=\s*true", jni_block.group(1)) if jni_block else None
    if legacy:
        return Advice(
            key="nativePackaging",
            title="原生库打包方式",
            status=MISS,
            detail="useLegacyPackaging = true 会在安装时解压原生库，占用双份存储且无法 mmap",
            doc=DOC_PAGE_SIZE,
        )
    return Advice(
        key="nativePackaging",
        title="原生库打包方式",
        status=PASS,
        detail="原生库保持未压缩，安装时无需解压即可 mmap 加载",
        doc=DOC_PAGE_SIZE,
    )


def _baseline_profile(config: ProjectConfig, present: bool) -> Advice:
    if present:
        return Advice(
            key="baseline",
            title="Baseline Profile",
            status=PASS,
            detail="包内含 assets/dexopt/baseline.prof，ART 会在安装期预编译热点路径",
            doc=DOC_BASELINE,
        )
    referenced = re.search(r"baselineprofile|BaselineProfile|androidx\.baselineprofile", config.all)
    return Advice(
        key="baseline",
        title="Baseline Profile",
        status=WARN if referenced else MISS,
        detail=(
            "配置中已引用 Baseline Profile 插件，但当前产物内没有 baseline.prof"
            if referenced
            else "产物内没有 baseline.prof，首次启动只能走解释执行并按需编译"
        ),
        doc=DOC_BASELINE,
    )


def _startup_profile(config: ProjectConfig, present: bool) -> Advice:
    if present:
        return Advice(
            key="startup",
            title="Startup Profile / DEX 布局优化",
            status=PASS,
            detail="已生成启动配置，R8 会把启动路径的类集中到首个 DEX，减少缺页",
            doc=DOC_STARTUP,
        )
    # 生成管线就绪与配置已产出是两件事：前者需连设备跑一次插桩测试
    if re.search(r"includeInStartupProfile\s*=\s*true", config.profile_module_sources):
        return Advice(
            key="startup",
            title="Startup Profile / DEX 布局优化",
            status=WARN,
            detail="生成管线已就绪（includeInStartupProfile = true），"
            "但尚无 startup-prof.txt；需连接 API 33 及以上设备执行 "
            ":app:generateReleaseBaselineProfile",
            doc=DOC_STARTUP,
        )
    if re.search(r"dexLayoutOptimization\s*=\s*true", config.all):
        return Advice(
            key="startup",
            title="Startup Profile / DEX 布局优化",
            status=WARN,
            detail="仅开启了 DEX 布局优化，缺少启动配置时 R8 无法判断哪些代码属于启动路径",
            doc=DOC_STARTUP,
        )
    return Advice(
        key="startup",
        title="Startup Profile / DEX 布局优化",
        status=MISS,
        detail="缺少 startup-prof.txt，启动路径的类会散落在 DEX 中并触发额外缺页",
        doc=DOC_STARTUP,
    )


def _signing_scheme(config: ProjectConfig, min_sdk: int | None) -> Advice:
    text = config.module_gradle
    if re.search(r"enableV1Signing\s*=\s*true", text):
        return Advice(
            key="signing",
            title="签名方案",
            status=WARN,
            detail="显式开启了 v1（JAR）签名，minSdk 24 以上无需保留，且会在 META-INF 留下签名文件",
            doc=DOC_SIGN,
        )

    v2_on = bool(re.search(r"enableV2Signing\s*=\s*true", text))
    v3_off = bool(re.search(r"enableV3Signing\s*=\s*false", text))
    covered = f"minSdk {min_sdk}" if min_sdk is not None else "当前 minSdk"

    if v3_off:
        detail = (
            f"已关闭 v3，{covered} 下由 v2 覆盖全部目标设备，产物只带一种校验方案，校验开销最低"
            if v2_on
            else f"已关闭 v3，仅保留 v2 校验方案（{covered} 下已足够）"
        )
        return Advice(
            key="signing", title="签名方案", status=PASS, detail=detail, doc=DOC_SIGN
        )

    if v2_on or re.search(r"enableV3Signing\s*=\s*true", text):
        return Advice(
            key="signing",
            title="签名方案",
            status=WARN,
            detail="只开启了部分方案，未关闭的方案仍由构建工具按 minSdk 自行决定",
            doc=DOC_SIGN,
        )
    return Advice(
        key="signing",
        title="签名方案",
        status=WARN,
        detail="未显式声明 v1/v2/v3 方案，不同构建工具版本的默认值可能变化",
        doc=DOC_SIGN,
    )


def _packaging_excludes(config: ProjectConfig) -> Advice:
    if re.search(r"packaging\s*\{", config.module_gradle) and re.search(
        r"excludes\s*\+=", config.module_gradle
    ):
        return Advice(
            key="packaging",
            title="打包排除规则",
            status=PASS,
            detail="已排除重复的 META-INF 元数据与第三方签名文件",
            doc=DOC_SIZE,
        )
    return Advice(
        key="packaging",
        title="打包排除规则",
        status=MISS,
        detail="未排除 META-INF 重复文件，依赖合并后会带入冗余元数据",
        doc=DOC_SIZE,
    )


def _read(path: Path) -> str:
    if not path.is_file():
        return ""
    return path.read_text(encoding="utf-8", errors="replace")


def _read_tree(root: Path, pattern: str) -> str:
    if not root.is_dir():
        return ""
    return "\n".join(_read(path) for path in sorted(root.rglob(pattern)))
