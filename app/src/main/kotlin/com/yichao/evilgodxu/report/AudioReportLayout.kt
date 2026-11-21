package com.yichao.evilgodxu.report

internal enum class AudioReportLineKind {
    TITLE,
    SECTION,
    DEVICE,
    FIELD,
    DETAIL,
    BLANK,
}

internal data class AudioReportLine(
    val raw: String,
    val kind: AudioReportLineKind,
    val label: String = "",
    val value: String = "",
)

// 文本是采集层唯一的报告内容来源；排版只识别层级，不改写、归纳或丢弃字段值
internal fun parseAudioReportLines(report: String): List<AudioReportLine> = report.lines().map { raw ->
    when {
        raw.isBlank() -> AudioReportLine(raw, AudioReportLineKind.BLANK)
        raw.startsWith("======== ") -> {
            val content = raw.removePrefix("======== ").removeSuffix(" ========")
            AudioReportLine(
                raw = raw,
                kind = AudioReportLineKind.TITLE,
                label = content.substringBefore(' '),
                value = content.substringAfter(' '),
            )
        }
        raw.startsWith("---- ") -> AudioReportLine(
            raw = raw,
            kind = AudioReportLineKind.SECTION,
            value = raw.removePrefix("---- ").removeSuffix(" ----"),
        )
        raw.startsWith("==== ") -> AudioReportLine(
            raw = raw,
            kind = AudioReportLineKind.DEVICE,
            value = raw.removePrefix("==== ").substringBefore(" ------------"),
        )
        raw.startsWith("  ") -> AudioReportLine(
            raw = raw,
            kind = AudioReportLineKind.DETAIL,
            value = raw.trimStart(),
        )
        else -> {
            val separator = raw.indexOf(':')
            AudioReportLine(
                raw = raw,
                kind = AudioReportLineKind.FIELD,
                label = if (separator >= 0) raw.substring(0, separator).trim() else "",
                value = if (separator >= 0) raw.substring(separator + 1).trim() else raw,
            )
        }
    }
}
