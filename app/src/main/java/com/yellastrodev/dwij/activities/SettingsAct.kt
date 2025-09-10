package com.yellastrodev.dwij.activities

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.preference.PreferenceManager
import android.util.Log
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.google.android.material.snackbar.Snackbar
import com.yellastrodev.dwij.CACHE_SIZE
import com.yellastrodev.dwij.DEFAULT_CACHE_SIZE
import com.yellastrodev.dwij.R
import com.yellastrodev.dwij.YA_ID
import com.yellastrodev.dwij.YA_LOGIN
import com.yellastrodev.dwij.YA_TOKEN
import com.yellastrodev.yandexmusiclib.kot_utils.yAuth
import com.yellastrodev.yandexmusiclib.kot_utils.yNetwork
import com.yellastrodev.yandexmusiclib.yAccount
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class SettingsAct: Activity() {

	companion object{

		lateinit var mYaAuth: yAuth
		fun authYa(fAct: Activity){
			mYaAuth = yAuth(fAct)
			mYaAuth.login()
		}

		@OptIn(DelicateCoroutinesApi::class)
		fun saveToken(fToken: String, fAct: Activity, param: (String) -> Unit = {}){
			val sharedPref = PreferenceManager.getDefaultSharedPreferences(fAct)
			with (sharedPref.edit()) {
				putString(YA_TOKEN, fToken)
				apply()
			}

			GlobalScope.launch {
				var netResult = yAccount.showInformAccount(fToken)
				Log.i("DWIJ_TAG", netResult.toString())
				if (netResult is yNetwork.Companion.NetResult.Success){
					var fLogin = netResult.json
						.getJSONObject("result")
						.getJSONObject("account")
						.getString("login")
					val userId = netResult.json
						.getJSONObject("result")
						.getJSONObject("account")
						.getString("uid")
					with (sharedPref.edit()) {
							putString(YA_LOGIN, fLogin)
							putString(YA_ID, userId)
							apply()
						}
						param(fLogin)
				} else {
					Log.e("DWIJ_TAG", "Ошибка авторизации: ${netResult.toString()}")
					withContext(Dispatchers.Main) {
						Toast.makeText(
							fAct,
							"Ошибка авторизации: ${netResult.toString()}",
							Toast.LENGTH_SHORT
						).show()
					}
				}
			}
		}

		fun onYamResult(resultCode: Int, data: Intent?, fAct: Activity, param: (String) -> Unit = {}): String {
			val fToken = mYaAuth.onResult(resultCode,data)
			saveToken(fToken,fAct,param)
			return fToken
		}
	}

	lateinit var vYaLoginText: TextView
	lateinit var vYaLoginBtn: Button
	var isYaLogin = false

	lateinit var sharedPref: android.content.SharedPreferences



	@RequiresApi(Build.VERSION_CODES.O)
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
				with (sharedPref.edit()) {
					remove(YA_TOKEN)
					apply()
				}
				isYaLogin = false
				setNoYaAuth()
			}else{
				authYa(this)
			}
		}

		initCacheStoreSize()

	}

	@SuppressLint("SetTextI18n")
    @RequiresApi(Build.VERSION_CODES.O)
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
			fvCur.text = "${(fCurMb / KILOBYTE)}Gb"
		else
			fvCur.text = "${fCurMb}Mb"

		fvSeekBar.progress = fCurMb

		fvSeekBar.secondaryProgress = 5000


		fvSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
			override fun onProgressChanged(seekBar: SeekBar, i: Int, b: Boolean) {
				if (b) {
					if(i<KILOBYTE){
						fvCur.text = "${i}Mb"
					}else
						fvCur.text = "${i/KILOBYTE}Gb"
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

	private fun setYaAuth(fLogin: String) {
		vYaLoginBtn.post {
			vYaLoginBtn.text = getText(R.string.auth_btn_exit)
			isYaLogin = true
			vYaLoginText.text = fLogin
		}

	}

	private fun setNoYaAuth() {
		vYaLoginText.text = getText(R.string.no_auth)
		vYaLoginBtn.text = getText(R.string.auth_btn)
	}


override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
	yAuth.REQUEST_LOGIN_SDK
	if (requestCode == yAuth.REQUEST_LOGIN_SDK) {
		onYamResult(resultCode,data,this) { it -> setYaAuth(it) }

	} else {
		super.onActivityResult(requestCode, resultCode, data)
	}
}

}