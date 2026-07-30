package com.yellastrodev.yandexmusiclib.kot_utils

import android.app.Activity
import android.content.Intent
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.yandex.authsdk.YandexAuthException
import com.yandex.authsdk.YandexAuthLoginOptions
import com.yandex.authsdk.YandexAuthOptions
import com.yandex.authsdk.YandexAuthResult
import com.yandex.authsdk.YandexAuthSdk
import com.yandex.authsdk.YandexAuthToken

class yAuth(
    fContext: AppCompatActivity,
    val resultClb: (String) -> Unit = {}
    ) {

    companion object {
        val REQUEST_LOGIN_SDK = 534534




    }

//    val sdk = YandexAuthSdk(
//        fContext, YandexAuthOptions(fContext,true,0)
//
//    )

    val sdk = YandexAuthSdk.create(YandexAuthOptions(fContext))

    // регистрируем контракт сразу, а не в login()
    private val launcher = fContext.registerForActivityResult(sdk.contract) { result ->
        handleResult(result)
    }

    val fCtx = fContext
    fun login(){


        val loginOptions = YandexAuthLoginOptions()
        launcher.launch(loginOptions)

//        fCtx.startActivityForResult(
//            sdk.createLoginIntent(
//                YandexAuthLoginOptions.Builder().build()
//            ),
//            REQUEST_LOGIN_SDK,
//            null
//        )
    }

    private fun handleResult(result: YandexAuthResult) {
        when (result) {
            is YandexAuthResult.Success -> onSuccessAuth(result.token)
            is YandexAuthResult.Failure -> onProccessError(result.exception)
            YandexAuthResult.Cancelled -> onCancelled()
        }
    }

    private fun onCancelled() {
        TODO("Not yet implemented")
    }

    private fun onProccessError(exception: YandexAuthException) {
            TODO("Not yet implemented")
    }

    private fun onSuccessAuth(token: YandexAuthToken) {
        Log.i("DWIJ_TAG", token.value)
        resultClb(token.value)
    }

//    fun onResult(resultCode: Int, data: Intent?): String {
//        try {
//            val yandexAuthToken = sdk.extractToken(resultCode, data)
//            yandexAuthToken?.let {
//                Log.i("DWIJ_TAG",it.value)
//                //token.value = it.value
//                return it.value
//            }
//
//        } catch (e: YandexAuthException) {
//            e.printStackTrace()
//            Log.i("DWIJ_TAG","Sory...")
//
//        }
//        return ""
//    }
}