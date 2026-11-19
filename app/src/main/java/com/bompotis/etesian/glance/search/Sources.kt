package com.bompotis.etesian.glance.search

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.os.UserManager
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import com.bompotis.etesian.glance.Prefs
import com.bompotis.etesian.glance.news.FeedsClient
import com.bompotis.etesian.glance.news.NewsStore
import com.bompotis.etesian.glance.notifications.NotificationHistory

private fun Context.granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

/** Installed apps, in every profile (work, private space). */
object AppsSource : Source {
    override val kind = Kind.APP
    override fun search(ctx: Context, query: String, limit: Int): List<Result> {
        val la = ctx.getSystemService(LauncherApps::class.java)
        val users = ctx.getSystemService(UserManager::class.java).userProfiles
        return users.flatMap { u -> runCatching { la.getActivityList(null, u) }.getOrDefault(emptyList()) }
            .mapNotNull { a ->
                val label = a.label.toString()
                val s = Match.score(query, label)
                if (s == Match.NONE || a.componentName.packageName == ctx.packageName) return@mapNotNull null
                val open = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(a.componentName)
                Result(Kind.APP, label, pkg = a.componentName.packageName, score = s,
                    open = Open.intent(ctx, open), key = "app:${a.componentName.flattenToString()}:${a.user.hashCode()}")
            }
            .sortedByDescending { it.score }.take(limit)
    }
}

/**
 * The shortcuts apps publish (a chat with someone, a new event, a navigation home): Sesame's main trick. "wa ma"
 * finds Maria in WhatsApp: the first word may match the app and the rest the shortcut. Needs a launcher's access
 * to shortcuts (ACCESS_SHORTCUTS, granted to Glance on Etesian by the platform key).
 */
object ShortcutsSource : Source {
    override val kind = Kind.SHORTCUT
    override fun available(ctx: Context) =
        runCatching { ctx.getSystemService(LauncherApps::class.java).hasShortcutHostPermission() }.getOrDefault(false)

    override fun search(ctx: Context, query: String, limit: Int): List<Result> {
        val la = ctx.getSystemService(LauncherApps::class.java)
        val um = ctx.getSystemService(UserManager::class.java)
        val q = LauncherApps.ShortcutQuery().setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
            LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED or
            LauncherApps.ShortcutQuery.FLAG_MATCH_CACHED)
        val pm = ctx.packageManager
        val labels = HashMap<String, String>()
        val tokens = query.trim().split(Regex("\\s+"))
        return um.userProfiles.flatMap { u -> runCatching { la.getShortcuts(q, u).orEmpty() }.getOrDefault(emptyList()) }
            .filter { it.isEnabled }
            .mapNotNull { s ->
                val label = (s.shortLabel ?: s.longLabel)?.toString() ?: return@mapNotNull null
                val app = labels.getOrPut(s.`package`) {
                    runCatching { pm.getApplicationLabel(pm.getApplicationInfo(s.`package`, 0)).toString() }.getOrDefault(s.`package`)
                }
                var score = maxOf(Match.score(query, label), Match.score(query, s.longLabel?.toString().orEmpty()) - 5)
                // "wa maria": the app, then the shortcut.
                if (tokens.size > 1 && Match.score(tokens[0], app) >= Match.INITIALS) {
                    val rest = Match.score(tokens.drop(1).joinToString(" "), label)
                    if (rest > Match.NONE) score = maxOf(score, rest + 5)
                }
                if (score == Match.NONE) return@mapNotNull null
                Result(Kind.SHORTCUT, label, app, s.`package`, score = score,
                    open = Open.shortcut(ctx, s.`package`, s.id, um.getSerialNumberForUser(s.userHandle)),
                    key = "shortcut:${s.`package`}:${s.id}")
            }
            .sortedByDescending { it.score }.take(limit)
    }
}

object ContactsSource : Source {
    override val kind = Kind.CONTACT
    override fun available(ctx: Context) = ctx.granted(Manifest.permission.READ_CONTACTS) && Prefs.source(ctx, kind)

    override fun search(ctx: Context, query: String, limit: Int): List<Result> {
        val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(query.trim()))
        val cols = arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY, ContactsContract.Contacts.HAS_PHONE_NUMBER)
        val out = ArrayList<Result>()
        runCatching {
            ctx.contentResolver.query(uri, cols, null, null, null)?.use { c ->
                while (c.moveToNext() && out.size < limit * 2) {
                    val name = c.getString(2) ?: continue
                    val lookup = ContactsContract.Contacts.getLookupUri(c.getLong(0), c.getString(1))
                    // The provider also matches by phone number and nickname: those still count, a little lower.
                    val s = Match.score(query, name).takeIf { it > Match.NONE } ?: Match.CONTAINS
                    out += Result(Kind.CONTACT, name, phone(ctx, c.getLong(0)), score = s,
                        open = Open.intent(ctx, Intent(Intent.ACTION_VIEW, lookup)), key = "contact:${c.getLong(0)}")
                }
            }
        }
        return out.sortedByDescending { it.score }.take(limit)
    }

    private fun phone(ctx: Context, id: Long): String = runCatching {
        ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?", arrayOf(id.toString()),
            "${ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY} DESC")?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull().orEmpty()
}

/** Text messages (SMS), by their text or the number they came from. */
object SmsSource : Source {
    override val kind = Kind.MESSAGE
    override fun available(ctx: Context) = ctx.granted(Manifest.permission.READ_SMS) && Prefs.source(ctx, kind)

    override fun search(ctx: Context, query: String, limit: Int): List<Result> {
        val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty() || query.trim().length < 2) return emptyList()
        val where = words.joinToString(" AND ") { "(${Telephony.Sms.BODY} LIKE ? OR ${Telephony.Sms.ADDRESS} LIKE ?)" }
        val args = words.flatMap { listOf("%$it%", "%$it%") }.toTypedArray()
        val names = HashMap<String, String>()
        return runCatching {
            ctx.contentResolver.query(Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.THREAD_ID),
                where, args, "${Telephony.Sms.DATE} DESC LIMIT $limit")?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val address = c.getString(0).orEmpty()
                        val who = names.getOrPut(address) { contactName(ctx, address) ?: address }
                        // The conversation with that number (AOSP Messaging doesn't open content://mms-sms threads).
                        val thread = Uri.fromParts("smsto", address, null)
                        add(Result(Kind.MESSAGE, who, c.getString(1).orEmpty().take(140), pkg = defaultSmsApp(ctx),
                            time = c.getLong(2), score = Match.CONTAINS,
                            open = Open.intent(ctx, Intent(Intent.ACTION_SENDTO, thread)),
                            key = "msg:$who:${c.getString(1)?.take(60)}"))
                    }
                }
            }
        }.getOrNull().orEmpty()
    }

    private fun defaultSmsApp(ctx: Context) = Telephony.Sms.getDefaultSmsPackage(ctx).orEmpty()

    private fun contactName(ctx: Context, number: String): String? {
        if (!ctx.granted(Manifest.permission.READ_CONTACTS) || number.isBlank()) return null
        return runCatching {
            ctx.contentResolver.query(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
    }
}

/** Every notification of the last 30 days, and every message in chat notifications (WhatsApp, Signal...). */
object NotificationsSource : Source {
    override val kind = Kind.NOTIFICATION
    override fun available(ctx: Context) = Prefs.source(ctx, kind) && Access.notificationsAllowed(ctx)

    override fun search(ctx: Context, query: String, limit: Int): List<Result> =
        NotificationHistory.search(ctx, query, limit).map { e ->
            Result(if (e.message) Kind.MESSAGE else Kind.NOTIFICATION, e.title.ifBlank { e.app },
                if (e.message) e.text else listOf(e.app, e.text).filter { it.isNotBlank() }.joinToString(" · "),
                e.pkg, e.time, if (Match.score(query, e.title) > Match.NONE) Match.WORD_PREFIX else Match.CONTAINS,
                Open.notification(ctx, e.key, e.pkg), key = "msg:${e.title}:${e.text.take(60)}")
        }
}

/** Files on the phone's storage, by name. */
object FilesSource : Source {
    override val kind = Kind.FILE
    override fun available(ctx: Context) = Prefs.source(ctx, kind) && Access.filesAllowed(ctx)

    override fun search(ctx: Context, query: String, limit: Int): List<Result> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val words = q.split(Regex("\\s+"))
        val where = words.joinToString(" AND ") { "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?" } +
            " AND ${MediaStore.MediaColumns.SIZE} > 0"
        return runCatching {
            ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DATE_MODIFIED),
                where, words.map { "%$it%" }.toTypedArray(), "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")?.use { c ->
                buildList {
                    while (c.moveToNext() && size < limit) {
                        val name = c.getString(1) ?: continue
                        val item = android.content.ContentUris.withAppendedId(uri, c.getLong(0))
                        val view = Intent(Intent.ACTION_VIEW).setDataAndType(item, c.getString(2) ?: "*/*")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        add(Result(Kind.FILE, name, c.getString(3).orEmpty().trimEnd('/'), time = c.getLong(4) * 1000,
                            score = Match.score(q, name).coerceAtLeast(Match.CONTAINS),
                            open = Open.intent(ctx, view), key = "file:$item"))
                    }
                }
            }
        }.getOrNull().orEmpty().sortedByDescending { it.score }
    }
}

/** The Settings pages people look for by name ("wifi", "battery", "developer"). */
object SettingsSource : Source {
    override val kind = Kind.SETTING
    private val pages = listOf(
        "Wi-Fi" to Settings.ACTION_WIFI_SETTINGS, "Bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
        "Mobile network" to Settings.ACTION_NETWORK_OPERATOR_SETTINGS, "Hotspot and tethering" to "android.settings.TETHER_SETTINGS",
        "Airplane mode" to Settings.ACTION_AIRPLANE_MODE_SETTINGS, "Display" to Settings.ACTION_DISPLAY_SETTINGS,
        "Sound and vibration" to Settings.ACTION_SOUND_SETTINGS, "Notifications" to "android.settings.NOTIFICATION_SETTINGS",
        "Battery" to Settings.ACTION_BATTERY_SAVER_SETTINGS, "Storage" to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
        "Apps" to Settings.ACTION_APPLICATION_SETTINGS, "Location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
        "Security" to Settings.ACTION_SECURITY_SETTINGS, "Privacy" to Settings.ACTION_PRIVACY_SETTINGS,
        "Accounts" to Settings.ACTION_SYNC_SETTINGS, "Accessibility" to Settings.ACTION_ACCESSIBILITY_SETTINGS,
        "Date and time" to Settings.ACTION_DATE_SETTINGS, "Languages" to Settings.ACTION_LOCALE_SETTINGS,
        "Keyboard" to Settings.ACTION_INPUT_METHOD_SETTINGS, "Do not disturb" to Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS,
        "NFC" to Settings.ACTION_NFC_SETTINGS, "VPN" to Settings.ACTION_VPN_SETTINGS,
        "Developer options" to Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, "About phone" to Settings.ACTION_DEVICE_INFO_SETTINGS,
        "Default apps" to Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, "Wallpaper" to Intent.ACTION_SET_WALLPAPER,
    )
    override fun available(ctx: Context) = Prefs.source(ctx, kind)
    override fun search(ctx: Context, query: String, limit: Int) = pages.mapNotNull { (label, action) ->
        val s = maxOf(Match.score(query, label), Match.score(query, label.replace("-", "")))
        // Letters scattered through a name ("set" in "Security") are noise for a short list like this.
        if (s < Match.CONTAINS) null
        else Result(Kind.SETTING, label, "Settings", "com.android.settings", score = s, open = Open.intent(ctx, Intent(action)))
    }.sortedByDescending { it.score }.take(limit)
}

/** Today's news (Glance's own list) and the Feeds app's posts. */
object NewsSource : Source {
    override val kind = Kind.NEWS
    override fun available(ctx: Context) = Prefs.source(ctx, kind)
    override fun search(ctx: Context, query: String, limit: Int): List<Result> {
        val news = NewsStore.cached(ctx).mapNotNull { a ->
            val s = headline(query, a.title)
            if (s == Match.NONE) null else Result(Kind.NEWS, a.title, a.source, time = a.time, score = s,
                open = Open.article(ctx, a.link), key = "news:${a.link}")
        }
        val posts = FeedsClient.items(ctx).mapNotNull { a ->
            val s = headline(query, a.title)
            if (s == Match.NONE) null else Result(Kind.FEED, a.title, a.source, time = a.time, score = s,
                open = Open.article(ctx, a.link), key = "news:${a.link}")
        }
        return (news + posts).sortedWith(compareByDescending<Result> { it.score }.thenByDescending { it.time }).take(limit)
    }
}

/**
 * A headline matches when every query word starts one of its words ("elect" finds "Election results"), never
 * by letters inside a word; from 3 letters, so a short query doesn't list the day's news.
 */
internal fun headline(query: String, title: String): Int {
    val tokens = Match.normalize(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (tokens.isEmpty() || tokens.sumOf { it.length } < 3) return Match.NONE
    val words = Match.words(title)
    return if (tokens.all { t -> words.any { it.startsWith(t) } }) Match.CONTAINS else Match.NONE
}

/** Always last: the query as a web search, in the browser the user picked. */
object WebSource : Source {
    override val kind = Kind.WEB
    override fun search(ctx: Context, query: String, limit: Int): List<Result> {
        val q = query.trim()
        val web = Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, q)
        val fallback = Intent(Intent.ACTION_VIEW, Uri.parse(Prefs.searchEngine(ctx).replace("%s", Uri.encode(q))))
        val open = if (web.resolveActivity(ctx.packageManager) != null) web else fallback
        val out = mutableListOf(Result(Kind.WEB, "Search the web for “$q”", "", score = 1,
            open = Open.intent(ctx, open), key = "web:$q"))
        if (Prefs.webSuggestions(ctx)) Suggest.fetch(q).take(limit - 1).forEach { s ->
            out += Result(Kind.WEB, s, "", score = 1, key = "suggest:$s",
                open = Open.intent(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(Prefs.searchEngine(ctx).replace("%s", Uri.encode(s))))))
        }
        return out
    }
}

/** What the user let Glance see. */
object Access {
    fun notificationsAllowed(ctx: Context): Boolean = runCatching {
        ctx.getSystemService(android.app.NotificationManager::class.java)
            .isNotificationListenerAccessGranted(NotificationHistory.listenerComponent(ctx))
    }.getOrDefault(false)

    fun filesAllowed(ctx: Context) = Environment.isExternalStorageManager() ||
        ctx.granted(Manifest.permission.READ_MEDIA_IMAGES)
}
