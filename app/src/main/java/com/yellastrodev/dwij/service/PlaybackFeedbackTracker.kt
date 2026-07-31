package com.yellastrodev.dwij.service

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.yellastrodev.dwij.data.source.PlaybackRemoteSource
import com.yellastrodev.dwij.data.source.PlaybackReportType
import com.yellastrodev.yandexmusiclib.tracks.PlayAudioRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.min

internal const val PLAY_AUDIO_ALBUM_ID = "play_audio_album_id"
internal const val PLAY_AUDIO_PLAYLIST_ID = "play_audio_playlist_id"
internal const val PLAY_AUDIO_DURATION_MS = "play_audio_duration_ms"
internal const val PLAY_AUDIO_SOURCE = "play_audio_source"
internal const val PLAY_AUDIO_ITEM_ID = "play_audio_item_id"
internal const val DEFAULT_PLAY_AUDIO_SOURCE = "dwij-android"

/**
 * Ведёт единственную сессию `/play-audio` поверх событий Media3.
 *
 * Пауза не завершает сессию. Переход на другой трек завершает прежний
 * `playId`, а фактическое время прослушивания считается по монотонным часам,
 * поэтому перемотка не увеличивает его скачком.
 */
class PlaybackFeedbackTracker(
    private val remote: PlaybackRemoteSource,
    private val scope: CoroutineScope,
    private val isTrackCached: (String) -> Boolean,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    private val newPlayId: () -> String = { UUID.randomUUID().toString() }
) {
    private val lock = Any()
    private val reports = Channel<Report>(Channel.UNLIMITED)
    private var session: Session? = null

    init {
        scope.launch {
            for (report in reports) {
                remote.send(report.type, report.request)
            }
        }
    }

    fun onMediaItemTransition(
        mediaItem: MediaItem?,
        reason: Int,
        isPlaying: Boolean,
        currentPositionMs: Long,
        durationMs: Long
    ) {
        val metadata = mediaItem?.toPlaybackMetadata()
        val now = elapsedRealtime()
        val outgoing = synchronized(lock) {
            val current = session
            if (
                current != null &&
                metadata != null &&
                current.matches(metadata) &&
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
            ) {
                current.updatePosition(currentPositionMs, durationMs)
                current.setPlaying(isPlaying, now)
                return@synchronized listOfNotNull(current.startReport())
            }

            buildList {
                current?.finish(
                    now = now,
                    completed = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
                )?.let { add(it) }
                session = metadata?.toSession(
                    isPlaying = isPlaying,
                    currentPositionMs = currentPositionMs,
                    playerDurationMs = durationMs,
                    now = now
                )
                session?.startReport()?.let { add(it) }
            }
        }
        enqueue(outgoing)
    }

    fun onIsPlayingChanged(
        mediaItem: MediaItem?,
        isPlaying: Boolean,
        currentPositionMs: Long,
        durationMs: Long
    ) {
        val metadata = mediaItem?.toPlaybackMetadata() ?: return
        val now = elapsedRealtime()
        val outgoing = synchronized(lock) {
            var current = session
            val reports = mutableListOf<Report>()
            if (current == null || !current.matches(metadata)) {
                current?.finish(now, completed = false)?.let(reports::add)
                current = metadata.toSession(
                    isPlaying = false,
                    currentPositionMs = currentPositionMs,
                    playerDurationMs = durationMs,
                    now = now
                )
                session = current
            }
            current.updatePosition(currentPositionMs, durationMs)
            current.setPlaying(isPlaying, now)
            if (isPlaying) current.startReport()?.let(reports::add)
            reports
        }
        enqueue(outgoing)
    }

    fun onProgress(
        trackId: String?,
        currentPositionMs: Long,
        durationMs: Long
    ) {
        synchronized(lock) {
            session
                ?.takeIf { it.trackId == trackId }
                ?.updatePosition(currentPositionMs, durationMs)
        }
    }

    fun onPlaybackEnded(
        currentPositionMs: Long,
        durationMs: Long,
        completed: Boolean
    ) {
        val now = elapsedRealtime()
        val report = synchronized(lock) {
            session?.apply {
                updatePosition(currentPositionMs, durationMs)
            }?.finish(now, completed).also {
                session = null
            }
        }
        report?.let(::enqueue)
    }

    private fun PlaybackMetadata.toSession(
        isPlaying: Boolean,
        currentPositionMs: Long,
        playerDurationMs: Long,
        now: Long
    ): Session {
        val resolvedDuration = playerDurationMs.validDurationOrNull()
            ?: durationMs.validDurationOrNull()
            ?: 0L
        return Session(
            trackId = trackId,
            itemId = itemId,
            albumId = albumId,
            playlistId = playlistId,
            source = source,
            fromCache = isTrackCached(trackId),
            playId = newPlayId(),
            durationMs = resolvedDuration,
            lastPositionMs = currentPositionMs.coerceAtLeast(0L),
            playingSinceMs = if (isPlaying) now else null
        )
    }

    private fun MediaItem.toPlaybackMetadata(): PlaybackMetadata? {
        val extras = mediaMetadata.extras
        val albumId = extras?.getString(PLAY_AUDIO_ALBUM_ID)
        if (mediaId.isBlank() || albumId.isNullOrBlank()) {
            Log.w(
                TAG,
                "[toPlaybackMetadata] play-audio пропущен: " +
                    "для trackId=$mediaId отсутствует albumId"
            )
            return null
        }
        val resolvedExtras = requireNotNull(extras)
        return PlaybackMetadata(
            trackId = mediaId,
            itemId = resolvedExtras.getString(PLAY_AUDIO_ITEM_ID)
                ?: mediaId,
            albumId = albumId,
            playlistId = resolvedExtras.getString(PLAY_AUDIO_PLAYLIST_ID),
            source = resolvedExtras.getString(PLAY_AUDIO_SOURCE)
                ?: DEFAULT_PLAY_AUDIO_SOURCE,
            durationMs = resolvedExtras.getLong(PLAY_AUDIO_DURATION_MS, 0L)
        )
    }

    private fun enqueue(report: Report) {
        if (reports.trySend(report).isFailure) {
            Log.e(TAG, "[enqueue] Не удалось поставить play-audio в очередь")
        }
    }

    private fun enqueue(reports: List<Report>) {
        reports.forEach(::enqueue)
    }

    private data class PlaybackMetadata(
        val trackId: String,
        val itemId: String,
        val albumId: String,
        val playlistId: String?,
        val source: String,
        val durationMs: Long
    )

    private data class Session(
        val trackId: String,
        val itemId: String,
        val albumId: String,
        val playlistId: String?,
        val source: String,
        val fromCache: Boolean,
        val playId: String,
        var durationMs: Long,
        var lastPositionMs: Long,
        var listenedMs: Long = 0L,
        var playingSinceMs: Long?,
        var startSent: Boolean = false
    ) {
        fun matches(metadata: PlaybackMetadata): Boolean =
            itemId == metadata.itemId &&
                trackId == metadata.trackId &&
                albumId == metadata.albumId &&
                playlistId == metadata.playlistId &&
                source == metadata.source

        fun updatePosition(positionMs: Long, playerDurationMs: Long) {
            if (positionMs >= 0L && positionMs != C.TIME_UNSET) {
                lastPositionMs = positionMs
            }
            playerDurationMs.validDurationOrNull()?.let {
                durationMs = it
            }
        }

        fun setPlaying(isPlaying: Boolean, now: Long) {
            if (isPlaying) {
                if (playingSinceMs == null) playingSinceMs = now
            } else {
                accumulate(now)
            }
        }

        fun startReport(): Report? {
            if (playingSinceMs == null || startSent) return null
            startSent = true
            return Report(
                type = PlaybackReportType.START,
                request = request(
                    trackLengthSeconds = 0,
                    totalPlayedSeconds = 0.0,
                    endPositionSeconds = durationMs.toSeconds()
                )
            )
        }

        fun finish(now: Long, completed: Boolean): Report? {
            accumulate(now)
            if (!startSent) return null

            val resolvedPositionMs = if (completed && durationMs > 0L) {
                durationMs
            } else {
                lastPositionMs
            }
            val boundedListenedMs = if (durationMs > 0L) {
                min(listenedMs, durationMs)
            } else {
                listenedMs
            }
            return Report(
                type = if (completed) {
                    PlaybackReportType.FINISH
                } else {
                    PlaybackReportType.INTERRUPTED
                },
                request = request(
                    trackLengthSeconds = durationMs.toSeconds().toInt(),
                    totalPlayedSeconds = boundedListenedMs.toSeconds(),
                    endPositionSeconds = resolvedPositionMs.toSeconds()
                )
            )
        }

        private fun accumulate(now: Long) {
            playingSinceMs?.let { startedAt ->
                listenedMs += (now - startedAt).coerceAtLeast(0L)
            }
            playingSinceMs = null
        }

        private fun request(
            trackLengthSeconds: Int,
            totalPlayedSeconds: Double,
            endPositionSeconds: Double
        ): PlayAudioRequest = PlayAudioRequest(
            trackId = trackId,
            source = source,
            albumId = albumId,
            playlistId = playlistId,
            fromCache = fromCache,
            playId = playId,
            trackLengthSeconds = trackLengthSeconds,
            totalPlayedSeconds = totalPlayedSeconds,
            endPositionSeconds = endPositionSeconds
        )
    }

    private data class Report(
        val type: PlaybackReportType,
        val request: PlayAudioRequest
    )

    private companion object {
        const val TAG = "PlaybackFeedbackTracker"
    }
}

private fun Long.validDurationOrNull(): Long? =
    takeIf { it > 0L && it != C.TIME_UNSET }

private fun Long.toSeconds(): Double = this / 1_000.0
