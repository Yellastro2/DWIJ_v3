package com.yellastrodev.yandexmusiclib

import android.util.Log
import com.yellastrodev.yandexmusiclib.entities.YaPlaylist
import com.yellastrodev.yandexmusiclib.kot_utils.yNetwork
import com.yellastrodev.yandexmusiclib.yUtils.yUtils.Companion.getArray
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class YamApiClient(
	val mToken: String,
	val mUserID: String,
	val mLogin: String = "") {

//	val mapper = jacksonObjectMapper()

	companion object {
		val BASE_URL = "https://api.music.yandex.net"
		val BASE_URL_2 = "https://api.music.yandex.ru"

		val WEB_URL = "https://music.yandex.ru"

		val TYPE_PLAYLIST = "playlist"
		val TYPE_ARTIST = "artist"
		val TYPE_TRACK = "track"
		val TYPE_ALBUM = "album"
		val TAG = "yClient"
	}

	class FeedbackType{
		companion object{
			val RADIO_STARTED = "radioStarted"
			val TRACK_STARTED = "trackStarted"
			val TRACK_FINISHED = "trackFinished"
			val SKIP = "skip"
		}
	}

	suspend fun createPlaylist(fName: String, fPublic: Boolean = true){
		val url = "$BASE_URL/users/$mUserID/playlists/create"
		val f_args = JSONObject()
		f_args.put("title", fName)
		var fP = "public"
		if(!fPublic) fP = "private"
		f_args.put("visibility", fP)

		yNetwork.post(mToken,url,f_args)
	}

	/**
	 * Выполняет действие с отметкой «Мне нравится» для указанного объекта.
	 *
	 * Поддерживаемые типы объектов:
	 * - `track` — трек
	 * - `artist` — исполнитель
	 * - `playlist` — плейлист
	 * - `album` — альбом
	 *
	 * Для плейлистов идентификатор указывается в формате `owner_id:playlist_id`,
	 * где `playlist_id` — идентификатор плейлиста, а `owner_id` — уникальный идентификатор владельца.
	 *
	 * @param objectType Тип объекта (`track`, `artist`, `playlist`, `album`).
	 * @param ids Уникальный идентификатор объекта или объектов. Может быть строкой с одним ID
	 * либо списком ID, объединённых в строку.
	 * @param remove Если `true`, снимает отметку «Мне нравится», иначе — устанавливает её. По умолчанию `false`.
	 * @param userID Уникальный идентификатор пользователя. Если не указан, используется ID текущего пользователя.
	 *
	 * @return `true`, если запрос выполнен успешно, иначе `false`.
	 *
	 * @throws yandex_music.exceptions.YandexMusicError В случае ошибки при выполнении запроса.
	 */
	suspend fun likeAction(
		objectType: String,
		ids: String,
		remove: Boolean = false,
		userID: String = mUserID
	): Boolean {
		val action = if (remove) "remove" else "add-multiple"
		val url = "${BASE_URL}/users/${userID}/likes/${objectType}s/${action}"

		val fParams = JSONObject("{'${objectType}-ids': '$ids' }")
		val result = yNetwork.post(mToken, url, fParams)

		return if (objectType == "track") {
			result.has("revision")
		} else {
			result.has("ok")
		}
	}


	/**
	 * Запрашивает следующий трек в потоке (радиостанции) Rotor API.
	 *
	 * Метод используется для продолжения воспроизведения в рамках сессии станции.
	 * Станция задаётся в формате `<type>:<id>`, например: `track:1234`.
	 *
	 * Алгоритм продолжения цепочки треков:
	 * 1. Передать ID трека, который был до этого (первый в цепочке).
	 * 2. Отправить фидбек о завершении или пропуске предыдущего трека (`queue`).
	 * 3. Отправить фидбек о начале следующего трека.
	 * 4. Выполнить запрос получения треков — в ответе придут новые треки или произойдёт сдвиг цепочки на 1 элемент.
	 *
	 * Для работы станции необходимо:
	 * - Создать сессию через `/rotor/session/new` и получить `radio_session_id` и `sequence`.
	 * - Оповестить о начале работы станции через `/rotor/session/{radio_session_id}/feedback`.
	 * - Оповестить об окончании трека через `/rotor/session/{radio_session_id}/feedback`.
	 * - Получить новые треки через `/rotor/session/{radio_session_id}/tracks`.
	 *
	 * Все официальные клиенты выполняют запросы с `settings2 = true`.
	 *
	 * @param fStationId Идентификатор сессии станции (`radio_session_id`), полученный при создании сессии.
	 * @param fPrevTrack ID предыдущего трека (первого в цепочке).
	 * @param fPrevSecond Время воспроизведения предыдущего трека в секундах (не используется в текущей реализации, но может быть полезно для фидбека).
	 * @param fNextTrack ID следующего трека (второго в цепочке, для отправки фидбека о старте).
	 *
	 * @return JSON‑ответ API с информацией о следующих треках или сдвиге цепочки.
	 *
	 * @throws yandex_music.exceptions.YandexMusicError В случае ошибки при выполнении запроса.
	 */
	suspend fun getWaveNextTrack(
		fStationId: String,
		fPrevTrack: String,
		fPrevSecond: Int,
		fNextTrack: String
	): JSONObject {
		val fUrl = "$BASE_URL/rotor/session/${fStationId}/tracks"
		val fParams = JSONObject("{'settings2': 'True'}")
		val fQue = JSONArray().apply { put(fPrevTrack) }
		fParams.put("queue", fQue)

		return yNetwork.post(mToken, fUrl, fParams, contentType = "json")
	}


	suspend fun rotorStationFBRadioStarted(fStationId: String,
                                           fFrom: String,
                                           fBatch: String = "",): JSONObject{
		return feedback(fStationId,FeedbackType.RADIO_STARTED,fFrom=fFrom, fBatch = fBatch)
	}

	suspend fun rotorStationFBTrackStarted(fStationId: String,
                                           fTrack: String,
                                           fBatch: String = "",): JSONObject{
		return feedback(fStationId,FeedbackType.TRACK_STARTED,fTrack=fTrack, fBatch = fBatch)
	}

	suspend fun rotorStationFBTrackFinished(fStationId: String,
                                            fTrack: String,
                                            fBatch: String = "",
                                            fSeconds: Float = 0.1f): JSONObject{

//		val fSeconds: Float = 0.1f
		return feedback(fStationId,FeedbackType.TRACK_FINISHED,fTrack=fTrack,fSeconds=fSeconds,  fBatch = fBatch)
	}

	suspend fun rotorStationFBSkip(fStationId: String,
                                   fFrom: String,
                                   fSeconds: Float,
                                   fBatch: String = "",): JSONObject{
		return feedback(fStationId,FeedbackType.SKIP,fFrom=fFrom,fSeconds=fSeconds,  fBatch = fBatch)
	}

	/**
	 * Отправляет событие (фидбек) в сессию станции Rotor API.
	 *
	 * Примеры:
	 * - `station`: `user:onyourwave`, `genre:allrock`
	 * - `from`: `mobile-radio-user-123456789`
	 *
	 * Используется для уведомления сервера о действиях пользователя в рамках сессии станции:
	 * - начале или окончании трека
	 * - пропуске трека
	 * - других событиях воспроизведения
	 *
	 * **Параметры:**
	 * @param fStationId Идентификатор сессии станции (`radio_session_id`), полученный при создании сессии.
	 * @param fType Тип отправляемого события (например, `trackStarted`, `trackFinished`, `skip` и т.д.).
	 * @param fTrack (опционально) ID трека, к которому относится событие.
	 * @param fFrom (опционально) Источник запуска станции (например, `mobile-radio-user-123456789`).
	 * @param fSeconds (опционально) Количество секунд, проигранных до события.
	 * @param fBatch (опционально) Уникальный идентификатор партии треков (`batch-id`), возвращается при получении треков.
	 *
	 * **Возвращает:**
	 * JSON-ответ API с результатом обработки события.
	 *
	 * **Примечания:**
	 * - `timestamp` формируется автоматически в формате `yyyy-MM-dd'T'HH:mm:ss.SSSSSSZZZZZ` по системному времени.
	 * - Для корректной работы цепочки треков рекомендуется отправлять фидбек о завершении предыдущего трека
	 *   и начале следующего перед запросом новых треков.
	 *
	 * @throws yandex_music.exceptions.YandexMusicError В случае ошибки при выполнении запроса.
	 */
	suspend fun feedback(
		fStationId: String,
		fType: String,
		fTrack: String = "",
		fFrom: String = "",
		fSeconds: Float = 0f,
		fBatch: String = ""
	): JSONObject {
		val fUrl = "$BASE_URL/rotor/session/${fStationId}/feedback"

		val anotherTIME = DateTimeFormatter
			.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSZZZZZ")
			.withZone(ZoneId.systemDefault())
			.format(Instant.now())

		val fParams = JSONObject().apply {
			put("type", fType)
			put("timestamp", anotherTIME)
			if (fTrack.isNotEmpty()) put("trackId", fTrack)
			if (fSeconds != 0f) put("totalPlayedSeconds", fSeconds.toString())
			if (fFrom.isNotEmpty()) put("from", fFrom)
		}

		val fJson = JSONObject().apply {
			put("event", fParams)
		}

		val fData = JSONObject().apply {
			if (fBatch.isNotEmpty()) put("batch-id", fBatch)
		}

		return yNetwork.post(mToken, fUrl, fJson, contentType = "json")
	}


	suspend fun getPlaylistObj(kind: Int, user_id: String? = null): YaPlaylist {

		val f_json = getPlaylistJSON(kind,user_id)

		return Json {ignoreUnknownKeys = true }.decodeFromString(f_json.toString())
	}

    suspend fun getPlaylistJSON(kind: Int, user_id: String? = null): JSONObject {

		var f_user_id =if (user_id == null) {
			mUserID
		} else {
			user_id
		}

		val url = "$BASE_URL/users/$f_user_id/playlists/$kind"


		val result = yNetwork.get(mToken, url)
		Log.i("TAG_YAM", result.toString())

		result.getJSONObject("result").getJSONArray("tracks")
		val f_json = result.getJSONObject("result")

		return f_json
	}

	suspend fun getCover(adressPart: String, size: Int): InputStream? {
		return yNetwork.getCoverStream(mToken, adressPart, size)
	}

	suspend fun getCover(coverData: JSONObject, size: Int): InputStream? {
//		try {
		if(coverData.has("error"))
			return null
		if (coverData.getString("type") == "mosaic") {
			val f_adr_part = coverData
				.getJSONArray("itemsUri")
				.getString(0)
			return getCover(f_adr_part, size)
		}

		return null
	}

	suspend fun getCoverPazle(coverData: JSONObject, size: Int): Array<InputStream?>? {
		if(coverData.has("error"))
			return null
		val fList = Array(coverData.getJSONArray("itemsUri").length()) {
			val qUrl = coverData
				.getJSONArray("itemsUri")
				.getString(it)
			return@Array getCover(qUrl, size)
		}
		return fList
	}

	fun likePlaylist(fPlayList: YaPlaylist){
//		TODO
	}

	suspend fun changePlaylist(fPlList: String, fDif: JSONArray, fRev: Int): JSONObject {
		val url = "$BASE_URL/users/$mUserID/playlists/${fPlList}/change"
		val fData =  JSONObject("{'kind': ${fPlList}, 'revision': $fRev, 'diff': $fDif}")
		return yNetwork.post(mToken,url,fData)
	}

	suspend fun getRotorList(){
		val fPath = "$BASE_URL/rotor/stations/list"
		val fParam = JSONObject()
		fParam.put("language","ru")

		val result = yNetwork.get(mToken, fPath, fParam)
		return
	}

	/**
	 * Получает список объектов указанного типа, помеченных пользователем как «Мне нравится».
	 *
	 * Поддерживаемые типы объектов:
	 * - `track` — трек
	 * - `artist` — исполнитель
	 * - `playlist` — плейлист
	 * - `album` — альбом
	 *
	 * Если `fUserP` не указан, используется идентификатор текущего пользователя (`mUserID`).
	 *
	 * @param fType Тип объекта (`track`, `artist`, `playlist`, `album`).
	 * @param fUserP (опционально) Идентификатор пользователя. Если пустая строка — берётся текущий пользователь.
	 *
	 * @return JSON‑ответ API с данными понравившихся объектов.
	 *
	 * @throws yandex_music.exceptions.YandexMusicError В случае ошибки при выполнении запроса.
	 */
    suspend fun getLiked(fType: String, fUserP: String = ""): JSONObject {
		val fUser = if (fUserP.isEmpty()) mUserID else fUserP
		val fUrl = "${BASE_URL}/users/${fUser}/likes/${fType}s"
		return yNetwork.get(mToken, fUrl)
	}


	/**
	 * Создаёт новую сессию станции Rotor API и возвращает стартовый набор треков.
	 *
	 * Станция задаётся через параметр `seed` в формате:
	 * - `track:{track_id}` — станция, основанная на конкретном треке
	 * - `user:onyourwave` — персональная станция «Моя волна»
	 * - `genre:{genre_id}` — станция по жанру
	 *
	 * В запросе всегда передаётся `includeTracksInResponse = true`, чтобы в ответе сразу пришли треки.
	 *
	 * @param fTag Идентификатор источника (seed) для запуска станции.
	 *             Примеры: `"track:1234"`, `"user:onyourwave"`, `"genre:allrock"`.
	 *
	 * @return JSON‑ответ API с информацией о новой сессии и стартовыми треками.
	 *         В случае ошибки возвращается пустой `JSONObject`.
	 *
	 * @throws yandex_music.exceptions.YandexMusicError В случае ошибки при выполнении запроса.
	 */
	suspend fun wave(fTag: String): JSONObject {
		val fSess = "$BASE_URL/rotor/session/new"

		val fJson = JSONObject(
			"{'seeds': ['${fTag}'], 'includeTracksInResponse': 'true'}"
		)

		return try {
			yNetwork.post(mToken, fSess, fJson, contentType = "json")
		} catch (e: Exception) {
			e.printStackTrace()
			JSONObject("")
		}
	}


	/**
	 * Получает один или несколько объектов указанного типа из API.
	 *
	 * Поддерживаемые типы объектов:
	 * - `playlist` — плейлист
	 * - `artist` — исполнитель
	 * - `track` — трек
	 * - `album` — альбом
	 *
	 * **Особенности:**
	 * - Для плейлистов идентификатор указывается в формате `owner_id:playlist_id`,
	 *   где `playlist_id` — идентификатор плейлиста, а `owner_id` — уникальный идентификатор владельца.
	 * - Метод возвращает сокращённую модель плейлиста для отображения больших списков.
	 *   **Не** возвращает список треков внутри плейлиста.
	 *   Чтобы получить плейлист с заполненным полем `tracks`, используйте
	 *   `users_playlists` или `Playlist.fetch_tracks`.
	 * - Для треков добавляется параметр `with-positions = true`, чтобы вернуть позиции треков (если поддерживается API).
	 *
	 * @param fType Тип объекта (`playlist`, `artist`, `track`, `album`).
	 * @param fIds Массив идентификаторов объектов. Может содержать строки или числа.
	 *
	 * @return JSON‑ответ API с данными запрошенных объектов.
	 *
	 * @throws yandex_music.exceptions.YandexMusicError В случае ошибки при выполнении запроса.
	 */
	suspend fun getObjList(fType: String, fIds: JSONArray): JSONObject {
		val fParams = JSONObject().apply {
			put("${fType}-ids", fIds)
			if (fType == TYPE_TRACK) {
				put("with-positions", "True")
			}
		}

		val fUrl = "$BASE_URL/${fType}s${if (fType == TYPE_PLAYLIST) "/list" else ""}"
		return yNetwork.post(mToken, fUrl, fParams, true)
	}


	/**
	 * Получает список плейлистов пользователя, каждый плейлист в режиме превью, отсутствуют данные треков
	 *
	 * 	"result": [{
	 *         "owner": {
	 *             "uid": 172****66,
	 *             "login": "urlogin",
	 *             "name": "Ivan",
	 *             "sex": "male",
	 *             "verified": false
	 *         },
	 *         "playlistUuid": "80987136-e**4-7**b-902c-06e22a451b1a",
	 *         "available": true,
	 *         "uid": 17****566,
	 *         "kind": 1000,
	 *         "title": "Новый плейлист",
	 *         "revision": 4,
	 *         "snapshot": 4,
	 *         "trackCount": 3,
	 *         "visibility": "public",
	 *         "collective": false,
	 *         "created": "2023-06-01T21:34:17+00:00",
	 *         "modified": "2023-06-02T09:46:11+00:00",
	 *         "isBanner": false,
	 *         "isPremiere": false,
	 *         "durationMs": 575940,
	 *         "cover": {
	 *             "type": "mosaic",
	 *             "itemsUri": ["avatars.yandex.net/get-music-content/1781407/a49e1148.a.9976672-1/%%", "avatars.yandex.net/get-music-content/5503671/001702f9.a.21335470-1/%%"],
	 *             "custom": false
	 *         },
	 *         "ogImage": "avatars.yandex.net/get-music-content/1781407/a49e1148.a.9976672-1/%%",
	 *         "tags": [],
	 *         "customWave": {
	 *             "title": "Новый плейлист",
	 *             "animationUrl": "https:music-custom-wave-media.s3.yandex.net/base.json",
	 *             "position": "default",
	 *             "header": "Моя волна по плейлисту"
	 *         }
	 *     }]
	 *
	 */
	suspend fun getUserListPllistsJSON(user_id: String? = null): JSONArray {
		var f_user_id = ""
		if( user_id == null && mUserID != null) {
			f_user_id = mUserID
		} else if (user_id != null){
			f_user_id = user_id
		}

		val url = "$BASE_URL/users/$f_user_id/playlists/list"

		val result = yNetwork.get(mToken, url)
		Log.i("TAG_YAM",result.toString())

		return result.getJSONArray("result")
	}

	suspend fun getUserListPllists(user_id: String? = null): List<YaPlaylist> {
		val playlistJson = getUserListPllistsJSON(user_id)
 		val objectList = ArrayList<YaPlaylist>()
		for (q_pllist in getArray<JSONObject>(playlistJson)) {
			val qObj: YaPlaylist = Json {ignoreUnknownKeys = true }.decodeFromString(q_pllist.toString())
			objectList.add(qObj)
		}
		return objectList
	}



	suspend fun removePlaylist(fKindId: String): Boolean {
		val fUrl = "$BASE_URL/users/$mUserID/playlists/$fKindId/delete"
		val fRes = yNetwork.post(mToken,fUrl)
		return responseWrapper(fRes)
	}

	fun responseWrapper(fRes: JSONObject): Boolean {
		try {
			val fMsg = fRes.getString("result")
			return fMsg == "ok"
		}catch (e: Exception){
			e.printStackTrace()
			return false
		}

	}
}