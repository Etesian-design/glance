package com.bompotis.etesian.glance.search

import android.content.Context
import android.util.Log
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Searches everywhere at once and puts the answers in sections: applications first (what a launcher search is
 * mostly for), then the other sections by how well their best result matches, the web last. A source that takes
 * longer than [DEADLINE_MS] is left out of this answer rather than holding up the rest.
 */
object Searcher {
    private const val TAG = "GlanceSearch"
    private const val DEADLINE_MS = 1500L
    const val PER_SECTION = 4

    class Section(val kind: Kind, val title: String, val results: List<Result>)

    private val pool = Executors.newFixedThreadPool(6)

    val all: List<Source> = listOf(AppsSource, ShortcutsSource, ContactsSource, SmsSource, NotificationsSource,
        SettingsSource, FilesSource, NewsSource, WebSource)

    fun search(ctx: Context, query: String, sources: List<Source> = all, perSection: Int = PER_SECTION): List<Section> {
        if (query.isBlank()) return emptyList()
        val app = ctx.applicationContext
        val usable = sources.filter { s -> runCatching { s.available(app) }.getOrDefault(false) }
        val futures = usable.map { s -> s to pool.submit(Callable { s.search(app, query, perSection * 2) }) }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DEADLINE_MS)
        val results = futures.flatMap { (s, f) ->
            runCatching { f.get(maxOf(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS) }
                .onFailure { f.cancel(true); Log.d(TAG, "${s.kind} left out: $it") }.getOrDefault(emptyList())
        }
        return arrange(results, perSection)
    }

    /** Sections from loose results: duplicates dropped (the first kept), each section's best first. */
    fun arrange(results: List<Result>, perSection: Int = PER_SECTION): List<Section> {
        val seen = HashSet<String>()
        val unique = results.filter { seen.add(it.key) }
        return unique.groupBy { it.kind }
            .map { (kind, rs) ->
                Section(kind, kind.section,
                    rs.sortedWith(compareByDescending<Result> { it.score }.thenByDescending { it.time }).take(perSection))
            }
            .sortedWith(compareBy<Section> { if (it.kind == Kind.APP) 0 else if (it.kind == Kind.WEB) 2 else 1 }
                .thenByDescending { it.results.first().score }
                .thenBy { it.kind.ordinal })
    }
}
