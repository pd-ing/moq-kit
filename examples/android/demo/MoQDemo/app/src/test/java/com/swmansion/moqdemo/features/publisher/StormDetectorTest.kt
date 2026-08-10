package com.swmansion.moqdemo.features.publisher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StormDetectorTest {

    private fun detector() = StormDetector(shortLifetimeMs = 5_000L, threshold = 3)

    @Test
    fun `three consecutive short-lived publishes trip the storm`() {
        val d = detector()
        // 실기기 폭풍 프로파일: up 후 2.5~4.4s 사망이 연속된다.
        assertFalse(d.recordSessionEnd(publishUpAtMs = 10_000L, nowMs = 12_600L))
        assertFalse(d.recordSessionEnd(publishUpAtMs = 20_000L, nowMs = 24_400L))
        assertTrue(d.recordSessionEnd(publishUpAtMs = 30_000L, nowMs = 32_600L))
    }

    @Test
    fun `a long-lived publish resets the streak`() {
        val d = detector()
        assertFalse(d.recordSessionEnd(10_000L, 12_600L))
        assertFalse(d.recordSessionEnd(20_000L, 24_400L))
        // 5s 이상 생존 = 폭풍 프로파일 아님 → 스트릭 소거.
        assertFalse(d.recordSessionEnd(30_000L, 40_000L))
        assertEquals(0, d.consecutiveShortLived)
        assertFalse(d.recordSessionEnd(50_000L, 52_000L))
        assertEquals(1, d.consecutiveShortLived)
    }

    @Test
    fun `failed connect attempts carry no storm signal`() {
        val d = detector()
        assertFalse(d.recordSessionEnd(10_000L, 12_600L))
        // 실패한 attempt(up 없음)는 카운트를 바꾸지 않는다 — Wi-Fi 아웃리지 중
        // 연속 실패가 폭풍으로 오판되면 안 된다.
        repeat(10) { assertFalse(d.recordSessionEnd(publishUpAtMs = null, nowMs = 20_000L)) }
        assertEquals(1, d.consecutiveShortLived)
    }

    @Test
    fun `exactly at the lifetime boundary is not short-lived`() {
        val d = detector()
        assertFalse(d.recordSessionEnd(10_000L, 15_000L))
        assertEquals(0, d.consecutiveShortLived)
    }

    @Test
    fun `reset clears the streak`() {
        val d = detector()
        assertFalse(d.recordSessionEnd(10_000L, 12_000L))
        assertFalse(d.recordSessionEnd(20_000L, 22_000L))
        d.reset()
        assertEquals(0, d.consecutiveShortLived)
        assertFalse(d.recordSessionEnd(30_000L, 32_000L))
        assertEquals(1, d.consecutiveShortLived)
    }
}
