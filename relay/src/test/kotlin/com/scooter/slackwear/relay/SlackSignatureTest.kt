package com.scooter.slackwear.relay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class SlackSignatureTest {

    private val secret = "8f742231b10e8888abcd99yyyzzz85a5"
    private val body = """{"type":"event_callback"}"""
    private val now = 1_700_000_000L

    @Test
    fun `accepts a correctly signed request`() {
        assertTrue(
            SlackSignature.isValid(
                signingSecret = secret,
                timestampHeader = now.toString(),
                signatureHeader = sign(now, body),
                rawBody = body,
                nowEpochSeconds = now,
            ),
        )
    }

    @Test
    fun `rejects a tampered body`() {
        assertFalse(
            SlackSignature.isValid(
                signingSecret = secret,
                timestampHeader = now.toString(),
                signatureHeader = sign(now, body),
                rawBody = """{"type":"event_callback","injected":true}""",
                nowEpochSeconds = now,
            ),
        )
    }

    @Test
    fun `rejects a replayed signature`() {
        assertFalse(
            SlackSignature.isValid(
                signingSecret = secret,
                timestampHeader = now.toString(),
                signatureHeader = sign(now, body),
                rawBody = body,

                nowEpochSeconds = now + 360,
            ),
        )
    }

    @Test
    fun `rejects a signature made with the wrong secret`() {
        assertFalse(
            SlackSignature.isValid(
                signingSecret = secret,
                timestampHeader = now.toString(),
                signatureHeader = sign(now, body, secret = "not-the-secret"),
                rawBody = body,
                nowEpochSeconds = now,
            ),
        )
    }

    @Test
    fun `rejects missing headers`() {
        assertFalse(
            SlackSignature.isValid(secret, null, sign(now, body), body, now),
        )
        assertFalse(
            SlackSignature.isValid(secret, now.toString(), null, body, now),
        )
    }

    private fun sign(timestamp: Long, body: String, secret: String = this.secret): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        val hash = mac.doFinal("v0:$timestamp:$body".toByteArray())
        return "v0=" + hash.joinToString("") { "%02x".format(it) }
    }
}
