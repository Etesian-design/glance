package com.bompotis.etesian.glance.news

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.Reader
import java.text.SimpleDateFormat
import java.util.Locale

/** RSS 2.0 and Atom, the parts news needs: title, link, date, publication, summary, picture. */
object Rss {

    data class Item(
        val title: String,
        val link: String,
        val time: Long,
        val source: String,
        val description: String,
        val image: String,
    )

    fun parse(reader: Reader): List<Item> {
        val p = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        p.setInput(reader)
        val items = ArrayList<Item>()
        var channel = ""
        var inItem = false
        var title = ""; var link = ""; var date = ""; var source = ""; var desc = ""; var image = ""
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && inItem && p.namespace.orEmpty().contains("search.yahoo.com/mrss")) {
                // media:content / media:thumbnail: the picture (Media RSS).
                if ((p.name == "content" || p.name == "thumbnail") && image.isEmpty() &&
                    p.getAttributeValue(null, "medium").let { it == null || it == "image" })
                    image = p.getAttributeValue(null, "url").orEmpty()
            } else if (p.eventType == XmlPullParser.START_TAG) {
                when (p.name) {
                    "item", "entry" -> {
                        inItem = true
                        title = ""; link = ""; date = ""; source = ""; desc = ""; image = ""
                    }
                    "title" -> if (inItem) title = text(p) else if (channel.isEmpty()) channel = text(p)
                    "link" -> if (inItem) {
                        val href = p.getAttributeValue(null, "href")
                        val rel = p.getAttributeValue(null, "rel")
                        if (href != null) { if (rel == null || rel == "alternate") link = href } else link = text(p)
                    }
                    "pubDate", "published", "updated", "date" -> if (inItem && date.isEmpty()) date = text(p)
                    "source" -> if (inItem) source = text(p)
                    "description", "summary", "content", "encoded" -> if (inItem && desc.isEmpty()) desc = text(p)
                    "enclosure" -> if (inItem && image.isEmpty() &&
                        p.getAttributeValue(null, "type").orEmpty().startsWith("image/")) image = p.getAttributeValue(null, "url").orEmpty()
                }
            } else if (p.eventType == XmlPullParser.END_TAG && (p.name == "item" || p.name == "entry") && inItem) {
                inItem = false
                if (title.isNotBlank() && link.isNotBlank())
                    items += Item(clean(title), link.trim(), time(date), source.trim().ifEmpty { channel.trim() }, desc, image)
            }
        }
        return items
    }

    private fun text(p: XmlPullParser): String {
        val sb = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (p.next()) {
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> sb.append(p.text)
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return sb.toString()
            }
        }
        return sb.toString()
    }

    /** Entities and tags out, spaces collapsed. */
    fun clean(s: String): String = s.replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">")
        .replace(Regex("\\s+"), " ").trim()

    private val formats = listOf("EEE, dd MMM yyyy HH:mm:ss zzz", "EEE, dd MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm zzz",
        "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd")

    fun time(s: String): Long {
        val t = s.trim()
        if (t.isEmpty()) return 0
        for (f in formats) {
            val fmt = SimpleDateFormat(f, Locale.US)
            if (f.endsWith("'Z'")) fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
            runCatching { return fmt.parse(t)!!.time }
        }
        return 0
    }
}
