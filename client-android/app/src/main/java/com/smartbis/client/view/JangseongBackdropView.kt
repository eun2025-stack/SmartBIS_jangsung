package com.smartbis.client.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/** Soft cream-and-forest landscape inspired by the approved Jangseong display prototype. */
class JangseongBackdropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        paint.shader = LinearGradient(0f, 0f, 0f, h, CREAM, PALE_GREEN, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        paint.color = SUN_GLOW
        canvas.drawCircle(w * .88f, h * .15f, h * .19f, paint)
        drawHill(canvas, h * .76f, floatArrayOf(0f, .18f, .35f, .54f, .73f, .86f, 1f),
            floatArrayOf(.16f, .01f, .13f, -.01f, .12f, -.02f, .10f), HILL_LIGHT)
        drawHill(canvas, h * .82f, floatArrayOf(0f, .14f, .33f, .49f, .69f, .88f, 1f),
            floatArrayOf(.08f, -.05f, .09f, -.03f, .11f, -.04f, .08f), HILL_MID)
        drawHill(canvas, h * .89f, floatArrayOf(0f, .2f, .4f, .6f, .8f, 1f),
            floatArrayOf(.05f, -.04f, .07f, -.02f, .06f, -.03f), HILL_DARK)
    }

    private fun drawHill(
        canvas: Canvas,
        baseY: Float,
        xPositions: FloatArray,
        yOffsets: FloatArray,
        color: Int
    ) {
        val w = width.toFloat()
        val h = height.toFloat()
        val path = Path().apply {
            moveTo(0f, baseY + h * yOffsets[0])
            for (index in 1 until xPositions.size) {
                lineTo(w * xPositions[index], baseY + h * yOffsets[index])
            }
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        paint.color = color
        canvas.drawPath(path, paint)
    }

    private companion object {
        const val CREAM = 0xFFF7F1E1.toInt()
        const val PALE_GREEN = 0xFFE8EFE3.toInt()
        const val SUN_GLOW = 0x22B5563C
        const val HILL_LIGHT = 0x668FB08A
        const val HILL_MID = 0x884C7A4E.toInt()
        const val HILL_DARK = 0xAA2F5233.toInt()
    }
}
