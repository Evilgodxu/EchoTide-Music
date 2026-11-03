# 归档：音质异常识别与 AI 音乐识别

本目录保存从项目中移除的两项识别算法及其全部配套代码，供日后查阅或回退。

- 归档日期：2026-10-08
- 取证脚本移入日期：2026-11-03
- 来源仓库：C:/Android/YiChaoMusic
- 归档目录结构：
  - `removed/`：已从工程删除的文件，按原仓库相对路径存放（字节原样保留）。
  - `modified-originals/`：改动前保留引用这两项算法的文件原始版本，按原仓库相对路径存放。
  - `probes/`：为这两项识别算法取证而写的 Python 脚本，从仓库 `tools/` 移入（字节原样保留）。

## 取证脚本（probes/）

均依赖 `numpy` 与 `soundfile`，用法为 `python <脚本> <音频文件>`。

| 脚本 | 用途 |
| --- | --- |
| `ai_detect_probe.py` | 复现三征象判据并输出频谱形态诊断 |
| `ai_feature_probe.py` | 细结构周期性、M/S 相关、谐噪比代理 |
| `deadzone_probe.py` | 判定高频死区是数字静默还是转码噪底 |
| `deadzone_calibration.py` | 有损转码与数字带限的死区对比标定 |
| `final_probe.py` | 分带 L/R 相关、时域动态、起音规律、全曲谱 |
| `fingerprint_probe.py` | 调制域帧率周期性、能量簇、网格纹波 |
| `flac_meta_probe.py` | 枚举 FLAC 元数据并匹配生成器署名 |
| `tag_probe_verify.py` | 用构造容器断言 `AiSourceTagProbe` 的分级匹配 |

## 被移除的功能

1. **音质异常识别**（界面上标注为「音质异常」，即伪无损 / 升频 / 砖墙转码识别）
   - 依据平均功率谱的多条物理证据路径判定 FLAC 是否为伪造无损。
2. **AI 音乐识别**（界面标注为「AI 合成 / 疑似AI」）
   - 生成器署名取证 + 频谱征象（立体声相关性、成像缺口、谐波梳、非原生带宽）合成判定。

两者共用一整套解码与判定基础设施：稀疏窗口频谱解码（`SpectralDecoder`）、全曲判定入口
（`FullSpectrumAnalyzer`）、整库合并批量分析（`LibraryAnalysisRunner`）、全曲锁定表
（`FullAnalysisLock`）与判定结果持久化缓存（`TrackVerdictCache`）。

## removed/ 文件清单

主源码（`data/music/analysis/`）：
- `FakeLosslessAnalyzer.kt` —— 音质异常识别器
- `AiMusicAnalyzer.kt` —— AI 音乐识别器
- `AiSourceTagProbe.kt` —— AI 生成器署名取证
- `FullSpectrumAnalyzer.kt` —— 全曲分析入口（一次解码同时出矩阵与两路判定）
- `FullAnalysisLock.kt` —— 全曲分析锁定表
- `LibraryAnalysisRunner.kt` —— 整库合并批量分析
- `SpectralDecoder.kt` —— 稀疏窗口频谱解码与判定摘要累加器
- `TrackVerdictCache.kt` —— 判定结果持久化缓存

界面（`screens/spectrum/component/`）：
- `SpectrumAnalysisPanel.kt` —— 频谱页两路判定结论区

测试与测试资源：
- `app/src/test/kotlin/.../analysis/AiSourceTagProbeTest.kt`
- `app/src/test/kotlin/.../analysis/AiVerdictProbeTest.kt`
- `app/src/test/resources/ai_verdict_summary.txt`
- `app/src/test/resources/ai_verdict_summary_segmented.txt`

## modified-originals/ 文件清单（改动前的原始版本）

- `data/music/analysis/SpectrogramDecoder.kt`（原含整曲判定摘要产出，现只产频谱图）
- `data/music/analysis/TrackAudioInfoReader.kt`（原含 `readFlacContainerFormat`）
- `data/music/analysis/Fft.kt`（原含供判定用的 `accumulatePower`）
- `screens/spectrum/SpectrumUiState.kt`、`SpectrumViewModel.kt`、`component/SpectrumBody.kt`、`component/SpectrumShareImage.kt`
- `screens/home/component/analysis/LibraryAnalysisSheet.kt`（原含识别类目行与曲库分析控制器）
- `screens/home/compact/CompactAssembly.kt`
- `data/music/PlaylistRefresher.kt`、`data/music/download/MusicDownloader.kt`
- `data/cache/CacheInventory.kt`、`screens/cache/component/CacheGroups.kt`
- `res/values/strings.xml`、`res/values-en/strings.xml`
- `res/xml/backup_rules.xml`、`res/xml/data_extraction_rules.xml`
- `app/src/test/kotlin/.../analysis/FftTest.kt`

## 保留的基础功能

- 查看频谱：`screens/spectrum` 全页面（频谱图、频率刻度、dB 色标、时间刻度、进度与不可分析占位）
- 保存频谱图 / 分享频谱图：`SpectrumChart` 长按菜单 → `SpectrumShareImage` + `SpectrumImageSharing`
- 曲库格式分析：`LibraryAnalysisSheet` 的格式占比圆环与按格式定位歌单（保留格式类目统计与切换）

## 回退方式

- 本目录逐文件复制即可还原：`removed/` 覆盖回原路径、`modified-originals/` 覆盖回原路径。
- 或经 git 回退本次提交。
