package com.scooter.slackwear.feature.conversation

import com.scooter.slackwear.core.model.ConversationKind
import com.scooter.slackwear.core.model.SlackUser
import com.scooter.slackwear.core.model.findSlackPermalinks
import com.scooter.slackwear.core.model.repository.ConversationRepository
import com.scooter.slackwear.core.model.repository.MessageRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class QuotedMessage(
    val conversationId: String,
    val ts: String,
    val threadTs: String?,
    val author: SlackUser?,
    val conversationLabel: String,
    val text: String,
)

class QuotedMessageResolver(
    private val messages: MessageRepository,
    private val conversations: ConversationRepository,
    private val scope: CoroutineScope,
) {
    private val cache = MutableStateFlow<Map<String, QuotedMessage>>(emptyMap())
    val quoted: StateFlow<Map<String, QuotedMessage>> = cache.asStateFlow()

    private val mutex = Mutex()
    private val attempted = mutableSetOf<String>()

    fun resolveIn(texts: List<String>) {
        val links = texts.flatMap(::findSlackPermalinks).distinctBy { it.url }
        if (links.isEmpty()) return

        scope.launch {
            mutex.withLock {
                val pending = links.filter { attempted.add(it.url) }
                if (pending.isEmpty()) return@withLock

                val summaries = conversations.observeConversations().first()
                val authors = messages.observeAuthors().first()

                pending.forEach { link ->
                    val message = try {
                        messages.lookupMessage(link.conversationId, link.ts).getOrNull()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        null

                    } ?: return@forEach

                    val summary = summaries.firstOrNull { it.conversation.id == link.conversationId }
                    cache.value = cache.value + (
                        link.url to QuotedMessage(
                            conversationId = link.conversationId,
                            ts = link.ts,
                            threadTs = link.threadTs,
                            author = authors[message.authorId],
                            conversationLabel = when (summary?.conversation?.kind) {
                                ConversationKind.DIRECT_MESSAGE -> "Direct Message"
                                ConversationKind.GROUP_MESSAGE -> "Group Message"
                                null -> ""
                                else -> summary.title
                            },
                            text = message.text,
                        )
                        )
                }
            }
        }
    }
}
