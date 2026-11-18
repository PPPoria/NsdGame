package com.poria.core

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 时间戳（毫秒）→ [DateFormat]。
 *
 * 默认用设备时区；需要确定性结果时（测试、跨时区同步）显式传 [zone]。
 */
fun Long.toDateFormat(zone: ZoneId = ZoneId.systemDefault()): DateFormat {
    val time = Instant.ofEpochMilli(this).atZone(zone).toLocalDateTime()
    return DateFormat(
        year = time.year,
        month = time.monthValue,
        day = time.dayOfMonth,
        hour = time.hour,
        minute = time.minute,
        second = time.second
    )
}

/**
 * 时间戳（毫秒）→ 星期几，周一 = 1 … 周日 = 7（符合中文习惯）。
 */
fun Long.toDayOfWeek(zone: ZoneId = ZoneId.systemDefault()): Int =
    Instant.ofEpochMilli(this).atZone(zone).dayOfWeek.value

/**
 * 年月日时分秒的轻量值对象。
 *
 * 有意使用 `var`：日历类 UI 经常原地改字段。`equals`/`hashCode` 由 data class 自动生成，
 * 不要再手写（手写 hashCode 返回类对象 hash 会导致放进 HashMap 时全部退化成链表）。
 */
data class DateFormat(
    var year: Int,
    var month: Int,
    var day: Int,
    var hour: Int = 0,
    var minute: Int = 0,
    var second: Int = 0
) {
    /**
     * 转回时间戳（毫秒）。毫秒位固定为 0。
     */
    fun toTimeStamp(zone: ZoneId = ZoneId.systemDefault()): Long =
        LocalDateTime.of(year, month, day, hour, minute, second)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    /** `2024-1-5` / `2024-1-5 8:30` / `2024-1-5 8:30:15` */
    override fun toString(): String = buildString {
        append(year).append('-').append(month).append('-').append(day)
        if (hour == 0 && minute == 0 && second == 0) return@buildString
        append(' ').append(hour).append(':').append(minute)
        if (second != 0) append(':').append(second)
    }
}
