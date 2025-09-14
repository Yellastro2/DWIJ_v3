package com.yellastrodev.dwij.fragments

import androidx.fragment.app.viewModels
import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.AppBarLayout
import com.yellastrodev.dwij.R
import com.yellastrodev.dwij.TYPE
import com.yellastrodev.dwij.VALUE
import com.yellastrodev.dwij.models.TracklistModel
import com.yellastrodev.dwij.yApplication
import com.yellastrodev.yandexmusiclib.entities.CoverSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ObjectFrag : Fragment(R.layout.frag_object) {

    companion object {
        val TRACK = "track"
        val PLAYLIST = "playlist"
        val ARTIST = "artist"

        fun newInstance() = ObjectFrag()
    }

    lateinit var mvTitle2: TextView

//    lateinit var mMain: MainActivity

//    private val mViewModel: ObjectViewModel by viewModels()

    private val model: TracklistModel by viewModels {
        TracklistModel.Factory(
            repo = (requireActivity().application as yApplication).playlistRepository,
            coverRepo = (requireActivity().application as yApplication).coverRepository,
            trackRepo = (requireActivity().application as yApplication).trackRepository,
            playerRepo = (requireActivity().application as yApplication).playerRepo
        )
    }

    var mType = ""
    var mValue: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("DWIJ_TIMING", "ObjectFrag created")

        if(arguments != null) {
            mType = requireArguments().getString(TYPE)!!
            mValue = requireArguments().getString(VALUE)!!

            if (mType == PLAYLIST) {

                lifecycleScope.launch(Dispatchers.IO) {
                    model.setType(mType, mValue)

                }
            }
        }

//        Log.d("DWIJ_TIMING", "ObjectFrag created")
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d("DWIJ_TIMING", "ObjectFrag start onViewCreated")

        val appBarLayout = view.findViewById<AppBarLayout>(R.id.appBarLayout)
        val pinnedLayout = view.findViewById<View>(R.id.pinnedLayout)
        val toolbar = view.findViewById<View>(R.id.toolbar)
        val swipeRefreshLayout = view.findViewById<SwipeRefreshLayout>(R.id.fr_obj_refresher)

        swipeRefreshLayout.setOnRefreshListener {
            model.viewModelScope.launch { model.refreshObject()
                withContext(Dispatchers.Main) {
                    swipeRefreshLayout.isRefreshing = false
                }
            }
        }

        appBarLayout.addOnOffsetChangedListener(
            AppBarLayout.OnOffsetChangedListener { appBar, verticalOffset ->
//                if (verticalOffset  == appBarLayout.totalScrollRange) {
//                    // Полностью схлопнулся → показываем pinnedLayout
//                    pinnedLayout.visibility = View.VISIBLE
//                } else {
//                    // Любое другое состояние → скрываем pinnedLayout
//                    pinnedLayout.visibility = View.GONE
//                }
                    val percent = Math.abs(verticalOffset).toFloat() / appBarLayout.totalScrollRange
                toolbar.alpha = percent  // 0 — раскрыт, 1 — полностью схлопнул

//                swipeRefreshLayout.isEnabled = verticalOffset == 0

            }
        )



        if(arguments != null) {
//            mType = requireArguments().getString(TYPE)!!
//            mValue = requireArguments().getString(VALUE)!!

            if (mType == PLAYLIST){

//                lifecycleScope.launch(Dispatchers.IO) {
//                    model.setType(mType, mValue)
//
//                }

                viewLifecycleOwner.lifecycleScope.launch {
                    viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        model.playlist.collect { playlist ->
                            if (playlist != null) {
                                lifecycleScope.launch(Dispatchers.IO) {
                                    val bitmap = model.coverRepo.getCover(playlist, CoverSize.`200x200`)

                                    // Ставим в ImageView на главном потоке
                                    withContext(Dispatchers.Main) {
                                        view.findViewById<ImageView>(R.id.fr_object_image).setImageBitmap(bitmap)
                                    }
                                }
                                view.findViewById<TextView>(R.id.fr_object_title).text = playlist.title
                                view.findViewById<TextView>(R.id.fr_object_title_colaps).text = playlist.title
                                view.findViewById<TextView>(R.id.fr_object_title2).text = playlist.description ?: ""
                            }
                        }
                    }
                }

//                mViewModel.viewModelScope.launch(Dispatchers.Default) {
//                    requireArguments().getString(KeyStore.USER)?.let {
//                        mViewModel.mUser = it
//                        mViewModel.mDataObject = yMediaStore.store(requireContext())
//                            .getPlaylist(mViewModel.mValue, mViewModel.mUser!!)
//                    }
//                    if (mViewModel.mUser == null)
//                        mViewModel.mDataObject = yMediaStore.store(requireContext())
//                            .getYamPlaylist(mViewModel.mValue)
//                    mViewModel.getAdapter(fMain)
//                        .setList(mViewModel.mDataObject as iTrackList)
//                    withContext(Dispatchers.Main) {
//                        loadObject()
//                    }
//                }
                view.findViewById<RecyclerView>(R.id.fr_obj_recycler)
                    .adapter = model.adapter
                model.adapter.onItemClicked = { pos ->
                    Log.d("DWIJ_TIMING", "ObjectFrag onItemClick")
                    findNavController().navigate(
                        R.id.action_objectFrag_to_bigPlayerFrag
                    )
                    lifecycleScope.launch {
                        model.onTrackClicked(pos)
                    }

                }
                view.findViewById<RecyclerView>(R.id.fr_obj_recycler)
                    .layoutManager = LinearLayoutManager(context)

            }
//            else if (mViewModel.mType == TRACK){
//                mViewModel.viewModelScope.launch(Dispatchers.Default) {
//                    mViewModel.mDataObject = yMediaStore.store(requireContext())
//                        .getTrack(mViewModel.mValue)
//                    withContext(Dispatchers.Main) {
//                        loadObject()
//                    }
//
//                }
//            }
        }

        mvTitle2 = requireView().findViewById<TextView>(R.id.fr_object_title2)
        view.findViewById<TextView>(R.id.fr_object_title)
        view.findViewById<View>(R.id.fr_object_wave_btn).setOnClickListener { onWaveBtn() }
        view.findViewById<View>(R.id.fr_object_play).setOnClickListener { onPlayBtn() }
        view.findViewById<View>(R.id.fr_object_share).setOnClickListener { share() }

//        viewLifecycleOwner.lifecycleScope.launchWhenStarted {
//            model.openPlayerScreen.collect { shouldOpen ->
//                if (shouldOpen) {
//                    findNavController().navigate(
//                        R.id.action_objectFrag_to_bigPlayerFrag
//                    )
//                    // после навигации сбрасываем, чтобы событие не сработало повторно
//                    model.resetOpenPlayerScreen()
//                }
//            }
//        }


//        Log.d("DWIJ_TIMING", "ObjectFrag on view created")
//        view.post { Log.d("DWIJ_TIMING", "first frame drawn") }
    }

    private fun share() {
//        if (mViewModel.mDataObject is YaTrack ||
//        mViewModel.mDataObject is YaPlaylist){
//            val fLink = mViewModel.mDataObject!!.getLink()
//
//            val sendIntent: Intent = Intent().apply {
//                action = Intent.ACTION_SEND
//                putExtra(Intent.EXTRA_TEXT, fLink)
//                putExtra(Intent.EXTRA_TITLE,mViewModel.mDataObject!!.getTitle())
//                type = "text/plain"
//            }
//
//            val shareIntent = Intent.createChooser(sendIntent, null)
//            startActivity(shareIntent)
//        }
    }

    private fun onWaveBtn() {
//        val fMain = requireActivity() as MainActivity
//        GlobalScope.launch(Dispatchers.IO){
//
//            withContext(Dispatchers.Main) {
//
//            }
//            val fStore = yMediaStore.store(fMain)
//
//            fMain.mPlayer?.setWaveList(mViewModel.mDataObject?.let { fStore.getWave(it) } as yWave)
//        }
//        fMain.openPlayer()
    }

    private fun onPlayBtn() {
//        val fMain = requireActivity() as MainActivity
//
//        lifecycleScope.launch(Dispatchers.Default) {
//            if (mViewModel.mDataObject is YaPlaylist)
//                fMain.setTrack(0,mViewModel.mDataObject as YaPlaylist)
//            if (mViewModel.mDataObject is YaTrack)
//                fMain.setTrack(0, YaSingleTrackList(mViewModel.mDataObject as YaTrack))
//        }

    }

    private fun loadObject() {

//        mViewModel.mDataObject?.let {
//            lifecycleScope.launch(Dispatchers.Default){
//                val fRes = it.getImage(yMediaStore.store(requireContext()))
//                withContext(Dispatchers.Main) {
//                    requireView().findViewById<ImageView>(R.id.fr_object_image)
//                        .setImageBitmap(fRes)
//                }
//            }
//            requireView().findViewById<TextView>(R.id.fr_object_title)
//                .text = it.getTitle()
//            mvTitle2.text = it.getInfo()
//            if (mViewModel.mType == TRACK){
//                mvTitle2.setOnClickListener {v->
//                    lifecycleScope.launch(Dispatchers.Default) {
//                        mMain.showArtistChoise(it as YaTrack)
//                    }
//                }
//            }
//        }
    }

}