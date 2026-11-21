package com.yichao.evilgodxu.report

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

// 固定宽度便于接收端缩放阅读；高度完全由排版后的内容决定，避免长报告末尾被裁掉
private const val IMAGE_WIDTH = 1440
private const val PAGE_PADDING = 56f
private const val CONTENT_WIDTH = IMAGE_WIDTH - PAGE_PADDING * 2
private const val LABEL_WIDTH = 330f
private const val ROW_PADDING = 18f

private const val COLOR_BACKGROUND = 0xFFFFFFFF.toInt()
private const val COLOR_TEXT = 0xFF20242A.toInt()
private const val COLOR_MUTED = 0xFF616872.toInt()
private const val COLOR_BORDER = 0xFFD8DBDF.toInt()
private const val COLOR_LABEL = 0xFFF5F6F7.toInt()
private const val COLOR_DEVICE = 0xFFEFF1F3.toInt()
private const val COLOR_DETAIL = 0xFFFAFAF9.toInt()

internal object AudioReportImage {

    fun write(report: String, file: File) {
        file.writeBytes(renderPng(report))
    }

    /** 生成报告排版后的 PNG 字节：相册存档与分享文件共用同一份渲染结果 */
    fun renderPng(report: String): ByteArray {
        val bitmap = render(report)
        return try {
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun render(report: String): Bitmap {
        val paints = ReportPaints()
        val lines = parseAudioReportLines(report).map { measure(it, paints) }
        val height = (PAGE_PADDING * 2 + lines.sumOf { it.height }).toInt()
        val bitmap = createBitmap(IMAGE_WIDTH, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(COLOR_BACKGROUND)

        var y = PAGE_PADDING
        lines.forEach { measured ->
            draw(canvas, measured, paints, y)
            y += measured.height
        }
        return bitmap
    }

    private fun measure(line: AudioReportLine, paints: ReportPaints): MeasuredLine = when (line.kind) {
        AudioReportLineKind.TITLE -> MeasuredLine(
            line = line,
            labelLayout = layout(line.label, paints.brand, CONTENT_WIDTH),
            valueLayout = layout(line.value, paints.title, CONTENT_WIDTH),
            height = 164,
        )
        AudioReportLineKind.SECTION -> MeasuredLine(
            line = line,
            valueLayout = layout(line.value, paints.section, CONTENT_WIDTH),
            height = 78,
        )
        AudioReportLineKind.DEVICE -> MeasuredLine(
            line = line,
            valueLayout = layout(line.value, paints.device, CONTENT_WIDTH - 32f),
            height = 70,
        )
        AudioReportLineKind.FIELD -> {
            val labelLayout = layout(line.label, paints.label, LABEL_WIDTH - ROW_PADDING * 2)
            val valueLayout = layout(
                line.value,
                paints.value,
                CONTENT_WIDTH - LABEL_WIDTH - ROW_PADDING * 2,
            )
            MeasuredLine(
                line = line,
                labelLayout = labelLayout,
                valueLayout = valueLayout,
                height = max(labelLayout.height, valueLayout.height) + (ROW_PADDING * 2).toInt(),
            )
        }
        AudioReportLineKind.DETAIL -> {
            val valueLayout = layout(line.value, paints.detail, CONTENT_WIDTH - ROW_PADDING * 2)
            MeasuredLine(
                line = line,
                valueLayout = valueLayout,
                height = valueLayout.height + (ROW_PADDING * 2).toInt(),
            )
        }
        AudioReportLineKind.BLANK -> MeasuredLine(line, height = 16)
    }

    private fun draw(canvas: Canvas, measured: MeasuredLine, paints: ReportPaints, y: Float) {
        when (measured.line.kind) {
            AudioReportLineKind.TITLE -> drawTitle(canvas, measured, paints, y)
            AudioReportLineKind.SECTION -> drawSection(canvas, measured, paints, y)
            AudioReportLineKind.DEVICE -> drawDevice(canvas, measured, paints, y)
            AudioReportLineKind.FIELD -> drawField(canvas, measured, paints, y)
            AudioReportLineKind.DETAIL -> drawDetail(canvas, measured, paints, y)
            AudioReportLineKind.BLANK -> Unit
        }
    }

    private fun drawTitle(canvas: Canvas, measured: MeasuredLine, paints: ReportPaints, y: Float) {
        val right = IMAGE_WIDTH - PAGE_PADDING
        canvas.drawRect(PAGE_PADDING, y, right, y + 4f, paints.rule)
        measured.labelLayout?.drawAt(canvas, PAGE_PADDING, y + 24f)
        measured.valueLayout?.drawAt(canvas, PAGE_PADDING, y + 62f)
        canvas.drawRect(PAGE_PADDING, y + measured.height - 2f, right, y + measured.height, paints.rule)
    }

    private fun drawSection(canvas: Canvas, measured: MeasuredLine, paints: ReportPaints, y: Float) {
        measured.valueLayout?.drawAt(canvas, PAGE_PADDING, y + 16f)
        val titleWidth = paints.section.measureText(measured.line.value)
        val lineStart = PAGE_PADDING + titleWidth + 24f
        val right = IMAGE_WIDTH - PAGE_PADDING
        if (lineStart < right) {
            canvas.drawRect(lineStart, y + 37f, right, y + 38f, paints.border)
        }
    }

    private fun drawDevice(canvas: Canvas, measured: MeasuredLine, paints: ReportPaints, y: Float) {
        val right = IMAGE_WIDTH - PAGE_PADDING
        val bottom = y + measured.height
        canvas.drawRect(PAGE_PADDING, y, right, bottom, paints.deviceBackground)
        canvas.drawRect(PAGE_PADDING, y, right, bottom, paints.border)
        measured.valueLayout?.drawAt(canvas, PAGE_PADDING + 16f, y + 16f)
    }

    private fun drawField(canvas: Canvas, measured: MeasuredLine, paints: ReportPaints, y: Float) {
        val right = IMAGE_WIDTH - PAGE_PADDING
        val bottom = y + measured.height
        val valueLeft = PAGE_PADDING + LABEL_WIDTH
        canvas.drawRect(PAGE_PADDING, y, valueLeft, bottom, paints.labelBackground)
        canvas.drawRect(PAGE_PADDING, y, right, bottom, paints.border)
        canvas.drawRect(valueLeft, y, valueLeft + 1f, bottom, paints.border)
        measured.labelLayout?.drawAt(canvas, PAGE_PADDING + ROW_PADDING, y + ROW_PADDING)
        measured.valueLayout?.drawAt(canvas, valueLeft + ROW_PADDING, y + ROW_PADDING)
    }

    private fun drawDetail(canvas: Canvas, measured: MeasuredLine, paints: ReportPaints, y: Float) {
        val right = IMAGE_WIDTH - PAGE_PADDING
        val bottom = y + measured.height
        canvas.drawRect(PAGE_PADDING, y, right, bottom, paints.detailBackground)
        canvas.drawRect(PAGE_PADDING, y, right, bottom, paints.border)
        measured.valueLayout?.drawAt(canvas, PAGE_PADDING + ROW_PADDING, y + ROW_PADDING)
    }

    private fun layout(text: String, paint: TextPaint, width: Float): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt())
            .setIncludePad(false)
            .build()

    private fun StaticLayout.drawAt(canvas: Canvas, x: Float, y: Float) {
        canvas.withTranslation(x, y) { draw(canvas) }
    }
}

private data class MeasuredLine(
    val line: AudioReportLine,
    val labelLayout: StaticLayout? = null,
    val valueLayout: StaticLayout? = null,
    val height: Int,
)

private class ReportPaints {
    val brand = textPaint(28f, COLOR_MUTED)
    val title = textPaint(54f, COLOR_TEXT, bold = true)
    val section = textPaint(34f, COLOR_TEXT, bold = true)
    val device = textPaint(30f, COLOR_TEXT, bold = true)
    val label = textPaint(28f, COLOR_MUTED)
    val value = textPaint(29f, COLOR_TEXT, monospace = true)
    val detail = textPaint(27f, COLOR_TEXT, monospace = true)
    val rule = rectPaint(COLOR_TEXT)
    val border = rectPaint(COLOR_BORDER, stroke = true)
    val deviceBackground = rectPaint(COLOR_DEVICE)
    val labelBackground = rectPaint(COLOR_LABEL)
    val detailBackground = rectPaint(COLOR_DETAIL)
}

private fun textPaint(
    size: Float,
    color: Int,
    bold: Boolean = false,
    monospace: Boolean = false,
): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
    textSize = size
    this.color = color
    typeface = when {
        monospace -> Typeface.MONOSPACE
        bold -> Typeface.DEFAULT_BOLD
        else -> Typeface.DEFAULT
    }
}

private fun rectPaint(color: Int, stroke: Boolean = false): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    this.color = color
    if (stroke) {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
}
