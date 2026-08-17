package com.swmansion.moqkit.publish.source

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 2026-08-14 QA D-06: pins the pure effect-attachment policy. The effects
 * themselves need a live capture session (device test); this host-JVM suite
 * pins the DECISION so a default flip or an availability-guard drop cannot
 * ship silently.
 */
class MicrophoneCaptureEffectsPlanTest {

    private fun plan(
        ns: Boolean = MicrophoneCapture.DEFAULT_ENABLE_NOISE_SUPPRESSOR,
        agc: Boolean = MicrophoneCapture.DEFAULT_ENABLE_AUTOMATIC_GAIN_CONTROL,
        aec: Boolean = MicrophoneCapture.DEFAULT_ENABLE_ACOUSTIC_ECHO_CANCELER,
        nsAvail: Boolean = true,
        agcAvail: Boolean = true,
        aecAvail: Boolean = true,
    ) = MicrophoneCapture.planEffects(
        enableNoiseSuppressor = ns,
        enableAutomaticGainControl = agc,
        enableAcousticEchoCanceler = aec,
        noiseSuppressorAvailable = nsAvail,
        automaticGainControlAvailable = agcAvail,
        acousticEchoCancelerAvailable = aecAvail,
    )

    @Test
    fun `a default-constructed capture carries the D-06 contract`() {
        // 리뷰 R6: 상수 값 핀만으론 생성자 기본값이 상수 참조를 유지하는지
        // 보증 못 한다 — 기본값을 리터럴로 재인라인해도 전 스위트가 그린으로
        // 통과했다(유일 독자 attachEffects=호스트-JVM 도달불가). 실제
        // attachEffects가 지나가는 인스턴스 시임으로 생성 결과를 직접 핀.
        assertEquals(
            MicrophoneCapture.EffectPlan(
                noiseSuppressor = true,
                automaticGainControl = true,
                acousticEchoCanceler = false,
            ),
            MicrophoneCapture().configuredEffectPlan(
                noiseSuppressorAvailable = true,
                automaticGainControlAvailable = true,
                acousticEchoCancelerAvailable = true,
            ),
        )
    }

    @Test
    fun `the D-06 default contract is NS on, AGC on, AEC off`() {
        // 리뷰 R4: 생성자 기본값 자체를 관측하는 호스트-JVM 테스트가 없어
        // default flip이 그린으로 통과했다 — 기본값의 정본은 companion
        // 상수이고(생성자가 참조), 이 핀이 그 계약을 직접 고정한다.
        assertEquals(true, MicrophoneCapture.DEFAULT_ENABLE_NOISE_SUPPRESSOR)
        assertEquals(true, MicrophoneCapture.DEFAULT_ENABLE_AUTOMATIC_GAIN_CONTROL)
        assertEquals(false, MicrophoneCapture.DEFAULT_ENABLE_ACOUSTIC_ECHO_CANCELER)
    }

    @Test
    fun `defaults plan NS and AGC but never AEC`() {
        // 방송은 로컬 재생 레퍼런스가 없어 AEC 이득이 없고 아티팩트 위험만
        // 있다 — 기본 OFF가 계약이다.
        val p = plan()
        assertEquals(
            MicrophoneCapture.EffectPlan(
                noiseSuppressor = true,
                automaticGainControl = true,
                acousticEchoCanceler = false,
            ),
            p,
        )
    }

    @Test
    fun `an unavailable effect is dropped from the plan`() {
        val p = plan(nsAvail = false, agcAvail = false)
        assertEquals(
            MicrophoneCapture.EffectPlan(
                noiseSuppressor = false,
                automaticGainControl = false,
                acousticEchoCanceler = false,
            ),
            p,
        )
    }

    @Test
    fun `AEC attaches only when explicitly opted in AND available`() {
        assertEquals(
            true,
            plan(aec = true, aecAvail = true).acousticEchoCanceler,
        )
        assertEquals(
            false,
            plan(aec = true, aecAvail = false).acousticEchoCanceler,
        )
        assertEquals(
            false,
            plan(aec = false, aecAvail = true).acousticEchoCanceler,
        )
    }

    @Test
    fun `disabling a default keeps it out of the plan even when available`() {
        val p = plan(ns = false, agc = false)
        assertEquals(
            MicrophoneCapture.EffectPlan(
                noiseSuppressor = false,
                automaticGainControl = false,
                acousticEchoCanceler = false,
            ),
            p,
        )
    }
}
