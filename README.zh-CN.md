<div align="center">

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp" width="96" alt="忆潮音乐" />

# 忆潮音乐 · Echo Tide

**沉浸聆听，忠于原声**

[English](README.md) | **简体中文**

![License](https://img.shields.io/badge/license-AGPL--3.0-blue)
![Platform](https://img.shields.io/badge/platform-Android-brightgreen)
![Version](https://img.shields.io/badge/version-6.2.8-informational)
![Kotlin](https://img.shields.io/badge/Kotlin-2.4.21-purple)
![AGP](https://img.shields.io/badge/AGP-9.5.1-blue)
![Gradle](https://img.shields.io/badge/Gradle-9.9.0-blue)
![Compose BOM](https://img.shields.io/badge/Compose%20BOM-2026.09.00-blue)
![minSdk](https://img.shields.io/badge/minSdk-34-orange)
![targetSdk](https://img.shields.io/badge/targetSdk-37-orange)

<img src="docs/Screenshot/promo-hero.webp" width="100%" alt="忆潮音乐 界面预览：带壳截图的竖屏播放器、横屏播放器与横屏 3D 封面轮播" />

</div>

## 产品展示

无论横竖，只为沉浸。

| 竖屏播放器 | 横屏播放器 | 横屏 3D 封面轮播 |
| :---: | :---: | :---: |
| <img src="docs/Screenshot/device-portrait.webp" width="215" alt="竖屏播放器（带壳）" /> | <img src="docs/Screenshot/device-landscape.webp" width="380" alt="横屏播放器（带壳）" /> | <img src="docs/Screenshot/device-carousel.webp" width="380" alt="横屏 3D 封面轮播（带壳）" /> |

> 截图为真机运行画面，曲目封面与歌词版权归原作者所有，仅作界面演示。

## 技术栈

| 层次 | 技术 |
| --- | --- |
| 语言 | Kotlin 2.4.21 |
| UI | Jetpack Compose(BOM 2026.09.00)+ Material 3 |
| 播放 | Media3 ExoPlayer 1.11.1 + MediaSessionService（USB 独占另走原生等时层） |
| 导航 | AndroidX Navigation3 1.2.1（类型安全路由） |
| 依赖注入 | 手动 DI（单例挂在 Application） |
| 持久化 | DataStore 1.3.0（Preferences + kotlinx.serialization 类型化存储） |
| 图片加载 | OkHttp + BitmapFactory，自研内存 LRU |
| 网络 | OkHttp 5.5.1 |
| 序列化 | kotlinx.serialization 1.13.0 |
| 异步 | kotlinx.coroutines 1.11.0（含 coroutines-guava 桥接 MediaController Future） |
| 图标 | 本地 Material Symbols 路径内联为 ImageVector，不引 material-icons-extended |
| 自适应布局 | 自研窗口尺寸类（`windowsize/`），不引第三方自适应库 |
| 生命周期 | androidx.lifecycle 2.11.0、activity-compose 1.13.0 |
| 原生层 | NDK 28.2.13676358、CMake 3.22.1（USB 独占的 usbfs 等时传输） |
| 构建 | AGP 9.5.1、Gradle 9.9.0、refreshVersions |

## 项目结构

```
.
├── .github/
│   └── workflows/                      # Release 清理工作流
├── app/
│   └── src/main/
│       ├── kotlin/com/yichao/evilgodxu/
│       │   ├── data/                    # 数据层
│       │   │   ├── cache/               #   缓存台账(分类登记表 / 逐类占用统计 / 冷启动回收)
│       │   │   ├── music/               #   音乐领域
│       │   │   │   ├── analysis/        #     无损容器、PCM 格式、FFT 与全曲频谱、容器头解析
│       │   │   │   ├── api/             #     歌曲 / 封面 / 歌词获取、歌词编解码(LRC / QRC / KRC / lrcx)、音质、翻译与网络客户端
│       │   │   │   ├── clip/            #     分享、默认铃声 / 闹钟设置、可读本地 URI、频谱图导出
│       │   │   │   ├── download/        #     曲目下载与缓存
│       │   │   │   ├── highlight/       #     副歌(高潮)定位(纯歌词分析)、全库片段表(后台扫描 + 落盘)
│       │   │   │   ├── metadata/        #     封面流水线与取色、元数据与歌词读写(区间流式标签读写)、内嵌标签补全、各级缓存
│       │   │   │   ├── model/           #     曲目与搜索数据模型(以平台键为身份)
│       │   │   │   ├── panel/           #     共享播放状态持有器、搜索逻辑与歌词翻译
│       │   │   │   ├── playback/        #     播放状态、队列切换、排序、常听、USB 直出 / 独占与免打扰、按设备音频输出(含缓冲策略)、输出延迟实测、音频信息快照(含蓝牙链路与编解码器解析)
│       │   │   │   ├── proxy/           #     代理音源(导入 / 解析 / 引擎 / 存储)与自定义平台注册表
│       │   │   │   ├── recommend/       #     歌词文本特征(与副歌定位共用)
│       │   │   │   ├── MusicScanner.kt  #     MediaStore 扫描与曲目补全
│       │   │   │   └── PlaylistRefresher.kt  # 播放列表刷新流程
│       │   │   ├── persistence/         #   持久化落点声明与共享路径(磁盘文件名的唯一事实来源)
│       │   │   ├── playlist/            #   歌单存储(智能 / 自定义)、分组与歌手页曲序
│       │   │   ├── repository/          #   设置仓库
│       │   │   └── settings/            #   设置 DataStore、播放与播放页排版偏好、系统应用语言镜像
│       │   ├── floatingwindow/          # 迷你播放器悬浮窗管理与控制器
│       │   │   └── miniplayer/          #   迷你播放器浮层 / 紧凑条 / 展开式播放列表
│       │   ├── localization/            # 应用内多语言管理
│       │   ├── log/                     # CrashLogManager
│       │   ├── navigation/              # Navigation3 类型安全路由与导航宿主
│       │   ├── permission/              # 权限检查、系统授权页往返与授权后回到前台
│       │   ├── screens/                 # 页面(首页 / 设置 / 存储 / 播放页排版 / 频谱 / 元数据)
│       │   │   ├── home/                #   首页播放器 + 启动期授权串行 + 歌单(含歌手页) + 在线搜索
│       │   │   │   ├── compact/         #     竖屏组装器(compact/player:竖屏播放器)
│       │   │   │   ├── expanded/        #     横屏组装器(expanded/player:横屏播放器)
│       │   │   │   └── component/       #     analysis / audioinfo / bar / dialog / panel / permission / player / playlist(含 playlist/expanded)/ queue / search / shell / swipe
│       │   │   ├── settings/            #   外观 / 语言 / 播放 / 代理音源 / 存储 / 关于
│       │   │   │   └── component/       #     appearance / cache / dialog / entry / info / language / playback / proxy
│       │   │   ├── cache/               #   存储 / 缓存管理(assembly / component)
│       │   │   ├── spectrum/            #   频谱分析页(assembly / component)
│       │   │   ├── typography/          #   播放页排版设置(assembly / component)
│       │   │   └── metadata/            #   元数据编辑页(assembly / component)
│       │   ├── service/                 # MediaSessionService 播放引擎
│       │   ├── theme/                   # Material 3 配色与字体
│       │   ├── ui/                      # 全局共享 UI:封面渐隐 / 过渡 / 预取 / 流动帧存储,对话框与选择器骨架,播放器与分区部件,图标
│       │   ├── update/                  # 检查更新、应用内更新与 APK 校验
│       │   ├── utils/                   # 通用工具
│       │   ├── windowsize/              # 窗口尺寸类与横屏形态判定
│       │   ├── App.kt                   # Application 入口(持有单例与组合局部)
│       │   ├── AppContent.kt            # 根可组合项(导航宿主与全局弹窗)
│       │   ├── MainActivity.kt          # 唯一 Activity(系统栏、外部意图、应用语言)
│       │   └── MainViewModel.kt         # Activity 专属 ViewModel(含 AppUiState)
│       ├── cpp/                         # 原生层:USB 独占的 usbfs 等时传输(JNI 桥 + 等时流)
│       └── res/                         # 资源(values / values-en)
├── archive/                             # 归档的功能试验(音质与 AI 识别、每日推荐)，不参与构建
├── baselineprofile/                     # 基准配置与启动配置源集
├── gradle/
│   ├── libs.versions.toml               # 版本目录(依赖管理)
│   └── wrapper/
├── docs/                                # 代理音源规范、界面截图与设计稿
├── LICENSE
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

自定义代理音源的接口规范见 [忆潮代理音源规范](docs/忆潮代理音源规范.md)。
APK 体积、对齐与签名的体检工具改用[模板仓库](https://github.com/Evilgodxu/android-template)发布的那一份，本仓库不再自带。

## 权限

| 权限 | 用途 |
| --- | --- |
| 悬浮窗 | 后台播放时的迷你播放器，及其展开的播放列表 |
| 全部文件访问 | 写回音频文件标签、导出封面 |
| 音乐访问(`READ_MEDIA_AUDIO`) | 读取设备曲库并播放 |
| 图片(`READ_MEDIA_IMAGES`、`READ_MEDIA_VISUAL_USER_SELECTED`) | 内嵌封面与本地封面候选；Android 14「选中的照片」部分授权同样视为已授权 |
| 蓝牙(`BLUETOOTH_CONNECT`) | 读取当前蓝牙输出设备的名称、地址与协商出的编解码器（音频信息） |
| 前台服务(`mediaPlayback`、`FOREGROUND_SERVICE_MEDIA_PLAYBACK`) | 后台播放 + 通知栏 / 锁屏控制 |
| 通知(`POST_NOTIFICATIONS`) | 版本更新下载完成通知(Android 13+) |
| 网络(`INTERNET`、`ACCESS_NETWORK_STATE`) | 在线搜索、歌词与封面获取、检查更新 |
| 音频设置(`MODIFY_AUDIO_SETTINGS`) | 播放引擎的音频配置，含 USB 直出按设备声明申请动态混音器属性所需 |
| 免打扰(`ACCESS_NOTIFICATION_POLICY`) | USB 直出期间自动切至「仅闹钟」档位，屏蔽通知与铃声干扰（不压制媒体流） |
| 唤醒锁(`WAKE_LOCK`) | 熄屏后维持播放引擎运行 |
| 安装应用(`REQUEST_INSTALL_PACKAGES`) | 应用内更新时拉起系统安装器 |
| 修改系统设置(`WRITE_SETTINGS`) | 将曲目设为默认来电铃声 / 闹钟铃声 |

启动链按「音乐访问 → 通知 → 全部文件访问」推进，每项每轮最多询问一次，拒绝后同一轮不再追问，下次启动重新走一遍。全部文件访问排在末段——它只影响写回标签与封面，不该挡在能听歌之前；用户当场拒绝后，长按菜单里的重命名、改歌词、换封面、删除，以及打开元数据编辑页时，都会就地再申请一次。其余权限都在用得到它的那一刻申请：图片在元数据编辑页与选择本地封面时，蓝牙在音频信息面板打开时，免打扰在打开 USB 直出时，修改系统设置在设为默认铃声时。系统授权页与应用在同一任务内打开，返回即回到应用；用户在授权页拨开开关后不会自己回返，应用监测到授权后把界面带回前台。Android 14 的「选中的照片」部分授权与完整图片权限同等看待——查询仍能返回用户已选中的图片，足以挑选封面。

## 快速开始

### 环境要求

- JDK 21 - 25
- Android Studio（建议最新稳定版）
- 包含 API 37(`compileSdk`)的 Android SDK

### 构建

```bash
git clone https://github.com/Evilgodxu/EchoTide-Music.git
cd EchoTide-Music

# 调试包
./gradlew assembleDebug

# 发布包（需先配置签名，见下文）
./gradlew assembleRelease
```

APK 输出为 `app/build/outputs/apk/` 下的 `EchoTideMusic-<版本号>-arm64-v8a.apk`，仅构建 `arm64-v8a` ABI。

### 发布签名

Release 构建从项目根目录的 `local.properties` 读取签名凭据：

```properties
KEYSTORE_PASSWORD=你的签名库密码
KEY_ALIAS=你的签名别名
KEY_PASSWORD=你的别名密码
```

签名库文件默认位于项目根目录 `jh.keystore`（如需调整请修改 `app/build.gradle.kts` 中的 `storeFile`）。两个文件均已被 git 忽略，请勿提交。

## 免责声明

忆潮音乐是一款面向本地曲库的播放器。内置的在线能力仅覆盖公开的歌曲搜索、封面获取、歌词获取与试听播放；
代理音源仅用于声明个人网络接口配置，不提供基于运行时的破解服务。

## 致谢

- 网易云音乐搜索服务曾参考 [Qplayer](https://github.com/TIMER-err/qplayer)
- 列表拖拽排序参考 [Reorderable](https://github.com/Calvin-LL/Reorderable) 等价实现，未采用依赖方式
- 基于 [musicdl](https://github.com/CharlesPikachu/musicdl) 实现网易云音乐、酷狗、酷我、QQ 的 Kotlin 原生搜索服务
- 应用图标由小红书作者「新建文件夹001」（[小红书主页](https://xhslink.cn/o/1dZk6ujEe8N)，ID 5282478962）设计，已获授权使用

## License

[AGPL-3.0](LICENSE) © 2026 Evilgodxu
