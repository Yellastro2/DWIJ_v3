package com.yellastrodev.dwij.fragments

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.os.Bundle
import android.preference.PreferenceManager
import android.util.Log
import android.view.View
import android.widget.AutoCompleteTextView
import androidx.annotation.OptIn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.navigation.fragment.findNavController
import com.yellastrodev.dwij.DWIJ_ACC_TOKEN
import com.yellastrodev.dwij.HomeCompactPlayerUiState
import com.yellastrodev.dwij.HomeScreen
import com.yellastrodev.dwij.R
import com.yellastrodev.dwij.TYPE
import com.yellastrodev.dwij.VALUE
import com.yellastrodev.dwij.YA_TOKEN
import com.yellastrodev.dwij.activities.MainActivity
import com.yellastrodev.dwij.yApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.yellastrodev.yandexmusiclib.entities.CoverSize

class HomeFrag: Fragment(R.layout.frag_home) {


    @OptIn(UnstableApi::class)
    @SuppressLint("CheckResult")
	/** Связывает домашний Compose-экран с навигацией и состоянием общего плеера Activity. */
	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		view.findViewById<ComposeView>(R.id.fr_home_player).apply {
			setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
			setContent {
				val playerModel = (activity as MainActivity).playerModel
				val track by playerModel.track.collectAsState()
				val playerState by playerModel.playerState.collectAsState()
				var cover by remember(track?.id) {
					mutableStateOf<ImageBitmap?>(null)
				}
				val unknownArtist = stringResource(R.string.home_player_unknown_artist)

				LaunchedEffect(track?.id) {
					track?.let { currentTrack ->
						playerModel.coverRepo
							.getCoverFlow(currentTrack, CoverSize.`100x100`)
							.flowOn(Dispatchers.IO)
							.collect { bitmap ->
								cover = bitmap.asImageBitmap()
							}
					}
				}

				HomeScreen(
					modifier = Modifier.fillMaxSize(),
					onSettingsClick = {
						(activity as MainActivity).mNavController.navigate(
							R.id.action_homeFrag_to_settingsAct,
						)
					},
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
					onCatalogClick = {
						(activity as MainActivity).mNavController.navigate(
							R.id.action_homeFrag_to_gridPlaylistFrag,
						)
					},
					onPlayerOpenClick = {
						findNavController().navigate(R.id.bigPlayerFrag)
					},
					onPlayerPlayPauseClick = playerModel::playAudio,
					onPlayerPreviousClick = {
						viewLifecycleOwner.lifecycleScope.launch {
							playerModel.prevTrack()
						}
					},
					onPlayerNextClick = {
						viewLifecycleOwner.lifecycleScope.launch {
							playerModel.nextTrack()
						}
					},
					player = track?.let { currentTrack ->
						HomeCompactPlayerUiState(
							title = currentTrack.title,
							artist = currentTrack.artists
								.joinToString(", ") { artist -> artist.name }
								.ifBlank { unknownArtist },
							cover = cover,
							isPlaying = playerState.isPlaying,
							currentPositionMillis = playerState.currentPosition,
							durationMillis = playerState.duration,
						)
					},
				)
			}
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
