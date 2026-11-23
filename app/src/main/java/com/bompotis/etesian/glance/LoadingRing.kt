package com.bompotis.etesian.glance

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * The N9 loading indicator, as the launcher draws it: an arc running round a squircle's edge. Drawn in a view's overlay
 * (Refresh, while the news loads) until [stop].
 */
class LoadingRing private constructor(private val view: View, color: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * view.resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        this.color = color
    }
    private val arc = Path()
    private val measure = PathMeasure()
    private var phase = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1100
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidateSelf() }
    }

    override fun onBoundsChange(b: android.graphics.Rect) {
        val inset = paint.strokeWidth / 2
        val size = minOf(b.width(), b.height()) - 2 * inset
        val l = b.left + (b.width() - size) / 2f; val t = b.top + (b.height() - size) / 2f
        measure.setPath(squircle(RectF(l, t, l + size, t + size)), true)
    }

    override fun draw(canvas: Canvas) {
        val length = measure.length
        if (length <= 0f) return
        val start = phase * length; val end = start + length * 0.3f
        arc.reset()
        measure.getSegment(start, end.coerceAtMost(length), arc, true)
        if (end > length) measure.getSegment(0f, end - length, arc, true)
        canvas.drawPath(arc, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT

    fun stop() { animator.cancel(); view.overlay.remove(this) }

    companion object {
        fun start(on: View, color: Int): LoadingRing = LoadingRing(on, color).also {
            it.setBounds(0, 0, on.width, on.height)
            on.overlay.add(it)
            it.animator.start()
        }
    }
}
