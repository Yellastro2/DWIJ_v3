package com.yellastrodev.dwij.fragments

import android.content.Intent
import android.net.Uri
import androidx.fragment.app.viewModels
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yellastrodev.dwij.TYPE
import com.yellastrodev.dwij.VALUE
import com.yellastrodev.dwij.models.GridPlaylistModel
import com.yellastrodev.dwij.models.TracklistModel
import com.yellastrodev.dwij.yApplication
import com.yelldev.dwij.android.KeyStore
import com.yelldev.dwij.android.MainActivity
import com.yelldev.dwij.android.R
import com.yelldev.dwij.android.entitis.YaM.YaPlaylist
import com.yelldev.dwij.android.entitis.YaM.YaSingleTrackList
import com.yelldev.dwij.android.entitis.YaM.YaTrack
import com.yelldev.dwij.android.entitis.YaM.yWave
import com.yelldev.dwij.android.entitis.iTrackList
import com.yelldev.dwij.android.models.ObjectViewModel
import com.yelldev.dwij.android.yMediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ObjectFrag : Fragment(R.layout.fragment_object) {

    companion object {
        val TRACK = "track"
        val PLAYLIST = "playlust"
        val ARTIST = "artist"

        fun newInstance() = ObjectFrag()
    }

    lateinit var mvTitle2: TextView

//    lateinit var mMain: MainActivity

//    private val mViewModel: ObjectViewModel by viewModels()

    lateinit var model: GridPlaylistModel
    lateinit var tracklistModel: TracklistModel

    var mType = ""
    var mValue: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // TODO: Use the ViewModel
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

//        val fMain = requireActivity() as MainActivity

        if(arguments != null) {
            model = GridPlaylistModel(
                repo = (requireActivity().application as yApplication).playlistRepository,
                coverRepo = (requireActivity().application as yApplication).albumCoverRepository
            )
            mType = requireArguments().getString(TYPE)!!
            mValue = requireArguments().getString(VALUE)!!

            if (mType == PLAYLIST){

                model = GridPlaylistModel(
                    repo = (requireActivity().application as yApplication).playlistRepository,
                    coverRepo = (requireActivity().application as yApplication).albumCoverRepository
                )

                tracklistModel = TracklistModel()

                val fMain = requireActivity() as MainActivity

                mViewModel.viewModelScope.launch(Dispatchers.Default) {
                    requireArguments().getString(KeyStore.USER)?.let {
                        mViewModel.mUser = it
                        mViewModel.mDataObject = yMediaStore.store(requireContext())
                            .getPlaylist(mViewModel.mValue, mViewModel.mUser!!)
                    }
                    if (mViewModel.mUser == null)
                        mViewModel.mDataObject = yMediaStore.store(requireContext())
                            .getYamPlaylist(mViewModel.mValue)
                    mViewModel.getAdapter(fMain)
                        .setList(mViewModel.mDataObject as iTrackList)
                    withContext(Dispatchers.Main) {
                        loadObject()
                    }
                }
                view.findViewById<RecyclerView>(R.id.fr_obj_recycler)
                    .adapter = mViewModel.getAdapter(fMain)
                view.findViewById<RecyclerView>(R.id.fr_obj_recycler)
                    .layoutManager = LinearLayoutManager(context)

            }else if (mViewModel.mType == TRACK){
                mViewModel.viewModelScope.launch(Dispatchers.Default) {
                    mViewModel.mDataObject = yMediaStore.store(requireContext())
                        .getTrack(mViewModel.mValue)
                    withContext(Dispatchers.Main) {
                        loadObject()
                    }

                }
            }
        }
        mMain = requireActivity() as MainActivity

        mvTitle2 = requireView().findViewById<TextView>(R.id.fr_object_title2)
        view.findViewById<View>(R.id.fr_object_wave_btn).setOnClickListener { onWaveBtn() }
        view.findViewById<View>(R.id.fr_object_play).setOnClickListener { onPlayBtn() }
        view.findViewById<View>(R.id.fr_object_share).setOnClickListener { share() }
    }

    private fun share() {
        if (mViewModel.mDataObject is YaTrack ||
        mViewModel.mDataObject is YaPlaylist){
            val fLink = mViewModel.mDataObject!!.getLink()

            val sendIntent: Intent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, fLink)
                putExtra(Intent.EXTRA_TITLE,mViewModel.mDataObject!!.getTitle())
                type = "text/plain"
            }

            val shareIntent = Intent.createChooser(sendIntent, null)
            startActivity(shareIntent)
        }
    }

    private fun onWaveBtn() {
        val fMain = requireActivity() as MainActivity
        GlobalScope.launch(Dispatchers.IO){

            withContext(Dispatchers.Main) {

            }
            val fStore = yMediaStore.store(fMain)

            fMain.mPlayer?.setWaveList(mViewModel.mDataObject?.let { fStore.getWave(it) } as yWave)
        }
        fMain.openPlayer()
    }

    private fun onPlayBtn() {
        val fMain = requireActivity() as MainActivity

        lifecycleScope.launch(Dispatchers.Default) {
            if (mViewModel.mDataObject is YaPlaylist)
                fMain.setTrack(0,mViewModel.mDataObject as YaPlaylist)
            if (mViewModel.mDataObject is YaTrack)
                fMain.setTrack(0, YaSingleTrackList(mViewModel.mDataObject as YaTrack))
        }

    }

    private fun loadObject() {

        mViewModel.mDataObject?.let {
            lifecycleScope.launch(Dispatchers.Default){
                val fRes = it.getImage(yMediaStore.store(requireContext()))
                withContext(Dispatchers.Main) {
                    requireView().findViewById<ImageView>(R.id.fr_object_image)
                        .setImageBitmap(fRes)
                }
            }
            requireView().findViewById<TextView>(R.id.fr_object_title)
                .text = it.getTitle()
            mvTitle2.text = it.getInfo()
            if (mViewModel.mType == TRACK){
                mvTitle2.setOnClickListener {v->
                    lifecycleScope.launch(Dispatchers.Default) {
                        mMain.showArtistChoise(it as YaTrack)
                    }
                }
            }
        }
    }

}