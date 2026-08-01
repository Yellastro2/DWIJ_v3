package com.yellastrodev.dwij.adapters

import android.graphics.Bitmap
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.yellastrodev.dwij.R
import com.yellastrodev.dwij.data.entities.dYaTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TrackListAdapter(
    private val loadCover: suspend (dYaTrack) -> Bitmap
) :
    RecyclerView.Adapter<TrackListAdapter.ViewHolder>() {
    var mScope: CoroutineScope? = null


    private val mListOfObj = ArrayList<dYaTrack>()


    var onItemClicked: (position: Int, track: dYaTrack) -> Unit = { _, _ -> }


    fun setList(allTracks: List<dYaTrack>) {
        Log.d(TAG, "[setList] Треков=${allTracks.size}")

        val oldList = mListOfObj.toList()
        val newList = allTracks.toList()
        val oldKeys = occurrenceKeys(oldList)
        val newKeys = occurrenceKeys(newList)
        val diffCallback = object : DiffUtil.Callback() {
            override fun getOldListSize() = oldList.size
            override fun getNewListSize() = newList.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                oldKeys[oldItemPosition] == newKeys[newItemPosition]
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                oldList[oldItemPosition].hasSameVisibleContentAs(newList[newItemPosition])
        }
        val diffResult = DiffUtil.calculateDiff(diffCallback)

        mListOfObj.clear()
        mListOfObj.addAll(newList)
        diffResult.dispatchUpdatesTo(this)
    }

    private fun occurrenceKeys(tracks: List<dYaTrack>): List<Pair<String, Int>> {
        val counts = mutableMapOf<String, Int>()
        return tracks.map { track ->
            val occurrence = counts.getOrDefault(track.id, 0)
            counts[track.id] = occurrence + 1
            track.id to occurrence
        }
    }

    private fun dYaTrack.hasSameVisibleContentAs(other: dYaTrack): Boolean =
        title == other.title &&
            available == other.available &&
            getCoverUriAny() == other.getCoverUriAny() &&
            durationMs == other.durationMs &&
            artists == other.artists

    class ViewHolder(
        view: View,
        private val loadCover: suspend (dYaTrack) -> Bitmap,
        private val scope: CoroutineScope
    ) : RecyclerView.ViewHolder(view) {
        val vTitle: TextView
        val vArtist: TextView
        val vImg: ImageView
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
            coverJob = scope.launch(Dispatchers.IO) {
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

        fun recycle() {
            coverJob?.cancel()
            coverJob = null
        }
    }

    override fun onCreateViewHolder(viewGroup: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(viewGroup.context)
            .inflate(R.layout.it_track, viewGroup, false)


        return ViewHolder(
            view = view,
            loadCover = loadCover,
            scope = checkNotNull(mScope) { "TrackListAdapter.mScope не задан" }
        )
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, position: Int) {
        viewHolder.itemView.setOnClickListener {
            val currentPosition = viewHolder.bindingAdapterPosition
            if (currentPosition == RecyclerView.NO_POSITION) {
                return@setOnClickListener
            }
            val track = mListOfObj.getOrNull(currentPosition)
                ?: return@setOnClickListener
            onItemClicked(currentPosition, track)
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

    override fun onViewRecycled(holder: ViewHolder) {
        holder.recycle()
        super.onViewRecycled(holder)
    }

    private companion object {
        const val TAG = "TrackListAdapter"
    }
}
