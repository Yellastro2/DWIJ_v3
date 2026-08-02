package com.yellastrodev.dwij.fragments

import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import com.yellastrodev.dwij.HomeMusicSource
import com.yellastrodev.dwij.MusicSourceSelectionStore
import com.yellastrodev.dwij.PlaylistGridScreen
import com.yellastrodev.dwij.PlaylistGridScreenItem
import com.yellastrodev.dwij.R
import com.yellastrodev.dwij.TYPE
import com.yellastrodev.dwij.VALUE
import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.entities.LocalPlaylistEntity
import com.yellastrodev.dwij.data.entities.LocalPlaylistOrigin
import com.yellastrodev.dwij.data.entities.dYaLikeTracklist.Companion.KIND_LIKED
import com.yellastrodev.dwij.data.entities.dYaPlaylist
import com.yellastrodev.dwij.data.entities.dYaTrack
import com.yellastrodev.dwij.data.repo.LocalMusicRepository
import com.yellastrodev.dwij.models.GridPlaylistModel
import com.yellastrodev.dwij.utils.DurationFormat.Companion.formatDuration
import com.yellastrodev.dwij.utils.LangFormats.Companion.getNumericPostfix
import com.yellastrodev.dwij.work.LocalLibrarySyncWorker
import com.yellastrodev.dwij.yApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Связывает Compose-сетку плейлистов с Яндекс- и локальными репозиториями. */
class GridPlaylistFrag : Fragment() {
    private val app: yApplication
        get() = requireActivity().application as yApplication

    private val selectedMusicSource = MusicSourceSelectionStore.selectedSource
    private var permissionRequestInFlight = false

    private val model: GridPlaylistModel by viewModels {
        GridPlaylistModel.Factory(
            repo = app.playlistRepository,
            trackRepo = app.trackRepository,
            coverRepo = app.coverRepository,
        )
    }

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        permissionRequestInFlight = false
        val granted = areLocalPermissionsGranted(permissions)
        if (granted) {
            persistSource(HomeMusicSource.Local)
            LocalLibrarySyncWorker.enqueueImmediate(requireContext().applicationContext)
        } else {
            Log.w(TAG, "[audioPermissionLauncher] Доступ к локальной музыке не выдан")
            persistSource(HomeMusicSource.Yandex)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readSavedSource()
    }

    /** Создаёт полностью Compose-представление и подготавливает обложки для видимых данных. */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            val yandexPlaylists by model.playlists.collectAsState()
            val localPlaylists by app.localMusicRepository.playlists
                .collectAsState(initial = emptyList())
            val musicSource by selectedMusicSource.collectAsState()
            val pickedTrackId = arguments?.getString(ACTION_DATA)
                .takeIf { isAddTrackMode() }
            var pickedTrack by remember(pickedTrackId) { mutableStateOf<dYaTrack?>(null) }
            var isRefreshing by remember { mutableStateOf(false) }

            LaunchedEffect(pickedTrackId) {
                if (pickedTrackId != null) {
                    when (val result = model.getTrack(pickedTrackId)) {
                        is DataResult.Success -> pickedTrack = result.value
                        is DataResult.Failure -> showSnackbar(R.string.playlists_track_load_failed)
                    }
                }
            }

            val createTitle = stringResource(R.string.playlists_create)
            val likedTitle = stringResource(R.string.playlists_liked)
            val localDwij = stringResource(R.string.local_playlist_dwij)
            val localMediaStore = stringResource(R.string.local_playlist_media_store)
            val localM3u = stringResource(R.string.local_playlist_m3u)
            val yandexScreenItems = remember(
                yandexPlaylists,
                pickedTrackId,
                createTitle,
                likedTitle,
            ) {
                val startedNanos = SystemClock.elapsedRealtimeNanos()
                yandexItems(
                    playlists = yandexPlaylists,
                    pickedTrackId = pickedTrackId,
                    createTitle = createTitle,
                    likedTitle = likedTitle,
                ).also { items ->
                    Log.d(
                        TAG,
                        "[composeYandexItems] Собрано=${items.size}, " +
                            "время=${elapsedMillis(startedNanos)} мс",
                    )
                }
            }
            val localScreenItems = remember(
                localPlaylists,
                localDwij,
                localMediaStore,
                localM3u,
            ) {
                val startedNanos = SystemClock.elapsedRealtimeNanos()
                localItems(
                    playlists = localPlaylists,
                    dwijLabel = localDwij,
                    mediaStoreLabel = localMediaStore,
                    m3uLabel = localM3u,
                ).also { items ->
                    Log.d(
                        TAG,
                        "[composeLocalItems] Собрано=${items.size}, " +
                            "время=${elapsedMillis(startedNanos)} мс",
                    )
                }
            }
            val screenItems = when (musicSource) {
                HomeMusicSource.Yandex -> yandexScreenItems
                HomeMusicSource.Local -> localScreenItems
            }

            PlaylistGridScreen(
                title = stringResource(
                    if (isAddTrackMode()) {
                        R.string.playlists_add_track_title
                    } else {
                        R.string.playlists_title
                    },
                ),
                items = screenItems,
                selectedSource = musicSource,
                onSourceSelected = ::selectMusicSource,
                onBackClick = { findNavController().popBackStack() },
                onItemClick = { item ->
                    onPlaylistItemClick(
                        item = item,
                        source = musicSource,
                        yandexPlaylists = yandexPlaylists,
                        localPlaylists = localPlaylists,
                        pickedTrackId = pickedTrackId,
                        pickedTrack = pickedTrack,
                    )
                },
                onItemLongClick = { item ->
                    if (musicSource == HomeMusicSource.Yandex && !isAddTrackMode()) {
                        yandexPlaylists.firstOrNull { it.getdId() == item.id }
                            ?.let(::showPlaylistDeleteInfo)
                    }
                },
                loadCover = ::loadPlaylistCover,
                emptyMessage = stringResource(
                    if (musicSource == HomeMusicSource.Local) {
                        R.string.local_playlists_empty
                    } else {
                        R.string.playlists_empty_yandex
                    },
                ),
                isRefreshing = isRefreshing,
                onRefresh = {
                    if (!isRefreshing) {
                        if (musicSource == HomeMusicSource.Local) {
                            LocalLibrarySyncWorker.enqueueImmediate(
                                requireContext().applicationContext,
                            )
                        } else {
                            isRefreshing = true
                            viewLifecycleOwner.lifecycleScope.launch {
                                try {
                                    if (model.refreshPlaylists() is DataResult.Failure) {
                                        showSnackbar(R.string.playlists_refresh_failed)
                                    }
                                } finally {
                                    isRefreshing = false
                                }
                            }
                        }
                    }
                },
                modifier = Modifier,
            )
        }
    }

    /** Загружает одну обложку по запросу видимой Compose-плитки. */
    private suspend fun loadPlaylistCover(playlistId: String): ImageBitmap? {
        val playlist = model.playlists.value.firstOrNull { it.getdId() == playlistId }
            ?: return null
        if (playlist.ogImageUri.isNullOrBlank()) return null
        return try {
            withContext(Dispatchers.IO) {
                model.getCover(playlist).asImageBitmap()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(
                TAG,
                "[loadPlaylistCover] Не загрузилась обложка playlistId=$playlistId",
                error,
            )
            null
        }
    }

    /** Обрабатывает обычный режим, создание и режим добавления трека одной точкой входа. */
    private fun onPlaylistItemClick(
        item: PlaylistGridScreenItem,
        source: HomeMusicSource,
        yandexPlaylists: List<dYaPlaylist>,
        localPlaylists: List<LocalPlaylistEntity>,
        pickedTrackId: String?,
        pickedTrack: dYaTrack?,
    ) {
        if (item.isCreateAction) {
            showSnackbar(R.string.playlists_create_pending)
            return
        }
        if (source == HomeMusicSource.Local) {
            if (isAddTrackMode()) {
                showSnackbar(R.string.playlists_local_add_unavailable)
            } else {
                localPlaylists.firstOrNull { it.playlistId == item.id }?.let(::openLocalPlaylist)
            }
            return
        }

        val playlist = yandexPlaylists.firstOrNull { it.getdId() == item.id } ?: return
        if (pickedTrackId == null) {
            openYandexPlaylist(playlist)
        } else if (item.highlighted) {
            if (pickedTrack == null) {
                showSnackbar(R.string.playlists_track_load_failed)
            } else {
                showRemoveTrackDialog(playlist, pickedTrack)
            }
        } else {
            addTrackToPlaylist(playlist, pickedTrackId)
        }
    }

    /** Открывает обычный Яндекс-плейлист в существующем экране объекта. */
    private fun openYandexPlaylist(playlist: dYaPlaylist) {
        findNavController().navigate(
            R.id.action_gridPlaylistFrag_to_objectFrag,
            Bundle().apply {
                putString(TYPE, ObjectFrag.PLAYLIST)
                putString(VALUE, playlist.getdId())
            },
        )
    }

    /** Открывает содержимое локального плейлиста через уже готовый локальный маршрут. */
    private fun openLocalPlaylist(playlist: LocalPlaylistEntity) {
        findNavController().navigate(
            R.id.localLibraryFrag,
            Bundle().apply {
                putString(LocalLibraryFrag.ARG_MODE, LocalLibraryFrag.MODE_PLAYLIST)
                putString(LocalLibraryFrag.ARG_PLAYLIST_ID, playlist.playlistId)
            },
        )
    }

    /** Добавляет выбранный трек и закрывает экран после успешного результата. */
    private fun addTrackToPlaylist(playlist: dYaPlaylist, trackId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            when (model.addTrackToPlaylist(playlist, trackId)) {
                is DataResult.Success -> {
                    showSnackbar(R.string.playlists_track_added)
                    findNavController().popBackStack()
                }

                is DataResult.Failure -> showSnackbar(R.string.playlists_track_add_failed)
            }
        }
    }

    /** Подтверждает удаление выбранного трека из уже содержащего его плейлиста. */
    private fun showRemoveTrackDialog(playlist: dYaPlaylist, track: dYaTrack) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.playlists_remove_track_title)
            .setMessage(R.string.playlists_remove_track_message)
            .setPositiveButton(R.string.playlists_remove_track_confirm) { dialog, _ ->
                dialog.dismiss()
                viewLifecycleOwner.lifecycleScope.launch {
                    when (model.removeTrackFromPlaylist(playlist, track)) {
                        is DataResult.Success -> Unit
                        is DataResult.Failure -> showSnackbar(
                            R.string.playlists_track_remove_failed,
                        )
                    }
                }
            }
            .setNegativeButton(R.string.playlists_cancel) { dialog, _ -> dialog.dismiss() }
            .show()
    }

    /** Пока оставляет старый long-click безопасным: предупреждает, но ничего не удаляет. */
    private fun showPlaylistDeleteInfo(playlist: dYaPlaylist) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.playlists_delete_title)
            .setMessage("${playlist.title}\n\n${getString(R.string.playlists_delete_message)}")
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** Меняет источник и при необходимости запрашивает доступ к локальной медиатеке. */
    private fun selectMusicSource(source: HomeMusicSource) {
        if (source == selectedMusicSource.value) return
        if (source == HomeMusicSource.Yandex) {
            persistSource(source)
            return
        }
        val permissions = LocalMusicRepository.requiredPermissions()
        if (permissions.all { permission ->
                ContextCompat.checkSelfPermission(requireContext(), permission) ==
                    PackageManager.PERMISSION_GRANTED
            }
        ) {
            persistSource(source)
            LocalLibrarySyncWorker.enqueueImmediate(requireContext().applicationContext)
        } else if (!permissionRequestInFlight) {
            permissionRequestInFlight = true
            MusicSourceSelectionStore.preview(HomeMusicSource.Local)
            audioPermissionLauncher.launch(permissions)
        }
    }

    private fun persistSource(source: HomeMusicSource) {
        MusicSourceSelectionStore.select(requireContext(), source)
    }

    private fun readSavedSource(): HomeMusicSource {
        val source = MusicSourceSelectionStore.restore(requireContext())
        val resolved = if (
            source == HomeMusicSource.Local &&
            ContextCompat.checkSelfPermission(
                requireContext(),
                LocalMusicRepository.requiredAudioPermission(),
            ) != PackageManager.PERMISSION_GRANTED
        ) HomeMusicSource.Yandex else source
        if (resolved != source) {
            MusicSourceSelectionStore.select(requireContext(), resolved)
        }
        return resolved
    }

    private fun isAddTrackMode(): Boolean =
        arguments?.getString(PLAYLIST_ACTION) == ACTION_ADDTRACK

    /** Проверяет как свежий результат launcher, так и уже выданные раньше разрешения. */
    private fun areLocalPermissionsGranted(permissions: Map<String, Boolean>): Boolean =
        LocalMusicRepository.requiredPermissions().all { permission ->
            permissions[permission] == true || ContextCompat.checkSelfPermission(
                requireContext(),
                permission,
            ) == PackageManager.PERMISSION_GRANTED
        }

    private fun showSnackbar(messageRes: Int) {
        view?.let { root ->
            Snackbar.make(root, messageRes, Snackbar.LENGTH_LONG).show()
        }
    }

    /** Возвращает время после монотонной отметки для диагностических логов. */
    private fun elapsedMillis(startedNanos: Long): Long =
        (SystemClock.elapsedRealtimeNanos() - startedNanos) / 1_000_000L

    companion object {
        const val PLAYLIST_ACTION = "playlist_action"
        const val ACTION_ADDTRACK = "add_track"
        const val ACTION_DATA = "action_data"
        private const val CREATE_ITEM_ID = "playlist_create_item"
        private const val TAG = "GridPlaylistFrag"

        /** Собирает Compose-модели Яндекс-плейлистов, включая плитку создания. */
        private fun yandexItems(
            playlists: List<dYaPlaylist>,
            pickedTrackId: String?,
            createTitle: String,
            likedTitle: String,
        ): List<PlaylistGridScreenItem> = buildList {
            if (pickedTrackId == null) {
                add(
                    PlaylistGridScreenItem(
                        id = CREATE_ITEM_ID,
                        title = createTitle,
                        isCreateAction = true,
                    ),
                )
            }
            playlists
                .filterNot { pickedTrackId != null && it.kind == KIND_LIKED }
                .forEach { playlist ->
                    val trackCount = playlist.trackCount
                    add(
                        PlaylistGridScreenItem(
                            id = playlist.getdId(),
                            title = if (playlist.kind == KIND_LIKED) likedTitle else playlist.title,
                            details = "$trackCount трек${getNumericPostfix(trackCount)}\n" +
                                formatDuration(playlist.durationMs ?: 0),
                            shouldLoadCover = !playlist.ogImageUri.isNullOrBlank(),
                            highlighted = pickedTrackId != null &&
                                playlist.tracks.any { it.trackId == pickedTrackId },
                        ),
                    )
                }
        }

        /** Преобразует локальные записи в те же плитки без сетевой обложки. */
        private fun localItems(
            playlists: List<LocalPlaylistEntity>,
            dwijLabel: String,
            mediaStoreLabel: String,
            m3uLabel: String,
        ): List<PlaylistGridScreenItem> = playlists.map { playlist ->
            PlaylistGridScreenItem(
                id = playlist.playlistId,
                title = playlist.name,
                details = when (playlist.origin) {
                    LocalPlaylistOrigin.DWIJ.name -> dwijLabel
                    LocalPlaylistOrigin.MEDIA_STORE.name -> mediaStoreLabel
                    else -> m3uLabel
                },
            )
        }
    }
}
