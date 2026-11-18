package com.bompotis.etesian.glance

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import com.bompotis.etesian.glance.notifications.NotificationHistory
import com.bompotis.etesian.glance.search.Access
import com.bompotis.etesian.glance.search.Kind

/** What search could also cover with the user's permission, and how to ask for it. */
object Setup {

    enum class Need(val kind: Kind, val label: Int) {
        CONTACTS(Kind.CONTACT, R.string.need_contacts),
        SMS(Kind.MESSAGE, R.string.need_sms),
        NOTIFICATIONS(Kind.NOTIFICATION, R.string.need_notifications),
        FILES(Kind.FILE, R.string.need_files),
    }

    fun allowed(ctx: Context, n: Need): Boolean = when (n) {
        Need.CONTACTS -> ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        Need.SMS -> ctx.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        Need.NOTIFICATIONS -> Access.notificationsAllowed(ctx)
        Need.FILES -> Access.filesAllowed(ctx)
    }

    /** Switched on in Settings but not allowed yet: the news shows a card offering them. */
    fun missing(ctx: Context) = Need.entries.filter { Prefs.source(ctx, it.kind) && !allowed(ctx, it) }

    fun request(a: Activity, n: Need) {
        when (n) {
            Need.CONTACTS -> a.requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 1)
            Need.SMS -> a.requestPermissions(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS), 2)
            Need.NOTIFICATIONS -> if (!grantNotificationsSelf(a)) a.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        NotificationHistory.listenerComponent(a).flattenToString()))
            Need.FILES -> a.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${a.packageName}")))
        }
    }

    /**
     * On Etesian (platform key: MANAGE_NOTIFICATION_LISTENERS) Glance switches its notification access on itself,
     * when the user asks for it here; elsewhere the system page asks them.
     */
    private fun grantNotificationsSelf(ctx: Context): Boolean = runCatching {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        NotificationManager::class.java.getMethod("setNotificationListenerAccessGranted",
            android.content.ComponentName::class.java, Boolean::class.javaPrimitiveType)
            .invoke(nm, NotificationHistory.listenerComponent(ctx), true)
        Access.notificationsAllowed(ctx)
    }.getOrDefault(false)

}
