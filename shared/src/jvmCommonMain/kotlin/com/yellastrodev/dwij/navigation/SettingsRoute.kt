package com.yellastrodev.dwij.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yellastrodev.dwij.auth.YandexSession
import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.di.DwijComponent
import com.yellastrodev.dwij.resources.Res
import com.yellastrodev.dwij.resources.auth_browser_error
import com.yellastrodev.dwij.resources.auth_clip_label
import com.yellastrodev.dwij.resources.auth_code_copied
import com.yellastrodev.dwij.resources.auth_device_message
import com.yellastrodev.dwij.resources.auth_device_title
import com.yellastrodev.dwij.resources.auth_error_account
import com.yellastrodev.dwij.resources.auth_error_configuration
import com.yellastrodev.dwij.resources.auth_error_network
import com.yellastrodev.dwij.resources.auth_error_oauth
import com.yellastrodev.dwij.resources.auth_error_response
import com.yellastrodev.dwij.resources.auth_error_timeout
import com.yellastrodev.dwij.resources.auth_open_browser
import com.yellastrodev.dwij.resources.auth_success
import com.yellastrodev.dwij.resources.multi_source_dialog_cancel
import com.yellastrodev.dwij.resources.settings_music_directories_add
import com.yellastrodev.dwij.resources.settings_music_directories_close
import com.yellastrodev.dwij.resources.settings_music_directories_dialog_title
import com.yellastrodev.dwij.resources.settings_music_directories_duplicate
import com.yellastrodev.dwij.resources.settings_music_directories_empty
import com.yellastrodev.dwij.resources.settings_music_directories_remove
import com.yellastrodev.dwij.resources.settings_music_directories_remove_message
import com.yellastrodev.dwij.resources.settings_music_directories_remove_title
import com.yellastrodev.dwij.resources.settings_music_directories_save_failed
import com.yellastrodev.dwij.resources.settings_music_directories_saved
import com.yellastrodev.dwij.resources.settings_music_directories_sync_failed
import com.yellastrodev.dwij.resources.settings_music_directory_picker_title
import com.yellastrodev.dwij.ui.LocalYamLogger
import com.yellastrodev.dwij.ui.ProxySettingsDialog
import com.yellastrodev.dwij.ui.SettingsScreen
import com.yellastrodev.yamusicsdk.YamApiClient
import com.yellastrodev.yamusicsdk.YamLogger
import com.yellastrodev.yamusicsdk.auth.DeviceAuthError
import com.yellastrodev.yamusicsdk.auth.DeviceAuthResult
import com.yellastrodev.yamusicsdk.auth.DeviceCode
import com.yellastrodev.yamusicsdk.auth.OAuthToken
import com.yellastrodev.yamusicsdk.auth.YandexDeviceAuth
import com.yellastrodev.yamusicsdk.network.YamError
import com.yellastrodev.yamusicsdk.network.YamProxyType
import com.yellastrodev.yamusicsdk.network.YamResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * Shared-route настроек, OAuth Device Flow и общего файлового кэша.
 *
 * Постоянные настройки и авторизация принадлежат shared-компоненту.
 * Платформа используется только для StatFs, Intent, clipboard и lifecycle.
 */
@Composable
fun SettingsRoute(
    component: DwijComponent,
    platform: SettingsPlatform,
    onBackClick: () -> Unit,
    startAuthorization: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val logger =
        LocalYamLogger.current

    val coroutineScope =
        rememberCoroutineScope()

    val snackbarHostState =
        remember {
            SnackbarHostState()
        }

    var yandexLogin by remember(component) {
        mutableStateOf(
            component
                .yandexSessionManager
                .currentLogin(),
        )
    }

    var isAuthInProgress by remember {
        mutableStateOf(false)
    }

    var authJob by remember {
        mutableStateOf<Job?>(null)
    }

    var deviceCode by remember {
        mutableStateOf<DeviceCode?>(null)
    }

    var cacheLimitMb by remember(component) {
        mutableStateOf(
            runCatching {
                bytesToMegabytes(
                    component
                        .cacheSettings
                        .maxSizeBytes,
                )
            }
                .getOrDefault(
                    MIN_CACHE_SIZE_MB,
                )
                .coerceAtLeast(
                    MIN_CACHE_SIZE_MB,
                ),
        )
    }

    var maxCacheMb by remember {
        mutableStateOf(
            MIN_CACHE_SIZE_MB + 1,
        )
    }

    var occupiedCacheSize by remember {
        mutableStateOf("0 B")
    }

    var musicDirectories by remember(platform) {
        mutableStateOf(
            platform.musicDirectories,
        )
    }

    var showMusicDirectoriesDialog by remember {
        mutableStateOf(false)
    }

    var showProxyDialog by remember {
        mutableStateOf(false)
    }

    var yandexProxyType by remember(component) {
        mutableStateOf(
            component
                .yandexProxySettings
                .selectedType,
        )
    }

    var yandexProxyUrl by remember(component) {
        val settings =
            component.yandexProxySettings

        mutableStateOf(
            settings.urlFor(
                settings.selectedType,
            ),
        )
    }

    var yandexProxyEnabled by remember(component) {
        val settings =
            component.yandexProxySettings

        mutableStateOf(
            settings.enabled &&
                settings.parseConfig(
                    settings.url,
                    settings.selectedType,
                ) != null,
        )
    }

    var directoryPendingRemoval by remember {
        mutableStateOf<String?>(null)
    }

    fun showMessage(
        message: String,
    ) {
        coroutineScope.launch {
            snackbarHostState
                .currentSnackbarData
                ?.dismiss()

            snackbarHostState
                .showSnackbar(
                    message,
                )
        }
    }

    fun refreshCacheState() {
        try {
            cacheLimitMb =
                bytesToMegabytes(
                    component
                        .cacheSettings
                        .maxSizeBytes,
                )
                    .coerceAtLeast(
                        MIN_CACHE_SIZE_MB,
                    )

            val availableMb =
                (
                        platform
                            .availableCacheBytes() /
                                BYTES_PER_MEGABYTE
                        )
                    .coerceAtMost(
                        Int.MAX_VALUE.toLong(),
                    )
                    .toInt()

            maxCacheMb =
                maxOf(
                    MIN_CACHE_SIZE_MB + 1,
                    cacheLimitMb,
                    availableMb,
                )
        } catch (error: Exception) {
            logger.error(
                TAG,
                "[refreshCacheState] Не удалось прочитать параметры хранилища",
                error,
            )
        }

        coroutineScope.launch {
            occupiedCacheSize =
                try {
                    withContext(
                        Dispatchers.IO,
                    ) {
                        formatSettingsSize(
                            component
                                .cacheManager
                                .getTotalSize(),
                        )
                    }
                } catch (
                    error: CancellationException,
                ) {
                    throw error
                } catch (error: Exception) {
                    logger.error(
                        TAG,
                        "[refreshCacheState] Не удалось вычислить размер кэша",
                        error,
                    )

                    "0 B"
                }
        }
    }

    fun replaceMusicDirectories(
        directories: List<String>,
    ) {
        val savedDirectories =
            try {
                platform.replaceMusicDirectories(
                    directories,
                )
            } catch (error: Exception) {
                logger.error(
                    TAG,
                    "[replaceMusicDirectories] Не удалось сохранить список музыкальных папок",
                    error,
                )

                null
            }

        if (savedDirectories == null) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar(
                    getString(
                        Res.string.settings_music_directories_save_failed,
                    ),
                )
            }

            return
        }

        musicDirectories =
            savedDirectories

        coroutineScope.launch {
            when (
                component
                    .localMusicRepository
                    .synchronize(
                        force =
                            true,
                    )
            ) {
                is DataResult.Success ->
                    snackbarHostState.showSnackbar(
                        getString(
                            Res.string.settings_music_directories_saved,
                        ),
                    )

                is DataResult.Failure ->
                    snackbarHostState.showSnackbar(
                        getString(
                            Res.string.settings_music_directories_sync_failed,
                        ),
                    )
            }
        }
    }

    fun clearYandexSession() {
        component
            .yandexSessionManager
            .clear()

        yandexLogin = null

        logger.info(
            TAG,
            "[clearYandexSession] Авторизация Яндекс Музыки удалена",
        )
    }

    fun applyCurrentProxySettings() {
        component
            .yandexSessionManager
            .updateProxyConfig(
                component
                    .yandexProxySettings
                    .activeConfigOrNull(),
            )
    }

    fun commitYandexProxyUrl() {
        val normalized =
            yandexProxyUrl.trim()

        yandexProxyUrl =
            normalized

        val settings =
            component.yandexProxySettings

        settings.setUrl(
            type = yandexProxyType,
            value = normalized,
        )

        if (
            settings.parseConfig(
                normalized,
                yandexProxyType,
            ) == null
        ) {
            yandexProxyEnabled =
                false

            settings.enabled =
                false
        }

        applyCurrentProxySettings()
    }

    fun setYandexProxyEnabled(
        enabled: Boolean,
    ) {
        val settings =
            component.yandexProxySettings

        val proxyConfig =
            settings.parseConfig(
                yandexProxyUrl,
                yandexProxyType,
            )

        val resolvedEnabled =
            enabled &&
                proxyConfig != null

        yandexProxyEnabled =
            resolvedEnabled

        settings.enabled =
            resolvedEnabled

        component
            .yandexSessionManager
            .updateProxyConfig(
                if (resolvedEnabled) {
                    proxyConfig
                } else {
                    null
                },
            )
    }

    fun selectYandexProxyType(
        type: YamProxyType,
    ) {
        if (type == yandexProxyType) {
            return
        }

        val settings =
            component.yandexProxySettings

        val nextUrl =
            settings.urlFor(type)

        val nextConfig =
            settings.selectType(type)

        val remainsEnabled =
            settings.enabled &&
                nextConfig != null

        yandexProxyType =
            type

        yandexProxyUrl =
            nextUrl

        yandexProxyEnabled =
            remainsEnabled

        component
            .yandexSessionManager
            .updateProxyConfig(
                if (remainsEnabled) {
                    nextConfig
                } else {
                    null
                },
            )
    }

    suspend fun saveToken(
        token: OAuthToken,
    ): SettingsAccountSaveResult {
        val status =
            when (
                val result =
                    YamApiClient(
                        accessToken =
                            token.accessToken,
                        userId = "",
                        logger = logger,
                        proxyConfig =
                            component
                                .yandexProxySettings
                                .activeConfigOrNull(),
                    ).accountStatus()
            ) {
                is YamResult.Success ->
                    result.value

                is YamResult.Failure -> {
                    logSettingsAccountError(
                        logger = logger,
                        error = result.error,
                    )

                    return SettingsAccountSaveResult
                        .Failure
                }
            }

        return try {
            val account =
                requireNotNull(
                    status.account,
                ) {
                    "В account/status отсутствует account"
                }

            val login =
                requireNotNull(
                    account.login,
                ) {
                    "В account/status отсутствует login"
                }

            val userId =
                requireNotNull(
                    account.uid,
                ) {
                    "В account/status отсутствует uid"
                }.toString()

            val expiresAtMillis =
                token.expiresIn
                    ?.let { seconds ->
                        currentTimeMillis() +
                                seconds * 1_000L
                    }

            component
                .yandexSessionManager
                .save(
                    YandexSession(
                        accessToken =
                            token.accessToken,
                        refreshToken =
                            token.refreshToken,
                        expiresAtMillis =
                            expiresAtMillis,
                        login =
                            login,
                        userId =
                            userId,
                    ),
                )

            logger.info(
                TAG,
                "[saveToken] Авторизация сохранена",
            )

            try {
                when (
                    val refreshResult =
                        component
                            .playlistRepository
                            .refreshPlaylists()
                ) {
                    is DataResult.Success -> {
                        logger.info(
                            TAG,
                            "[saveToken] Данные Яндекс Музыки обновлены после авторизации",
                        )
                    }

                    is DataResult.Failure -> {
                        logger.warning(
                            TAG,
                            "[saveToken] Авторизация сохранена, но данные не обновлены: " +
                                    refreshResult.error,
                        )
                    }
                }
            } catch (
                error: CancellationException,
            ) {
                throw error
            } catch (error: Exception) {
                logger.error(
                    TAG,
                    "[saveToken] Авторизация сохранена, но обновление данных завершилось с ошибкой",
                    error,
                )
            }

            SettingsAccountSaveResult
                .Success(
                    login,
                )
        } catch (
            error: CancellationException,
        ) {
            throw error
        } catch (error: Exception) {
            logger.error(
                TAG,
                "[saveToken] Некорректный ответ account/status",
                error,
            )

            SettingsAccountSaveResult.Failure
        }
    }

    fun startYandexAuth() {
        if (
            authJob?.isActive ==
            true
        ) {
            return
        }

        isAuthInProgress = true

        authJob =
            coroutineScope.launch {
                try {
                    when (
                        val result =
                            YandexDeviceAuth(
                                clientId =
                                    platform
                                        .oauthClientId,
                                clientSecret =
                                    platform
                                        .oauthClientSecret,
                                logger =
                                    logger,
                                proxyConfig =
                                    component
                                        .yandexProxySettings
                                        .activeConfigOrNull(),
                            ).authorize(
                                onCode = { code ->
                                    deviceCode = code
                                },
                            )
                    ) {
                        is DeviceAuthResult.Success -> {
                            when (
                                val saved =
                                    saveToken(
                                        result.value,
                                    )
                            ) {
                                is SettingsAccountSaveResult.Success -> {
                                    yandexLogin =
                                        saved.login

                                    snackbarHostState
                                        .showSnackbar(
                                            getString(
                                                Res.string.auth_success,
                                            ),
                                        )
                                }

                                SettingsAccountSaveResult.Failure -> {
                                    snackbarHostState
                                        .showSnackbar(
                                            getString(
                                                Res.string.auth_error_account,
                                            ),
                                        )
                                }
                            }
                        }

                        is DeviceAuthResult.Failure -> {
                            logSettingsAuthError(
                                logger =
                                    logger,
                                error =
                                    result.error,
                            )

                            if (
                                result.error
                                        !is DeviceAuthError.Cancelled
                            ) {
                                snackbarHostState
                                    .showSnackbar(
                                        settingsAuthErrorMessage(
                                            result.error,
                                        ),
                                    )
                            }
                        }
                    }
                } catch (
                    error: CancellationException,
                ) {
                    logger.info(
                        TAG,
                        "[startYandexAuth] Ожидание авторизации отменено",
                    )

                    throw error
                } catch (error: Exception) {
                    logger.error(
                        TAG,
                        "[startYandexAuth] Неожиданная ошибка авторизации",
                        error,
                    )

                    snackbarHostState
                        .showSnackbar(
                            getString(
                                Res.string.auth_error_response,
                            ),
                        )
                } finally {
                    deviceCode = null
                    isAuthInProgress = false
                    authJob = null
                }
            }
    }

    LaunchedEffect(startAuthorization) {
        if (startAuthorization) {
            startYandexAuth()
        }
    }

    LaunchedEffect(
        component,
        platform,
    ) {
        refreshCacheState()
    }

    platform.ResumeEffect {
        refreshCacheState()

        musicDirectories =
            platform.musicDirectories

        yandexLogin =
            component
                .yandexSessionManager
                .currentLogin()
    }

    val musicDirectoryPickerTitle =
        stringResource(
            Res.string.settings_music_directory_picker_title,
        )

    val duplicateMusicDirectoryMessage =
        stringResource(
            Res.string.settings_music_directories_duplicate,
        )

    Box(
        modifier =
            modifier.fillMaxSize(),
    ) {
        SettingsScreen(
            appVersion =
                platform.appVersion,
            yandexLogin =
                yandexLogin,
            isAuthInProgress =
                isAuthInProgress,
            cacheLimitMb =
                cacheLimitMb,
            minCacheMb =
                MIN_CACHE_SIZE_MB,
            maxCacheMb =
                maxCacheMb,
            occupiedCacheSize =
                occupiedCacheSize,
            musicDirectories =
                musicDirectories,
            onBackClick =
                onBackClick,
            onAuthClick = {
                if (
                    yandexLogin ==
                    null
                ) {
                    startYandexAuth()
                } else {
                    clearYandexSession()
                }
            },
            onCacheLimitCommitted = {
                    megabytes,
                ->

                val normalizedMb =
                    megabytes.coerceIn(
                        MIN_CACHE_SIZE_MB,
                        maxCacheMb,
                    )

                cacheLimitMb =
                    normalizedMb

                component
                    .cacheSettings
                    .maxSizeBytes =
                    normalizedMb.toLong() *
                            BYTES_PER_MEGABYTE
            },
            onProxyClick = {
                val settings =
                    component.yandexProxySettings

                val selectedType =
                    settings.selectedType

                yandexProxyType =
                    selectedType

                yandexProxyUrl =
                    settings.urlFor(
                        selectedType,
                    )

                yandexProxyEnabled =
                    settings.enabled &&
                        settings.parseConfig(
                            settings.urlFor(
                                selectedType,
                            ),
                            selectedType,
                        ) != null

                showProxyDialog =
                    true
            },
            onMusicDirectoriesClick = {
                showMusicDirectoriesDialog =
                    true
            },
            modifier =
                Modifier.fillMaxSize(),
        )

        SnackbarHost(
            hostState =
                snackbarHostState,
            modifier =
                Modifier
                    .align(
                        Alignment.BottomCenter,
                    )
                    .padding(
                        horizontal = 14.dp,
                        vertical = 12.dp,
                    ),
        )
    }

    if (showProxyDialog) {
        ProxySettingsDialog(
            proxyType =
                yandexProxyType,
            proxyUrl =
                yandexProxyUrl,
            enabled =
                yandexProxyEnabled,
            isProxyUrlValid =
                component
                    .yandexProxySettings
                    .parseConfig(
                        yandexProxyUrl,
                        yandexProxyType,
                    ) != null,
            onProxyUrlChange = { value ->
                yandexProxyUrl =
                    value
            },
            onProxyUrlCommitted =
                ::commitYandexProxyUrl,
            onProxyTypeChange =
                ::selectYandexProxyType,
            onEnabledChange =
                ::setYandexProxyEnabled,
            onDismiss = {
                showProxyDialog =
                    false
            },
        )
    }

    val clipboardLabel =
        stringResource(
            Res.string.auth_clip_label,
        )

    val codeCopiedMessage =
        stringResource(
            Res.string.auth_code_copied,
        )

    val browserErrorMessage =
        stringResource(
            Res.string.auth_browser_error,
        )

    if (
        showMusicDirectoriesDialog &&
        directoryPendingRemoval ==
        null
    ) {
        AlertDialog(
            onDismissRequest = {
                showMusicDirectoriesDialog =
                    false
            },
            title = {
                Text(
                    stringResource(
                        Res.string.settings_music_directories_dialog_title,
                    ),
                )
            },
            text = {
                val directories =
                    musicDirectories.orEmpty()

                if (directories.isEmpty()) {
                    Text(
                        stringResource(
                            Res.string.settings_music_directories_empty,
                        ),
                    )
                } else {
                    LazyColumn(
                        verticalArrangement =
                            Arrangement.spacedBy(
                                8.dp,
                            ),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(
                                    max = 360.dp,
                                ),
                    ) {
                        items(
                            items =
                                directories,
                            key = { directory ->
                                directory.lowercase()
                            },
                        ) { directory ->
                            Row(
                                verticalAlignment =
                                    Alignment.CenterVertically,
                                modifier =
                                    Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text =
                                        directory,
                                    maxLines =
                                        2,
                                    overflow =
                                        TextOverflow.Ellipsis,
                                    modifier =
                                        Modifier.weight(1f),
                                )
                                TextButton(
                                    onClick = {
                                        directoryPendingRemoval =
                                            directory
                                    },
                                ) {
                                    Text(
                                        stringResource(
                                            Res.string.settings_music_directories_remove,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val selectedDirectory =
                            try {
                                platform.chooseMusicDirectory(
                                    musicDirectoryPickerTitle,
                                )
                            } catch (error: Exception) {
                                logger.error(
                                    TAG,
                                    "[chooseMusicDirectory] Не удалось открыть выбор музыкальной папки",
                                    error,
                                )

                                null
                            }

                        if (selectedDirectory != null) {
                            val currentDirectories =
                                musicDirectories.orEmpty()

                            if (
                                currentDirectories.any { directory ->
                                    directory.equals(
                                        selectedDirectory,
                                        ignoreCase =
                                            true,
                                    )
                                }
                            ) {
                                showMessage(
                                    duplicateMusicDirectoryMessage,
                                )
                            } else {
                                replaceMusicDirectories(
                                    currentDirectories +
                                            selectedDirectory,
                                )
                            }
                        }
                    },
                ) {
                    Text(
                        stringResource(
                            Res.string.settings_music_directories_add,
                        ),
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showMusicDirectoriesDialog =
                            false
                    },
                ) {
                    Text(
                        stringResource(
                            Res.string.settings_music_directories_close,
                        ),
                    )
                }
            },
            modifier =
                Modifier.widthIn(
                    max = 640.dp,
                ),
        )
    }

    directoryPendingRemoval?.let { directory ->
        AlertDialog(
            onDismissRequest = {
                directoryPendingRemoval =
                    null
            },
            title = {
                Text(
                    stringResource(
                        Res.string.settings_music_directories_remove_title,
                    ),
                )
            },
            text = {
                Text(
                    stringResource(
                        Res.string.settings_music_directories_remove_message,
                        directory,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        directoryPendingRemoval =
                            null

                        replaceMusicDirectories(
                            musicDirectories
                                .orEmpty()
                                .filterNot { candidate ->
                                    candidate.equals(
                                        directory,
                                        ignoreCase =
                                            true,
                                    )
                                },
                        )
                    },
                ) {
                    Text(
                        stringResource(
                            Res.string.settings_music_directories_remove,
                        ),
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        directoryPendingRemoval =
                            null
                    },
                ) {
                    Text(
                        stringResource(
                            Res.string.multi_source_dialog_cancel,
                        ),
                    )
                }
            },
        )
    }

    deviceCode?.let { code ->
        AlertDialog(
            onDismissRequest = {
                authJob?.cancel()
                deviceCode = null
            },
            title = {
                Text(
                    stringResource(
                        Res.string.auth_device_title,
                    ),
                )
            },
            text = {
                Text(
                    stringResource(
                        Res.string.auth_device_message,
                        code.verificationUrl,
                        code.userCode,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        try {
                            platform.copyText(
                                label =
                                    clipboardLabel,
                                text =
                                    code.userCode,
                            )

                            val opened =
                                platform.openUrl(
                                    code.verificationUrl,
                                )

                            showMessage(
                                if (opened) {
                                    codeCopiedMessage
                                } else {
                                    browserErrorMessage
                                },
                            )
                        } catch (
                            error: Exception,
                        ) {
                            logger.error(
                                TAG,
                                "[openAuthorizationPage] Не удалось открыть страницу авторизации",
                                error,
                            )

                            showMessage(
                                browserErrorMessage,
                            )
                        }
                    },
                ) {
                    Text(
                        stringResource(
                            Res.string.auth_open_browser,
                        ),
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        authJob?.cancel()
                        deviceCode = null
                    },
                ) {
                    Text(
                        stringResource(
                            Res.string.multi_source_dialog_cancel,
                        ),
                    )
                }
            },
        )
    }
}

private sealed interface SettingsAccountSaveResult {

    data class Success(
        val login: String,
    ) : SettingsAccountSaveResult

    data object Failure :
        SettingsAccountSaveResult
}

private suspend fun settingsAuthErrorMessage(
    error: DeviceAuthError,
): String =
    when (error) {
        DeviceAuthError.Configuration ->
            getString(
                Res.string.auth_error_configuration,
            )

        DeviceAuthError.Cancelled ->
            getString(
                Res.string.auth_error_response,
            )

        is DeviceAuthError.Timeout ->
            getString(
                Res.string.auth_error_timeout,
            )

        is DeviceAuthError.Network ->
            getString(
                Res.string.auth_error_network,
            )

        is DeviceAuthError.OAuth ->
            getString(
                Res.string.auth_error_oauth,
                error.code,
            )

        is DeviceAuthError.Http,
        is DeviceAuthError.InvalidResponse,
            ->
            getString(
                Res.string.auth_error_response,
            )
    }

private fun logSettingsAuthError(
    logger: YamLogger,
    error: DeviceAuthError,
) {
    when (error) {
        is DeviceAuthError.Network ->
            logger.error(
                TAG,
                "[startYandexAuth] Ошибка сети",
                error.cause,
            )

        is DeviceAuthError.InvalidResponse ->
            logger.error(
                TAG,
                "[startYandexAuth] Некорректный ответ OAuth",
                error.cause,
            )

        is DeviceAuthError.OAuth ->
            logger.warning(
                TAG,
                "[startYandexAuth] OAuth-ошибка: ${error.code}",
            )

        is DeviceAuthError.Http ->
            logger.warning(
                TAG,
                "[startYandexAuth] HTTP-ошибка: ${error.statusCode}",
            )

        is DeviceAuthError.Timeout ->
            logger.warning(
                TAG,
                "[startYandexAuth] Таймаут: ${error.timeoutSeconds} сек.",
            )

        DeviceAuthError.Cancelled ->
            logger.info(
                TAG,
                "[startYandexAuth] Авторизация отменена",
            )

        DeviceAuthError.Configuration ->
            logger.error(
                TAG,
                "[startYandexAuth] OAuth не настроен",
            )
    }
}

private fun logSettingsAccountError(
    logger: YamLogger,
    error: YamError,
) {
    when (error) {
        YamError.Unauthorized ->
            logger.warning(
                TAG,
                "[saveToken] Токен не принят",
            )

        YamError.NoInternet ->
            logger.warning(
                TAG,
                "[saveToken] Нет подключения к сети",
            )

        YamError.Timeout ->
            logger.warning(
                TAG,
                "[saveToken] Таймаут account/status",
            )

        is YamError.Http ->
            logger.warning(
                TAG,
                "[saveToken] HTTP ${error.statusCode}, code=${error.code}",
            )

        is YamError.InvalidResponse ->
            logger.error(
                TAG,
                "[saveToken] Некорректный account/status",
                error.cause,
            )

        is YamError.Network ->
            logger.error(
                TAG,
                "[saveToken] Ошибка сети account/status",
                error.cause,
            )
    }
}

private fun formatSettingsSize(
    bytes: Long,
): String {
    if (bytes < 1024) {
        return "$bytes B"
    }

    val units =
        arrayOf(
            "KB",
            "MB",
            "GB",
            "TB",
        )

    var value =
        bytes.toDouble()

    var unitIndex = -1

    do {
        value /= 1024.0
        unitIndex++
    } while (
        value >= 1024 &&
        unitIndex < units.lastIndex
    )

    return java.lang.String.format(
        "%.2f %s",
        value,
        units[unitIndex],
    )
}

private fun bytesToMegabytes(
    bytes: Long,
): Int =
    (
            bytes /
                    BYTES_PER_MEGABYTE
            )
        .toInt()

private fun currentTimeMillis(): Long =
    System.currentTimeMillis()

private const val TAG =
    "SettingsRoute"

private const val MIN_CACHE_SIZE_MB =
    200

private const val BYTES_PER_MEGABYTE =
    1024L * 1024L
