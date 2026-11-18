package com.poria.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HashExtTest {

    @Test
    fun `scrambleHash 稳定`() {
        assertEquals("brick".scrambleHash(), "brick".scrambleHash())
        assertEquals(12345.scrambleHash(), 12345.scrambleHash())
    }

    @Test
    fun `scrambleHash 能区分相邻的小整数`() {
        val hashes = (0 until 64).map { it.scrambleHash() }.toSet()
        assertEquals(64, hashes.size)
    }

    @Test
    fun `scrambleHash 会打散 hashCode 的规律性`() {
        assertNotEquals(1.hashCode().toLong(), 1.scrambleHash().toLong())
    }
}
