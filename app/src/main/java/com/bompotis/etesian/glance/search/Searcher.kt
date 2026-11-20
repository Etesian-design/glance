package com.bompotis.etesian.glance.search

import android.content.Context
import android.util.Log
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Searches everywhere at once and puts the answers in sections: applications first (what a launcher search is
 * mostly for), then the other sections by how well their best result matches, the web last. Each source's answers
 * show as soon as it has them ([search]'s onUpdate), so a slow one (the web, a busy phone's messages) never holds up
 * the rest; one that takes longer than [DEADLINE_MS] is given up on. A search stops as soon as it is no longer wanted (the next letter typed).
 */
object Searcher {
    private const val TAG = "GlanceSearch"
    /** Nothing waits for a slow source any more (each shows as it answers); past this it is given up on. */
    private const val DEADLINE_MS = 8000L
    const val PER_SECTION = 4

    class Section(val kind: Kind, val title: String, val results: List<Result>)

    private val pool = Executors.newFixedThreadPool(6)
    /** The network's own threads: a slow connection never queues the phone's searches behind its requests. */
    private val netPool = Executors.newFixedThreadPool(3)

    val all: List<Source> = listOf(AppsSource, ShortcutsSource, ContactsSource, SmsSource, NotificationsSource,
        SettingsSource, FilesSource, NewsSource, WebSource)

    /**
     * The sections for [query]. [onUpdate] gets them again each time another source answers (on this thread);
     * [stale] says the search is no longer wanted, and it then stops at once (what it had is returned).
     */
    fun search(ctx: Context, query: String, sources: List<Source> = all, perSection: Int = PER_SECTION,
               stale: () -> Boolean = { false }, onUpdate: (List<Section>) -> Unit = {}): List<Section> {
        if (query.isBlank()) return emptyList()
        val app = ctx.applicationContext
        val usable = sources.filter { s -> runCatching { s.available(app) }.getOrDefault(false) }
        // Each source's answer joins this queue as it finishes, in whatever order they come.
        val results = LinkedBlockingQueue<Future<Pair<Source, List<Result>>>>()
        val futures = usable.map { s ->
            object : FutureTask<Pair<Source, List<Result>>>(Callable {
                // Queued behind an older search's work and no longer wanted: nothing to do.
                s to (if (stale()) emptyList() else s.search(app, query, perSection * 2))
            }) {
                override fun done() { results.add(this) }
            }.also { (if (s.kind == Kind.WEB) netPool else pool).execute(it) }
        }
        val start = System.nanoTime()
        val deadline = start + TimeUnit.MILLISECONDS.toNanos(DEADLINE_MS)
        // In the sources' order, whatever order they answer in (the same results always arrange the same way).
        val got = HashMap<Source, List<Result>>()
        var sections = emptyList<Section>()
        var left = futures.size
        while (left > 0) {
            if (stale()) break
            val wait = minOf(deadline - System.nanoTime(), TimeUnit.MILLISECONDS.toNanos(POLL_MS))
            if (wait <= 0) break
            val f = results.poll(wait, TimeUnit.NANOSECONDS) ?: continue
            left--
            val (s, rs) = runCatching { f.get() }.onFailure { Log.d(TAG, "a source failed: $it") }.getOrNull() ?: continue
            got[s] = rs
            sections = arrange(usable.flatMap { got[it].orEmpty() }, perSection)
            if (!stale()) onUpdate(sections)
            Log.d(TAG, "${s.kind}: ${rs.size} after ${(System.nanoTime() - start) / 1_000_000}ms")
        }
        futures.forEach { if (!it.isDone) it.cancel(true) }
        if (left > 0 && !stale()) Log.d(TAG, "$left source(s) left out after ${DEADLINE_MS}ms")
        return sections
    }

    /** How often a waiting search looks whether it is still wanted. */
    private const val POLL_MS = 40L

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
