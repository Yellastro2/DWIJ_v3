package com.yellastrodev.dwij.data.repo

import android.util.Log
import com.yellastrodev.dwij.data.entities.dTracklist
import com.yellastrodev.dwij.data.entities.dYaTrack
import com.yellastrodev.dwij.data.entities.dYaWave
import com.yellastrodev.dwij.data.entities.toEntity
import com.yellastrodev.dwij.data.source.WaveRemoteSource
import com.yellastrodev.yandexmusiclib.entities.TrackShort
import com.yellastrodev.yandexmusiclib.network.YamResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class WaveRepository(
    val remote: WaveRemoteSource,
    val trackRepository: TrackRepository,
    val playerRepository: PlayerRepository
) {

    val TAG = "WaveRepository"

    private var curentWave: dYaWave? = null

    suspend fun getWave(dTracklist: dTracklist?): List<dYaTrack> {
        val result = remote.getWave(dTracklist?.getWaveId() ?: "user:onyourwave")
        when(result){
            is YamResult.Success -> {
                curentWave = dYaWave(
                    radioSessionId = result.value.station,
                    batchId = result.value.batchId,
                    tracks = result.value.tracks.map { TrackShort(it.id) }
                )
                dTracklist?. let{
                    curentWave!!.title =  "${it.getDTitle()} волна"
                } ?: run {
                    curentWave!!.title =  "Волна"

                }
                val trackList = result.value.tracks.map { it.toEntity() }
                trackRepository.putTracks(trackList)
                return trackList
            }

            else -> {
                // обработка всех остальных случаев
                // например, логирование или возврат пустого списка
                ArrayList<dYaTrack>()
            }
        }

        return ArrayList<dYaTrack>()
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
        curentWave?.let{
            remote.sendWaveStarted(it)
        }

        observePlayerState()
        GlobalScope.launch {
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
        // подписка на state — создаём ровно здесь
        observeJob = playerRepository.state
            .onEach { state ->

                val currentId = playerRepository.currentTrack.value

                // обновляем позицию для текущего трека
                if (currentId == lastTrackId) {
                    lastTrackPosSec = (state.currentPosition / 1000).toInt()
                }

                // трек сменился
                if (currentId != lastTrackId) {
                    lastTrackId?.let { prevId ->
                        onTrackNext(prevId, lastTrackPosSec, lastTrackDuration)
                    }
                    onTrackStarted(currentId ?: "")
                    lastTrackId = currentId
                    lastTrackPosSec = 0
                    lastTrackDuration = (state.duration / 1000).toInt()
                }

            }
            .launchIn(GlobalScope) // или свой scope
    }

    private suspend fun onTrackStarted(trackId: String) {
        Log.d(TAG, "onTrackStarted: $trackId")
        curentWave?.let{
            remote.sendTrackStarted(it, trackId)
            //если позиция трека trackId в wave.tracks последняя
            if (it.tracks.last().id == trackId)
                updateWave(it, trackId)
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
                wave.batchId = result.value.batchId
                wave.tracks = wave.tracks +
                    result.value.tracks.map { TrackShort(it.id) }
                playerRepository.addTracks(dTracks)
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
