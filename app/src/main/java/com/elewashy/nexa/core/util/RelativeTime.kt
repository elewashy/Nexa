package com.elewashy.nexa.core.util

import android.content.Context
import com.elewashy.nexa.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

fun Instant.relativeTime(context: Context): String {
    return try {
        val now = Instant.now()
        val minutes = ChronoUnit.MINUTES.between(this, now)
        val hours = ChronoUnit.HOURS.between(this, now)
        val days = ChronoUnit.DAYS.between(this, now)

        when {
            minutes < 1 -> context.getString(R.string.just_now)
            minutes < 60 -> context.getString(R.string.minutes_ago, minutes.toString())
            hours < 24 -> context.getString(R.string.hours_ago, hours.toString())
            days < 30 -> context.getString(R.string.days_ago, days.toString())
            else -> {
                val date = this.atZone(ZoneId.systemDefault()).toLocalDate()
                val currentYear = LocalDate.now().year
                val pattern = if (date.year != currentYear) "MMM d, yyyy" else "MMM d"
                date.format(DateTimeFormatter.ofPattern(pattern))
            }
        }
    } catch (e: Exception) {
        context.getString(R.string.invalid_date)
    }
}
