package com.swmansion.moqkit

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The dial URL may carry a publish/subscribe JWT as `?jwt=` (the relay reads
 * it from the connection URL query). Log lines must never emit the token:
 * [Session.redactForLog] backs the "Connecting to …" and "Connection failed"
 * log sites.
 */
class SessionLogRedactionTest {

    @Test
    fun noQueryIsUnchanged() {
        assertEquals(
            "https://relay.example:4443/pding",
            Session.redactForLog("https://relay.example:4443/pding"),
        )
    }

    @Test
    fun jwtOnlyQueryIsRedacted() {
        assertEquals(
            "https://relay.example:4443/pding?jwt=<redacted>",
            Session.redactForLog("https://relay.example:4443/pding?jwt=eyJhbGciOiJIUzI1NiJ9.eyJwdXQiOiJwZGluZyJ9.sig"),
        )
    }

    @Test
    fun jwtAfterOtherParamsIsRedactedAndOthersKept() {
        assertEquals(
            "https://relay.example/pding?ns=live&jwt=<redacted>&x=1",
            Session.redactForLog("https://relay.example/pding?ns=live&jwt=abc.def.ghi&x=1"),
        )
    }

    @Test
    fun jwtBeforeFragmentStopsAtFragment() {
        assertEquals(
            "https://relay.example/pding?jwt=<redacted>#frag",
            Session.redactForLog("https://relay.example/pding?jwt=abc#frag"),
        )
    }

    @Test
    fun nonJwtQueryIsUnchanged() {
        assertEquals(
            "https://relay.example/pding?path=live/1&ns=pding",
            Session.redactForLog("https://relay.example/pding?path=live/1&ns=pding"),
        )
    }

    @Test
    fun embeddedInErrorMessageIsRedacted() {
        assertEquals(
            "dial https://relay.example/pding?jwt=<redacted> failed",
            Session.redactForLog("dial https://relay.example/pding?jwt=eyJ.sig failed"),
        )
    }
}
