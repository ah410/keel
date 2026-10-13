package com.arceusvault.keel.data

import android.app.job.JobParameters
import android.app.job.JobService
import com.arceusvault.keel.KeelApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** The periodic background alert check scheduled by [AlertWatch]. */
class AlertJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val watch = (application as KeelApp).container.alertWatch
        running = scope.launch {
            // A failed check is retried on the next period; reschedule-on-failure would just drain battery.
            runCatching { withTimeout(90_000) { watch.check() } }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        return false
    }
}
