package com.kernel.ai.core.skills.natives

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import com.kernel.ai.core.memory.ImportantDateRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CalendarBirthdayLookup @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    data class BirthdayEntry(
        val label: String,
        val normalizedLabel: String,
        val month: Int,
        val day: Int,
    )

    @Volatile
    private var cachedBirthdays: List<BirthdayEntry>? = null

    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun findBirthday(label: String): BirthdayEntry? {
        if (!hasPermission()) return null
        val normalizedQuery = normalizeBirthdayLookupLabel(label)
        if (normalizedQuery.isBlank()) return null
        val birthdays = loadBirthdays()
        birthdays.firstOrNull { it.normalizedLabel == normalizedQuery }?.let { return it }

        val partialMatches = birthdays.filter { labelsPotentiallyMatch(it.label, label) }
        return partialMatches.singleOrNull()
    }

    fun getAllBirthdays(): List<BirthdayEntry> {
        if (!hasPermission()) return emptyList()
        return loadBirthdays()
    }

    fun invalidateCache() {
        cachedBirthdays = null
    }

    private fun loadBirthdays(): List<BirthdayEntry> {
        cachedBirthdays?.let { return it }
        return synchronized(this) {
            cachedBirthdays ?: queryBirthdays().also { cachedBirthdays = it }
        }
    }

    private fun queryBirthdays(): List<BirthdayEntry> {
        val projection = arrayOf(
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.CALENDAR_DISPLAY_NAME,
            CalendarContract.Events.ACCOUNT_TYPE,
        )
        val selection = "${CalendarContract.Events.DELETED} = 0 AND ${CalendarContract.Events.TITLE} IS NOT NULL"
        val results = linkedMapOf<String, BirthdayEntry>()

        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection,
            selection,
            null,
            null,
        )?.use { cursor ->
            val titleIndex = cursor.getColumnIndexOrThrow(CalendarContract.Events.TITLE)
            val dtStartIndex = cursor.getColumnIndexOrThrow(CalendarContract.Events.DTSTART)
            val allDayIndex = cursor.getColumnIndexOrThrow(CalendarContract.Events.ALL_DAY)
            val displayNameIndex = cursor.getColumnIndexOrThrow(CalendarContract.Events.CALENDAR_DISPLAY_NAME)
            val accountTypeIndex = cursor.getColumnIndexOrThrow(CalendarContract.Events.ACCOUNT_TYPE)

            while (cursor.moveToNext()) {
                val title = cursor.getString(titleIndex) ?: continue
                val calendarDisplayName = cursor.getString(displayNameIndex).orEmpty()
                val accountType = cursor.getString(accountTypeIndex).orEmpty()
                if (!looksLikeBirthdaySource(calendarDisplayName, accountType, title)) continue

                val normalizedTitle = normalizeBirthdayTitle(title) ?: continue
                val startMillis = cursor.getLong(dtStartIndex)
                val isAllDay = cursor.getInt(allDayIndex) != 0
                val date = toLocalDate(startMillis, isAllDay)
                results.putIfAbsent(
                    normalizedTitle,
                    BirthdayEntry(
                        label = normalizedTitle,
                        normalizedLabel = normalizeBirthdayLookupLabel(normalizedTitle),
                        month = date.monthValue,
                        day = date.dayOfMonth,
                    ),
                )
            }
        }
        return results.values.toList()
    }

    private fun looksLikeBirthdaySource(calendarDisplayName: String, accountType: String, title: String): Boolean {
        val displayNameLower = calendarDisplayName.lowercase(Locale.ENGLISH)
        val titleLower = title.lowercase(Locale.ENGLISH)
        return displayNameLower.contains("birthday") ||
            titleLower.contains("birthday") ||
            (accountType == "com.google" && titleLower.contains("birthday"))
    }

    private fun normalizeBirthdayTitle(raw: String): String? {
        val trimmed = raw.trim()
        val birthdaySuffix = Regex("""(?i)^(.+?)'?s\s+birthday$|^birthday\s*:?\s+(.+)$|^(.+?)\s+birthday$""")
        val match = birthdaySuffix.matchEntire(trimmed) ?: return null
        val label = match.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return label.takeIf { it.isNotBlank() }
    }

    companion object {
        fun labelsPotentiallyMatch(left: String, right: String): Boolean {
            val leftTokens = matchTokens(left)
            val rightTokens = matchTokens(right)
            if (leftTokens.isEmpty() || rightTokens.isEmpty()) return false
            return containsAllEquivalent(leftTokens, rightTokens) || containsAllEquivalent(rightTokens, leftTokens)
        }

        private fun containsAllEquivalent(container: List<String>, query: List<String>): Boolean =
            query.all { queryToken -> container.any { containerToken -> tokensEquivalent(containerToken, queryToken) } }

        private fun tokensEquivalent(left: String, right: String): Boolean {
            if (left == right) return true
            return tokenVariants(left).intersect(tokenVariants(right)).isNotEmpty()
        }

        private fun tokenVariants(token: String): Set<String> {
            val trimmed = token.trim().lowercase(Locale.ENGLISH)
            if (trimmed.isBlank()) return emptySet()

            val variants = linkedSetOf(trimmed)
            if (trimmed.endsWith("s") && trimmed.length > 3) {
                variants += trimmed.dropLast(1)
            }
            return variants
        }

        private fun matchTokens(raw: String): List<String> =
            normalizeBirthdayLookupLabel(raw).split(" ").filter { it.isNotBlank() }

        private fun normalizeBirthdayLookupLabel(raw: String): String = ImportantDateRepository.normalizeLabel(
            raw.replace(Regex("""\bbirthday\b""", RegexOption.IGNORE_CASE), "").trim(),
        )
    }

    private fun toLocalDate(startMillis: Long, isAllDay: Boolean): LocalDate {
        val zone = if (isAllDay) ZoneId.of("UTC") else ZoneId.systemDefault()
        return Instant.ofEpochMilli(startMillis).atZone(zone).toLocalDate()
    }
}
