package com.yellastrodev.dwij.fragments

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.os.Bundle
import android.preference.PreferenceManager
import android.util.Log
import android.view.View
import android.widget.AutoCompleteTextView
import android.widget.ImageButton
import androidx.annotation.OptIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.navigation.fragment.findNavController
import com.yellastrodev.dwij.DWIJ_ACC_TOKEN
import com.yellastrodev.dwij.HomeScreen
import com.yellastrodev.dwij.R
import com.yellastrodev.dwij.TYPE
import com.yellastrodev.dwij.VALUE
import com.yellastrodev.dwij.YA_TOKEN
import com.yellastrodev.dwij.activities.MainActivity
import com.yellastrodev.dwij.yApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeFrag: Fragment(R.layout.frag_home) {


    @OptIn(UnstableApi::class)
    @SuppressLint("CheckResult")
	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		view.findViewById<ComposeView>(R.id.fr_home_player).apply {
			setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
			setContent {
				HomeScreen(
					modifier = Modifier.fillMaxWidth(),
					onPlaylistsClick = {
						(activity as MainActivity).mNavController.navigate(
							R.id.action_homeFrag_to_gridPlaylistFrag,
						)
					},
					onTracksClick = {
						val bundle = Bundle().apply {
							putString(TYPE, ObjectFrag.TRACKLIST)
						}
						findNavController().navigate(R.id.objectFrag, bundle)
					},
					onWaveClick = ::playWave,
					onAllTracksClick = ::openALLTracks,
				)
			}
		}

		view.findViewById<ImageButton>(R.id.fr_home_settngs).setOnClickListener {
			(activity as MainActivity).mNavController.navigate(R.id.action_homeFrag_to_settingsAct)
		}

		val mvSearch = view.findViewById<AutoCompleteTextView>(R.id.fr_home_search)

		view.findViewById<View>(R.id.fr_home_acc).setOnClickListener {
			val sharedPref = PreferenceManager.getDefaultSharedPreferences(requireContext())
			val fToken = sharedPref.getString(DWIJ_ACC_TOKEN,"")
			val fYaLogin = sharedPref.getString(YA_TOKEN, "")
//			if (fToken.isNullOrEmpty()&& fYaLogin.isNullOrEmpty())
//				(activity as MainActivity).mNavController.navigate(R.id.loginFrag)
//			else
//				(activity as MainActivity).mNavController.navigate(R.id.accountFrag)
		}
	}

	private fun playWave() {
//		showProgress()
		lifecycleScope.launch(Dispatchers.IO) {
			(requireActivity().application as yApplication).waveRepository.playWave()

			withContext(Dispatchers.Main) {
				try {
//					finishProgress()
					findNavController().navigate(R.id.bigPlayerFrag)
				} catch (e: Exception) {
					Log.e(
						"DWIJ_TAG",
						"[playWave] Не удалось открыть плеер после запуска волны",
						e,
					)
				}
			}
//			val fWave = yMediaStore.store(requireContext().applicationContext).getWave()
//            withContext(Dispatchers.Main) {
//                finishProgress()
//                if (fWave != null) {
//                    (activity as MainActivity).playWave(fWave)
//                }
//            }
		}
	}

	fun openALLTracks(){
//		val fBndl = Bundle()
//		fBndl.putString(TrackListFrag.TRACKLIST_TYPE,TrackListFrag.LIST_OF_ALL)
//		(activity as MainActivity).mNavController
//			.navigate(R.id.action_homeFrag_to_trackListFrag,fBndl)
	}

	lateinit var mDialog: AlertDialog

	private fun finishProgress() {
		mDialog.dismiss()
	}

	private fun showProgress() {
		val fDialBuilder = AlertDialog.Builder(requireContext())
		fDialBuilder.setTitle("Loading wave")
		fDialBuilder.setMessage("wait plz")
		mDialog = fDialBuilder.show()

//		mDialog?.setMessage("Done $fProg of $fMax")
	}
}
