package com.yellastrodev.dwij.ui.playlist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yellastrodev.dwij.HomeMusicSource
import com.yellastrodev.dwij.MusicSourceSelectionStore
import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.entities.LocalPlaylistEntity
import com.yellastrodev.dwij.data.entities.LocalPlaylistOrigin
import com.yellastrodev.dwij.data.entities.LocalPlaylistSummary
import com.yellastrodev.dwij.data.entities.dYaLikeTracklist.Companion.KIND_LIKED
import com.yellastrodev.dwij.data.entities.dYaPlaylist
import com.yellastrodev.dwij.data.entities.dYaTrack
import com.yellastrodev.dwij.data.repo.CoverRepository
import com.yellastrodev.dwij.data.repo.LocalMusicRepository
import com.yellastrodev.dwij.data.repo.PlaylistRepository
import com.yellastrodev.dwij.data.repo.TrackRepository
import com.yellastrodev.dwij.models.GridPlaylistModel
import com.yellastrodev.dwij.ui.toImageBitmapOrNull
import com.yellastrodev.dwij.utils.DurationFormat.Companion.formatDuration
import com.yellastrodev.dwij.utils.LangFormats.Companion.getNumericPostfix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Все неплатформенные зависимости экрана. */
data class PlaylistGridDependencies(
    val playlistRepository: PlaylistRepository,
    val trackRepository: TrackRepository,
    val coverRepository: CoverRepository,
    val localMusicRepository: LocalMusicRepository,
    val musicSourceSelectionStore: MusicSourceSelectionStore,
)

/** Тексты экрана плейлистов. */
@Immutable
data class PlaylistGridTexts(
    val title: String,
    val addTrackTitle: String,
    val create: String,
    val liked: String,
    val localDwij: String,
    val localMediaStore: String,
    val localM3u: String,
    val localEmpty: String,
    val yandexEmpty: String,
)

enum class PlaylistGridArtwork {
    Create,
    Liked,
    PlayerFallback,
}

@Immutable
data class PlaylistGridScreenItem(
    override val id: String,
    val title: String,
    val details: String = "",
    override val shouldLoadCover: Boolean = false,
    val fallbackArtwork: PlaylistGridArtwork? = null,
    val artwork: PlaylistGridArtwork? = null,
    val highlighted: Boolean = false,
    override val isCreateAction: Boolean = false,
) : PlaylistGridEntry

enum class PlaylistGridMessage {
    TrackLoadFailed,
    CreateFailed,
    LocalAddUnavailable,
    TrackAddFailed,
    TrackRemoveFailed,
    RefreshFailed,
}

@Immutable
data class PlaylistGridMessageEvent(
    val id: Long,
    val message: PlaylistGridMessage,
)

sealed interface PlaylistGridDialogState {

    @Immutable
    data class Create(
        val source: HomeMusicSource,
        val isCreating: Boolean,
    ) : PlaylistGridDialogState

    @Immutable
    data class RemoveTrack(
        val playlistTitle: String,
    ) : PlaylistGridDialogState

    @Immutable
    data class DeleteInfo(
        val playlistTitle: String,
    ) : PlaylistGridDialogState
}

@Immutable
data class PlaylistGridRouteState(
    val title: String,
    val items: List<PlaylistGridScreenItem>,
    val selectedSource: HomeMusicSource,
    val showSourceSelector: Boolean,
    val emptyMessage: String,
    val isLoading: Boolean,
    val isRefreshing: Boolean,
    val dialog: PlaylistGridDialogState?,
    val message: PlaylistGridMessageEvent?,
)

/**
 * Действия, которые presentation-слой возвращает владельцу экрана.
 *
 * Решения по этим действиям принимает shared-route.
 */
class PlaylistGridRouteActions internal constructor(
    val onSourceSelected: (HomeMusicSource) -> Unit,
    val onBackClick: () -> Unit,
    val onItemClick: (PlaylistGridScreenItem) -> Unit,
    val onItemLongClick: (PlaylistGridScreenItem) -> Unit,
    val loadCover: suspend (String) -> ImageBitmap?,
    val onRefresh: () -> Unit,
    val onDialogDismiss: () -> Unit,
    val onCreatePlaylist: (
        title: String,
        isPublic: Boolean,
    ) -> Unit,
    val onRemoveTrackConfirm: () -> Unit,
    val onDeleteInfoConfirm: () -> Unit,
)

/**
 * Shared-владелец экрана плейлистов.
 *
 * Здесь находятся выбор источника, состояния диалогов, операции с плейлистами,
 * обновление данных и обработка пользовательских действий.
 *
 * Навигационные действия передаются отдельными callback-функциями, поэтому
 * платформенный адаптер больше не зависит от конкретной системы навигации.
 */
@Composable
fun PlaylistGridRoute(
    trackToAdd: String? = null,
    dependencies: PlaylistGridDependencies,
    platform: PlaylistGridPlatform,
    onOpenYandexPlaylist: (playlistId: String) -> Unit,
    onOpenLocalPlaylist: (playlistId: String) -> Unit,
    onCloseScreen: () -> Unit,
    texts: PlaylistGridTexts,
    content: @Composable (
        state: PlaylistGridRouteState,
        actions: PlaylistGridRouteActions,
        modifier: Modifier,
    ) -> Unit,
    modifier: Modifier = Modifier,
) {
    val coroutineScope = rememberCoroutineScope()

    val model = viewModel(
        key = "playlist-grid-${trackToAdd ?: "browse"}",
    ) {
        GridPlaylistModel(
            playlistRepo = dependencies.playlistRepository,
            trackRepo = dependencies.trackRepository,
            coverRepo = dependencies.coverRepository,
        )
    }

    val yandexPlaylists by model.playlists.collectAsState()
    val yandexInitialLoadComplete by
    model.initialLoadComplete.collectAsState()

    val localPlaylistSummarySnapshot by
    dependencies.localMusicRepository
        .playlistSummaries
        .collectAsState(initial = null)

    val isLocalSynchronizing by
    dependencies.localMusicRepository
        .isSynchronizing
        .collectAsState()

    val musicSource by
    dependencies.musicSourceSelectionStore
        .selectedSource
        .collectAsState()

    val localPlaylistSummaries =
        localPlaylistSummarySnapshot.orEmpty()

    val localPlaylists = remember(localPlaylistSummaries) {
        localPlaylistSummaries.map(
            LocalPlaylistSummary::playlist,
        )
    }

    val isAddTrackMode = trackToAdd != null

    val screenSource =
        if (isAddTrackMode) {
            HomeMusicSource.Yandex
        } else {
            musicSource
        }

    var permissionRequestInFlight by remember {
        mutableStateOf(false)
    }

    var pickedTrack by remember(trackToAdd) {
        mutableStateOf<dYaTrack?>(null)
    }

    var isRefreshing by remember {
        mutableStateOf(false)
    }

    var createDialogSource by remember {
        mutableStateOf<HomeMusicSource?>(null)
    }

    var isCreatingPlaylist by remember {
        mutableStateOf(false)
    }

    var removeTrackRequest by remember {
        mutableStateOf<Pair<dYaPlaylist, dYaTrack>?>(null)
    }

    var deleteInfoPlaylist by remember {
        mutableStateOf<dYaPlaylist?>(null)
    }

    var messageSequence by remember {
        mutableLongStateOf(0L)
    }

    var message by remember {
        mutableStateOf<PlaylistGridMessageEvent?>(null)
    }

    fun showMessage(value: PlaylistGridMessage) {
        messageSequence += 1L

        message = PlaylistGridMessageEvent(
            id = messageSequence,
            message = value,
        )
    }

    fun selectMusicSource(source: HomeMusicSource) {
        if (
            source == musicSource ||
            permissionRequestInFlight
        ) {
            return
        }

        if (source == HomeMusicSource.Yandex) {
            dependencies.musicSourceSelectionStore.select(source)
            return
        }

        if (platform.hasLocalMusicAccess()) {
            dependencies.musicSourceSelectionStore.select(source)
            platform.startLocalLibrarySync()
            return
        }

        permissionRequestInFlight = true

        dependencies.musicSourceSelectionStore.preview(
            HomeMusicSource.Local,
        )

        coroutineScope.launch {
            val granted = try {
                platform.requestLocalMusicAccess()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                false
            } finally {
                permissionRequestInFlight = false
            }

            if (granted) {
                dependencies.musicSourceSelectionStore.select(
                    HomeMusicSource.Local,
                )

                platform.startLocalLibrarySync()
            } else {
                dependencies.musicSourceSelectionStore.select(
                    HomeMusicSource.Yandex,
                )
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(
        dependencies.musicSourceSelectionStore,
        platform,
    ) {
        val restored =
            dependencies.musicSourceSelectionStore.restore()

        if (
            restored == HomeMusicSource.Local &&
            !platform.hasLocalMusicAccess()
        ) {
            dependencies.musicSourceSelectionStore.select(
                HomeMusicSource.Yandex,
            )
        }
    }

    androidx.compose.runtime.LaunchedEffect(trackToAdd) {
        if (trackToAdd != null) {
            when (val result = model.getTrack(trackToAdd)) {
                is DataResult.Success -> {
                    pickedTrack = result.value
                }

                is DataResult.Failure -> {
                    showMessage(
                        PlaylistGridMessage.TrackLoadFailed,
                    )
                }
            }
        }
    }

    val yandexScreenItems = remember(
        yandexPlaylists,
        trackToAdd,
        texts.create,
        texts.liked,
    ) {
        buildYandexItems(
            playlists = yandexPlaylists,
            pickedTrackId = trackToAdd,
            showCreateAction = !isAddTrackMode,
            createTitle = texts.create,
            likedTitle = texts.liked,
        )
    }

    val localScreenItems = remember(
        localPlaylistSummaries,
        texts.create,
        texts.localDwij,
        texts.localMediaStore,
        texts.localM3u,
    ) {
        buildLocalItems(
            playlists = localPlaylistSummaries,
            showCreateAction = !isAddTrackMode,
            createTitle = texts.create,
            dwijLabel = texts.localDwij,
            mediaStoreLabel = texts.localMediaStore,
            m3uLabel = texts.localM3u,
        )
    }

    val screenItems = when (screenSource) {
        HomeMusicSource.Yandex -> yandexScreenItems
        HomeMusicSource.Local -> localScreenItems
    }

    fun createPlaylist(
        source: HomeMusicSource,
        title: String,
        isPublic: Boolean,
    ) {
        coroutineScope.launch {
            try {
                when (source) {
                    HomeMusicSource.Yandex -> {
                        when (
                            val result =
                                model.createPlaylist(
                                    title = title,
                                    isPublic = isPublic,
                                )
                        ) {
                            is DataResult.Success -> {
                                isCreatingPlaylist = false
                                createDialogSource = null

                                onOpenYandexPlaylist(
                                    result.value.getdId(),
                                )
                            }

                            is DataResult.Failure -> {
                                isCreatingPlaylist = false

                                showMessage(
                                    PlaylistGridMessage.CreateFailed,
                                )
                            }
                        }
                    }

                    HomeMusicSource.Local -> {
                        when (
                            val result =
                                withContext(Dispatchers.IO) {
                                    dependencies
                                        .localMusicRepository
                                        .saveDwijPlaylist(
                                            name = title,
                                            trackIds = emptyList(),
                                        )
                                }
                        ) {
                            is DataResult.Success -> {
                                isCreatingPlaylist = false
                                createDialogSource = null

                                onOpenLocalPlaylist(
                                    result.value.playlistId,
                                )
                            }

                            is DataResult.Failure -> {
                                isCreatingPlaylist = false

                                showMessage(
                                    PlaylistGridMessage.CreateFailed,
                                )
                            }
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                isCreatingPlaylist = false

                showMessage(
                    PlaylistGridMessage.CreateFailed,
                )
            }
        }
    }

    val dialogState = when {
        createDialogSource != null -> {
            PlaylistGridDialogState.Create(
                source = requireNotNull(createDialogSource),
                isCreating = isCreatingPlaylist,
            )
        }

        removeTrackRequest != null -> {
            PlaylistGridDialogState.RemoveTrack(
                playlistTitle =
                    requireNotNull(removeTrackRequest).first.title,
            )
        }

        deleteInfoPlaylist != null -> {
            PlaylistGridDialogState.DeleteInfo(
                playlistTitle =
                    requireNotNull(deleteInfoPlaylist).title,
            )
        }

        else -> null
    }

    val state = PlaylistGridRouteState(
        title =
            if (isAddTrackMode) {
                texts.addTrackTitle
            } else {
                texts.title
            },
        items = screenItems,
        selectedSource = screenSource,
        showSourceSelector = !isAddTrackMode,
        emptyMessage =
            if (screenSource == HomeMusicSource.Local) {
                texts.localEmpty
            } else {
                texts.yandexEmpty
            },
        isLoading =
            when (screenSource) {
                HomeMusicSource.Yandex -> {
                    !yandexInitialLoadComplete
                }

                HomeMusicSource.Local -> {
                    localPlaylistSummarySnapshot == null ||
                            (
                                    localPlaylistSummarySnapshot
                                        .isNullOrEmpty() &&
                                            isLocalSynchronizing
                                    )
                }
            },
        isRefreshing =
            isRefreshing ||
                    (
                            screenSource == HomeMusicSource.Local &&
                                    isLocalSynchronizing
                            ),
        dialog = dialogState,
        message = message,
    )

    val actions = PlaylistGridRouteActions(
        onSourceSelected = ::selectMusicSource,
        onBackClick = onCloseScreen,
        onItemClick = { item ->
            when {
                item.isCreateAction -> {
                    createDialogSource = screenSource
                }

                screenSource == HomeMusicSource.Local -> {
                    if (isAddTrackMode) {
                        showMessage(
                            PlaylistGridMessage.LocalAddUnavailable,
                        )
                    } else {
                        localPlaylists
                            .firstOrNull { playlist ->
                                playlist.playlistId == item.id
                            }
                            ?.let(LocalPlaylistEntity::playlistId)
                            ?.let(onOpenLocalPlaylist)
                    }
                }

                else -> {
                    val playlist =
                        yandexPlaylists.firstOrNull {
                            it.getdId() == item.id
                        }

                    when {
                        playlist == null -> Unit

                        trackToAdd == null -> {
                            onOpenYandexPlaylist(
                                playlist.getdId(),
                            )
                        }

                        item.highlighted -> {
                            val track = pickedTrack

                            if (track == null) {
                                showMessage(
                                    PlaylistGridMessage
                                        .TrackLoadFailed,
                                )
                            } else {
                                removeTrackRequest =
                                    playlist to track
                            }
                        }

                        else -> {
                            coroutineScope.launch {
                                when (
                                    model.addTrackToPlaylist(
                                        playlist = playlist,
                                        trackId = trackToAdd,
                                    )
                                ) {
                                    is DataResult.Success -> {
                                        onCloseScreen()
                                    }

                                    is DataResult.Failure -> {
                                        showMessage(
                                            PlaylistGridMessage
                                                .TrackAddFailed,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        onItemLongClick = { item ->
            if (
                screenSource == HomeMusicSource.Yandex &&
                !isAddTrackMode
            ) {
                deleteInfoPlaylist =
                    yandexPlaylists.firstOrNull {
                        it.getdId() == item.id
                    }
            }
        },
        loadCover = { playlistId ->
            val playlist =
                yandexPlaylists.firstOrNull {
                    it.getdId() == playlistId
                }

            if (
                playlist == null ||
                playlist.ogImageUri.isNullOrBlank()
            ) {
                null
            } else {
                try {
                    withContext(Dispatchers.IO) {
                        model
                            .getCover(playlist)
                            ?.toImageBitmapOrNull()
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
            }
        },
        onRefresh = {
            if (!isRefreshing) {
                if (screenSource == HomeMusicSource.Local) {
                    platform.startLocalLibrarySync()
                } else {
                    isRefreshing = true

                    coroutineScope.launch {
                        try {
                            if (
                                model.refreshPlaylists()
                                        is DataResult.Failure
                            ) {
                                showMessage(
                                    PlaylistGridMessage
                                        .RefreshFailed,
                                )
                            }
                        } finally {
                            isRefreshing = false
                        }
                    }
                }
            }
        },
        onDialogDismiss = {
            if (!isCreatingPlaylist) {
                createDialogSource = null
                removeTrackRequest = null
                deleteInfoPlaylist = null
            }
        },
        onCreatePlaylist = { title, isPublic ->
            val source = createDialogSource

            if (
                source != null &&
                !isCreatingPlaylist
            ) {
                isCreatingPlaylist = true

                createPlaylist(
                    source = source,
                    title = title,
                    isPublic = isPublic,
                )
            }
        },
        onRemoveTrackConfirm = {
            val request = removeTrackRequest
            removeTrackRequest = null

            if (request != null) {
                coroutineScope.launch {
                    if (
                        model.removeTrackFromPlaylist(
                            playlist = request.first,
                            track = request.second,
                        ) is DataResult.Failure
                    ) {
                        showMessage(
                            PlaylistGridMessage
                                .TrackRemoveFailed,
                        )
                    }
                }
            }
        },
        onDeleteInfoConfirm = {
            deleteInfoPlaylist = null
        },
    )

    content(
        state,
        actions,
        modifier,
    )
}

private fun buildYandexItems(
    playlists: List<dYaPlaylist>,
    pickedTrackId: String?,
    showCreateAction: Boolean,
    createTitle: String,
    likedTitle: String,
): List<PlaylistGridScreenItem> = buildList {
    if (showCreateAction) {
        add(
            PlaylistGridScreenItem(
                id = CREATE_ITEM_ID,
                title = createTitle,
                artwork = PlaylistGridArtwork.Create,
                isCreateAction = true,
            ),
        )
    }

    playlists
        .filterNot {
            pickedTrackId != null &&
                    it.kind == KIND_LIKED
        }
        .forEach { playlist ->
            val trackCount = playlist.trackCount
            val isLikedPlaylist =
                playlist.kind == KIND_LIKED

            add(
                PlaylistGridScreenItem(
                    id = playlist.getdId(),
                    title =
                        if (isLikedPlaylist) {
                            likedTitle
                        } else {
                            playlist.title
                        },
                    details =
                        "$trackCount трек" +
                                getNumericPostfix(trackCount) +
                                "\n" +
                                formatDuration(
                                    playlist.durationMs ?: 0,
                                ),
                    shouldLoadCover =
                        !isLikedPlaylist &&
                                !playlist
                                    .ogImageUri
                                    .isNullOrBlank(),
                    fallbackArtwork =
                        if (isLikedPlaylist) {
                            null
                        } else {
                            PlaylistGridArtwork.PlayerFallback
                        },
                    artwork =
                        if (isLikedPlaylist) {
                            PlaylistGridArtwork.Liked
                        } else {
                            null
                        },
                    highlighted =
                        pickedTrackId != null &&
                                playlist.tracks.any {
                                    it.trackId == pickedTrackId
                                },
                ),
            )
        }
}

private fun buildLocalItems(
    playlists: List<LocalPlaylistSummary>,
    showCreateAction: Boolean,
    createTitle: String,
    dwijLabel: String,
    mediaStoreLabel: String,
    m3uLabel: String,
): List<PlaylistGridScreenItem> = buildList {
    if (showCreateAction) {
        add(
            PlaylistGridScreenItem(
                id = CREATE_ITEM_ID,
                title = createTitle,
                artwork = PlaylistGridArtwork.Create,
                isCreateAction = true,
            ),
        )
    }

    playlists.forEach { summary ->
        val playlist = summary.playlist

        val formatLabel =
            when (playlist.origin) {
                LocalPlaylistOrigin.DWIJ.name -> {
                    dwijLabel
                }

                LocalPlaylistOrigin.MEDIA_STORE.name -> {
                    mediaStoreLabel
                }

                else -> {
                    m3uLabel
                }
            }

        val duration = formatDuration(
            summary.durationMs
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt(),
        )

        add(
            PlaylistGridScreenItem(
                id = playlist.playlistId,
                title = playlist.name,
                fallbackArtwork =
                    PlaylistGridArtwork.PlayerFallback,
                details =
                    "${summary.trackCount} трек" +
                            getNumericPostfix(summary.trackCount) +
                            " · $duration\n$formatLabel",
            ),
        )
    }
}

private const val CREATE_ITEM_ID =
    "playlist_create_item"