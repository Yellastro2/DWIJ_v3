package com.yellastrodev.dwij.data.repo

import com.yellastrodev.dwij.data.entities.dTracklist
import com.yellastrodev.dwij.data.entities.MusicSource
import com.yellastrodev.dwij.data.entities.Song
import com.yellastrodev.dwij.data.entities.dYaWave
import com.yellastrodev.dwij.data.entities.toEntity
import com.yellastrodev.dwij.data.source.WaveRemoteSource
import com.yellastrodev.yamusicsdk.YamLogger
import com.yellastrodev.yamusicsdk.entities.TrackShort
import com.yellastrodev.yamusicsdk.network.YamResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

class WaveRepository(
    val remote: WaveRemoteSource,
    val trackRepository: TrackRepository,
    private val songRepository: SongRepository,
    val playerRepository: PlaybackQueue,
    private val isTrackCached: (String) -> Boolean,
    private val scope: CoroutineScope,
    private val logger: YamLogger
) {

    val TAG = "WaveRepository"

    private var curentWave: dYaWave? = null
    private val loadingGate = AtomicBoolean(false)
    private val mutableIsLoading = MutableStateFlow(false)

    /** Общий признак загрузки первой пачки волны для UI и защиты от повторных запросов. */
    val isLoading: StateFlow<Boolean> = mutableIsLoading.asStateFlow()

    suspend fun getWave(dTracklist: dTracklist?): List<Song> {
        return when (
            val result = remote.getWave(dTracklist?.getWaveId() ?: "user:onyourwave")
        ) {
            is YamResult.Success -> {
                curentWave = dYaWave(
                    radioSessionId = result.value.station,
                    batchId = result.value.batchId,
                    tracks = result.value.tracks.map { TrackShort(it.id) }
                )
                curentWave?.let { remote.sendWaveStarted(it) }
                dTracklist?. let{
                    curentWave!!.title =  "${it.getDTitle()} волна"
                } ?: run {
                    curentWave!!.title =  "Волна"

                }
                val trackList = result.value.tracks.map { it.toEntity() }
                trackRepository.putTracks(trackList)
                songRepository.songsForYandexTracks(trackList)
            }

            is YamResult.Failure -> {
                logger.error(TAG, "[getWave] Волну загрузить не удалось: ${result.error}")
                emptyList()
            }
        }
    }

    // Храним job, чтобы можно было отменить снаружи
    private var observeJob: Job? = null

    /**
     * Запускает волну в application-scope и сразу возвращает управление UI.
     *
     * В отличие от coroutine scope экрана, [scope] не отменяется при переходе в полный плеер.
     * Возвращает `false`, если первая пачка уже загружается.
     */
    fun requestWave(dtrackList: dTracklist? = null): Boolean {
        if (!tryStartLoading()) return false
        scope.launch {
            try {
                loadAndPlayWave(dtrackList)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logger.error(TAG, "[requestWave] Не удалось запустить волну", error)
            } finally {
                finishLoading()
            }
        }
        return true
    }

    /** Запускает Яндекс-волну, построенную вокруг конкретного трека. */
    fun requestTrackWave(
        trackId: String,
        trackTitle: String,
    ): Boolean {
        val normalizedTrackId = trackId.trim()

        if (normalizedTrackId.isEmpty()) {
            logger.warning(
                TAG,
                "[requestTrackWave] Не указан идентификатор трека",
            )
            return false
        }

        return requestWave(
            TrackWaveSeed(
                trackId = normalizedTrackId,
                trackTitle = trackTitle,
            ),
        )
    }

    /**
     * Загружает и запускает первую пачку в coroutine вызывающего слоя.
     *
     * Этот вариант нужен автоматическому продолжению волны в [com.yellastrodev.dwij.data.repo.PlayerRepository].
     */
    suspend fun playWave(dtrackList: dTracklist? = null) {
        if (!tryStartLoading()) return
        try {
            loadAndPlayWave(dtrackList)
        } finally {
            finishLoading()
        }
    }

    /** Выполняет сетевую загрузку и публикует подготовленную очередь в общем репозитории плеера. */
    private suspend fun loadAndPlayWave(dtrackList: dTracklist?) {
        logger.debug(TAG, "[loadAndPlayWave] Запрашиваем ${dtrackList?.getWaveId() ?: "свою"} волну")
        val waveList = getWave(dtrackList)
        if (waveList.isEmpty()) {
            logger.warning(TAG, "[loadAndPlayWave] Сервер не вернул треки волны")
            return
        }
        withContext(Dispatchers.Main) {
            playerRepository.playQueue(
                waveList,
                0,
                requireNotNull(curentWave),
            )
        }
        logger.debug(TAG, "[loadAndPlayWave] Очередь волны запущена: треков=${waveList.size}")
        observePlayerState()
        scope.launch {
            playerRepository.isShuffleBlock
                .first { isBlocked -> !isBlocked }
            stopObserving()
        }
    }

    /** Атомарно занимает единственный слот загрузки волны и публикует его UI. */
    private fun tryStartLoading(): Boolean {
        val accepted = loadingGate.compareAndSet(false, true)
        if (accepted) {
            mutableIsLoading.value = true
        } else {
            logger.debug(TAG, "[tryStartLoading] Загрузка волны уже идёт")
        }
        return accepted
    }

    /** Освобождает слот только после установки очереди или полного завершения ошибки. */
    private fun finishLoading() {
        mutableIsLoading.value = false
        loadingGate.set(false)
    }

    private var lastTrackId: String? = null
    private var lastTrackFeedbackEnabled = false
    private var lastTrackPosSec: Int = 0
    private var lastTrackDuration: Int = 0
    private val skipOffset = 10

    /**
     * слушает переключение треков, отправляет фидбеки в ремот о начале трека и конце\скипе трека
     */
    fun observePlayerState() {
        observeJob?.cancel()
        observeJob = playerRepository.state
            .onEach { state ->
                val playbackTrack = playerRepository.currentPlaybackTrack.value
                val currentId = playbackTrack
                    ?.takeIf { track -> track.source == MusicSource.YANDEX }
                    ?.yandexTrack
                    ?.id
                val isUnavailableCached =
                    currentId != null &&
                        playbackTrack?.yandexTrack?.available == false &&
                        isTrackCached(currentId)
                val shouldSendCurrentFeedback =
                    currentId != null && !isUnavailableCached

                // обновляем позицию для текущего трека
                if (currentId == lastTrackId) {
                    lastTrackPosSec = (state.currentPosition / 1000).toInt()
                    val durationSeconds = (state.duration / 1000).toInt()
                    if (durationSeconds > 0) {
                        lastTrackDuration = durationSeconds
                    }
                }

                // трек сменился
                if (currentId != lastTrackId) {
                    lastTrackId?.takeIf { lastTrackFeedbackEnabled }?.let { prevId ->
                        onTrackNext(prevId, lastTrackPosSec, lastTrackDuration)
                    }
                    currentId?.let { trackId ->
                        onTrackStarted(trackId, sendFeedback = shouldSendCurrentFeedback)
                    }
                    lastTrackId = currentId
                    lastTrackFeedbackEnabled = shouldSendCurrentFeedback
                    lastTrackPosSec = 0
                    lastTrackDuration = (state.duration / 1000).toInt()
                }

            }
            .launchIn(scope)
    }

    private suspend fun onTrackStarted(trackId: String, sendFeedback: Boolean) {
        logger.debug(TAG, "[onTrackStarted] trackId=$trackId, feedback=$sendFeedback")
        curentWave?.let{
            if (sendFeedback) {
                remote.sendTrackStarted(it, trackId)
            } else {
                logger.debug(
                    TAG,
                    "[onTrackStarted] Rotor feedback пропущен: " +
                        "трек недоступен в ЯМ и находится в кэше, " +
                        "trackId=$trackId",
                )
            }
            // Следующую пачку запрашиваем при старте последнего трека очереди.
            if (it.tracks.lastOrNull()?.id == trackId) {
                updateWave(it, trackId)
            }
        }
    }


    suspend fun onTrackNext(trackId: String, position: Int, duration: Int) {
        logger.debug(TAG, "onTrackNext: $trackId $position of $duration")
        curentWave?.let{
            if (position + skipOffset < duration)
                remote.sendTrackSkip(it, trackId, position)
            else
                remote.sendTrackFinished(it, trackId, position)

        }

    }

    private suspend fun updateWave(wave: dYaWave, lastTrackId: String) {
        logger.debug(TAG, "updateWave: $lastTrackId")
        val result = remote.getNextTracks(wave, lastTrackId)
        when(result){
            is YamResult.Success -> {
                wave.batchId = result.value.batchId

                val knownTrackIds = wave.tracks
                    .mapTo(mutableSetOf(), TrackShort::id)
                val uniqueTracks = result.value.tracks.filter { track ->
                    knownTrackIds.add(track.id)
                }
                val duplicateCount =
                    result.value.tracks.size - uniqueTracks.size

                if (duplicateCount > 0) {
                    logger.warning(
                        TAG,
                        "[updateWave] Отфильтровано повторов=$duplicateCount, " +
                            "получено=${result.value.tracks.size}",
                    )
                }

                if (uniqueTracks.isEmpty()) {
                    logger.warning(
                        TAG,
                        "[updateWave] Новая партия не содержит уникальных треков",
                    )
                    return
                }

                val dTracks = uniqueTracks.map { tr -> tr.toEntity() }
                trackRepository.putTracks(dTracks)
                val songs = songRepository.songsForYandexTracks(dTracks)
                wave.tracks = wave.tracks +
                    uniqueTracks.map { TrackShort(it.id) }
                playerRepository.addTracks(songs)
                logger.debug(TAG, "updateWave: ${wave.tracks.size}")
            }

            is YamResult.Failure -> {
                logger.error(TAG, "[updateWave] Новые треки не загружены: ${result.error}")
            }
        }
    }

    fun stopObserving() {
        observeJob?.cancel()
        observeJob = null
    }

}


/** Адаптирует Яндекс-трек к существующему контракту seed-объекта rotor. */
private data class TrackWaveSeed(
    val trackId: String,
    val trackTitle: String,
) : dTracklist {

    override fun getdId(): String = trackId

    override fun getDTitle(): String = trackTitle.ifBlank { "Трек" }

    override fun getType(): String = TYPE

    override fun getWaveId(): String = "track:$trackId"

    private companion object {
        const val TYPE = "ya_track_wave_seed"
    }
}
