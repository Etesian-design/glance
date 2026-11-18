package com.bompotis.etesian.glance.news

import android.content.Context
import android.util.Log
import com.bompotis.etesian.glance.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The news: fetched from Google News (Top stories and the topics picked in Settings) and the Feeds app, picked
 * and ordered by [selection], kept in a file so the screen shows at once and works offline.
 */
object NewsStore {
    private const val TAG = "GlanceNews"
    /** A refresh within this long of the last one is skipped, unless forced (pull to refresh). */
    private const val MIN_INTERVAL_MS = 10 * 60_000L

    /** Swappable: Cloud Companion's agents can take over choosing the stories later. */
    @Volatile var selection: Selection = Ranker

    private val io = Executors.newSingleThreadExecutor()
    private val fetchers = Executors.newFixedThreadPool(4)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    @Volatile private var articles: List<Article>? = null
    @Volatile private var stories: List<Story>? = null
    @Volatile private var interests: Interests? = null
    @Volatile var refreshing = false; private set

    fun listen(l: () -> Unit) { listeners += l }
    fun unlisten(l: () -> Unit) { listeners -= l }
    private fun changed(ctx: Context) {
        listeners.forEach { runCatching { it() } }
        ctx.contentResolver.notifyChange(com.bompotis.etesian.glance.GlanceProvider.NEWS, null)
    }

    private fun file(ctx: Context) = File(ctx.filesDir, "news.json")
    private fun interestsFile(ctx: Context) = File(ctx.filesDir, "interests.json")

    fun interests(ctx: Context): Interests = interests ?: synchronized(this) {
        interests ?: runCatching { Interests.fromJson(JSONObject(interestsFile(ctx).readText())) }
            .getOrDefault(Interests()).also { interests = it }
    }

    private fun saveInterests(ctx: Context) {
        val json = synchronized(this) { interests(ctx).toJson().toString() }
        runCatching { interestsFile(ctx).writeText(json) }
    }

    /** The articles from the last refresh (for search). */
    fun cached(ctx: Context): List<Article> = articles ?: synchronized(this) {
        articles ?: runCatching {
            val o = JSONObject(file(ctx).readText())
            val a = o.getJSONArray("articles")
            (0 until a.length()).map { Article.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList()).also { articles = it }
    }

    fun lastRefresh(ctx: Context): Long = runCatching { JSONObject(file(ctx).readText()).optLong("time") }.getOrDefault(0)

    /** The stories, ranked: from memory, else ranked from the saved articles. */
    fun stories(ctx: Context): List<Story> = stories ?: synchronized(this) {
        stories ?: selection.select(cached(ctx), interests(ctx), System.currentTimeMillis()).also { stories = it }
    }

    fun refresh(ctx: Context, force: Boolean = false) {
        val app = ctx.applicationContext
        if (refreshing || (!force && System.currentTimeMillis() - lastRefresh(app) < MIN_INTERVAL_MS)) return
        refreshing = true
        changed(app)
        io.execute {
            try {
                val fresh = fetchAll(app)
                // A failed fetch (offline) keeps what we had.
                if (fresh.isNotEmpty()) {
                    synchronized(this) { articles = fresh; stories = null }
                    runCatching {
                        file(app).writeText(JSONObject().put("time", System.currentTimeMillis())
                            .put("articles", JSONArray(fresh.map { it.toJson() })).toString())
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "refresh", e)
            } finally {
                refreshing = false
                changed(app)
            }
        }
    }

    private fun fetchAll(ctx: Context): List<Article> {
        val edition = Prefs.edition(ctx)
        val jobs = ArrayList<java.util.concurrent.Future<List<Article>>>()
        if (Prefs.googleNews(ctx)) {
            jobs += fetchers.submit<List<Article>> { GoogleNews.articles(get(GoogleNews.topUrl(edition)), "", top = true) }
            Prefs.topics(ctx).forEach { t ->
                jobs += fetchers.submit<List<Article>> { GoogleNews.articles(get(GoogleNews.topicUrl(t, edition)), t, top = false) }
            }
        }
        val out = ArrayList<Article>()
        jobs.forEach { f -> runCatching { out += f.get(30, TimeUnit.SECONDS) }.onFailure { Log.w(TAG, "fetch", it) } }
        // Top stories carry no topic: take it from the same story in a topic feed.
        val topicOf = out.filter { it.topic.isNotEmpty() }.associate { it.link to it.topic }
        val merged = out.groupBy { it.link }.map { (_, same) ->
            val a = same.first()
            a.copy(topic = same.firstOrNull { it.topic.isNotEmpty() }?.topic ?: topicOf[a.link].orEmpty(),
                top = same.any { it.top }, coverage = same.flatMap { it.coverage }.distinct())
        }
        if (Prefs.feeds(ctx)) return merged + FeedsClient.items(ctx).filter { a -> merged.none { it.link == a.link } }
        return merged
    }

    private fun get(url: String): List<Rss.Item> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", "EtesianGlance")
        return try { c.inputStream.reader().use { Rss.parse(it) } } finally { c.disconnect() }
    }

    fun opened(ctx: Context, a: Article) {
        synchronized(this) { interests(ctx).opened(a); stories = null }
        if (a.origin == Origin.FEEDS) FeedsClient.markRead(ctx, a)
        io.execute { saveInterests(ctx) }
        changed(ctx)
    }

    fun fewerLike(ctx: Context, a: Article) {
        synchronized(this) { interests(ctx).fewerLike(a); stories = null }
        io.execute { saveInterests(ctx) }
        changed(ctx)
    }

    fun find(ctx: Context, link: String): Article? = cached(ctx).firstOrNull { it.link == link }

    /** Forget what was learnt (Settings). */
    fun resetInterests(ctx: Context) {
        synchronized(this) { interests = Interests(); stories = null }
        interestsFile(ctx).delete()
        changed(ctx)
    }

    /** Settings changed what to fetch: re-rank now, fetch again. */
    fun settingsChanged(ctx: Context) {
        synchronized(this) { stories = null }
        refresh(ctx, force = true)
    }
}
