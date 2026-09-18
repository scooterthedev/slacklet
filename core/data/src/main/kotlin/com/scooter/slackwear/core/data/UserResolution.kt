package com.scooter.slackwear.core.data

import com.scooter.slackwear.core.database.dao.UserDao
import com.scooter.slackwear.core.network.SlackApi
import com.scooter.slackwear.core.network.unwrap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

internal suspend fun resolveUsers(
    api: SlackApi,
    userDao: UserDao,
    ids: Collection<String>,
    maxLookups: Int = 100,
    concurrency: Int = 8,
): Unit = withContext(Dispatchers.IO) {
    val distinct = ids.filter(String::isNotBlank).distinct()
    if (distinct.isEmpty()) return@withContext

    val known = userDao.existingIds(distinct).toSet()
    val missing = distinct.filterNot(known::contains).take(maxLookups)
    if (missing.isEmpty()) return@withContext

    val semaphore = Semaphore(concurrency)
    val resolved = coroutineScope {
        missing.map { id ->
            async {
                semaphore.withPermit {
                    runCatching { api.userInfo(id).unwrap().user }.getOrNull()?.toEntity()
                }
            }
        }.awaitAll().filterNotNull()
    }

    if (resolved.isNotEmpty()) userDao.upsert(resolved)
}
