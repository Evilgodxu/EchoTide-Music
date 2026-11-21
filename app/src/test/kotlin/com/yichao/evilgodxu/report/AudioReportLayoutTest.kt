package com.yichao.evilgodxu.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioReportLayoutTest {

    private val lines = parseAudioReportLines(ACTUAL_REPORT)

    @Test
    fun `解析保留原始报告的每一行`() {
        assertEquals(ACTUAL_REPORT.lines(), lines.map { it.raw })
    }

    @Test
    fun `标题与分区转换为排版文本`() {
        val title = lines.first()
        assertEquals(AudioReportLineKind.TITLE, title.kind)
        assertEquals("EchoTideMusic", title.label)
        assertEquals("设备音频报告", title.value)
        assertTrue(lines.any { it.kind == AudioReportLineKind.SECTION && it.value == "平台音频属性" })
        assertTrue(lines.any { it.kind == AudioReportLineKind.SECTION && it.value == "USB 设备（1 个）" })
    }

    @Test
    fun `长动态混音端口值完整保留`() {
        val mixer = lines.single { it.label == "动态混音端口" && it.value.startsWith("默认行为 48000Hz") }
        assertTrue(mixer.value.contains("默认行为 384000Hz/2ch/32位整型"))
        assertTrue(mixer.value.endsWith("（原始 14 条，含重名条目）"))
    }

    @Test
    fun `描述符结构保持独立明细行`() {
        val details = lines.filter { it.kind == AudioReportLineKind.DETAIL }
        assertEquals(11, details.size)
        assertEquals("结构: UAC2，控制接口 #0，可播放格式 3 个", details.first().value)
        assertEquals("类请求自检: 播放链特征单元#7 读音量 -> 失败", details.last().value)
    }

    @Test
    fun `末尾USB授权与节点字段完整保留`() {
        val last = lines.last { it.kind != AudioReportLineKind.BLANK }
        assertEquals(AudioReportLineKind.FIELD, last.kind)
        assertEquals("已授权", last.label)
        assertEquals("是 节点: /dev/bus/usb/002/002", last.value)
    }
}

private val ACTUAL_REPORT = """
======== EchoTideMusic 设备音频报告 ========
生成时间: 2026-11-21 16:16:26
版本: 6.3.6 (119)
设备: Xiaomi 2107119DC（product=lisa device=lisa）
硬件: 平台=qcom SoC=QTI SM7325 ABI=arm64-v8a, armeabi-v7a, armeabi
系统: Android 14（API 34，补丁 2025-11-01）
特性: PRO=否 低延迟=是 音频输出=是 USB主机=是 USB配件=是 BLE=是

---- 平台音频属性 ----
输出采样率: 48000
每突发帧数: 192
支持未处理采集: true

---- 输出设备（4 台）----
==== 设备 #2 ------------
产品名: 2107119DC
类型: BUILTIN_EARPIECE
方向: 输出
声道数: 1
声道掩码: 0x40
索引掩码: 未上报
编码: 16位整型
采样率（平台上报）: 48000
动态混音端口: 无（厂商未声明，或该设备此刻没有输出）

==== 设备 #3 ------------
产品名: 2107119DC
类型: BUILTIN_SPEAKER
方向: 输出
声道数: 2
声道掩码: STEREO
索引掩码: 未上报
编码: 16位整型
采样率（平台上报）: 48000
动态混音端口: 无（厂商未声明，或该设备此刻没有输出）

==== 设备 #11 ------------
产品名: 2107119DC
类型: TELEPHONY
方向: 输出
声道数: 1 2
声道掩码: MONO STEREO
索引掩码: 未上报
编码: 16位整型
采样率（平台上报）: 8000 16000
动态混音端口: 无（厂商未声明，或该设备此刻没有输出）

==== 设备 #2602 ------------
产品名: USB-Audio - KT02H20 Hifi Audio
类型: USB_HEADSET
地址: card=1;device=0
方向: 输出
声道数: 2
声道掩码: STEREO
索引掩码: 0x3
编码: 32位浮点 32位整型
采样率（平台上报）: 48000 88200 96000 176400 192000 352800 384000
动态混音端口: 默认行为 48000Hz/2ch/32位整型、默认行为 88200Hz/2ch/32位整型、默认行为 96000Hz/2ch/32位整型、默认行为 176400Hz/2ch/32位整型、默认行为 192000Hz/2ch/32位整型、默认行为 352800Hz/2ch/32位整型、默认行为 384000Hz/2ch/32位整型（原始 14 条，含重名条目）
描述符声明: UAC2、采样率 44100 48000 88200 96000 176400 192000 352800 384000（独占期间读到）、编码 PCM_16BIT PCM_24BIT PCM_32BIT、声道 2
  结构: UAC2，控制接口 #0，可播放格式 3 个
  格式: alt1 接口=1 端点=0x4 包=196B 同步=自适应 反馈=无 终端=5 时钟=6 位深=16 离散 未声明
  格式: alt2 接口=1 端点=0x4 包=294B 同步=自适应 反馈=无 终端=5 时钟=6 位深=24 离散 未声明
  格式: alt3 接口=1 端点=0x4 包=392B 同步=自适应 反馈=无 终端=5 时钟=6 位深=32 离散 未声明
  终端: #5 输入 时钟=6 源=0
  终端: #8 输出 时钟=6 源=7
  单元: 特征#7 源=5 主音量=否 声道音量=可写 可写
  时钟: #6 源 输入=- 频率可写=是
  回溯: 各格式的时钟源 6 6 6（0 表示回溯不到）
  时钟源档位: #6 -> 读不到（一次问满 98B 得 -1；先问个数 2B 得 -1；读当前值 4B 得 -1）
  类请求自检: 播放链特征单元#7 读音量 -> 失败


---- USB 设备（1 个） ----
==== 0BDA:0023 ------------
名称: KT02H20 Hifi Audio 厂商: KTMicro
类: 239/2/1 版本: 0.01 接口数: 6
已授权: 是 节点: /dev/bus/usb/002/002
""".trimIndent()
