package com.bompotis.etesian.glance

import android.content.Context
import android.content.SharedPreferences
import com.bompotis.etesian.glance.news.GoogleNews
import com.bompotis.etesian.glance.search.Kind

/** Glance's settings (Settings screen), read from anywhere. */
object Prefs {
    const val GOOGLE_NEWS = "google_news"
    const val FEEDS = "feeds"
    const val TOPICS = "topics"
    const val EDITION = "edition"
    const val WEB_SUGGESTIONS = "web_suggestions"
    const val SEARCH_ENGINE = "search_engine"
    fun sourceKey(kind: Kind) = "source_${kind.name.lowercase()}"

    val DEFAULT_TOPICS = setOf("WORLD", "NATION", "BUSINESS", "TECHNOLOGY", "SCIENCE")
    const val DUCKDUCKGO = "https://duckduckgo.com/?q=%s"

    fun of(ctx: Context): SharedPreferences = ctx.getSharedPreferences("glance", Context.MODE_PRIVATE)

    fun source(ctx: Context, kind: Kind) = of(ctx).getBoolean(sourceKey(kind), true)
    fun googleNews(ctx: Context) = of(ctx).getBoolean(GOOGLE_NEWS, true)
    fun feeds(ctx: Context) = of(ctx).getBoolean(FEEDS, true)
    fun topics(ctx: Context): Set<String> =
        of(ctx).getStringSet(TOPICS, DEFAULT_TOPICS)!!.filter { it in GoogleNews.TOPICS }.toSet()
    /** The Google News edition ("hl=en-GB&gl=GB&ceid=GB:en"): the phone's language and country unless picked. */
    fun edition(ctx: Context) = of(ctx).getString(EDITION, null)?.takeIf { it.isNotBlank() } ?: GoogleNews.edition()
    fun webSuggestions(ctx: Context) = of(ctx).getBoolean(WEB_SUGGESTIONS, false)
    fun searchEngine(ctx: Context) = of(ctx).getString(SEARCH_ENGINE, DUCKDUCKGO)!!.takeIf { "%s" in it } ?: DUCKDUCKGO
}
