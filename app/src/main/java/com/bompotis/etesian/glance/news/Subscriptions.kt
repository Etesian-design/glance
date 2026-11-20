package com.bompotis.etesian.glance.news

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.Reader
import java.net.URI

/** One of Glance's own subscriptions: an RSS or Atom feed. */
data class Subscription(val url: String, val title: String, val offline: Boolean = false) {
    fun toJson(): JSONObject = JSONObject().put("url", url).put("title", title).put("offline", offline)

    companion object {
        fun fromJson(o: JSONObject) = Subscription(o.getString("url"), o.optString("title"), o.optBoolean("offline"))
    }
}

/**
 * Glance's subscriptions, its own (so stock Android has feeds too, without the Etesian Feeds app): kept in a file,
 * fetched with the news. On Etesian the Feeds app's posts are mixed in as well.
 */
object Subscriptions {
    private fun file(ctx: Context) = File(ctx.filesDir, "subscriptions.json")

    fun all(ctx: Context): List<Subscription> = synchronized(this) {
        runCatching { fromJson(file(ctx).readText()) }.getOrDefault(emptyList())
    }

    fun save(ctx: Context, subs: List<Subscription>) = synchronized(this) {
        file(ctx).writeText(toJson(subs))
    }

    /** Added, or replaced if the same feed is there already (same URL). */
    fun put(ctx: Context, s: Subscription) = synchronized(this) {
        val list = all(ctx).toMutableList()
        val i = list.indexOfFirst { it.url == s.url }
        if (i >= 0) list[i] = s else list += s
        save(ctx, list)
    }

    fun remove(ctx: Context, url: String) = synchronized(this) { save(ctx, all(ctx).filter { it.url != url }) }

    fun toJson(subs: List<Subscription>): String = JSONArray(subs.map { it.toJson() }).toString()
    fun fromJson(s: String): List<Subscription> = JSONArray(s).let { a -> (0 until a.length()).map { Subscription.fromJson(a.getJSONObject(it)) } }

    /** The posts of a fetched subscription, as articles (source: the feed's name). */
    fun articles(sub: Subscription, feed: Rss.Feed): List<Article> = feed.items.map { i ->
        Article(i.title, sub.title.ifBlank { feed.title }.ifBlank { i.source }, i.link, i.time, "",
            Rss.clean(i.description).take(300), i.image, Origin.FEEDS, feed = sub.url)
    }
}

/** Finding the feed of a site: the page's <link rel="alternate" type="application/rss+xml"> (or Atom). */
object Discovery {
    private val linkTag = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE)
    private fun attr(tag: String, name: String) =
        Regex("\\b$name\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", RegexOption.IGNORE_CASE).find(tag)
            ?.let { m -> m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } }

    /** Whether a response is a feed (else it is a page to look in). */
    fun isFeed(text: String): Boolean {
        val head = text.take(2000).trimStart().lowercase()
        return head.startsWith("<?xml") && ("<rss" in head || "<feed" in head || "<rdf" in head) ||
            head.startsWith("<rss") || head.startsWith("<feed")
    }

    /** The feeds a page names, as absolute URLs, in the page's order. */
    fun feeds(html: String, base: String): List<String> = linkTag.findAll(html).mapNotNull { m ->
        val tag = m.value
        val rel = attr(tag, "rel")?.lowercase().orEmpty()
        val type = attr(tag, "type")?.lowercase().orEmpty()
        val href = attr(tag, "href")?.replace("&amp;", "&") ?: return@mapNotNull null
        if ("alternate" !in rel.split(Regex("\\s+")) || (type != "application/rss+xml" && type != "application/atom+xml")) null
        else runCatching { URI(base).resolve(href.trim()).toString() }.getOrNull()
    }.distinct().toList()
}

/** OPML, how feed readers pass subscriptions between them. */
object Opml {
    fun write(subs: List<Subscription>): String {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<opml version=\"2.0\">\n<head><title>Glance subscriptions</title></head>\n<body>\n")
            subs.forEach { append("<outline type=\"rss\" text=\"${esc(it.title)}\" title=\"${esc(it.title)}\" xmlUrl=\"${esc(it.url)}\"/>\n") }
            append("</body>\n</opml>\n")
        }
    }

    /** Every outline with a feed URL, folders flattened. */
    fun read(reader: Reader): List<Subscription> {
        val p = XmlPullParserFactory.newInstance().newPullParser()
        p.setInput(reader)
        val out = ArrayList<Subscription>()
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && p.name.equals("outline", true)) {
                val url = (p.getAttributeValue(null, "xmlUrl") ?: p.getAttributeValue(null, "xmlurl")).orEmpty().trim()
                if (url.startsWith("http") && out.none { it.url == url })
                    out += Subscription(url, (p.getAttributeValue(null, "title") ?: p.getAttributeValue(null, "text")).orEmpty().trim())
            }
        }
        return out
    }
}
