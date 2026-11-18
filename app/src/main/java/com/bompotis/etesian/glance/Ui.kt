package com.bompotis.etesian.glance

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.format.DateUtils

/** Glance's colours (values / values-night) and shapes. */
object Ui {
    val BACKGROUND = R.color.background
    val SURFACE = R.color.surface
    val PRIMARY = R.color.text_primary
    val SECONDARY = R.color.text_secondary
    val ACCENT = R.color.accent
    val DIVIDER = R.color.divider

    fun color(ctx: Context, id: Int) = ctx.getColor(id)

    fun pill(ctx: Context) = GradientDrawable().apply {
        val d = ctx.resources.displayMetrics.density
        cornerRadius = 24 * d
        setColor(color(ctx, SURFACE))
        setStroke((1 * d).toInt().coerceAtLeast(1), color(ctx, DIVIDER))
    }

    fun rounded(ctx: Context, colorId: Int, radiusDp: Float) = GradientDrawable().apply {
        cornerRadius = radiusDp * ctx.resources.displayMetrics.density
        setColor(color(ctx, colorId))
    }

    /** "5 min", "3 h", "Yesterday"... */
    fun ago(time: Long, now: Long = System.currentTimeMillis()): String {
        if (time <= 0) return ""
        val m = (now - time) / 60_000
        return when {
            m < 1 -> "Now"
            m < 60 -> "$m min"
            m < 24 * 60 -> "${m / 60} h"
            else -> DateUtils.getRelativeTimeSpanString(time, now, DateUtils.DAY_IN_MILLIS).toString()
        }
    }
}
