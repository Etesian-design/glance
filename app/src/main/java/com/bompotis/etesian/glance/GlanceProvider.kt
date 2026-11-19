package com.bompotis.etesian.glance

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import com.bompotis.etesian.glance.news.NewsStore
import com.bompotis.etesian.glance.search.Open
import com.bompotis.etesian.glance.search.Searcher

/**
 * Glance for the Etesian launcher (its News screen and the search in its applications view), signature
 * permission only:
 * - `news`: the ranked stories (section, title, source, link, time, topic, summary, image, outlets, open);
 *   changes are announced on [NEWS].
 * - call `search` (arg: the query; extras "perSection"): "sections", each with "kind", "title" and "results"
 *   (Result.toBundle); `refresh` (arg "force"); `fewer` (arg: a story's link); `state`.
 * A story's or result's "open" is an intent URI for Glance's [Open] activity, to start as is.
 */
class GlanceProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.bompotis.etesian.glance"
        const val PERMISSION = "com.bompotis.etesian.glance.permission.READ"
        val NEWS: Uri = Uri.parse("content://$AUTHORITY/news")
        val COLUMNS = arrayOf("section", "title", "source", "link", "time", "topic", "summary", "image", "outlets", "open")
    }

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?,
                       sortOrder: String?): Cursor? {
        val ctx = context!!
        if (uri.lastPathSegment != "news") return null
        return MatrixCursor(COLUMNS).apply {
            NewsStore.stories(ctx).forEach { s ->
                val a = s.lead
                addRow(arrayOf<Any>(s.section.name, a.title, a.source, a.link, a.time, a.topic, a.summary, s.image,
                    s.sources.size, Open.article(ctx, a.link).toUri(android.content.Intent.URI_INTENT_SCHEME)))
            }
            setNotificationUri(ctx.contentResolver, NEWS)
        }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context!!
        // The provider's readPermission doesn't cover call().
        ctx.enforceCallingOrSelfPermission(PERMISSION, "Glance")
        val out = Bundle()
        val id = clearCallingIdentity()
        try {
            when (method) {
                "search" -> {
                    val sections = Searcher.search(ctx, arg.orEmpty(), perSection = extras?.getInt("perSection", Searcher.PER_SECTION)
                        ?: Searcher.PER_SECTION)
                    out.putParcelableArrayList("sections", ArrayList(sections.map { s ->
                        Bundle().apply {
                            putString("kind", s.kind.name)
                            putString("title", s.title)
                            putParcelableArrayList("results", ArrayList(s.results.map { it.toBundle() }))
                        }
                    }))
                }
                "refresh" -> NewsStore.refresh(ctx, force = arg == "force")
                "fewer" -> arg?.let { NewsStore.find(ctx, it) }?.let { NewsStore.fewerLike(ctx, it) }
                "state" -> {}
                else -> return null
            }
        } finally {
            restoreCallingIdentity(id)
        }
        out.putBoolean("refreshing", NewsStore.refreshing)
        out.putLong("lastRefresh", NewsStore.lastRefresh(ctx))
        return out
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0
}
