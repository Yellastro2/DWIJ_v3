package com.yellastrodev.dwij.data.repo

import android.util.Log
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.yellastrodev.dwij.data.entities.dYaTrack
import com.yellastrodev.dwij.data.entities.dYaWave
import com.yellastrodev.dwij.data.entities.toEntity
import com.yellastrodev.dwij.data.source.WaveRemoteSourse
import com.yellastrodev.yandexmusiclib.YamApiClient
import com.yellastrodev.yandexmusiclib.entities.TrackShort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class WaveRepository(
    val remote: WaveRemoteSourse,
    val trackRepository: TrackRepository,
    val playerRepository: PlayerRepository
) {

    val TAG = "WaveRepository"

    private var curentWave: dYaWave? = null

    suspend fun getWave(): List<dYaTrack> {
        val result = remote.getWave()
        when(result){
            is YamApiClient.WaveResult.Success -> {
                curentWave = result.wave.toEntity()
                val trackList = result.trackList.map { it.toEntity() }
                trackRepository.putTracks(trackList)
                return trackList
            }

            YamApiClient.WaveResult.Error.AccessDenied -> TODO()
            YamApiClient.WaveResult.Error.NoInternet -> TODO()
            is YamApiClient.WaveResult.Error.Unknown -> TODO()
            YamApiClient.WaveResult.Error.netError -> TODO()
        }

    }

    // Храним job, чтобы можно было отменить снаружи
    private var observeJob: Job? = null

    suspend fun playWave(){
        val waveList = getWave()
        withContext(Dispatchers.Main) {
            playerRepository.playQueue(
                waveList,
                0,
                curentWave!!)
        }
        curentWave?.let{
            remote.sendWaveStarted(it, waveList.first().id)
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
            is YamApiClient.NextWaveResult.Success -> {
                val dTracks = result.trackList.map { tr -> tr.toEntity() }
                trackRepository.putTracks(dTracks)
                wave.batchId = result.newBatch
                wave.tracks = wave.tracks + result.trackList.map { TrackShort(it.id) }
                playerRepository.addTracks(dTracks)
                Log.d(TAG, "updateWave: ${wave.tracks.size}")
            }

            YamApiClient.NextWaveResult.Error.AccessDenied -> TODO()
            YamApiClient.NextWaveResult.Error.NoInternet -> TODO()
            is YamApiClient.NextWaveResult.Error.Unknown -> TODO()
            YamApiClient.NextWaveResult.Error.netError -> TODO()
        }
    }

    fun stopObserving() {
        observeJob?.cancel()
        observeJob = null
    }

    private val bufferSize = 5
    private val loadedTracks = mutableListOf<dYaTrack>()
    private var hasMore = true


    private suspend fun loadNextBatch() {
        curentWave?.let{

        }
        val newTracks = getWave()
        if (newTracks.isEmpty()) {
            hasMore = false
            return
        }
        loadedTracks.addAll(newTracks)
//        val mediaItems = newTracks.map { track -> track.toMediaItem() }
//        playerRepository.service?.player?.addMediaItems(mediaItems)
    }
}