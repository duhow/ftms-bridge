package net.duhowpi.ftmsbridge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

class LineChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    data class DataSeries(val label: String, val color: Int, val points: List<Float>)

    private val series = mutableListOf<DataSeries>()
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GRAY
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GRAY
        textSize = 28f
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 3f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val paddingPx = 48f
    private var totalDurationSec: Int = 0
    private var xLabels: Pair<String, String> = Pair("0", "")
    private var legendEntries: List<Pair<String, Int>> = emptyList()

    fun setData(vararg dataSeries: DataSeries, durationSec: Int) {
        series.clear()
        series.addAll(dataSeries)
        totalDurationSec = durationSec
        val mm = durationSec / 60
        val ss = durationSec % 60
        xLabels = Pair("0:00", "%d:%02d".format(mm, ss))
        legendEntries = dataSeries.map { Pair(it.label, it.color) }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (series.isEmpty()) return
        val w = width.toFloat()
        val h = height.toFloat()
        val chartLeft = paddingPx * 1.5f
        val chartRight = w - paddingPx
        val chartTop = paddingPx
        val chartBottom = h - paddingPx * 1.8f
        val chartW = chartRight - chartLeft
        val chartH = chartBottom - chartTop

        // Draw axis lines
        canvas.drawLine(chartLeft, chartTop, chartLeft, chartBottom, axisPaint)
        canvas.drawLine(chartLeft, chartBottom, chartRight, chartBottom, axisPaint)

        // Draw each series
        series.forEachIndexed { idx, s ->
            if (s.points.isEmpty()) return@forEachIndexed
            val min = s.points.min()
            val max = s.points.max()
            val range = (max - min).takeIf { it > 0f } ?: 1f

            linePaint.color = s.color
            val path = Path()
            s.points.forEachIndexed { i, v ->
                // coerceAtLeast(1) prevents division by zero for a single-point series
                val x = chartLeft + (i.toFloat() / (s.points.size - 1).coerceAtLeast(1)) * chartW
                val y = chartBottom - ((v - min) / range) * chartH
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, linePaint)

            // Y-axis labels (left for first, right for second)
            labelPaint.color = s.color
            if (idx == 0) {
                labelPaint.textAlign = Paint.Align.RIGHT
                canvas.drawText(String.format("%.1f", max), chartLeft - 4f, chartTop + 10f, labelPaint)
                canvas.drawText(String.format("%.1f", min), chartLeft - 4f, chartBottom, labelPaint)
            } else {
                labelPaint.textAlign = Paint.Align.LEFT
                canvas.drawText(String.format("%.1f", max), chartRight + 4f, chartTop + 10f, labelPaint)
                canvas.drawText(String.format("%.1f", min), chartRight + 4f, chartBottom, labelPaint)
            }
        }

        // X labels
        labelPaint.color = Color.GRAY
        labelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(xLabels.first, chartLeft, h - 4f, labelPaint)
        labelPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(xLabels.second, chartRight, h - 4f, labelPaint)

        // Legend
        var legendX = chartLeft
        val legendY = chartTop - 8f
        legendEntries.forEach { (label, color) ->
            labelPaint.color = color
            labelPaint.textAlign = Paint.Align.LEFT
            canvas.drawText("— $label", legendX, legendY, labelPaint)
            legendX += labelPaint.measureText("— $label") + 20f
        }
    }
}
