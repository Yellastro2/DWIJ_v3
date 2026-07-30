package com.yellastrodev.yandexmusiclib.account

import com.yellastrodev.yandexmusiclib.network.YamHttpMethod
import com.yellastrodev.yandexmusiclib.network.YamHttpRequest
import com.yellastrodev.yandexmusiclib.network.YamResponseDecoder
import com.yellastrodev.yandexmusiclib.network.YamResult
import com.yellastrodev.yandexmusiclib.network.YamTransport

internal class AccountApi(
    private val transport: YamTransport
) {
    suspend fun status(): YamResult<AccountStatus> {
        return when (
            val response = transport.execute(
                YamHttpRequest(
                    method = YamHttpMethod.GET,
                    path = "/account/status"
                )
            )
        ) {
            is YamResult.Success -> YamResponseDecoder.decodeResult(
                response = response.value,
                resultSerializer = AccountStatus.serializer()
            )
            is YamResult.Failure -> response
        }
    }
}
