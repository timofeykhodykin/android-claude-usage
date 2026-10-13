package dev.claudeusage.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val SECOND = 1_000L
const val MINUTE = 60 * SECOND
const val HOUR = 60 * MINUTE
const val DAY = 24 * HOUR

/** The two limits every plan has, for code that treats them alike. */
enum class Metric(val windowLength: Long) {
    SESSION(5 * HOUR),
    WEEKLY(7 * DAY),
}

@Serializable
data class UsageWindow(
    /** 0…100 */
    val utilization: Double,
    /** Epoch millis; null while the window hasn't started (no message sent in it yet). */
    val resetsAt: Long? = null,
) {
    /** Once the reset time has passed the window starts over at 0, even if nothing could be fetched since. */
    fun hasReset(now: Long): Boolean = resetsAt != null && resetsAt <= now

    fun current(now: Long): Double = if (hasReset(now)) 0.0 else utilization
}

/** A limit besides session and week, such as the weekly limit of one model. */
@Serializable
data class ExtraLimit(
    /** Stable key: the response field (`seven_day_sonnet`) or `model:<name>` for scoped limits. */
    val id: String,
    /** Model name as Anthropic shows it ("Sonnet", "Opus"). */
    val name: String,
    val window: UsageWindow,
)

/** Pay-as-you-go usage beyond the plan, when turned on. */
@Serializable
data class ExtraUsage(
    val utilization: Double? = null,
    val usedCredits: Double? = null,
    val monthlyLimit: Double? = null,
)

@Serializable
data class UsageSnapshot(
    val session: UsageWindow? = null,
    val weekly: UsageWindow? = null,
    val extras: List<ExtraLimit> = emptyList(),
    val extraUsage: ExtraUsage? = null,
    /** When it was fetched, epoch millis. */
    val fetchedAt: Long,
) {
    fun window(metric: Metric): UsageWindow? = if (metric == Metric.SESSION) session else weekly
}

/**
 * One observation of both limits. Recorded whenever either changes, plus a heartbeat while nothing
 * changes, so a flat stretch can be told apart from a stretch nobody was watching (phone asleep).
 * Short keys: the history holds thousands of these.
 */
@Serializable
data class UsagePoint(
    @SerialName("t") val date: Long,
    @SerialName("s") val session: Double? = null,
    @SerialName("sr") val sessionResetsAt: Long? = null,
    @SerialName("w") val weekly: Double? = null,
    @SerialName("wr") val weeklyResetsAt: Long? = null,
) {
    constructor(snapshot: UsageSnapshot) : this(
        date = snapshot.fetchedAt,
        session = snapshot.session?.utilization, sessionResetsAt = snapshot.session?.resetsAt,
        weekly = snapshot.weekly?.utilization, weeklyResetsAt = snapshot.weekly?.resetsAt,
    )

    fun utilization(metric: Metric): Double? = if (metric == Metric.SESSION) session else weekly
    fun resetsAt(metric: Metric): Long? = if (metric == Metric.SESSION) sessionResetsAt else weeklyResetsAt
}

/** Account details from `/api/oauth/profile`, shown in the app and widget headers. */
@Serializable
data class Profile(
    val email: String? = null,
    val name: String? = null,
    /** `claude_max`, `claude_pro`, `claude_team`, `claude_enterprise`, … */
    val organizationType: String? = null,
    /** `default_claude_max_20x`, `default_claude_max_5x`, … */
    val rateLimitTier: String? = null,
    val fetchedAt: Long = 0,
) {
    /** "Max 20x", "Max 5x", "Pro", "Team", "Enterprise", or null when unknown. */
    val plan: String?
        get() {
            val type = organizationType?.lowercase() ?: return null
            val tier = rateLimitTier?.lowercase().orEmpty()
            return when {
                "max" in type -> when {
                    "20x" in tier -> "Max 20x"
                    "5x" in tier -> "Max 5x"
                    else -> "Max"
                }
                "pro" in type -> "Pro"
                "team" in type -> "Team"
                "enterprise" in type -> "Enterprise"
                "free" in type -> "Free"
                else -> null
            }
        }
}
