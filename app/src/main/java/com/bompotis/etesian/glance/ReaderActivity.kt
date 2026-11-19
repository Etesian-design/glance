package com.bompotis.etesian.glance

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Glance's reader: a story as just its article (Mozilla's Readability, the engine of Firefox's Reader View, itself
 * Arc90's Readability), in Glance's type and colours, without the site's scripts, ads or pop-ups.
 *
 * The link is loaded out of sight first: Google News' links only reach the publisher by a script on Google's page,
 * and many sites build the article with scripts. Once on the publisher's page, Readability takes the article out
 * and it is shown in a second view where scripts are off. A page that isn't an article is shown as it is. The
 * original is always a tap away (Open in browser).
 */
class ReaderActivity : Activity() {

    companion object {
        private const val EXTRA_LINK = "link"
        /** Give up waiting for the page after this long: show what there is. */
        private const val TIMEOUT_MS = 25_000L
        /** Shorter than this (characters) is not an article: a cookie wall, an index page, a video. */
        const val MIN_ARTICLE = 600

        fun intent(ctx: Context, link: String): Intent =
            Intent(ctx, ReaderActivity::class.java).putExtra(EXTRA_LINK, link).setData(Uri.parse(link))
    }

    private lateinit var link: String
    /** Where the page ended up (the publisher's), for the browser and sharing. */
    private var landed = ""
    private var article: Article? = null
    private lateinit var loader: WebView
    private lateinit var reader: WebView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var site: TextView
    private val main = Handler(Looper.getMainLooper())
    private var done = false
    /** Google's cookie choice is on screen (once per phone, in the UK and EU): the user answers it. */
    private var consenting = false
    private val extractRetry = Runnable { extract() }
    private val timeout: Runnable = Runnable {
        when { done -> {}; consenting -> main.postDelayed(timeout, TIMEOUT_MS); else -> showPage() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        link = intent.getStringExtra(EXTRA_LINK) ?: intent.dataString ?: run { finish(); return }
        landed = link
        val d = resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.color(this@ReaderActivity, Ui.BACKGROUND))
        }
        // Just where the article is from, on top; the actions are on the bottom bar.
        site = TextView(this).apply {
            textSize = 14f; setTextColor(Ui.color(this@ReaderActivity, Ui.SECONDARY)); maxLines = 1
            ellipsize = TextUtils.TruncateAt.END; text = host(link)
            setPadding(px(20), px(10), px(20), px(10))
        }
        root.addView(site)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(4)))

        val stack = FrameLayout(this)
        root.addView(stack, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        CookieManager.getInstance().setAcceptCookie(true)

        loader = WebView(this).apply {
            visibility = View.INVISIBLE
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.setSupportMultipleWindows(false)
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.mediaPlaybackRequiresUserGesture = true
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    val s = r.url.scheme
                    if (s == "http" || s == "https") return false
                    // intent:, market:... a page trying to leave for an app: stay.
                    return true
                }
                override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) { if (!done) { landed = url; site.text = host(url) } }
                override fun onPageFinished(v: WebView, url: String) {
                    if (done) return
                    landed = url; site.text = host(url)
                    val h = host(url)
                    // Google's page forwards to the publisher by script: wait for it. Its consent page needs the user.
                    if (h.startsWith("consent.")) { askConsent(); return }
                    if (h == "news.google.com") return
                    // Past Google's consent page (the user chose): back to getting the article.
                    if (consenting) {
                        consenting = false; v.visibility = View.INVISIBLE; this@ReaderActivity.progress.visibility = View.VISIBLE
                        main.removeCallbacks(timeout); main.postDelayed(timeout, TIMEOUT_MS)
                    }
                    // Sites that fill the article in by script: try now, and once more a moment later.
                    main.removeCallbacks(extractRetry)
                    main.postDelayed(extractRetry, 700)
                }
            }
        }
        stack.addView(loader, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        reader = WebView(this).apply {
            visibility = View.INVISIBLE
            settings.javaScriptEnabled = false
            settings.domStorageEnabled = false
            settings.allowFileAccess = false
            setBackgroundColor(Ui.color(this@ReaderActivity, Ui.BACKGROUND))
            // Links in the article open in the browser.
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, r.url)) }
                    return true
                }
            }
        }
        stack.addView(reader, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        status = TextView(this).apply {
            textSize = 15f; setTextColor(Ui.color(this@ReaderActivity, Ui.SECONDARY)); gravity = Gravity.CENTER
            setPadding(px(24), px(24), px(24), px(24)); text = getString(R.string.reader_loading)
        }
        stack.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // The bottom bar, as everywhere in Etesian: Back, then the original in the browser, Share, the menu.
        Ui.toolbar(this, root, back = true, actions = listOf(
            Ui.Action("browser", R.drawable.tb_browser, getString(R.string.open_in_browser)),
            Ui.Action("share", R.drawable.tb_share, getString(R.string.share)),
            Ui.Action("menu", R.drawable.tb_menu, getString(R.string.menu)))) { id ->
            when (id) { "browser" -> openInBrowser(); "share" -> share(); "menu" -> menu() }
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars())
            // The system's bar is drawn over the navigation inset: keep the page clear of it.
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        setContentView(root)
        reader.contentDescription = "reader:loading"
        loader.loadUrl(link)
        main.postDelayed(timeout, TIMEOUT_MS)
    }

    private fun host(url: String) = runCatching { Uri.parse(url).host.orEmpty().removePrefix("www.") }.getOrDefault("")

    private val script by lazy { assets.open("readability/Readability.js").reader().use { it.readText() } }

    /** Readability over the page as it is now; the article shown if there is one. */
    private fun extract() { extractNow() }

    private fun extractNow(): Boolean {
        if (done) return true
        loader.evaluateJavascript(script + "\n" + Reader.EXTRACT) { raw ->
            if (done) return@evaluateJavascript
            val a = Reader.parse(raw) ?: return@evaluateJavascript
            if (a.length < MIN_ARTICLE) return@evaluateJavascript
            article = a
            done = true
            render()
            // The page's own scripts stop here.
            loader.stopLoading(); loader.loadUrl("about:blank")
        }
        return false
    }

    private fun render() {
        val a = article ?: return
        reader.loadDataWithBaseURL(landed, Reader.page(a, Prefs.readerSize(this), colors(), site = a.site.ifEmpty { host(landed) },
            original = original(), originalLabel = getString(R.string.read_on, host(original()))),
            "text/html", "utf-8", null)
        reader.visibility = View.VISIBLE
        loader.visibility = View.INVISIBLE
        status.visibility = View.GONE
        progress.visibility = View.GONE
        reader.contentDescription = "reader:article:${a.length}"
    }

    private fun askConsent() {
        consenting = true
        loader.visibility = View.VISIBLE
        progress.visibility = View.GONE
        status.visibility = View.GONE
        reader.contentDescription = "reader:consent"
    }

    /** Not an article (or too slow): the page itself, with a word that this is it as it is. */
    private fun showPage() {
        if (article != null) return
        done = true
        loader.visibility = View.VISIBLE
        progress.visibility = View.GONE
        status.visibility = View.GONE
        reader.contentDescription = "reader:page"
        android.widget.Toast.makeText(this, R.string.reader_unavailable, android.widget.Toast.LENGTH_LONG).show()
    }

    private fun colors() = Reader.Colors(
        css(Ui.color(this, Ui.BACKGROUND)), css(Ui.color(this, Ui.PRIMARY)), css(Ui.color(this, Ui.SECONDARY)),
        css(Ui.color(this, Ui.ACCENT)), css(Ui.color(this, R.color.divider)))

    private fun css(c: Int) = String.format("#%06X", c and 0xFFFFFF)

    private fun menu() {
        com.bompotis.etesian.toolbar.EtesianMenu.show(this, R.menu.reader, null) { mi ->
            val size = Prefs.readerSize(this)
            when (mi.itemId) {
                R.id.larger -> { Prefs.setReaderSize(this, size + 2); render() }
                R.id.smaller -> { Prefs.setReaderSize(this, size - 2); render() }
            }
            true
        }
    }

    private fun share() {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, listOfNotNull(article?.title, original()).joinToString("\n")), null))
    }

    /** The publisher's link once known (not Google's), else the one we were given. */
    private fun original() = landed.takeIf { it.startsWith("http") && host(it) != "news.google.com" } ?: link

    private fun openInBrowser() {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(original())).addCategory(Intent.CATEGORY_BROWSABLE)) }
    }

    @Deprecated("Back goes back in the page when it is shown as it is")
    override fun onBackPressed() {
        if (loader.visibility == View.VISIBLE && loader.canGoBack()) loader.goBack()
        else @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        loader.destroy(); reader.destroy()
        super.onDestroy()
    }

    /** What Readability found. */
    data class Article(val title: String, val byline: String, val site: String, val content: String, val length: Int,
                       val image: String, val published: String, val lang: String, val dir: String)
}

/** The reader's page: built from Readability's article, no scripts. */
object Reader {

    data class Colors(val background: String, val text: String, val secondary: String, val accent: String, val divider: String)

    /** Run in the page after Readability.js: the article as JSON, with the page's own picture (og:image). */
    const val EXTRACT = """
(function() {
  try {
    var meta = function(n) { var e = document.querySelector('meta[property="' + n + '"],meta[name="' + n + '"]'); return e ? e.content : ''; };
    // Text only screen readers get (BBC's "published at" before every time): drawn 1px or less, or the usual
    // classes. Marked in the live page, where sizes are known, and dropped from the copy Readability reads.
    var linky = function(e) {
      var t = e.textContent.replace(/\s+/g, ''), l = 0, as = e.getElementsByTagName('a');
      for (var k = 0; k < as.length; k++) l += as[k].textContent.replace(/\s+/g, '').length;
      return t.length > 0 && l / t.length > 0.5;
    };
    var all = document.body ? document.body.getElementsByTagName('*') : [];
    for (var i = 0; i < all.length; i++) {
      var el = all[i];
      if (!el.textContent.trim() || el.querySelector('img,picture,video')) continue;
      var cls = typeof el.className === 'string' ? el.className : '';
      if (/visually-?hidden|sr-only|screen-?reader|offscreen/i.test(cls) ||
          (el.getClientRects().length && el.offsetWidth <= 1 && el.offsetHeight <= 1)) el.setAttribute('data-etesian-sr', '1');
      // Menus the page's styles hide on a phone (CNN's whole site menu in its header): Readability reads an unstyled copy
      // and takes them for text. Only hidden navigation goes, inside a header, nav, footer or menu, or mostly links; a
      // hidden part of the article itself (a collapsed "read more") stays.
      else if (!el.getClientRects().length && getComputedStyle(el).display === 'none' &&
          (el.closest('header,nav,footer,aside,[role=navigation],[role=menu],[role=banner],[role=contentinfo]') || linky(el)))
        el.setAttribute('data-etesian-sr', '1');
    }
    var copy = document.cloneNode(true);
    var sr = copy.querySelectorAll('[data-etesian-sr]');
    for (var j = 0; j < sr.length; j++) sr[j].remove();
    var a = new Readability(copy, {charThreshold: 400}).parse();
    if (!a) return null;
    return JSON.stringify({title: a.title || document.title, byline: a.byline || '', site: a.siteName || '',
      content: a.content || '', length: a.length || 0, image: meta('og:image') || meta('twitter:image'),
      published: a.publishedTime || meta('article:published_time'), lang: a.lang || '', dir: a.dir || ''});
  } catch (e) { return null; }
})();"""

    /** evaluateJavascript's result: a JSON string (quoted) holding our JSON, or "null". */
    fun parse(raw: String?): ReaderActivity.Article? = runCatching {
        if (raw == null || raw == "null") return null
        val o = JSONObject(JSONTokener(raw).nextValue() as String)
        ReaderActivity.Article(o.optString("title"), o.optString("byline"), o.optString("site"), o.optString("content"),
            o.optInt("length"), o.optString("image"), o.optString("published"), o.optString("lang"), o.optString("dir"))
    }.getOrNull()

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** "18 November 2026" from an ISO date, or "" if it isn't one. */
    fun date(iso: String): String = runCatching {
        val t = java.time.OffsetDateTime.parse(iso)
        t.format(java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy", java.util.Locale.getDefault()))
    }.recoverCatching { java.time.LocalDate.parse(iso.take(10)).format(java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy")) }
        .getOrDefault("")

    fun page(a: ReaderActivity.Article, size: Int, c: Colors, site: String, original: String = "", originalLabel: String = ""): String {
        // The page's picture on top, unless the article already starts with it.
        val name = a.image.substringAfterLast('/').substringBefore('?').take(40)
        val hero = if (a.image.startsWith("http") && (name.isEmpty() || !a.content.contains(name)))
            "<img class=\"hero\" src=\"${esc(a.image)}\" alt=\"\">" else ""
        val meta = listOf(a.byline.trim(), date(a.published)).filter { it.isNotEmpty() }.joinToString(" · ")
        val dir = if (a.dir == "rtl") " dir=\"rtl\"" else ""
        return """<!doctype html><html lang="${esc(a.lang)}"$dir><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="script-src 'none'; object-src 'none'; frame-src 'none'">
<style>
html { -webkit-text-size-adjust: 100%; }
body { margin: 0; padding: 8px 20px 48px; background: ${c.background}; color: ${c.text};
  font: ${size}px/1.6 "Noto Serif", Georgia, serif; word-wrap: break-word; }
main { max-width: 40em; margin: 0 auto; }
.site { font: 600 13px/1.4 sans-serif; letter-spacing: .06em; text-transform: uppercase; color: ${c.accent}; margin: 12px 0 6px; }
h1 { font: 700 1.55em/1.25 sans-serif; margin: 0 0 10px; }
.meta { font: 14px/1.4 sans-serif; color: ${c.secondary}; margin-bottom: 18px; }
img, video, picture, svg { max-width: 100%; height: auto; }
.hero { display: block; width: calc(100% + 40px); max-width: none; margin: 0 -20px 18px; }
figure { margin: 1.2em 0; }
figcaption, .caption { font: 13px/1.4 sans-serif; color: ${c.secondary}; margin-top: 6px; }
a { color: ${c.accent}; text-decoration: none; }
h2, h3, h4 { font-family: sans-serif; line-height: 1.3; margin: 1.4em 0 .5em; }
blockquote { margin: 1.2em 0; padding: 0 0 0 16px; border-left: 3px solid ${c.accent}; color: ${c.secondary}; }
pre, code { font-family: monospace; font-size: .85em; }
pre { overflow-x: auto; padding: 12px; border: 1px solid ${c.divider}; border-radius: 8px; }
table { display: block; overflow-x: auto; border-collapse: collapse; }
td, th { border: 1px solid ${c.divider}; padding: 4px 8px; }
hr { border: 0; border-top: 1px solid ${c.divider}; margin: 2em 0; }
iframe, form, button, input { display: none; }
.original { margin: 2.5em 0 0; padding-top: 1.2em; border-top: 1px solid ${c.divider}; font: 600 15px/1.4 sans-serif; }
</style></head><body><main>
$hero<div class="site">${esc(site)}</div>
<h1>${esc(a.title)}</h1>
${if (meta.isNotEmpty()) "<div class=\"meta\">${esc(meta)}</div>" else ""}
${a.content}
${if (original.startsWith("http")) "<p class=\"original\"><a href=\"${esc(original)}\">${esc(originalLabel)} ↗</a></p>" else ""}
</main></body></html>"""
    }
}
