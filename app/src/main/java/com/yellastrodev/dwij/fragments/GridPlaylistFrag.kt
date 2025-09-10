package com.yellastrodev.dwij.fragments

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.yellastrodev.dwij.R
import com.yellastrodev.dwij.data.repo.PlaylistRepository
import com.yellastrodev.dwij.data.source.PlaylistCacheSource
import com.yellastrodev.dwij.data.source.PlaylistRemoteSource
import com.yellastrodev.dwij.models.GridPlaylistModel
import com.yellastrodev.dwij.yApplication
import com.yellastrodev.yandexmusiclib.YamApiClient

class GridPlaylistFrag() : Fragment(R.layout.frag_grid_playlist) {

	companion object {
		val PLAYLIST_ACTION = "playlist_action"
		val ACTION_ADDTRACK = "add_track"
		val ACTION_DATA = "action_data"

	}

//	val LOG = yLog.log(GridPlaylistFrag::class.java.name)


	lateinit var mvRecyclerView: RecyclerView

	var mGridSize = 0

//	var mOnIteClick: (iPlaylist) -> Unit = {
//			fPl: iPlaylist ->
////		val snack = Snackbar.make(requireActivity().findViewById(android.R.id.content),
////			"Start playlist ${fPl.mTitle}", Snackbar.LENGTH_LONG)
////
////		snack.show()
//		(requireActivity() as MainActivity).showPlaylist(fPl.mId) }

	var mPickedTrack: String = "-1"
//	var mTrackObj: YaTrack? = null

	private val model: GridPlaylistModel by viewModels {
		GridPlaylistModel.Factory(
			repo = (requireActivity().application as yApplication).playlistRepository,
			coverRepo = (requireActivity().application as yApplication).albumCoverRepository
		)
	}

	@SuppressLint("CheckResult", "NotifyDataSetChanged")
	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {


		val displayMetrics = DisplayMetrics()



		requireActivity().windowManager.defaultDisplay.getMetrics(displayMetrics)

		val width = displayMetrics.widthPixels
//		var height = displayMetrics.heightPixels
		mGridSize = width /3

		var fTrackLoadJob: Deferred<Unit>? = null

		if(arguments != null){
			val fAction = requireArguments().getString(PLAYLIST_ACTION)
			val fTrackId = requireArguments().getString(ACTION_DATA)

//			if(fAction == ACTION_ADDTRACK && fTrackId != null){
////				model.viewModelScope.launch {
//				val fStore = yMediaStore.store(mMainActivity)
//				fTrackLoadJob = model.viewModelScope.async(Dispatchers.IO) {
//					mTrackObj = fStore.getTrack(fTrackId)
//                    withContext(Dispatchers.Main) {
//                        model.getAdapter().setTrack(mTrackObj!!)
//                    }
//				}
//					mPickedTrack = fTrackId
//					mOnIteClick = {
//							fPl: iPlaylist ->
//						GlobalScope.launch(Dispatchers.Default){
//							(fPl as YaPlaylist).addTrack(fStore,mTrackObj!!)
//                            withContext(Dispatchers.Main) {
//                                val snack = Snackbar.make(
//                                    view.rootView.findViewById(R.id.content),
//                                    "${mTrackObj!!.mTitle} added to ${fPl.mTitle}",
//                                    Snackbar.LENGTH_LONG
//                                )
//                                (activity as MainActivity).mNavController.popBackStack()
//                                snack.show()
//                            }
//						}.start()
////					}
//				}
//
////				Thread{
////					val fStore = yMediaStore.store(mMainActivity)
////					mTrackObj = fStore.getTrack(fTrackId)
////				}.start()
//
//			}
		}else {
			mPickedTrack = "-1"
//			mTrackObj = null
		}


		mvRecyclerView = view.findViewById<RecyclerView>(R.id.fr_ls_plls_recycl)

//		model.adapter.onClick = mOnIteClick
		model.adapter.mGridSize = mGridSize
//		model.adapter.onCreatePlClick = {
//			(activity as MainActivity).openFrame(CreateListFrag())
//		}
		model.adapter.onLongItemClick = { fPlist ->
			val builder: AlertDialog.Builder = AlertDialog.Builder(requireContext())
			builder
				.setMessage("Удалить плейлист?!!")
				.setTitle("Точно?")
				.setPositiveButton("Yes,remove") { fD, o ->
					fD.dismiss()
//					removePlList(fPlist)
				}
				.setNegativeButton("nenada") { fD, o -> fD.dismiss() }

			val dialog: AlertDialog = builder.create()
			dialog.show()

		}

		view.findViewById<SwipeRefreshLayout>(R.id.fr_ls_plls_swip).setOnRefreshListener {
			model.viewModelScope.launch { model.refreshPlaylists()
				withContext(Dispatchers.Main) {
					view.findViewById<SwipeRefreshLayout>(R.id.fr_ls_plls_swip).isRefreshing = false
				}
			}
		}

		mvRecyclerView.adapter = model.adapter
		mvRecyclerView.layoutManager = GridLayoutManager(context,3)


//		if (mTrackObj==null)
//			Thread{
//				val fLiked = fStore.getLikedTracks()
//				mvRecyclerView.post { f_adapt.dataSet.add(fLiked)
//				f_adapt.notifyDataSetChanged()}
//
//			}.start()


//		view.findViewById<Button>(R.id.fr_ls_plls_btn_sd)
//			.setOnClickListener { loadYaTracks() }
//		view.findViewById<View>(R.id.fr_list_pllist_back).setOnClickListener {
//			mMainActivity.mNavController.popBackStack()
//		}

//		view.findViewById<View>(R.id.fr_ls_plls_btn_create).setOnClickListener {
//			(activity as MainActivity).openFrame(CreateListFrag())
//		}

	}

//	fun removePlList(fPlist: iPlaylist) {
//		model.viewModelScope.launch(Dispatchers.IO) {
//			val fStore = yMediaStore.store(requireContext())
//			val fRes = fStore.deletePllist(fPlist as YaPlaylist)
//            withContext(Dispatchers.Main) {
//                if (fRes)
//                    model.adapter.removeItem(fPlist)
//                else
//                    Snackbar.make(requireView(), KeyStore.s_network_error, Snackbar.LENGTH_LONG)
//                        .show()
//            }
//
//		}
//	}

	fun loadYaTracks(){

	}


}