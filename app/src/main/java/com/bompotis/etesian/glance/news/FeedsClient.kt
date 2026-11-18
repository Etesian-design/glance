package com.bompotis.etesian.glance.news

import android.content.Context
import android.net.Uri

/**
 * The Etesian Feeds app's posts (its subscriptions), read through its provider like the launcher's Events view
 * does. Needs the Feeds read permission (signature: an Etesian-signed Glance); elsewhere this is just empty.
 */
object FeedsClient {
    const val PACKAGE = "com.bompotis.etesian.feeds"
    const val PERMISSION = "$PACKAGE.permission.READ"
    private val ITEMS: Uri = Uri.parse("content://$PACKAGE/items")

    fun available(ctx: Context) = ctx.checkSelfPermission(PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun items(ctx: Context, limit: Int = 300): List<Article> {
        if (!available(ctx)) return emptyList()
        return runCatching {
            ctx.contentResolver.query(ITEMS, null, null, null, null)?.use { c ->
                val feed = c.getColumnIndex("feedTitle"); val title = c.getColumnIndex("title")
                val summary = c.getColumnIndex("summary"); val link = c.getColumnIndex("link"); val time = c.getColumnIndex("time")
                val id = c.getColumnIndex("id")
                buildList {
                    while (c.moveToNext() && size < limit) {
                        val l = c.getString(link) ?: continue
                        add(Article(Rss.clean(c.getString(title).orEmpty()), c.getString(feed).orEmpty(), l, c.getLong(time),
                            "", Rss.clean(c.getString(summary).orEmpty()).take(300), "", Origin.FEEDS, ref = c.getString(id).orEmpty()))
                    }
                }
            }
        }.getOrNull().orEmpty()
    }

    /** Opened in Glance: marked read in Feeds too. */
    fun markRead(ctx: Context, a: Article) {
        if (!available(ctx) || a.ref.isEmpty()) return
        runCatching { ctx.contentResolver.call(Uri.parse("content://$PACKAGE"), "setRead", a.ref, null) }
    }
}
