package com.yellastrodev.yandexmusiclib.download

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Вариант загрузки трека из `/download-info`.
 */
@Serializable
data class DownloadInfo(
    val codec: String,
    @SerialName("bitrateInKbps")
    val bitrateInKbps: Int,
    val gain: Boolean,
    val preview: Boolean,
    val downloadInfoUrl: String,
    val direct: Boolean
)
