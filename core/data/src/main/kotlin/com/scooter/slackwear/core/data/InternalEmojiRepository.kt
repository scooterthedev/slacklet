package com.scooter.slackwear.core.data

import com.scooter.slackwear.core.model.repository.EmojiCount
import com.scooter.slackwear.core.model.repository.EmojiRepository
import com.scooter.slackwear.core.network.ClientApi
import com.scooter.slackwear.core.network.unwrap
import kotlinx.coroutines.flow.MutableStateFlow

class InternalEmojiRepository(
    private val clientApi: ClientApi,
) : EmojiRepository {

    private val cache = MutableStateFlow<List<EmojiCount>>(emptyList())

    @Volatile
    private var loaded = false

    override suspend fun mostUsed(): Result<List<EmojiCount>> = runCatching {
        if (!loaded) {

            cache.value = clientApi.topEmojis(fetchAll = true).unwrap().user.toEmojiCounts()
            loaded = true
        }
        cache.value
    }
}
