package com.yellastrodev.dwij.fragments

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import com.yellastrodev.dwij.R
import com.yellastrodev.dwij.TYPE
import com.yellastrodev.dwij.VALUE
import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.entities.iPlaylist
import com.yellastrodev.dwij.models.GridPlaylistModel
import com.yellastrodev.dwij.yApplication

class GridPlaylistFrag() : Fragment(R.layout.frag_grid_playlist) {

	companion object {
		val PLAYLIST_ACTION = "playlist_action"
		val ACTION_ADDTRACK = "add_track"
		val ACTION_DATA = "action_data"

	}

	lateinit var mvRecyclerView: RecyclerView

	var mGridSize = 0

	var mOnItemClick: (iPlaylist) -> Unit = {
			playlist: iPlaylist ->
		val bundle = Bundle().apply {
			putString(TYPE, ObjectFrag.PLAYLIST)
			putString(VALUE, playlist.getdId())
		}
		findNavController().navigate(R.id.action_gridPlaylistFrag_to_objectFrag,bundle)
	}

	var mPickedTrack: String = "-1"

	private val model: GridPlaylistModel by viewModels {
		GridPlaylistModel.Factory(
			repo = (requireActivity().application as yApplication).playlistRepository,
			trackRepo = (requireActivity().application as yApplication).trackRepository,
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

		if(arguments != null){
			val fAction = requireArguments().getString(PLAYLIST_ACTION)
			val fTrackId = requireArguments().getString(ACTION_DATA)

			if(fAction == ACTION_ADDTRACK && fTrackId != null){
				viewLifecycleOwner.lifecycleScope.launch {
					when (val result = model.getTrack(fTrackId)) {
						is DataResult.Success -> model.adapter.pickedTrack = result.value
						is DataResult.Failure -> Snackbar.make(
							view,
							"Не удалось загрузить трек",
							Snackbar.LENGTH_LONG
						).show()
					}
				}
				view.findViewById<TextView>(R.id.fr_list_pllist_title).text = "добавить в плейлист"
				mOnItemClick = { fPl: iPlaylist ->
					viewLifecycleOwner.lifecycleScope.launch {
						when (model.addTrackToPlaylist(fPl, fTrackId)) {
							is DataResult.Success -> {
								Snackbar.make(
									view,
									"Трек добавлен в плейлист",
									Snackbar.LENGTH_LONG
								).show()
								findNavController().popBackStack()
							}
							is DataResult.Failure -> Snackbar.make(
								view,
								"Не удалось добавить трек",
								Snackbar.LENGTH_LONG
							).show()
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
			viewLifecycleOwner.lifecycleScope.launch {
				model.refreshPlaylists()
				view.findViewById<SwipeRefreshLayout>(R.id.fr_ls_plls_swip).isRefreshing = false
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
		view.findViewById<View>(R.id.fr_list_pllist_back).setOnClickListener {
			findNavController().popBackStack()
		}

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
