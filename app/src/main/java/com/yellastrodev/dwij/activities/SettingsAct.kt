package com.yellastrodev.dwij.activities

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.StatFs
import android.preference.PreferenceManager
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import com.yellastrodev.dwij.CACHE_SIZE
import com.yellastrodev.dwij.DEFAULT_CACHE_SIZE
import com.yellastrodev.dwij.R
import com.yellastrodev.dwij.SettingsScreen
import com.yellastrodev.dwij.YA_ID
import com.yellastrodev.dwij.YA_LOGIN
import com.yellastrodev.dwij.YA_REFRESH_TOKEN
import com.yellastrodev.dwij.YA_TOKEN
import com.yellastrodev.dwij.YA_TOKEN_EXPIRES_AT
import com.yellastrodev.dwij.yApplication
import com.yellastrodev.yandexmusiclib.YamApiClient
import com.yellastrodev.yandexmusiclib.auth.DeviceAuthError
import com.yellastrodev.yandexmusiclib.auth.DeviceAuthResult
import com.yellastrodev.yandexmusiclib.auth.DeviceCode
import com.yellastrodev.yandexmusiclib.auth.OAuthToken
import com.yellastrodev.yandexmusiclib.auth.YandexDeviceAuth
import com.yellastrodev.yandexmusiclib.network.YamError
import com.yellastrodev.yandexmusiclib.network.YamResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


/** Хостит Compose-настройки и сохраняет существующую OAuth- и cache-логику Activity. */
class SettingsAct: AppCompatActivity() {

	companion object {
		private const val TAG = "SettingsAct"
		private const val MIN_CACHE_SIZE_MB = 200
		private const val BYTES_PER_MEGABYTE = 1024L * 1024L

		private fun bytesToMegabytes(bytes: Long): Int =
			(bytes / BYTES_PER_MEGABYTE).toInt()
	}

	private var yandexLogin by mutableStateOf<String?>(null)
	private var isAuthInProgress by mutableStateOf(false)
	private var cacheLimitMb by mutableStateOf(bytesToMegabytes(DEFAULT_CACHE_SIZE))
	private var maxCacheMb by mutableStateOf(MIN_CACHE_SIZE_MB + 1)
	private var occupiedCacheSize by mutableStateOf("0 B")
	private var skipNextResumeCacheRefresh = false
	private val isYaLogin: Boolean
		get() = yandexLogin != null

	private lateinit var sharedPref: android.content.SharedPreferences
	private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
	private var authJob: Job? = null
	private var authDialog: AlertDialog? = null



	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		enableEdgeToEdge()
		WindowCompat.getInsetsController(window, window.decorView).apply {
			isAppearanceLightStatusBars = false
			isAppearanceLightNavigationBars = false
		}

		sharedPref = PreferenceManager.getDefaultSharedPreferences(this)
		val token = sharedPref.getString(YA_TOKEN, "")
		if (token.isNullOrEmpty()) {
			setNoYaAuth()
		} else {
			setYaAuth(sharedPref.getString(YA_LOGIN, "nologin") ?: "nologin")
		}
		refreshCacheState()
		skipNextResumeCacheRefresh = true

		setContent {
			SettingsScreen(
				yandexLogin = yandexLogin,
				isAuthInProgress = isAuthInProgress,
				cacheLimitMb = cacheLimitMb,
				minCacheMb = MIN_CACHE_SIZE_MB,
				maxCacheMb = maxCacheMb,
				occupiedCacheSize = occupiedCacheSize,
				onBackClick = ::finish,
				onAuthClick = {
					if (isYaLogin) clearYandexSession() else authYa()
				},
				onCacheLimitCommitted = ::saveCacheLimit,
			)
		}
	}

	private fun authYa() {
		if (authJob?.isActive == true) {
			return
		}

		isAuthInProgress = true
		authJob = activityScope.launch {
			try {
				when (
					val authResult = YandexDeviceAuth().authorize(
						onCode = { code -> showDeviceCode(code) }
					)
				) {
					is DeviceAuthResult.Success -> {
						when (val accountResult = saveToken(authResult.value)) {
							is AccountSaveResult.Success -> {
								setYaAuth(accountResult.login)
								Toast.makeText(
									this@SettingsAct,
									R.string.auth_success,
									Toast.LENGTH_SHORT
								).show()
							}
							AccountSaveResult.Failure -> {
								Toast.makeText(
									this@SettingsAct,
									R.string.auth_error_account,
									Toast.LENGTH_LONG
								).show()
							}
						}
					}
					is DeviceAuthResult.Failure -> {
						logAuthError(authResult.error)
						if (authResult.error !is DeviceAuthError.Cancelled) {
							Toast.makeText(
								this@SettingsAct,
								authErrorMessage(authResult.error),
								Toast.LENGTH_LONG
							).show()
						}
					}
				}
			} catch (error: CancellationException) {
				Log.i(TAG, "[authYa] Ожидание авторизации отменено")
				throw error
			} catch (error: Exception) {
				Log.e(TAG, "[authYa] Неожиданная ошибка авторизации", error)
				Toast.makeText(
					this@SettingsAct,
					R.string.auth_error_response,
					Toast.LENGTH_LONG
				).show()
			} finally {
				authDialog?.dismiss()
				authDialog = null
				isAuthInProgress = false
			}
		}
	}

	private fun showDeviceCode(code: DeviceCode) {
		authDialog?.dismiss()
		val dialog = AlertDialog.Builder(this)
			.setTitle(R.string.auth_device_title)
			.setMessage(
				getString(
					R.string.auth_device_message,
					code.verificationUrl,
					code.userCode
				)
			)
			.setPositiveButton(R.string.auth_open_browser, null)
			.setNegativeButton(android.R.string.cancel) { _, _ ->
				authJob?.cancel()
			}
			.create()

		dialog.setOnShowListener {
			dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
				try {
					getSystemService(ClipboardManager::class.java).setPrimaryClip(
						ClipData.newPlainText(
							getString(R.string.auth_clip_label),
							code.userCode
						)
					)
					Toast.makeText(
						this,
						R.string.auth_code_copied,
						Toast.LENGTH_SHORT
					).show()
					startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(code.verificationUrl)))
				} catch (error: ActivityNotFoundException) {
					Log.e(TAG, "[showDeviceCode] Не найден браузер", error)
					Toast.makeText(
						this,
						R.string.auth_browser_error,
						Toast.LENGTH_SHORT
					).show()
				}
			}
		}
		dialog.setOnCancelListener {
			authJob?.cancel()
		}
		authDialog = dialog
		dialog.show()
	}

	private suspend fun saveToken(token: OAuthToken): AccountSaveResult {
		val status = when (
			val result = YamApiClient(
				accessToken = token.accessToken,
				userId = ""
			).accountStatus()
		) {
			is YamResult.Success -> result.value
			is YamResult.Failure -> {
				logAccountError(result.error)
				return AccountSaveResult.Failure
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
			val expiresAt = token.expiresIn?.let {
				System.currentTimeMillis() + it * 1_000L
			}

			val editor = sharedPref.edit()
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

			(application as yApplication).yamClient.updateAuthorization(
				token = token.accessToken,
				userId = userId,
				login = login
			)
			Log.i(TAG, "[saveToken] Авторизация сохранена")
			AccountSaveResult.Success(login)
		} catch (error: Exception) {
			Log.e(TAG, "[saveToken] Некорректный ответ account/status", error)
			AccountSaveResult.Failure
		}
	}

	private fun logAccountError(error: YamError) {
		when (error) {
			YamError.Unauthorized ->
				Log.w(TAG, "[saveToken] Токен не принят")
			YamError.NoInternet ->
				Log.w(TAG, "[saveToken] Нет подключения к сети")
			YamError.Timeout ->
				Log.w(TAG, "[saveToken] Таймаут account/status")
			is YamError.Http ->
				Log.w(TAG, "[saveToken] HTTP ${error.statusCode}, code=${error.code}")
			is YamError.InvalidResponse ->
				Log.e(TAG, "[saveToken] Некорректный account/status", error.cause)
			is YamError.Network ->
				Log.e(TAG, "[saveToken] Ошибка сети account/status", error.cause)
		}
	}

	private fun clearYandexSession() {
		with(sharedPref.edit()) {
			remove(YA_TOKEN)
			remove(YA_REFRESH_TOKEN)
			remove(YA_TOKEN_EXPIRES_AT)
			remove(YA_LOGIN)
			remove(YA_ID)
			apply()
		}
		(application as yApplication).yamClient.clearAuthorization()
		setNoYaAuth()
	}

	private fun authErrorMessage(error: DeviceAuthError): String = when (error) {
		DeviceAuthError.Configuration -> getString(R.string.auth_error_configuration)
		DeviceAuthError.Cancelled -> getString(R.string.auth_error_response)
		is DeviceAuthError.Timeout -> getString(R.string.auth_error_timeout)
		is DeviceAuthError.Network -> getString(R.string.auth_error_network)
		is DeviceAuthError.OAuth -> getString(R.string.auth_error_oauth, error.code)
		is DeviceAuthError.Http,
		is DeviceAuthError.InvalidResponse -> getString(R.string.auth_error_response)
	}

	private fun logAuthError(error: DeviceAuthError) {
		when (error) {
			is DeviceAuthError.Network ->
				Log.e(TAG, "[authYa] Ошибка сети", error.cause)
			is DeviceAuthError.InvalidResponse ->
				Log.e(TAG, "[authYa] Некорректный ответ OAuth", error.cause)
			is DeviceAuthError.OAuth ->
				Log.w(TAG, "[authYa] OAuth-ошибка: ${error.code}")
			is DeviceAuthError.Http ->
				Log.w(TAG, "[authYa] HTTP-ошибка: ${error.statusCode}")
			is DeviceAuthError.Timeout ->
				Log.w(TAG, "[authYa] Таймаут: ${error.timeoutSeconds} сек.")
			DeviceAuthError.Cancelled ->
				Log.i(TAG, "[authYa] Авторизация отменена")
			DeviceAuthError.Configuration ->
				Log.e(TAG, "[authYa] OAuth не настроен")
		}
	}

	private sealed interface AccountSaveResult {
		data class Success(val login: String) : AccountSaveResult
		data object Failure : AccountSaveResult
	}

	/** Перечитывает лимит сразу, а фактический объём файлов считает вне UI-потока. */
	private fun refreshCacheState() {
		cacheLimitMb = bytesToMegabytes(
			sharedPref.getLong(CACHE_SIZE, DEFAULT_CACHE_SIZE),
		).coerceAtLeast(MIN_CACHE_SIZE_MB)
		val statFs = StatFs(cacheDir.absolutePath)
		val availableMb = (statFs.availableBytes / BYTES_PER_MEGABYTE)
			.coerceAtMost(Int.MAX_VALUE.toLong())
			.toInt()
		maxCacheMb = maxOf(MIN_CACHE_SIZE_MB + 1, cacheLimitMb, availableMb)

		activityScope.launch(Dispatchers.IO) {
			val totalSize = (application as yApplication).cacheManager.getTotalSize()
			val formattedSize = formatSize(totalSize)
			withContext(Dispatchers.Main) {
				occupiedCacheSize = formattedSize
			}
		}
	}

	/** Сохраняет выбранный Compose-слайдером предел в байтах. */
	private fun saveCacheLimit(megabytes: Int) {
		val normalizedMb = megabytes.coerceIn(MIN_CACHE_SIZE_MB, maxCacheMb)
		cacheLimitMb = normalizedMb
		sharedPref.edit()
			.putLong(CACHE_SIZE, normalizedMb.toLong() * BYTES_PER_MEGABYTE)
			.apply()
	}

	override fun onResume() {
		super.onResume()
		if (skipNextResumeCacheRefresh) {
			skipNextResumeCacheRefresh = false
		} else if (::sharedPref.isInitialized) {
			refreshCacheState()
		}
	}

	/** Форматирует реальный занятый объём с двумя знаками после запятой. */
	private fun formatSize(bytes: Long): String {
		if (bytes < 1024) return "$bytes B"

		val units = arrayOf("KB", "MB", "GB", "TB")
		var value = bytes.toDouble()
		var unitIndex = -1

		do {
			value /= 1024.0
			unitIndex++
		} while (value >= 1024 && unitIndex < units.lastIndex)

		// округляем до 1–2 знаков после запятой
		return String.format("%.2f %s", value, units[unitIndex])
	}

	private fun setYaAuth(fLogin: String) {
		yandexLogin = fLogin
	}

	private fun setNoYaAuth() {
		yandexLogin = null
	}

	override fun onDestroy() {
		authDialog?.dismiss()
		activityScope.cancel()
		super.onDestroy()
	}

}
