package com.bompotis.etesian.glance

import android.app.AlertDialog
import android.os.Bundle
import android.text.format.Formatter
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.bompotis.etesian.glance.news.Net
import com.bompotis.etesian.glance.news.NewsStore
import com.bompotis.etesian.glance.news.Offline
import com.bompotis.etesian.glance.news.Opml
import com.bompotis.etesian.glance.news.Subscription
import com.bompotis.etesian.glance.news.Subscriptions
import java.util.concurrent.Executors

/** Feeds: Glance's own subscriptions (each can be kept for offline reading), keywords, Yahoo, OPML. */
class FeedsSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.feeds_page)
        if (savedInstanceState == null)
            supportFragmentManager.beginTransaction().replace(android.R.id.content, Screen()).commit()
        Ui.toolbar(this, null, back = true, actions = emptyList()) {}
    }

    class Screen : PreferenceFragmentCompat() {
        private val bg = Executors.newSingleThreadExecutor()
        private var dirty = false

        private val import = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val ctx = context?.applicationContext ?: return@registerForActivityResult
            if (uri == null) return@registerForActivityResult
            bg.execute {
                val n = runCatching {
                    val subs = ctx.contentResolver.openInputStream(uri)!!.reader().use { Opml.read(it) }
                    val have = Subscriptions.all(ctx).map { it.url }.toSet()
                    subs.filter { it.url !in have }.onEach { Subscriptions.put(ctx, it) }.size
                }.getOrDefault(-1)
                activity?.runOnUiThread {
                    toast(if (n < 0) getString(R.string.opml_failed) else getString(R.string.opml_imported, n))
                    if (n > 0) { dirty = true; build() }
                }
            }
        }

        private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("text/x-opml")) { uri ->
            val ctx = context?.applicationContext ?: return@registerForActivityResult
            if (uri == null) return@registerForActivityResult
            val ok = runCatching {
                ctx.contentResolver.openOutputStream(uri, "wt")!!.writer().use { it.write(Opml.write(Subscriptions.all(ctx))) }
            }.isSuccess
            toast(getString(if (ok) R.string.opml_exported else R.string.opml_failed))
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.sharedPreferencesName = "glance"
            build()
        }

        private fun toast(s: String) = context?.let { Toast.makeText(it, s, Toast.LENGTH_SHORT).show() }

        private fun build() {
            val ctx = preferenceManager.context
            val screen = preferenceManager.createPreferenceScreen(ctx)
            preferenceScreen = screen

            // Subscriptions
            val subs = PreferenceCategory(ctx).apply { title = getString(R.string.subscriptions) }
            screen.addPreference(subs)
            Subscriptions.all(ctx).forEach { s ->
                subs.addPreference(Preference(ctx).apply {
                    title = s.title.ifBlank { s.url }
                    summary = if (s.offline) getString(R.string.offline_on, s.url) else s.url
                    setOnPreferenceClickListener { edit(s); true }
                })
            }
            subs.addPreference(Preference(ctx).apply {
                key = "add_feed"; title = getString(R.string.add_feed); summary = getString(R.string.add_feed_summary)
                setOnPreferenceClickListener { addFeed(); true }
            })
            subs.addPreference(Preference(ctx).apply {
                title = getString(R.string.opml_import)
                setOnPreferenceClickListener { import.launch(arrayOf("text/x-opml", "text/xml", "application/xml", "application/octet-stream", "*/*")); true }
            })
            subs.addPreference(Preference(ctx).apply {
                title = getString(R.string.opml_export)
                setOnPreferenceClickListener { export.launch("glance-subscriptions.opml"); true }
            })

            // Keywords
            val kw = PreferenceCategory(ctx).apply { title = getString(R.string.keywords); summary = getString(R.string.keywords_summary) }
            screen.addPreference(kw)
            Prefs.keywords(ctx).forEach { k ->
                kw.addPreference(Preference(ctx).apply {
                    title = k
                    setOnPreferenceClickListener {
                        AlertDialog.Builder(requireContext()).setTitle(k)
                            .setPositiveButton(R.string.remove) { _, _ ->
                                Prefs.setKeywords(requireContext(), Prefs.keywords(requireContext()) - k); dirty = true; build()
                            }.setNegativeButton(android.R.string.cancel, null).show()
                        true
                    }
                })
            }
            kw.addPreference(Preference(ctx).apply {
                key = "add_keyword"; title = getString(R.string.add_keyword)
                setOnPreferenceClickListener {
                    ask(getString(R.string.add_keyword), getString(R.string.keyword_hint)) { text ->
                        Prefs.setKeywords(requireContext(), Prefs.keywords(requireContext()) + text); dirty = true; build()
                    }
                    true
                }
            })

            // Sources: Yahoo next to Google (Google's own switches are on the main page). Not saved by the switch:
            // its state comes from Prefs, which knows the default.
            val sources = PreferenceCategory(ctx).apply { title = getString(R.string.more_sources) }
            screen.addPreference(sources)
            sources.addPreference(SwitchPreferenceCompat(ctx).apply {
                key = Prefs.YAHOO; isPersistent = false; title = getString(R.string.yahoo); summary = getString(R.string.yahoo_summary)
                isChecked = Prefs.yahoo(ctx)
                setOnPreferenceChangeListener { _, on -> Prefs.setYahoo(requireContext(), on == true); dirty = true; true }
            })
            sources.addPreference(SwitchPreferenceCompat(ctx).apply {
                key = Prefs.YAHOO_OFFLINE; isPersistent = false; title = getString(R.string.yahoo_offline)
                summary = getString(R.string.offline_summary)
                isChecked = Prefs.yahooOffline(ctx)
                setOnPreferenceChangeListener { _, on -> Prefs.setYahooOffline(requireContext(), on == true); true }
            })

            // Offline reading
            val off = PreferenceCategory(ctx).apply { title = getString(R.string.offline_reading) }
            screen.addPreference(off)
            val store = Offline.store(ctx)
            off.addPreference(Preference(ctx).apply {
                key = "offline_delete"
                title = getString(R.string.offline_delete)
                summary = getString(R.string.offline_state, store.count(), Formatter.formatShortFileSize(ctx, store.bytes()),
                    (com.bompotis.etesian.glance.news.OfflineStore.MAX_AGE_MS / 86_400_000L).toInt(),
                    Formatter.formatShortFileSize(ctx, com.bompotis.etesian.glance.news.OfflineStore.MAX_BYTES))
                setOnPreferenceClickListener { store.clear(); build(); true }
            })
        }

        private fun edit(s: Subscription) {
            val ctx = requireContext()
            var offline = s.offline
            AlertDialog.Builder(ctx).setTitle(s.title.ifBlank { s.url })
                .setMultiChoiceItems(arrayOf(getString(R.string.keep_offline)), booleanArrayOf(offline)) { _, _, on -> offline = on }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    if (offline != s.offline) { Subscriptions.put(ctx, s.copy(offline = offline)); build() }
                }
                .setNeutralButton(R.string.remove) { _, _ -> Subscriptions.remove(ctx, s.url); dirty = true; build() }
                .setNegativeButton(android.R.string.cancel, null).show()
        }

        private fun addFeed() = ask(getString(R.string.add_feed), getString(R.string.feed_hint)) { text ->
            val ctx = requireContext().applicationContext
            toast(getString(R.string.feed_looking))
            bg.execute {
                val r = runCatching { Net.discover(text) }
                activity?.runOnUiThread {
                    r.onSuccess { (title, url) ->
                        Subscriptions.put(ctx, Subscription(url, title.ifBlank { android.net.Uri.parse(url).host.orEmpty() }))
                        toast(getString(R.string.feed_added, title.ifBlank { url })); dirty = true; build()
                    }.onFailure { toast(getString(R.string.feed_not_found)) }
                }
            }
        }

        private fun ask(title: String, hint: String, then: (String) -> Unit) {
            val ctx = requireContext()
            val input = EditText(ctx).apply { this.hint = hint; isSingleLine = true; contentDescription = "input" }
            val pad = (20 * resources.displayMetrics.density).toInt()
            val box = android.widget.FrameLayout(ctx).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
            AlertDialog.Builder(ctx).setTitle(title).setView(box)
                .setPositiveButton(android.R.string.ok) { _, _ -> input.text.toString().trim().takeIf { it.isNotEmpty() }?.let(then) }
                .setNegativeButton(android.R.string.cancel, null).show()
        }

        override fun onPause() {
            super.onPause()
            // What to fetch changed: fetch again once, on the way out.
            if (dirty) { dirty = false; NewsStore.settingsChanged(requireContext()) }
        }
    }
}
