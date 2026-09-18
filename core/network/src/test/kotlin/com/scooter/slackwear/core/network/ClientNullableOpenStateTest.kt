package com.scooter.slackwear.core.network

import com.scooter.slackwear.core.network.model.ClientChannelsBody
import com.scooter.slackwear.core.network.model.ClientChannelsResponse
import com.scooter.slackwear.core.network.model.UserBootResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClientNullableOpenStateTest {
    private val decoders = listOf(Json, Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    })

    @Test
    fun bootOpenStateDistinguishesOmittedAndNullFromExplicitEmpty() {
        assertNull(UserBootResponse().isOpen)
        decoders.forEach { json ->
            assertNull(json.decodeFromString<UserBootResponse>("""{"ok":true}""").isOpen)
            assertNull(json.decodeFromString<UserBootResponse>("""{"is_open":null}""").isOpen)
            assertEquals(emptyList<String>(), json.decodeFromString<UserBootResponse>("""{"is_open":[]}""").isOpen)
            assertEquals(listOf("D1"), json.decodeFromString<UserBootResponse>("""{"is_open":["D1"]}""").isOpen)
        }
    }

    @Test
    fun channelsOpenStateDistinguishesOmittedAndNullFromExplicitEmpty() {
        assertNull(ClientChannelsBody().isOpen)
        decoders.forEach { json ->
            val omitted = json.decodeFromString<ClientChannelsResponse>("""{"channels":{}}""")
            val explicitNull = json.decodeFromString<ClientChannelsResponse>("""{"channels":{"is_open":null}}""")
            val empty = json.decodeFromString<ClientChannelsResponse>("""{"channels":{"is_open":[]}}""")
            val open = json.decodeFromString<ClientChannelsResponse>("""{"channels":{"is_open":["D1"]}}""")
            assertNull(requireNotNull(omitted.channels).isOpen)
            assertNull(requireNotNull(explicitNull.channels).isOpen)
            assertEquals(emptyList<String>(), requireNotNull(empty.channels).isOpen)
            assertEquals(listOf("D1"), requireNotNull(open.channels).isOpen)
        }
    }
}
