# APK 优化与体检工具

对已构建的 release APK 做体积归因、安全的重新压缩、16 KiB 页对齐与重新签名，
并对照官方优化清单核验构建期配置。

## 能力边界

**能做的（后处理阶段）**

| 项 | 手段 | 依据 |
| --- | --- | --- |
| 体积 | 按条目决定 STORED / DEFLATE，deflate 提升至 zlib 9 级 | ZIP 容器层面，不改写任何条目字节 |
| 体积 | 逐条实测收益，低于阈值时不重写产物 | 避免为了几十字节更换签名块 |
| 加载性能 | `zipalign -P 16 4`：未压缩条目 4 字节、`.so` 按 16 KiB 页对齐 | 使运行时可用 `mmap` 直接加载，兼容 16 KiB 页设备 |
| 可安装性 | 重打包后以 `apksigner` 重签并校验 | 任何改动都会使原签名失效 |

**不能做的**

- **无法通过后处理提升运行性能。** 代码优化、DEX 布局、启动加速都由 R8 在编译期完成；
  官方明确提示：替换或修改 R8 产物的工具会破坏其代码级、DEX 布局与 Baseline Profile 优化，
  甚至导致性能回退。本工具因此把 `classes*.dex`、`resources.arsc`、
  `AndroidManifest.xml`、`lib/**/*.so` 列为受保护条目，一律拒绝改写。
- 无法裁剪未使用的代码与资源，那是 R8 与资源缩减的工作。

真正的性能与体积杠杆在构建期，由 `advise` / `audit` 子命令核验。

## 依赖

- Python 3.11+（仅用标准库）
- Android SDK build-tools（`zipalign`、`apksigner`、`aapt2`）

SDK 定位顺序：`--build-tools` → `--sdk` → `ANDROID_HOME` / `ANDROID_SDK_ROOT`
→ 沿目录上溯读取 `local.properties` 的 `sdk.dir` → 平台默认路径。

## 用法

```bash
# 体检：体积归因、存储方式偏差、对齐、签名、可回收体积
python tools/apk-optimizer/apkopt.py analyze

# 体检 + 构建期优化清单，并导出报告
python tools/apk-optimizer/apkopt.py audit --md docs/APK体积与性能报告.md

# 试运行：只计算收益，不写文件
python tools/apk-optimizer/apkopt.py optimize --dry-run

# 执行优化：输出到 <APK 所在目录>/optimized/，源文件保持原样
python tools/apk-optimizer/apkopt.py optimize

# 指定签名方案（默认 v2；auto 表示沿用源包已有方案）
python tools/apk-optimizer/apkopt.py optimize --signing-schemes v2
python tools/apk-optimizer/apkopt.py optimize --signing-schemes v2,v3

# CI 断言：对齐、签名、体积预算
python tools/apk-optimizer/apkopt.py verify --max-size 6291456 --require-baseline-profile
```

未指定 APK 时，自动在 `app/build/outputs/apk/release`、`app/release`、`release` 中查找。

**输出位置**：默认写入 `<APK 所在目录>/optimized/<原名>-optimized.apk`，
即 `app/build/outputs/apk/release/optimized/`；用 `--out DIR` 改写。
中间产物放在 `<out>/.work/`，流程结束即删除。源 APK 永不覆盖。

常用参数：

| 参数 | 说明 |
| --- | --- |
| `--out DIR` | 输出目录，默认 `<APK 所在目录>/optimized` |
| `--signing-schemes` | 签名方案，默认 `v2`；`auto` 沿用源包；可组合 `v1,v2,v3` |
| `--force` | 收益低于阈值也执行重压缩 |
| `--min-savings N` | 触发重压缩的最小收益字节数，默认 4096 |
| `--keystore PATH` | 密钥库，默认取项目根目录下的 `.jks` / `.keystore` |
| `--json PATH` / `--md PATH` | 结构化结果 / Markdown 报告 |
| `--sdk` / `--build-tools` | 显式指定 SDK |

退出码：`0` 正常，`1` 执行错误，`2` 校验未通过。

## 签名方案取舍

| 方案 | 生效范围 | 说明 |
| --- | --- | --- |
| v1 | API ≤ 23 | JAR 签名，会在 `META-INF` 留下签名文件 |
| **v2** | API ≥ 24 | 整包校验，**默认**；`minSdk 34` 下已足够 |
| v3 | API ≥ 28 | 额外支持密钥轮换（proof-of-rotation） |
| v4 | — | 仅服务于 `adb install --incremental`，默认关闭 |

选择 v2 会放弃 v3 的密钥轮换能力；若计划在使用同一密钥的前提下更换密钥库，
应改用 `--signing-schemes v2,v3`。仅签 v2 而由 v3 签名版本升级安装不受影响——
两者校验的证书相同。

> 注意：`apksigner` 会依据 APK 的 `minSdkVersion` 省略冗余方案。本项目
> `minSdk 34`，所有设备都能校验 v3，因此 `--signing-schemes v2,v3` 实际只写入 v3。
> 要得到 v2 签名，必须显式关闭 v3——即使用 `--signing-schemes v2`。


## 安全约束

- **非破坏式**：优化产物写入独立目录，源 APK 永不覆盖，可随时回退比较。
- **先对齐后签名**：`zipalign` → `apksigner`，与官方要求的顺序一致。
- **签名方案显式声明**：默认 v2，可用 `--signing-schemes` 调整；v1/v2/v3 全部显式传参，
  结果不随 apksigner 默认策略变化。
- **密码经环境变量传递**：不出现在命令行参数与日志中。
- **收益不足即放弃**：重打包后体积若反而变大，删除产物并保留原包。

## 目录结构

```text
tools/apk-optimizer/
├── apkopt.py          # 启动入口
├── README.md
└── apkopt/
    ├── cli.py         # 参数解析与命令分发
    ├── sdk.py         # SDK 定位与原生工具封装
    ├── apk.py         # APK 容器只读视图
    ├── policy.py      # 受保护条目与压缩策略
    ├── recompress.py  # 逐条存储方式决策与实际试压
    ├── analyzer.py    # 体积归因、对齐与签名核验
    ├── optimizer.py   # 重压缩 → 对齐 → 签名流水线
    ├── advisor.py     # 构建期配置体检
    └── report.py      # 终端与 Markdown 渲染
```
