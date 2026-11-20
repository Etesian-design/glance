package com.bompotis.etesian.glance.news

import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * What the user reads, learnt on the phone: a weight per topic, publication and keyword, up when a story is
 * opened, down on "Fewer like this", fading back to neutral over a few weeks. Nothing leaves the phone.
 */
class Interests(
    val topics: MutableMap<String, Double> = HashMap(),
    val sources: MutableMap<String, Double> = HashMap(),
    val keywords: MutableMap<String, Double> = HashMap(),
    /** Stories the user asked not to see again (their lead's link). */
    val hidden: MutableSet<String> = HashSet(),
    /** Opened stories: shown lower, so the list moves on. */
    val read: MutableSet<String> = HashSet(),
    var updated: Long = 0,
) {
    /** The keywords picked in Settings (not learnt, not saved here): their stories come first. */
    var wanted: List<String> = emptyList()

    fun opened(a: Article, now: Long = System.currentTimeMillis()) {
        decay(now)
        if (a.topic.isNotEmpty()) bump(topics, a.topic, 0.15)
        bump(sources, a.source, 0.10)
        Ranker.tokens(a.title).forEach { bump(keywords, it, 0.05) }
        read += a.link
        trim()
    }

    fun fewerLike(a: Article, now: Long = System.currentTimeMillis()) {
        decay(now)
        if (a.topic.isNotEmpty()) bump(topics, a.topic, -0.2)
        bump(sources, a.source, -0.3)
        Ranker.tokens(a.title).forEach { bump(keywords, it, -0.05) }
        hidden += a.link
        trim()
    }

    /** Every weight halves towards neutral in [HALF_LIFE_DAYS] days. */
    fun decay(now: Long) {
        if (updated > 0 && now > updated) {
            val f = 0.5.pow((now - updated) / 86_400_000.0 / HALF_LIFE_DAYS)
            listOf(topics, sources, keywords).forEach { m -> m.replaceAll { _, v -> v * f } }
        }
        updated = now
    }

    private fun bump(m: MutableMap<String, Double>, k: String, by: Double) {
        if (k.isBlank()) return
        m[k] = ((m[k] ?: 0.0) + by).coerceIn(-1.0, 1.5)
    }

    private fun trim() {
        if (keywords.size > 600) keywords.entries.sortedBy { kotlin.math.abs(it.value) }.take(keywords.size - 500)
            .forEach { keywords.remove(it.key) }
        if (read.size > 800) read.toList().take(read.size - 600).forEach { read.remove(it) }
        if (hidden.size > 800) hidden.toList().take(hidden.size - 600).forEach { hidden.remove(it) }
    }

    fun toJson(): JSONObject = JSONObject().put("topics", JSONObject(topics as Map<*, *>))
        .put("sources", JSONObject(sources as Map<*, *>)).put("keywords", JSONObject(keywords as Map<*, *>))
        .put("hidden", org.json.JSONArray(hidden.toList())).put("read", org.json.JSONArray(read.toList()))
        .put("updated", updated)

    companion object {
        const val HALF_LIFE_DAYS = 14.0

        fun fromJson(o: JSONObject): Interests {
            fun map(k: String) = HashMap<String, Double>().apply {
                o.optJSONObject(k)?.let { j -> j.keys().forEach { put(it, j.getDouble(it)) } }
            }
            fun set(k: String) = HashSet<String>().apply {
                o.optJSONArray(k)?.let { a -> (0 until a.length()).forEach { add(a.getString(it)) } }
            }
            return Interests(map("topics"), map("sources"), map("keywords"), set("hidden"), set("read"), o.optLong("updated"))
        }
    }
}

/**
 * Picks and orders the news: the selection Glance makes on the phone. A later version can get the stories from
 * Cloud Companion instead (its agents choosing), through the same [Selection] interface.
 */
interface Selection {
    fun select(articles: List<Article>, interests: Interests, now: Long): List<Story>
}

object Ranker : Selection {
    /** Freshness halves every [HALF_LIFE_H] hours. */
    const val HALF_LIFE_H = 8.0
    const val TOP_COUNT = 5
    const val MAX_STORIES = 60
    /** Titles sharing this much of their words are the same story. */
    const val SAME_STORY = 0.45
    const val NO_PICTURE = 0.4
    /** For you shows only stories with pictures, unless fewer than this have one. */
    const val MIN_FOR_YOU = 20
    private const val MAX_AGE_H = 72.0
    /** A story on one of the user's keywords counts this much more. */
    const val KEYWORD_BOOST = 2.5

    /**
     * Whether an article is about a keyword: each of its words starts a word of the headline or summary, matched
     * like search (no case, no accents: "Εκλογές" finds "εκλογες"; "election" finds "elections").
     */
    fun matchesKeyword(a: Article, keywords: List<String>): Boolean {
        val split = Regex("[^\\p{L}\\p{N}]+")
        val words = com.bompotis.etesian.glance.search.Match.normalize(a.title + " " + a.summary).split(split).filter { it.isNotEmpty() }
        return keywords.any { k ->
            val kw = com.bompotis.etesian.glance.search.Match.normalize(k).split(split).filter { it.isNotEmpty() }
            kw.isNotEmpty() && kw.all { w -> words.any { it.startsWith(w) } }
        }
    }

    private val stop = setOf(
        "the", "and", "for", "with", "that", "this", "from", "into", "after", "over", "about", "than", "what", "when",
        "will", "would", "could", "should", "says", "said", "have", "has", "had", "are", "was", "were", "been", "its",
        "his", "her", "their", "they", "you", "your", "our", "but", "not", "new", "how", "why", "who", "more", "most",
        "out", "off", "can", "all", "any", "may", "now", "first", "last", "year", "years", "day", "live", "news",
        "update", "updates", "video", "watch", "report", "amid", "just", "one", "two", "via",
    )

    /** The words that say what a title is about (3+ letters, no filler). */
    fun tokens(title: String): Set<String> = com.bompotis.etesian.glance.search.Match.normalize(title)
        .split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 && it !in stop }.toSet()

    fun similarity(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val common = a.count { it in b }
        return common.toDouble() / (a.size + b.size - common)
    }

    /** Same-story groups: an article joins the first group whose lead it resembles enough (or has the same link). */
    fun cluster(articles: List<Article>): List<List<Article>> {
        val groups = ArrayList<MutableList<Article>>()
        val keys = ArrayList<Set<String>>()
        for (a in articles.sortedWith(compareByDescending<Article> { it.top }.thenByDescending { it.time })) {
            val t = tokens(a.title)
            val i = groups.indices.firstOrNull { g -> groups[g][0].link == a.link || similarity(keys[g], t) >= SAME_STORY }
            if (i == null) { groups += mutableListOf(a); keys += t } else groups[i] += a
        }
        return groups
    }

    fun score(group: List<Article>, interests: Interests, now: Long): Double {
        val lead = group.first()
        val newest = group.maxOf { it.time }.takeIf { it > 0 } ?: (now - 12 * 3_600_000L)
        val ageH = ((now - newest) / 3_600_000.0).coerceAtLeast(0.0)
        if (ageH > MAX_AGE_H) return 0.0
        val freshness = 0.5.pow(ageH / HALF_LIFE_H)
        // How many outlets cover it: a story everyone reports matters more than one outlet's piece.
        val outlets = (group.map { it.source } + group.flatMap { it.coverage }).toSet().size
        val coverage = 1 + ln(outlets.coerceAtLeast(1).toDouble())
        val keywords = tokens(lead.title).sumOf { interests.keywords[it] ?: 0.0 }.coerceIn(-0.6, 0.8)
        val topic = group.mapNotNull { a -> interests.topics[a.topic] }.maxOrNull() ?: 0.0
        val source = group.maxOf { interests.sources[it.source] ?: 0.0 }
        val interest = exp(topic + source + keywords).coerceIn(0.3, 3.5)
        // The user subscribed to it in Feeds; Google News put it in Top stories.
        val chosen = (if (group.any { it.origin == Origin.FEEDS }) 1.25 else 1.0) * (if (group.any { it.top }) 1.3 else 1.0)
        // A keyword the user picked, in the headline or summary of any article on the story.
        val keyword = if (interests.wanted.isNotEmpty() && group.any { matchesKeyword(it, interests.wanted) }) KEYWORD_BOOST else 1.0
        val seen = if (group.any { it.link in interests.read }) 0.35 else 1.0
        // A story with a picture to show reads like Google News; one without drops back (a tile stands in).
        val pictured = if (group.any { it.image.isNotEmpty() }) 1.0 else NO_PICTURE
        return freshness * coverage * interest * chosen * keyword * seen * pictured
    }

    override fun select(articles: List<Article>, interests: Interests, now: Long): List<Story> {
        val scored = cluster(articles.filter { it.link !in interests.hidden })
            .map { g ->
                // The lead: a Google pick first (it names the story), else the newest.
                val lead = g.firstOrNull { it.top } ?: g.maxBy { it.time }
                Triple(lead, g.filter { it !== lead }, score(g, interests, now))
            }
            .filter { it.third > 0 }
            .sortedByDescending { it.third }

        // Top stories: what Google News' editors lead with (or, without it, the best overall), unread first.
        val topPool = scored.filter { it.first.top }.ifEmpty { scored }
        val top = topPool.sortedByDescending { if (it.first.link in interests.read) it.third * 0.5 else it.third }
            .take(TOP_COUNT)
        val others = scored.filter { t -> top.none { it.first.link == t.first.link } }
        // Stories with a picture; those without only to fill For you up to a screenful or two.
        val hasPicture = { t: Triple<Article, List<Article>, Double> -> t.first.image.isNotEmpty() || t.second.any { it.image.isNotEmpty() } }
        val pictured = others.filter(hasPicture)
        val rest = if (pictured.size >= MIN_FOR_YOU) pictured
            else pictured + others.filterNot(hasPicture).take(MIN_FOR_YOU - pictured.size)

        // For you: best first, but no publication or topic takes over: each repeat counts for less.
        val picked = ArrayList<Story>()
        val perSource = HashMap<String, Int>()
        val perTopic = HashMap<String, Int>()
        val pool = rest.toMutableList()
        while (pool.isNotEmpty() && picked.size < MAX_STORIES - top.size) {
            val best = pool.maxBy { (lead, _, s) ->
                s * 0.7.pow(perSource[lead.source] ?: 0) * 0.85.pow(perTopic[lead.topic] ?: 0)
            }
            pool.remove(best)
            perSource.merge(best.first.source, 1, Int::plus)
            perTopic.merge(best.first.topic, 1, Int::plus)
            picked += Story(best.first, best.second, best.third, Section.FOR_YOU)
        }
        return top.map { Story(it.first, it.second, it.third, Section.TOP) } + picked
    }
}
