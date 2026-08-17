package com.swmansion.moqkit.publish.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the [MicrophoneCapture.onSourceError] property to the real
 * [com.swmansion.moqkit.publish.source.internal.SourceErrorLatch] delegation —
 * a default-interface no-op left in place (or private storage that the latch
 * never sees) would compile and pass the latch's own suite while the Publisher
 * observed nothing.
 *
 * start() is also driven here: with `unitTests.isReturnDefaultValues = true`
 * the stubbed AudioRecord reports state 0 (never STATE_INITIALIZED), so on the
 * host JVM start() deterministically takes the init-failure branch. That pins
 * BOTH the rearm() call site (a start after stop() must reopen the closed
 * latch) and the init-failure report call site with its exact message. The
 * read-thread half (the give-up report) needs a live AudioRecord and is
 * covered by review and the on-device scenario (mic revoke / audioserver
 * kill), same as the read loop in [MicrophoneCaptureDeliveryTest].
 */
class MicrophoneCaptureSourceErrorTest {

    @Test
    fun onSourceErrorDelegatesToLatchBothWays() {
        val mic = MicrophoneCapture()
        val got = mutableListOf<String>()
        val cb: (String) -> Unit = { got.add(it) }
        mic.onSourceError = cb
        // get must observe the same registration the latch holds.
        assertEquals(cb, mic.onSourceError)
        mic.sourceError.report(mic.sourceError.session, "dead route")
        assertEquals(listOf("dead route"), got)
    }

    @Test
    fun failureBeforeRegistrationDeliversOnRegistration() {
        val mic = MicrophoneCapture()
        mic.sourceError.report(mic.sourceError.session, "AudioRecord initialization failed")
        val got = mutableListOf<String>()
        mic.onSourceError = { got.add(it) }
        assertEquals(listOf("AudioRecord initialization failed"), got)
    }

    @Test
    fun stopSuppressesDeathSignal() {
        // stop() before start() is safe on the host JVM: record and thread are
        // null and the AudioRecord catch path is never reached.
        val mic = MicrophoneCapture()
        val got = mutableListOf<String>()
        mic.onSourceError = { got.add(it) }
        mic.stop()
        assertNull(mic.onSourceError)
        mic.sourceError.report(mic.sourceError.session, "give-up losing the race to stop")
        assertEquals(emptyList<String>(), got)
    }

    @Test
    fun reRegistrationAfterStopStaysSuppressed() {
        // A direct-API consumer may re-register onSourceError after stop()
        // without a new start(); the stopped session's un-joined read thread
        // still holds a matching session token, so suppression must come from
        // the latch's closed state, not from the callback having been cleared.
        val mic = MicrophoneCapture()
        val session = mic.sourceError.session
        mic.stop()
        val got = mutableListOf<String>()
        mic.onSourceError = { got.add(it) }
        mic.sourceError.report(session, "give-up after stop")
        assertEquals(emptyList<String>(), got)
    }

    @Test
    fun startInitFailureReportsToRegisteredCallback() {
        // Stubbed AudioRecord (returnDefaultValues) never reaches
        // STATE_INITIALIZED, so start() takes the init-failure branch and must
        // report it with this exact message.
        val mic = MicrophoneCapture()
        val got = mutableListOf<String>()
        mic.onSourceError = { got.add(it) }
        mic.start()
        assertEquals(listOf("AudioRecord initialization failed"), got)
    }

    @Test
    fun startAfterStopRearmsTheDeathSignal() {
        // stop() closes the latch; a later start() of the same instance is a
        // fresh capture session and its death must be delivered again. Without
        // the rearm() at the top of start(), the closed latch would swallow
        // the init-failure report of the restarted session.
        val mic = MicrophoneCapture()
        mic.stop()
        val got = mutableListOf<String>()
        mic.onSourceError = { got.add(it) }
        mic.start()
        assertEquals(listOf("AudioRecord initialization failed"), got)
    }
}
