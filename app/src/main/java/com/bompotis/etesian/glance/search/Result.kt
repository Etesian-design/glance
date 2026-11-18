package com.bompotis.etesian.glance.search

import android.content.Context
import android.content.Intent
import android.os.Bundle

/** What a result is; the order is the order the sections show in when their best matches tie. */
enum class Kind(val section: String) {
    APP("Applications"),
    SHORTCUT("Shortcuts"),
    CONTACT("Contacts"),
    MESSAGE("Messages"),
    NOTIFICATION("Notifications"),
    SETTING("Settings"),
    FILE("Files"),
    NEWS("News"),
    FEED("Feeds"),
    WEB("Web"),
}

/**
 * One search result. [open] is what a tap does, as an intent for [Open] (Glance's own activity): only Glance can
 * start an app shortcut, fire a notification's own action or lend its read access to a file, so the launcher
 * opens results through it too.
 */
data class Result(
    val kind: Kind,
    val title: String,
    val subtitle: String = "",
    /** The app the result belongs to: its icon is the result's icon. */
    val pkg: String = "",
    val time: Long = 0,
    val score: Int = 0,
    val open: Intent,
    /** Same thing found twice (one SMS in Messages and as a notification): the later source drops it. */
    val key: String = "${kind.name}:$title:$subtitle",
) {
    fun toBundle() = Bundle().apply {
        putString("kind", kind.name)
        putString("section", kind.section)
        putString("title", title)
        putString("subtitle", subtitle)
        putString("pkg", pkg)
        putLong("time", time)
        putInt("score", score)
        putString("open", open.toUri(Intent.URI_INTENT_SCHEME))
    }
}

/** One place search looks: apps, contacts, notifications... */
interface Source {
    val kind: Kind
    /** Whether the user allowed it (permissions) and it is switched on in Settings. */
    fun available(ctx: Context): Boolean = true
    /** Results for [query], best first; runs off the main thread and should give up quickly. */
    fun search(ctx: Context, query: String, limit: Int): List<Result>
}
