package com.yellastrodev.dwij.adapters

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.yellastrodev.dwij.R
import com.yellastrodev.dwij.utils.DurationFormat.Companion.formatDuration
import com.yellastrodev.dwij.utils.PlaylistsDiff
import com.yellastrodev.yandexmusiclib.entities.YaPlaylist
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

class GridPlaylistAdapter(
	var mTrack: Any? = null,
	private val loadCover: suspend (YaPlaylist) -> Bitmap
) :
RecyclerView.Adapter<GridPlaylistAdapter.ViewHolder>() {

	var mGridSize = 3
	var onClick: (YaPlaylist)-> Unit = {}

	var onCreatePlClick: () -> Unit = {}

	var onLongItemClick: (YaPlaylist) -> Unit = {}

	var mScope: CoroutineScope? = null

	private var mList: ArrayList<YaPlaylist> = ArrayList()
	fun setList(newList: ArrayList<YaPlaylist>){

		// Считаем дифф
		val diff = PlaylistsDiff.diffPlaylists(
			oldMap = mList.associateBy { it.playlistUuid },
			newList = newList
		)

		if (!diff.isNotEmpty()) return // ничего не изменилось

		// --- Удаления ---
		// Удаляем с конца, чтобы индексы не сдвигались
		diff.removed
			.mapNotNull { uuid -> mList.indexOfFirst { it.playlistUuid == uuid }.takeIf { it != -1 } }
			.sortedDescending()
			.forEach { index ->
				mList.removeAt(index)
				notifyItemRemoved(index)
			}

		// --- Добавления ---
		diff.added.forEach { uuid ->
			val indexInNew = newList.indexOfFirst { it.playlistUuid == uuid }
			if (indexInNew != -1) {
				mList.add(indexInNew, newList[indexInNew])
				notifyItemInserted(indexInNew)
			}
		}

		// --- Изменения ---
		diff.changed.forEach { uuid ->
			val indexInNew = newList.indexOfFirst { it.playlistUuid == uuid }
			if (indexInNew != -1) {
				mList[indexInNew] = newList[indexInNew]
				notifyItemChanged(indexInNew)
			}
		}

//		mList = ArrayList()
////		if (mTrack != null){
////			fList.removeAll(
////				fList.filter { it.getType()==YaLikedTracks.LIKED_ID })
////		}
////		mList.add(PlaylistCreateItem())
//		mList.addAll(fList)
//		notifyDataSetChanged()
	}

	fun getList() = mList

	fun init() {
//		mList.add(PlaylistCreateItem())
	}

//	fun setTrack(fTr: YaTrack){
//		mTrack = fTr
//		notifyDataSetChanged()
//	}

	class ViewHolder(view: View,
					 private val loadCover: suspend (YaPlaylist) -> Bitmap) : RecyclerView.ViewHolder(view) {
		val vTitle: TextView
		val vAutor: TextView
		val vImg: ImageView

		private var coverJob: Job? = null

		init {
			vTitle = view.findViewById(R.id.it_pl_grid_title)
			vAutor = view.findViewById(R.id.it_pl_grid_body)
			vImg = view.findViewById(R.id.it_pl_grid_img)
		}

		fun bind(playlist: YaPlaylist) {
			// Отменяем предыдущую загрузку для этого ViewHolder
			coverJob?.cancel()

			// Ставим placeholder или очищаем
//			vImg.setImageResource(R.drawable.placeholder)

			// Запускаем новую корутину для загрузки картинки
			coverJob = CoroutineScope(Dispatchers.IO).launch {
				try {
					val bitmap = loadCover(playlist)
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
			.inflate(R.layout.it_playlist_grid, viewGroup, false)
		view.layoutParams = ViewGroup.LayoutParams(mGridSize,mGridSize)

		return ViewHolder(view, loadCover)
	}

	@SuppressLint("CheckResult")
	override fun onBindViewHolder(viewHolder: ViewHolder, position: Int) {


		viewHolder.bind(mList[position])
//		mScope?.launch(Dispatchers.IO) {
//			try{
//				val bitmap = loadCover(mList[position])
////				val fRes = mList[position]
////					.getImage(yMediaStore.store(viewHolder.vAutor.context))
//                withContext(Dispatchers.Main) {
//                    viewHolder.vImg.setImageBitmap(bitmap)
//                }
//			}catch (e: Exception){
//				val fData = mList[position].title
//				Log.w("DWIJ_DEBUG",Exception("${fData}\n\n" +
//						"${e.stackTraceToString()}"))
//			}
//
//		}

//		if (mList[position].mId == PlaylistCreateItem.PLAY_CREATE_ITEM_ID){
//			viewHolder.vTitle.text = "Создать плейлист"
//			viewHolder.vAutor.text = ""
//			viewHolder.itemView.setOnClickListener { onCreatePlClick() }
//			return
//		}

		val s_many = 2
		val s_fev = 1
		val s_one = 0

		val f_size = mList[position].trackCount
		val f_dur = mList[position].durationMs ?: 0
		val fDurStr = formatDuration(f_dur)
		var f_numb = 0
		if(f_dur>60){
		}else{
			var f_end_type = 0
			var f_end_lettr = ""
			val f_end_num = (f_dur -
					Math.round((f_dur/10).toDouble())).toInt()
			if (f_end_num>4 || f_end_num == 0) f_end_lettr = "ов"
			else if (f_end_num>1) f_end_lettr = "а"

		}

		var f_end_lettr = ""
		val f_end_num = (f_size -
				Math.round((f_size/10).toDouble())).toInt()
		if (f_end_num>4 || f_end_num == 0) f_end_lettr = "ов"
		else if (f_end_num>1) f_end_lettr = "а"
		viewHolder.vTitle.text = mList[position].title
		viewHolder.vAutor.text = "$f_size трек$f_end_lettr - $fDurStr"


		val f_name_patrn = "back1_1"
		val i = Random.nextInt(300)
		val name = f_name_patrn + (i.toString().padStart(3, '0'));
		val globeId = viewHolder.itemView.resources.getIdentifier(name, "drawable",
			viewHolder.itemView.context.getPackageName());
		viewHolder.vImg.setImageResource(globeId)



		viewHolder.itemView.setOnLongClickListener {
			onLongItemClick(mList[position])
			return@setOnLongClickListener true
		}

//		if(mTrack != null && mTrack!!.mPlaylists.contains(mList[position].mId)){
//			viewHolder.vTitle.setBackgroundColor(0xD0080E75.toInt())
//			viewHolder.itemView.setOnClickListener {
//				val builder: AlertDialog.Builder = AlertDialog.Builder(viewHolder.itemView.context)
//				builder
//					.setMessage("Удалить трек?!!")
//					.setTitle("Удалить трек из плейлиста?")
//					.setPositiveButton("Yes,remove") { fD, o ->
//						fD.dismiss()
//						CoroutineScope(Dispatchers.IO).launch {
//							val fRes = (mList[position] as YaPlaylist).removeTrack(
//								yMediaStore.store(viewHolder.itemView.context),
//								mTrack!!
//							)
//                            withContext(Dispatchers.Main) {
//                                if (fRes) {
//                                    notifyItemChanged(position)
//                                } else {
//                                    Snackbar.make(
//                                        viewHolder.itemView,
//                                        KeyStore.s_network_error, Snackbar.LENGTH_LONG
//                                    )
//                                        .show()
//                                }
//                            }
//
//
//						}
//
//
//					}
//					.setNegativeButton("nenada") { fD, o -> fD.dismiss() }
//
//				val dialog: AlertDialog = builder.create()
//				dialog.show()
//				Snackbar.make(viewHolder.itemView.rootView.findViewById(R.id.content),
//					"Track already in", Snackbar.LENGTH_SHORT).show()}
//		}else {
			viewHolder.vTitle.setBackgroundColor(0x7AD5A54F.toInt())
			viewHolder.itemView.setOnClickListener {onClick(mList[position])
			}
//		}

	}

	override fun getItemCount() = mList.size
	fun removeItem(fPlist: YaPlaylist) {
		val fPos = mList.indexOf(fPlist)
		mList.remove(fPlist)
		notifyItemRemoved(fPos)
	}

}