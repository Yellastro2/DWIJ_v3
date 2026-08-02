package com.yellastrodev.dwij.data.repo

import android.util.Log
import com.yellastrodev.dwij.data.entities.dTracklist
import com.yellastrodev.dwij.data.entities.Song
import com.yellastrodev.dwij.data.entities.dYaWave
import com.yellastrodev.dwij.data.entities.toEntity
import com.yellastrodev.dwij.data.source.WaveRemoteSource
import com.yellastrodev.yandexmusiclib.entities.TrackShort
import com.yellastrodev.yandexmusiclib.network.YamResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class WaveRepository(
    val remote: WaveRemoteSource,
    val trackRepository: TrackRepository,
    private val songRepository: SongRepository,
    val playerRepository: PlayerRepository,
    private val scope: CoroutineScope
) {

    val TAG = "WaveRepository"

    private var curentWave: dYaWave? = null

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
                Log.e(TAG, "[getWave] Волну загрузить не удалось: ${result.error}")
                emptyList()
            }
        }
    }

    // Храним job, чтобы можно было отменить снаружи
    private var observeJob: Job? = null

    suspend fun playWave(dtrackList: dTracklist? = null) {
        Log.d(TAG, "playWave: ${dtrackList?.getWaveId()?: "own"}")
        val waveList = getWave(dtrackList)
        if (waveList.isEmpty())
            return
        withContext(Dispatchers.Main) {
            playerRepository.playQueue(
                waveList,
                0,
                curentWave!!)
        }
        observePlayerState()
        scope.launch {
            playerRepository.isShuffleBlock
                .first { isBlocked -> !isBlocked } // ждём пока станет false
            stopObserving()
        }
    }

    private var lastTrackId: String? = null
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

                val currentId = playerRepository.currentSong.value
                    ?.yandexInstances
                    ?.firstOrNull()
                    ?.track
                    ?.id

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
                    lastTrackId?.let { prevId ->
                        onTrackNext(prevId, lastTrackPosSec, lastTrackDuration)
                    }
                    currentId?.let { trackId -> onTrackStarted(trackId) }
                    lastTrackId = currentId
                    lastTrackPosSec = 0
                    lastTrackDuration = (state.duration / 1000).toInt()
                }

            }
            .launchIn(scope)
    }

    private suspend fun onTrackStarted(trackId: String) {
        Log.d(TAG, "onTrackStarted: $trackId")
        curentWave?.let{
            remote.sendTrackStarted(it, trackId)
            // Следующую пачку запрашиваем при старте последнего трека очереди.
            if (it.tracks.lastOrNull()?.id == trackId) {
                updateWave(it, trackId)
            }
        }
    }


    suspend fun onTrackNext(trackId: String, position: Int, duration: Int) {
        Log.d(TAG, "onTrackNext: $trackId $position of $duration")
        curentWave?.let{
            if (position + skipOffset < duration)
                remote.sendTrackSkip(it, trackId, position)
            else
                remote.sendTrackFinished(it, trackId, position)

        }

    }

    private suspend fun updateWave(wave: dYaWave, lastTrackId: String) {
        Log.d(TAG, "updateWave: $lastTrackId")
        val result = remote.getNextTracks(wave, lastTrackId)
        when(result){
            is YamResult.Success -> {
                val dTracks = result.value.tracks.map { tr -> tr.toEntity() }
                trackRepository.putTracks(dTracks)
                val songs = songRepository.songsForYandexTracks(dTracks)
                wave.batchId = result.value.batchId
                wave.tracks = wave.tracks +
                    result.value.tracks.map { TrackShort(it.id) }
                playerRepository.addTracks(songs)
                Log.d(TAG, "updateWave: ${wave.tracks.size}")
            }

            is YamResult.Failure -> {
                Log.e(TAG, "[updateWave] Новые треки не загружены: ${result.error}")
            }
        }
    }

    fun stopObserving() {
        observeJob?.cancel()
        observeJob = null
    }

}
