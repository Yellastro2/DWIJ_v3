package com.yellastrodev.yandexmusiclib.rotor

import com.yellastrodev.yandexmusiclib.entities.YaTrack
import com.yellastrodev.yandexmusiclib.entities.YaTrackWrap
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Очередная партия треков станции Rotor.
 */
data class RotorBatch(
    val station: String,
    val batchId: String,
    val tracks: List<YaTrack>
)

enum class RotorFeedbackType(internal val apiValue: String) {
    RADIO_STARTED("radioStarted"),
    TRACK_STARTED("trackStarted"),
    TRACK_FINISHED("trackFinished"),
    SKIP("skip")
}

@Serializable
internal data class RotorTracksPayload(
    val batchId: String,
    val sequence: List<YaTrackWrap> = emptyList()
)

@Serializable
internal data class RotorStationResultPayload(
    val station: RotorStationPayload
)

@Serializable
internal data class RotorStationPayload(
    val id: RotorStationIdPayload,
    @SerialName("idForFrom")
    val idForFrom: String
)

@Serializable
internal data class RotorStationIdPayload(
    val type: String,
    val tag: String
) {
    val value: String
        get() = "$type:$tag"
}
