package com.yellastrodev.yandexmusiclib.kot_utils

import android.app.Activity
import android.content.Intent
import android.util.Log
import com.yandex.authsdk.YandexAuthException
import com.yandex.authsdk.YandexAuthLoginOptions
import com.yandex.authsdk.YandexAuthOptions
import com.yandex.authsdk.YandexAuthSdk

class yAuth(fContext: Activity) {

    companion object {
        val REQUEST_LOGIN_SDK = 534534




    }

    val sdk = YandexAuthSdk(
        fContext, YandexAuthOptions(fContext,true,0)

    )

    val fCtx = fContext
    fun login(){

        fCtx.startActivityForResult(
            sdk.createLoginIntent(
                YandexAuthLoginOptions.Builder().build()
            ),
            REQUEST_LOGIN_SDK,
            null
        )
    }

    fun onResult(resultCode: Int, data: Intent?): String {
        try {
            val yandexAuthToken = sdk.extractToken(resultCode, data)
            yandexAuthToken?.let {
                Log.i("DWIJ_TAG",it.value)
                //token.value = it.value
                return it.value
            }

        } catch (e: YandexAuthException) {
            e.printStackTrace()
            Log.i("DWIJ_TAG","Sory...")

        }
        return ""
    }
}