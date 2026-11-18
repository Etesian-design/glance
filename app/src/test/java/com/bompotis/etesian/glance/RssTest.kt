package com.bompotis.etesian.glance

import com.bompotis.etesian.glance.news.GoogleNews
import com.bompotis.etesian.glance.news.Rss
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RssTest {
    private fun fixture(name: String) = javaClass.classLoader!!.getResourceAsStream(name)!!.reader()

    /** A real Google News topic feed (fetched 2026-11-18). */
    @Test fun googleNewsFeed() {
        val items = Rss.parse(fixture("google_technology.xml"))
        assertEquals(57, items.size)
        val a = GoogleNews.articles(items, "TECHNOLOGY", top = false)
        val first = a.first()
        assertEquals("GTA 6 reviews say highly-anticipated game is a masterpiece", first.title)
        assertEquals("BBC", first.source)
        assertTrue(first.link.startsWith("https://news.google.com/rss/articles/"))
        assertTrue(first.time > 0)
        assertTrue("other outlets on the story", "Financial Times" in first.coverage && "The Guardian" in first.coverage)
        assertFalse("not itself", "BBC" in first.coverage)
        assertTrue(a.all { it.title.isNotBlank() && it.source.isNotBlank() && !it.title.endsWith(" - ${it.source}") })
    }

    @Test fun atomWithMediaPicture() {
        val xml = """<?xml version="1.0"?>
            <feed xmlns="http://www.w3.org/2005/Atom" xmlns:media="http://search.yahoo.com/mrss/">
              <title>Blog</title>
              <entry><title>Hello &amp; welcome</title><link rel="alternate" href="https://b.example/1"/>
                <link rel="enclosure" href="https://b.example/1.mp3"/>
                <updated>2026-11-18T10:00:00Z</updated><summary>Hi</summary>
                <media:thumbnail url="https://b.example/1.jpg"/></entry>
            </feed>"""
        val i = Rss.parse(xml.reader()).single()
        assertEquals("Hello & welcome", i.title)
        assertEquals("https://b.example/1", i.link)
        assertEquals("Blog", i.source)
        assertEquals("https://b.example/1.jpg", i.image)
        assertEquals(1794996000000L, i.time)
    }

    @Test fun rssEnclosurePicture() {
        val xml = """<rss><channel><title>Site</title><item><title>A</title><link>https://s.example/a</link>
            <pubDate>Wed, 18 Nov 2026 12:22:15 GMT</pubDate><enclosure url="https://s.example/a.png" type="image/png"/>
            <description><![CDATA[<p>Text</p>]]></description></item></channel></rss>"""
        val i = Rss.parse(xml.reader()).single()
        assertEquals("https://s.example/a.png", i.image)
        assertEquals("<p>Text</p>", i.description)
        assertTrue(i.time > 0)
    }
}
