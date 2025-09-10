package com.yellastrodev.yandexmusiclib.yUtils

import org.json.JSONArray
import org.json.JSONException

class yUtils {

	companion object {
		/**
		 * Преобразует JSON-массив в ArrayList.
		 */
		@Throws(JSONException::class)
		fun <T> getArray(fJson: JSONArray): ArrayList<T> {
			val f_array: ArrayList<T> = ArrayList()
			for (i in 0 until fJson.length()) {
				f_array.add(fJson[i] as T)
			}
			return f_array
		}
	}

}