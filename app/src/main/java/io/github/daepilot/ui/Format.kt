package io.github.daepilot.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Format {
    private val dateTime = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.getDefault())

    fun bytes(value: Long): String {
        if (value <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var size = value.toDouble()
        var index = 0
        while (size >= 1024 && index < units.lastIndex) {
            size /= 1024
            index++
        }
        return if (index == 0) {
            "${value} B"
        } else {
            String.format(Locale.getDefault(), "%.1f %s", size, units[index])
        }
    }

    fun relativeTime(epochMs: Long): String {
        if (epochMs <= 0) return "从未"
        val diff = System.currentTimeMillis() - epochMs
        return when {
            diff < 60_000 -> "刚刚"
            diff < 3_600_000 -> "${diff / 60_000} 分钟前"
            diff < 86_400_000 -> "${diff / 3_600_000} 小时前"
            diff < 7 * 86_400_000L -> "${diff / 86_400_000} 天前"
            else -> dateTime.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
        }
    }

    fun expiry(epochSeconds: Long): String {
        if (epochSeconds <= 0) return "未知"
        return dateTime.format(
            Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()),
        )
    }

    fun memory(kb: Long): String = if (kb <= 0) "—" else bytes(kb * 1024)

    fun mbps(value: Double?): String =
        if (value == null || value <= 0 || !value.isFinite()) {
            "—"
        } else {
            String.format(Locale.getDefault(), "%.1f", value)
        }

    fun percent(value: Double?): String =
        if (value == null || value < 0 || !value.isFinite()) {
            "—"
        } else {
            String.format(Locale.getDefault(), "%.1f%%", value)
        }
}
