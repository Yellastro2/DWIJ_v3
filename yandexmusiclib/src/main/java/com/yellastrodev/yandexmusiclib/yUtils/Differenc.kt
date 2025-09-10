package com.yellastrodev.yandexmusiclib.yUtils

import com.yellastrodev.yandexmusiclib.kot_utils.iYaTrack
import com.yellastrodev.yandexmusiclib.kot_utils.yTrack
import org.json.JSONArray
import org.json.JSONObject

class Differenc {
//	Класс, представляющий обёртку над созданием данных для запроса изменения плейлиста.
//
//    Note:
//        Результатом является перечень (массив) операций, которые будут применены к плейлисту.
//
//        Конечной разницей (набором операций) является JSON, который будет отправлен в теле запроса.
//
//    Attributes:
//        operations (:obj:`list` из :obj:`dict`): Перечень операция для изменения плейлиста.

	companion object{
		enum class Operation(s: String) {
//			Класс перечисления типов операций для изменения плейлиста.
//
//    		Note:
//        	Существует две операции: вставка, удаление.

			INSERT("insert"),
			DELETE("delete")
		}
	}
	val mOperations = ArrayList<JSONObject>()

//	def __init__(self):
//	self.operations = []

	fun toJSON(): JSONArray {
//		Сериализация всех операций над плейлистом.
//
//        Returns:
//            :obj:`str`: Сформированное тело для запроса.
		return JSONArray(mOperations)
	}
//	def to_json(self) -> str:
//	"""
//        """
//	return json.dumps(self.operations, ensure_ascii=not ujson)

	fun addDelete(fFrom: Int,fTo: Int): Differenc {
//		Добавление операции удаления.
//
//        Note:
//            Передаётся диапазон для удаления треков.
//
//        Args:
//            from_ (:obj:`int`): С какого индекса.
//            to (:obj:`int`): По какой индекс. не включительно
//
//        Returns:
//            :obj:`yandex_music.utils.difference.Difference`: Набор операций над плейлистом.

		val fOperation = JSONObject("{" +
				"'op': ${Operation.DELETE}," +
				"'from': $fFrom," +
				"'to': $fTo}")
		mOperations.add(fOperation)
		return this@Differenc
	}
//	def add_delete(self, from_: int, to: int) -> 'Difference':
//	"""
//        """
//	operation =
//	{ 'op': Operation.DELETE.value, 'from': from_, 'to': to }
//
//	self.operations.append(operation)
//	return self
	fun addInsert(fAt: Int,fTrackId: String, fAlbId: String): Differenc {

		val fJsonTracks = JSONArray()

		fJsonTracks.put(
			JSONObject("{ 'id': '${fTrackId}'," +
					" 'albumId': ${fAlbId} }")
		)


		val fOperation = JSONObject("{" +
				"'op': ${Operation.INSERT}," +
				"'at': $fAt," +
				"'tracks': $fJsonTracks}")
		mOperations.add(fOperation)
		return this@Differenc

	}

//	fun addInsert(fAt: Int,vararg fTracks: yTrack): Differenc {
////		Добавление операции вставки.
////
////        Note:
////            В `tracks` передаётся словарь с двумя ключами: `id`, `album_id`. Это нужно для формирования операции.
////
////        Args:
////            at (:obj:`int`): Индекс для вставки.
////            tracks (:obj:`dict` | :obj:`list: из :obj:`dict`): Словарь уникальными идентификаторами треков.
////
////        Returns:
////            :obj:`yandex_music.utils.difference.Difference`: Набор операций над плейлистом.
////	# TODO (MarshalX) принимать TrackId, а так же строку и сплитить её по ":".
////	#  При отсутствии album_id кидать исключение.
////	#  https://github.com/MarshalX/yandex-music-api/issues/558
//		val fJsonTracks = JSONArray()
//		for(qTrack in fTracks){
//			fJsonTracks.put(
//				JSONObject("{ 'id': '${qTrack.mId}'," +
//					" 'albumId': ${qTrack.mAlbums.get(0).mId} }")
//			)
//		}
//
//		val fOperation = JSONObject("{" +
//				"'op': ${Operation.INSERT}," +
//				"'at': $fAt," +
//				"'tracks': $fJsonTracks}")
//		mOperations.add(fOperation)
//		return this@Differenc
//
//	}



//	operation =
//	{ 'op': Operation.INSERT.value, 'at': at, 'tracks': [] }
//
//	for track in tracks:
//	# TODO (MarshalX) replace to normal TrackId
//	object
//	#  https://github.com/MarshalX/yandex-music-api/issues/558
//	track = type('TrackId', (), track)
//
//	operation['tracks'].append(
//	{ 'id': track.id, 'albumId': track.album_id })
//
//	self.operations.append(operation)
//	return self

}