package com.yellastrodev.dwij.playback.feedback

import com.yellastrodev.dwij.data.entities.MusicSource
import com.yellastrodev.dwij.data.source.PlaybackRemoteSource
import com.yellastrodev.yamusicsdk.YamLogger
import com.yellastrodev.yamusicsdk.tracks.PlayAudioRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.min

/**
 * Ведёт единственную сессию `/play-audio`.
 *
 * Класс не знает о Media3 и Android. Платформенный адаптер передаёт сюда
 * нормализованные метаданные, причину перехода и позиции воспроизведения.
 */
class PlaybackFeedbackTracker(
    private val remote: PlaybackRemoteSource,
    private val scope: CoroutineScope,
    private val isTrackCached: (String) -> Boolean,
    private val logger: YamLogger,
    private val elapsedRealtimeMs: () -> Long = {
        System.nanoTime() / NANOS_IN_MILLISECOND
    },
    private val newPlayId: () -> String = {
        UUID.randomUUID().toString()
    },
) {
    private val lock = Any()
    private val reports = Channel<Report>(Channel.UNLIMITED)

    private var session: Session? = null

    init {
        scope.launch {
            for (report in reports) {
                remote.send(
                    type = report.type,
                    request = report.request,
                )
            }
        }
    }

    fun onMediaItemTransition(
        metadata: PlaybackFeedbackMetadata?,
        reason: PlaybackTransitionReason,
        isPlaying: Boolean,
        currentPositionMs: Long,
        durationMs: Long?,
    ) {
        val acceptedMetadata = metadata?.acceptedOrNull()
        val now = elapsedRealtimeMs()

        val outgoing = synchronized(lock) {
            val current = session

            if (
                current != null &&
                acceptedMetadata != null &&
                current.matches(acceptedMetadata) &&
                reason == PlaybackTransitionReason.PLAYLIST_CHANGED
            ) {
                current.updatePosition(
                    positionMs = currentPositionMs,
                    playerDurationMs = durationMs,
                )
                current.setPlaying(
                    isPlaying = isPlaying,
                    now = now,
                )

                return@synchronized listOfNotNull(
                    current.startReport(),
                )
            }

            buildList {
                current?.finish(
                    now = now,
                    completed = reason == PlaybackTransitionReason.AUTO,
                )?.let(::add)

                session = acceptedMetadata?.toSession(
                    isPlaying = isPlaying,
                    currentPositionMs = currentPositionMs,
                    playerDurationMs = durationMs,
                    now = now,
                )

                session?.startReport()?.let(::add)
            }
        }

        enqueue(outgoing)
    }

    fun onIsPlayingChanged(
        metadata: PlaybackFeedbackMetadata?,
        isPlaying: Boolean,
        currentPositionMs: Long,
        durationMs: Long?,
    ) {
        val acceptedMetadata = metadata?.acceptedOrNull() ?: return
        val now = elapsedRealtimeMs()

        val outgoing = synchronized(lock) {
            var current = session
            val pendingReports = mutableListOf<Report>()

            if (
                current == null ||
                !current.matches(acceptedMetadata)
            ) {
                current?.finish(
                    now = now,
                    completed = false,
                )?.let(pendingReports::add)

                current = acceptedMetadata.toSession(
                    isPlaying = false,
                    currentPositionMs = currentPositionMs,
                    playerDurationMs = durationMs,
                    now = now,
                )

                session = current
            }

            current.updatePosition(
                positionMs = currentPositionMs,
                playerDurationMs = durationMs,
            )

            current.setPlaying(
                isPlaying = isPlaying,
                now = now,
            )

            if (isPlaying) {
                current.startReport()?.let(pendingReports::add)
            }

            pendingReports
        }

        enqueue(outgoing)
    }

    fun onProgress(
        trackId: String?,
        currentPositionMs: Long,
        durationMs: Long?,
    ) {
        synchronized(lock) {
            session
                ?.takeIf { current ->
                    current.trackId == trackId
                }
                ?.updatePosition(
                    positionMs = currentPositionMs,
                    playerDurationMs = durationMs,
                )
        }
    }

    fun onPlaybackEnded(
        currentPositionMs: Long,
        durationMs: Long?,
        completed: Boolean,
    ) {
        val now = elapsedRealtimeMs()

        val report = synchronized(lock) {
            session
                ?.apply {
                    updatePosition(
                        positionMs = currentPositionMs,
                        playerDurationMs = durationMs,
                    )
                }
                ?.finish(
                    now = now,
                    completed = completed,
                )
                .also {
                    session = null
                }
        }

        report?.let(::enqueue)
    }

    private fun PlaybackFeedbackMetadata.acceptedOrNull(): PlaybackFeedbackMetadata? {
        if (musicSource == MusicSource.LOCAL) {
            return null
        }

        if (
            isYandexAvailable == false &&
            isTrackCached(trackId)
        ) {
            logger.debug(
                TAG,
                "[acceptedOrNull] play-audio пропущен: " +
                    "Яндекс-трек недоступен в ЯМ и воспроизводится из кэша, " +
                    "trackId=$trackId",
            )
            return null
        }

        if (
            trackId.isBlank() ||
            albumId.isNullOrBlank()
        ) {
            logger.warning(
                TAG,
                "[acceptedOrNull] play-audio пропущен: " +
                    "для trackId=$trackId отсутствует albumId",
            )
            return null
        }

        return this
    }

    private fun PlaybackFeedbackMetadata.toSession(
        isPlaying: Boolean,
        currentPositionMs: Long,
        playerDurationMs: Long?,
        now: Long,
    ): Session {
        val resolvedDuration = playerDurationMs.validDurationOrNull()
            ?: durationMs.validDurationOrNull()
            ?: 0L

        return Session(
            trackId = trackId,
            itemId = itemId,
            albumId = requireNotNull(albumId),
            playlistId = playlistId,
            reportSource = reportSource,
            playId = newPlayId(),
            durationMs = resolvedDuration,
            lastPositionMs = currentPositionMs.coerceAtLeast(0L),
            playingSinceMs = if (isPlaying) now else null,
        )
    }

    private fun enqueue(report: Report) {
        if (reports.trySend(report).isFailure) {
            logger.error(
                TAG,
                "[enqueue] Не удалось поставить play-audio в очередь",
                null,
            )
        }
    }

    private fun enqueue(reports: List<Report>) {
        reports.forEach(::enqueue)
    }

    private data class Session(
        val trackId: String,
        val itemId: String,
        val albumId: String,
        val playlistId: String?,
        val reportSource: String,
        val playId: String,
        var durationMs: Long,
        var lastPositionMs: Long,
        var listenedMs: Long = 0L,
        var playingSinceMs: Long?,
        var startSent: Boolean = false,
    ) {
        fun matches(metadata: PlaybackFeedbackMetadata): Boolean {
            return itemId == metadata.itemId &&
                trackId == metadata.trackId &&
                albumId == metadata.albumId &&
                playlistId == metadata.playlistId &&
                reportSource == metadata.reportSource
        }

        fun updatePosition(
            positionMs: Long,
            playerDurationMs: Long?,
        ) {
            if (positionMs >= 0L) {
                lastPositionMs = positionMs
            }

            playerDurationMs.validDurationOrNull()?.let { validDuration ->
                durationMs = validDuration
            }
        }

        fun setPlaying(
            isPlaying: Boolean,
            now: Long,
        ) {
            if (isPlaying) {
                if (playingSinceMs == null) {
                    playingSinceMs = now
                }
            } else {
                accumulate(now)
            }
        }

        fun startReport(): Report? {
            if (
                playingSinceMs == null ||
                startSent
            ) {
                return null
            }

            startSent = true

            return Report(
                type = PlaybackReportType.START,
                request = request(
                    trackLengthSeconds = 0,
                    totalPlayedSeconds = 0.0,
                    endPositionSeconds = durationMs.toSeconds(),
                ),
            )
        }

        fun finish(
            now: Long,
            completed: Boolean,
        ): Report? {
            accumulate(now)

            if (!startSent) {
                return null
            }

            val resolvedPositionMs =
                if (completed && durationMs > 0L) {
                    durationMs
                } else {
                    lastPositionMs
                }

            val boundedListenedMs =
                if (durationMs > 0L) {
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
                    endPositionSeconds = resolvedPositionMs.toSeconds(),
                ),
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
            endPositionSeconds: Double,
        ): PlayAudioRequest {
            return PlayAudioRequest(
                trackId = trackId,
                source = reportSource,
                albumId = albumId,
                playlistId = playlistId,
                playId = playId,
                trackLengthSeconds = trackLengthSeconds,
                totalPlayedSeconds = totalPlayedSeconds,
                endPositionSeconds = endPositionSeconds,
            )
        }
    }

    private data class Report(
        val type: PlaybackReportType,
        val request: PlayAudioRequest,
    )

    private companion object {
        const val TAG = "PlaybackFeedbackTracker"
        const val NANOS_IN_MILLISECOND = 1_000_000L
    }
}

private fun Long?.validDurationOrNull(): Long? {
    return this?.takeIf { value -> value > 0L }
}

private fun Long.toSeconds(): Double {
    return this / 1_000.0
}
