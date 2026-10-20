# Oboe 1.11.0 评估（YiChaoTmp/oboe-1.11.0）

评估对象：Oboe 1.11.0 源码（`include/oboe`、`src/`、`docs/`、`samples/`、`apps/OboeTester`）。
对照对象：本项目现有输出链路（`data/music/playback/*`、`cpp/echotide_usb_iso/*`）。
结论只回答一件事：**这里面有什么是本项目用得上的**。

---

## 0. 结论

**不作为输出实现引入。** 理由在后面「一、Oboe 能给我们什么」里逐条列出：它的平台路径与我们已走的
AudioTrack 路径是同一套 AudioFlinger 通路，拿不到额外的东西，却会丢掉音量、音效、起播阈值、
自建打包整型轨这些我们已经依赖的能力，并额外链入 `liblog` 与 `OpenSLES`（`CMakeLists.txt:116`），
与「撤就撤干净」的约定相抵。

**值得借的是四样东西**，按价值排序：

| # | 内容 | 形态 | 优先级 |
|---|---|---|---|
| 1 | 容量/尺寸分离 + 欠载闭环调缓冲的思路（`LatencyTuner`）：先取「按设备实测的固定尺寸」，再视情做运行期闭环 | 纯 Kotlin，不需要库 | P0 |
| 2 | OboeTester 的四套量测法（毛刺扫描、回环延迟、冷启动、设备报告） | 工具/方法论，dev-only | P1 |
| 3 | 「隐性默认值」清单（重采样、2 bursts、音效降级、MMAP 条件） | 认知，避免走弯路 | P1 |
| 4 | AAudio 独占流替代 `setPreferredMixerAttributes` 的可能性 | 需实测验证 | P2 |

---

## 一、Oboe 能给我们什么、不能给什么

| 能力 | Oboe 11 | 本项目现状 |
|---|---|---|
| USB 等时端点直写、描述符解析、时钟选择、反馈端点 | 无 | 已有，且比平台路径更彻底（`UsbIsoStream`） |
| DSD / DoP | 无（格式表只到 I32 与压缩格式） | 不支持（曲库白名单即排除） |
| 未开流时查询设备能力 | 无（只能问已开的流） | 已有（动态混音端口声明 + `AudioDeviceInfo`） |
| HAL 侧真实采样率/位深/声道 | **有，仅 AAudio 且 API 34+**（`getHardwareSampleRate/Format/ChannelCount`） | 无公开 Java 接口 |
| 应用侧单调写入帧计数 | **有，AAudio 全版本**（`getFramesWritten`，`AudioStream.h:264`） | 自建轨逐次记账；两个默认变体要等 API 37 |
| 欠载计数 | 有（`getXRunCount`） | `AudioTrack.getUnderrunCount`（API 24+）已有，媒体3 也在读（`AudioTrackAudioOutput.java:457`） |
| 运行时调缓冲尺寸 | 有（`setBufferSizeInFrames` + 容量/尺寸分离） | 原语同有，未使用 |
| 闭环调缓冲 | **有（`LatencyTuner`）** | 固定 80ms，开环 |
| 音量、音效、起播阈值 | **全无**（无 `setVolume`，仅能挂 sessionId 接 Java 音效） | 三者都在用 |
| 位置换算、变速、静音跳过 | 无（只有 `getTimestamp` 与 `calculateLatencyMillis`） | 自建且更细（两段延迟、锚点换算） |
| 重采样 | 有，顶配 32 抽头 sinc（`MultiChannelResampler.cpp:70`） | 96 抽头 Kaiser 半带，且只做同族 2 的幂 |
| 断连处理 | 回调模式下 `onErrorAfterClose` 重开 | 自有 `AudioDeviceCallback` 链路 |
| 解码/封装 | 无 | 媒体3 |

一句话：**Oboe 在「低延迟交互型音频」（合成器、游戏、录音）上是成熟方案，在「高保真回放」
这件事上它没有超出我们的部分，唯一的空白是 HAL 侧真实格式与单调写入帧这两个量。**

---

## 二、最值得拿的一条：容量/尺寸分离 + 按欠载定缓冲（P0/P1）

### 2.1 库里的做法（已核实）

`LatencyTuner` 全文 108 行（`src/common/LatencyTuner.cpp`），机制只有三条：

- 建流后把**生效尺寸**压到最小：`kDefaultNumBursts = 2` 个 burst（`LatencyTuner.h:135`，
  构造时 `setMinimumBufferSize(2 * burst)`、`setBufferSizeIncrement(burst)`）；
- 进入 Active 前先空转 `kIdleCount = 8` 次，避免刚起播就判欠载（`LatencyTuner.cpp:49-51`）；
- 每次欠载把尺寸**加一个 burst**，封顶为容量（`LatencyTuner.cpp:58-77`）；只在 `reset()`
  时缩回最小（`LatencyTuner.cpp:99-104`），也就是起播与恢复那一刻。

`AudioStreamBuilder.cpp:209-213` 是它的前提：AAudio 的 LowLatency 输出流开流后会被**自动**把
生效尺寸设成 `2 * framesPerBurst`，容量保持不变。容量与尺寸是两个量，容量决定内存，尺寸决定驻留。

### 2.2 与本项目的对位

`PlaybackBufferPolicy` 的注释已经把这个问题写清楚了（`PlaybackBufferPolicy.kt:21-25`）：

> 欠载不会被察觉并自动放大缓冲——动态改容量虽有 `AudioTrack.setBufferSizeInFrames`，媒体3 不会
> 自己去调，重建轨道时仍按本策略取同一个值。

这正是 `LatencyTuner` 回答的问题。而且**两个原语在 Java 侧都齐**（minSdk 34）：

- `AudioTrack.setBufferSizeInFrames(int)`（API 23）——改生效尺寸，不动容量；
- `AudioTrack.getUnderrunCount()`（API 24）——欠载计数；媒体3 已经在写路径上读它并折算成
  `onUnderrun` 事件（`AudioTrackAudioOutput.java:450-476`），自建轨也照样能读。

### 2.3 落地形状（分两档，风险不同）

**稳妥版（建议先做）**：不改运行期的尺寸，只把 80ms 从**常数**改成**按设备实测**的值。

- 尺寸仍在建轨时一次定死（`PlaybackBufferPolicy.trackBufferBytes` 的输入改为「该设备实测的最小
  安全缓冲 × 安全系数，封顶 80ms」），位置口径、欠载记账、面板读数全都不变；
- 实测值来自 3.1 的毛刺扫描，按「设备型号 + 采样率」存一张小表，取不到就用 80ms 兜底；
- 收益：能扛的设备上起播、暂停恢复、跳转的滞后立刻缩短，且不触碰任何既有不变量。

**进阶版（需真机验证）**：尺寸在运行期闭环，即 `LatencyTuner` 的原样做法。构造上要注意三点：

1. 容量仍按 80ms 申请，只改生效尺寸；起播阈值按生效尺寸的一半算；
2. 每次 `onUnderrun` 加一档（一个 burst），封顶容量；只在起播、暂停恢复、跳转后缩回起始值
   （`LatencyTuner` 的 `reset()` 时机），**播放中不缩**——缩尺寸会丢环内数据；
3. `IntPcmAudioSink.outputListener` 上报欠载时 `bufferSize` 现取建轨时的容量，要改报当前生效尺寸。

必须先验的风险：媒体3 在**建轨那一刻**把缓冲时长固化进位置跟踪器
（`AudioTrackPositionTracker.bufferSizeUs`，`AudioTrackPositionTracker.java:202`），运行期改尺寸
不会同步；它只影响 `latencyUs` 的修正项（`getLatency() - bufferSizeUs`，再减尾音位置的
`positionUs - latencyUs`，line 431-433、468）。尺寸远小于容量时该差值可能落到 0 被截断，
表现为上报位置比实际略微超前（量级是个位数到数十毫秒）。验收口径：改尺寸后对照
`OutputLatencyReading.afterTrackMs` 与界面进度，确认没有可感的提前。

顺带一条同源的经验：legacy（非 MMAP）流的下限是 1 个 burst，低到 0 会被平台拒绝
（`QuirksManager.cpp:38-40`、`kLegacyBottomMarginInBursts`）。起始值不要低于 1 个 burst。

---

## 三、OboeTester 里的四套量测法（P1）

`apps/OboeTester` 是一个完整的音频测量台。对我们的价值不在代码，在**方法**——它回答的正是
`OutputLatency` 与 `PlaybackBufferPolicy` 注释里点出「量不到」的那几件事。

### 3.1 自动毛刺扫描 → 把 80ms 从经验值变成实测值

`AutomatedGlitchActivity` / `BaseAutoGlitchActivity`：按 burst 逐档抬负载，直到出现 xrun，报出
「第几档开始出毛刺」。这套流程可以直接用来给第二节的起始值与步长定标：**同一台设备上跑一遍，
就知道它的最小安全缓冲是多少**，不必全设备统一 80ms。

实现成本低：不需要回环，只需自放的测试音 + `getUnderrunCount` 变化 + 播放时长，播放器内做成
debug 页即可。现成的 TSV 结果表范式见 `TestDataPathsActivity`（通路 × 采样率 × 格式的矩阵）。

### 3.2 回环延迟 → 补上「设备传输」那一段

`OutputLatency.kt:20-24` 写明：

> 这一段之内不再可拆：没有接口能把「设备传输」与「解码器自身缓冲」分开，只能靠换通路对照。

`RoundTripLatencyActivity` + `LatencyAnalyzer` 正是拆它的办法：自放一个脉冲、用麦克风回采、算
脉冲到回采的往返时间与置信度。接回环线（或 USB 声卡 loopback）时得到的就是**含 DAC 与传输的
端到端真值**，减掉已知的两段即为「设备侧那一段」。

代价：需要录音权限与一次校准动作，不适合常驻。做成「音频信息面板 → 校准」里的一次性测量，
结果按设备记下来即可。

### 3.3 Tap-to-tone / 冷启动

`TapToToneActivity`（点击到发声，含触控与调度延迟）、`TestColdStartLatencyActivity`（建流耗时）。
后者与我们相关：每次跳转都会释放并重建音频轨（`IntPcmAudioSink.flush()`），建轨耗时直接体现在
跳转的听感上。冷启动测试能把这段时间量出来，是否值得优化才有依据。

### 3.4 设备报告 → 并进「分享诊断」

`DeviceReportActivity` 采集：`AudioManager` 属性（原生采样率、每突发帧数等）、每台设备的完整 dump
（含 `channelMasks`、`channelIndexMasks`、`encodings`、`sampleRates`，见
`samples/audio-device/.../AudioDeviceInfoConverter.java`）、USB 设备描述、媒体编解码器、CPU 信息，
一键分享。

调音问题最难复现的恰恰是「换台设备就不一样」。我们已有 `CrashLogManager` 的分享通道，把这份
设备快照并进去，比让用户口述设备型号有用得多。**这是本次评估里性价比最高的一项。**

> 已落地：`log/DeviceReport.kt`，随设置页「日志」一并分享（无日志时也能单独分享）。
> 采集范围：设备/SoC/ABI/系统补丁、音频系统特性、AudioManager 属性、每台输出设备的完整能力
> （声道数/掩码/索引掩码/编码/采样率）、动态混音端口档位与已受理偏好、USB 描述符声明、USB 设备表。

---

## 四、若哪天引入，会咬人的隐性默认值（P1）

即使不引入，这几条也是「这个库会怎么坑人」的清单，值得记下：

1. **重采样默认是开着的。** `SampleRateConversionQuality` 默认 `Medium`（`AudioStreamBase.h:391`）。
   更关键的是：请求 LowLatency 且指定了采样率时，
   `QuirksManager` 会把**子流的采样率改成 Unspecified** 交给平台挑，再在库内重采样
   （`QuirksManager.cpp:245-251`）。对一个位深/采样率敏感的播放器，这是致命默认值——
   拿到的「低延迟流」是重采样后的。必须显式 `None`。
2. **LowLatency 输出流的默认尺寸是 2 个 burst**（`AudioStreamBuilder.cpp:209-213`），
   我们 80ms 的口径会被覆盖，得自己再 `setBufferSizeInFrames`。
3. **`sessionId` 与 LowLatency 互斥**：传了 sessionId（要挂 Java 音效）就把性能模式强制降为
   `None`（`AudioStreamAAudio.cpp:418-429`）。
4. **空间化默认被改成 Never**（`AudioStreamAAudio.cpp:501-510`）——与本项目
   `SPATIALIZATION_BEHAVIOR_NEVER` 的取向一致，说明这条是业界共识。
5. **独占流更易断连**（`FullGuide.md:31`、`GettingStarted.md:237`）；且 MMAP 只在
   LowLatency + （采样率未指定 or 48k or 允许重采样）+ 声道 ≤ 2 时才可能成立
   （`QuirksManager.cpp:56-64`）。也就是说 44.1k 上要么被重采样到 48k，要么拿不到 MMAP
   ——**44.1k/24bit 的独占 MMAP 基本不成立**。这反过来印证了 `UsbDirectOutput` 放弃「位完美」、
   改走 usbfs 的判断。
6. **关流前会 sleep 10~100ms** 防止回调访问已释放内存（`AudioStream.h:507-527`）；
   `close()` 不得与另一线程的写并发（`FullGuide.md:388`）。这与我们
   `UsbIsoStream::stop()` 里「未回收 URB 不得释放」是同一类问题，结论也一致：先停、再等、后释放。
7. **OpenSL ES 分支对我们完全无用**（minSdk 34 → 恒走 AAudio），但它被无条件编译并强制链
   `log` 与 `OpenSLES`（`CMakeLists.txt:26-32, 116`）。要裁只能自己改 CMake：
   `OBOE_DISABLE_CONVERSION=ON` 可砍掉整个 flowgraph（`CMakeLists.txt:78-85`），
   但 OpenSLES 那几个源文件与链接项没有开关。

---

## 五、待验证：AAudio 独占流能否替代专用输出流（P2）

现在的专用输出流靠 `setPreferredMixerAttributes` + 设备声明的动态混音端口，为此要养一条保活轨，
解决「设备上没有输出就查不到声明」的死结（见 `SilentKeepAliveOutput` 类注释）。
AAudio 的 `SharingMode::Exclusive` 语义是「独占端点、绕过混音」（`FullGuide.md:31`），
**按语义它不需要厂商声明动态端口，也不需要保活轨**——这是待验证的假设，不是已核实的事实：
它最终仍由 AudioPolicyManager 挑 profile，被静默退回共享/重采样完全可能。

若成立，收益是砍掉一整条保活链路；若不成立（被框架静默退回共享/重采样），代价是一次实验的人力。

验证方法（半天量级，native 侧二十行）：

1. `Exclusive` + `LowLatency` + 44.1k + I24 开流；
2. 开流后读 `getSharingMode()`、`OboeExtensions::isMMapUsed()`、`getHardwareSampleRate()`、
   `getSampleRate()`；
3. `dumpsys media.audio_flinger` 看是否新建了 direct output、是否出现 resampler。

判据：`getSharingMode() == Exclusive` 且 `getHardwareSampleRate() == 44100`。
另需确认 USB 解码器被本应用接管（内核驱动解绑）之后，AAudio 侧是否已看不到该设备——
若看不到，这条路只能覆盖内置/未接管的通路，与直出无关。

---

## 六、其余可借，但优先级低

- **`FifoBuffer` 多读者范式**（`FifoControllerIndirect` 共享读写计数）：若将来做「播放中实时频谱」
  而不想扰动播放线程，这是现成的无锁范式。当前 `Spectrogram` 只离线分析文件，暂不需要。
- **重采样器**：`IntegerRatio`（有理比化简）+ `PolyphaseResampler` + `SincResampler`（32 抽头、
  Kaiser 窗）是完整可参考的实现。但我们的半带是 96 抽头、β=10、约 -100dB 阻带，且偶数相位是
  逐位照抄——质量与保真口径都更高，只在「应用内做跨族转换」时才涉及，且我们已明确不做。
- **`AudioClock::sleepUntilNanoTime`**（绝对时刻睡眠，避免相对睡眠累积漂移）：我们的回收线程是
  poll 事件驱动，不适用。
- **`StabilizedCallback` / ADPF workload hint（API 36）**：给负载剧烈变化的合成器用；我们只有
  半带插值，负载恒定。
- **`FullDuplexStream`**：只对 3.2 的回环测量有用。
- **`flushFromFrame`（API 37）/ offload 一族（`setOffloadDelayPadding`、
  `PerformanceMode::PowerSavingOffloaded`）**：压缩直通与省电卸载，音乐 PCM 回放不涉及。
- **`AudioStreamRoutingCallback`（API 37）**：路由变化不再断流。我们已有
  `AudioDeviceCallback` 链路，且媒体3 的 `setPreferredAudioDevice` 会跟着走，暂不需要。

---

## 七、建议动作

1. **P0**：把缓冲策略从「固定 80ms」改为「按设备表取值，封顶 80ms」——建轨时一次定死，运行期不动，
   既有位置与欠载口径全部不变。表先放保守初值（例如统一 60ms 跑一轮），待 3.1 的扫描结果替换。
2. **P0**：把 `DeviceReportActivity` 的采集项并进现有分享诊断通道（设备 dump + AudioManager 属性
   + 属性级信息）。这是纯收益、零风险的一项。
3. **P1**：做一版 dev-only 的毛刺扫描，按机型/采样率测出最小安全缓冲，落成第 1 条用的那张表；
   成本可控时再加一个回环延迟校准入口（3.2）。
4. **P1**：P0 稳定后，再评估进阶版（运行期闭环调尺寸）是否值得——先验 2.3 末尾那条位置超前风险。
5. **P2**：空闲时做第五节那半天的 AAudio 独占验证；只有结论为「成立」才值得继续。
6. **不做**：把输出链路换成 Oboe。
