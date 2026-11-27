package com.yichao.evilgodxu.screens.spectrum.component

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.graphics.createBitmap
import com.yichao.evilgodxu.data.music.analysis.SPECTROGRAM_DYNAMIC_RANGE_DB
import com.yichao.evilgodxu.data.music.analysis.Spectrogram
import com.yichao.evilgodxu.utils.formatTime
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 导出内容：曲名歌手 + 参数行，文案由界面传入
internal class SpectrumShareContent(
    val title: String,
    val artist: String,
    val durationMs: Long,
    val info: String,
)

// 导出高清图：固定 1920px，绘图区 3:2
internal object SpectrumShareImage {

    // 固定宽 1920px，高度随内容
    private const val WIDTH = 1920
    private const val PAD = 48f

    // 字号 30：两行同号居中
    private const val TEXT_SIZE = 30f

    // 曲名与歌手之间的分隔符
    private const val CREDIT_SEPARATOR = " · "

    // 绘图区左右留给刻度与色标
    private const val AXIS_LABEL_WIDTH = 160f
    private const val AXIS_GAP = 12f
    private const val SCALE_LABEL_WIDTH = 100f
    private const val BAR_WIDTH = 30f
    private const val BAR_GAP = 14f
    private const val PLOT_ASPECT = 3f / 2f

    // 刻度线长 12，标签最小间距 40
    private const val TICK_LINE_LENGTH = 12f
    private const val TICK_MIN_GAP = 40f

    // 各段竖向间距
    private const val LINE_GAP = 14f
    private const val BLOCK_GAP = 24f

    // 深底浅字，层次只靠字号
    private const val BACKGROUND = 0xFF0B0D14.toInt()
    private const val TEXT_COLOR = 0xFF9BA3B4.toInt()
    private const val AXIS_COLOR = 0xFF6B7280.toInt()

    // 基线相对中心偏移约半个字高
    private const val BASELINE_CENTER_RATIO = 0.36f

    // 渲染并编码 PNG，需在后台线程调用
    suspend fun render(spectrogram: Spectrogram, content: SpectrumShareContent): ByteArray =
        withContext(Dispatchers.Default) {
            ByteArrayOutputStream().use { out ->
                draw(spectrogram, content).compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
        }

    // 先按内容定高再逐块绘制
    private suspend fun draw(spectrogram: Spectrogram, content: SpectrumShareContent): Bitmap {
        val scale = FrequencyAxisScale.ofSampleRate(spectrogram.sampleRate)
        val plotLeft = PAD + AXIS_LABEL_WIDTH + AXIS_GAP
        val plotRight = WIDTH - PAD - SCALE_LABEL_WIDTH - BAR_WIDTH - BAR_GAP
        val plotWidth = (plotRight - plotLeft).toInt()
        val plotHeight = plotWidth / PLOT_ASPECT
        // 顶部不留标题区
        val plotTop = PAD
        val plotBottom = plotTop + plotHeight

        val paint = textPaint(TEXT_SIZE, TEXT_COLOR)
        val creditText = creditLine(content.title, content.artist, paint)

        // 两行同字号居中，空行不占位
        var y = plotBottom + LINE_GAP + lineHeight(paint) + BLOCK_GAP
        val creditBaseline = if (creditText.isNotBlank()) {
            baselineOfTop(paint, y).also { y += lineHeight(paint) + LINE_GAP }
        } else {
            null
        }
        val infoBaseline = if (content.info.isNotBlank()) {
            baselineOfTop(paint, y).also { y += lineHeight(paint) }
        } else {
            null
        }
        val height = (y + PAD).toInt()

        val bitmap = createBitmap(WIDTH, height)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BACKGROUND)
        canvas.drawBitmap(
            renderSpectrogramBitmap(spectrogram, scale, plotWidth, plotHeight.toInt()),
            plotLeft,
            plotTop,
            null,
        )
        drawFrequencyAxis(canvas, scale, plotLeft, plotTop, plotBottom, plotHeight, paint)
        drawColorScale(canvas, plotRight, plotTop, plotBottom, plotHeight, paint)
        drawTimeLabels(canvas, plotLeft, plotRight, plotBottom, content.durationMs, paint)
        if (creditBaseline != null) {
            drawCentered(canvas, creditText, creditBaseline, paint)
        }
        if (infoBaseline != null) {
            drawCentered(canvas, content.info, infoBaseline, paint)
        }
        return bitmap
    }

    // 曲名歌手相接，超宽末尾截断
    private fun creditLine(title: String, artist: String, paint: TextPaint): String {
        if (title.isBlank() && artist.isBlank()) return ""
        val separator = if (title.isBlank() || artist.isBlank()) "" else CREDIT_SEPARATOR
        val full = title + separator + artist
        val maxWidth = WIDTH - PAD * 2
        if (paint.measureText(full) <= maxWidth) return full
        return TextUtils.ellipsize(full, paint, maxWidth, TextUtils.TruncateAt.END).toString()
    }

    // 刻度挑选同屏幕规则
    private fun drawFrequencyAxis(
        canvas: Canvas,
        scale: FrequencyAxisScale,
        plotLeft: Float,
        plotTop: Float,
        plotBottom: Float,
        plotHeight: Float,
        labelPaint: Paint,
    ) {
        val linePaint = strokePaint()
        canvas.drawLine(plotLeft, plotTop, plotLeft, plotBottom, linePaint)
        layoutFrequencyTicks(
            ticks = frequencyTicks(scale),
            scale = scale,
            plotHeightPx = plotHeight,
            minGapPx = TICK_MIN_GAP,
        ).forEach { (tick, offsetY) ->
            val y = plotTop + offsetY
            canvas.drawLine(plotLeft - TICK_LINE_LENGTH, y, plotLeft, y, linePaint)
            val width = labelPaint.measureText(tick.label)
            canvas.drawText(
                tick.label,
                plotLeft - TICK_LINE_LENGTH - AXIS_GAP - width,
                y + TEXT_SIZE * BASELINE_CENTER_RATIO,
                labelPaint,
            )
        }
    }

    // 色标与屏幕同色板同分档
    private fun drawColorScale(
        canvas: Canvas,
        plotRight: Float,
        plotTop: Float,
        plotBottom: Float,
        plotHeight: Float,
        labelPaint: Paint,
    ) {
        val barLeft = plotRight + BAR_GAP
        val barRight = barLeft + BAR_WIDTH
        val barPaint = Paint().apply {
            shader = LinearGradient(
                0f,
                plotTop,
                0f,
                plotBottom,
                SPECTRUM_COLOR_STOPS.reversedArray(),
                null,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(barLeft, plotTop, barRight, plotBottom, barPaint)
        for (step in 0..SCALE_INTERVALS) {
            val fraction = step.toFloat() / SCALE_INTERVALS
            val decibel = -SPECTROGRAM_DYNAMIC_RANGE_DB * fraction
            val label = if (decibel == 0f) "0" else decibel.toInt().toString()
            canvas.drawText(
                label,
                barRight + AXIS_GAP,
                plotTop + plotHeight * fraction + TEXT_SIZE * BASELINE_CENTER_RATIO,
                labelPaint,
            )
        }
    }

    // 时间刻度两端贴绘图区
    private fun drawTimeLabels(
        canvas: Canvas,
        plotLeft: Float,
        plotRight: Float,
        plotBottom: Float,
        durationMs: Long,
        labelPaint: Paint,
    ) {
        val baseline = plotBottom + LINE_GAP + TEXT_SIZE
        canvas.drawText(formatTime(0L), plotLeft, baseline, labelPaint)
        val end = formatTime(durationMs)
        canvas.drawText(end, plotRight - labelPaint.measureText(end), baseline, labelPaint)
    }

    // 单行居中绘制
    private fun drawCentered(canvas: Canvas, text: String, baseline: Float, paint: Paint) {
        canvas.drawText(text, WIDTH / 2f - paint.measureText(text) / 2f, baseline, paint)
    }

    private fun textPaint(textSize: Float, color: Int): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        this.textSize = textSize
        this.color = color
        typeface = Typeface.DEFAULT
    }

    private fun strokePaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AXIS_COLOR
        strokeWidth = 1.5f
    }

    // 字面高度与基线换算
    private fun lineHeight(paint: Paint): Float = -paint.fontMetrics.ascent + paint.fontMetrics.descent

    private fun baselineOfTop(paint: Paint, top: Float): Float = top - paint.fontMetrics.ascent
}
