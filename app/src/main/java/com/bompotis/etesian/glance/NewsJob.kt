package com.bompotis.etesian.glance

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.bompotis.etesian.glance.news.NewsStore

/** Fetches the news about once an hour while there is a network, so the screen is fresh when opened. */
class NewsJob : JobService() {
    companion object {
        private const val ID = 1
        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java)
            if (js.getPendingJob(ID) != null) return
            js.schedule(JobInfo.Builder(ID, ComponentName(ctx, NewsJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(60 * 60_000L, 15 * 60_000L)
                .setPersisted(true)
                .build())
        }
    }

    override fun onStartJob(params: JobParameters): Boolean {
        val done = object : () -> Unit {
            override fun invoke() {
                if (!NewsStore.refreshing && !com.bompotis.etesian.glance.news.Offline.running) { NewsStore.unlisten(this); jobFinished(params, false) }
            }
        }
        NewsStore.listen(done)
        NewsStore.refresh(this)
        if (!NewsStore.refreshing && !com.bompotis.etesian.glance.news.Offline.running) { NewsStore.unlisten(done); return false }
        return true
    }

    override fun onStopJob(params: JobParameters) = true

    class Boot : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) = schedule(ctx)
    }
}
