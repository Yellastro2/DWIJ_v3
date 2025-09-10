package com.yellastrodev.yandexmusiclib.kot_utils


import com.yellastrodev.yandexmusiclib.kot_utils.yNetwork.Companion.NetResult
import com.yellastrodev.yandexmusiclib.YamApiClient
import com.yellastrodev.yandexmusiclib.YamApiClient.Companion.BASE_URL
import com.yellastrodev.yandexmusiclib.yUtils.yLog
import java.math.BigInteger
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException


abstract class yTrack() {


	abstract open val mId: String
	abstract val mTitle: String
//	abstract var m_Artist_Data: JSONArray
//	lateinit var artists: ArrayList<Unit>
	open val mAlbums = ArrayList<String>()
	abstract var mCover: String?

//

	companion object {
		open var LOG = yLog.log(yTrack::class.java.name)
		fun getMd5(input: String): String {
			return try {
				val md = MessageDigest.getInstance("MD5")
				val messageDigest = md.digest(input.toByteArray())
				val no = BigInteger(1, messageDigest)
				var hashtext = no.toString(16)
				while (hashtext.length < 32) {
					hashtext = "0$hashtext"
				}
				hashtext
			} catch (e: NoSuchAlgorithmException) {
				throw RuntimeException(e)
			}
		}

		fun get_XML_Field(f_body: String, f_field: String): String {
			val f_start = "<$f_field>"
			val f_res = f_body.substring(
				f_body.indexOf(f_start) + f_start.length,
				f_body.indexOf("</$f_field>")
			)
			return f_res
		}

		sealed class Mp3LinkResult {
			data class Success(val url: String) : Mp3LinkResult()
			data class Error(val cause: NetResult.Error) : Mp3LinkResult()
		}

		suspend fun mp3Link(fClient: YamApiClient, fId: String): Mp3LinkResult {
			val urlToRequest = "/tracks/$fId/download-info"

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

	fun getLink(): String {
//		https://music.yandex.ru/album/9378276/track/58567502
		return "${YamApiClient.WEB_URL}/album/${mAlbums[0]}/track/${mId}"
	}

//	init {
//		mId = m_Data.getString("id")
//		title = m_Data.getString("title")
//		val k_cover = "coverUri"
//		if (m_Data.has(k_cover)) {
//			m_Cover_Url = m_Data.getString("coverUri")
//		} else m_Cover_Url = ""
//		m_Artist_Data = m_Data.getJSONArray("artists")
//		val fJsAlbums = m_Data.getJSONArray("albums")
//		for (i in 0 until fJsAlbums.length()) {
//			mAlbums.add(yAlbum(fJsAlbums.getJSONObject(i)))
//		}
//
//	}


//	@WorkerThread
//	fun get_Cover_Bitmap(f_size: Int): Bitmap? {
//		try {
//			if (m_Cover_Url == "") return null
//			val f_url = m_Cover_Url.replace("%%", "$f_size" + "x$f_size")
//			val url = URL("https://$f_url");
//			val image = BitmapFactory.decodeStream(url.openConnection().getInputStream());
//			return image
//		} catch (e: IOException) {
//			LOG.warning(e.stackTraceToString());
//			return null
//		}
//
//	}
}
