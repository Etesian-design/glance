package com.bompotis.etesian.glance.news

import java.net.HttpURLConnection
import java.net.URL

/** Plain HTTP for feeds and pages: a phone browser's user agent, short timeouts. */
object Net {
    const val MOBILE_UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

    /** The body as text, and where it ended up after redirects. */
    fun text(url: String, max: Int = 3_000_000): Pair<String, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 20_000; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", MOBILE_UA)
        try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            val body = c.inputStream.reader().use { r -> val b = CharArray(max); var n = 0
                while (n < max) { val k = r.read(b, n, max - n); if (k < 0) break; n += k }; String(b, 0, n) }
            return body to c.url.toString()
        } finally { c.disconnect() }
    }

    /** A feed from what the user typed: a feed URL, or a site whose page names its feed. Its title and URL. */
    fun discover(input: String): Pair<String, String> {
        val url = input.trim().let { if (it.startsWith("http")) it else "https://$it" }
        val (body, landed) = text(url)
        if (Discovery.isFeed(body)) return Rss.parseFeed(body.reader()).title to url
        val found = Discovery.feeds(body, landed).firstOrNull() ?: throw java.io.IOException("no feed")
        val (feed, _) = text(found)
        return Rss.parseFeed(feed.reader()).title to found
    }
}
