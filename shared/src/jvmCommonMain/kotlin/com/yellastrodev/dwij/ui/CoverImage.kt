package com.yellastrodev.dwij.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.decodeToImageBitmap
import com.yellastrodev.dwij.data.repo.CoverData

fun CoverData.toImageBitmapOrNull(): ImageBitmap? {
    return runCatching {
        bytes.decodeToImageBitmap()
    }.getOrNull()
}