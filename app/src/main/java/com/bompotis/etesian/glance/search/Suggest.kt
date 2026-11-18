package com.bompotis.etesian.glance.search

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Web search suggestions from DuckDuckGo (off by default, Settings → Web suggestions: what is typed leaves the
 * phone). Gives up after [TIMEOUT_MS]: search never waits on the network.
 */
object Suggest {
    private const val TIMEOUT_MS = 1200

    fun fetch(query: String): List<String> = runCatching {
        val url = URL("https://duckduckgo.com/ac/?type=list&q=" + URLEncoder.encode(query, "UTF-8"))
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = TIMEOUT_MS; c.readTimeout = TIMEOUT_MS
        c.setRequestProperty("User-Agent", "EtesianGlance")
        try { parse(c.inputStream.bufferedReader().readText(), query) } finally { c.disconnect() }
    }.getOrDefault(emptyList())

    /** ["query", ["suggestion", ...]], without the query itself. */
    fun parse(body: String, query: String): List<String> {
        val a = JSONArray(body).optJSONArray(1) ?: return emptyList()
        return (0 until a.length()).map { a.getString(it) }.filter { !it.equals(query.trim(), ignoreCase = true) }
    }
}
