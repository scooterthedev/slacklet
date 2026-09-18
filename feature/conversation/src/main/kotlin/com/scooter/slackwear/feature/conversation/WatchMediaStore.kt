package com.scooter.slackwear.feature.conversation

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class WatchMediaItem(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val kind: Kind,
) {
    enum class Kind { IMAGE, AUDIO, VIDEO }
}

class WatchMediaStore(private val context: Context) {

    suspend fun list(maxBytes: Long): List<WatchMediaItem> = withContext(Dispatchers.IO) {
        buildList {
            addAll(query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, WatchMediaItem.Kind.IMAGE, maxBytes))
            addAll(query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, WatchMediaItem.Kind.AUDIO, maxBytes))
            addAll(query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, WatchMediaItem.Kind.VIDEO, maxBytes))
        }
    }

    private fun query(
        collection: Uri,
        kind: WatchMediaItem.Kind,
        maxBytes: Long,
    ): List<WatchMediaItem> = runCatching {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
        )

        context.contentResolver.query(
            collection,
            projection,
            "${MediaStore.MediaColumns.SIZE} > 0 AND ${MediaStore.MediaColumns.SIZE} <= ?",
            arrayOf(maxBytes.toString()),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)

            buildList {
                while (cursor.moveToNext() && size < PER_KIND_LIMIT) {
                    val id = cursor.getLong(idColumn)
                    add(
                        WatchMediaItem(
                            uri = ContentUris.withAppendedId(collection, id),
                            name = cursor.getString(nameColumn) ?: "file-$id",
                            sizeBytes = cursor.getLong(sizeColumn),
                            kind = kind,
                        ),
                    )
                }
            }
        }.orEmpty()
    }.onFailure {

        Log.w(TAG, "Could not read $kind from MediaStore", it)
    }.getOrDefault(emptyList())

    suspend fun read(item: WatchMediaItem): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(item.uri)?.use { it.readBytes() }
        }.getOrNull()
    }

    private companion object {

        const val PER_KIND_LIMIT = 40
        const val TAG = "WatchMediaStore"
    }
}
