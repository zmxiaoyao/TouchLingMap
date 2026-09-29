package com.touchling.mapper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/**
 * v0.6.0 背屏"网格"样式：纯黑底 + 淡格线，方便当触控板时判断手指位移。
 */
class GridView(ctx: Context) : View(ctx) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33FFFFFF
        strokeWidth = 2f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cols = 12
        val rows = 8
        val w = width.toFloat()
        val h = height.toFloat()
        for (i in 0..cols) {
            val x = w * i / cols
            canvas.drawLine(x, 0f, x, h, paint)
        }
        for (j in 0..rows) {
            val y = h * j / rows
            canvas.drawLine(0f, y, w, y, paint)
        }
    }
}