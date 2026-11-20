package com.bompotis.etesian.glance

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import com.bompotis.etesian.glance.search.Kind
import com.bompotis.etesian.glance.search.Result
import com.bompotis.etesian.glance.search.Searcher
import com.bompotis.etesian.glance.search.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** 2026-11-20 (user): "Show the phone results as they load instead of waiting for everything to be ready". */
class ProgressiveSearchTest {
    private val ctx: Context = object : ContextWrapper(null) { override fun getApplicationContext() = this }

    private class Fake(override val kind: Kind, val ms: Long) : Source {
        override fun available(ctx: Context) = true
        override fun search(ctx: Context, query: String, limit: Int): List<Result> {
            Thread.sleep(ms)
            return listOf(Result(kind, "$kind $query", score = 50, open = Intent()))
        }
    }

    @Test fun phoneResultsShowBeforeASlowWebAnswers() {
        val seen = CopyOnWriteArrayList<Pair<Long, List<Kind>>>()
        val t0 = System.nanoTime()
        val last = Searcher.search(ctx, "q", listOf(Fake(Kind.APP, 10), Fake(Kind.CONTACT, 30), Fake(Kind.WEB, 900))) {
            seen += (System.nanoTime() - t0) / 1_000_000 to it.map { s -> s.kind }
        }
        assertEquals(listOf(Kind.APP), seen[0].second)
        assertTrue("apps shown after ${seen[0].first}ms", seen[0].first < 300)
        assertEquals(listOf(Kind.APP, Kind.CONTACT), seen[1].second)
        assertEquals(listOf(Kind.APP, Kind.CONTACT, Kind.WEB), last.map { it.kind })
    }

    @Test fun aSearchNoLongerWantedStopsAtOnce() {
        val wanted = java.util.concurrent.atomic.AtomicBoolean(true)
        val t0 = System.nanoTime()
        Thread { Thread.sleep(50); wanted.set(false) }.start()
        Searcher.search(ctx, "q", listOf(Fake(Kind.APP, 10), Fake(Kind.WEB, 1200)), stale = { !wanted.get() })
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("stopped after ${ms}ms", ms < 400)
    }

    @Test fun aSlowConnectionDoesNotQueueThePhonesSearches() {
        // A burst of typing on a bad connection: six web requests still running from older searches.
        repeat(6) { Thread { Searcher.search(ctx, "old$it", listOf(Fake(Kind.WEB, 1400))) }.start() }
        Thread.sleep(50)
        val t0 = System.nanoTime()
        var first = -1L
        Searcher.search(ctx, "new", listOf(Fake(Kind.APP, 10), Fake(Kind.WEB, 50))) {
            if (first < 0) first = (System.nanoTime() - t0) / 1_000_000
        }
        assertTrue("apps shown after ${first}ms", first in 0..300)
    }
}
