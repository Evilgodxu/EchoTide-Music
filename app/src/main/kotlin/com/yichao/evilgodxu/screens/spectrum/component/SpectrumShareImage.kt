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

// 导出图内容：首行曲名与歌手、图下方参数行。
// 文案由界面按当前语言产出后传入，渲染端不与资源耦合
internal class SpectrumShareContent(
    val title: String,
    val artist: String,
    val durationMs: Long,
    val info: String,
)

// 频谱图导出渲染：把首行曲名与歌手、时频图、频率刻度、dB 色标与参数行排成一张固定宽度的高清图。
// 版面按导出图自身尺寸定（不随屏幕尺寸变化），绘图区按 3:2 铺开取更多频率与时间细节；
// 图内每一行与左轴刻度的换算沿用屏幕同一套 FrequencyAxisScale 与档位挑选规则，两处读数一致。
// 整体取深色版式：频谱图本身为深色，导出图与图内配色同源
internal object SpectrumShareImage {

    // 版面尺寸（px）：固定宽度，高度由内容行数决定
    private const val WIDTH = 1920
    private const val PAD = 48f

    // 各段字号：首行曲名与歌手取较大字号作标题，刻度与参数行同取正文号
    private const val CREDIT_TEXT_SIZE = 40f
    private const val LABEL_TEXT_SIZE = 30f

    // 首行曲名与歌手之间的分隔符
    private const val CREDIT_SEPARATOR = " · "

    // 绘图区版面：左右两侧分别留给频率刻度与色标
    private const val AXIS_LABEL_WIDTH = 160f
    private const val AXIS_GAP = 12f
    private const val SCALE_LABEL_WIDTH = 100f
    private const val BAR_WIDTH = 30f
    private const val BAR_GAP = 14f
    private const val PLOT_ASPECT = 3f / 2f

    // 刻度线伸出绘图区的长度与相邻标签的最小间距
    private const val TICK_LINE_LENGTH = 12f
    private const val TICK_MIN_GAP = 40f

    // 各段竖向间距
    private const val LINE_GAP = 14f
    private const val BLOCK_GAP = 24f

    // 导出图配色：深色底、浅色字。全图文字取同一色，层次只由字号给出
    private const val BACKGROUND = 0xFF0B0D14.toInt()
    private const val TEXT_COLOR = 0xFF9BA3B4.toInt()
    private const val AXIS_COLOR = 0xFF6B7280.toInt()

    // 标签以字面竖向居中于给定位置时，基线相对中心的偏移（约半个字高）
    private const val BASELINE_CENTER_RATIO = 0.36f

    // 渲染导出图并编码为 PNG。重采样与绘制是纯计算，调用方须在后台线程调用
    suspend fun render(spectrogram: Spectrogram, content: SpectrumShareContent): ByteArray =
        withContext(Dispatchers.Default) {
            ByteArrayOutputStream().use { out ->
                draw(spectrogram, content).compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
        }

    // 版面与绘制：先按内容行数定出图高，再逐块绘制
    private fun draw(spectrogram: Spectrogram, content: SpectrumShareContent): Bitmap {
        val scale = FrequencyAxisScale.ofSampleRate(spectrogram.sampleRate)
        val plotLeft = PAD + AXIS_LABEL_WIDTH + AXIS_GAP
        val plotRight = WIDTH - PAD - SCALE_LABEL_WIDTH - BAR_WIDTH - BAR_GAP
        val plotWidth = (plotRight - plotLeft).toInt()
        val plotHeight = plotWidth / PLOT_ASPECT

        // 全图文字同取一种正文色，字号各自区分层次
        val creditPaint = textPaint(CREDIT_TEXT_SIZE, TEXT_COLOR)
        val bodyPaint = textPaint(LABEL_TEXT_SIZE, TEXT_COLOR)

        // 自上而下排布：首行为曲名与歌手，其后为绘图区、时间刻度与参数行，空行不占位
        var y = PAD
        val creditBaseline = baselineOfTop(creditPaint, y)
        y += lineHeight(creditPaint) + BLOCK_GAP
        val plotTop = y
        val plotBottom = plotTop + plotHeight
        y = plotBottom + LINE_GAP + lineHeight(bodyPaint) + BLOCK_GAP
        val infoBaseline = if (content.info.isNotBlank()) {
            baselineOfTop(bodyPaint, y).also { y += lineHeight(bodyPaint) }
        } else {
            null
        }
        val height = (y + PAD).toInt()

        val bitmap = createBitmap(WIDTH, height)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BACKGROUND)
        drawCreditLine(canvas, content, creditBaseline, creditPaint)
        canvas.drawBitmap(
            renderSpectrogramBitmap(spectrogram, scale, plotWidth, plotHeight.toInt()),
            plotLeft,
            plotTop,
            null,
        )
        drawFrequencyAxis(canvas, scale, plotLeft, plotTop, plotBottom, plotHeight, bodyPaint)
        drawColorScale(canvas, plotRight, plotTop, plotBottom, plotHeight, bodyPaint)
        drawTimeLabels(canvas, plotLeft, plotRight, plotBottom, content.durationMs, bodyPaint)
        if (infoBaseline != null) {
            drawCentered(canvas, content.info, infoBaseline, bodyPaint)
        }
        return bitmap
    }

    // 首行：曲名与歌手居中于整图宽度，两者以分隔符相接；歌手名为空时只留曲名。
    // 整串超出可用宽度时按末尾截断，避免溢出图缘
    private fun drawCreditLine(
        canvas: Canvas,
        content: SpectrumShareContent,
        baseline: Float,
        paint: TextPaint,
    ) {
        val separator = if (content.artist.isBlank()) "" else CREDIT_SEPARATOR
        val full = content.title + separator + content.artist
        val maxWidth = WIDTH - PAD * 2
        val text = if (paint.measureText(full) > maxWidth) {
            TextUtils.ellipsize(full, paint, maxWidth, TextUtils.TruncateAt.END).toString()
        } else {
            full
        }
        drawCentered(canvas, text, baseline, paint)
    }

    // 频率刻度：档位挑选与屏幕同一规则，标签右对齐到刻度线以左
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
                y + LABEL_TEXT_SIZE * BASELINE_CENTER_RATIO,
                labelPaint,
            )
        }
    }

    // dB 色标：渐变条自上而下由满强度降至量程下限，与屏幕色标同色板、同分档
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
                plotTop + plotHeight * fraction + LABEL_TEXT_SIZE * BASELINE_CENTER_RATIO,
                labelPaint,
            )
        }
    }

    // 时间刻度：两端分别贴绘图区左右缘
    private fun drawTimeLabels(
        canvas: Canvas,
        plotLeft: Float,
        plotRight: Float,
        plotBottom: Float,
        durationMs: Long,
        labelPaint: Paint,
    ) {
        val baseline = plotBottom + LINE_GAP + LABEL_TEXT_SIZE
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

    // 字面高度与基线换算：绘制端按字面顶/中心定位，避免受字体度量细节影响
    private fun lineHeight(paint: Paint): Float = -paint.fontMetrics.ascent + paint.fontMetrics.descent

    private fun baselineOfTop(paint: Paint, top: Float): Float = top - paint.fontMetrics.ascent
}
