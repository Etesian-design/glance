package com.bompotis.etesian.glance.news

import org.json.JSONArray
import org.json.JSONObject

/** Where an article came from: Google News (editors' picks by topic) or one of the user's Feeds subscriptions. */
enum class Origin { GOOGLE, FEEDS }

/** One article, from any source. */
data class Article(
    val title: String,
    /** The publication ("BBC News"). */
    val source: String,
    val link: String,
    val time: Long,
    /** The Google News topic it was listed under (WORLD, TECHNOLOGY...), or "" for a Feeds post. */
    val topic: String = "",
    val summary: String = "",
    val image: String = "",
    val origin: Origin = Origin.GOOGLE,
    /** Listed in Google News' Top stories (the editors' and its own pick of what matters now). */
    val top: Boolean = false,
    /** The other outlets Google News lists under the same story. */
    val coverage: List<String> = emptyList(),
    /** A Feeds post's own id (Feeds marks posts read by it). */
    val ref: String = "",
) {
    val id: String get() = link

    fun toJson(): JSONObject = JSONObject().put("title", title).put("source", source).put("link", link).put("time", time)
        .put("topic", topic).put("summary", summary).put("image", image).put("origin", origin.name).put("top", top)
        .put("coverage", JSONArray(coverage)).put("ref", ref)

    companion object {
        fun fromJson(o: JSONObject) = Article(o.getString("title"), o.optString("source"), o.getString("link"),
            o.optLong("time"), o.optString("topic"), o.optString("summary"), o.optString("image"),
            runCatching { Origin.valueOf(o.optString("origin")) }.getOrDefault(Origin.GOOGLE), o.optBoolean("top"),
            o.optJSONArray("coverage")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
            o.optString("ref"))
    }
}

/** A story: articles about the same thing, the best placed one leading. */
data class Story(
    val lead: Article,
    val others: List<Article>,
    val score: Double,
    val section: Section,
) {
    val sources: Set<String> get() = (listOf(lead) + others).map { it.source }.toSet() + lead.coverage
    /** The lead's picture, else one from another article on the story. */
    val image: String get() = lead.image.ifEmpty { others.firstOrNull { it.image.isNotEmpty() }?.image.orEmpty() }
}

enum class Section(val title: String) { TOP("Top stories"), FOR_YOU("For you") }
