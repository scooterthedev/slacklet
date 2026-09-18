package com.scooter.slackwear.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceSignInTest {

    @Test
    fun `extracts token from a ul-slack magic-login-sso deep link`() {
        assertEquals(
            "TOKEN",
            extractMagicToken("ul-slack://worker/e/magic-login-sso/TOKEN/TEAMID"),
        )
    }

    @Test
    fun `extracts token regardless of host or prefix segments`() {
        assertEquals(
            "abc123",
            extractMagicToken("ul-slack://magic-login-sso/abc123"),
        )
    }

    @Test
    fun `strips a trailing query or fragment from the token`() {
        assertEquals(
            "TOKEN",
            extractMagicToken("ul-slack://h/magic-login-sso/TOKEN/TEAM?foo=bar"),
        )
    }

    @Test
    fun `accepts a bare token`() {
        assertEquals("baretoken", extractMagicToken("  baretoken  "))
    }

    @Test
    fun `rejects a url missing the marker`() {
        assertNull(extractMagicToken("https://example.com/other"))
        assertNull(extractMagicToken(""))
    }
}
