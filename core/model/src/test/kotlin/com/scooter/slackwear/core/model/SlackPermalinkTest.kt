package com.scooter.slackwear.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlackPermalinkTest {

    @Test
    fun aPermalinkBecomesAChannelAndTimestamp() {
        val link = findSlackPermalinks(
            "see https://hackclub.slack.com/archives/D0B7JE5EHD4/p1789635664129599",
        ).single()

        assertEquals("D0B7JE5EHD4", link.conversationId)

        assertEquals("1789635664.129599", link.ts)
        assertEquals(null, link.threadTs)
    }

    @Test
    fun aLinkIntoAThreadKeepsTheParentItHasToOpen() {
        val link = findSlackPermalinks(
            "https://hackclub.slack.com/archives/C123ABC/p1789635664129599?thread_ts=1789600000.000100&cid=C123ABC",
        ).single()

        assertEquals("C123ABC", link.conversationId)
        assertEquals("1789635664.129599", link.ts)
        assertEquals("1789600000.000100", link.threadTs)
    }

    @Test
    fun severalLinksInOneMessageAreAllFoundInOrder() {
        val links = findSlackPermalinks(
            """
            first https://a.slack.com/archives/C1/p1700000000000001
            second https://b.slack.com/archives/C2/p1700000000000002
            """.trimIndent(),
        )
        assertEquals(listOf("C1", "C2"), links.map { it.conversationId })
        assertEquals(listOf("1700000000.000001", "1700000000.000002"), links.map { it.ts })
    }

    @Test
    fun theOriginalTextIsKeptSoItCanBeRemovedFromTheBody() {
        val url = "https://hackclub.slack.com/archives/C1/p1700000000000001"
        val body = "look at $url please"
        val link = findSlackPermalinks(body).single()

        assertEquals(url, link.url)
        assertEquals("look at  please", body.replace(link.url, ""))
    }

    @Test
    fun otherSlackUrlsAndOtherHostsAreNotMessageLinks() {
        assertTrue(findSlackPermalinks("https://hackclub.slack.com/archives/C1").isEmpty())
        assertTrue(findSlackPermalinks("https://example.com/archives/C1/p1700000000000001").isEmpty())
        assertTrue(findSlackPermalinks("https://hackclub.slack.com/team/U123").isEmpty())

        assertTrue(findSlackPermalinks("https://a.slack.com/archives/C1/p123456").isEmpty())
    }
}
