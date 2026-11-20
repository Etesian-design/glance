package com.bompotis.etesian.glance.news

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.bompotis.etesian.glance.Prefs
import com.bompotis.etesian.glance.Reader
import com.bompotis.etesian.glance.ReaderActivity
import java.io.File

/**
 * Reading offline: after a refresh, on Wi-Fi (an unmetered network) and out of battery saver, the new articles of
 * the subscriptions marked "Keep for offline reading" are opened out of sight, one at a time, and the reader's
 * own extraction ([Reader.EXTRACT] over Readability) is kept so the reader opens them with no network.
 *
 * Never Google News' links: they reach the publisher only through Google's page script. A site that answers
 * 403 or 429 is left alone for the rest of the run.
 */
object Offline {
    private const val TAG = "GlanceOffline"
    private const val PER_RUN = 30
    private const val PAGE_TIMEOUT_MS = 25_000L
    private const val SETTLE_MS = 1500L

    @Volatile var running = false; private set
    private val main = Handler(Looper.getMainLooper())

    fun store(ctx: Context) = OfflineStore(File(ctx.filesDir, "offline"))

    /** The kept article for a link, if there is one: where it landed, and what the reader shows. */
    fun load(ctx: Context, link: String): Pair<String, ReaderActivity.Article>? {
        val s = store(ctx).get(link) ?: return null
        val a = Reader.parse(org.json.JSONObject.quote(s.article)) ?: return null
        return s.landed.ifEmpty { link } to a
    }

    fun allowedNow(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val pm = ctx.getSystemService(PowerManager::class.java)
        return cm.activeNetwork != null && !cm.isActiveNetworkMetered && !pm.isPowerSaveMode
    }

    /** What may be kept: posts of offline subscriptions (and Yahoo's, if switched on); never Google's. */
    fun wanted(articles: List<Article>, offlineFeeds: Set<String>, yahoo: Boolean): List<Article> = articles
        .filter { (it.origin == Origin.FEEDS && it.feed in offlineFeeds) || (yahoo && it.origin == Origin.YAHOO) }
        .filter { !Uri.parse(it.link).host.orEmpty().endsWith("news.google.com") }
        .sortedByDescending { it.time }

    /** Called after a refresh, from any thread. [done] runs on the main thread when the run ends. */
    fun prefetch(ctx: Context, articles: List<Article>, done: () -> Unit) {
        val app = ctx.applicationContext
        if (running) return
        val store = store(app)
        val todo = wanted(articles, Subscriptions.all(app).filter { it.offline }.map { it.url }.toSet(), Prefs.yahooOffline(app))
            .filter { !store.has(it.link) }.take(PER_RUN)
        store.evict()
        if (todo.isEmpty() || !allowedNow(app)) return
        running = true
        main.post { Run(app, store, todo) { running = false; done() }.next() }
    }

    private class Run(val ctx: Context, val store: OfflineStore, todo: List<Article>, val finished: () -> Unit) {
        private val queue = ArrayDeque(todo)
        private val blocked = HashSet<String>()
        private var current: Article? = null
        private var landed = ""
        private var token = 0
        private val script by lazy { ctx.assets.open("readability/Readability.js").reader().use { it.readText() } }
        @SuppressLint("SetJavaScriptEnabled")
        private val web = WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.blockNetworkImage = true
            settings.mediaPlaybackRequiresUserGesture = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            layout(0, 0, 1080, 2000)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) =
                    r.url.scheme != "http" && r.url.scheme != "https"
                override fun onReceivedHttpError(v: WebView, r: WebResourceRequest, resp: WebResourceResponse) {
                    if (r.isForMainFrame && (resp.statusCode == 403 || resp.statusCode == 429)) {
                        blocked += host(r.url.toString()); Log.i(TAG, "${resp.statusCode} from ${r.url.host}: backing off")
                        skip()
                    }
                }
                override fun onPageFinished(v: WebView, url: String) {
                    if (current == null || url == "about:blank") return
                    landed = url
                    val t = token
                    main.postDelayed({ if (t == token) extract() }, SETTLE_MS)
                }
            }
        }

        private fun host(u: String) = Uri.parse(u).host.orEmpty().removePrefix("www.")

        fun next() {
            token++
            current = null
            if (!allowedNow(ctx)) queue.clear()
            var a: Article? = null
            while (queue.isNotEmpty() && a == null) queue.removeFirst().takeIf { host(it.link) !in blocked }?.let { a = it }
            val art = a ?: run { web.destroy(); finished(); return }
            current = art; landed = art.link
            val t = token
            main.postDelayed({ if (t == token) skip() }, PAGE_TIMEOUT_MS)
            web.loadUrl(art.link)
        }

        private fun skip() { web.stopLoading(); main.post { next() } }

        private fun extract() {
            val a = current ?: return
            val t = token
            web.evaluateJavascript(script + "\n" + Reader.EXTRACT) { raw ->
                if (t != token) return@evaluateJavascript
                val parsed = Reader.parse(raw)
                if (parsed != null && parsed.length >= ReaderActivity.MIN_ARTICLE) {
                    runCatching { store.put(a.link, landed, org.json.JSONTokener(raw).nextValue() as String) }
                        .onFailure { Log.w(TAG, "save", it) }
                }
                skip()
            }
        }
    }
}
