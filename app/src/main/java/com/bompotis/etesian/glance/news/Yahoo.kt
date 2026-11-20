package com.bompotis.etesian.glance.news

/**
 * Yahoo News' own RSS (US, in English): a second editors' pick mixed with Google's. Its links go to Yahoo's copy
 * or the publisher directly (no script redirect), so they can be read offline if the user asks. The same story
 * from both is one story: the Ranker groups articles by their headline's words, whatever the source.
 */
object Yahoo {
    const val URL = "https://news.yahoo.com/rss/"
    const val NAME = "Yahoo News"

    fun articles(items: List<Rss.Item>): List<Article> = items.map { i ->
        val source = i.source.takeIf { it.isNotBlank() && !it.equals("Yahoo News - Latest News & Headlines", true) } ?: NAME
        Article(i.title, source, i.link, i.time, "", Rss.clean(i.description).take(300), i.image, Origin.YAHOO)
    }
}
