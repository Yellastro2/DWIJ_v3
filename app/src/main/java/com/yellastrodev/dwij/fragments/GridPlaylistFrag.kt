package com.yellastrodev.dwij.fragments

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.viewModelScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.yellastrodev.dwij.R
import com.yellastrodev.dwij.TYPE
import com.yellastrodev.dwij.VALUE
import com.yellastrodev.dwij.data.entities.dYaPlaylist
import com.yellastrodev.dwij.models.GridPlaylistModel
import com.yellastrodev.dwij.yApplication
import kotlinx.coroutines.GlobalScope

class GridPlaylistFrag() : Fragment(R.layout.frag_grid_playlist) {

	companion object {
		val PLAYLIST_ACTION = "playlist_action"
		val ACTION_ADDTRACK = "add_track"
		val ACTION_DATA = "action_data"

	}

	lateinit var mvRecyclerView: RecyclerView

	var mGridSize = 0

	var mOnItemClick: (dYaPlaylist) -> Unit = {
			playlist: dYaPlaylist ->
		val bundle = Bundle().apply {
			putString(TYPE, ObjectFrag.PLAYLIST)
			putString(VALUE, playlist.playlistUuid)
		}
		findNavController().navigate(R.id.action_gridPlaylistFrag_to_objectFrag,bundle)
	}

	var mPickedTrack: String = "-1"

	private val model: GridPlaylistModel by viewModels {
		GridPlaylistModel.Factory(
			repo = (requireActivity().application as yApplication).playlistRepository,
			coverRepo = (requireActivity().application as yApplication).coverRepository
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

			if(fAction == ACTION_ADDTRACK && fTrackId != null){
				model.adapter.pickedTrack = model.getTrack(fTrackId)
				view.findViewById<View>(R.id.fr_list_pllist_title).visibility = View.VISIBLE
				mOnItemClick = {
							fPl: dYaPlaylist ->
						GlobalScope.launch(Dispatchers.Default){
							model.addTrackToPlaylist(fPl,fTrackId)
                            withContext(Dispatchers.Main) {
                                val snack = Snackbar.make(
                                    view.rootView.findViewById(android.R.id.content),
                                    "track added to playlist",
                                    Snackbar.LENGTH_LONG
                                )
                                findNavController().popBackStack()
                                snack.show()
                            }
						}
					}
			}
		}else {
			mPickedTrack = "-1"
//			mTrackObj = null
		}


		mvRecyclerView = view.findViewById<RecyclerView>(R.id.fr_ls_plls_recycl)

		model.adapter.onClick = mOnItemClick
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