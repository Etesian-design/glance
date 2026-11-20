package com.bompotis.etesian.glance

import com.bompotis.etesian.glance.news.Article
import com.bompotis.etesian.glance.news.Discovery
import com.bompotis.etesian.glance.news.GoogleNews
import com.bompotis.etesian.glance.news.Interests
import com.bompotis.etesian.glance.news.OfflineStore
import com.bompotis.etesian.glance.news.Opml
import com.bompotis.etesian.glance.news.Origin
import com.bompotis.etesian.glance.news.Ranker
import com.bompotis.etesian.glance.news.Rss
import com.bompotis.etesian.glance.news.Subscription
import com.bompotis.etesian.glance.news.Subscriptions
import com.bompotis.etesian.glance.news.Yahoo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class FeedsEngineTest {
    private val now = 1_800_000_000_000L
    private val h = 3_600_000L

    @Test fun rssSubscription() {
        val xml = """<?xml version="1.0"?><rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/"><channel>
            <title>BBC News</title><link>https://www.bbc.co.uk/news</link>
            <item><title>Storm hits coast</title><link>https://www.bbc.co.uk/news/1</link>
              <description>&lt;p&gt;Winds of 90mph&lt;/p&gt;</description><pubDate>Fri, 20 Nov 2026 10:00:00 GMT</pubDate>
              <media:thumbnail width="240" url="https://ichef.bbci.co.uk/ace/standard/240/a.jpg"/></item>
            </channel></rss>"""
        val feed = Rss.parseFeed(xml.reader())
        assertEquals("BBC News", feed.title)
        val a = Subscriptions.articles(Subscription("https://feeds.bbci.co.uk/news/rss.xml", ""), feed).single()
        assertEquals("BBC News", a.source); assertEquals(Origin.FEEDS, a.origin)
        assertEquals("https://feeds.bbci.co.uk/news/rss.xml", a.feed)
        assertEquals("Winds of 90mph", a.summary)
        assertEquals("https://ichef.bbci.co.uk/ace/standard/800/a.jpg", a.image)
        assertTrue(a.time > 0)
    }

    @Test fun atomSubscription() {
        val xml = """<?xml version="1.0" encoding="utf-8"?><feed xmlns="http://www.w3.org/2005/Atom">
            <title>Example Blog</title><link href="https://ex.com/"/>
            <entry><title>Hello Atom</title><link rel="replies" href="https://ex.com/1#c"/><link rel="alternate" href="https://ex.com/1"/>
              <updated>2026-11-20T09:00:00Z</updated><summary>First post</summary></entry></feed>"""
        val feed = Rss.parseFeed(xml.reader())
        assertEquals("Example Blog", feed.title)
        val a = Subscriptions.articles(Subscription("https://ex.com/feed", "My blog"), feed).single()
        assertEquals("Hello Atom", a.title); assertEquals("https://ex.com/1", a.link); assertEquals("My blog", a.source)
        assertEquals(1_795_165_200_000L, a.time)
    }

    @Test fun discoversTheFeedOfASite() {
        val html = """<!doctype html><html><head><title>Site</title>
            <link rel="stylesheet" href="/s.css">
            <link rel="alternate" type="application/rss+xml" title="All" href="/feed.xml?a=1&amp;b=2">
            <LINK REL='alternate' TYPE='application/atom+xml' HREF='https://cdn.ex.com/atom'>
            <link rel="alternate" hreflang="el" href="/el/">
            </head><body><a href="/rss">rss</a></body></html>"""
        assertEquals(listOf("https://ex.com/feed.xml?a=1&b=2", "https://cdn.ex.com/atom"), Discovery.feeds(html, "https://ex.com/news/"))
        assertFalse(Discovery.isFeed(html))
        assertTrue(Discovery.isFeed("<?xml version=\"1.0\"?>\n<rss version=\"2.0\">"))
        assertTrue(Discovery.isFeed("  <feed xmlns=\"http://www.w3.org/2005/Atom\">"))
    }

    @Test fun opmlRoundTrip() {
        val subs = listOf(Subscription("https://feeds.bbci.co.uk/news/rss.xml", "BBC & \"News\""), Subscription("https://hnrss.org/frontpage", "HN"))
        val back = Opml.read(Opml.write(subs).reader())
        assertEquals(subs, back)
        // Folders, other readers' attribute spellings, duplicates.
        val other = """<opml version="1.0"><body><outline text="Tech"><outline text="Verge" xmlUrl="https://theverge.com/rss"/>
            <outline text="Verge again" xmlUrl="https://theverge.com/rss"/></outline><outline text="no feed"/></body></opml>"""
        assertEquals(listOf(Subscription("https://theverge.com/rss", "Verge")), Opml.read(other.reader()))
    }

    @Test fun subscriptionsJsonKeepsTheOfflineSwitch() {
        val subs = listOf(Subscription("https://a/feed", "A", offline = true), Subscription("https://b/feed", "B"))
        assertEquals(subs, Subscriptions.fromJson(Subscriptions.toJson(subs)))
    }

    private fun art(title: String, source: String, hoursAgo: Double, summary: String = "", origin: Origin = Origin.GOOGLE) =
        Article(title, source, "https://x/${title.hashCode()}/$source", now - (hoursAgo * h).toLong(), "WORLD", summary,
            image = "https://img/${title.hashCode()}.jpg", origin = origin)

    @Test fun keywordsMatchLikeSearch() {
        assertTrue(Ranker.matchesKeyword(art("Εκλογές στην Ελλάδα", "ERT", 1.0), listOf("εκλογες")))
        assertTrue(Ranker.matchesKeyword(art("Local elections called", "BBC", 1.0), listOf("Election")))
        assertTrue(Ranker.matchesKeyword(art("Vote today", "BBC", 1.0, summary = "The Champions League final"), listOf("champions league")))
        assertFalse(Ranker.matchesKeyword(art("Reelection campaign", "BBC", 1.0), listOf("election")))
        assertFalse(Ranker.matchesKeyword(art("Football results", "BBC", 1.0), listOf("champions league")))
    }

    @Test fun keywordStoriesComeFirstInForYou() {
        val words = listOf("volcano", "budget", "election", "vaccine", "satellite", "football", "drought", "merger")
        val list = words.mapIndexed { i, w -> art("${w.replaceFirstChar { it.uppercase() }} report $i", "S$i", i * 0.5) }
        val plain = Ranker.select(list, Interests(), now).map { it.lead.title }
        assertTrue(plain.indexOf("Merger report 7") > 3)
        val interests = Interests().apply { wanted = listOf("Merger") }
        val boosted = Ranker.select(list, interests, now)
        assertEquals("Merger report 7", boosted.first().lead.title)
        val g = Ranker.cluster(list.take(1)).single()
        assertEquals(Ranker.KEYWORD_BOOST, Ranker.score(g, Interests().apply { wanted = listOf("volcano") }, now) / Ranker.score(g, Interests(), now), 1e-9)
    }

    @Test fun googleAndYahooOnTheSameStoryAreOneStory() {
        val google = GoogleNews.articles(listOf(Rss.Item("Storm Bert brings flooding to Wales and England - BBC",
            "https://news.google.com/rss/articles/abc", now - h, "BBC", "", "")), "", top = true)
        val yahoo = Yahoo.articles(listOf(Rss.Item("Storm Bert brings flooding across Wales and England",
            "https://news.yahoo.com/storm-bert-123.html", now - 2 * h, "Reuters", "<p>Heavy rain</p>", "https://s.yimg.com/a.jpg")))
        assertEquals(Origin.YAHOO, yahoo.single().origin); assertEquals("Reuters", yahoo.single().source)
        val other = art("Chancellor sets out budget plans", "FT", 1.0)
        val stories = Ranker.select(google + yahoo + other, Interests(), now)
        assertEquals(2, stories.size)
        val storm = stories.first { it.lead.title.startsWith("Storm") }
        assertEquals("https://news.google.com/rss/articles/abc", storm.lead.link)
        assertEquals(setOf("BBC", "Reuters"), storm.sources)
    }

    @Test fun keywordSearchFeedKeepsTheEdition() {
        assertEquals("https://news.google.com/rss/search?q=champions+league&hl=el&gl=GR&ceid=GR:el",
            GoogleNews.searchUrl(" champions league ", "hl=el&gl=GR&ceid=GR:el"))
    }

    @Test fun offlineStoreCapsByAgeAndSize() {
        val dir = Files.createTempDirectory("offline").toFile()
        val day = 86_400_000L
        val store = OfflineStore(dir, maxAgeMs = 30 * day, maxBytes = 2500)
        val body = "x".repeat(900)
        store.put("https://a/1", "https://a/1", "{\"content\":\"$body\"}", now - 40 * day)
        store.put("https://a/2", "", "{\"content\":\"$body\"}", now - 3 * day)
        assertFalse("too old", store.has("https://a/1"))
        store.put("https://a/3", "", "{\"content\":\"$body\"}", now - 2 * day)
        store.put("https://a/4", "https://pub/4", "{\"content\":\"$body\"}", now)
        assertFalse("oldest dropped to fit", store.has("https://a/2"))
        assertTrue(store.has("https://a/3")); assertTrue(store.has("https://a/4"))
        assertTrue(store.bytes() <= 2500)
        assertEquals("https://pub/4", store.get("https://a/4")!!.landed)
        assertNull(store.get("https://a/9"))
        store.clear(); assertEquals(0, store.count())
        dir.deleteRecursively()
    }
}
