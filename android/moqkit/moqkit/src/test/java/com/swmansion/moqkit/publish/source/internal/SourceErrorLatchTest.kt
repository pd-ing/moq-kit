package com.swmansion.moqkit.publish.source.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the at-most-once + ordering + session-isolation contract of the
 * capture-death signal.
 *
 * The interesting cases are the races the latch exists for: failure BEFORE
 * registration (apps start the microphone before the publisher attaches, so an
 * init failure precedes [SourceErrorLatch.callback] being set), deliberate
 * [SourceErrorLatch.close] racing a capture give-up, re-registration after a
 * delivery, and a stale session's late report crossing into a restarted
 * session. Each is driven both ways so a regression in either arm fails, and
 * BOTH delivery arms (report-side and setter-side) are followed by a second
 * report so the `delivered` flag is pinned on each arm.
 */
class SourceErrorLatchTest {

    @Test
    fun registerThenReportDeliversOnce() {
        val latch = SourceErrorLatch()
        val got = mutableListOf<String>()
        latch.callback = { got.add(it) }
        latch.report(latch.session, "dead route")
        latch.report(latch.session, "dead route again")
        assertEquals(listOf("dead route"), got)
    }

    @Test
    fun reportThenRegisterDeliversPendingFailure() {
        val latch = SourceErrorLatch()
        latch.report(latch.session, "init failed")
        val got = mutableListOf<String>()
        latch.callback = { got.add(it) }
        assertEquals(listOf("init failed"), got)
    }

    @Test
    fun setterDeliveryAlsoLatchesAtMostOnce() {
        // The setter arm's `delivered = true` is a separate write from the
        // report arm's; a report AFTER a setter-side delivery must not fire a
        // second time.
        val latch = SourceErrorLatch()
        latch.report(latch.session, "init failed")
        val got = mutableListOf<String>()
        latch.callback = { got.add(it) }
        latch.report(latch.session, "second failure")
        assertEquals(listOf("init failed"), got)
    }

    @Test
    fun firstMessageWinsOverLaterReports() {
        val latch = SourceErrorLatch()
        latch.report(latch.session, "first")
        latch.report(latch.session, "second")
        val got = mutableListOf<String>()
        latch.callback = { got.add(it) }
        assertEquals(listOf("first"), got)
    }

    @Test
    fun reRegistrationAfterDeliveryDeliversNothing() {
        val latch = SourceErrorLatch()
        val got = mutableListOf<String>()
        latch.callback = { got.add(it) }
        latch.report(latch.session, "dead")
        latch.callback = { got.add("again: $it") }
        assertEquals(listOf("dead"), got)
    }

    @Test
    fun settingNullNeverDelivers() {
        val latch = SourceErrorLatch()
        latch.report(latch.session, "dead")
        latch.callback = null
        val got = mutableListOf<String>()
        latch.callback = { got.add(it) }
        // The null registration must not consume the pending failure.
        assertEquals(listOf("dead"), got)
    }

    @Test
    fun staleSessionReportIsDropped() {
        // A read thread from a previous session (stop() interrupts but never
        // joins) reports with ITS session token; after a rearm that report
        // must not fire the restarted session's callback, and must not even
        // become pending.
        val latch = SourceErrorLatch()
        val stale = latch.rearm()
        val fresh = latch.rearm()
        val got = mutableListOf<String>()
        latch.callback = { got.add(it) }
        latch.report(stale, "stale session death")
        assertEquals(emptyList<String>(), got)
        latch.report(fresh, "current session death")
        assertEquals(listOf("current session death"), got)
    }

    @Test
    fun staleSessionReportLeavesNoPending() {
        val latch = SourceErrorLatch()
        val stale = latch.rearm()
        latch.rearm()
        latch.report(stale, "stale session death")
        val got = mutableListOf<String>()
        latch.callback = { got.add(it) }
        assertEquals(emptyList<String>(), got)
    }

    @Test
    fun closeSuppressesPendingFailure() {
        val latch = SourceErrorLatch()
        latch.report(latch.session, "dead")
        latch.close()
        val got = mutableListOf<String>()
        latch.callback = { got.add(it) }
        assertEquals(emptyList<String>(), got)
    }

    @Test
    fun closeSuppressesFutureReportAndClearsCallback() {
        val latch = SourceErrorLatch()
        val got = mutableListOf<String>()
        latch.callback = { got.add(it) }
        latch.close()
        assertNull(latch.callback)
        latch.report(latch.session, "give-up losing the race to stop")
        assertEquals(emptyList<String>(), got)
    }

    @Test
    fun reportAfterCloseWithReRegisteredCallbackIsSuppressed() {
        // close() clears the callback, but the setter stores a later
        // registration unconditionally — so this is the one state where
        // report()'s own closed guard (not the null callback) is what keeps a
        // stopped session's late give-up from firing: closed, same session
        // token, live callback.
        val latch = SourceErrorLatch()
        val session = latch.session
        latch.close()
        val got = mutableListOf<String>()
        latch.callback = { got.add(it) }
        latch.report(session, "give-up after stop with re-registered callback")
        assertEquals(emptyList<String>(), got)
    }

    @Test
    fun rearmAfterCloseAllowsFreshSession() {
        val latch = SourceErrorLatch()
        latch.report(latch.session, "stale failure from previous session")
        latch.close()
        val fresh = latch.rearm()
        val got = mutableListOf<String>()
        latch.callback = { got.add(it) }
        // The previous session's failure is obsolete after rearm.
        assertEquals(emptyList<String>(), got)
        latch.report(fresh, "fresh failure")
        assertEquals(listOf("fresh failure"), got)
    }

    @Test
    fun rearmAfterDeliveryAllowsNewDelivery() {
        val latch = SourceErrorLatch()
        val got = mutableListOf<String>()
        latch.callback = { got.add(it) }
        latch.report(latch.session, "session 1")
        val next = latch.rearm()
        latch.report(next, "session 2")
        assertEquals(listOf("session 1", "session 2"), got)
    }

    @Test
    fun rearmAdvancesSessionToken() {
        val latch = SourceErrorLatch()
        assertEquals(0L, latch.session)
        assertEquals(1L, latch.rearm())
        assertEquals(1L, latch.session)
        assertEquals(2L, latch.rearm())
        assertEquals(2L, latch.session)
    }

    @Test
    fun callbackMayClearItselfWithoutDeadlock() {
        val latch = SourceErrorLatch()
        val got = mutableListOf<String>()
        latch.callback = {
            got.add(it)
            latch.callback = null
        }
        latch.report(latch.session, "dead")
        assertEquals(listOf("dead"), got)
        assertNull(latch.callback)
    }
}
