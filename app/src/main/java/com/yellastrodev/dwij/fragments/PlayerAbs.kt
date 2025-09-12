package com.yellastrodev.dwij.fragments

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.yellastrodev.dwij.R
import com.yellastrodev.dwij.models.PlayerModel
import com.yellastrodev.dwij.yApplication
import com.yellastrodev.yandexmusiclib.entities.CoverSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.getValue

open class PlayerAbs() : Fragment() {

//	var mPlayer: PlayerService? = null
	var mvSeekBar: SeekBar? = null
	lateinit var mvArtist: TextView
	lateinit var mvTitle: TextView
	lateinit var mvCover: ImageView
	lateinit var mvPlay: ImageButton
	var mvRandom: ImageButton? = null
	lateinit var mvNext: ImageButton
	lateinit var mvPrev: ImageButton

	val playerModel: PlayerModel by viewModels {
		PlayerModel.Factory(
			playerRepo = (requireActivity().application as yApplication).playerRepo,
			trackRepo = (requireActivity().application as yApplication).trackRepository,
			coverRepo = (requireActivity().application as yApplication).coverRepository
		)
	}

//	lateinit var mModel: PlayerModel

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
//		attachToService()
		val fdsf = 5
	}

//	public fun attachToService(){
//		mPlayer = (activity as MainActivity).mPlayer
//		mPlayer?.mPlayerFrag = this
//	}


	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
//		mModel = ViewModelProvider(this).get(PlayerModel::class.java)

		//val someInt = requireArguments().getInt("some_int")
		mvNext.setOnClickListener {
//
			detachSeekbar()
			lifecycleScope.launch {
				playerModel.nextTrack()
			}
//			GlobalScope.launch(Dispatchers.IO){mPlayer?.nextTrack()}

		}

		mvPrev.setOnClickListener {
			detachSeekbar()
			lifecycleScope.launch {
				playerModel.prevTrack()
			}

		}
		mvPlay.setOnClickListener {
			//val f_pos = mPlayer!!.mMediaPlayer.currentPosition
			playerModel.playAudio()
		}

		viewLifecycleOwner.lifecycleScope.launch {
			viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
				playerModel.track.collect { track ->
					if (track != null) {
						lifecycleScope.launch(Dispatchers.IO) {
							val bitmap = playerModel.coverRepo.getCover(track, CoverSize.`200x200`)

							// Ставим в ImageView на главном потоке
							withContext(Dispatchers.Main) {
								mvCover.setImageBitmap(bitmap)
							}
						}
						mvTitle.text = track.title
						mvArtist.text = track.artists.joinToString(", ") { it.name }
					}
				}
			}
		}

		lifecycleScope.launch {
			viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
				playerModel.playerState.collect {
					if (it.isPlaying) setPlay() else setPause()
//					val progress = if (it.currentPosition > 0) it.duration / it.currentPosition else 0L
					mvSeekBar?.progress = it.currentPosition.toInt()
					mvSeekBar?.max = it.duration.toInt()
				}
			}
		}
//		mvRandom?.setOnClickListener { v -> setRandom(v) }
//
//
//		if(mPlayer != null && mPlayer!!.mList.size>0) {
//			setTrack(
//				mPlayer!!.mList[mPlayer!!.m_CurentTrack],
//				mPlayer!!.mTrackList
//			)
//			setRandomBtn()
//			if(mPlayer!!.mMediaPlayer.isPlaying)
//				setPlay()
//			else setPause()
//		}else{
////			Snackbar.make(
////				view,
////				"Media mPlayer not initialized", Snackbar.LENGTH_LONG
////			).show()
//		}

	}

	override fun onResume() {
		super.onResume()
//		if (mPlayer!=null&& mPlayer!!.mMediaPlayer != null){
//			if(mPlayer!!.mMediaPlayer.isPlaying)
//				setPlay()
//			else setPause()
//		}
	}



	fun setRandomBtn(){
//		if (mPlayer == null) return
//		if (mPlayer!!.is_random) {
//			mvRandom?.setImageResource(R.drawable.random_on)
//
//		} else{
//			mvRandom?.setImageResource(R.drawable.random)
//
//		}
	}

	fun setRandom(fV: View){
//		val f_mode = mPlayer?.setRandomMode() ?: false
		setRandomBtn()
//		val f_msg = if (f_mode) "enable" else "disable"
//		val snack = Snackbar.make(fV,"Random mode $f_msg",Snackbar.LENGTH_SHORT)
//		snack.show()
	}
	lateinit var mTrackId: String
//	open fun setTrack(fTrack: iTrack, fTrackList: iTrackList?){
//		mTrackId = fTrack.mId
//		if(!isAdded) return
//		mvArtist?.setText(fTrack.mArtist)
//		mvTitle?.setText(fTrack.mTitle)
//
//		lifecycleScope.launch(Dispatchers.IO){
//			val fSingle = fTrack.set_Cover_toView(yMediaStore.store(requireContext()),400)
//            withContext(Dispatchers.Main) {
//                if (fSingle != null)
//                    mvCover?.setImageBitmap(fSingle)
//                else
//                    mvCover?.setImageResource(R.drawable.logo_big)
//            }
//		}
//
//		setRandomBtn()
//		initializeSeekBar()
//	}

	private lateinit var runnable:Runnable
	private var handler: Handler = Handler()
	private var isHandled = false
	fun initializeSeekBar() {
		Log.i("DWIJ_DEBUG","initializeSeekBar call")
//		if (mPlayer!=null&& mPlayer!!.mMediaPlayer != null && mvSeekBar != null){
//
//			mPlayer!!.onCustPrepareListener = {
//				Log.i("DWIJ_DEBUG","onCust PrepareListener call")
//				attachSeekBar()
//				mPlayer!!.onCustPrepareListener = {}
//			}
//		}
	}

	fun attachSeekBar(){
		Log.i("DWIJ_DEBUG","attachSeekBar call")

		isHandled = true
//		var fDur = mPlayer!!.mMediaPlayer.duration
//		mvSeekBar!!.max = fDur
//
//		runnable = Runnable {
//			if(isHandled) {
//				var fDur = mPlayer!!.mMediaPlayer.duration
//				if (fDur == 0) Log.e("DWIJ_DEBUG","seekbar handler runnable duration == 0")
//				mvSeekBar!!.max = fDur
//				val fCur = mPlayer!!.mMediaPlayer.currentPosition
//				mvSeekBar!!.progress = fCur
//				if (isHandled)
//					handler.postDelayed(runnable, 1000)
//			}}
//		handler.postDelayed(runnable, 1000)
//		mPlayer!!.onCustCompletionListener = {
//			Log.i("DWIJ_DEBUG","onCust CompletionListener call")
//
//			isHandled = false
//			mPlayer!!.onCustPrepareListener = {}
//			mvSeekBar!!.progress = 0
//		}
	}

	fun detachSeekbar(){
		Log.i("DWIJ_DEBUG","detach seekbar call")
		isHandled = false
	}

	fun setPause() {
		mvPlay.setImageResource(R.drawable.play)
	}

	fun setPlay() {
		mvPlay.setImageResource(R.drawable.stop)
	}

	var mDialog: AlertDialog? = null

	fun load(fProg: Int, fMax: Int) {
		val fH = Handler(Looper.getMainLooper())

		fH.post { if(mDialog == null){
			val fDialBuilder = AlertDialog.Builder(requireContext())
			fDialBuilder.setTitle("Loading data")
			fDialBuilder.setMessage("wait plz")
			mDialog = fDialBuilder.show()
		}
			mDialog?.setMessage("Done $fProg of $fMax") }

	}

	fun loadCompleate() {
		val fH = Handler(Looper.getMainLooper())

		fH.post{
			if(mDialog != null){
				mDialog!!.dismiss()
				mDialog = null
			}
		}

	}

	var mWaveDialog: AlertDialog? = null

	fun setProgress() {
		context?.let { val fDialBuilder = AlertDialog.Builder(it)
			fDialBuilder.setTitle("Loading wave")
			fDialBuilder.setMessage("wait plz")
			mWaveDialog = fDialBuilder.show() }

	}

	fun finishWaveDialog() {
		val fH = Handler(Looper.getMainLooper())

		fH.post{ mWaveDialog?.dismiss()}
	}
}