package com.resourcetracker.domain

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** The default ("6pm") reminder time, and formatting helpers for any time. */
object ReminderTime {
    const val HOUR = 18
    const val MINUTE = 0

    /** Minutes since midnight for the default 6pm reminder (18 * 60). */
    const val DEFAULT_MINUTES = HOUR * 60 + MINUTE

    val localTime: LocalTime = LocalTime.of(HOUR, MINUTE)

    /** Converts storage format (minutes since midnight) back to a [LocalTime]. */
    fun localTimeFor(minutesSinceMidnight: Int): LocalTime =
        LocalTime.of(minutesSinceMidnight / 60, minutesSinceMidnight % 60)

    /**
     * Human-readable reminder time, e.g. "6:00 PM" or "18:00" depending on locale.
     *
     * A getter, not a val, and localised rather than a hard-coded "h:mm a" pattern:
     * a value computed once into a static field would keep the locale that was active
     * at class-load time, so a user who changes their phone's language would keep
     * seeing the old formatting until the process restarted.
     */
    val label: String
        get() = localTime.format(
            DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault()),
        )

    /** Same as [label], for a stored minute-of-day value. */
    fun labelFor(minutesSinceMidnight: Int): String =
        localTimeFor(minutesSinceMidnight).format(
            DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault()),
        )
}
