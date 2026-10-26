package com.smartbis.client.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.caverock.androidsvg.SVG

/** Draws a bundled SVG logo asset without converting or downloading it at runtime. */
class SvgAssetView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    var assetPath: String? = null
        set(value) {
            field = value
            svg = value?.let { path ->
                runCatching { SVG.getFromAsset(context.assets, path) }.getOrNull()
            }
            invalidate()
        }

    private var svg: SVG? = null

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        svg?.renderToCanvas(canvas, RectF(0f, 0f, width.toFloat(), height.toFloat()))
    }
}
