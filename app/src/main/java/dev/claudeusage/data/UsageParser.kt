package dev.claudeusage.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class UnexpectedResponseException : Exception("Unexpected response")

/** Parses the responses of `api/oauth/usage` and `api/oauth/profile`. Lenient: unknown fields are ignored. */
object UsageParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Weekly limits of single models, in the order Anthropic lists them. */
    private val modelWeeklyFields = listOf("seven_day_sonnet" to "Sonnet", "seven_day_opus" to "Opus")

    fun parseUsage(body: String, now: Long): UsageSnapshot {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: throw UnexpectedResponseException()
        val limits = (root["limits"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

        // Newer response shape: `limits: [{ kind: "session" | "weekly_all" | "weekly_scoped", percent, resets_at }]`.
        fun fromLimits(kind: String): UsageWindow? {
            val limit = limits.firstOrNull { it.string("kind") == kind } ?: return null
            val percent = limit.double("percent") ?: return null
            return UsageWindow(percent, parseDate(limit["resets_at"]))
        }

        val session = window(root["five_hour"]) ?: fromLimits("session")
        val weekly = window(root["seven_day"]) ?: fromLimits("weekly_all")

        val extras = mutableListOf<ExtraLimit>()
        for ((field, name) in modelWeeklyFields) {
            window(root[field])?.let { extras += ExtraLimit(field, name, it) }
        }
        for (limit in limits) {
            if (limit.string("kind") != "weekly_scoped") continue
            val name = ((limit["scope"] as? JsonObject)?.get("model") as? JsonObject)?.string("display_name")
                ?: continue
            val percent = limit.double("percent") ?: continue
            if (extras.any { it.name.equals(name, ignoreCase = true) }) continue
            extras += ExtraLimit("model:$name", name, UsageWindow(percent, parseDate(limit["resets_at"])))
        }

        val extraUsage = (root["extra_usage"] as? JsonObject)
            ?.takeIf { (it["is_enabled"] as? JsonPrimitive)?.booleanOrNull == true }
            ?.let { ExtraUsage(it.double("utilization"), it.double("used_credits"), it.double("monthly_limit")) }

        if (session == null && weekly == null && extras.isEmpty() && root.isEmpty()) throw UnexpectedResponseException()
        return UsageSnapshot(session, weekly, extras, extraUsage, fetchedAt = now)
    }

    fun parseProfile(body: String, now: Long): Profile {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: throw UnexpectedResponseException()
        val account = root["account"] as? JsonObject
        val organization = root["organization"] as? JsonObject
        var type = organization?.string("organization_type")
        if (type == null && account != null) {
            type = when {
                (account["has_claude_max"] as? JsonPrimitive)?.booleanOrNull == true -> "claude_max"
                (account["has_claude_pro"] as? JsonPrimitive)?.booleanOrNull == true -> "claude_pro"
                else -> null
            }
        }
        return Profile(
            email = account?.string("email") ?: account?.string("email_address"),
            name = account?.string("display_name") ?: account?.string("full_name"),
            organizationType = type,
            rateLimitTier = organization?.string("rate_limit_tier"),
            fetchedAt = now,
        )
    }

    private fun window(element: JsonElement?): UsageWindow? {
        val obj = element as? JsonObject ?: return null
        val utilization = obj.double("utilization") ?: return null
        return UsageWindow(utilization, parseDate(obj["resets_at"]))
    }

    /** ISO 8601 with any number of fractional digits (`2026-06-11T18:00:00.308440+00:00`), or epoch seconds. */
    fun parseDate(element: JsonElement?): Long? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        if (!primitive.isString) {
            val seconds = primitive.doubleOrNull ?: return null
            return (seconds * 1000).toLong()
        }
        return parseIsoDate(primitive.content)
    }

    fun parseIsoDate(raw: String): Long? {
        if (raw.isBlank()) return null
        return runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull()
    }

    /** `Retry-After` is either delta-seconds or an HTTP date. Returns millis to wait. */
    fun parseRetryAfter(value: String?, now: Long): Long? {
        val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        v.toDoubleOrNull()?.let { return maxOf(0L, (it * 1000).toLong()) }
        return runCatching {
            val date = ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            maxOf(0L, date - now)
        }.getOrNull()
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }

    private fun JsonObject.double(key: String): Double? {
        val p = this[key] as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        return p.doubleOrNull ?: p.longOrNull?.toDouble()
    }
}
