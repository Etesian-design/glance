package com.bompotis.etesian.glance

import com.bompotis.etesian.glance.search.Match
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchTest {
    @Test fun exactAndPrefix() {
        assertEquals(Match.EXACT, Match.score("maps", "Maps"))
        assertEquals(Match.PREFIX, Match.score("whats", "WhatsApp"))
    }

    @Test fun initialsFindTwoWordNames() {
        assertEquals(Match.INITIALS, Match.score("wa", "WhatsApp"))
        assertEquals(Match.INITIALS, Match.score("gm", "Google Maps"))
    }

    @Test fun wordPrefixBeatsInitials() {
        assertEquals(Match.WORD_PREFIX, Match.score("map", "Google Maps"))
        assertTrue(Match.score("map", "Google Maps") > Match.score("gm", "Google Maps"))
    }

    @Test fun severalWordsEachStartAWord() {
        assertEquals(Match.ALL_WORDS, Match.score("ma ka", "Maria Karra"))
        assertEquals(Match.ALL_WORDS, Match.score("ka ma", "Maria Karra"))
        assertEquals(Match.NONE, Match.score("ma ma", "Maria Karra"))
    }

    @Test fun accentsAndGreekIgnoreMarks() {
        assertEquals(Match.PREFIX, Match.score("cafe", "Café Nero"))
        assertEquals(Match.PREFIX, Match.score("ελεν", "Ελένη Παπά"))
    }

    @Test fun weakMatchesNeedThreeLetters() {
        assertEquals(Match.NONE, Match.score("ap", "Snapchat"))
        assertEquals(Match.CONTAINS, Match.score("apc", "Snapchat"))
        assertEquals(Match.SUBSEQUENCE, Match.score("spc", "Snapchat"))
        assertEquals(Match.NONE, Match.score("xyz", "Snapchat"))
    }
}
