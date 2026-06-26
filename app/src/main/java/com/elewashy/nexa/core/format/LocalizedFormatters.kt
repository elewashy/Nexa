package com.elewashy.nexa.core.format

import android.content.Context
import android.text.format.Formatter
import com.elewashy.nexa.R
import java.text.NumberFormat

object LocalizedFormatters {
    fun fileSize(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

    fun speed(context: Context, bytesPerSecond: Long): String {
        if (bytesPerSecond <= 0) return ""
        return context.getString(R.string.file_size_per_second, fileSize(context, bytesPerSecond))
    }

    fun percent(context: Context, percent: Int): String {
        val locale = context.resources.configuration.locales[0]
        return NumberFormat.getPercentInstance(locale).format(percent / 100.0)
    }

    fun progressSize(context: Context, downloadedBytes: Long, totalBytes: Long): String {
        return context.getString(
            R.string.download_progress_size,
            fileSize(context, downloadedBytes),
            fileSize(context, totalBytes),
        )
    }

    fun updateProgress(context: Context, downloadedBytes: Long, totalBytes: Long, percent: Int): String {
        return context.getString(
            R.string.update_download_progress,
            fileSize(context, downloadedBytes),
            fileSize(context, totalBytes),
            percent(context, percent),
        )
    }

    fun eta(context: Context, seconds: Long): String {
        if (seconds < 0) return ""
        if (seconds == 0L) return context.getString(R.string.eta_less_than_second)

        return when {
            seconds < 60 -> context.resources.getQuantityString(R.plurals.eta_seconds_left, seconds.toInt(), seconds)
            seconds < 3600 -> {
                val minutes = ceilDiv(seconds, 60).toInt()
                context.resources.getQuantityString(R.plurals.eta_minutes_left, minutes, minutes)
            }
            else -> {
                val hours = ceilDiv(seconds, 3600).toInt()
                context.resources.getQuantityString(R.plurals.eta_hours_left, hours, hours)
            }
        }
    }

    private fun ceilDiv(value: Long, divisor: Long): Long = (value + divisor - 1) / divisor
}
