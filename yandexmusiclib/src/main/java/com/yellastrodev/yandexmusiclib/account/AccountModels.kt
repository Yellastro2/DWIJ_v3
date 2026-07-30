package com.yellastrodev.yandexmusiclib.account

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Основная информация об аккаунте из `account/status`.
 *
 * Nullable-поля повторяют модель локальной Python SDK.
 */
@Serializable
data class YandexAccount(
    val now: String? = null,
    val uid: Long? = null,
    val login: String? = null,
    @SerialName("full_name")
    val fullName: String? = null,
    @SerialName("second_name")
    val secondName: String? = null,
    @SerialName("first_name")
    val firstName: String? = null,
    @SerialName("display_name")
    val displayName: String? = null,
    @SerialName("service_available")
    val serviceAvailable: Boolean? = null,
    @SerialName("hosted_user")
    val hostedUser: Boolean? = null,
    val birthday: String? = null,
    val region: Int? = null,
    @SerialName("registered_at")
    val registeredAt: String? = null,
    @SerialName("has_info_for_app_metrica")
    val hasInfoForAppMetrica: Boolean? = null,
    val child: Boolean? = null
)

/**
 * Типизированная часть статуса аккаунта, используемая Android-клиентом.
 *
 * Модель можно расширять полями подписки и разрешений без изменения transport.
 */
@Serializable
data class AccountStatus(
    val account: YandexAccount? = null,
    val advertisement: String? = null,
    @SerialName("cache_limit")
    val cacheLimit: Int? = null,
    @SerialName("default_email")
    val defaultEmail: String? = null,
    @SerialName("skips_per_hour")
    val skipsPerHour: Int? = null,
    @SerialName("station_exists")
    val stationExists: Boolean? = null,
    @SerialName("premium_region")
    val premiumRegion: Int? = null,
    @SerialName("pretrial_active")
    val pretrialActive: Boolean? = null,
    val userhash: String? = null
)
