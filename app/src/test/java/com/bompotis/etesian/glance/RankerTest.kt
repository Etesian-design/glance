package com.bompotis.etesian.glance

import com.bompotis.etesian.glance.news.Article
import com.bompotis.etesian.glance.news.Interests
import com.bompotis.etesian.glance.news.Origin
import com.bompotis.etesian.glance.news.Ranker
import com.bompotis.etesian.glance.news.Section
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RankerTest {
    private val now = 1_800_000_000_000L
    private val h = 3_600_000L
    private fun a(title: String, source: String, hoursAgo: Double, topic: String = "WORLD", top: Boolean = false,
                  origin: Origin = Origin.GOOGLE, coverage: List<String> = emptyList()) =
        Article(title, source, "https://x/${title.hashCode()}/$source", now - (hoursAgo * h).toLong(), topic,
            origin = origin, top = top, coverage = coverage)

    /** Headlines with nothing in common, as unrelated stories have. */
    private val words = listOf("volcano", "budget", "election", "vaccine", "satellite", "football", "drought", "merger",
        "strike", "museum", "earthquake", "tariff", "festival", "rocket", "pandemic", "harvest", "verdict", "glacier",
        "airline", "summit")
    private fun headline(n: Int) = "${words[n].replaceFirstChar { it.uppercase() }} report ${n}th"

    @Test fun sameStoryFromTwoOutletsIsOneStory() {
        val g = Ranker.cluster(listOf(
            a("Storm Bert brings flooding to Wales and England", "BBC", 1.0),
            a("Flooding in Wales and England as Storm Bert hits", "Guardian", 2.0),
            a("Chancellor sets out budget plans", "FT", 1.0),
        ))
        assertEquals(2, g.size)
        assertEquals(2, g.first { it.size == 2 }.size)
    }

    @Test fun fresherAndWiderCoverageRankHigher() {
        val i = Interests()
        val old = Ranker.score(listOf(a("Old story about markets", "FT", 20.0)), i, now)
        val fresh = Ranker.score(listOf(a("New story about markets", "FT", 1.0)), i, now)
        val wide = Ranker.score(listOf(a("Wide story", "FT", 1.0, coverage = listOf("BBC", "Guardian", "Times"))), i, now)
        assertTrue(fresh > old)
        assertTrue(wide > fresh)
        assertEquals(0.0, Ranker.score(listOf(a("Too old", "FT", 100.0)), i, now), 0.0)
    }

    @Test fun topStoriesComeFromGoogleTopThenForYou() {
        val list = (0..7).map { a(headline(it), "S$it", 1 + it.toDouble(), top = true) } +
            (8..17).map { a(headline(it), "O$it", 1 + it - 8.0) }
        assertEquals("all different stories", 18, Ranker.cluster(list).size)
        val s = Ranker.select(list, Interests(), now)
        assertEquals(Ranker.TOP_COUNT, s.count { it.section == Section.TOP })
        assertTrue(s.take(Ranker.TOP_COUNT).all { it.lead.top })
        assertTrue(s.drop(Ranker.TOP_COUNT).all { it.section == Section.FOR_YOU })
    }

    @Test fun oneOutletDoesNotTakeOverForYou() {
        // Top stories taken by Google's picks, For you would be all "Loud" by score alone.
        val list = (0..4).map { a(headline(it), "Top$it", 0.5, top = true) } +
            (5..12).map { a(headline(it), "Loud", 0.5 + (it - 5) * 0.1) } +
            listOf(a(headline(13), "Quiet", 2.0))
        val forYou = Ranker.select(list, Interests(), now).filter { it.section == Section.FOR_YOU }
        assertEquals(9, forYou.size)
        val quiet = forYou.indexOfFirst { it.lead.source == "Quiet" }
        assertTrue("the other outlet is not buried ($quiet)", quiet in 0..3)
    }

    @Test fun learningFromWhatIsRead() {
        val sport = a("Cup final tonight team", "Sky", 2.0, topic = "SPORTS")
        val science = a("Telescope finds planet", "Nature", 2.0, topic = "SCIENCE")
        val i = Interests()
        repeat(4) { i.opened(science.copy(link = "l$it"), now) }
        assertTrue(Ranker.score(listOf(science), i, now) > Ranker.score(listOf(sport), i, now))
        i.fewerLike(sport, now)
        val s = Ranker.select(listOf(sport, science), i, now)
        assertTrue("hidden", s.none { it.lead.link == sport.link })
    }

    @Test fun interestsFadeAndSurviveJson() {
        val i = Interests()
        i.opened(a("Planet found", "Nature", 1.0, topic = "SCIENCE"), now)
        val w = i.topics["SCIENCE"]!!
        i.decay(now + (Interests.HALF_LIFE_DAYS * 86_400_000).toLong())
        assertEquals(w / 2, i.topics["SCIENCE"]!!, 1e-9)
        val back = Interests.fromJson(i.toJson())
        assertEquals(i.topics, back.topics)
        assertEquals(i.read, back.read)
    }

    @Test fun subscribedFeedsGetABoost() {
        val i = Interests()
        val google = Ranker.score(listOf(a("Some story here", "X", 2.0)), i, now)
        val feed = Ranker.score(listOf(a("Some story here", "X", 2.0, origin = Origin.FEEDS)), i, now)
        assertTrue(feed > google)
    }
}
