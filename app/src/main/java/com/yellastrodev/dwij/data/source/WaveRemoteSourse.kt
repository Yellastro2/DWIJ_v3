package com.yellastrodev.dwij.data.source

import android.util.Log
import com.yellastrodev.yandexmusiclib.YamApiClient

class WaveRemoteSourse(private val client: YamApiClient) {

    val TAG = "WaveRemoteSourse"

    suspend fun getWave(tag: String = "user:onyourwave"): YamApiClient.WaveResult {
        Log.d(TAG, "getWave: $tag")
        val result = client.getWavetObj(tag)
//        Log.d(TAG, "getWave: $result")
        return result
    }
}