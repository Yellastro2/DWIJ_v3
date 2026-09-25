package com.yellastrodev.dwij.playback

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.yellastrodev.dwij.TRACK_ID
import com.yellastrodev.dwij.playback.feedback.PlaybackMetadataKeys
import org.json.JSONArray
import org.json.JSONObject

/** Сохраняет компактную очередь и позицию для восстановления Media3 после смерти процесса. */
class PlaybackResumeStore(context: Context) {
    private val preferences = context.getSharedPreferences("playback_resume", Context.MODE_PRIVATE)

    /** Снимок очереди без крупных bitmap-данных обложек. */
    data class Snapshot(
        val items: List<MediaItem>,
        val index: Int,
        val positionMs: Long,
        val shuffle: Boolean,
        val repeatMode: Int,
    )

    /** Записывает воспроизводимые URI и текущую позицию одним атомарным обновлением. */
    fun save(player: Player) {
        if (player.mediaItemCount == 0) return
        val items = JSONArray()
        for (index in 0 until player.mediaItemCount) {
            val item = player.getMediaItemAt(index)
            val uri = item.localConfiguration?.uri ?: continue
            val metadata = item.mediaMetadata
            val extras = JSONObject()
            metadata.extras?.let { bundle ->
                for (key in EXTRA_KEYS) {
                    when (val value = bundle.get(key)) {
                        is String, is Boolean, is Long, is Int -> extras.put(key, value)
                    }
                }
            }
            items.put(JSONObject().apply {
                put("id", item.mediaId)
                put("uri", uri.toString())
                put("title", metadata.title?.toString())
                put("artist", metadata.artist?.toString())
                put("artworkUri", metadata.artworkUri?.toString())
                put("extras", extras)
            })
        }
        if (items.length() == 0) return
        val snapshot = JSONObject().apply {
            put("items", items)
            put("index", player.currentMediaItemIndex.coerceAtLeast(0))
            put("positionMs", player.currentPosition.coerceAtLeast(0L))
            put("shuffle", player.shuffleModeEnabled)
            put("repeatMode", player.repeatMode)
        }
        preferences.edit().putString(KEY_SNAPSHOT, snapshot.toString()).apply()
    }

    /** Читает только целый и пригодный для проигрывания снимок. */
    fun load(): Snapshot? = runCatching {
        val root = JSONObject(preferences.getString(KEY_SNAPSHOT, null) ?: return null)
        val encodedItems = root.getJSONArray("items")
        val items = buildList {
            for (index in 0 until encodedItems.length()) {
                val encoded = encodedItems.getJSONObject(index)
                val extras = Bundle()
                val savedExtras = encoded.getJSONObject("extras")
                for (key in EXTRA_KEYS) {
                    if (!savedExtras.has(key)) continue
                    when (val value = savedExtras.get(key)) {
                        is String -> extras.putString(key, value)
                        is Boolean -> extras.putBoolean(key, value)
                        is Number -> extras.putLong(key, value.toLong())
                    }
                }
                val metadata = MediaMetadata.Builder()
                    .setTitle(encoded.optString("title"))
                    .setArtist(encoded.optString("artist"))
                    .setExtras(extras)
                    .apply {
                        encoded.optString("artworkUri").takeIf(String::isNotEmpty)
                            ?.let { setArtworkUri(Uri.parse(it)) }
                    }
                    .build()
                add(MediaItem.Builder()
                    .setMediaId(encoded.getString("id"))
                    .setUri(Uri.parse(encoded.getString("uri")))
                    .setMediaMetadata(metadata)
                    .build())
            }
        }
        if (items.isEmpty()) return null
        Snapshot(
            items = items,
            index = root.optInt("index").coerceIn(items.indices),
            positionMs = root.optLong("positionMs").coerceAtLeast(0L),
            shuffle = root.optBoolean("shuffle"),
            repeatMode = root.optInt("repeatMode", Player.REPEAT_MODE_OFF),
        )
    }.getOrNull()

    private companion object {
        const val KEY_SNAPSHOT = "snapshot"
        val EXTRA_KEYS = listOf(
            TRACK_ID,
            PlaybackMetadataKeys.MUSIC_SOURCE,
            PlaybackMetadataKeys.PLAY_ITEM_ID,
            PlaybackMetadataKeys.PLAY_ALBUM_ID,
            PlaybackMetadataKeys.PLAY_PLAYLIST_ID,
            PlaybackMetadataKeys.PLAY_DURATION_MS,
            PlaybackMetadataKeys.PLAY_SOURCE,
            PlaybackMetadataKeys.YANDEX_AVAILABLE,
        )
    }
}
