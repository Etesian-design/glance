package com.bompotis.etesian.glance.search

import android.app.Activity
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.Process
import android.os.UserManager
import android.util.Log
import com.bompotis.etesian.glance.notifications.NotificationHistory

/**
 * Opens a search result, as Glance: starts an app shortcut (only a launcher or a holder of ACCESS_SHORTCUTS may),
 * fires a notification's own action while it is still showing, or starts an intent with Glance's read access
 * (files). Exported only to the launcher (Glance's signature permission), never to other apps.
 */
class Open : Activity() {

    companion object {
        private const val TAG = "GlanceOpen"
        private const val EXTRA_TARGET = "target"
        private const val EXTRA_SHORTCUT = "shortcut"
        private const val EXTRA_PACKAGE = "pkg"
        private const val EXTRA_USER = "user"
        private const val EXTRA_NOTIFICATION = "notification"
        private const val EXTRA_ARTICLE = "article"

        private fun base(ctx: Context) = Intent().setComponent(ComponentName(ctx.packageName, Open::class.java.name))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)

        /** Starts [target] (an app, a contact, a file...). */
        fun intent(ctx: Context, target: Intent): Intent =
            base(ctx).putExtra(EXTRA_TARGET, target.toUri(Intent.URI_INTENT_SCHEME))
                // Distinct data per target, so two results never share one PendingIntent or task record.
                .setData(android.net.Uri.parse("glance:open/${target.toUri(0).hashCode()}"))

        fun shortcut(ctx: Context, pkg: String, id: String, userSerial: Long): Intent =
            base(ctx).putExtra(EXTRA_SHORTCUT, id).putExtra(EXTRA_PACKAGE, pkg).putExtra(EXTRA_USER, userSerial)
                .setData(android.net.Uri.parse("glance:shortcut/$pkg/$id"))

        /** A news story: opened in the browser, and remembered (what the user reads ranks higher). */
        fun article(ctx: Context, link: String): Intent =
            base(ctx).putExtra(EXTRA_ARTICLE, link).setData(android.net.Uri.parse("glance:article/${link.hashCode()}"))

        /** A notification: its own action while it is showing, otherwise the app. */
        fun notification(ctx: Context, key: String, pkg: String): Intent =
            base(ctx).putExtra(EXTRA_NOTIFICATION, key).putExtra(EXTRA_PACKAGE, pkg)
                .setData(android.net.Uri.parse("glance:notification/${key.hashCode()}"))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { open(intent) }.onFailure { Log.w(TAG, "open", it) }
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    private fun open(i: Intent) {
        val pkg = i.getStringExtra(EXTRA_PACKAGE)
        i.getStringExtra(EXTRA_SHORTCUT)?.let { id ->
            val user = getSystemService(UserManager::class.java)
                .getUserForSerialNumber(i.getLongExtra(EXTRA_USER, 0)) ?: Process.myUserHandle()
            getSystemService(LauncherApps::class.java).startShortcut(pkg!!, id, null, null, user)
            return
        }
        i.getStringExtra(EXTRA_NOTIFICATION)?.let { key ->
            if (NotificationHistory.fire(key)) return
            pkg?.let { packageManager.getLaunchIntentForPackage(it) }?.let { startActivity(it) }
            return
        }
        i.getStringExtra(EXTRA_ARTICLE)?.let { link ->
            com.bompotis.etesian.glance.news.NewsStore.find(this, link)?.let {
                com.bompotis.etesian.glance.news.NewsStore.opened(this, it)
            }
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        i.getStringExtra(EXTRA_TARGET)?.let { uri ->
            val target = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(target, ActivityOptions.makeBasic().toBundle())
        }
    }
}
