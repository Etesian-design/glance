package com.bompotis.etesian.glance

import android.content.Intent
import com.bompotis.etesian.glance.search.Kind
import com.bompotis.etesian.glance.search.Result
import com.bompotis.etesian.glance.search.Searcher
import com.bompotis.etesian.glance.search.Suggest
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchTest {
    private val i = Intent()
    private fun r(kind: Kind, title: String, score: Int, key: String = "$kind:$title") = Result(kind, title, score = score, open = i, key = key)

    @Test fun appsFirstWebLastOthersByBestMatch() {
        val s = Searcher.arrange(listOf(
            r(Kind.WEB, "Search the web", 1),
            r(Kind.FILE, "maria.pdf", 45),
            r(Kind.CONTACT, "Maria", 90),
            r(Kind.APP, "Maps", 20),
        ))
        assertEquals(listOf(Kind.APP, Kind.CONTACT, Kind.FILE, Kind.WEB), s.map { it.kind })
    }

    @Test fun duplicatesDroppedSectionsCapped() {
        val s = Searcher.arrange((1..9).map { r(Kind.CONTACT, "C$it", it) } +
            listOf(r(Kind.MESSAGE, "Maria: hi", 45, key = "msg:a"), r(Kind.MESSAGE, "Maria: hi (notif)", 70, key = "msg:a")), 4)
        assertEquals(4, s.first { it.kind == Kind.CONTACT }.results.size)
        assertEquals("C9", s.first { it.kind == Kind.CONTACT }.results.first().title)
        assertEquals(1, s.first { it.kind == Kind.MESSAGE }.results.size)
    }

    @Test fun suggestionsWithoutTheQuery() {
        assertEquals(listOf("weather athens", "weather london"),
            Suggest.parse("""["weather",["weather","weather athens","weather london"]]""", "weather"))
    }
}

class HeadlineTest {
    @Test fun headlinesMatchWordStartsOnly() {
        val m = com.bompotis.etesian.glance.search.Match
        assertEquals(m.CONTAINS, com.bompotis.etesian.glance.search.headline("elect", "Election results tonight"))
        assertEquals(m.CONTAINS, com.bompotis.etesian.glance.search.headline("res ton", "Election results tonight"))
        assertEquals(m.NONE, com.bompotis.etesian.glance.search.headline("set", "Anthropic upsets markets"))
        assertEquals(m.NONE, com.bompotis.etesian.glance.search.headline("el", "Election"))
    }
}
