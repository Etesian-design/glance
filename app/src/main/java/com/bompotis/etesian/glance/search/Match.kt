package com.bompotis.etesian.glance.search

import java.text.Normalizer

/**
 * How well a query matches a name, the way Sesame does it: a few letters find things by the start of a name, the
 * start of any word, the initials ("wa" finds WhatsApp, "gm" Google Maps) or, weakest, letters in order.
 * Several query words must each match a different word of the name ("ma ka" finds "Maria Karra"). 0 is no match.
 */
object Match {

    const val NONE = 0
    const val EXACT = 100
    const val PREFIX = 90
    const val WORD_PREFIX = 75
    const val INITIALS = 70
    const val ALL_WORDS = 65
    const val CONTAINS = 45
    const val SUBSEQUENCE = 20

    /** Lower case, without accents, so "Ελένη" matches "ελενη" and "Café" matches "cafe". */
    fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase().trim()

    /** The words of a name: split at spaces and punctuation, and at camel case ("WhatsApp" is "whats", "app"). */
    fun words(name: String): List<String> {
        val split = Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("(?<=\\p{Ll})(?=\\p{Lu})"), " ")
        return split.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
    }

    fun score(query: String, name: String): Int {
        val q = normalize(query)
        if (q.isEmpty() || name.isEmpty()) return NONE
        val n = normalize(name)
        if (n == q) return EXACT
        if (n.startsWith(q)) return PREFIX
        val words = words(name)
        val tokens = q.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.size > 1) return if (allWords(tokens, words)) ALL_WORDS else NONE
        if (words.drop(1).any { it.startsWith(q) }) return WORD_PREFIX
        if (q.length >= 2 && initials(q, words)) return INITIALS
        if (q.length >= 3 && n.contains(q)) return CONTAINS
        if (q.length >= 3 && subsequence(q, n.replace(" ", ""))) return SUBSEQUENCE
        return NONE
    }

    /** Every query word starts a different word of the name, in any order. */
    private fun allWords(tokens: List<String>, words: List<String>): Boolean {
        val free = words.toMutableList()
        for (t in tokens) {
            val i = free.indexOfFirst { it.startsWith(t) }
            if (i < 0) return false
            free.removeAt(i)
        }
        return true
    }

    /** The query is the first letters of consecutive words ("gm" for Google Maps, "wa" for WhatsApp). */
    private fun initials(q: String, words: List<String>): Boolean {
        if (words.size < 2) return false
        val first = words.joinToString("") { it.take(1) }
        return first.contains(q)
    }

    private fun subsequence(q: String, n: String): Boolean {
        var i = 0
        for (c in n) if (i < q.length && c == q[i]) i++
        return i == q.length
    }
}
