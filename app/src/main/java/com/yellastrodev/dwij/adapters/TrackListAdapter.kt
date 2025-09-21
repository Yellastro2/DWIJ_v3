package com.yellastrodev.dwij.adapters

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.yellastrodev.dwij.R
import com.yellastrodev.dwij.data.entities.dYaTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TrackListAdapter(
    private val loadCover: suspend (dYaTrack) -> Bitmap
) :
    RecyclerView.Adapter<TrackListAdapter.ViewHolder>() {
    var mScope: CoroutineScope? = null


    var mListOfObj: ArrayList<dYaTrack> = ArrayList<dYaTrack>()

    var mInitJob:  Deferred<Unit>? = null


    var onItemClicked: (pos: Int) -> Unit = { pos ->}


    @SuppressLint("NotifyDataSetChanged")
    fun setList(allTracks: ArrayList<dYaTrack>) {
        Log.d("TrackListAdapter", "setList: ${allTracks.size}")
        val diff = diffTracks(mListOfObj, allTracks)
//        mListOfObj = allTracks
//        mInitJob = null
//        notifyDataSetChanged()
    }

    /**
     * проверяет изменения в списках, включая позиции каждого трека.
     * читает их только по .id, игнорирует остальные поля и изменения самих обьектов
     */
    private fun diffTracks(
        oldTracks: ArrayList<dYaTrack>,
        newTracks: ArrayList<dYaTrack>
    ) {
        val oldMap = oldTracks.associateBy { it.id }
        val newMap = newTracks.associateBy { it.id }
        val removed = oldTracks.filter { !newMap.containsKey(it.id) }
        val added = newTracks.filter { !oldMap.containsKey(it.id) }

        val oldIndexMap = oldTracks.mapIndexed { index, track -> track.id to index }.toMap()
        val newIndexMap = newTracks.mapIndexed { index, track -> track.id to index }.toMap()

        // 1) удаляем (по убыванию индексов чтобы не ломать позиции)
        removed.mapNotNull { oldIndexMap[it.id] }
            .sortedDescending()
            .forEach { idx ->
                mListOfObj.removeAt(idx)
                notifyItemRemoved(idx)
            }

        // 2) добавляем (по возрастанию индексов)
        val toAdd = added.map { track -> newIndexMap[track.id]!! to track }
            .sortedBy { it.first }
        toAdd.forEach { (idx, track) ->
            val insertIdx = idx.coerceIn(0, mListOfObj.size)
            mListOfObj.add(insertIdx, track)
            notifyItemInserted(insertIdx)
        }

        // 3) выравниваем порядок: проходим по newTracks и двигаем элементы в нужные позиции
        for (i in newTracks.indices) {
            val id = newTracks[i].id
            val curr = mListOfObj.indexOfFirst { it.id == id }
            if (curr == -1) continue // вдруг не найдено (безопасно пропустить)
            if (curr != i) {
                val item = mListOfObj.removeAt(curr)
                mListOfObj.add(i, item)
                notifyItemMoved(curr, i)
            }
        }

        mInitJob = null
    }

    fun addToList(fTracks: Collection<dYaTrack>) {

        mListOfObj.addAll(fTracks)
        notifyDataSetChanged()

    }

    class ViewHolder(view: View, private val loadCover: suspend (dYaTrack) -> Bitmap) : RecyclerView.ViewHolder(view) {
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

        fun bind(track: dYaTrack) {
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
        viewHolder.itemView.setOnClickListener {
            onItemClicked(position)
        }


        viewHolder.bind(mListOfObj[position])

        viewHolder.vTitle.text = mListOfObj[position].title
        var artistsString = mListOfObj[position].artists.joinToString(", ") { it.name }
        viewHolder.vArtist.text = artistsString

        val f_name_patrn = "back1_1"
        val i = 0//Random.nextInt(300)
        val name = f_name_patrn + (i.toString().padStart(3, '0'));
        val globeId = viewHolder.itemView.resources.getIdentifier(name, "drawable",
            viewHolder.itemView.context.getPackageName());
        viewHolder.vImg.setImageResource(globeId)
    }

    override fun getItemCount() = mListOfObj.size
}