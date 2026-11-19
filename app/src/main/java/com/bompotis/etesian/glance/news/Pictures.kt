package com.bompotis.etesian.glance.news

import java.net.HttpURLConnection
import java.net.URL

/**
 * Pictures for the stories. Google News' feeds carry none, and its links lead to a Google page, not the publisher's.
 * So: publishers whose feeds do carry pictures (fetched alongside, by topic) lend theirs to the Google story about
 * the same thing; an article that links straight to its publisher (a Feeds post) takes the page's own picture.
 */
object Pictures {

    /** Matching a publisher's headline to Google's: looser than same-story clustering, the outlets word it apart. */
    const val SAME_PICTURE = 0.3
    /** And at least this many words in common, so two short headlines don't match on one word. */
    private const val MIN_COMMON = 2

    private val general = listOf(
        "https://feeds.bbci.co.uk/news/rss.xml",
        "https://www.theguardian.com/world/rss",
        "https://www.theguardian.com/uk/rss",
        "https://feeds.skynews.com/feeds/rss/home.xml",
        "https://rss.nytimes.com/services/xml/rss/nyt/HomePage.xml",
        "https://feeds.npr.org/1001/rss.xml",
        "https://abcnews.go.com/abcnews/topstories",
        "https://feeds.bbci.co.uk/news/world/rss.xml",
        "https://www.theguardian.com/us-news/rss",
        "https://feeds.bbci.co.uk/sport/rss.xml",
        "https://www.skysports.com/rss/12040",
        // No pictures in these feeds, but their links are the publisher's own page: its picture is read from there.
        "https://www.aljazeera.com/xml/rss/all.xml",
        "https://rss.dw.com/rdf/rss-en-all",
        "https://www.cbsnews.com/latest/rss/main",
        "https://www.euronews.com/rss",
    )

    /** Publisher feeds for each Google News topic, besides [general]. */
    private val byTopic = mapOf(
        "WORLD" to listOf("https://feeds.bbci.co.uk/news/world/rss.xml", "https://rss.nytimes.com/services/xml/rss/nyt/World.xml"),
        "NATION" to listOf("https://feeds.bbci.co.uk/news/uk/rss.xml", "https://rss.nytimes.com/services/xml/rss/nyt/US.xml"),
        "BUSINESS" to listOf("https://feeds.bbci.co.uk/news/business/rss.xml", "https://www.theguardian.com/business/rss"),
        "TECHNOLOGY" to listOf("https://feeds.bbci.co.uk/news/technology/rss.xml", "https://www.theguardian.com/technology/rss",
            "https://www.theverge.com/rss/index.xml", "https://feeds.arstechnica.com/arstechnica/index"),
        "SCIENCE" to listOf("https://feeds.bbci.co.uk/news/science_and_environment/rss.xml", "https://www.theguardian.com/science/rss"),
        "HEALTH" to listOf("https://feeds.bbci.co.uk/news/health/rss.xml", "https://rss.nytimes.com/services/xml/rss/nyt/Health.xml"),
        "SPORTS" to listOf("https://feeds.bbci.co.uk/sport/rss.xml", "https://www.theguardian.com/sport/rss"),
        "ENTERTAINMENT" to listOf("https://feeds.bbci.co.uk/news/entertainment_and_arts/rss.xml", "https://www.theguardian.com/culture/rss"),
    )

    fun feeds(topics: Collection<String>): List<String> = (general + topics.flatMap { byTopic[it].orEmpty() }).distinct()

    /**
     * For each article without a picture, the publisher article most like it, if one is enough alike (link → match).
     * A match with a picture of its own is preferred over a closer one without (whose page must then be read).
     */
    fun match(articles: List<Article>, donors: List<Article>): Map<String, Article> {
        val pool = donors.map { it to Ranker.tokens(it.title) }
        val out = HashMap<String, Article>()
        for (a in articles) {
            if (a.image.isNotEmpty()) continue
            val t = Ranker.tokens(a.title)
            val alike = pool.mapNotNull { (d, dt) ->
                if (t.count { it in dt } < MIN_COMMON) null
                else Ranker.similarity(t, dt).takeIf { it >= SAME_PICTURE }?.let { d to it }
            }
            (alike.filter { it.first.image.isNotEmpty() }.maxByOrNull { it.second } ?: alike.maxByOrNull { it.second })
                ?.let { out[a.link] = it.first }
        }
        return out
    }

    /** Articles without a picture take their match's, where it has one. */
    fun lend(articles: List<Article>, donors: List<Article>): List<Article> {
        val m = match(articles, donors)
        return articles.map { a -> m[a.link]?.image?.takeIf { it.isNotEmpty() }?.let { a.copy(image = it) } ?: a }
    }

    /** A page's own picture (og:image, else twitter:image), from its head. Not Google's pages: theirs is a logo. */
    fun ofPage(link: String): String {
        val host = runCatching { URL(link).host }.getOrNull() ?: return ""
        if (host.endsWith("google.com")) return ""
        val c = URL(link).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000; c.readTimeout = 8_000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) EtesianGlance")
        return try {
            if (c.responseCode !in 200..299) return ""
            // The meta tags are in the head: the first 256 KB is plenty.
            val buf = CharArray(256 * 1024)
            val n = c.inputStream.reader().use { r -> var total = 0
                while (total < buf.size) { val k = r.read(buf, total, buf.size - total); if (k < 0) break; total += k }
                total }
            meta(String(buf, 0, n), URL(c.url, "/").toString())
        } catch (_: Exception) { "" } finally { c.disconnect() }
    }

    /** og:image or twitter:image in [html], made absolute against [base]. */
    fun meta(html: String, base: String = ""): String {
        for (name in listOf("og:image:secure_url", "og:image", "twitter:image")) {
            val tag = Regex("<meta[^>]+(?:property|name)=[\"']${Regex.escape(name)}[\"'][^>]*>", RegexOption.IGNORE_CASE).find(html)?.value
                ?: Regex("<meta[^>]+content=[\"'][^\"']+[\"'][^>]+(?:property|name)=[\"']${Regex.escape(name)}[\"'][^>]*>", RegexOption.IGNORE_CASE).find(html)?.value
                ?: continue
            val url = Regex("content=[\"']([^\"']+)").find(tag)?.groupValues?.get(1)?.replace("&amp;", "&") ?: continue
            val abs = runCatching { URL(URL(base.ifEmpty { url }), url).toString() }.getOrDefault(url)
            if (abs.startsWith("http")) return abs
        }
        return ""
    }
}
