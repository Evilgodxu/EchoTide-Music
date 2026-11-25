# 项目约定

适用于本仓库。与用户级或全局指令冲突时，以本文件为准。

## 技术栈与依赖

- 单 Activity + Jetpack Compose（Material 3），MVVM + 单向数据流。
- 依赖版本只在 `gradle/libs.versions.toml` 维护，用 `./gradlew refreshVersions` 巡检，不手写版本号。巡检插件从 `~/.m2` 消费，`settings.gradle.kts` 的版本须与 `../refreshVersions/plugins/version.txt` 一致；改动插件源码后先在其 `plugins/` 执行 `publishToMavenLocal`。只接受正式版，不要绕过 `rejectVersionIf` 的连字符过滤。
- Release 优化走 AGP 9.3+ 的 `optimization { enable = true }`，不再写 `isMinifyEnabled` / `isShrinkResources` / `getDefaultProguardFile`。额外保留规则放 `app/src/main/keepRules/*.keep`，只保留仍被依赖的库所需条目。

## 架构与状态

```text
DataStore → Repository → ViewModel → UiState → UI      状态自上而下
UI → ViewModel → Repository → DataStore                事件自下而上
```

- 应用级状态（主题 / 语言 / 版本）由 Activity 作用域的 `MainViewModel` 聚成 `AppUiState`，经 `LocalMainViewModel` 下发。
- 页面级状态由 `{Screen}ViewModel` 持有 `MutableStateFlow<{Screen}UiState>`，对外只暴露不可变 `StateFlow`。
- 导航是分层覆盖层（`navigation/AppRoot.kt`）：播放页常驻底层，设置 / 频谱 / 元数据作为 `AppDestination` 覆盖层从右侧滑入；每层独立 `ViewModelStore`，关闭即清空。没有路由栈，不引入导航库。
- 状态变化只用框架机制：`StateFlow`、`collectAsStateWithLifecycle()`、`CompositionLocal`。
- 手动 DI：`App` 构造 `AppContainer`，系统组件经 `(application as App).container` 取单例；不引入 Hilt / Koin / Dagger。启动编排属于 `App.onCreate`。
- ViewModel 只依赖仓库接口，不直接触碰 DataStore、文件或网络。

## 数据持久化

- 统一 DataStore，不用 SharedPreferences。键值用 Preferences DataStore（键放 `SettingsKeys`），结构化数据用类型化 DataStore + kotlinx.serialization。
- 每份数据在存储模块旁以 `PersistedData(..., DATA_STORE_JSON)` 声明；读写与存储页统计共用这一处名字。
- 写入一律 `suspend` + `DataStore.updateData {}`，整份原子提交，不做「内存先改、异步补写」；读取经 `catchPersistenceIo` 兜底 IO 异常。
- `BootMirrorStore` 只是首帧缓存，语义仍以 DataStore 为准。
- 应用语言同时写入系统 `LocaleManager`。

## 单元测试

- 位置 `app/src/test/kotlin/`，包名与主源码一致；只测纯逻辑与状态持有者，不测像素级布局。
- 仓库接口用内存 fake，不引入 Mockito / MockK；测试方法名用反引号中文描述行为。
- 时间与调度用 `kotlinx-coroutines-test`：`Dispatchers.setMain(StandardTestDispatcher())` + `advanceUntilIdle()`。

## 构建验证

- 改动后跑 `:app:assembleDebug`、`:app:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleRelease`。
- Lint 须 0 error 0 warning；`allWarningsAsErrors` 已开，弃用 API 必须迁移到等价接口。
- `checkReleaseBuilds = false`：发布前执行 `./gradlew :app:lintRelease` 或 `./gradlew check`。
- 删 `UnusedResources` 前先查依赖库是否按名解析（解开 AAR 看 `values.xml`）。确认误报后在 `app/lint.xml` 定向 `ignore path` 并写明理由，不 `disable` 整个检查。

## 页面模块

页面根包只放骨架：`XxxScreen.kt`、`XxxUiState.kt`、`XxxViewModel.kt`、`XxxEnvironment.kt`；其余文件进 `assembly/`、`component/` 或按业务语义新建的子目录。

- **优先复用现有形态**：能留在当前页内的，依次用页内层级（只换标题与内容、接管返回，如 `SettingsPage`）→ 页内面板 / 弹窗 / 分层覆盖层；只有从别的页面进入的目的地才新增 `AppDestination` 与页面骨架。
- `assembly/` 必需：布局由 `component/` 组件组装，组装器不承载业务逻辑。仅当两种形态的区块结构确实不同才拆 `compact/` 与 `expanded/`（当前只有首页），二者共用同一份 `UiState` 与事件回调。
- `{Screen}Screen` 只做 ViewModel 连接、尺寸类计算与跨形态副作用；至少一个无状态 Composable 可脱离 ViewModel 预览。
- 跨页面组件放 `ui/component/`，页面内组件放该页 `component/`。

## 布局与自适应

- 窄窗与宽窗都要成立。窗口尺寸类只在 `{Screen}Screen` 计算并作为参数下传，组件不自行读取尺寸。
- 形态差异优先用「布局标志位 + 宽度上限」（`ContentPane`）表达，同一份内容不维护两套组装器。
- 页面的下一级以页内层级留在本页，共用标题栏与转场；层级可自带状态持有者（如 `CacheViewModel`）。

## 界面

- 遵循项目现有风格，照着首页与设置页的既有实现做，不另立规范。
- 颜色只取 `MaterialTheme.colorScheme`（`theme/Color.kt` 与图表分类色除外）；形状、间距、透明度、动效取 `theme/Style.kt` 的 `AppShape` / `AppSpacing` / `AppAlpha` / `AppMotion`，不写字面量。
- 深浅两套主题都要成立；共享组件能覆盖就不得自造（`PageTopBar`、`ContentPane`、`GroupCard`、`AppDialog` / `DialogCard` / `DialogOption`、`AppSwitch`、`ExpandPicker`、`AppIcons`）。
- 首页是唯一沉浸舞台；其余页面跟随主题，自行 `navigationBarsPadding()`（带键盘的页面取 `ime.union(navigationBars)`）。

## 依赖库资源覆盖

- 覆盖库的通知图标等资源：同名同类型放对应 `res/`，位图放 `drawable-nodpi/`。
- 覆盖意图写在资源旁的代码注释或 `lint.xml` 理由里，避免被当成无用资源删除。

## 注释

- 注释讲意图与取舍，不复述代码，不写来源考证与变更过程。临时埋点用完即删。

## 提交与交付

- 提交信息用 `type(scope): 中文描述`，type 取 `feat` / `fix` / `refactor` / `build` / `docs` / `perf`；正文说明为什么改。
- 版本号提升单独一笔 `build: 升级版本号至 x.y.z`。
- 多行消息用 Bash heredoc（`git commit -F - <<'MSG'`）；本机是 Git Bash，不要用 PowerShell here-string。未推送时用 `git commit --amend -F -` 改正，已推送不改写历史。
- 改动落地并验证后构建 `./gradlew :app:assembleRelease`，把 `app/build/outputs/apk/release/EchoTideMusic-<versionName>-arm64-v8a.apk` 作为对话内附件卡片直接交付；交付前确认产物修改时间晚于最后一次代码改动。

## 历史笔记

- 排查与设计笔记归档在 `docs/archive/`，只作背景参考，不作为约定。
