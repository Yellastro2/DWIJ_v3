package com.yellastrodev.dwij

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Временный Compose-экран поиска: поле принимает текст локально, а остальные
 * данные служат визуальными заглушками до подключения поискового репозитория.
 */
@Composable
fun SearchPlaceholderScreen(
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val recentQueries = listOf(
        stringResource(R.string.search_recent_query_city),
        stringResource(R.string.search_recent_query_road),
        stringResource(R.string.search_recent_query_synthwave),
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 26.dp, bottom = 24.dp),
    ) {
        TextHeader()
        SearchInput(
            query = query,
            onQueryChange = { query = it },
        )

        SearchSectionTitle(
            text = stringResource(R.string.search_recent_title),
            modifier = Modifier.padding(top = 24.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            recentQueries.forEachIndexed { index, recentQuery ->
                SearchQueryChip(
                    text = recentQuery,
                    onClick = { query = recentQuery },
                )
                if (index != recentQueries.lastIndex) {
                    Spacer(modifier = Modifier.width(8.dp))
                }
            }
        }

        SearchSectionTitle(
            text = stringResource(R.string.search_categories_title),
            modifier = Modifier.padding(top = 10.dp),
        )
        SearchCategoryGrid()

        SearchSectionTitle(
            text = stringResource(R.string.search_suggestions_title),
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
        )
        SearchResultStub(
            textureRes = R.drawable.bg_drive_texture,
            title = stringResource(R.string.search_result_first_title),
            subtitle = stringResource(R.string.search_result_first_subtitle),
        )
        SearchResultStub(
            textureRes = R.drawable.bg_focus_texture,
            title = stringResource(R.string.search_result_second_title),
            subtitle = stringResource(R.string.search_result_second_subtitle),
        )
        SearchResultStub(
            textureRes = R.drawable.bg_calm_texture,
            title = stringResource(R.string.search_result_third_title),
            subtitle = stringResource(R.string.search_result_third_subtitle),
        )
    }
}

@Composable
private fun TextHeader() {
    Column(modifier = Modifier.padding(horizontal = 18.dp)) {
        androidx.compose.material3.Text(
            text = stringResource(R.string.search_title),
            color = Color.White,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
        )
        androidx.compose.material3.Text(
            text = stringResource(R.string.search_subtitle),
            color = Color(0xFF9298AC),
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

@Composable
private fun SearchInput(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 18.dp)
            .height(56.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xE60A0E1B))
            .border(
                width = 1.dp,
                color = Color(0x99FF178F),
                shape = RoundedCornerShape(18.dp),
            ),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_home_nav_search),
            contentDescription = null,
            colorFilter = ColorFilter.tint(Color(0xFFFF178F)),
            modifier = Modifier
                .padding(start = 16.dp)
                .size(25.dp),
        )
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            cursorBrush = SolidColor(Color(0xFFFF178F)),
            textStyle = TextStyle(
                color = Color.White,
                fontSize = 15.sp,
            ),
            decorationBox = { innerTextField ->
                Box {
                    if (query.isEmpty()) {
                        androidx.compose.material3.Text(
                            text = stringResource(R.string.search_hint),
                            color = Color(0xFF777E92),
                            fontSize = 15.sp,
                        )
                    }
                    innerTextField()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 52.dp, end = 16.dp),
        )
    }
}

@Composable
private fun SearchSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.Text(
        text = text,
        color = Color.White,
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(horizontal = 18.dp),
    )
}

@Composable
private fun SearchQueryChip(
    text: String,
    onClick: () -> Unit,
) {
    androidx.compose.material3.Text(
        text = text,
        color = Color(0xFFD8DAE4),
        fontSize = 13.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF111727))
            .border(1.dp, Color(0x554E8BFF), RoundedCornerShape(14.dp))
            .clickable(
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

@Composable
private fun SearchCategoryGrid() {
    val categories = listOf(
        SearchCategory(
            title = stringResource(R.string.search_category_new_releases),
            textureRes = R.drawable.bg_drive_texture,
            frameRes = R.drawable.dvizh_drive_glitch_frame_contour,
        ),
        SearchCategory(
            title = stringResource(R.string.search_category_electronic),
            textureRes = R.drawable.bg_focus_texture,
            frameRes = R.drawable.dvizh_focus_glitch_frame_contour,
        ),
        SearchCategory(
            title = stringResource(R.string.search_category_rock),
            textureRes = R.drawable.bg_party_texture,
            frameRes = R.drawable.dvizh_orange_glitch_frame_contour,
        ),
        SearchCategory(
            title = stringResource(R.string.search_category_calm),
            textureRes = R.drawable.bg_calm_texture,
            frameRes = R.drawable.dvizh_calm_glitch_frame_contour,
        ),
    )

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp),
        userScrollEnabled = false,
        modifier = Modifier
            .fillMaxWidth()
            .height(216.dp),
    ) {
        items(categories.size) { index ->
            val category = categories[index]
            Box(
                modifier = Modifier
                    .height(100.dp)
                    .padding(5.dp),
            ) {
                Image(
                    painter = painterResource(category.textureRes),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize(),
                )
                Image(
                    painter = painterResource(category.frameRes),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize(),
                )
                androidx.compose.material3.Text(
                    text = category.title,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 13.dp, top = 12.dp, end = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun SearchResultStub(
    @DrawableRes textureRes: Int,
    title: String,
    subtitle: String,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .padding(horizontal = 14.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xB30A0F1C))
            .border(1.dp, Color(0x44366CFF), RoundedCornerShape(14.dp)),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 6.dp)
                .size(50.dp),
        ) {
            Image(
                painter = painterResource(textureRes),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Image(
                painter = painterResource(R.drawable.dvizh_album_thumb_glitch_frame_contour),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth()
                .padding(start = 66.dp, end = 48.dp),
        ) {
            androidx.compose.material3.Text(
                text = title,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            androidx.compose.material3.Text(
                text = subtitle,
                color = Color(0xFF8F96A9),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Image(
            painter = painterResource(R.drawable.ic_home_player_play),
            contentDescription = null,
            colorFilter = ColorFilter.tint(Color(0xFFFF178F)),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 13.dp)
                .size(24.dp),
        )
    }
}

private data class SearchCategory(
    val title: String,
    @DrawableRes val textureRes: Int,
    @DrawableRes val frameRes: Int,
)

@Preview(widthDp = 360, heightDp = 720)
@Composable
private fun SearchPlaceholderScreenPreview() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(R.color.background)),
    ) {
        SearchPlaceholderScreen()
    }
}
