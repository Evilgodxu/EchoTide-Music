# 项目约定

适用于本仓库的开发约定。与用户级 `~/.dsh/AGENTS.md` 或全局自定义指令冲突时，一律以本文件为准。

## 技术栈与依赖

- 单 Activity + Jetpack Compose（Material 3）+ Navigation3，状态管理为 MVVM + 单向数据流。
- 依赖版本统一写在 `gradle/libs.versions.toml`，用 `./gradlew refreshVersions` 巡检；不手写单个依赖版本。
- 巡检插件从本机仓库（`~/.m2`）消费，`settings.gradle.kts` 里的版本号须与 `../refreshVersions/plugins/version.txt`
  一致；改动该源码分支后先在其 `plugins/` 目录执行 `./gradlew publishToMavenLocal`，再巡检。
- 只接受正式版：`settings.gradle.kts` 的 `rejectVersionIf` 已滤除带连字符的预发布标签，升级时不要绕过该过滤。
- 取舍顺序：语言标准库 → Compose / AndroidX 原生能力 → 按源码等价自实现 → 引入依赖。
- 调用链路取最短：不设只做转发的中间层；拆分以抑制碎片化为界，聚合以上帝类为界。
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
- 状态变化用框架原生机制响应：`StateFlow`、`collectAsStateWithLifecycle()`、`CompositionLocal`。禁止把状态
  包成自定义回调层层转发，也禁止为同一份状态另存副本。
- 依赖注入是**手动 DI**：`App` 构造一站式 `AppContainer`，Activity、系统组件与 ViewModel 经
  `App.container` 或 `viewModelFactory` 取构造参数。不引入 Hilt / Koin / Dagger。
- 系统创建的组件（Service、无界面 Activity）无法构造注入，经 `(application as App).container` 取共享单例；
  容器不对外转发成员，也不承担启动编排——启动顺序属于 `App.onCreate`。
- ViewModel 只依赖仓库接口，不直接触碰 DataStore、文件或网络。

## 数据持久化

- 设置项以 DataStore Preferences 为单一事实源，经 `SettingsRepository` 接口访问；实现可替换为内存 fake 供单测。
- 写入一律 `suspend` + `dataStore.edit {}`；同一 `edit` 内修改多个键以保证原子性。
- 新增设置项：键放 `SettingsKeys`，枚举以稳定字符串落盘，读取时未知值回落到默认项而非抛错
  （参照 `ThemeMode.fromValue`）。
- 存量多键存储（自定义歌单、代理音源等）仍在 SharedPreferences，**不迁移、不混用**：新增设置项一律走
  DataStore，只有改动存量存储时才按下节判断 `commit` / `apply`。

## 单元测试

- 位置 `app/src/test/kotlin/`，包名与主源码一致。
- 覆盖对象：纯逻辑（版本比较、格式解析、编解码）与状态持有者（ViewModel 的写入与回流）；不测像素级布局。
- 用内存 fake 实现仓库接口替代 mock 框架，不引入 Mockito / MockK。
- 网络与磁盘不参与单测；时间与调度用 `kotlinx-coroutines-test`：`Dispatchers.setMain(StandardTestDispatcher())`
  配合 `advanceUntilIdle()`，实验性 API 显式标注 `@OptIn`。
- 新增测试用反引号中文描述行为（如 `` `写入主题经仓库回流更新状态` ``），断言对外可见的状态，不断言私有实现。

## 构建验证

- 改动后至少验证 `:app:assembleDebug`、`:app:testDebugUnitTest`、`:app:lintDebug` 三项。
- lint 必须保持 **0 errors / 0 warnings**；新增问题当轮修掉，不积压、不用基线文件兜底。
- 覆盖 UI 与资源变更时同时验证 `:app:assembleRelease`（R8 优化与资源缩减路径与 debug 不同）。
- Kotlin 已开启 `allWarningsAsErrors`，警告即构建失败；`app/lint.xml` 已把 `Deprecated` 与 `UnsafeOptIn*`
  提级为 error，弃用 API 须迁移到等价接口而非抑制。

## 注释

- 解释意图与设计决策，不复述代码行为。
- 代码变更时同步更新，不留过期注释。
- 不写内容溯源与背景交代（「曾经」「来自某版本」这类），历史交给 git。

## 删除与重命名

- 删除须指定精确路径，逐条列举、分批执行；严禁通配符匹配或递归大范围删除。
- 删除前确认存在可回退存档：已纳入 git 即可回退，未跟踪文件先备份。
- 文件改名用 `git mv`，保留历史；改名的同时清掉旧文件名与旧符号的全部引用。

## 日志：按需埋点，不做防御性埋点

日志不是资产，是为回答某个具体问题临时支起来的脚手架——**问题答完就撤**。判据只有一条：
**它要回答的那个问题是否还存在**；与级别无关，异常级的留痕同样适用。

> 这条约定的由来：原生层曾埋过一批「怕它崩」的留痕，含整套原生崩溃记录（信号处理器 +
> `native_crash.log` + 启动时回收）。后续确认原生层并无异常，这批留痕整体撤除，并把只为它存在的
> 东西一并撤掉——`liblog` 链接、送流误期记账、失败文案、只被日志调用的辅助函数。留下的不是
> 「更保险」，而是维护成本，以及一个误导性暗示：「这条日志还在，说明这里可能还有问题」。

新增留痕之前先过这几条：

- **先问它回答哪个具体问题**，以及这个问题现在是否存在。答不上来就不写。
- **「怕出错」不是理由**：防御性留痕会在没人看的前提下持续输出，最后只剩噪音。真出了错按现场补，
  比预先猜准得多——猜错的那部分就是纯负担。
- **「换个环境才出现」也不是理由**：只在特定机型、特定格式上触发，只说明问题难查，不说明该预先埋点。
- **不为守卫与设计内降级留痕**：参数校验、多方案逐个降级这类「设计上就会走到」的分支不记；
  只有「不该发生却发生了」才值得留痕。
- **同一失败只留一处**：下层记完又向上抛的，上层再记一遍就是重复条目——记在最终处置它的那一层。
- **留在读得到的地方**：能进分享日志的统一通道（`CrashLogManager`）就不要直写 `android.util.Log`
  ——用户报问题时读不到，等于没留。`CrashLogManager` 自身落盘失败时降级到 `Log` 是唯一例外。
- **文案带定位上下文**：写清设备、格式、URI、路径这类「换个环境就不同」的量，不写
  `Download failed` 这种无上下文的英文串。

要求：

- **撤就撤干净**：留痕连同只为它存在的死代码一起撤——记账计数器、失败文案、只被它调用的辅助函数、
  仅为它引入的 `#include` 与链接库。留一半比不留更糟。
- **每轮改动复核留痕是否仍对应现存问题**，过期即撤（与「注释不留过期注释」同一条口径）。

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

要求：

- **定向优先于整体**：能只排除一个文件就不要 `disable` 整个检查——整体关闭会连带放过真实问题。
- **每处抑制都写明理由**：说明为什么这是有意为之而非疏漏。二进制资源无法内联注释，理由写在 `lint.xml` 里。
- **能真修就不要抑制**：先确认该项是否指出了真实缺陷。例：`IconLocation` 指出位图放在无密度限定的
  `drawable/`，官方给的做法是移入 `drawable-nodpi/`（刻意不随密度缩放时），而非抑制；
  `SelectedPhotoAccess` 指出未处理 Android 14 部分照片授权，补上细分权限并调整授权判定即为真修。

## SharedPreferences 的 commit 与 apply

`commit()`（同步）与 `apply()`（异步）的选择在本仓库是**有语义的**，迁移 API 时不得改变：

- 关键数据（自定义歌单、代理音源、待更新信息、启动语言镜像）用 `commit(commit = true)`：
  异步落盘在进程被杀时存在丢失窗口，且多处依赖「单次 Editor 一次落盘」保证多字段一致性。
- 非关键数据可用 `apply()`。

KTX 迁移的对应写法：`.edit().putX().commit()` → `.edit(commit = true) { putX() }`；
`.edit().putX().apply()` → `.edit { putX() }`（`edit {}` 默认即 `apply`）。
相关文件中的「同步写盘」注释是设计说明，迁移后须保持其结论仍然成立。

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
- 重构后原类职责须显著下降，且不得引入新的隐式依赖或状态双份。
