package dev.claudeusage.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.claudeusage.Graph
import dev.claudeusage.data.MINUTE
import dev.claudeusage.data.SECOND
import dev.claudeusage.data.UsageState
import dev.claudeusage.widget.Widgets
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Background refreshes, for the widgets and notifications only (nothing runs in the background without them).
 *
 * Android runs periodic work at most every 15 minutes, so shorter intervals use a chain of one-off jobs, each
 * enqueuing the next, with the periodic job as a safety net that restarts the chain if the system dropped it.
 * Between fetches an alarm redraws the widgets at the next moment their content changes by itself: a limit
 * resetting, or the data turning stale.
 */
object Scheduler {
    private const val TAG = "Scheduler"
    private const val PERIODIC = "refresh-periodic"
    private const val CHAIN = "refresh-chain"
    private const val NOW = "refresh-now"

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun backgroundNeeded(context: Context): Boolean {
        val settings = Graph.settings.value
        return Graph.tokens.isSignedIn && (Widgets.hasAny(context) || settings.notifyReset || settings.notifyThreshold > 0)
    }

    /** Brings the scheduled work in line with the settings, the login and the widgets on the home screen. */
    fun ensure(context: Context) {
        val work = WorkManager.getInstance(context)
        if (!backgroundNeeded(context)) {
            work.cancelUniqueWork(PERIODIC)
            work.cancelUniqueWork(CHAIN)
            return
        }
        val minutes = Graph.settings.value.backgroundMinutes
        val periodic = PeriodicWorkRequestBuilder<RefreshWorker>(maxOf(15, minutes).toLong(), TimeUnit.MINUTES)
            .setConstraints(network)
            .build()
        work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, periodic)
        if (minutes < 15) enqueueChain(context, ExistingWorkPolicy.KEEP) else work.cancelUniqueWork(CHAIN)
    }

    /** Called after the interval changed: restart the chain with the new delay. */
    fun reschedule(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(CHAIN)
        ensure(context)
    }

    internal fun enqueueChain(context: Context, policy: ExistingWorkPolicy) {
        val minutes = Graph.settings.value.backgroundMinutes
        if (minutes >= 15 || !backgroundNeeded(context)) return
        val next = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setInitialDelay(minutes.toLong(), TimeUnit.MINUTES)
            .setConstraints(network)
            .setInputData(workDataOf(RefreshWorker.KEY_CHAIN to true))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(CHAIN, policy, next)
    }

    /** A refresh the user asked for (widget button): runs right away as expedited work. */
    fun refreshNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(workDataOf(RefreshWorker.KEY_MANUAL to true))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * Schedules a redraw for the next moment the widgets change without new data. Inexact (Android batches such
     * alarms within a few minutes), which needs no special permission and costs next to nothing.
     */
    fun scheduleRedraw(context: Context, state: UsageState, now: Long = System.currentTimeMillis()) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = PendingIntent.getBroadcast(
            context, 0, Intent(context, RedrawReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val snapshot = state.snapshot
        val staleAt = snapshot?.let { it.fetchedAt + maxOf(3L * Graph.settings.value.backgroundMinutes, 30L) * MINUTE }
        val next = listOfNotNull(snapshot?.session?.resetsAt, snapshot?.weekly?.resetsAt, staleAt)
            .filter { it > now }
            .minOrNull()
        if (next == null || !Widgets.hasAny(context) && !Graph.settings.value.notifyReset) {
            alarms.cancel(intent)
            return
        }
        alarms.setWindow(AlarmManager.RTC, next + 5 * SECOND, 60 * SECOND, intent)
        Log.i(TAG, "Next redraw in ${(next - now) / 1000}s")
    }
}

class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        val chain = inputData.getBoolean(KEY_CHAIN, false)
        val repository = Graph.repository
        try {
            val minutes = Graph.settings.value.backgroundMinutes
            // A little slack so a job that runs slightly early still fetches.
            val maxAge = if (manual) 0L else minutes * MINUTE - 90 * SECOND
            val result = repository.refresh(maxAge, manual)
            Log.i(TAG, "Refresh (${if (manual) "manual" else if (chain) "chain" else "periodic"}): $result")
            if (result != dev.claudeusage.data.RefreshResult.UPDATED && result != dev.claudeusage.data.RefreshResult.FAILED) {
                // Nothing changed, but the clock moved on (resets, staleness).
                repository.notifyChanged()
            }
        } finally {
            if (manual && Widgets.refreshing) {
                Widgets.refreshing = false
                Widgets.updateAll(applicationContext)
            }
            if (chain) Scheduler.enqueueChain(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
            else if (!manual) Scheduler.enqueueChain(applicationContext, ExistingWorkPolicy.KEEP)
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "RefreshWorker"
        const val KEY_MANUAL = "manual"
        const val KEY_CHAIN = "chain"
    }
}

/** Fired by [Scheduler.scheduleRedraw]: redraw (and maybe notify) without fetching. */
class RedrawReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Graph.appScope.launch {
            try {
                Graph.repository.notifyChanged()
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * System events that change what the widgets show without new data: the clock or time zone (reset times are
 * shown as clock times), the language, a reboot (alarms are gone), an app update.
 */
class SystemEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_LOCALE_CHANGED -> Unit
            else -> return
        }
        val pending = goAsync()
        Graph.appScope.launch {
            try {
                Scheduler.ensure(context)
                Graph.repository.notifyChanged()
            } finally {
                pending.finish()
            }
        }
    }
}
