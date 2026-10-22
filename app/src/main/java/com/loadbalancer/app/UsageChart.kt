package com.loadbalancer.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.format.Formatter
import android.view.View

/** Stacked bar chart: Wi-Fi at the bottom, mobile data on top. */
class UsageChart(context: Context) : View(context) {
    private var wifi = LongArray(0)
    private var cell = LongArray(0)
    private var labels = emptyList<String>()
    private var wifiName = ""
    private var cellName = ""
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9AA5D6")
        textSize = 11f * resources.displayMetrics.scaledDensity
    }

    fun setData(wifi: LongArray, cell: LongArray, labels: List<String>, wifiName: String, cellName: String) {
        this.wifi = wifi
        this.cell = cell
        this.labels = labels
        this.wifiName = wifiName
        this.cellName = cellName
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val n = wifi.size
        if (n == 0) return
        val top = 18f * d
        val bottom = height - 22f * d
        val max = (0 until n).maxOf { wifi[it] + cell[it] }.coerceAtLeast(1L)
        canvas.drawText(Formatter.formatShortFileSize(context, max), 0f, 12f * d, text)

        paint.color = Color.parseColor("#22FFFFFF")
        canvas.drawRect(0f, bottom, width.toFloat(), bottom + 1f * d, paint)

        val slot = width.toFloat() / n
        val barW = slot * 0.62f
        val r = minOf(barW / 2f, 4f * d)
        for (i in 0 until n) {
            val x = i * slot + (slot - barW) / 2f
            val wh = (bottom - top) * wifi[i] / max
            val ch = (bottom - top) * cell[i] / max
            paint.color = WIFI
            if (wh > 0f) canvas.drawRoundRect(RectF(x, bottom - wh, x + barW, bottom), r, r, paint)
            paint.color = CELL
            if (ch > 0f) canvas.drawRoundRect(RectF(x, bottom - wh - ch, x + barW, bottom - wh), r, r, paint)
        }

        val y = height - 5f * d
        if (labels.isNotEmpty()) {
            text.textAlign = Paint.Align.LEFT
            canvas.drawText(labels.first(), 0f, y, text)
            text.textAlign = Paint.Align.RIGHT
            canvas.drawText(labels.last(), width.toFloat(), y, text)
        }
        val legendX = width - 12f * d
        text.textAlign = Paint.Align.RIGHT
        canvas.drawText(cellName, legendX, 12f * d, text)
        val cx = legendX - text.measureText(cellName) - 8f * d
        paint.color = CELL
        canvas.drawCircle(cx, 8f * d, 4f * d, paint)
        val wx = cx - 14f * d
        canvas.drawText(wifiName, wx, 12f * d, text)
        paint.color = WIFI
        canvas.drawCircle(wx - text.measureText(wifiName) - 8f * d, 8f * d, 4f * d, paint)
        text.textAlign = Paint.Align.LEFT
    }

    companion object {
        private val WIFI = Color.parseColor("#2EE6D6")
        private val CELL = Color.parseColor("#FFB02E")
    }
}
