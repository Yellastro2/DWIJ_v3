package com.yellastrodev.dwij.activities

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Оставляет фон Activity от края до края, но защищает содержимое от системных
 * панелей и вырезов экрана. Исходные padding корневой View сохраняются.
 */
internal fun AppCompatActivity.applySystemBarInsets(
    rootView: View,
    useDarkSystemBarIcons: Boolean,
) {
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = useDarkSystemBarIcons
        isAppearanceLightNavigationBars = useDarkSystemBarIcons
    }

    val initialPaddingLeft = rootView.paddingLeft
    val initialPaddingTop = rootView.paddingTop
    val initialPaddingRight = rootView.paddingRight
    val initialPaddingBottom = rootView.paddingBottom

    ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
        val safeInsets = windowInsets.getInsets(
            WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout(),
        )
        view.updatePadding(
            left = initialPaddingLeft + safeInsets.left,
            top = initialPaddingTop + safeInsets.top,
            right = initialPaddingRight + safeInsets.right,
            bottom = initialPaddingBottom + safeInsets.bottom,
        )
        WindowInsetsCompat.CONSUMED
    }
    ViewCompat.requestApplyInsets(rootView)
}
