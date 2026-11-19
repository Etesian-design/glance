package com.bompotis.etesian.glance

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.bompotis.etesian.glance.news.NewsStore
import com.bompotis.etesian.glance.news.Section
import com.bompotis.etesian.glance.news.Story
import com.bompotis.etesian.glance.search.Open
import com.bompotis.etesian.glance.search.Searcher
import java.util.concurrent.Executors

/**
 * Glance's one screen, like Google News: the search bar on top (search everything on the phone, like Sesame),
 * the news under it (Top stories, then For you). Typing replaces the news with results; clearing brings it back.
 */
class MainActivity : Activity() {

    companion object {
        /** Opened from a search key or the launcher's search: the keyboard comes up at once. */
        const val EXTRA_FOCUS_SEARCH = "focus_search"
    }

    private lateinit var field: EditText
    private lateinit var action: ImageButton
    private lateinit var refresh: SwipeRefreshLayout
    private val adapter by lazy { Rows(this, ::onStoryMenu) }
    private val main = Handler(Looper.getMainLooper())
    private val searcher = Executors.newSingleThreadExecutor()
    @Volatile private var searchSeq = 0
    private var query = ""
    private val newsChanged: () -> Unit = { main.post { if (query.isEmpty()) showNews() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NewsJob.schedule(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.color(this@MainActivity, Ui.BACKGROUND))
        }
        root.addView(searchBar(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
            clipToPadding = false
            // Scrolling the results hides the keyboard, as in Sesame.
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(rv: RecyclerView, state: Int) {
                    if (state == RecyclerView.SCROLL_STATE_DRAGGING) hideKeyboard()
                }
            })
        }
        refresh = SwipeRefreshLayout(this).apply {
            addView(list)
            setOnRefreshListener { NewsStore.refresh(this@MainActivity, force = true) }
        }
        root.addView(refresh, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        // The bottom bar, as everywhere in Etesian: Refresh and Settings (no Back on the main screen).
        val ownBar = Ui.toolbar(this, root, back = false, actions = listOf(
            Ui.Action("refresh", R.drawable.tb_refresh, getString(R.string.refresh)),
            Ui.Action("settings", R.drawable.tb_settings, getString(R.string.settings)))) { id ->
            when (id) {
                "refresh" -> { refresh.isRefreshing = true; NewsStore.refresh(this, force = true) }
                "settings" -> startActivity(Intent(this, SettingsActivity::class.java))
            }
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            if (ownBar == null) { v.setPadding(bars.left, bars.top, bars.right, 0); list.setPadding(0, 0, 0, bars.bottom) }
            else { v.setPadding(bars.left, bars.top, bars.right, bars.bottom); list.setPadding(0, 0, 0, 0) }
            insets
        }
        setContentView(root)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        val q = i?.getStringExtra(android.app.SearchManager.QUERY)
        if (q != null) field.setText(q)
        if (i?.getBooleanExtra(EXTRA_FOCUS_SEARCH, false) == true || i?.action == Intent.ACTION_SEARCH ||
            i?.action == Intent.ACTION_ASSIST) showKeyboard()
    }

    private fun searchBar(): View {
        val d = resources.displayMetrics.density
        val bar = FrameLayout(this).apply { setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (8 * d).toInt()) }
        val box = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.pill(this@MainActivity)
            setPadding((14 * d).toInt(), 0, (4 * d).toInt(), 0)
        }
        box.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_search)
            imageTintList = android.content.res.ColorStateList.valueOf(Ui.color(this@MainActivity, Ui.SECONDARY))
        }, LinearLayout.LayoutParams((22 * d).toInt(), (22 * d).toInt()))
        field = EditText(this).apply {
            hint = getString(R.string.search_hint)
            background = null
            isSingleLine = true
            textSize = 17f
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setPadding((10 * d).toInt(), 0, 0, 0)
            contentDescription = getString(R.string.search_hint)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: Editable?) = onQuery(e?.toString().orEmpty())
            })
            // Enter opens the first result, as in Sesame.
            setOnEditorActionListener { _, id, ev ->
                if (id == EditorInfo.IME_ACTION_GO || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { adapter.openFirst(); true } else false
            }
        }
        box.addView(field, LinearLayout.LayoutParams(0, (48 * d).toInt(), 1f))
        action = ImageButton(this).apply {
            background = null
            // Clears the search (Settings and Refresh are on the bottom bar).
            setOnClickListener { field.setText("") }
            imageTintList = android.content.res.ColorStateList.valueOf(Ui.color(this@MainActivity, Ui.SECONDARY))
        }
        box.addView(action, LinearLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt()))
        bar.addView(box)
        updateAction()
        return bar
    }

    private fun updateAction() {
        action.setImageResource(R.drawable.ic_clear)
        action.contentDescription = getString(R.string.clear)
        action.visibility = if (query.isEmpty()) View.INVISIBLE else View.VISIBLE
    }

    private fun onQuery(q: String) {
        query = q.trim()
        updateAction()
        refresh.isEnabled = query.isEmpty()
        val seq = ++searchSeq
        if (query.isEmpty()) { showNews(); return }
        main.postDelayed({
            if (seq != searchSeq) return@postDelayed
            searcher.execute {
                if (seq != searchSeq) return@execute
                val sections = Searcher.search(this, query)
                main.post { if (seq == searchSeq) adapter.showResults(sections) }
            }
        }, 120)
    }

    private fun showNews() {
        val stories = NewsStore.stories(this)
        refresh.isRefreshing = NewsStore.refreshing && stories.isNotEmpty()
        adapter.showNews(stories, NewsStore.refreshing, NewsStore.lastRefresh(this), Setup.missing(this))
    }

    /** A story's long press: the N9 menu, as in every Etesian app. */
    private fun onStoryMenu(@Suppress("UNUSED_PARAMETER") v: View, s: Story) {
        com.bompotis.etesian.toolbar.EtesianMenu.show(this, R.menu.story, null) { mi ->
            when (mi.itemId) {
                R.id.fewer -> NewsStore.fewerLike(this, s.lead)
                R.id.share -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "${s.lead.title}\n${s.lead.link}"), null))
                R.id.browser -> open(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(s.lead.link)))
            }
            true
        }
    }

    override fun onResume() {
        super.onResume()
        NewsStore.listen(newsChanged)
        NewsStore.refresh(this)
        if (query.isEmpty()) showNews() else onQuery(field.text.toString())
    }

    override fun onPause() {
        super.onPause()
        NewsStore.unlisten(newsChanged)
    }

    @Deprecated("Back clears the search first")
    override fun onBackPressed() {
        if (query.isNotEmpty()) field.setText("") else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun showKeyboard() {
        field.requestFocus()
        field.post { getSystemService(InputMethodManager::class.java).showSoftInput(field, 0) }
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(field.windowToken, 0)
    }

    /** Opens what a row points at, through [Open]. */
    fun open(i: Intent) = runCatching { startActivity(i) }

    fun openStory(s: Story) = open(Open.article(this, s.lead.link))

    fun isTop(s: Story) = s.section == Section.TOP
}
