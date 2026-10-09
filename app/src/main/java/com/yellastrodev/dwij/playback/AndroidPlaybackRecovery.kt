package com.yellastrodev.dwij.playback

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.yellastrodev.dwij.playback.feedback.PlaybackMetadataKeys
import com.yellastrodev.dwij.utils.PlayerEvent
import com.yellastrodev.dwij.utils.TrackChangeDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/** Ограничивает подготовку и каждый затык двадцатью секундами, затем пропускает трек без перезапуска. */
internal class AndroidPlaybackRecovery(
    private val player: Player,
    private val scope: CoroutineScope,
    private val stateStore: PlaybackStateStore,
    private val resetSource: () -> Unit,
    private val finishFeedback: () -> Unit,
) : Player.Listener {
    private var attempt: AndroidPlaybackAttempt? = null
    private var key: ItemKey? = null
    private var deadlineJob: Job? = null
    private var recoveryJob: Job? = null
    private var mutating = false
    private var released = false

    /** Отмена ручной команды не позволяет отложенному retry вернуть предыдущий трек. */
    fun onUserCommand() {
        clear()
        // Повторный выбор той же позиции может не породить ни одного события Media3.
        begin()
        if (player.playbackState == Player.STATE_READY) {
            attempt?.onReady()
            deadlineJob?.cancel()
        }
    }

    /** Метаданные обложки не создают новую попытку; новый элемент очереди создаёт. */
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (mutating || released) return
        if (currentKey() != key || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
            begin()
        }
    }

    /** Учитывает также перемотку из системной MediaSession, минующую PlayerService. */
    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        if (!mutating && !released && reason == Player.DISCONTINUITY_REASON_SEEK) {
            begin()
            val selected = attempt
            scope.launch {
                yield()
                if (!released && attempt === selected && selected != null &&
                    player.playbackState == Player.STATE_IDLE && player.playWhenReady) player.prepare()
            }
        }
    }

    /** Обычная остановка завершает попытку; IDLE после ошибки оставляет запланированный retry. */
    override fun onPlaybackStateChanged(playbackState: Int) {
        if (mutating || released) return
        if (playbackState == Player.STATE_ENDED ||
            (playbackState == Player.STATE_IDLE && player.playerError == null && attempt?.failed != true)) {
            clear()
        }
    }

    /** Обрабатывает согласованный снимок состояния после группы событий Media3. */
    override fun onEvents(player: Player, events: Player.Events) {
        if (mutating || released) return
        if (attempt != null && key != currentKey()) begin()
        if (attempt?.failed == true) return
        if (player.playbackState != Player.STATE_BUFFERING && player.playbackState != Player.STATE_READY) return
        if (attempt == null || key != currentKey()) begin()
        val current = attempt ?: return
        if (player.playbackState == Player.STATE_READY) current.onReady()
        else current.onBuffering(SystemClock.elapsedRealtime(), player.playWhenReady)
        scheduleDeadline(current)
    }

    /** После исчерпания повторов сегмента ошибка пропускает трек и сообщает о ней через общий snackbar. */
    override fun onPlayerError(error: PlaybackException) {
        if (mutating || released) return
        if (attempt == null || key != currentKey()) begin()
        attempt?.let { recover(it, "ошибка Media3, код=${error.errorCode}") }
    }

    /** Полностью отменяет задачи перед release плеера. */
    fun release() {
        released = true
        clear()
    }

    /** Выделяет новую попытку; индекс различает одинаковые песни в очереди. */
    private fun begin() {
        deadlineJob?.cancel()
        key = currentKey()
        attempt = key?.let { AndroidPlaybackAttempt(SystemClock.elapsedRealtime()) }
        attempt?.let(::scheduleDeadline)
    }

    /** При смене попытки старая coroutine проверяет идентичность и не управляет новым треком. */
    private fun clear() {
        deadlineJob?.cancel()
        recoveryJob?.cancel()
        attempt = null
        key = null
    }

    /** Перепланирует таймер по абсолютному сроку, а не от последнего BUFFERING. */
    private fun scheduleDeadline(current: AndroidPlaybackAttempt) {
        deadlineJob?.cancel()
        val deadline = current.deadlineMs() ?: return
        deadlineJob = scope.launch {
            delay((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
            recover(current, "истёк срок подготовки/ожидания данных")
        }
    }

    /** Останавливает неисправный источник и переходит дальше; текущую песню с начала не повторяет. */
    private fun recover(current: AndroidPlaybackAttempt, reason: String) {
        if (released || attempt !== current || key != currentKey() || !current.fail()) return
        deadlineJob?.cancel()
        val failedKey = key ?: return
        Log.w(TAG, "[skipFailedTrack] Индекс=${failedKey.index}, трек=${failedKey.id}: $reason")
        recoveryJob = scope.launch {
            // Не изменяем плеер внутри его текущей рассылки callback.
            yield()
            if (!isCurrent(current, failedKey)) return@launch
            stateStore.emit(PlayerEvent.ShowError("Не удалось загрузить трек. Переходим к следующему."))
            if (!isCurrent(current, failedKey)) return@launch
            finishFeedback()
            mutating = true
            try {
                player.stop()
                resetSource()
            } finally {
                mutating = false
            }
            stateStore.setPlaying(false)
            val next = player.currentTimeline.getNextWindowIndex(
                failedKey.index,
                if (player.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF else player.repeatMode,
                player.shuffleModeEnabled,
            )
            if (next == C.INDEX_UNSET || next == failedKey.index) {
                attempt = null
                key = null
                player.playWhenReady = false
                stateStore.setWantsToPlay(false)
                stateStore.completeTrackChange()
                Log.w(TAG, "[skipFailedTrack] Трек=${failedKey.id}: очередь закончилась")
                stateStore.emit(PlayerEvent.TrackListEnd("Playlist finished"))
                return@launch
            }
            Log.w(TAG, "[skipFailedTrack] Трек=${failedKey.id}: следующий=$next")
            stateStore.beginTrackChange(TrackChangeDirection.NEXT, player.playWhenReady)
            mutating = true
            try {
                player.seekTo(next, 0L)
                begin()
                player.prepare()
            } finally {
                mutating = false
            }
            // READY может быть получен синхронно для кэшированного/локального файла.
            attempt?.let {
                if (player.playbackState == Player.STATE_READY) it.onReady()
                scheduleDeadline(it)
            }
        }
    }

    /** Не допускает восстановления после ручного переключения, изменения очереди или release. */
    private fun isCurrent(current: AndroidPlaybackAttempt, expected: ItemKey): Boolean =
        !released && attempt === current && key == expected && currentKey() == expected

    /** Не включает обложку в идентичность элемента, чтобы artwork-update не сбрасывал дедлайн. */
    private fun currentKey(): ItemKey? = player.currentMediaItem?.let {
        ItemKey(player.currentMediaItemIndex, it.mediaId, it.localConfiguration?.uri?.toString(),
            it.mediaMetadata.extras?.getString(PlaybackMetadataKeys.PLAY_ITEM_ID))
    }

    /** Идентичность элемента очереди, независимая от обновления обложки. */
    private data class ItemKey(val index: Int, val id: String, val uri: String?, val playItemId: String?)

    private companion object { const val TAG = "AndroidPlaybackRecovery" }
}

/** Повторяет отказавшую загрузку до трёх раз; общий срок BUFFERING ограничивает recovery. */
internal class AndroidPlaybackLoadErrorPolicy : DefaultLoadErrorHandlingPolicy(3) {
    /** Повтор не меняет элемент очереди и не сбрасывает абсолютный двадцатисекундный срок. */
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
        if (loadErrorInfo.errorCount <= 3) super.getRetryDelayMsFor(loadErrorInfo)
        else C.TIME_UNSET
}
