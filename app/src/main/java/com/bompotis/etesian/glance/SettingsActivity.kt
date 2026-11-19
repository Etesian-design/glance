package com.bompotis.etesian.glance

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.bompotis.etesian.glance.news.FeedsClient
import com.bompotis.etesian.glance.news.GoogleNews
import com.bompotis.etesian.glance.news.NewsStore
import com.bompotis.etesian.glance.notifications.NotificationHistory
import com.bompotis.etesian.glance.search.Kind
import com.bompotis.etesian.glance.search.ShortcutsSource

/** What search looks through, and where the news comes from. */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.settings)
        if (savedInstanceState == null)
            supportFragmentManager.beginTransaction().replace(android.R.id.content, Screen()).commit()
        // Back on the bottom bar, as everywhere in Etesian (no Up arrow on top).
        Ui.toolbar(this, null, back = true, actions = emptyList()) {}
    }

    class Screen : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.sharedPreferencesName = "glance"
            val ctx = preferenceManager.context
            val screen = preferenceManager.createPreferenceScreen(ctx)
            preferenceScreen = screen

            val search = PreferenceCategory(ctx).apply { title = getString(R.string.search) }
            screen.addPreference(search)
            Setup.Need.entries.forEach { n ->
                search.addPreference(SwitchPreferenceCompat(ctx).apply {
                    key = Prefs.sourceKey(n.kind)
                    title = getString(n.label)
                    setDefaultValue(true)
                    setOnPreferenceChangeListener { _, on ->
                        if (on == true && !Setup.allowed(requireContext(), n)) Setup.request(requireActivity(), n)
                        true
                    }
                })
            }
            search.addPreference(SwitchPreferenceCompat(ctx).apply {
                key = Prefs.sourceKey(Kind.SETTING); title = getString(R.string.source_settings); setDefaultValue(true)
            })
            search.addPreference(SwitchPreferenceCompat(ctx).apply {
                key = Prefs.sourceKey(Kind.NEWS); title = getString(R.string.source_news); setDefaultValue(true)
            })
            search.addPreference(Preference(ctx).apply {
                key = "shortcuts_state"
                title = getString(R.string.source_shortcuts)
                isSelectable = false
            })
            search.addPreference(SwitchPreferenceCompat(ctx).apply {
                key = Prefs.WEB_SUGGESTIONS
                title = getString(R.string.web_suggestions)
                summary = getString(R.string.web_suggestions_summary)
                setDefaultValue(false)
            })
            search.addPreference(ListPreference(ctx).apply {
                key = Prefs.SEARCH_ENGINE
                title = getString(R.string.search_engine)
                entries = arrayOf("DuckDuckGo", "Startpage", "Brave Search", "Google", "Bing")
                entryValues = arrayOf(Prefs.DUCKDUCKGO, "https://www.startpage.com/do/search?q=%s",
                    "https://search.brave.com/search?q=%s", "https://www.google.com/search?q=%s", "https://www.bing.com/search?q=%s")
                setDefaultValue(Prefs.DUCKDUCKGO)
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            })
            search.addPreference(Preference(ctx).apply {
                title = getString(R.string.clear_notification_history)
                summary = getString(R.string.clear_notification_history_summary, NotificationHistory.KEEP_DAYS)
                setOnPreferenceClickListener { NotificationHistory.clear(requireContext()); true }
            })

            val news = PreferenceCategory(ctx).apply { title = getString(R.string.news) }
            screen.addPreference(news)
            news.addPreference(SwitchPreferenceCompat(ctx).apply {
                key = Prefs.READER
                title = getString(R.string.reader)
                summary = getString(R.string.reader_summary)
                setDefaultValue(true)
            })
            news.addPreference(SwitchPreferenceCompat(ctx).apply {
                key = Prefs.GOOGLE_NEWS
                title = getString(R.string.google_news)
                summary = getString(R.string.google_news_summary)
                setDefaultValue(true)
            })
            news.addPreference(MultiSelectListPreference(ctx).apply {
                key = Prefs.TOPICS
                title = getString(R.string.topics)
                val labels = resources.getStringArray(R.array.topic_labels)
                entries = labels
                entryValues = GoogleNews.TOPICS.toTypedArray()
                setDefaultValue(Prefs.DEFAULT_TOPICS)
                summaryProvider = Preference.SummaryProvider<MultiSelectListPreference> { p ->
                    GoogleNews.TOPICS.indices.filter { GoogleNews.TOPICS[it] in p.values }.joinToString(", ") { labels[it] }
                }
            })
            news.addPreference(ListPreference(ctx).apply {
                key = Prefs.EDITION
                title = getString(R.string.edition)
                entries = resources.getStringArray(R.array.edition_labels)
                entryValues = resources.getStringArray(R.array.edition_values)
                setDefaultValue("")
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            })
            news.addPreference(SwitchPreferenceCompat(ctx).apply {
                key = Prefs.FEEDS
                title = getString(R.string.feeds)
                summary = getString(if (FeedsClient.available(ctx)) R.string.feeds_summary else R.string.feeds_missing)
                isEnabled = FeedsClient.available(ctx)
                setDefaultValue(true)
            })
            news.addPreference(Preference(ctx).apply {
                title = getString(R.string.reset_interests)
                summary = getString(R.string.reset_interests_summary)
                setOnPreferenceClickListener { NewsStore.resetInterests(requireContext()); true }
            })

            listOf(Prefs.GOOGLE_NEWS, Prefs.TOPICS, Prefs.EDITION, Prefs.FEEDS).forEach { k ->
                findPreference<Preference>(k)?.setOnPreferenceChangeListener { _, _ ->
                    view?.post { NewsStore.settingsChanged(requireContext()) }
                    true
                }
            }
        }

        override fun onResume() {
            super.onResume()
            val ctx = requireContext()
            // Allowed or not, as it is now (the user may come back from a system page).
            Setup.Need.entries.forEach { n ->
                findPreference<SwitchPreferenceCompat>(Prefs.sourceKey(n.kind))?.summary =
                    if (Setup.allowed(ctx, n)) null else getString(R.string.not_allowed)
            }
            findPreference<Preference>("shortcuts_state")?.summary =
                getString(if (ShortcutsSource.available(ctx)) R.string.shortcuts_on else R.string.shortcuts_off)
        }
    }
}
