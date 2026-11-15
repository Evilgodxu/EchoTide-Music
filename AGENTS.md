# 项目约定

适用于本仓库的开发约定。与用户级 `~/.dsh/AGENTS.md` 或全局自定义指令冲突时，一律以本文件为准。

## 技术栈与依赖

- 单 Activity + Jetpack Compose（Material 3）+ Navigation3，状态管理为 MVVM + 单向数据流。
- 依赖版本统一写在 `gradle/libs.versions.toml`，用 `./gradlew refreshVersions` 巡检；不手写单个依赖版本。
- 巡检插件从本机仓库（`~/.m2`）消费，`settings.gradle.kts` 里的版本号须与 `../refreshVersions/plugins/version.txt`
  一致；改动该源码分支后先在其 `plugins/` 目录执行 `./gradlew publishToMavenLocal`，再巡检。
- 只接受正式版：`settings.gradle.kts` 的 `rejectVersionIf` 已滤除带连字符的预发布标签，升级时不要绕过该过滤。
- Release 优化统一走 AGP 9.3+ 的 `optimization { enable = true }`：它同时开启代码优化、优化型资源缩减并内置
  等价于 `proguard-android-optimize.txt` 的默认规则，不要再写回 `isMinifyEnabled` / `isShrinkResources` /
  `getDefaultProguardFile`。本项目的额外保留规则由 `app/src/release/keepRules/*.keep` 源集提供。

## 架构与状态

```text
DataStore → Repository → ViewModel → UiState → UI      状态自上而下
UI → ViewModel → Repository → DataStore                事件自下而上
```

- **应用级状态**（主题 / 语言 / 版本）由 Activity 作用域的 `MainViewModel` 聚合为 `AppUiState`，经
  `LocalMainViewModel` 下发；主题、本地化与各页面共用这一份。
- **页面级状态**由 `{Screen}ViewModel` 持有 `MutableStateFlow<{Screen}UiState>`，对外只暴露不可变 `StateFlow`；
  作用域由 Navigation3 的 `rememberViewModelStoreNavEntryDecorator()` 绑定到导航条目，出栈即回收。
- 状态变化用框架原生机制响应：`StateFlow`、`collectAsStateWithLifecycle()`、`CompositionLocal`。
- 依赖注入是**手动 DI**：`App` 构造一站式 `AppContainer`，Activity、系统组件与 ViewModel 经
  `App.container` 或 `viewModelFactory` 取构造参数。不引入 Hilt / Koin / Dagger。
- 系统创建的组件（Service、无界面 Activity）无法构造注入，经 `(application as App).container` 取共享单例；
  容器不对外转发成员，也不承担启动编排——启动顺序属于 `App.onCreate`。
- ViewModel 只依赖仓库接口，不直接触碰 DataStore、文件或网络。

## 数据持久化

- 所有持久化统一走 DataStore，不再使用 SharedPreferences。
- 简单键值（设置、播放快照、背景取色等）用 Preferences DataStore，键统一放 `SettingsKeys`。
- 结构化数据（歌单、歌手曲序、代理音源、播放队列、常听、搜索历史、更新信息、心动高亮段）
  用类型化 DataStore + kotlinx.serialization；每份数据以 `PersistedData(..., DATA_STORE_JSON)`
  在存储模块旁声明，读写与存储页统计共用这一处名字。
- 写入一律 `suspend` + `DataStore.updateData {}`，同一份状态整份原子提交，不做「内存先改、异步补写」。
- 读取侧经 `catchPersistenceIo` 兜底磁盘 IO 异常，避免一条读失败让整个状态回流中断。
- 冷启动首帧镜像（`BootMirrorStore`）是唯一例外：它是 DataStore 的首帧缓存，不承担语义，
  完整状态仍以 DataStore 为准。
- 应用语言同时写入系统 `LocaleManager`；冷启动由系统在创建 Activity 前配置资源，不再自建启动语言镜像。

## 单元测试

- 位置 `app/src/test/kotlin/`，包名与主源码一致。
- 覆盖对象：纯逻辑（版本比较、格式解析、编解码）与状态持有者（ViewModel 的写入与回流）；不测像素级布局。
- 网络与磁盘不参与单测；时间与调度用 `kotlinx-coroutines-test`：`Dispatchers.setMain(StandardTestDispatcher())`
  配合 `advanceUntilIdle()`，实验性 API 显式标注 `@OptIn`。

## 构建验证

- 覆盖 UI 与资源变更时同时验证 `:app:assembleRelease`：R8 优化与资源缩减路径与 debug 不同。
- `app/lint.xml` 已把 `Deprecated` 与 `UnsafeOptIn*` 提级为 error，弃用 API 须迁移到等价接口。

## Lint 未使用资源（UnusedResources）的判断

> 这条约定的由来：`media3_icon_circular_play.png` 曾被判为未使用资源删除，
> 实际它是 media3 通知图标的**按名覆盖**资源，删除会让通知栏图标静默回落到库默认图。

`UnusedResources` 对**「被依赖库按资源名解析」的资源**会误报。这类资源的引用不在应用源码里，
而在依赖库自身的 `values.xml` 中，因此**「全仓库文本搜索无引用」不足以判定资源已废弃**。

删除任何被判为未使用的资源前，须按顺序排查：

1. **查依赖库是否声明了同名资源**

   ```powershell
   # 从 AAR 中查同名资源
   $aar = Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1" -Recurse -Filter "*.aar" |
          Where-Object { $_.Name -match "media3|androidx" } | Select-Object -First 50
   foreach ($a in $aar) {
     $zip = [System.IO.Compression.ZipFile]::OpenRead($a.FullName)
     $zip.Entries | Where-Object { $_.FullName -match "<资源名>" } | ForEach-Object { "$($a.Name): $($_.FullName)" }
     $zip.Dispose()
   }
   ```

2. **查库的 `values.xml` 是否按名引用它** —— 这一步才是关键。库常以
   `<drawable name="X">@drawable/<资源名></drawable>` 的形式间接引用：

   ```powershell
   # 解出 AAR 后检索库 XML
   Get-ChildItem -Recurse $extractedDir -Include *.xml |
     Select-String -Pattern "<资源名>"
   ```

3. **确认是否为本应用的覆盖版本** —— 比对字节哈希。哈希不同说明是自定义覆盖，不是冗余副本：
   删除等于放弃自定义外观。

4. 确认是误报后，用**定向 `lint.xml`** 排除（见下节），不要删除资源，也不要 `disable` 整个检查。

## Lint 抑制的写法

优先选作用域最小、能自证理由的方式：

| 场景 | 做法 |
|---|---|
| 单个 XML 元素可标注 | 该元素上 `tools:ignore="检查名"`（根元素补 `xmlns:tools`） |
| 单个二进制资源 / 需要限定路径 | `app/lint.xml` 内 `<ignore path="...">`，仅排除该文件 |
| 检查与源码位置无关（如 `ChromeOsAbiSupport` 取决于 ABI 配置） | `build.gradle.kts` 的 `lint { disable += ... }` |

- **能真修就不要抑制**：先确认该项是否指出了真实缺陷。例：`IconLocation` 指出位图放在无密度限定的
  `drawable/`，官方给的做法是移入 `drawable-nodpi/`（刻意不随密度缩放时），而非抑制；
  `SelectedPhotoAccess` 指出未处理 Android 14 部分照片授权，补上细分权限并调整授权判定即为真修。
- 二进制资源无法内联注释，抑制理由写在 `lint.xml` 里。

## 组件归属

- 跨页面复用的组件上提至 `ui/component/`。
- 无跨页面复用的组件下沉到所属页面的 `component/`。

## 页面根包

- 页面根包只允许存放骨架文件，其余业务文件一律进入子目录收敛。
- 骨架白名单：`XxxScreen.kt`、`XxxUiState.kt`、`XxxViewModel.kt`。
- 其余文件进入 `assembly/`、`component/` 等子目录，或按业务语义新建子目录。

## 布局与自适应

- 屏幕必须同时适配窄窗与宽窗。窗口尺寸类在 `{Screen}Screen` 单一位置计算，作为参数显式下传给组装器；
  组件只按收到的布局标志位渲染，不自行读取窗口尺寸。
- 形态差异优先用「布局标志位 + 宽度上限」表达（`ContentPane` 的行宽上限即此类）。同一份内容不维护两套组装器。
- 只有当两种形态的功能区块结构确实不同（区块增减、导航形态改变）时，才拆 `compact/` 与 `expanded/`
  两套组装器；二者必须消费同一份 `UiState` 与同一套事件回调，禁止各自定义状态或事件。当前仅首页属于此类
  （竖屏 pager + 竖屏播放器 / 横屏侧栏歌单 + 横屏播放器）。
- 布局由组装器组装页面 `component/` 或 `ui/component/` 下的组件得出，不在同一文件内硬编码多种布局；
  共用组件经组装器参数注入，组装器本身不承载业务逻辑。
- 拆分判据是「单一问题」：可独立交互 / 预览 / 测试的功能区块才拆成组件；单行 `Text`、`Spacer` 这类微结构内联；
  只服务当前屏幕的私有组装留在同文件的 `private` Composable 中。
- 页面级弹层与跨形态副作用挂在 `{Screen}Screen` 骨架层，不随形态重复实现。

## 页面模块目录示例

```text
screens/home/
├── assembly/                      # 组装器(必须)
│   └── HomeAssembly.kt            #   结构差异大时改为 compact/ + expanded/ 两套
├── component/                     # 页面组件(必须)
│   ├── …/
│   │   └── ….kt
│   └── …/
├── HomeScreen.kt                  # 骨架(必须)
├── HomeUiState.kt                 # 骨架(必须)
└── HomeViewModel.kt               # 骨架(必须)
```

`{Screen}Screen` 只做 ViewModel 连接、尺寸类计算与副作用；至少有一个无状态 Composable 接收 `UiState`
与事件回调，脱离 ViewModel 即可预览。

## 依赖库资源的覆盖

覆盖依赖库资源（图标、主题、字符串）时：

- **同名同类型放置**在对应 `res/` 目录下，库按名解析即可命中覆盖版本。
- **位图放 `drawable-nodpi/`**：覆盖库的通知小图标等须按原尺寸呈现的位图不随密度缩放；
  放在无限定的 `drawable/` 会被 `IconLocation` 判为位置不当。
- **保留覆盖意图的记录**：在 `docs/注意事项.md` 记明该资源来自哪个库、用途是什么，
  避免后人（或静态检查）再把它当无用资源删除。

## 未明确决策

- 设计存在多种方案时择优；影响面超出单文件且无官方推荐时，先确认再动手。
- 当前技术栈有官方推荐做法时，按官方推荐处理。