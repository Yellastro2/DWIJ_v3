package com.yellastrodev.yandexmusiclib.kot_utils

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException


class yNetwork {

    companion object {
        private val userAgent =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/98.0.4758.102 Safari/537.36"

        private var X_Yandex_Music_Client = "YandexMusicAndroid/24022571"
        private val SECRET = "p93jhgh689SBReK6ghtw62"

        sealed class NetResult {
            data class Success(val json: JSONObject) : NetResult()
            sealed class Error : NetResult() {
                object Timeout : Error()
                object NoInternet : Error()
                object AccessDenied : Error()
                data class Unknown(val throwable: Throwable) : Error()
            }
        }

        sealed class NetStreamResult {
            data class Success(val stream: InputStream) : NetStreamResult()
            sealed class Error : NetStreamResult() {
                object Timeout : Error()
                object NoInternet : Error()
                object AccessDenied : Error()
                data class Unknown(val throwable: Throwable) : Error()
            }
        }


        suspend fun post(
            token: String,
            url: String,
            body: JSONObject = JSONObject(),
            parseArray: Boolean = false,
            contentType: String = "form" // "form" или "json"
        ): JSONObject {
            return withContext(Dispatchers.IO) {
                val fUrl = URL(url)
                println("\nPOST to $url; token: $token\n")

                (fUrl.openConnection() as HttpsURLConnection).run {
                    requestMethod = "POST"
                    setRequestProperty("Authorization", "OAuth $token")
                    doOutput = true

                    val requestBody: String = when (contentType.lowercase()) {
                        "json" -> {
                            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                            body.toString()
                        }
                        else -> {
                            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                            buildString {
                                for (key in body.keys()) {
                                    val value = if (parseArray) {
                                        try {
                                            val arr = body.getJSONArray(key)
                                            (0 until arr.length()).joinToString(",") { arr.getString(it) }
                                        } catch (e: Exception) {
                                            body.getString(key)
                                        }
                                    } else {
                                        body.getString(key)
                                    }
                                    append("${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}&")
                                }
                            }.removeSuffix("&")
                        }
                    }

                    OutputStreamWriter(outputStream).use { it.write(requestBody) }

                    val code = responseCode
                    val msg = responseMessage
                    println("POST: $fUrl: Code: $code; Msg: $msg")

                    if (code == 200) {
                        val responseText = inputStream.bufferedReader().use { it.readText() }
                        JSONObject(responseText)
                    } else {
                        JSONObject(mapOf("code" to code, "message" to msg, "result" to msg))
                    }
                }
            }
        }

        suspend fun getRepeatWithHeader(
            token: String,
            url: String,
            maxTries: Int = 3
        ): NetResult {
            var attempt = 0
            var result: NetResult = NetResult.Error.Unknown(Exception("Not leas one try in getRepeatWithHeader"))
            while (attempt < maxTries) {
                try {
                    result = getWithHeader(token, url ) // наша suspend-функция
                    if (result is NetResult.Success) {
                        return result
                    } else {
                        throw Exception("result is not Success")
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    val waitTime = 500L + 1000L * attempt
                    delay(waitTime) // не блокирует поток
                    attempt++
                }
            }
            Log.e("yNetwork", "error connection after $maxTries tries")
            return result
        }




        private suspend fun getWithHeader(
            token: String,
            url: String,
            userAgent: String = "MyApp/1.0"
        ): NetResult = withContext(Dispatchers.IO) {
            try {
                val fUrl = URL(url)
                println("\ngetWithHeaders, $url; token: $token\n")

                (fUrl.openConnection() as HttpURLConnection).run {
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", userAgent)
                    setRequestProperty("accept", "application/json")
                    if (token != null) {
                        setRequestProperty("Authorization", "OAuth $token")
                    }

                    println("\nSent 'GET' request to URL : $fUrl; Response Code : $responseCode")

                    val responseText = inputStream.bufferedReader().use { it.readText() }
                    NetResult.Success(JSONObject(responseText))
                }
            } catch (e: SocketTimeoutException) {
                NetResult.Error.Timeout
            } catch (e: UnknownHostException) {
                NetResult.Error.NoInternet
            } catch (e: ConnectException) {
                NetResult.Error.NoInternet
            } catch (e: SSLException) {
                NetResult.Error.AccessDenied
            } catch (e: Exception) {
                NetResult.Error.Unknown(e)
            }
        }


        suspend fun getCoverStream(
            token: String,
            fAdrPart: String,
            fSize: Int
        ): NetStreamResult {
            return withContext(Dispatchers.IO) {
                val fSizeStr = "${fSize}x$fSize"
                val fAdrPart2 = fAdrPart.replace("%%", fSizeStr)
                val fAdr = "https://$fAdrPart2"

                println("[getCoverStream] Start request")
                println("[getCoverStream] URL: $fAdr")
                println("[getCoverStream] Token: ${token.take(4)}..")

                val url = URL(fAdr)
                try {
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        setRequestProperty("User-Agent", userAgent)
                        setRequestProperty("Accept", "application/json")
                        setRequestProperty("Authorization", "OAuth ${token}")
                        connectTimeout = 10_000
                        readTimeout = 15_000
                    }

                    println("[getCoverStream] Sending GET request...")
                    val code = conn.responseCode
                    println("[getCoverStream] Response code: $code")

                    if (code != HttpURLConnection.HTTP_OK) {
                        println("[getCoverStream] Error: HTTP $code")
                        conn.disconnect()
                        return@withContext NetStreamResult.Error.Unknown(Exception(code.toString()))
                    }

                    println("[getCoverStream] Success, returning InputStream")
                    // Возвращаем поток — закрывать его должен вызывающий код
//                conn.inputStream
                    return@withContext NetStreamResult.Success(conn.inputStream)
                } catch (e: Exception) {
                    e.printStackTrace()
                    return@withContext NetStreamResult.Error.Unknown(e)
                }
            }
        }

        /**
         * Выполняет GET-запрос с параметрами.
         *
         * @param token Токен авторизации.
         * @param fAdr Базовый адрес запроса.
         * @param fParams Параметры запроса в виде JSON.
         * @return JSON-ответ сервера.
         */
        suspend fun get(
            token: String,
            fAdr: String,
            fParams: JSONObject
        ): JSONObject = withContext(Dispatchers.IO) {
            val query = buildString {
                append("?")
                for (key in fParams.keys()) {
                    append("$key=${fParams.getString(key)}&")
                }
            }.removeSuffix("&")

            val fullUrl = fAdr + query
            println("[GET] URL with params: $fullUrl")
            get(fullUrl, token)
        }

        /**
         * Выполняет GET-запрос по указанному адресу.
         *
         * @param token Токен авторизации.
         * @param fAdr Полный URL.
         * @return JSON-ответ сервера.
         */
        suspend fun get(
            token: String,
            fAdr: String
        ): JSONObject = withContext(Dispatchers.IO) {
            val url = URL(fAdr)
            var responseText = ""

            println("[GET] Request: $fAdr")
            println("[GET] Token: ${token.take(4)}..")

            with(url.openConnection() as HttpURLConnection) {
                requestMethod = "GET"
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "OAuth ${token}")
                setRequestProperty("Content-Type", "application/json")

                println("[GET] Response code: $responseCode")

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            println("[GET] $line")
                            responseText += line
                        }
                    }
                } else {
                    responseText = """{"code":"$responseCode","message":"$responseMessage"}"""
                }
            }

            JSONObject(responseText)
        }

        /**
         * Выполняет GET-запрос и возвращает XML-ответ в виде строки.
         *
         * @param token Токен авторизации.
         * @param fSome Полный URL.
         * @return Ответ сервера в виде строки.
         */
        suspend fun getXml(
            token: String,
            fSome: String
        ): String = withContext(Dispatchers.IO) {
            val url = URL(fSome)
            var responseText = ""

            println("[GET-XML] Request: $fSome")
            println("[GET-XML] Token: ${token.take(4)}..")

            with(url.openConnection() as HttpURLConnection) {
                requestMethod = "GET"
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "OAuth ${token}")

                println("[GET-XML] Response code: $responseCode")

                if (responseCode != HttpURLConnection.HTTP_OK) {
                    val err = """{"code":$responseCode,"msg":"$responseMessage"}"""
                    println("[GET-XML] ERROR: $err")
                    return@withContext err
                }

                inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        println("[GET-XML] $line")
                        responseText += line
                    }
                }
            }

            responseText
        }


    }


}