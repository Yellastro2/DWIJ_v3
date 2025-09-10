package com.yellastrodev.yandexmusiclib.kot_utils

import org.json.JSONObject

class yAlbum (mData: JSONObject){
	val mId: Int
	val mTitle: String

	init {
		mId = mData.getInt("id")
		mTitle = mData.getString("title")
	}
}
//{
//            "id": 9754991,
//            "title": "Infinity",
//            "type": "single",
//            "metaType": "music",
//            "version": "Dubdogz & Bhaskar Edit",
//            "year": 2020,
//            "releaseDate": "2020-01-31T00:00:00+03:00",
//            "coverUri": "avatars.yandex.net/get-music-content/2266607/52b9517a.a.9754991-1/%%",
//            "ogImage": "avatars.yandex.net/get-music-content/2266607/52b9517a.a.9754991-1/%%",
//            "genre": "dance",
//            "trackCount": 1,
//            "recent": false,
//            "veryImportant": false,
//            "artists": [{
//                "id": 4298176,
//                "name": "Dubdogz",
//                "various": false,
//                "composer": false,
//                "cover": {
//                    "type": "from-album-cover",
//                    "uri": "avatars.yandex.net/get-music-content/2810397/b1fae4b8.a.10591621-1/%%",
//                    "prefix": "b1fae4b8.a.10591621-1"
//                },
//                "genres": [],
//                "disclaimers": []
//            }