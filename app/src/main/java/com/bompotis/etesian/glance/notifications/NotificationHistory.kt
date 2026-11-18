package com.bompotis.etesian.glance.notifications

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Every notification the phone showed, kept for [KEEP_DAYS] days so search finds them after they are dismissed:
 * one row per notification, and one per message of a chat notification (MessagingStyle), so "what did Maria say"
 * finds the message even when the chat app has since replaced the notification. Ongoing ones (music, navigation,
 * downloads) and Glance's own are left out.
 */
object NotificationHistory {

    class Entry(
        val key: String,
        val pkg: String,
        val app: String,
        val title: String,
        val text: String,
        val time: Long,
        /** A message of a conversation (MessagingStyle): who sent it is [title]. */
        val message: Boolean,
    )

    private const val TAG = "GlanceNotifs"
    const val KEEP_DAYS = 30
    private const val MAX_ROWS = 20_000

    /** The notifications still showing, by key: tapping their result fires their own action. */
    private val live = ConcurrentHashMap<String, PendingIntent>()
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var db: Db? = null

    private fun db(ctx: Context) = db ?: synchronized(this) { db ?: Db(ctx.applicationContext).also { db = it } }

    private class Db(ctx: Context) : SQLiteOpenHelper(ctx, "notifications.db", null, 1) {
        override fun onCreate(d: SQLiteDatabase) {
            d.execSQL("""CREATE TABLE n (key TEXT NOT NULL, pkg TEXT NOT NULL, app TEXT NOT NULL, title TEXT NOT NULL,
                text TEXT NOT NULL, time INTEGER NOT NULL, message INTEGER NOT NULL,
                UNIQUE(pkg, title, text, message) ON CONFLICT REPLACE)""")
            d.execSQL("CREATE INDEX n_time ON n(time)")
        }
        override fun onUpgrade(d: SQLiteDatabase, old: Int, new: Int) {}
    }

    /** The listener is connected (the user allowed notification access). */
    @Volatile var connected = false

    fun listenerComponent(ctx: Context) = ComponentName(ctx, Listener::class.java)

    fun record(ctx: Context, sbn: StatusBarNotification) {
        val n = sbn.notification
        if (sbn.packageName == ctx.packageName || sbn.isOngoing || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        n.contentIntent?.let { live[sbn.key] = it }
        val entries = entries(ctx, sbn)
        if (entries.isEmpty()) return
        io.execute {
            runCatching {
                val w = db(ctx).writableDatabase
                w.beginTransaction()
                try {
                    entries.forEach { e ->
                        w.insert("n", null, ContentValues().apply {
                            put("key", e.key); put("pkg", e.pkg); put("app", e.app); put("title", e.title)
                            put("text", e.text); put("time", e.time); put("message", if (e.message) 1 else 0)
                        })
                    }
                    w.setTransactionSuccessful()
                } finally { w.endTransaction() }
            }.onFailure { Log.w(TAG, "record", it) }
        }
    }

    fun removed(sbn: StatusBarNotification) { live.remove(sbn.key) }

    /** Fires a showing notification's own action, as the user tapping it would; false if it's gone. */
    fun fire(key: String): Boolean {
        val pi = live[key] ?: return false
        return runCatching {
            pi.send(null, 0, null, null, null, null,
                android.app.ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(
                    android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle())
            true
        }.getOrDefault(false)
    }

    /** What one notification contributes: its messages if it is a conversation, otherwise itself. */
    internal fun entries(ctx: Context, sbn: StatusBarNotification): List<Entry> {
        val n = sbn.notification
        val x: Bundle = n.extras
        val app = runCatching {
            ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)
        val messages = x.getParcelableArray(Notification.EXTRA_MESSAGES, android.os.Parcelable::class.java)
        if (!messages.isNullOrEmpty()) {
            val conversation = x.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            return Notification.MessagingStyle.Message.getMessagesFromBundleArray(messages).mapNotNull { m ->
                val text = m.text?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val sender = m.senderPerson?.name?.toString() ?: x.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: app
                Entry(sbn.key, sbn.packageName, app,
                    if (conversation != null && conversation != sender) "$sender · $conversation" else sender,
                    text, m.timestamp, message = true)
            }
        }
        val title = x.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (x.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: x.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return emptyList()
        return listOf(Entry(sbn.key, sbn.packageName, app, title, text, sbn.postTime, message = false))
    }

    /** Entries whose app, title or text contain every word of [query], newest first. */
    fun search(ctx: Context, query: String, limit: Int): List<Entry> {
        val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val where = words.joinToString(" AND ") { "(title LIKE ? OR text LIKE ? OR app LIKE ?)" }
        val args = words.flatMap { w -> List(3) { "%${w.replace("%", "").replace("_", "")}%" } }.toTypedArray()
        return runCatching {
            db(ctx).readableDatabase.query("n", null, where, args, null, null, "time DESC", limit.toString()).use { c ->
                buildList {
                    while (c.moveToNext()) add(Entry(c.getString(0), c.getString(1), c.getString(2), c.getString(3),
                        c.getString(4), c.getLong(5), c.getInt(6) == 1))
                }
            }
        }.getOrDefault(emptyList())
    }

    /** Drops what is older than [KEEP_DAYS] days, and the oldest beyond [MAX_ROWS]. */
    fun prune(ctx: Context) = io.execute {
        runCatching {
            val w = db(ctx).writableDatabase
            w.delete("n", "time < ?", arrayOf((System.currentTimeMillis() - KEEP_DAYS * 86_400_000L).toString()))
            w.execSQL("DELETE FROM n WHERE rowid NOT IN (SELECT rowid FROM n ORDER BY time DESC LIMIT $MAX_ROWS)")
        }
    }

    fun clear(ctx: Context) = io.execute { runCatching { db(ctx).writableDatabase.delete("n", null, null) } }

    class Listener : NotificationListenerService() {
        override fun onListenerConnected() {
            connected = true
            // What is showing now, so it is searchable from the start.
            runCatching { activeNotifications?.forEach { record(this, it) } }
            prune(this)
        }
        override fun onListenerDisconnected() { connected = false }
        override fun onNotificationPosted(sbn: StatusBarNotification) = record(this, sbn)
        override fun onNotificationRemoved(sbn: StatusBarNotification) = removed(sbn)
    }
}
