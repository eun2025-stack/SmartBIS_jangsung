package com.smartbis.client.view

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.VideoView

/** Fits the source aspect ratio inside its parent and lets FrameLayout center the video surface. */
class CenteredVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : VideoView(context, attrs) {
    private var sourceWidth = 0
    private var sourceHeight = 0

    fun updateSourceSize(width: Int, height: Int) {
        sourceWidth = width
        sourceHeight = height
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        val availableHeight = MeasureSpec.getSize(heightMeasureSpec)

        if (sourceWidth <= 0 || sourceHeight <= 0) {
            setMeasuredDimension(availableWidth, availableHeight)
            return
        }

        val scale = minOf(
            availableWidth.toFloat() / sourceWidth,
            availableHeight.toFloat() / sourceHeight
        )
        setMeasuredDimension((sourceWidth * scale).toInt(), (sourceHeight * scale).toInt())
    }
}
