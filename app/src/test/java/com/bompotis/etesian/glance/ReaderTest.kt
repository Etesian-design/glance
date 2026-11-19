package com.bompotis.etesian.glance

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderTest {
    private val colors = Reader.Colors("#000000", "#F2F2F2", "#9A9A9A", "#00A5E5", "#333333")

    @Test fun parsesWhatThePageReturns() {
        val inner = JSONObject().put("title", "A \"quoted\" title").put("content", "<p>Hi</p>").put("length", 1234)
            .put("image", "https://e.com/pic.jpg").toString()
        val raw = JSONObject.quote(inner) // evaluateJavascript gives the string JSON-encoded
        val a = Reader.parse(raw)!!
        assertEquals("A \"quoted\" title", a.title); assertEquals(1234, a.length); assertEquals("<p>Hi</p>", a.content)
        assertNull(Reader.parse("null")); assertNull(Reader.parse(null)); assertNull(Reader.parse("garbage"))
    }

    @Test fun pageHasNoScriptsAndEscapesTheTitle() {
        val a = ReaderActivity.Article("<b>T</b>", "By Ann", "BBC", "<p>Body</p>", 900, "https://e.com/hero.jpg",
            "2026-11-18T10:00:00Z", "en", "")
        val html = Reader.page(a, 19, colors, "BBC")
        assertTrue(html.contains("script-src 'none'"))
        assertTrue(html.contains("&lt;b&gt;T&lt;/b&gt;"))
        assertTrue("hero picture", html.contains("class=\"hero\" src=\"https://e.com/hero.jpg\""))
        assertTrue(html.contains("By Ann · 18 November 2026"))
        assertTrue(html.contains("font: 19px/1.6"))
        val linked = Reader.page(a, 19, colors, "BBC", "https://bbc.co.uk/a", "Read the original on bbc.co.uk")
        assertTrue("the original at the end", linked.contains("<a href=\"https://bbc.co.uk/a\">Read the original on bbc.co.uk ↗</a>"))
    }

    @Test fun noSecondHeroWhenTheArticleHasIt() {
        val a = ReaderActivity.Article("T", "", "", "<img src=\"https://e.com/x/hero.jpg?w=800\"><p>Body</p>", 900,
            "https://e.com/x/hero.jpg", "", "", "")
        assertFalse(Reader.page(a, 19, colors, "").contains("class=\"hero\""))
    }

    @Test fun readabilityIsBundled() {
        val f = java.io.File("src/main/assets/readability/Readability.js")
        assertTrue(f.readText().contains("function Readability("))
    }
}
