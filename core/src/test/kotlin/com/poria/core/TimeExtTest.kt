package com.poria.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class TimeExtTest {

    private val zone: ZoneId = ZoneId.systemDefault()

    private fun millisOf(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `周一对应 1，周日对应 7`() {
        // 2024-01-01 是周一，2024-01-07 是周日
        assertEquals(1, millisOf(2024, 1, 1, 12).toDayOfWeek(zone))
        assertEquals(7, millisOf(2024, 1, 7, 12).toDayOfWeek(zone))
    }

    @Test
    fun `时间戳转 DateFormat 各字段正确`() {
        val format = millisOf(2024, 3, 9, 18, 45).toDateFormat(zone)
        assertEquals(DateFormat(2024, 3, 9, 18, 45, 0), format)
    }

    @Test
    fun `DateFormat 转回时间戳是幂等的`() {
        val millis = millisOf(2024, 12, 31, 23, 59)
        assertEquals(millis, millis.toDateFormat(zone).toTimeStamp(zone))
    }

    @Test
    fun `毫秒位被抹掉`() {
        val withMillis = millisOf(2024, 5, 6, 7, 8) + 999
        assertEquals(millisOf(2024, 5, 6, 7, 8), withMillis.toDateFormat(zone).toTimeStamp(zone))
    }

    @Test
    fun `toString 按是否带时分秒切换格式`() {
        assertEquals("2024-1-5", DateFormat(2024, 1, 5).toString())
        assertEquals("2024-1-5 8:30", DateFormat(2024, 1, 5, 8, 30).toString())
        assertEquals("2024-1-5 8:30:15", DateFormat(2024, 1, 5, 8, 30, 15).toString())
    }

    @Test
    fun `相等对象 hash 相同，不等对象能共存于 HashSet`() {
        val a = DateFormat(2024, 1, 5)
        val b = DateFormat(2024, 1, 5)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, DateFormat(2024, 2, 5))

        // 回归测试：手写 hashCode 曾经返回 javaClass.hashCode()，
        // 导致所有实例 hash 相同、HashSet 里退化成链表。
        val distinctDays = (1..28).mapTo(HashSet()) { DateFormat(2024, 1, it) }
        assertEquals(28, distinctDays.size)
    }
}
