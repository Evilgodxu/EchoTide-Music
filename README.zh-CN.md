<div align="center">

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp" width="96" alt="忆潮音乐" />

# 忆潮音乐 · Echo Tide

**沉浸式音乐播放器**

**这是一个风格老派轻量且简约的沉浸式音乐播放器**

[English](README.md) | **简体中文**

![License](https://img.shields.io/badge/license-AGPL--3.0-blue)
![Platform](https://img.shields.io/badge/platform-Android-brightgreen)
![Version](https://img.shields.io/badge/version-4.5.7-informational)
![Kotlin](https://img.shields.io/badge/Kotlin-2.4.20-purple)
![AGP](https://img.shields.io/badge/AGP-9.4.1-blue)
![Gradle](https://img.shields.io/badge/Gradle-9.8.0-blue)
![Compose BOM](https://img.shields.io/badge/Compose%20BOM-2026.09.00-blue)
![minSdk](https://img.shields.io/badge/minSdk-34-orange)
![targetSdk](https://img.shields.io/badge/targetSdk-37-orange)

<img src="docs/Screenshot/promo-hero.webp" width="100%" alt="忆潮音乐 界面预览：带壳截图的竖屏播放器、横屏播放器与横屏 3D 封面轮播" />

</div>

## 产品展示

无论是竖屏还是横屏均采用最少干扰最大沉浸体验设计。

| 竖屏播放器 | 横屏播放器 | 横屏 3D 封面轮播 |
| :---: | :---: | :---: |
| <img src="docs/Screenshot/device-portrait.webp" width="215" alt="竖屏播放器(带壳)" /> | <img src="docs/Screenshot/device-landscape.webp" width="380" alt="横屏播放器(带壳)" /> | <img src="docs/Screenshot/device-carousel.webp" width="380" alt="横屏 3D 封面轮播(带壳)" /> |

> 截图为真机运行画面,曲目封面与歌词版权归原作者所有,仅作界面演示。

## 技术栈

| 层次 | 技术 |
| --- | --- |
| 语言 | Kotlin 2.4.20 |
| UI | Jetpack Compose(BOM 2026.09.00)+ Material 3 |
| 播放 | Media3 ExoPlayer 1.11.1 + MediaSessionService |
| 导航 | AndroidX Navigation3 1.2.0(类型安全路由) |
| 依赖注入 | 手动 DI(单例挂在 Application) |
| 持久化 | DataStore Preferences 1.2.1 |
| 图片加载 | Coil 3.6.3 |
| 网络 | OkHttp 5.5.0 |
| 序列化 | kotlinx.serialization 1.11.0 |
| 异步 | kotlinx.coroutines 1.11.0(含 coroutines-guava 桥接 MediaController Future) |
| 自适应布局 | androidx.window 1.5.1、material3-adaptive 1.3.0 |
| 生命周期 | androidx.lifecycle 2.11.0、activity-compose 1.13.0 |
| 构建 | AGP 9.4.1、Gradle 9.8.0、refreshVersions |

## 项目结构

```
.
├── app/
│   └── src/main/
│       ├── kotlin/com/yichao/evilgodxu/
│       │   ├── data/                    # 数据层
│       │   │   ├── cache/               #   缓存台账(分类 / 占用统计 / 冷启动回收)
│       │   │   ├── music/               #   曲库扫描 / 在线音源 / 元数据 / 代理音源
│       │   │   │   ├── api/             #     搜索服务、翻译接口与网络客户端
│       │   │   │   ├── analysis/        #     无损格式、音质异常与 AI 音乐分析、生成器署名取证、音频信息读取、逐字歌词对齐、FFT 与全曲频谱、全曲分析锁定
│       │   │   │   ├── blacklist/       #     黑名单存储
│       │   │   │   ├── clip/            #     分享、默认铃声 / 闹钟设置、可读 URI 解析、频谱图导出
│       │   │   │   ├── download/        #     在线曲目下载与缓存
│       │   │   │   ├── metadata/        #     封面管理、元数据与歌词读写(区间流式标签读写)、元数据缓存、相册图片写入
│       │   │   │   ├── model/           #     曲目与搜索数据模型(以平台键为身份)
│       │   │   │   ├── panel/           #     面板状态持有器、搜索逻辑与逐字对齐入口
│       │   │   │   ├── playback/        #     播放状态、播放器工具、队列切换、歌单排序、USB 直出与免打扰、按设备音频输出(含缓冲策略)、输出延迟实测、音频信息快照(含蓝牙链路与编解码器解析)
│       │   │   │   ├── proxy/           #     代理音源(导入 / 解析 / 引擎 / 存储)、自定义平台注册表与歌单同步
│       │   │   │   ├── recommend/       #     每日推荐(榜单候选池、歌词特征、TF-IDF、MMR)
│       │   │   │   ├── MusicScanner.kt  #     MediaStore 扫描与曲目补全
│       │   │   │   └── PlaylistRefresher.kt  # 播放列表刷新流程
│       │   │   ├── playlist/            #   歌单存储(智能 / 自定义)与分组
│       │   │   ├── repository/          #   设置仓库
│       │   │   └── settings/            #   设置 DataStore、播放与歌词排版偏好、启动语言镜像
│       │   ├── floatingwindow/          # 悬浮窗 / 迷你播放器视图管理、控制器与权限流程
│       │   │   └── miniplayer/          #   迷你播放器浮层 / 条 / 播放列表面板
│       │   ├── localization/            # 应用内多语言管理
│       │   ├── log/                     # CrashLogManager
│       │   ├── navigation/              # Navigation3 类型安全路由与导航宿主
│       │   ├── permission/              # 权限、悬浮窗授权与电池优化白名单监控
│       │   ├── screens/                 # 页面(首页 / 设置 / 存储 / 排版 / 频谱 / 元数据)
│       │   │   ├── home/                #   首页播放器 + 权限流程 + 歌单 + 在线搜索
│       │   │   │   ├── compact/         #     竖屏组装器、播放器与部件
│       │   │   │   ├── expanded/        #     横屏组装器、播放器与部件
│       │   │   │   └── component/       #     analysis / audioinfo / bar / dialog / panel / permission / player / playlist / queue / search / shell / swipe
│       │   │   ├── settings/            #   外观 / 黑名单 / 存储 / 语言 / 播放 / 代理音源 / 关于
│       │   │   ├── cache/               #   存储 / 缓存管理
│       │   │   ├── spectrum/            #   频谱分析页(compact / expanded / component)
│       │   │   ├── typography/          #   歌词排版设置
│       │   │   └── metadata/            #   元数据编辑页(compact / expanded / component)
│       │   ├── service/                 # MediaSessionService 播放引擎
│       │   ├── theme/                   # Material 3 配色与字体
│       │   ├── ui/                      # 全局共享 UI(component → 含封面渐隐 CoverFade 与统一对话框骨架 AppDialog / component/dialog / component/player → 含音频信息覆盖层与内容区 / component/section / icons)
│       │   ├── update/                  # 检查更新、应用内更新与 APK 校验
│       │   ├── utils/                   # 通用工具
│       │   ├── windowsize/              # 窗口尺寸类与横屏形态判定
│       │   ├── App.kt                   # Application 入口(持有单例与组合局部)
│       │   ├── AppContent.kt            # 根可组合项(导航宿主与全局弹窗)
│       │   ├── MainActivity.kt          # 唯一 Activity(系统栏、外部意图、启动语言)
│       │   └── MainViewModel.kt         # Activity 专属 ViewModel(含 AppUiState)
│       └── res/                         # 资源(values / values-en)
├── gradle/
│   ├── libs.versions.toml               # 版本目录(依赖管理)
│   └── wrapper/
├── docs/                                # 代理音源规范、界面截图与注意事项
├── LICENSE
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

自定义代理音源的接口规范见 [忆潮代理音源规范](docs/忆潮代理音源规范.md)。

## 架构

应用遵循 **MVVM + 单向数据流**:状态由 `ViewModel` → `UiState` → UI 自上而下流动,事件由 UI 自下而上传递;共享数据逻辑位于 `data/` 层并通过 Repository 暴露,全部由**手动依赖注入**组装——每一个应用级单例挂在 `Application` 上,并通过具名组合局部(CompositionLocal)暴露给界面树。

页面代码采用**分形态组装(per-form assembly)模式**:

- `{Screen}Screen.kt` — 页面入口:在 compact/expanded 形态间分发并处理跨形态副作用,不承载布局
- `{Screen}ViewModel.kt` / `{Screen}UiState.kt` — 页面级状态与事件
- `compact/`、`expanded/` 下的 `{Screen}Assembly` — 按窗口尺寸类与旋转状态选择对应形态的组装器
- `component/` — 页面专用可组合项,按语义子目录分组(如 bar/、dialog/、panel/、playlist/、player/、search/、shell/、swipe/)

被两个及以上功能复用的代码上提至顶层(`data/`、`theme/`、`utils/`、`ui/`),仅单页使用的代码保留在页面模块内。播放逻辑位于 `data/music`(播放 / 下载 / 分析 / 面板 / 推荐),通过窗口级 `MusicPanelStateHolder` 暴露给 UI;悬浮 UI 拆分为 `floatingwindow/`(视图管理,以及迷你播放器自身的可组合项)与 `ui/component/player`(完整音乐面板及其子部件),实际播放由 `service/MusicPlaybackService`(Media3 ExoPlayer + `MediaSessionService`)驱动。

除页面自身状态外,有两类逻辑刻意置于界面树之外,以便跨重组与旋转存活:首页 **面板状态**(`HomePanelState`,持有播放列表显隐、对话框、滑动控制器与曲库分析会话)与共享**播放状态持有者**。其中曲库分析会话常驻首页层,关闭其面板不会中断正在执行的分析。每日推荐的榜单候选池与播放启动镜像同理:候选池由 `App` 在后台预热,最近一次播放状态镜像落盘,使冷启动后的首帧即为完整内容。每日推荐同样过 `MusicBlacklist`:被用户明确拉黑的曲目在粗排阶段直接跳过,而候选集中过代表的特征与被跳过曲目的特征只做降权,故一次拉黑不会退化成逐曲过滤。

频谱分析是独立页面:分析会话按曲目挂在 `SpectrumViewModel` 中,解码跑在 `Dispatchers.Default` 上,离开页面即随作用域取消;解码本体(`SpectrogramDecoder`)与判定入口(`FullSpectrumAnalyzer`)置于 `data/music/analysis`,与曲库分析共用判定缓存与判据。`FullAnalysisLock` 是「以完整分析为准」的落点——曲库分析的分段采样遇到锁定曲目一律跳过,不再改写其结论。

歌词解析同样收在一处:`data/music/api/LyricCodec` 把各平台的歌词原文(普通 LRC、增强 LRC 的行内字标签、QQ 的 QRC、酷狗的 KRC、酷我的 lrcx)统一解析为同一份 `LyricLine` 列表,逐字时间轴一律归一为绝对毫秒,因此各平台的解析结果可直接互换比较,「逐字优先、无字标签则退化为逐行」的选取策略也只需实现一次。取词、解析、缓存写入与自动补译分别落在 `OnlineLyrics`、`LyricCodec`、`MusicMetadataCache` 与 `data/music/panel/MusicPanelLyricsTranslate`,后者的进度对话框与逐字对齐共用同一组件。酷狗 KRC 是唯一自带译文的来源:`[language]` 元信息块(base64 编码的 JSON,取 `type=1` 段)内的译文按歌词行顺序 1:1 对齐,这类曲目无需调用翻译接口即带译文;只有字标签全零的翻译行仍按时间戳并入。

音频信息面板读的是播放链路本身,不与任何播放器布局耦合:`AudioInfoCollector` 从共享播放状态组装出一份 `AudioInfoSnapshot`,Compose 侧则靠一个版本号触发重算——播放器自身回调(播放状态、起播意愿、音频会话 ID)、`AudioDeviceCallback`(设备插拔)与 `ON_RESUME`(刚授予的权限当即体现)各自使其自增。所有字段均可为空,读不到的字段不产出该行,因此同一个面板覆盖扬声器、USB 解码器与蓝牙链路时,UI 侧无需分支。它打印的链路取值——浮点输出、实际写出的 PCM 编码、位完美直出——由按设备音频输出与 USB 直出模块向上回填到共享状态,面板读到的正是播放路径写入的同一份来源,而非从源格式推断。面板同时给出实测延迟与音频轨缓冲,两者都不能按设备查询:`OutputLatency` 拿 `AudioTrack.getTimestamp` 分别与播放头、写入帧位比对,把链路在音频轨处切成「轨之后」与「轨自身驻留」两段,相加得出全链路,`OutputLatencySampler` 再对抖动的读数取滑动平均;`PlaybackBufferPolicy` 以 512 帧 PCM 申请缓冲(取代媒体3 固定的 500ms 口径),压住的正是这段驻留。USB 直出期间,`DirectOutputDoNotDisturb` 把输出成色映射到系统免打扰——位完美或源格式直出成立即进入「仅闹钟」,成色消失即还原先前档位,通知与铃声因此不再打扰,而媒体流本身不受压制。

无损与线性 PCM 容器的标签重写走 `TagSource`,它只暴露区间读取与区间搬运:标签布局由文件头部与尾部窗口定位算出,音频体按原偏移流式复制,因此数百 MB 的高解析单文件在改写标签时不再整文件驻留内存。各类容器布局——ID3v2、M4A/MP4 盒子表、FLAC Vorbis 注释、Ogg 页序列,以及 AIFF、DSDIFF、DSF、APE、WAV 的 IFF/RIFF 式块结构——只需给出「头部字面字节 + 音频体区间 + 尾部字面字节」交给写入方。

元数据编辑是搭在这条路径之上的页面:编辑会话挂在按曲目区分的 `MetadataViewModel` 中,每次页面回到前台都重读一次文件标签(导航栈缓存的 ViewModel 会把上次的快照当作当前值),离开页面时补写未到自动保存窗口的改动——因此该页没有保存入口。逐行与全文两种歌词编辑形态的差别只在草稿如何还原为歌词文本,最终都落到同一处增强 LRC 编码写入。

## 权限

| 权限 | 用途 |
| --- | --- |
| 悬浮窗 | 悬浮音乐面板与迷你播放器 |
| 全部文件访问 | 导入与管理本地音乐文件 |
| 音乐访问(`READ_MEDIA_AUDIO`) | 读取设备曲库并播放 |
| 图片(`READ_MEDIA_IMAGES`) | 内嵌封面与本地封面候选;Android 14「选中的照片」部分授权同样视为已授权 |
| 蓝牙(`BLUETOOTH_CONNECT`) | 读取当前蓝牙输出设备的名称、地址与协商出的编解码器(音频信息) |
| 前台服务(`mediaPlayback`、`FOREGROUND_SERVICE_MEDIA_PLAYBACK`) | 后台播放 + 通知栏 / 锁屏控制 |
| 通知(`POST_NOTIFICATIONS`) | 版本更新下载完成通知(Android 13+) |
| 网络(`INTERNET`、`ACCESS_NETWORK_STATE`) | 在线搜索、歌词与封面获取、检查更新 |
| 音频设置(`MODIFY_AUDIO_SETTINGS`) | 播放引擎的音频配置,含 USB 直出按设备声明申请动态混音器属性所需 |
| 免打扰(`ACCESS_NOTIFICATION_POLICY`) | USB 直出期间自动切至「仅闹钟」档位,屏蔽通知与铃声干扰(不压制媒体流) |
| 唤醒锁(`WAKE_LOCK`) | 熄屏后维持播放引擎运行 |
| 忽略电池优化(`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) | 避免后台播放被系统回收 |
| 安装应用(`REQUEST_INSTALL_PACKAGES`) | 应用内更新时拉起系统安装器 |
| 修改系统设置(`WRITE_SETTINGS`) | 将曲目设为默认来电铃声 / 闹钟铃声 |

权限由引导对话框逐项申请:三项曲库权限为必需,蓝牙、通知与电池优化白名单为可选且仅在缺失时列出。Android 14 的「选中的照片」部分授权与完整图片权限同等看待——查询仍能返回用户已选中的图片,足以挑选封面。当前输出为蓝牙设备时,音频信息面板还会就地申请蓝牙权限。

## 快速开始

### 环境要求

- JDK 21
- Android Studio(建议最新稳定版)
- 包含 API 37(`compileSdk`)的 Android SDK

### 构建

```bash
git clone https://github.com/Evilgodxu/EchoTide-Music.git
cd EchoTide-Music

# 调试包
./gradlew assembleDebug

# 发布包(需先配置签名,见下文)
./gradlew assembleRelease
```

APK 输出为 `app/build/outputs/apk/` 下的 `EchoTideMusic-<版本号>-arm64-v8a.apk`,仅构建 `arm64-v8a` ABI。

### 发布签名

Release 构建从项目根目录的 `local.properties` 读取签名凭据:

```properties
KEYSTORE_PASSWORD=你的签名库密码
KEY_ALIAS=jh
KEY_PASSWORD=你的别名密码
```

签名库文件默认位于项目根目录 `jh.keystore`(如需调整请修改 `app/build.gradle.kts` 中的 `storeFile`)。两个文件均已被 git 忽略,请勿提交。

### 宣传图

README 使用的带壳截图与首屏宣传图由 `tools/make_promo_hero.py` 从 `docs/Screenshot/` 下的真机截图生成:脚本按屏幕短边等比推导边框厚度、机身圆角与侧键位置,输出透明背景的三张带壳截图(`device-*.webp`)与合成后的 `promo-hero.webp`。

```bash
python tools/make_promo_hero.py
```

## 免责声明

搜索服务依赖公共网络接口。内置搜索服务仅用于基础的歌曲、封面与歌词搜索,不承诺播放能力,应用仅在音源返回可播放直链时理论上支持试听与免费歌曲;音质升级依赖代理音源。歌词自动补译依赖有道公开的免鉴权翻译接口,其可用性、限流策略与译文质量均由该服务决定。接口可用性随地区与歌曲而异。应用仅供个人学习交流使用,请支持正版版权方。

## 致谢

- 网易云音乐解析早期参考 [Qplayer](https://github.com/TIMER-err/qplayer)
- 列表拖拽排序 [Reorderable](https://github.com/Calvin-LL/Reorderable),现已在应用内自行实现(算法等价)
- 基于 [musicdl](https://github.com/CharlesPikachu/musicdl) 实现 网易云音乐 酷狗 酷我 咪咕 QQ 的 Kotlin 原生音源解析

## License

[AGPL-3.0](LICENSE) © 2026 Evilgodxu
