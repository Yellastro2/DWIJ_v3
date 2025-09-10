package com.yellastrodev.yandexmusiclib

import com.yellastrodev.yandexmusiclib.exeptions.NoTokenFoundException
import com.yellastrodev.yandexmusiclib.kot_utils.yNetwork
import com.yellastrodev.yandexmusiclib.kot_utils.yNetwork.Companion.getRepeatWithHeader
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ExecutionException

class yAccount {

    companion object {
        const val BaseUrl: String = "https://api.music.yandex.net:443"

        @Throws(
            IOException::class,
            InterruptedException::class,
            ExecutionException::class,
            NoTokenFoundException::class,
            JSONException::class
        )
        suspend fun showInformAccount(fToken: String): yNetwork.Companion.NetResult {
            if (fToken == "") throw NoTokenFoundException()
            val urlToRequest = "/account/status"

            //String f_testUrl = "https://api.music.yandex.net/";
            val result = getRepeatWithHeader(fToken, BaseUrl + urlToRequest)
            return result
        }
    }
}