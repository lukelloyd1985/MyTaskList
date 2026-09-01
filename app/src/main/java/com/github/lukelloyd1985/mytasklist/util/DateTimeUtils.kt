package com.github.lukelloyd1985.mytasklist.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object DateTimeUtils {
    private val dateTimeFormat = SimpleDateFormat("EEE, d MMM · HH:mm", Locale.getDefault())
    private val dateOnlyFormat = SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault())
    private val timeOnlyFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun formatDueDate(date: Date): String = dateTimeFormat.format(date)

    fun formatDatePart(date: Date): String = dateOnlyFormat.format(date)

    fun formatTimePart(date: Date): String = timeOnlyFormat.format(date)

    fun isOverdue(date: Date, now: Date = Date()): Boolean = date.before(now)

    fun isToday(date: Date, now: Date = Date()): Boolean {
        val a = Calendar.getInstance().apply { time = date }
        val b = Calendar.getInstance().apply { time = now }
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    }

    fun combine(dateMillis: Long, hour: Int, minute: Int): Date {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = dateMillis
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return calendar.time
    }
}
