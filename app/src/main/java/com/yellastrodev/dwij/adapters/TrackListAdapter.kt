package com.yellastrodev.dwij.adapters

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.yellastrodev.dwij.R
import com.yellastrodev.yandexmusiclib.entities.YaPlaylist
import com.yellastrodev.yandexmusiclib.entities.YaTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TrackListAdapter(
    private val loadCover: suspend (YaTrack) -> Bitmap
) :
    RecyclerView.Adapter<TrackListAdapter.ViewHolder>() {
    var mScope: CoroutineScope? = null


    var mListOfObj: ArrayList<YaTrack> = ArrayList<YaTrack>()

    var mInitJob:  Deferred<Unit>? = null


    fun setList(allTracks: ArrayList<YaTrack>) {
        mListOfObj = allTracks
        mInitJob = null
        notifyDataSetChanged()
    }

    fun addToList(fTracks: Collection<YaTrack>) {

        mListOfObj.addAll(fTracks)
        notifyDataSetChanged()

    }

    class ViewHolder(view: View, private val loadCover: suspend (YaTrack) -> Bitmap) : RecyclerView.ViewHolder(view) {
        val vTitle: TextView
        val vArtist: TextView
        val vImg: ImageView
        var mId: Int = -1


        private var coverJob: Job? = null

        init {
            vTitle = view.findViewById(R.id.it_track_title)
            vArtist = view.findViewById(R.id.it_track_autor)
            vImg = view.findViewById(R.id.it_track_img)
        }

        fun bind(track: YaTrack) {
            // Отменяем предыдущую загрузку для этого ViewHolder
            coverJob?.cancel()

            // Ставим placeholder или очищаем
//			vImg.setImageResource(R.drawable.placeholder)

            // Запускаем новую корутину для загрузки картинки
            coverJob = CoroutineScope(Dispatchers.IO).launch {
                try {
                    val bitmap = loadCover(track)
                    withContext(Dispatchers.Main) {
                        vImg.setImageBitmap(bitmap)
                    }
                } catch (_: CancellationException) {
                    // если отменили, ничего не делаем
                } catch (e: Exception) {
                    // можно логировать или ставить ошибочный placeholder
//					imageView.setImageResource(R.drawable.error_placeholder)
                }
            }
        }
    }

    override fun onCreateViewHolder(viewGroup: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(viewGroup.context)
            .inflate(R.layout.it_track, viewGroup, false)


        return ViewHolder(view, loadCover)
    }

    @SuppressLint("SuspiciousIndentation")
    override fun onBindViewHolder(viewHolder: ViewHolder, position: Int) {
        viewHolder.mId = position
//        viewHolder.itemView.setOnClickListener {
//            CoroutineScope(Dispatchers.Default).async {
//                mMain.setTrack(position, mTrackList)
//            }}
        viewHolder.bind(mListOfObj[position])

        viewHolder.vTitle.text = mListOfObj[position].title
        var artistsString = mListOfObj[position].artists.fold("") { acc, yaArtist ->
            acc + yaArtist.name + ", "
        }
        artistsString = artistsString.removeSuffix(",")
        viewHolder.vArtist.text = artistsString
//
//        CoroutineScope(Dispatchers.Default)
//            .async(Dispatchers.Default) {
//                // если так не делать, при быстром скроле списка на одном вьюхолдере
//                // будут висеть сразу несколько асинхов - загрузятся поочередно несколько картинок
//                if (viewHolder.mId != position)
//                    return@async
//                val fSingle = mListOfObj[position].set_Cover_toView(
//                    yMediaStore.store(viewHolder.vAutor.context),400)
//                if (viewHolder.mId != position)
//                    return@async
//                CoroutineScope(Dispatchers.Main).async {
//                    if (viewHolder.mId != position)
//                        return@async
//                    if (fSingle!=null)
//                        viewHolder.vImg.setImageBitmap(fSingle)
//                    else
//                        Log.w("DWIJ_DEBUG",fSingle)
//                }
//            }




        val f_name_patrn = "back1_1"
        val i = 0//Random.nextInt(300)
        val name = f_name_patrn + (i.toString().padStart(3, '0'));
        val globeId = viewHolder.itemView.resources.getIdentifier(name, "drawable",
            viewHolder.itemView.context.getPackageName());
        viewHolder.vImg.setImageResource(globeId)
        GlobalScope.launch(Dispatchers.IO){

        }
//			val fSingle = dataSet[position].set_Cover_toView(
//				yMediaStore.store(viewHolder.vAutor.context),400)
//			if(fSingle != null)
//				fSingle.subscribe({viewHolder.vImg.setImageBitmap(it)},{Log.w("DWIJ_DEBUG",it)})

//				Thread {
//					val bitmap = dataSet[position].set_Cover_toView(yMediaStore.store(viewHolder.vAutor.context))
//					if (bitmap != null){
//						viewHolder.itemView.post { viewHolder.vImg.setImageBitmap(bitmap) }
//					}
//				}.start()
    }

    override fun getItemCount() = mListOfObj.size



}