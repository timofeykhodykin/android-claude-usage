package dev.claudeusage.data

import android.content.Context
import android.util.Log
import dev.claudeusage.auth.TokenManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

data class UsageState(
    val snapshot: UsageSnapshot? = null,
    val history: List<UsagePoint> = emptyList(),
    val profile: Profile? = null,
    val refreshing: Boolean = false,
    /** What went wrong with the last attempt; cleared by the next success. */
    val error: ErrorKind? = null,
    val errorStatus: Int = 0,
    /** While rate limited, automatic refreshes pause until then. */
    val backoffUntil: Long? = null,
    val lastAttempt: Long? = null,
) {
    fun dataAge(now: Long): Long = snapshot?.let { now - it.fetchedAt } ?: Long.MAX_VALUE

    fun backingOff(now: Long): Boolean = backoffUntil?.let { it > now } ?: false

    fun sessionsEstimate(now: Long): SessionsEstimate? =
        UsageHistory.sessionsEstimate(history, snapshot?.weekly?.current(now), now)
}

enum class RefreshResult { UPDATED, FRESH_ENOUGH, SKIPPED, FAILED }

/**
 * The usage data and the one place that fetches it. The usage endpoint is rate-limited hard (about 30 calls in
 * 15 minutes, then 429 for a long while), so every caller states how old the data may be, refreshes are
 * serialized, and after a 429 automatic refreshes back off exponentially (manual ones at most once a minute).
 */
class UsageRepository(context: Context, private val tokens: TokenManager) {
    private val api = UsageApi(tokens)
    private val prefs = context.getSharedPreferences("usage", Context.MODE_PRIVATE)
    private val historyFile = File(context.filesDir, "usage-history.jsonl")
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val mutex = Mutex()

    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<UsageState> = _state.asStateFlow()

    /** Called after every change of the data (widgets, notifications, reset alarms). */
    var onChanged: (suspend (previous: UsageState, current: UsageState) -> Unit)? = null

    private fun loadState(): UsageState {
        fun <T> decode(key: String, serializer: kotlinx.serialization.KSerializer<T>): T? =
            prefs.getString(key, null)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
        val now = System.currentTimeMillis()
        return UsageState(
            snapshot = decode(KEY_SNAPSHOT, UsageSnapshot.serializer()),
            history = loadHistory(now),
            profile = decode(KEY_PROFILE, Profile.serializer()),
            error = prefs.getString(KEY_ERROR, null)?.let { runCatching { ErrorKind.valueOf(it) }.getOrNull() },
            errorStatus = prefs.getInt(KEY_ERROR_STATUS, 0),
            backoffUntil = prefs.getLong(KEY_BACKOFF, 0).takeIf { it > 0 },
            lastAttempt = prefs.getLong(KEY_LAST_ATTEMPT, 0).takeIf { it > 0 },
        )
    }

    /**
     * Fetches unless the data is younger than [maxAge]. [manual] (a tap) may break through a backoff, but not
     * more than once a minute: hammering a rate-limited endpoint only extends the limit.
     */
    suspend fun refresh(maxAge: Long, manual: Boolean = false): RefreshResult = mutex.withLock {
        if (!tokens.isSignedIn) return RefreshResult.SKIPPED
        val now = System.currentTimeMillis()
        val current = _state.value
        if (current.dataAge(now) < maxAge && current.error == null) return RefreshResult.FRESH_ENOUGH
        val sinceLast = current.lastAttempt?.let { now - it } ?: Long.MAX_VALUE
        if (current.backingOff(now) && (!manual || sinceLast < MIN_MANUAL_SPACING)) {
            Log.i(TAG, "Refresh skipped: rate limited until ${current.backoffUntil}")
            return RefreshResult.SKIPPED
        }
        // A double tap or several widgets at once: one request is enough.
        if (sinceLast < MIN_SPACING && current.error == null) return RefreshResult.FRESH_ENOUGH

        val previous = current
        _state.update { it.copy(refreshing = true, lastAttempt = now) }
        prefs.edit().putLong(KEY_LAST_ATTEMPT, now).apply()

        val result = try {
            val fresh = withContext(Dispatchers.IO) { api.fetchUsage(now) }
            adopt(fresh)
            prefs.edit().remove(KEY_ERROR).remove(KEY_ERROR_STATUS).remove(KEY_BACKOFF).putInt(KEY_RATE_LIMITS, 0).apply()
            _state.update { it.copy(error = null, errorStatus = 0, backoffUntil = null) }
            Log.i(TAG, "Fetched usage: session ${fresh.session?.utilization}%, weekly ${fresh.weekly?.utilization}%")
            refreshProfileIfStale(now)
            RefreshResult.UPDATED
        } catch (e: UsageException) {
            Log.w(TAG, "Fetch failed: ${e.message}")
            var backoff: Long? = _state.value.backoffUntil
            if (e.kind == ErrorKind.RATE_LIMITED) {
                // Exponential (2×, 4×, 8× the base…), never shorter than Retry-After, capped at 30 minutes.
                // Manual taps don't escalate it; only the automatic schedule does.
                var count = prefs.getInt(KEY_RATE_LIMITS, 0)
                if (!manual || count == 0) count++
                val exponential = (BACKOFF_BASE * 2.0.pow(count)).toLong()
                val delay = min(MAX_BACKOFF, max(max(e.retryAfter ?: 0, exponential), MINUTE))
                backoff = now + delay
                prefs.edit().putInt(KEY_RATE_LIMITS, count).apply()
                Log.i(TAG, "Rate limited $count× in a row; pausing ${delay / 1000}s")
            }
            prefs.edit().putString(KEY_ERROR, e.kind.name).putInt(KEY_ERROR_STATUS, e.status)
                .putLong(KEY_BACKOFF, backoff ?: 0).apply()
            _state.update { it.copy(error = e.kind, errorStatus = e.status, backoffUntil = backoff) }
            RefreshResult.FAILED
        } finally {
            _state.update { it.copy(refreshing = false) }
        }
        onChanged?.invoke(previous, _state.value)
        result
    }

    private suspend fun refreshProfileIfStale(now: Long) {
        val profile = _state.value.profile
        if (profile != null && now - profile.fetchedAt < PROFILE_MAX_AGE) return
        try {
            val fresh = withContext(Dispatchers.IO) { api.fetchProfile(now) }
            prefs.edit().putString(KEY_PROFILE, json.encodeToString(Profile.serializer(), fresh)).apply()
            _state.update { it.copy(profile = fresh) }
        } catch (e: UsageException) {
            Log.w(TAG, "Profile fetch failed: ${e.message}")
        }
    }

    /** Takes a newer snapshot, keeping windows the response left out. */
    private fun adopt(incoming: UsageSnapshot) {
        val old = _state.value.snapshot
        val merged = incoming.copy(
            session = incoming.session ?: old?.session,
            weekly = incoming.weekly ?: old?.weekly,
        )
        prefs.edit().putString(KEY_SNAPSHOT, json.encodeToString(UsageSnapshot.serializer(), merged)).apply()
        _state.update { it.copy(snapshot = merged, history = record(it.history, merged)) }
    }

    /** Appends to the history when something changed (or as a heartbeat). */
    private fun record(history: List<UsagePoint>, snapshot: UsageSnapshot): List<UsagePoint> {
        val point = UsagePoint(snapshot)
        var list = history
        if (list.isNotEmpty() && list.last().date > point.date + HOUR) {
            // The clock jumped back: entries "from the future" would block recording.
            list = UsageHistory.pruned(list, point.date)
            rewriteHistory(list)
        }
        if (!UsageHistory.shouldRecord(point, list.lastOrNull())) return list
        list = list + point
        runCatching { historyFile.appendText(UsageHistory.encode(point) + "\n") }
        // Drop old entries a day's worth at a time.
        if (point.date - list.first().date > UsageHistory.RETENTION + DAY) {
            list = UsageHistory.pruned(list, point.date)
            rewriteHistory(list)
        }
        return list
    }

    private fun loadHistory(now: Long): List<UsagePoint> {
        val all = runCatching { UsageHistory.decode(historyFile.readText()) }.getOrDefault(emptyList())
        val kept = UsageHistory.pruned(all, now)
        if (kept.size != all.size) rewriteHistory(kept)
        return kept
    }

    private fun rewriteHistory(points: List<UsagePoint>) {
        val tmp = File(historyFile.parentFile, historyFile.name + ".tmp")
        runCatching {
            tmp.writeText(points.joinToString("") { UsageHistory.encode(it) + "\n" })
            tmp.renameTo(historyFile)
        }
    }

    /** Forgets everything about the account (signing out, or another account signing in). */
    suspend fun clear() = mutex.withLock {
        val previous = _state.value
        prefs.edit().clear().apply()
        historyFile.delete()
        _state.value = UsageState()
        onChanged?.invoke(previous, _state.value)
    }

    /** Debug builds only: shows made-up data, for trying the UI and widgets without an account. */
    suspend fun loadDemo(demo: UsageState) {
        check(dev.claudeusage.BuildConfig.DEBUG)
        val previous = _state.value
        _state.value = demo
        onChanged?.invoke(previous, demo)
    }

    /** Re-renders everything that depends on the data (the clock moved on, settings changed). */
    suspend fun notifyChanged() {
        val current = _state.value
        onChanged?.invoke(current, current)
    }

    companion object {
        private const val TAG = "UsageRepository"
        private const val KEY_SNAPSHOT = "snapshot"
        private const val KEY_PROFILE = "profile"
        private const val KEY_ERROR = "error"
        private const val KEY_ERROR_STATUS = "errorStatus"
        private const val KEY_BACKOFF = "backoffUntil"
        private const val KEY_LAST_ATTEMPT = "lastAttempt"
        private const val KEY_RATE_LIMITS = "rateLimits"

        const val MIN_MANUAL_SPACING = MINUTE
        const val MIN_SPACING = 15 * SECOND
        const val BACKOFF_BASE = 2 * MINUTE
        const val MAX_BACKOFF = 30 * MINUTE
        const val PROFILE_MAX_AGE = 12 * HOUR
    }
}
