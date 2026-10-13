package dev.claudeusage.widget

import dev.claudeusage.auth.AuthState
import dev.claudeusage.data.DAY
import dev.claudeusage.data.HOUR
import dev.claudeusage.data.MINUTE
import dev.claudeusage.data.Profile
import dev.claudeusage.data.UsagePoint
import dev.claudeusage.data.UsageSnapshot
import dev.claudeusage.data.UsageState
import dev.claudeusage.data.UsageWindow

/** Made-up but plausible usage, for widget previews and the configuration screen before any data arrives. */
object SampleData {
    fun state(now: Long = System.currentTimeMillis()): UsageState {
        val sessionReset = now + 2 * HOUR + 35 * MINUTE
        val weekReset = now + 3 * DAY + 4 * HOUR
        val history = mutableListOf<UsagePoint>()
        // This week: a few sessions on earlier days, each costing ~13% of the week per full session.
        var weekly = 4.0
        var t = weekReset - 7 * DAY + 6 * HOUR
        while (t < now - 2 * HOUR - 30 * MINUTE) {
            val reset = t + 5 * HOUR
            var session = 0.0
            for (step in 0..18) {
                history += UsagePoint(t + step * 10 * MINUTE, session, reset, weekly, weekReset)
                session += 4.0
                weekly += 0.5
            }
            t += 22 * HOUR
        }
        // The current session: steady use for an hour and a half, a pause, then use again.
        val start = sessionReset - 5 * HOUR
        val values = listOf(0, 3, 7, 11, 14, 16, 18, 18, 18, 18, 20, 23, 26, 29, 31)
        values.forEachIndexed { i, v ->
            val at = start + 25 * MINUTE + i * 10 * MINUTE
            if (at < now) history += UsagePoint(at, v.toDouble(), sessionReset, 49.0 + v / 8.0, weekReset)
        }
        val snapshot = UsageSnapshot(
            session = UsageWindow(31.0, sessionReset),
            weekly = UsageWindow(53.0, weekReset),
            fetchedAt = now - 40 * 1000,
        )
        return UsageState(
            snapshot = snapshot,
            history = history.filter { it.date <= now }.sortedBy { it.date },
            profile = Profile(organizationType = "claude_max", rateLimitTier = "default_claude_max_5x", fetchedAt = now),
        )
    }

    fun widgetData(now: Long = System.currentTimeMillis()) =
        WidgetData(state(now), AuthState.SignedIn(null), now, refreshing = false, backgroundMinutes = 15)
}
