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

    /** A bottom bar action. */
    class Action(val id: String, val icon: Int, val label: String)

    /**
     * The page's actions on the Etesian (N9) bottom bar, Back at its far left, as every Etesian app does (no buttons
     * on top). Off the ROM, or with the bar off, the page draws the same bar itself: returned, else null.
     */
    fun toolbar(activity: android.app.Activity, root: android.widget.LinearLayout?, back: Boolean, actions: List<Action>,
                onTap: (String) -> Unit): android.view.View? {
        if (com.bompotis.etesian.toolbar.EtesianToolbar.isAvailable(activity)) {
            com.bompotis.etesian.toolbar.EtesianToolbar.of(activity).setBack(back)
                .setItems(actions.map { com.bompotis.etesian.toolbar.EtesianToolbar.Item.action(it.id, it.icon, it.label) }) { onTap(it) }
            return null
        }
        if (root == null) return null
        val d = activity.resources.displayMetrics.density
        val bar = android.widget.LinearLayout(activity).apply { setBackgroundColor(color(activity, SURFACE)) }
        val all = (if (back) listOf(Action("back", R.drawable.ic_back_bar, activity.getString(R.string.back))) else emptyList()) + actions
        for (a in all) bar.addView(android.widget.ImageView(activity).apply {
            setImageResource(a.icon)
            imageTintList = android.content.res.ColorStateList.valueOf(color(activity, PRIMARY))
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            contentDescription = a.label
            setPadding(0, (14 * d).toInt(), 0, (14 * d).toInt())
            setOnClickListener { if (a.id == "back") @Suppress("DEPRECATION") activity.onBackPressed() else onTap(a.id) }
        }, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(bar, android.widget.LinearLayout.LayoutParams(-1, -2))
        return bar
    }
}
