package com.yellastrodev.dwij.fragments

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.util.DisplayMetrics
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewPropertyAnimator
import android.widget.Button
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.yellastrodev.dwij.R
import com.yellastrodev.dwij.data.entities.dYaPlaylist
import com.yellastrodev.dwij.data.entities.dYaTrack
import com.yellastrodev.dwij.models.PlayerModel
import com.yellastrodev.dwij.yApplication
import com.yellastrodev.yandexmusiclib.CONSTANTS.Companion.LIKED_ID
import com.yellastrodev.yandexmusiclib.entities.CoverSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.getValue
import androidx.core.content.ContextCompat
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayoutManager
import com.google.android.flexbox.JustifyContent
import com.yellastrodev.dwij.activities.MainActivity

class BigPlayerFrag() :
	PlayerAbs()
{
	override val TAG = "BigPlayerFrag"


	val sPrevieAlpha = 0.3F

	lateinit var mvTrackList: Button
	lateinit var mvPlListFlexbox: RecyclerView
	lateinit var mvToPlaylist: View
	lateinit var mvAlbum: TextView
	lateinit var mvLike: ImageView
	lateinit var mvRestrict: TextView

//	var mTrack: iTrack? = null

	var isLiked = false

	@SuppressLint("MissingInflatedId")
	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?
	): View? {

		Log.d("DWIJ_TIMING", "BigPlayerFrag onCreateView")


		val view = inflater.inflate(R.layout.frag_player,container,false)

		mvSeekBar = view.findViewById(R.id.seekBar)
		mvTitle = view.findViewById(R.id.txt_title)
		mvArtist = view.findViewById(R.id.txt_artist)
		mvCover = view.findViewById(R.id.fr_player_cover)
		mvPlay = view.findViewById(R.id.btn_play)
		mvPrev = view.findViewById(R.id.btn_prev)
		mvNext = view.findViewById(R.id.btn_next)
		mvRandom = view.findViewById(R.id.fr_player_random)
		mvMainTitle = view.findViewById(R.id.bigplayer_main_title)
		mvAlbum = view.findViewById(R.id.fr_player_album_name)
		mvLike = view.findViewById(R.id.fr_player_like)
		mvRestrict =  view.findViewById(R.id.fr_player_restrict)


		Log.d("DWIJ_TIMING", "BigPlayerFrag onCreateView finish")
		return view
	}

	override fun getCoverSize(): CoverSize {
		return CoverSize.`400x400`
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		mvTitle.setOnClickListener { openTrackInfo() }
		var doubleClick = false

		mvLike.alpha = 0.0F
		mvAlbum.alpha = 0.0F

		showPreviewLike()

		var isLike = false

		mvCover.setOnClickListener {
			val fAnims = showPreviewLike()

			if (doubleClick!!) {
//				val fStore = yMediaStore.store(requireContext())
//				mModel.viewModelScope.launch(Dispatchers.IO){
//					fStore.likeTrack(mTrackId)
//                    withContext(Dispatchers.Main) {
//                        setLikedState(fStore)
//                    }
//				}
				isLike = !isLike
				fAnims.forEach { it.cancel() }
				mvAlbum.animate()
					.alpha(0.0F)
					.setDuration(300)
				var fFirstDur = 300
				var fSecDur = 200
				if (isLike){
					fFirstDur = 100
					fSecDur = 400
				}
				val fSecondAnim = mvLike.animate()
					.alpha(1F)
					.setDuration(fFirstDur.toLong())
					.withEndAction { mvLike.animate()
						.alpha(0.0F)
						.setDuration(fSecDur.toLong())
					}
			}else
				doubleClick = true
			Handler().postDelayed({ doubleClick = false }, 500)
		}

//		view.findViewById<View>(R.id.fr_bg_player_btn_close).setOnClickListener {
//			(activity as MainActivity).mNavController.navigate(R.id.action_bigPlayerFrag_to_trackListFrag)
//		}

		val fvRepeat = view.findViewById<ImageView>(R.id.fr_player_cycle)
		fvRepeat.setOnClickListener {
//			mPlayer?.let {
//				mPlayer?.isRepeat = !mPlayer?.isRepeat!!
//				if (mPlayer?.isRepeat!!)
//					fvRepeat.setBackgroundColor(Color.GREEN)
//				else
//					fvRepeat.background = null
//			}

		}

//		if (mPlayer?.isRepeat!!)
//			fvRepeat.setBackgroundColor(Color.GREEN)
//		else
//			fvRepeat.background = null



		mvSeekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
			override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
				if (fromUser) {
					lifecycleScope.launch {
						playerModel.seekTo(progress.toLong())
					}
					val dsds =5
				}
			}

			override fun onStartTrackingTouch(seekBar: SeekBar) {
			}

			override fun onStopTrackingTouch(seekBar: SeekBar) {
			}
		})
//		initializeSeekBar()
		mvToPlaylist = view.findViewById<View>(R.id.fr_bigplay_topl_text)
		mvToPlaylist.setOnClickListener{
			toPlaylist()
		}

		mvPlListFlexbox = view.findViewById(R.id.fr_bg_player_pllist_flex)
		mvPlListFlexbox.setOnClickListener {
			toPlaylist()
		}
		val displayMetrics = DisplayMetrics()
		requireActivity().windowManager.defaultDisplay.getMetrics(displayMetrics)

		var width = displayMetrics.widthPixels
		mvCover.layoutParams = ConstraintLayout.LayoutParams(width,width)

//		if (mTrack != null){
//			setTrack(mTrack!!,null)
//		}


		Log.d("DWIJ_TIMING", "BigPlayerFrag onViewCreated")
		view.post { Log.d("TIMING", "BigPlayerFrag first frame drawn") }
	}

	override suspend fun onTrackFlow(track: dYaTrack){
		playerModel.playlistRepo.getPlaylistsByKeys(track.playlists)
			.take(1) // забираем только один результат
			.collect { fPlLists ->
			Log.d(TAG, "BigPlayer.onTrackFlow collect playlists=$fPlLists")
			val filtered = fPlLists.filter { it.kind != LIKED_ID }
			withContext(Dispatchers.Main) {
				if (filtered.isNotEmpty()) {
					mvToPlaylist.visibility = View.GONE
					val lm = FlexboxLayoutManager(context).apply {
						flexDirection = FlexDirection.ROW
						flexWrap = FlexWrap.WRAP
						justifyContent = JustifyContent.CENTER
						alignItems = AlignItems.CENTER
					}
					mvPlListFlexbox.layoutManager = lm
					mvPlListFlexbox.adapter = CustomAdapter(filtered, playerModel)
				}else {
					mvToPlaylist.visibility = View.VISIBLE
					mvPlListFlexbox.adapter = null
				}

			}
		}
	}



	private fun openTrackInfo() {
//		(activity as MainActivity).openTrackInfo(mTrackId)
	}


	override fun onResume() {
		super.onResume()
//		attachToService()
	}

	private fun toPlaylist() {
		val fBndl = Bundle()
		fBndl.putString(GridPlaylistFrag.PLAYLIST_ACTION,GridPlaylistFrag.ACTION_ADDTRACK)
		fBndl.putString(GridPlaylistFrag.ACTION_DATA, playerModel.track.value!!.id)

		(activity as MainActivity).mNavController.navigate(R.id.gridPlaylistFrag, fBndl)
	}



//	fun setLikedState(fStore: yMediaStore){
//		val fisLiked = fStore.isTrackLiked(mTrackId)
//		if (fisLiked != isLiked){
//			if (fisLiked)
//				mvLike.drawable.setTint(Color.parseColor(COLOR_PINK))
//			else
//				mvLike.drawable.setTint(Color.parseColor("#FFFFFF"))
//		}
//		isLiked = fisLiked
//	}

//	override fun setTrack(fTrack: iTrack, fTrackList: iTrackList?) {
//
//		super.setTrack(fTrack, fTrackList)
//		mTrack = fTrack
//		val fStore = yMediaStore.store(requireContext())
//		if (fTrack is YaTrack){
//			mvRestrict.visibility =  if (!fTrack.isAvaibale) View.VISIBLE else View.GONE
//			lifecycleScope.launch(Dispatchers.Default){
//				val fCashed = fStore.getTrack(fTrack.mId)
//				if (fCashed != fTrack) {
//                    withContext(Dispatchers.Main) {
//                        fTrack.mPlaylists = fCashed.mPlaylists
//                        setPlaylists(fTrack, fStore)
//                    }
//
//				}
//			}
//		}
//
//
//
//
//		setLikedState(fStore)
//
//
//		setPlaylists(fTrack,fStore)
//		mvAlbum.text = ""
//		fTrack.mAlbums.forEach {
//			mvAlbum.text = if (mvAlbum.text == "") it else "${mvAlbum.text}, $it"   }
//
//		val fFirstAnimAlbum = mvAlbum.animate()
//			.alpha(sPrevieAlpha)
//			.setDuration(1200)
//			.withEndAction { mvAlbum.animate()
//				.alpha(0.0F)
//				.setDuration(500)
//			}
//
//
//		if (fTrackList != null) {
//			mvMainTitle.text = fTrackList.getTitle()
//		}
//	}


	fun showPreviewLike(): List<ViewPropertyAnimator> {

		val fFirstAnim = mvLike.animate()
			.alpha(sPrevieAlpha)
			.setDuration(800)
			.withEndAction { mvLike.animate()
				.alpha(0.0F)
				.setDuration(300)
			}
		val fFirstAnimAlbum = mvAlbum.animate()
			.alpha(sPrevieAlpha)
			.setDuration(1200)
			.withEndAction { mvAlbum.animate()
				.alpha(0.0F)
				.setDuration(500)
			}

		return listOf(fFirstAnim,fFirstAnimAlbum)
	}
//
//	private fun updPlList(fPlLists: ArrayList<iPlaylist>) {
//		if(fPlLists.size>0){
//			mvPlListFlexbox.adapter = CustomAdapter(fPlLists,
//				activity as MainActivity
//			)
//			mvToPlaylist.visibility = View.GONE
//		}else{
//			mvToPlaylist.visibility = View.VISIBLE
//		}
//	}

	class CustomAdapter(
		private val dataSet: List<dYaPlaylist>,
		val model: PlayerModel
	) :
		RecyclerView.Adapter<CustomAdapter.ViewHolder>() {

		lateinit var mRecyclerView: RecyclerView




		override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
			super.onAttachedToRecyclerView(recyclerView)
			mRecyclerView = recyclerView
		}

		class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
			val vTitle: TextView

			init {
				vTitle = view.findViewById(R.id.it_pllist_flex_title)

			}
		}

		override fun onCreateViewHolder(viewGroup: ViewGroup, viewType: Int): ViewHolder {
			val view = LayoutInflater.from(viewGroup.context)
				.inflate(R.layout.it_playlist_flex, viewGroup, false)

			view.setOnClickListener {
				mRecyclerView.callOnClick() }
			return ViewHolder(view)
		}

		override fun onBindViewHolder(viewHolder: ViewHolder, position: Int) {
//			viewHolder.itemView.setOnClickListener {
////				TODO
//				  }
			viewHolder.vTitle.text = dataSet[position].title

			viewHolder.vTitle.apply {
				background = model.getBackground(viewHolder.vTitle.context, dataSet[position].title)
			}

			viewHolder.itemView.setOnClickListener {
				mRecyclerView.callOnClick() }
		}

		override fun getItemCount() = dataSet.size



	}
}