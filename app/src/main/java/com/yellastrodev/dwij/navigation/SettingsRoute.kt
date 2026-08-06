package com.yellastrodev.dwij.navigation

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.StatFs
import android.preference.PreferenceManager
import android.util.Log
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import com.yellastrodev.dwij.CACHE_SIZE
import com.yellastrodev.dwij.DEFAULT_CACHE_SIZE
import com.yellastrodev.dwij.R
import com.yellastrodev.dwij.ui.SettingsScreen
import com.yellastrodev.dwij.YA_ID
import com.yellastrodev.dwij.YA_LOGIN
import com.yellastrodev.dwij.YA_REFRESH_TOKEN
import com.yellastrodev.dwij.YA_TOKEN
import com.yellastrodev.dwij.YA_TOKEN_EXPIRES_AT
import com.yellastrodev.dwij.YamLoggerAndroid
import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.yApplication
import com.yellastrodev.dwij.BuildConfig
import com.yellastrodev.yandexmusiclib.YamApiClient
import com.yellastrodev.yandexmusiclib.auth.DeviceAuthError
import com.yellastrodev.yandexmusiclib.auth.DeviceAuthResult
import com.yellastrodev.yandexmusiclib.auth.DeviceCode
import com.yellastrodev.yandexmusiclib.auth.OAuthToken
import com.yellastrodev.yandexmusiclib.auth.YandexDeviceAuth
import com.yellastrodev.yandexmusiclib.network.YamError
import com.yellastrodev.yandexmusiclib.network.YamResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Compose-route настроек, включая OAuth Device Flow и состояние дискового кэша. */
@Composable
fun SettingsRoute(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val application = context.applicationContext as yApplication
    val sharedPreferences = remember(context) {
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
    }
    val coroutineScope = rememberCoroutineScope()
    var yandexLogin by remember {
        mutableStateOf(
            sharedPreferences.getString(YA_TOKEN, "")
                ?.takeIf(String::isNotEmpty)
                ?.let { sharedPreferences.getString(YA_LOGIN, "nologin") ?: "nologin" },
        )
    }
    var isAuthInProgress by remember { mutableStateOf(false) }
    var authJob by remember { mutableStateOf<Job?>(null) }
    var deviceCode by remember { mutableStateOf<DeviceCode?>(null) }
    var cacheLimitMb by remember {
        mutableStateOf(bytesToMegabytes(DEFAULT_CACHE_SIZE))
    }
    var maxCacheMb by remember { mutableStateOf(MIN_CACHE_SIZE_MB + 1) }
    var occupiedCacheSize by remember { mutableStateOf("0 B") }

    fun refreshCacheState() {
        cacheLimitMb = bytesToMegabytes(
            sharedPreferences.getLong(CACHE_SIZE, DEFAULT_CACHE_SIZE),
        ).coerceAtLeast(MIN_CACHE_SIZE_MB)
        val statFs = StatFs(context.cacheDir.absolutePath)
        val availableMb = (statFs.availableBytes / BYTES_PER_MEGABYTE)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        maxCacheMb = maxOf(MIN_CACHE_SIZE_MB + 1, cacheLimitMb, availableMb)
        coroutineScope.launch(Dispatchers.IO) {
            val formattedSize = formatSettingsSize(application.cacheManager.getTotalSize())
            withContext(Dispatchers.Main) { occupiedCacheSize = formattedSize }
        }
    }

    fun clearYandexSession() {
        with(sharedPreferences.edit()) {
            remove(YA_TOKEN)
            remove(YA_REFRESH_TOKEN)
            remove(YA_TOKEN_EXPIRES_AT)
            remove(YA_LOGIN)
            remove(YA_ID)
            apply()
        }
        application.yamClient.clearAuthorization()
        yandexLogin = null
    }

    suspend fun saveToken(token: OAuthToken): SettingsAccountSaveResult {
        val status = when (
            val result = YamApiClient(
                accessToken = token.accessToken,
                userId = "",
                logger = YamLoggerAndroid()
            ).accountStatus()
        ) {
            is YamResult.Success -> result.value
            is YamResult.Failure -> {
                logSettingsAccountError(result.error)
                return SettingsAccountSaveResult.Failure
            }
        }
        return try {
            val account = requireNotNull(status.account) {
                "В account/status отсутствует account"
            }
            val login = requireNotNull(account.login) {
                "В account/status отсутствует login"
            }
            val userId = requireNotNull(account.uid) {
                "В account/status отсутствует uid"
            }.toString()
            val expiresAt = token.expiresIn?.let { seconds ->
                System.currentTimeMillis() + seconds * 1_000L
            }
            val editor = sharedPreferences.edit()
                .putString(YA_TOKEN, token.accessToken)
                .putString(YA_LOGIN, login)
                .putString(YA_ID, userId)
            if (token.refreshToken == null) {
                editor.remove(YA_REFRESH_TOKEN)
            } else {
                editor.putString(YA_REFRESH_TOKEN, token.refreshToken)
            }
            if (expiresAt == null) {
                editor.remove(YA_TOKEN_EXPIRES_AT)
            } else {
                editor.putLong(YA_TOKEN_EXPIRES_AT, expiresAt)
            }
            editor.apply()
            application.yamClient.updateAuthorization(
                token = token.accessToken,
                userId = userId,
                login = login,
            )
            Log.i(TAG, "[saveToken] Авторизация сохранена")
            try {
                when (val refreshResult = application.playlistRepository.refreshPlaylists()) {
                    is DataResult.Success -> Log.i(
                        TAG,
                        "[saveToken] Данные Яндекс Музыки обновлены после авторизации",
                    )
                    is DataResult.Failure -> Log.w(
                        TAG,
                        "[saveToken] Авторизация сохранена, но данные Яндекс Музыки не обновлены: " +
                            refreshResult.error,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(
                    TAG,
                    "[saveToken] Авторизация сохранена, но обновление данных завершилось с ошибкой",
                    error,
                )
            }
            SettingsAccountSaveResult.Success(login)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(TAG, "[saveToken] Некорректный ответ account/status", error)
            SettingsAccountSaveResult.Failure
        }
    }

    fun startYandexAuth() {
        if (authJob?.isActive == true) return
        isAuthInProgress = true
        authJob = coroutineScope.launch {
            try {
                when (
                    val result = YandexDeviceAuth(
                        clientId = BuildConfig.YANDEX_OAUTH_CLIENT_ID,
                        clientSecret = BuildConfig.YANDEX_OAUTH_CLIENT_SECRET,
                        logger = YamLoggerAndroid()).authorize(
                        onCode = { code -> deviceCode = code },
                    )
                ) {
                    is DeviceAuthResult.Success -> when (val saved = saveToken(result.value)) {
                        is SettingsAccountSaveResult.Success -> {
                            yandexLogin = saved.login
                            Toast.makeText(context, R.string.auth_success, Toast.LENGTH_SHORT).show()
                        }
                        SettingsAccountSaveResult.Failure -> Toast.makeText(
                            context,
                            R.string.auth_error_account,
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    is DeviceAuthResult.Failure -> {
                        logSettingsAuthError(result.error)
                        if (result.error !is DeviceAuthError.Cancelled) {
                            Toast.makeText(
                                context,
                                settingsAuthErrorMessage(context, result.error),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
            } catch (error: CancellationException) {
                Log.i(TAG, "[authYa] Ожидание авторизации отменено")
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "[authYa] Неожиданная ошибка авторизации", error)
                Toast.makeText(context, R.string.auth_error_response, Toast.LENGTH_LONG).show()
            } finally {
                deviceCode = null
                isAuthInProgress = false
                authJob = null
            }
        }
    }

    LaunchedEffect(Unit) { refreshCacheState() }
    DisposableEffect(lifecycleOwner) {
        var firstResume = true
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (firstResume) {
                    firstResume = false
                } else {
                    refreshCacheState()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SettingsScreen(
        yandexLogin = yandexLogin,
        isAuthInProgress = isAuthInProgress,
        cacheLimitMb = cacheLimitMb,
        minCacheMb = MIN_CACHE_SIZE_MB,
        maxCacheMb = maxCacheMb,
        occupiedCacheSize = occupiedCacheSize,
        onBackClick = { navController.navigateUp() },
        onAuthClick = {
            if (yandexLogin == null) startYandexAuth() else clearYandexSession()
        },
        onCacheLimitCommitted = { megabytes ->
            val normalizedMb = megabytes.coerceIn(MIN_CACHE_SIZE_MB, maxCacheMb)
            cacheLimitMb = normalizedMb
            sharedPreferences.edit()
                .putLong(CACHE_SIZE, normalizedMb.toLong() * BYTES_PER_MEGABYTE)
                .apply()
        },
        modifier = modifier,
    )

    deviceCode?.let { code ->
        AlertDialog(
            onDismissRequest = {
                authJob?.cancel()
                deviceCode = null
            },
            title = { Text(stringResource(R.string.auth_device_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.auth_device_message,
                        code.verificationUrl,
                        code.userCode,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        try {
                            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                                ClipData.newPlainText(
                                    context.getString(R.string.auth_clip_label),
                                    code.userCode,
                                ),
                            )
                            Toast.makeText(
                                context,
                                R.string.auth_code_copied,
                                Toast.LENGTH_SHORT,
                            ).show()
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(code.verificationUrl)),
                            )
                        } catch (error: ActivityNotFoundException) {
                            Log.e(TAG, "[showDeviceCode] Не найден браузер", error)
                            Toast.makeText(
                                context,
                                R.string.auth_browser_error,
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                ) { Text(stringResource(R.string.auth_open_browser)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        authJob?.cancel()
                        deviceCode = null
                    },
                ) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}

private sealed interface SettingsAccountSaveResult {
    data class Success(val login: String) : SettingsAccountSaveResult
    data object Failure : SettingsAccountSaveResult
}

private fun settingsAuthErrorMessage(context: Context, error: DeviceAuthError): String =
    when (error) {
        DeviceAuthError.Configuration -> context.getString(R.string.auth_error_configuration)
        DeviceAuthError.Cancelled -> context.getString(R.string.auth_error_response)
        is DeviceAuthError.Timeout -> context.getString(R.string.auth_error_timeout)
        is DeviceAuthError.Network -> context.getString(R.string.auth_error_network)
        is DeviceAuthError.OAuth -> context.getString(R.string.auth_error_oauth, error.code)
        is DeviceAuthError.Http,
        is DeviceAuthError.InvalidResponse -> context.getString(R.string.auth_error_response)
    }

private fun logSettingsAuthError(error: DeviceAuthError) {
    when (error) {
        is DeviceAuthError.Network -> Log.e(TAG, "[authYa] Ошибка сети", error.cause)
        is DeviceAuthError.InvalidResponse ->
            Log.e(TAG, "[authYa] Некорректный ответ OAuth", error.cause)
        is DeviceAuthError.OAuth -> Log.w(TAG, "[authYa] OAuth-ошибка: ${error.code}")
        is DeviceAuthError.Http -> Log.w(TAG, "[authYa] HTTP-ошибка: ${error.statusCode}")
        is DeviceAuthError.Timeout ->
            Log.w(TAG, "[authYa] Таймаут: ${error.timeoutSeconds} сек.")
        DeviceAuthError.Cancelled -> Log.i(TAG, "[authYa] Авторизация отменена")
        DeviceAuthError.Configuration -> Log.e(TAG, "[authYa] OAuth не настроен")
    }
}

private fun logSettingsAccountError(error: YamError) {
    when (error) {
        YamError.Unauthorized -> Log.w(TAG, "[saveToken] Токен не принят")
        YamError.NoInternet -> Log.w(TAG, "[saveToken] Нет подключения к сети")
        YamError.Timeout -> Log.w(TAG, "[saveToken] Таймаут account/status")
        is YamError.Http ->
            Log.w(TAG, "[saveToken] HTTP ${error.statusCode}, code=${error.code}")
        is YamError.InvalidResponse ->
            Log.e(TAG, "[saveToken] Некорректный account/status", error.cause)
        is YamError.Network ->
            Log.e(TAG, "[saveToken] Ошибка сети account/status", error.cause)
    }
}

private fun formatSettingsSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = -1
    do {
        value /= 1024.0
        unitIndex++
    } while (value >= 1024 && unitIndex < units.lastIndex)
    return String.format("%.2f %s", value, units[unitIndex])
}

private fun bytesToMegabytes(bytes: Long): Int =
    (bytes / BYTES_PER_MEGABYTE).toInt()

private const val TAG = "SettingsRoute"
private const val MIN_CACHE_SIZE_MB = 200
private const val BYTES_PER_MEGABYTE = 1024L * 1024L
