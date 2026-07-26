package net.duhowpi.ftmsbridge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

class LineChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    data class DataSeries(
        val label: String,
        val color: Int,
        val points: List<Float>,
        val yMin: Float? = null,
        val yMax: Float? = null
    )

    private val series = mutableListOf<DataSeries>()

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GRAY
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 128, 128, 128)
        strokeWidth = 1f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
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
    private val tooltipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 40, 40, 40)
        style = Paint.Style.FILL
    }
    private val tooltipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val tooltipLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 200, 200, 200)
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val kmMarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 76, 175, 80)
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val paddingLeft = 72f
    private val paddingRight = 72f
    private val paddingTop = 48f
    private val paddingBottom = 52f

    private var totalDurationSec: Int = 0
    private var legendEntries: List<Pair<String, Int>> = emptyList()
    private var tooltipIndex: Int? = null
    private var kmMarkIndices: List<Int> = emptyList()

    fun setData(vararg dataSeries: DataSeries, durationSec: Int, kmMarkIndices: List<Int> = emptyList()) {
        series.clear()
        series.addAll(dataSeries)
        totalDurationSec = durationSec
        legendEntries = dataSeries.map { Pair(it.label, it.color) }
        tooltipIndex = null
        this.kmMarkIndices = kmMarkIndices
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (series.isEmpty()) return false
        val maxPoints = series.maxOf { it.points.size }
        if (maxPoints < 2) return false
        val chartLeft = paddingLeft
        val chartRight = width.toFloat() - paddingRight
        val chartW = chartRight - chartLeft

        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val touchX = event.x.coerceIn(chartLeft, chartRight)
                val fraction = (touchX - chartLeft) / chartW
                tooltipIndex = (fraction * (maxPoints - 1)).roundToInt().coerceIn(0, maxPoints - 1)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Keep tooltip visible after release
                return true
            }
        }
        return false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (series.isEmpty()) return
        val w = width.toFloat()
        val h = height.toFloat()
        val chartLeft = paddingLeft
        val chartRight = w - paddingRight
        val chartTop = paddingTop
        val chartBottom = h - paddingBottom
        val chartW = chartRight - chartLeft
        val chartH = chartBottom - chartTop

        // --- Time marks ---
        if (totalDurationSec > 0) {
            val minIntervals = 3 // gives at least 4 marks
            val maxIntervalSec = 600 // 10 minutes
            val numIntervals = maxOf(minIntervals,
                Math.ceil(totalDurationSec.toDouble() / maxIntervalSec).toInt())
            val intervalSec = totalDurationSec.toDouble() / numIntervals

            for (i in 0..numIntervals) {
                val timeSec = (i * intervalSec).roundToInt()
                val x = chartLeft + (timeSec.toFloat() / totalDurationSec) * chartW
                // Vertical grid line (dashed)
                canvas.drawLine(x, chartTop, x, chartBottom, gridPaint)
                // Time label
                val mm = timeSec / 60
                val ss = timeSec % 60
                val label = "%d:%02d".format(mm, ss)
                labelPaint.color = Color.GRAY
                labelPaint.textAlign = if (i == 0) Paint.Align.LEFT
                                       else if (i == numIntervals) Paint.Align.RIGHT
                                       else Paint.Align.CENTER
                canvas.drawText(label, x, h - 6f, labelPaint)
            }
        }

        // --- Axis lines ---
        canvas.drawLine(chartLeft, chartTop, chartLeft, chartBottom, axisPaint)
        canvas.drawLine(chartLeft, chartBottom, chartRight, chartBottom, axisPaint)

        // --- Kilometre markers (vertical line + label at every completed km) ---
        if (kmMarkIndices.isNotEmpty()) {
            val maxPts = series.maxOf { it.points.size }
            val lastIdx = (maxPts - 1).coerceAtLeast(1)
            kmMarkIndices.forEachIndexed { i, pointIdx ->
                if (pointIdx in 0..lastIdx) {
                    val x = chartLeft + (pointIdx.toFloat() / lastIdx) * chartW
                    canvas.drawLine(x, chartTop, x, chartBottom, kmMarkPaint)
                    labelPaint.color = kmMarkPaint.color
                    labelPaint.textAlign = Paint.Align.CENTER
                    canvas.drawText("${i + 1} km", x, chartTop + labelPaint.textSize, labelPaint)
                }
            }
        }

        // --- Series lines ---
        series.forEachIndexed { idx, s ->
            if (s.points.isEmpty()) return@forEachIndexed
            val dataMin = s.points.min()
            val dataMax = s.points.max()
            // Apply soft range hints: the visible axis always spans at least [yMin, yMax],
            // but expands further if actual data falls outside that range.
            val min = if (s.yMin != null) minOf(s.yMin, dataMin) else dataMin
            val max = if (s.yMax != null) maxOf(s.yMax, dataMax) else dataMax
            val range = (max - min).takeIf { it > 0f } ?: 1f
            val lastIdx = (s.points.size - 1).coerceAtLeast(1)

            linePaint.color = s.color
            val path = Path()
            s.points.forEachIndexed { i, v ->
                val x = chartLeft + (i.toFloat() / lastIdx) * chartW
                // When all values are identical (range == 0 fallback), center the line vertically
                val y = if (dataMax == dataMin) chartTop + chartH / 2f
                        else chartBottom - ((v - min) / range) * chartH
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, linePaint)

            // Y-axis labels (left for first series, right for second+)
            labelPaint.color = s.color
            if (idx == 0) {
                labelPaint.textAlign = Paint.Align.RIGHT
                canvas.drawText(String.format("%.1f", max), chartLeft - 6f, chartTop + 12f, labelPaint)
                canvas.drawText(String.format("%.1f", min), chartLeft - 6f, chartBottom, labelPaint)
            } else {
                labelPaint.textAlign = Paint.Align.LEFT
                canvas.drawText(String.format("%.1f", max), chartRight + 6f, chartTop + 12f, labelPaint)
                canvas.drawText(String.format("%.1f", min), chartRight + 6f, chartBottom, labelPaint)
            }
        }

        // --- Legend ---
        var legendX = chartLeft
        val legendY = chartTop - 10f
        legendEntries.forEach { (label, color) ->
            labelPaint.color = color
            labelPaint.textAlign = Paint.Align.LEFT
            canvas.drawText("— $label", legendX, legendY, labelPaint)
            legendX += labelPaint.measureText("— $label") + 20f
        }

        // --- Tooltip ---
        val tipIdx = tooltipIndex ?: return
        val maxPoints = series.maxOf { it.points.size }
        if (tipIdx < 0 || tipIdx >= maxPoints) return

        // Vertical line at tooltip position
        val tipFraction = tipIdx.toFloat() / (maxPoints - 1).coerceAtLeast(1)
        val tipX = chartLeft + tipFraction * chartW
        canvas.drawLine(tipX, chartTop, tipX, chartBottom, tooltipLinePaint)

        // Dots on each series
        series.forEach { s ->
            if (tipIdx < s.points.size) {
                val dataMin = s.points.min()
                val dataMax = s.points.max()
                val min = if (s.yMin != null) minOf(s.yMin, dataMin) else dataMin
                val max = if (s.yMax != null) maxOf(s.yMax, dataMax) else dataMax
                val range = (max - min).takeIf { it > 0f } ?: 1f
                val v = s.points[tipIdx]
                val lastIdx = (s.points.size - 1).coerceAtLeast(1)
                val dotX = chartLeft + (tipIdx.toFloat() / lastIdx) * chartW
                // When all values are identical, center the dot vertically
                val dotY = if (dataMax == dataMin) chartTop + chartH / 2f
                           else chartBottom - ((v - min) / range) * chartH
                dotPaint.color = s.color
                canvas.drawCircle(dotX, dotY, 8f, dotPaint)
            }
        }

        // Tooltip box
        val timeSec = if (totalDurationSec > 0) (tipFraction * totalDurationSec).roundToInt() else tipIdx
        val mm = timeSec / 60
        val ss = timeSec % 60
        val timeLabel = "%d:%02d".format(mm, ss)
        val lines = mutableListOf(timeLabel)
        series.forEach { s ->
            if (tipIdx < s.points.size) {
                lines.add("${s.label}: %.1f".format(s.points[tipIdx]))
            }
        }

        val textPad = 12f
        val lineH = tooltipTextPaint.textSize + 6f
        val boxW = lines.maxOf { tooltipTextPaint.measureText(it) } + textPad * 2
        val boxH = lines.size * lineH + textPad * 2

        var boxLeft = tipX + 12f
        if (boxLeft + boxW > chartRight) boxLeft = tipX - 12f - boxW
        val boxTop = chartTop + 8f
        val rect = RectF(boxLeft, boxTop, boxLeft + boxW, boxTop + boxH)
        canvas.drawRoundRect(rect, 8f, 8f, tooltipBgPaint)
        tooltipTextPaint.textAlign = Paint.Align.LEFT
        lines.forEachIndexed { i, line ->
            canvas.drawText(line, boxLeft + textPad, boxTop + textPad + tooltipTextPaint.textSize + i * lineH, tooltipTextPaint)
        }
    }
}
