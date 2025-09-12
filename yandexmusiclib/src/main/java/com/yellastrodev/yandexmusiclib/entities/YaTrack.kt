package com.yellastrodev.yandexmusiclib.entities

import com.yellastrodev.yandexmusiclib.YamApiClient
import com.yellastrodev.yandexmusiclib.YamApiClient.Companion.BASE_URL
import com.yellastrodev.yandexmusiclib.kot_utils.yNetwork
import com.yellastrodev.yandexmusiclib.kot_utils.yNetwork.Companion.NetResult
import com.yellastrodev.yandexmusiclib.kot_utils.yTrack.Companion.Mp3LinkResult
import com.yellastrodev.yandexmusiclib.kot_utils.yTrack.Companion.getMd5
import com.yellastrodev.yandexmusiclib.kot_utils.yTrack.Companion.get_XML_Field
import com.yellastrodev.yandexmusiclib.yUtils.IntOrStringAsStringSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
class YaTrack(
    @Serializable(with = IntOrStringAsStringSerializer::class)
    val id: String,
    val title: String,
    val available: Boolean,
    val availableForPremiumUsers: Boolean? = null,
    val availableFullWithoutPermission: Boolean? = null,
//    val availableForOptions: List<Options> = listOf(),
    val durationMs: Int? = null,
    val previewDurationMs: Int? = null,
    val storageDir: String? = null,
    val fileSize: Int? = null,
//    val r128: R128? = null,
    val artists: List<YaArtist>,
    val albums: List<YaAlbum>,
    val trackSource: String? = null,
//    val major: Major? = null,
    @SerialName("ogImage")
    val ogImageUri: String? = null,
    val coverUri: String? = null,
//    val lyricsAvailable: Boolean? = null,
//    val lyricsInfo: LyricsInfo? = null,
//    val derivedColors: DerivedColors? = null,
//    val type: AlbumType? = null,
//    val rememberPosition: Boolean? = null,
//    val trackSharingFlag: TrackSharingFlag? = null,
//    val contentWarning: String? = null
) {

    suspend fun mp3Link(fClient: YamApiClient): Mp3LinkResult {
        val urlToRequest = "/tracks/$id/download-info"

        val result = yNetwork.getRepeatWithHeader(fClient.mToken, BASE_URL + urlToRequest)

        if (result is NetResult.Success) {
            val resultGetXml = yNetwork.getXml(
                fClient.mToken,
                result.json.getJSONArray("result").getJSONObject(0)
                    .getString("downloadInfoUrl")
            )

            // Generating mp3 link
            val host = get_XML_Field(resultGetXml, "host")
            val path = get_XML_Field(resultGetXml, "path")
            val ts = get_XML_Field(resultGetXml, "ts")
            val s = get_XML_Field(resultGetXml, "s")
            val secret =
                String.format("XGRlBW9FXlekgbPrRHuSiA%s%s", path.substring(1, path.length - 1), s)
            val sign = getMd5(secret)

            return Mp3LinkResult.Success(String.format(
                "https://%s/get-%s/%s/%s/%s",
                host,
                "mp3",
                sign,
                ts,
                path
            ))
        } else
            return Mp3LinkResult.Error(result as NetResult.Error)
    }
}