package com.yellastrodev.dwij.activities

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.preference.PreferenceManager
import android.util.Log
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.util.UnstableApi
import com.google.android.material.snackbar.Snackbar
import com.yellastrodev.dwij.CACHE_SIZE
import com.yellastrodev.dwij.DEFAULT_CACHE_SIZE
import com.yellastrodev.dwij.R
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


@androidx.annotation.OptIn(UnstableApi::class)
class SettingsAct: AppCompatActivity() {

	companion object {
		private const val TAG = "SettingsAct"
	}

	lateinit var vYaLoginText: TextView
	lateinit var vYaLoginBtn: Button
	var isYaLogin = false

	lateinit var sharedPref: android.content.SharedPreferences
	private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
	private var authJob: Job? = null
	private var authDialog: AlertDialog? = null



    override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.lay_settings)

		sharedPref = PreferenceManager.getDefaultSharedPreferences(this)

		vYaLoginBtn = findViewById(R.id.ya_m_btn)
		vYaLoginText = findViewById(R.id.ya_m_auth_text)


		val fKey = sharedPref.getString(YA_TOKEN,"")

		if (fKey.equals("")){
			setNoYaAuth()
		}else{
			val fLogin = sharedPref.getString(YA_LOGIN,"nologin")!!
			setYaAuth(fLogin)
		}

		vYaLoginBtn.setOnClickListener {
			if (isYaLogin){
				clearYandexSession()
			}else{
				authYa()
			}
		}


	}

	private fun authYa() {
		if (authJob?.isActive == true) {
			return
		}

		vYaLoginBtn.isEnabled = false
		vYaLoginBtn.setText(R.string.auth_btn_waiting)
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
				vYaLoginBtn.isEnabled = true
				if (!isYaLogin) {
					vYaLoginBtn.setText(R.string.auth_btn)
				}
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
				mToken = token.accessToken,
				mUserID = ""
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

    @androidx.annotation.OptIn(UnstableApi::class)
    @SuppressLint("SetTextI18n")
    fun initCacheStoreSize(){
		val fvSeekBar = findViewById<SeekBar>(R.id.act_sett_store_progress)
		val fvMin = findViewById<TextView>(R.id.act_sett_store_min)
		val fvMax = findViewById<TextView>(R.id.act_sett_store_max)
		val fvCur = findViewById<TextView>(R.id.act_sett_store_cur)

		val KILOBYTE = 1024

		val externalStatFs = StatFs(Environment.getExternalStorageDirectory().absolutePath)
		var externalTotal: Long
		var externalFree: Int
		externalTotal = ( externalStatFs.blockCountLong * externalStatFs.blockSizeLong) / ( KILOBYTE * KILOBYTE )
		externalFree = (( externalStatFs.availableBlocksLong * externalStatFs.blockSizeLong) / ( KILOBYTE * KILOBYTE )).toInt()

		val fMin = 200
		val fMax = externalFree
		fvSeekBar.max = fMax
		fvSeekBar.min = fMin
		fvMax.text = "${fMax/KILOBYTE}Gb"

		val fCur = sharedPref.getLong(CACHE_SIZE, DEFAULT_CACHE_SIZE)
		val fCurMb = (fCur / KILOBYTE / KILOBYTE ).toInt()
		if (fCurMb > KILOBYTE)
			fvCur.text = "${(fCurMb / KILOBYTE)}Gb $cacheSizeStr"
		else
			fvCur.text = "${fCurMb}Mb $cacheSizeStr"

		fvSeekBar.progress = fCurMb

		fvSeekBar.secondaryProgress = 5000


		fvSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
			override fun onProgressChanged(seekBar: SeekBar, i: Int, b: Boolean) {
				if (b) {
					if(i<KILOBYTE){
						fvCur.text = "${i}Mb $cacheSizeStr"
					}else
						fvCur.text = "${i/KILOBYTE}Gb $cacheSizeStr"
					val dsds =5
				}
			}

			override fun onStartTrackingTouch(seekBar: SeekBar) {
			}

			override fun onStopTrackingTouch(seekBar: SeekBar) {
				val fCurChanged = seekBar.progress
				if (fCurChanged < fCurMb){
					Snackbar.make(seekBar,"Память очистится в след. кешировании =_=",Snackbar.LENGTH_LONG)
						.show()
				}
				sharedPref.edit()
					.putLong(CACHE_SIZE, (fCurChanged.toLong() * KILOBYTE * KILOBYTE))
					.apply()

				val dsf =0
			}
		})
	}

	var cacheSizeStr = ""

    @androidx.annotation.OptIn(UnstableApi::class)
	override fun onResume() {
		super.onResume()

		val cacheSize = (application as yApplication).cacheManager.getTotalSize()
		cacheSizeStr = "(занято ${formatSize(cacheSize)})"

		initCacheStoreSize()
	}

	fun formatSize(bytes: Long): String {
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
		vYaLoginBtn.text = getText(R.string.auth_btn_exit)
		isYaLogin = true
		vYaLoginText.text = fLogin
	}

	private fun setNoYaAuth() {
		isYaLogin = false
		vYaLoginText.text = getText(R.string.no_auth)
		vYaLoginBtn.text = getText(R.string.auth_btn)
	}

	override fun onDestroy() {
		authDialog?.dismiss()
		activityScope.cancel()
		super.onDestroy()
	}

}
