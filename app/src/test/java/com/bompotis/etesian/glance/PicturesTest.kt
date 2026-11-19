package com.bompotis.etesian.glance

import com.bompotis.etesian.glance.news.Article
import com.bompotis.etesian.glance.news.GoogleNews
import com.bompotis.etesian.glance.news.Pictures
import com.bompotis.etesian.glance.news.Rss
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PicturesTest {
    private fun feed(name: String) = javaClass.classLoader!!.getResourceAsStream(name)!!.reader().use { Rss.parse(it) }
    private fun donors(vararg names: String) = names.flatMap { n -> feed(n).map { Article(it.title, it.source, it.link, it.time, image = it.image) } }

    @Test fun publisherFeedsHaveLargePictures() {
        val guardian = feed("pic_guardian_world.xml")
        assertTrue(guardian.all { it.image.isNotEmpty() })
        assertTrue("the largest size listed", guardian.first().image.contains("width=700"))
        assertTrue("BBC asked for 800 px", feed("pic_bbc.xml").all { "/standard/800/" in it.image || "/ic/800x450/" in it.image })
    }

    @Test fun googleStoriesBorrowPublisherPictures() {
        val google = GoogleNews.articles(feed("pic_google_top.xml"), "", top = true)
        val lent = Pictures.lend(google, donors("pic_bbc.xml", "pic_guardian_world.xml", "pic_sky.xml"))
        val withPic = lent.filter { it.image.isNotEmpty() }
        withPic.forEach { println("${it.title}  <-  ${it.image.take(70)}") }
        println("${withPic.size} of ${lent.size}")
        // Every Google story whose headline a saved publisher feed has gets that article's picture.
        val d = donors("pic_bbc.xml", "pic_guardian_world.xml", "pic_sky.xml")
        val listed = google.filter { g -> d.any { it.title == g.title } }
        assertTrue(listed.size >= 4)
        listed.forEach { g -> assertEquals(g.title, d.first { it.title == g.title }.image, lent.first { it.link == g.link }.image) }
    }

    @Test fun unrelatedHeadlinesDoNotMatch() {
        val d = listOf(Article("Storm hits coast as thousands lose power", "BBC", "b", 0, image = "https://x/storm.jpg"))
        val a = listOf(Article("Power company profits rise sharply", "FT", "a", 0))
        assertEquals("", Pictures.lend(a, d).single().image)
        val same = listOf(Article("Thousands lose power as storm hits the coast", "Sky", "c", 0))
        assertEquals("https://x/storm.jpg", Pictures.lend(same, d).single().image)
    }

    @Test fun pagePicture() {
        assertEquals("https://e.com/a.jpg", Pictures.meta("<head><meta property=\"og:image\" content=\"https://e.com/a.jpg\"></head>"))
        assertEquals("https://e.com/b.jpg", Pictures.meta("<meta content='/b.jpg' name='twitter:image'>", "https://e.com/"))
        assertEquals("", Pictures.meta("<p>none</p>"))
    }
}
