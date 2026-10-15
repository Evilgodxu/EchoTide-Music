# 项目约定

适用于本仓库的开发约定。与用户级 `~/.dsh/AGENTS.md` 冲突时以本文件为准。

## 构建验证

- 改动后至少验证 `:app:assembleDebug`、`:app:testDebugUnitTest`、`:app:lintDebug` 三项。
- lint 必须保持 **0 errors / 0 warnings**；新增问题当轮修掉，不积压、不用基线文件兜底。
- 覆盖 UI 与资源变更时同时验证 `:app:assembleRelease`（R8 优化与资源缩减路径与 debug 不同）。

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

## 依赖库资源的覆盖

覆盖依赖库资源（图标、主题、字符串）时：

- **同名同类型放置**在对应 `res/` 目录下，库按名解析即可命中覆盖版本。
- **位图放 `drawable-nodpi/`**：覆盖库的通知小图标等须按原尺寸呈现的位图不随密度缩放；
  放在无限定的 `drawable/` 会被 `IconLocation` 判为位置不当。
- **保留覆盖意图的记录**：在 `docs/注意事项.md` 记明该资源来自哪个库、用途是什么，
  避免后人（或静态检查）再把它当无用资源删除。
