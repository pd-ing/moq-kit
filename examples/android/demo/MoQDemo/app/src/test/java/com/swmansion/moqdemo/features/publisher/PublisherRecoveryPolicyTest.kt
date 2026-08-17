package com.swmansion.moqdemo.features.publisher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-08-14 QA 수정 회귀 핀(호스트-JVM, ViewModel 인스턴스 불필요):
 * D-03 방향-불일치 게이트와 D-01/D-05 스톰-신호 결정은 순수 companion
 * 함수로 추출되어 여기서 직접 핀된다 — 리뷰 R1이 확인한 "콜러측 결정
 * 무커버" 갭의 봉인.
 */
class PublisherRecoveryPolicyTest {

    // ---- D-03: orientationMismatchMessage ----------------------------------

    @Test
    fun `portrait display over a landscape broadcast is blocked with the broadcast orientation named`() {
        val msg = PublisherViewModel.orientationMismatchMessage(
            publishedAspect = 1920f / 1080f,
            displayPortrait = true,
        )
        assertNotNull(msg)
        assertTrue(msg!!.contains("landscape"))
    }

    @Test
    fun `landscape display over a portrait broadcast is blocked with the broadcast orientation named`() {
        val msg = PublisherViewModel.orientationMismatchMessage(
            publishedAspect = 720f / 1280f,
            displayPortrait = false,
        )
        assertNotNull(msg)
        assertTrue(msg!!.contains("portrait"))
    }

    @Test
    fun `a matching orientation passes`() {
        assertNull(
            PublisherViewModel.orientationMismatchMessage(1920f / 1080f, displayPortrait = false),
        )
        assertNull(
            PublisherViewModel.orientationMismatchMessage(720f / 1280f, displayPortrait = true),
        )
    }

    @Test
    fun `no live broadcast aspect never blocks`() {
        assertNull(PublisherViewModel.orientationMismatchMessage(null, displayPortrait = true))
        assertNull(PublisherViewModel.orientationMismatchMessage(null, displayPortrait = false))
    }

    // ---- D-01/D-05: stormSignal --------------------------------------------

    @Test
    fun `a medium-lived signature death on a validated network is countable AND forced short`() {
        // 실기기 폭풍 레짐: 12-15s 수명 + short frame + 네트워크 정상.
        val s = PublisherViewModel.stormSignal(
            lastPublishUpAtMs = 10_000L,
            nowMs = 23_000L,
            networkUpAtDeath = true,
            corruptionSignature = true,
        )
        assertEquals(10_000L, s.countableUpAtMs)
        assertTrue(s.forceShortLived)
    }

    @Test
    fun `a long-lived CLEAN death is countable but not forced (it resets the streak)`() {
        val s = PublisherViewModel.stormSignal(
            lastPublishUpAtMs = 10_000L,
            nowMs = 40_000L,
            networkUpAtDeath = true,
            corruptionSignature = false,
        )
        assertEquals(10_000L, s.countableUpAtMs)
        assertFalse(s.forceShortLived)
    }

    @Test
    fun `a signature death on a DOWN network is never forced`() {
        // 플래핑 링크의 사망을 폭풍으로 오판해 건강한 앱 재시작을 지시하면
        // 안 된다 — networkUpAtDeath 가드가 유일한 방벽이다.
        val s = PublisherViewModel.stormSignal(
            lastPublishUpAtMs = 10_000L,
            nowMs = 23_000L,
            networkUpAtDeath = false,
            corruptionSignature = true,
        )
        assertFalse(s.forceShortLived)
        // 수명 >= STORM_SESSION_LIFETIME_MS 라 countable(스트릭 리셋 방향).
        assertEquals(10_000L, s.countableUpAtMs)
    }

    @Test
    fun `a short clean death is neutral (not countable)`() {
        val s = PublisherViewModel.stormSignal(
            lastPublishUpAtMs = 10_000L,
            nowMs = 12_000L,
            networkUpAtDeath = true,
            corruptionSignature = false,
        )
        assertNull(s.countableUpAtMs)
        assertFalse(s.forceShortLived)
    }

    @Test
    fun `a death without an up carries no signal`() {
        val s = PublisherViewModel.stormSignal(
            lastPublishUpAtMs = 0L,
            nowMs = 12_000L,
            networkUpAtDeath = true,
            corruptionSignature = true,
        )
        assertNull(s.countableUpAtMs)
    }

    @Test
    fun `a SHORT-lived signature death on a validated network is countable AND forced short`() {
        // 리뷰 R3: 고전 폭풍 프로파일(2.5-4.4s 사망)은 수명 절이 거짓이라
        // 시그니처 disjunct가 유일한 countable 경로다 — 이 disjunct를
        // "forceShortLived와 중복"으로 오판해 지우면 recordSessionEnd가
        // null no-op이 되어 폭풍이 영원히 안 잡힌다. 그 절삭이 여기서 죽는다.
        val s = PublisherViewModel.stormSignal(
            lastPublishUpAtMs = 10_000L,
            nowMs = 13_000L,
            networkUpAtDeath = true,
            corruptionSignature = true,
        )
        assertEquals(10_000L, s.countableUpAtMs)
        assertTrue(s.forceShortLived)
    }

    @Test
    fun `a SHORT-lived signature death on a DOWN network stays neutral`() {
        // 시그니처 disjunct는 network AND signature여야 한다 — 어느 한쪽만으로
        // countable이 되면 플래핑 링크의 단명 사망이 스트릭을 쌓는다.
        val s = PublisherViewModel.stormSignal(
            lastPublishUpAtMs = 10_000L,
            nowMs = 13_000L,
            networkUpAtDeath = false,
            corruptionSignature = true,
        )
        assertNull(s.countableUpAtMs)
        assertFalse(s.forceShortLived)
    }

    // ---- 리뷰 R3: postCommitStormAction ------------------------------------

    @Test
    fun `an active reconnect run schedules the full two-stage dwell`() {
        for (live in listOf(true, false)) {
            for (streak in listOf(0, 2)) {
                assertEquals(
                    PublisherViewModel.PostCommitStormAction.FULL_DWELL,
                    PublisherViewModel.postCommitStormAction(
                        reconnectRunActive = true, liveRepublish = live, stormStreak = streak,
                    ),
                )
            }
        }
    }

    @Test
    fun `a user publish resets the streak immediately`() {
        for (streak in listOf(0, 2)) {
            assertEquals(
                PublisherViewModel.PostCommitStormAction.RESET_NOW,
                PublisherViewModel.postCommitStormAction(
                    reconnectRunActive = false, liveRepublish = false, stormStreak = streak,
                ),
            )
        }
    }

    @Test
    fun `a SHORT-lived clean death at EXACTLY the lifetime bound is countable (streak reset)`() {
        // 리뷰 R4 경계 핀: countable의 lifetime 절은 >= 다 — 정확히 5000ms에서
        // countable이어야 recordSessionEnd가 스트릭을 리셋한다. `>` 절삭이
        // 여기서 죽는다 (시그니처 disjunct는 false로 눌러 lifetime 절만 판정).
        val s = PublisherViewModel.stormSignal(
            lastPublishUpAtMs = 10_000L,
            nowMs = 15_000L,
            networkUpAtDeath = true,
            corruptionSignature = false,
        )
        assertEquals(10_000L, s.countableUpAtMs)
        assertFalse(s.forceShortLived)
    }

    @Test
    fun `a benign live republish with a pending streak re-arms a streak-only dwell`() {
        // 리뷰 R3 결함 핀: 15s run-clear와 60s streak-clear 사이에 착지한
        // 회전/오디오 재무장 republish가 dwell을 취소하고 아무것도 재예약하지
        // 않으면 스트릭이 고아화되어, 한참 뒤 단발 시그니처 사망 하나가
        // 안정 방송을 false-latch로 죽인다.
        assertEquals(
            PublisherViewModel.PostCommitStormAction.STREAK_ONLY_DWELL,
            PublisherViewModel.postCommitStormAction(
                reconnectRunActive = false, liveRepublish = true, stormStreak = 2,
            ),
        )
        // 리뷰 R4 경계 핀: streak=1(단발 시그니처 사망 후의 최빈 상태)도
        // dwell이 필요하다 — `> 0`을 `> 1`/`>= 2`로 바꾸는 절삭은 0과 2만
        // 핀된 상태에선 그린으로 통과해 R3 고아화 결함을 한 칸 아래서
        // 재개방한다. 이 한 줄이 그 절삭을 죽인다.
        assertEquals(
            PublisherViewModel.PostCommitStormAction.STREAK_ONLY_DWELL,
            PublisherViewModel.postCommitStormAction(
                reconnectRunActive = false, liveRepublish = true, stormStreak = 1,
            ),
        )
    }

    @Test
    fun `a benign live republish with a clean streak schedules nothing`() {
        assertEquals(
            PublisherViewModel.PostCommitStormAction.NONE,
            PublisherViewModel.postCommitStormAction(
                reconnectRunActive = false, liveRepublish = true, stormStreak = 0,
            ),
        )
    }

    // ---- 리뷰 R6: startsFreshRun ------------------------------------------

    @Test
    fun `no active run always starts a fresh reconnect run`() {
        assertTrue(PublisherViewModel.startsFreshRun(reconnectRunActive = false, lifetimeMs = 0L))
        assertTrue(PublisherViewModel.startsFreshRun(reconnectRunActive = false, lifetimeMs = -1L))
    }

    @Test
    fun `a short-lived death inside an active run continues the run (storm budget carries)`() {
        // AND-V48-001/D-05의 핵심: 폭풍 사이클이 매번 fresh 90s 예산을 받으면
        // give-up이 영원히 불가다 — 15s 미만 생존은 같은 outage다.
        assertFalse(PublisherViewModel.startsFreshRun(reconnectRunActive = true, lifetimeMs = 3_000L))
        assertFalse(
            PublisherViewModel.startsFreshRun(
                reconnectRunActive = true,
                lifetimeMs = PublisherViewModel.RUN_CONTINUATION_MAX_LIFETIME_MS - 1,
            ),
        )
    }

    @Test
    fun `a publish that held EXACTLY the continuation bound is a separate outage`() {
        // 경계 = >= (R4의 lifetime 경계 핀과 같은 규율).
        assertTrue(
            PublisherViewModel.startsFreshRun(
                reconnectRunActive = true,
                lifetimeMs = PublisherViewModel.RUN_CONTINUATION_MAX_LIFETIME_MS,
            ),
        )
    }

    // ---- 리뷰 R8/R12: armsPostRecoveryAudioCheck --------------------------

    @Test
    fun `a reconnect-recovered generation arms the audio readiness check`() {
        assertTrue(
            PublisherViewModel.armsPostRecoveryAudioCheck(
                reconnectRunActive = true, audioRecovering = false, micEnabled = true,
            ),
        )
    }

    @Test
    fun `a steady-state audio-recovery republish arms the readiness check`() {
        // 리뷰 R8 결함 핀: 재무장 republish가 reconnect 밖(steady state)에서
        // 커밋되면 검사가 안 걸려, Starting-웨지 mic가 audioRecovering=true를
        // 영구 래치했다(Stopped 미도달=onAudioTrackDied 재발화 불가·Retry 없음).
        assertTrue(
            PublisherViewModel.armsPostRecoveryAudioCheck(
                reconnectRunActive = false, audioRecovering = true, micEnabled = true,
            ),
        )
    }

    @Test
    fun `a plain first publish or rotation generation ALSO arms the readiness check`() {
        // 리뷰 R12(운영자 지시 후속): 첫 user publish·회전 재게시 세대의
        // Starting-웨지 mic는 Stopped 미도달=onAudioTrackDied 발화 불가라
        // 무배너 silent video-only로 영구 지속됐다(R8/R9가 파킹한 선행 갭).
        // 이제 mic 탑재 세대 전부가 검사를 받는다 — 이 행이 구 게이팅
        // ((runActive||recovering)&&mic)으로의 회귀를 죽인다.
        assertTrue(
            PublisherViewModel.armsPostRecoveryAudioCheck(
                reconnectRunActive = false, audioRecovering = false, micEnabled = true,
            ),
        )
    }

    @Test
    fun `a mic-disabled broadcast never arms the readiness check`() {
        for (runActive in listOf(true, false)) {
            for (recovering in listOf(true, false)) {
                assertFalse(
                    PublisherViewModel.armsPostRecoveryAudioCheck(
                        reconnectRunActive = runActive, audioRecovering = recovering, micEnabled = false,
                    ),
                )
            }
        }
    }

    @Test
    fun `the continuation bound is the stability dwell itself`() {
        // 설계 계약(리뷰 R2): "run이 아직 안 끝났다"의 정의가 15s dwell이므로
        // 두 상수는 동일해야 한다 — 한쪽만 튜닝하면 여기서 죽는다.
        assertEquals(
            PublisherViewModel.STABLE_RESET_MS,
            PublisherViewModel.RUN_CONTINUATION_MAX_LIFETIME_MS,
        )
    }

    // ---- 2026-08-16 QA D-03: genReleasePlan (지각 등록 레이스) --------------

    @Test
    fun `a first release sweeps present captures and runs the native close`() {
        val plan = PublisherViewModel.genReleasePlan(
            alreadyReleased = false, hasMic = true, hasScreen = true,
        )
        assertTrue(plan.stopMic)
        assertTrue(plan.stopScreen)
        assertTrue(plan.runNativeClose)
        // 첫 릴리즈는 지각 회수가 아니다 — 경고 로그 없음.
        assertFalse(plan.logLateReclaim)
    }

    @Test
    fun `a repeat release still sweeps a late-registered capture and logs the reclaim`() {
        // 결함 핀(실기기 08-16 D-03): teardown이 mic 등록 전에 릴리즈를 마치면
        // 재개된 connect 코루틴이 등록+start한 AudioRecord를 finally의 재릴리즈가
        // `released` 조기반환으로 영영 놓치지 않았다(flinger 481/513 Active 잔존).
        // 반복 호출도 캡처는 반드시 스윕하고, 실제 회수가 있을 때만 로그한다.
        val plan = PublisherViewModel.genReleasePlan(
            alreadyReleased = true, hasMic = true, hasScreen = false,
        )
        assertTrue(plan.stopMic)
        assertFalse(plan.stopScreen)
        assertTrue(plan.logLateReclaim)
        // 네이티브 클로즈는 첫 릴리즈 전용 — 반복 실행 금지.
        assertFalse(plan.runNativeClose)
    }

    @Test
    fun `a repeat release with nothing to reclaim is a silent no-op`() {
        val plan = PublisherViewModel.genReleasePlan(
            alreadyReleased = true, hasMic = false, hasScreen = false,
        )
        assertFalse(plan.stopMic)
        assertFalse(plan.stopScreen)
        assertFalse(plan.logLateReclaim)
        assertFalse(plan.runNativeClose)
    }

    @Test
    fun `a first release with no captures still runs the native close`() {
        // 캡처가 아직 등록되지 않은 세대(connect 초입 teardown)도 세션의
        // bounded native close는 정확히 한 번 받아야 한다.
        val plan = PublisherViewModel.genReleasePlan(
            alreadyReleased = false, hasMic = false, hasScreen = false,
        )
        assertFalse(plan.stopMic)
        assertFalse(plan.stopScreen)
        assertFalse(plan.logLateReclaim)
        assertTrue(plan.runNativeClose)
    }

    // ---- 스톰 시그니처 정규식 ----------------------------------------------

    @Test
    fun `the storm signature matches the verified corruption reasons and nothing benign`() {
        val sig = PublisherViewModel.STORM_REASON_SIGNATURE
        assertTrue(sig.containsMatchIn("Session error: transport: short frame"))
        assertTrue(sig.containsMatchIn("connection closed: 1002: frame too large"))
        assertTrue(sig.containsMatchIn("Protocol: invalid frame"))
        // 일반 네트워크 사망은 시그니처가 아니다 (플래핑/서버 재배포 커버).
        assertFalse(sig.containsMatchIn("IO error: Connection reset by peer (os error 104)"))
        assertFalse(sig.containsMatchIn("transport: connection closed"))
        assertFalse(sig.containsMatchIn("timed out"))
    }
}
