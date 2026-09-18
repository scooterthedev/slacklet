package com.scooter.slackwear.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GroupConversationNameTest {

    @Test
    fun groupNameBecomesItsMembers() {
        val group = group("mpdm-echo--1mon3dp--sahil-1")
        assertEquals(listOf("echo", "1mon3dp", "sahil"), group.groupMembers)
        assertEquals("echo, 1mon3dp, sahil", group.displayName)
    }

    @Test
    fun largeGroupsNameWhatFitsAndCountTheRest() {
        val group = group("mpdm-alice--bob--carol--dave--erin-1")
        assertEquals(5, group.groupMembers?.size)
        assertEquals("alice, bob, carol +2", group.displayName)
    }

    @Test
    fun namesSlackDidNotGenerateAreLeftAlone() {
        val named = group("weekly sync")
        assertNull(named.groupMembers)
        assertEquals("weekly sync", named.displayName)
    }

    @Test
    fun onlyGroupsAreUnpacked() {
        val channel = Conversation(id = "C1", name = "general", kind = ConversationKind.PUBLIC_CHANNEL)
        assertNull(channel.groupMembers)
        assertEquals("#general", channel.displayName)

        val dm = Conversation(id = "D1", name = "alice", kind = ConversationKind.DIRECT_MESSAGE)
        assertNull(dm.groupMembers)
        assertEquals("alice", dm.displayName)
    }

    private fun group(name: String) =
        Conversation(id = "G1", name = name, kind = ConversationKind.GROUP_MESSAGE)
}
