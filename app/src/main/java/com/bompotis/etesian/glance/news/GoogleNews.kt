package com.bompotis.etesian.glance.news

import java.util.Locale

/**
 * Google News without the app or its page: its RSS feeds (Top stories and one per topic) for the phone's language
 * and country. No ads, no tracking scripts; a story's link goes through news.google.com to the publisher.
 */
object GoogleNews {
    val TOPICS = listOf("WORLD", "NATION", "BUSINESS", "TECHNOLOGY", "SCIENCE", "HEALTH", "SPORTS", "ENTERTAINMENT")

    fun edition(locale: Locale = Locale.getDefault()): String {
        val country = locale.country.ifEmpty { "US" }
        val lang = locale.language.ifEmpty { "en" }
        return "hl=$lang-$country&gl=$country&ceid=$country:$lang"
    }

    fun topUrl(edition: String) = "https://news.google.com/rss?$edition"
    fun topicUrl(topic: String, edition: String) = "https://news.google.com/rss/headlines/section/topic/$topic?$edition"

    /** A search feed: the latest coverage of a keyword, in the same edition. */
    fun searchUrl(query: String, edition: String) =
        "https://news.google.com/rss/search?q=${java.net.URLEncoder.encode(query.trim(), "UTF-8")}&$edition"

    /** Google News items as articles: "Headline - Publication" split, the other outlets on the story kept. */
    fun articles(items: List<Rss.Item>, topic: String, top: Boolean): List<Article> = items.map { i ->
        val source = i.source.ifBlank { i.title.substringAfterLast(" - ", "") }
        val title = if (source.isNotEmpty() && i.title.endsWith(" - $source")) i.title.removeSuffix(" - $source").trim() else i.title
        Article(title, source, i.link, i.time, topic, "", i.image, Origin.GOOGLE, top, coverage(i.description, source))
    }

    /** The description is a list of the story's coverage: "<a>Headline</a>&nbsp;<font>Outlet</font>" per entry. */
    fun coverage(description: String, own: String): List<String> =
        Regex("<font[^>]*>([^<]+)</font>").findAll(description).map { Rss.clean(it.groupValues[1]) }
            .filter { it.isNotEmpty() && it != own }.distinct().toList()
}
