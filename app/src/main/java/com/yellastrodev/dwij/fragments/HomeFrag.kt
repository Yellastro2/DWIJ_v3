package com.yellastrodev.dwij.fragments

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.os.Bundle
import android.preference.PreferenceManager
import android.util.Log
import android.view.View
import android.widget.AutoCompleteTextView
import android.widget.ImageButton
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.yellastrodev.dwij.DWIJ_ACC_TOKEN
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



	@SuppressLint("CheckResult")
	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		view.findViewById<View>(R.id.fr_home_pllist).setOnClickListener {
			(activity as MainActivity).mNavController.navigate(R.id.action_homeFrag_to_gridPlaylistFrag)


		}

		view.findViewById<View>(R.id.fr_home_tracks).setOnClickListener {
			val bundle = Bundle().apply {
				putString(TYPE, ObjectFrag.TRACKLIST)
			}
			findNavController().navigate(R.id.objectFrag,bundle)
		}

		view.findViewById<View>(R.id.fr_home_totalall_btn).setOnClickListener {
			openALLTracks()
		}

		view.findViewById<ImageButton>(R.id.fr_home_settngs).setOnClickListener {
			(activity as MainActivity).mNavController.navigate(R.id.action_homeFrag_to_settingsAct)
		}

		val mvSearch = view.findViewById<AutoCompleteTextView>(R.id.fr_home_search)

		view.findViewById<View>(R.id.fr_home_wave).setOnClickListener {
//			showProgress()
			lifecycleScope.launch(Dispatchers.IO){
				val waveList =
					(requireActivity().application as yApplication).waveRepository.playWave()

				withContext(Dispatchers.Main) {
					try {
//						finishProgress()
						findNavController().navigate(R.id.bigPlayerFrag)
					}catch (e: Exception){
						Log.e("DWIJ_TAG", "onWaveClick.finishProgress: ", e)
					}
				}
//				val fWave = yMediaStore.store(requireContext().applicationContext).getWave()
//                withContext(Dispatchers.Main) {
//                    finishProgress()
//                    if (fWave != null) {
//                        (activity as MainActivity).playWave(fWave)
//                    }
//                }
			}
		}

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