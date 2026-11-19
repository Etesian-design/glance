package com.bompotis.etesian.glance

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bompotis.etesian.glance.news.Section
import com.bompotis.etesian.glance.news.Story
import com.bompotis.etesian.glance.search.Kind
import com.bompotis.etesian.glance.search.Result
import com.bompotis.etesian.glance.search.Searcher
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** The rows of Glance's list: section headings, stories, search results, the setup card and a status line. */
class Rows(
    private val activity: MainActivity,
    private val onStoryMenu: (View, Story) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class Row {
        class Heading(val text: String) : Row()
        class StoryRow(val story: Story, val lead: Boolean) : Row()
        class ResultRow(val result: Result) : Row()
        class SetupCard(val needs: List<Setup.Need>) : Row()
        class Status(val text: String) : Row()
    }

    private var rows: List<Row> = emptyList()
    private val d = activity.resources.displayMetrics.density
    private fun px(dp: Int) = (dp * d).toInt()

    fun showNews(stories: List<Story>, refreshing: Boolean, last: Long, missing: List<Setup.Need>) {
        rows = buildList {
            if (missing.isNotEmpty()) add(Row.SetupCard(missing))
            if (stories.isEmpty()) add(Row.Status(activity.getString(if (refreshing) R.string.loading_news else R.string.no_news)))
            var section: Section? = null
            stories.forEachIndexed { i, s ->
                if (s.section != section) { section = s.section; add(Row.Heading(s.section.title)) }
                add(Row.StoryRow(s, lead = i == 0))
            }
            if (stories.isNotEmpty() && last > 0)
                add(Row.Status(activity.getString(R.string.updated, Ui.ago(last))))
        }
        notifyDataSetChanged()
    }

    fun showResults(sections: List<Searcher.Section>) {
        rows = buildList {
            sections.forEach { s ->
                add(Row.Heading(s.title))
                s.results.forEach { add(Row.ResultRow(it)) }
            }
        }
        notifyDataSetChanged()
    }

    fun openFirst() {
        (rows.firstOrNull { it is Row.ResultRow } as? Row.ResultRow)?.let { activity.open(it.result.open) }
    }

    override fun getItemCount() = rows.size
    override fun getItemViewType(position: Int) = when (rows[position]) {
        is Row.Heading -> 0; is Row.StoryRow -> 1; is Row.ResultRow -> 2; is Row.SetupCard -> 3; is Row.Status -> 4
    }

    private class Holder(v: View) : RecyclerView.ViewHolder(v)

    override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder = Holder(when (type) {
        0 -> text(13f, Ui.ACCENT, bold = true).apply {
            setPadding(px(16), px(18), px(16), px(6))
            isAllCaps = true
            letterSpacing = 0.06f
        }
        4 -> text(13f, Ui.SECONDARY).apply { gravity = Gravity.CENTER; setPadding(px(16), px(24), px(16), px(24)) }
        else -> LinearLayout(activity).apply {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    })

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
        when (val r = rows[position]) {
            is Row.Heading -> (h.itemView as TextView).text = r.text
            is Row.Status -> (h.itemView as TextView).text = r.text
            is Row.StoryRow -> bindStory(h.itemView as LinearLayout, r)
            is Row.ResultRow -> bindResult(h.itemView as LinearLayout, r.result)
            is Row.SetupCard -> bindSetup(h.itemView as LinearLayout, r.needs)
        }
    }

    private fun text(size: Float, color: Int, bold: Boolean = false) = TextView(activity).apply {
        textSize = size
        setTextColor(Ui.color(activity, color))
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun bindStory(row: LinearLayout, r: Row.StoryRow) {
        row.removeAllViews()
        row.orientation = LinearLayout.VERTICAL
        // As in Google News: the picture edge to edge, the headline and where it's from under it.
        row.setPadding(0, 0, 0, px(14))
        row.background = ripple()
        val s = r.story
        val img = Picture(activity)
        row.addView(img, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = px(6); bottomMargin = px(12) })
        val tile = Tile(s.lead.source.ifEmpty { s.lead.title }, px(1).toFloat())
        if (s.image.isNotEmpty()) Images.load(s.image, img, tile) else { img.tag = null; img.setImageDrawable(tile) }
        row.addView(text(if (r.lead) 21f else 18f, Ui.PRIMARY, bold = true).apply {
            setPadding(px(16), 0, px(16), 0)
            text = s.lead.title
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.1f)
        })
        val outlets = s.sources.size
        val meta = listOfNotNull(
            s.lead.source.takeIf { it.isNotEmpty() },
            Ui.ago(s.lead.time).takeIf { it.isNotEmpty() },
            if (outlets > 1) activity.resources.getQuantityString(R.plurals.outlets, outlets, outlets) else null,
            topicLabel(s.lead.topic),
        ).joinToString(" · ")
        row.addView(text(13f, Ui.SECONDARY).apply { text = meta; setPadding(px(16), px(6), px(16), 0) })
        if (s.lead.summary.isNotEmpty() && s.lead.origin == com.bompotis.etesian.glance.news.Origin.FEEDS) {
            row.addView(text(14f, Ui.SECONDARY).apply {
                text = s.lead.summary; maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(px(16), px(4), px(16), 0)
            })
        }
        row.contentDescription = "story:${s.lead.title}"
        row.setOnClickListener { activity.openStory(s) }
        row.setOnLongClickListener { onStoryMenu(it, s); true }
    }

    private fun topicLabel(t: String) = when (t) {
        "" -> null; "NATION" -> activity.getString(R.string.topic_nation)
        else -> t.lowercase().replaceFirstChar { it.uppercase() }
    }

    private fun bindResult(row: LinearLayout, r: Result) {
        row.removeAllViews()
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(px(16), px(8), px(16), px(8))
        row.background = ripple()
        val icon = ImageView(activity)
        row.addView(icon, LinearLayout.LayoutParams(px(40), px(40)).apply { marginEnd = px(14) })
        icon.setImageDrawable(iconFor(r))
        val texts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(16f, Ui.PRIMARY).apply { text = r.title; maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        if (r.subtitle.isNotEmpty()) texts.addView(text(13.5f, Ui.SECONDARY).apply {
            text = r.subtitle; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (r.time > 0 && r.kind in setOf(Kind.MESSAGE, Kind.NOTIFICATION, Kind.FILE, Kind.NEWS, Kind.FEED))
            row.addView(text(12f, Ui.SECONDARY).apply { text = Ui.ago(r.time); setPadding(px(8), 0, 0, 0) })
        row.contentDescription = "result:${r.kind.name.lowercase()}:${r.title}"
        row.setOnClickListener { activity.open(r.open) }
    }

    private fun iconFor(r: Result): Drawable {
        if (r.pkg.isNotEmpty()) runCatching { return activity.packageManager.getApplicationIcon(r.pkg) }
        val res = when (r.kind) {
            Kind.WEB -> R.drawable.ic_search
            Kind.FILE -> R.drawable.ic_file
            Kind.NEWS, Kind.FEED -> R.drawable.ic_news
            else -> 0
        }
        if (res != 0) return activity.getDrawable(res)!!.mutate().apply { setTint(Ui.color(activity, Ui.SECONDARY)) }
        return Letter(activity, r.title, Ui.color(activity, Ui.ACCENT))
    }

    private fun bindSetup(row: LinearLayout, needs: List<Setup.Need>) {
        row.removeAllViews()
        row.orientation = LinearLayout.VERTICAL
        row.setPadding(px(16), px(12), px(16), px(12))
        row.background = null
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(activity, Ui.SURFACE, 14f)
            setPadding(px(16), px(14), px(16), px(10))
        }
        card.addView(text(15f, Ui.PRIMARY, bold = true).apply { setText(R.string.setup_title) })
        card.addView(text(13.5f, Ui.SECONDARY).apply { setText(R.string.setup_text); setPadding(0, px(4), 0, px(6)) })
        needs.forEach { n ->
            card.addView(text(15f, Ui.ACCENT, bold = true).apply {
                text = activity.getString(n.label)
                setPadding(0, px(10), 0, px(10))
                background = ripple()
                contentDescription = "setup:${n.name.lowercase()}"
                setOnClickListener { Setup.request(activity, n) }
            })
        }
        row.addView(card)
    }

    private fun ripple(): Drawable? {
        val a = activity.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        return a.getDrawable(0).also { a.recycle() }
    }
}

/** A round tile with a name's first letter (contacts without a photo, messages). */
class Letter(ctx: android.content.Context, name: String, private val color: Int) : Drawable() {
    private val letter = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
    private val bg = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = this@Letter.color }
    private val fg = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = android.graphics.Color.WHITE
        textAlign = android.graphics.Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    override fun draw(c: android.graphics.Canvas) {
        val b = bounds
        val r = minOf(b.width(), b.height()) / 2f
        c.drawCircle(b.exactCenterX(), b.exactCenterY(), r, bg)
        fg.textSize = r
        c.drawText(letter, b.exactCenterX(), b.exactCenterY() - (fg.descent() + fg.ascent()) / 2, fg)
    }
    override fun setAlpha(alpha: Int) { bg.alpha = alpha; fg.alpha = alpha }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { bg.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/** Story pictures, fetched once, kept in memory (a few MB), scaled down to the screen. */
object Images {
    private val cache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())

    /** Loads [url] into [into], showing [meanwhile] until it comes (and if it never does). */
    fun load(url: String, into: ImageView, meanwhile: Drawable? = null) {
        into.tag = url
        cache.get(url)?.let { into.setImageBitmap(it); return }
        into.setImageDrawable(meanwhile)
        pool.execute {
            val bmp = runCatching {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 10_000; c.readTimeout = 15_000
                try {
                    val bytes = c.inputStream.readBytes()
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                    var sample = 1
                    while (o.outWidth / (sample * 2) >= 1080) sample *= 2
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                        inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 })
                } finally { c.disconnect() }
            }.getOrNull() ?: return@execute
            cache.put(url, bmp)
            main.post { if (into.tag == url) into.setImageBitmap(bmp) }
        }
    }
}

/** A story's picture: the width of the screen, 16:9, cropped to fill. */
class Picture(ctx: android.content.Context) : ImageView(ctx) {
    init { scaleType = ScaleType.CENTER_CROP }
    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        setMeasuredDimension(width, width * 9 / 16)
    }
}

/** No picture to show: a colour of the outlet's own (the same each time) with its name, so every story has one. */
class Tile(private val name: String, private val dp: Float) : Drawable() {
    private val hue = ((name.hashCode() and 0x7fffffff) % 360).toFloat()
    private val bg = android.graphics.Paint()
    private val ink = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE6FFFFFF.toInt(); typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = android.graphics.Paint.Align.CENTER
    }
    override fun draw(c: android.graphics.Canvas) {
        val b = bounds
        bg.shader = android.graphics.LinearGradient(0f, b.top.toFloat(), b.width().toFloat(), b.bottom.toFloat(),
            android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.55f)),
            android.graphics.Color.HSVToColor(floatArrayOf((hue + 40) % 360, 0.6f, 0.3f)), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(b, bg)
        ink.textSize = 26 * dp
        val label = TextUtils.ellipsize(name, android.text.TextPaint(ink), b.width() - 48 * dp, TextUtils.TruncateAt.END).toString()
        c.drawText(label, b.exactCenterX(), b.exactCenterY() - (ink.descent() + ink.ascent()) / 2, ink)
    }
    override fun setAlpha(a: Int) { ink.alpha = a }
    override fun setColorFilter(f: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.OPAQUE
}
