package com.yellastrodev.dwij.ui.theme

import androidx.compose.ui.graphics.Color

/** Единая палитра shared-интерфейса без зависимости от платформенных ресурсов. */
object DwijColors {

    // Общая палитра
    const val BackgroundArgb = 0xFF03040F

    val Background = Color(BackgroundArgb)
    val White = Color.White
    val Black = Color.Black
    val Transparent = Color.Transparent
    val Disabled = Color.DarkGray
    val Pink = Color(0xFFFF00BF)
    val Cyan = Color(0xFF00BBEB)
    val CyanBright = Color(0xFF00DFFF)
    val SecondaryText = Color(0xFFA7AABC)
    val MutedText = Color(0xFFAAAFC0)
    val ListSecondaryText = Color(0xFF969BAD)
    val LoadingPlaceholder = Color(0xFF202635)

    // Search
    val SearchEntityMeta = Color(0xFF8F96A9)
    val SearchEntityAvatarBackground = Color(0xFF12182A)
    val SearchEntityAvatarInitial = Color(0x99FF4BA7)

    // Create playlist
    val CreateDialogBackground = Color(0xFF050711)
    val CreateDialogLabel = Color(0xFFD7DAE7)
    val CreateDialogFieldBackground = Color(0xD9070A14)
    val CreateDialogHint = Color(0xFF73798B)
    val CreateDialogSecondary = Color(0xFF9297A8)
    val CreateDialogToggleBackground = Color(0xFF242938)
    val CreateDialogToggleKnob = Color(0xFF8F96A9)
    val CreateDialogButtonBackground = Color(0xD90A0D18)

    // Settings
    val SettingsCardBackground = Color(0xFF080B15)
    val SettingsActionBackground = Color(0xD9070911)

    // Object screen
    val ObjectTitleText = Color(0xFFD4D6E0)
    val ObjectHeaderOverlay = Color(0xCC0D1020)
    val ObjectActionBackground = Color(0xB30A0714)
    val ObjectFooterBackground = Color(0xA6080B16)

    // Multiple sources
    val MultiSourceBackground = Color(0xFF080A16)

    // Full player
    val PlayerSnackbarBackground = Color(0xF21B1022)
    val PlayerCoverPlaceholderBackground = Color(0xE00A0C18)
    val PlayerArtistText = Color(0xFFD0D2DD)
    val PlayerMainControlBackground = Color(0xD90A0714)
    val PlayerPlaylistPurple = Color(0xFF9D4DFF)
    val PlayerPlaylistOrange = Color(0xFFFF8A00)

    // Tracks and local library
    val TrackCoverBackground = Color(0xFF101522)
    val TrackUnavailable = Color(0xFFFFD54A)
    val TrackGlitchCyan = Color(0xFF00C8F0)
    val TrackGlitchPink = Color(0xFFFF1694)
    val LocalLibraryProgress = Color(0xFF343846)
    val LocalLibraryDivider = Color(0xFF282B35)

    // Playlists
    val PlaylistDetailsText = Color(0xFFE9F8FF)
    val PlaylistTitleBackground = Color(0xB30A0714)
    val PlaylistHighlightedTitleBackground = Color(0xB31A0B22)
    val PlaylistDetailsBackground = Color(0x99001622)

    // Home
    val HomeNavigationSelected = Color(0xFFFF178F)
    val HomeNavigationUnselected = Color(0xFF9095A7)
    val HomeTimeText = Color(0xFFE7E5ED)
    val HomeProgressText = Color(0xFFCDD0DC)
    val HomeProgressBackground = Color(0xFF303543)

    val HomeRadialRoad = Color(0xFFFF2D82)
    val HomeRadialFocus = Color(0xFF00BEFF)
    val HomeRadialCalm = Color(0xFFB737FF)
    val HomeRadialFavorite = Color(0xFFFF2D96)
    val HomeRadialRadio = Color(0xFF00E6DC)
    val HomeRadialParty = Color(0xFFFF9100)
}
