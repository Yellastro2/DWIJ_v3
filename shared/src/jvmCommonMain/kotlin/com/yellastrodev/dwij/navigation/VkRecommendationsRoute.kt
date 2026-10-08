package com.yellastrodev.dwij.navigation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.yellastrodev.dwij.HomeMusicSource
import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.repo.VK_RECOMMENDATION_PREFIX
import com.yellastrodev.dwij.data.repo.VkRecommendationCard
import com.yellastrodev.dwij.di.DwijComponent
import com.yellastrodev.dwij.resources.*
import com.yellastrodev.dwij.ui.playlist.*
import com.yellastrodev.dwij.ui.theme.DwijColors
import com.yellastrodev.dwij.ui.toImageBitmapOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** Четыре персональные рекомендации: метаданные обложек обновляются отдельно от составов треклистов. */
@Composable
internal fun VkRecommendationsRoute(
    component: DwijComponent,
    onBackClick: () -> Unit,
    onOpenTracks: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val repository = component.vkMusicRepository
    val session by repository.sessionRevision.collectAsState()
    val authorized by repository.authorized.collectAsState()
    var cards by remember(session) { mutableStateOf<List<VkRecommendationCard>>(emptyList()) }
    var loading by remember(session) { mutableStateOf(false) }
    var error by remember(session) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    /** Получает только метаданные трёх персональных подборок; треки до нажатия не запрашиваются. */
    suspend fun loadCatalog() {
        loading = true
        error = null
        try {
            when (val result = repository.recommendationCards()) {
                is DataResult.Success -> cards = result.value
                is DataResult.Failure -> error = result.error.vkPlaylistMessage()
            }
        } finally { loading = false }
    }

    LaunchedEffect(session, authorized) {
        if (authorized) loadCatalog()
    }
    val rootCards = listOf(
        VkRecommendationCard("${VK_RECOMMENDATION_PREFIX}recommendations", stringResource(Res.string.home_recommendations)),
        VkRecommendationCard("${VK_RECOMMENDATION_PREFIX}generated:-21", stringResource(Res.string.vk_recommendation_for_you)),
        VkRecommendationCard("${VK_RECOMMENDATION_PREFIX}generated:-24", stringResource(Res.string.vk_recommendation_discoveries)),
        VkRecommendationCard("${VK_RECOMMENDATION_PREFIX}generated:-23", stringResource(Res.string.vk_recommendation_new)),
    )
    val items = rootCards.map { fallback ->
        VkRecommendationGridEntry(fallback.copy(playlist = cards.firstOrNull { it.id == fallback.id }?.playlist))
    }
    key(session) {
    PlaylistGridScreen(
        title = stringResource(Res.string.home_recommendations),
        items = items, selectedSource = HomeMusicSource.Vk, onSourceSelected = {},
        onBackClick = onBackClick,
        onItemClick = { onOpenTracks(it.id) },
        onItemLongClick = {},
        emptyMessage = error ?: stringResource(Res.string.track_list_empty),
        loadingMessage = stringResource(Res.string.list_loading_placeholder),
        sourceSelector = { _, _ -> }, showSourceSelector = false,
        itemContent = { item, cover, onClick, _, itemModifier ->
            PlaylistGridItem(
                item = PlaylistGridItemUiModel(title = item.card.title),
                coverState = cover, onClick = onClick, modifier = itemModifier,
                fallbackContent = {
                    Box(Modifier.fillMaxSize().background(DwijColors.Background), contentAlignment = Alignment.Center) {
                        val texture = when (item.card.playlist?.id ?: item.id.substringAfterLast(':').toLongOrNull()) {
                            -21L -> Res.drawable.bg_calm_texture
                            -24L -> Res.drawable.bg_drive_texture
                            -23L -> Res.drawable.bg_party_texture
                            else -> Res.drawable.bg_focus_texture
                        }
                        Image(painterResource(texture), contentDescription = null,
                            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        Image(painterResource(Res.drawable.ic_player_play_v2), contentDescription = null,
                            modifier = Modifier.size(54.dp))
                    }
                },
            )
        },
        loadCover = { id -> withContext(Dispatchers.IO) {
            items.firstOrNull { it.id == id }?.card?.playlist?.let {
                component.coverRepository.getVkPlaylistCover(it)?.toImageBitmapOrNull()
            }
        } },
        isLoading = false, isRefreshing = loading,
        onRefresh = { if (authorized && !loading) scope.launch { loadCatalog() } },
        modifier = modifier,
    )
    }
}

/** Адаптирует метаданные рекомендации к той же плитке, что используется для волн Яндекс Музыки. */
private data class VkRecommendationGridEntry(val card: VkRecommendationCard) : PlaylistGridEntry {
    override val id: String get() = card.id
    override val isCreateAction = false
    override val shouldLoadCover: Boolean get() = card.playlist?.coverUrl != null
}
