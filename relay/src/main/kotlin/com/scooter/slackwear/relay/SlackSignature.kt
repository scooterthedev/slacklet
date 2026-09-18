package com.scooter.slackwear.relay

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

object SlackSignature {

    private const val VERSION = "v0"
    private const val MAX_SKEW_SECONDS = 60 * 5

    fun isValid(
        signingSecret: String,
        timestampHeader: String?,
        signatureHeader: String?,
        rawBody: String,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000,
    ): Boolean {
        val timestamp = timestampHeader?.toLongOrNull() ?: return false
        val signature = signatureHeader ?: return false

        if (abs(nowEpochSeconds - timestamp) > MAX_SKEW_SECONDS) return false

        val expected = sign(signingSecret, "$VERSION:$timestamp:$rawBody")
        return constantTimeEquals(expected, signature)
    }

    private fun sign(secret: String, basestring: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        val hash = mac.doFinal(basestring.toByteArray())
        return "$VERSION=" + hash.joinToString("") { "%02x".format(it) }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var difference = 0
        for (i in a.indices) difference = difference or (a[i].code xor b[i].code)
        return difference == 0
    }
}
