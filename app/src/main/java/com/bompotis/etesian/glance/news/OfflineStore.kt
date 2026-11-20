package com.bompotis.etesian.glance.news

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Articles kept for reading offline: what the reader extracted (Readability's JSON), one file per link. Capped by
 * age and size, oldest dropped first.
 */
class OfflineStore(private val dir: File, val maxAgeMs: Long = MAX_AGE_MS, val maxBytes: Long = MAX_BYTES) {
    companion object {
        const val MAX_AGE_MS = 30 * 86_400_000L
        const val MAX_BYTES = 50_000_000L
    }

    data class Saved(val link: String, val landed: String, val article: String, val saved: Long)

    private fun name(link: String) = MessageDigest.getInstance("SHA-1").digest(link.toByteArray())
        .joinToString("") { "%02x".format(it) } + ".json"
    private fun files() = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().toList()

    fun has(link: String) = File(dir, name(link)).exists()

    /** [article] is the JSON the reader's extraction returns. */
    fun put(link: String, landed: String, article: String, now: Long = System.currentTimeMillis()) {
        dir.mkdirs()
        val f = File(dir, name(link))
        f.writeText(JSONObject().put("link", link).put("landed", landed).put("article", article).put("saved", now).toString())
        f.setLastModified(now)
        evict(now)
    }

    fun get(link: String): Saved? = runCatching {
        val o = JSONObject(File(dir, name(link)).readText())
        Saved(o.getString("link"), o.optString("landed"), o.getString("article"), o.optLong("saved"))
    }.getOrNull()

    /** Older than [maxAgeMs] out; then the oldest until all fit in [maxBytes]. */
    fun evict(now: Long = System.currentTimeMillis()) {
        val all = files().sortedBy { it.lastModified() }.toMutableList()
        all.filter { now - it.lastModified() > maxAgeMs }.forEach { it.delete(); all.remove(it) }
        var total = all.sumOf { it.length() }
        while (total > maxBytes && all.isNotEmpty()) { val f = all.removeAt(0); total -= f.length(); f.delete() }
    }

    fun count() = files().size
    fun bytes() = files().sumOf { it.length() }
    fun clear() { files().forEach { it.delete() } }
}
