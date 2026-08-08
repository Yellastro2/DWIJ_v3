package com.yellastrodev.dwij.desktop.windows

import com.sun.jna.CallbackReference
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WTypes
import com.sun.jna.platform.win32.WinDef.HICON
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.LRESULT
import com.sun.jna.platform.win32.WinDef.WPARAM
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.platform.win32.COM.COMInvoker
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.yellastrodev.dwij.utils.PlayerState
import com.yellastrodev.yandexmusiclib.YamLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.awt.EventQueue
import java.awt.Window
import java.io.File
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Windows thumbnail toolbar под live-preview окна в панели задач.
 *
 * Previous | Play/Pause | Next
 *
 * Сама миниатюра окна остаётся стандартной Windows live-preview.
 */
class WindowsTaskbarControls(
    private val scope: CoroutineScope,
    private val logger: YamLogger,
    private val cacheDirectory: File,
    private val playerState: StateFlow<PlayerState>,
    private val onPrevious: suspend () -> Unit,
    private val onPlayPause: suspend () -> Unit,
    private val onNext: suspend () -> Unit,
) {

    private val closed =
        AtomicBoolean(false)

    private var attached =
        false

    @Volatile
    private var buttonsAdded =
        false

    @Volatile
    private var latestIsPlaying =
        playerState.value.isPlaying

    private var appliedIsPlaying:
            Boolean? =
        null

    private var hwnd:
            HWND? =
        null

    private var oldWindowProc:
            Pointer? =
        null

    /**
     * Strong reference обязателен:
     * Windows продолжает вызывать этот callback,
     * пока WndProc не будет восстановлен.
     */
    private var windowProc:
            WinUser.WindowProc? =
        null

    private var taskbarButtonCreatedMessage =
        0

    private var taskbarList:
            TaskbarList3? =
        null

    private var comUninitializeNeeded =
        false

    private var previousIcon:
            HICON? =
        null

    private var playIcon:
            HICON? =
        null

    private var pauseIcon:
            HICON? =
        null

    private var nextIcon:
            HICON? =
        null

    private var stateJob:
            Job? =
        null

    private var addRetryJob:
            Job? =
        null

    fun attach(
        window: Window,
    ) {
        if (
            closed.get() ||
            attached
        ) {
            return
        }

        try {
            runOnAwtThread {
                attachOnAwtThread(
                    window,
                )
            }
        } catch (error: Throwable) {
            logger.error(
                TAG,
                "[attach] Не удалось подключить Windows taskbar controls",
                error,
            )
        }
    }

    fun close() {
        if (
            !closed.compareAndSet(
                false,
                true,
            )
        ) {
            return
        }

        addRetryJob
            ?.cancel()

        addRetryJob =
            null

        stateJob
            ?.cancel()

        stateJob =
            null

        try {
            runOnAwtThread {
                releaseNativeResourcesOnAwtThread()
            }
        } catch (error: Throwable) {
            logger.error(
                TAG,
                "[close] Ошибка закрытия Windows taskbar controls",
                error,
            )
        }
    }

    private fun attachOnAwtThread(
        window: Window,
    ) {
        if (
            attached ||
            closed.get()
        ) {
            return
        }

        try {
            val windowPointer =
                Native.getComponentPointer(
                    window,
                )

            if (
                windowPointer == null ||
                Pointer.nativeValue(
                    windowPointer,
                ) == 0L
            ) {
                error(
                    "Не удалось получить HWND Compose Desktop окна",
                )
            }

            hwnd =
                HWND(
                    windowPointer,
                )

            taskbarButtonCreatedMessage =
                User32.INSTANCE
                    .RegisterWindowMessage(
                        TASKBAR_BUTTON_CREATED_MESSAGE,
                    )

            if (
                taskbarButtonCreatedMessage == 0
            ) {
                error(
                    "RegisterWindowMessage(TaskbarButtonCreated) вернул 0",
                )
            }

            initializeCom()

            createTaskbarList()

            loadIcons()

            installWindowProc()

            attached =
                true

            startPlayerStateSync()

            if (
                !tryAddButtons()
            ) {
                scheduleAddRetries()
            }

            logger.info(
                TAG,
                "[attach] Taskbar integration подключена",
            )
        } catch (error: Throwable) {
            releaseNativeResourcesOnAwtThread()

            attached =
                false

            throw error
        }
    }

    private fun initializeCom() {
        val result =
            Ole32.INSTANCE
                .CoInitializeEx(
                    null,
                    Ole32.COINIT_APARTMENTTHREADED,
                )
                .toInt()

        when {
            result >= 0 -> {
                /*
                 * И S_OK, и S_FALSE требуют парный CoUninitialize().
                 */
                comUninitializeNeeded =
                    true
            }

            result ==
                    RPC_E_CHANGED_MODE -> {
                /*
                 * AWT thread уже имеет другую COM apartment model.
                 * Используем существующую.
                 */
                comUninitializeNeeded =
                    false
            }

            else -> {
                error(
                    "CoInitializeEx failed: " +
                            formatHResult(
                                result,
                            ),
                )
            }
        }
    }

    private fun createTaskbarList() {
        val resultPointer =
            PointerByReference()

        val result =
            Ole32.INSTANCE
                .CoCreateInstance(
                    Guid.CLSID(
                        CLSID_TASKBAR_LIST,
                    ),
                    null,
                    WTypes.CLSCTX_INPROC_SERVER,
                    Guid.IID(
                        IID_ITASKBAR_LIST3,
                    ),
                    resultPointer,
                )
                .toInt()

        requireSucceeded(
            operation =
                "CoCreateInstance(CLSID_TaskbarList)",
            result =
                result,
        )

        val pointer =
            resultPointer.value
                ?: error(
                    "CoCreateInstance вернул null ITaskbarList3",
                )

        val instance =
            TaskbarList3(
                pointer,
            )

        val initResult =
            instance.hrInit()

        if (
            initResult < 0
        ) {
            instance.release()

            error(
                "ITaskbarList3.HrInit failed: " +
                        formatHResult(
                            initResult,
                        ),
            )
        }

        taskbarList =
            instance
    }

    private fun installWindowProc() {
        val currentHwnd =
            hwnd
                ?: error(
                    "HWND ещё не инициализирован",
                )

        val proc =
            object :
                WinUser.WindowProc {

                override fun callback(
                    hwnd: HWND,
                    uMsg: Int,
                    wParam: WPARAM,
                    lParam: LPARAM,
                ): LRESULT =
                    handleWindowMessage(
                        hwnd =
                            hwnd,
                        message =
                            uMsg,
                        wParam =
                            wParam,
                        lParam =
                            lParam,
                    )
            }

        windowProc =
            proc

        val callbackPointer =
            CallbackReference
                .getFunctionPointer(
                    proc,
                )

        oldWindowProc =
            if (
                Platform.is64Bit()
            ) {
                User32.INSTANCE
                    .SetWindowLongPtr(
                        currentHwnd,
                        WinUser.GWL_WNDPROC,
                        callbackPointer,
                    )
            } else {
                val oldValue =
                    User32.INSTANCE
                        .SetWindowLong(
                            currentHwnd,
                            WinUser.GWL_WNDPROC,
                            Pointer.nativeValue(
                                callbackPointer,
                            ).toInt(),
                        )

                Pointer.createConstant(
                    oldValue.toLong() and
                            0xFFFF_FFFFL,
                )
            }

        if (
            oldWindowProc == null ||
            Pointer.nativeValue(
                oldWindowProc,
            ) == 0L
        ) {
            error(
                "Не удалось subclass'нуть WndProc окна",
            )
        }
    }

    private fun handleWindowMessage(
        hwnd: HWND,
        message: Int,
        wParam: WPARAM,
        lParam: LPARAM,
    ): LRESULT {
        try {
            if (
                message ==
                taskbarButtonCreatedMessage
            ) {
                /*
                 * Explorer мог перезапуститься:
                 * тогда toolbar надо зарегистрировать заново.
                 */
                buttonsAdded =
                    false

                appliedIsPlaying =
                    null

                if (
                    !tryAddButtons()
                ) {
                    scheduleAddRetries()
                }

                return callOriginalWindowProc(
                    hwnd =
                        hwnd,
                    message =
                        message,
                    wParam =
                        wParam,
                    lParam =
                        lParam,
                )
            }

            if (
                message ==
                WM_COMMAND
            ) {
                val raw =
                    wParam.toLong()

                val notificationCode =
                    (
                            raw ushr 16 and
                                    0xFFFFL
                            ).toInt()

                val buttonId =
                    (
                            raw and
                                    0xFFFFL
                            ).toInt()

                if (
                    notificationCode ==
                    THBN_CLICKED
                ) {
                    dispatchCommand(
                        buttonId,
                    )

                    return LRESULT(
                        0L,
                    )
                }
            }

            return callOriginalWindowProc(
                hwnd =
                    hwnd,
                message =
                    message,
                wParam =
                    wParam,
                lParam =
                    lParam,
            )
        } catch (error: Throwable) {
            logger.error(
                TAG,
                "[WndProc] Ошибка обработки Windows message=$message",
                error,
            )

            return callOriginalWindowProc(
                hwnd =
                    hwnd,
                message =
                    message,
                wParam =
                    wParam,
                lParam =
                    lParam,
            )
        }
    }

    private fun dispatchCommand(
        buttonId: Int,
    ) {
        val action:
                (suspend () -> Unit)? =
            when (
                buttonId
            ) {
                BUTTON_PREVIOUS ->
                    onPrevious

                BUTTON_PLAY_PAUSE ->
                    onPlayPause

                BUTTON_NEXT ->
                    onNext

                else ->
                    null
            }

        action
            ?: return

        scope.launch {
            try {
                action()
            } catch (
                error: CancellationException,
            ) {
                throw error
            } catch (error: Exception) {
                logger.error(
                    TAG,
                    "[command] Ошибка taskbar command id=$buttonId",
                    error,
                )
            }
        }
    }

    private fun tryAddButtons():
            Boolean {

        if (
            closed.get() ||
            buttonsAdded
        ) {
            return buttonsAdded
        }

        val currentTaskbarList =
            taskbarList
                ?: return false

        val currentHwnd =
            hwnd
                ?: return false

        val buttons =
            createInitialButtons()

        val result =
            currentTaskbarList
                .thumbBarAddButtons(
                    hwnd =
                        currentHwnd,
                    buttons =
                        buttons,
                )

        if (
            result < 0
        ) {
            return false
        }

        buttonsAdded =
            true

        appliedIsPlaying =
            latestIsPlaying

        logger.info(
            TAG,
            "[toolbar] Previous / Play-Pause / Next добавлены",
        )

        return true
    }

    private fun scheduleAddRetries() {
        if (
            closed.get() ||
            buttonsAdded
        ) {
            return
        }

        addRetryJob
            ?.cancel()

        addRetryJob =
            scope.launch {
                repeat(
                    ADD_RETRY_COUNT,
                ) { attempt ->

                    delay(
                        ADD_RETRY_DELAY_MS,
                    )

                    if (
                        closed.get() ||
                        buttonsAdded
                    ) {
                        return@launch
                    }

                    try {
                        runOnAwtThread {
                            tryAddButtons()
                        }
                    } catch (error: Throwable) {
                        logger.debug(
                            TAG,
                            "[toolbar] retry=${attempt + 1} failed: " +
                                    error.message,
                        )
                    }

                    if (
                        buttonsAdded
                    ) {
                        return@launch
                    }
                }

                if (
                    !closed.get() &&
                    !buttonsAdded
                ) {
                    logger.warning(
                        TAG,
                        "[toolbar] Не удалось добавить taskbar buttons после retries",
                    )
                }
            }
    }

    private fun startPlayerStateSync() {
        stateJob
            ?.cancel()

        stateJob =
            scope.launch {
                playerState
                    .map { state ->
                        state.isPlaying
                    }
                    .distinctUntilChanged()
                    .collect { isPlaying ->
                        latestIsPlaying =
                            isPlaying

                        if (
                            closed.get()
                        ) {
                            return@collect
                        }

                        try {
                            runOnAwtThread {
                                updatePlayPauseButton()
                            }
                        } catch (error: Throwable) {
                            logger.error(
                                TAG,
                                "[state] Не удалось обновить Play/Pause taskbar button",
                                error,
                            )
                        }
                    }
            }
    }

    private fun updatePlayPauseButton() {
        if (
            closed.get() ||
            !buttonsAdded ||
            appliedIsPlaying ==
            latestIsPlaying
        ) {
            return
        }

        val currentTaskbarList =
            taskbarList
                ?: return

        val currentHwnd =
            hwnd
                ?: return

        val button =
            ThumbButton()

        configureButton(
            button =
                button,
            id =
                BUTTON_PLAY_PAUSE,
            icon =
                if (
                    latestIsPlaying
                ) {
                    pauseIcon
                } else {
                    playIcon
                },
            tooltip =
                if (
                    latestIsPlaying
                ) {
                    "Пауза"
                } else {
                    "Воспроизвести"
                },
        )

        button.write()

        val result =
            currentTaskbarList
                .thumbBarUpdateButton(
                    hwnd =
                        currentHwnd,
                    button =
                        button,
                )

        if (
            result < 0
        ) {
            logger.warning(
                TAG,
                "[toolbar] ThumbBarUpdateButtons failed: " +
                        formatHResult(
                            result,
                        ),
            )

            return
        }

        appliedIsPlaying =
            latestIsPlaying
    }

    private fun createInitialButtons():
            Array<ThumbButton> {

        /*
         * toArray() создаёт contiguous native memory,
         * чего требует ThumbBarAddButtons.
         */
        val structures =
            ThumbButton()
                .toArray(
                    3,
                )

        val buttons =
            structures
                .map { structure ->
                    structure as ThumbButton
                }
                .toTypedArray()

        configureButton(
            button =
                buttons[0],
            id =
                BUTTON_PREVIOUS,
            icon =
                previousIcon,
            tooltip =
                "Предыдущий трек",
        )

        configureButton(
            button =
                buttons[1],
            id =
                BUTTON_PLAY_PAUSE,
            icon =
                if (
                    latestIsPlaying
                ) {
                    pauseIcon
                } else {
                    playIcon
                },
            tooltip =
                if (
                    latestIsPlaying
                ) {
                    "Пауза"
                } else {
                    "Воспроизвести"
                },
        )

        configureButton(
            button =
                buttons[2],
            id =
                BUTTON_NEXT,
            icon =
                nextIcon,
            tooltip =
                "Следующий трек",
        )

        buttons.forEach(
            ThumbButton::write,
        )

        return buttons
    }

    private fun configureButton(
        button: ThumbButton,
        id: Int,
        icon: HICON?,
        tooltip: String,
    ) {
        button.dwMask =
            THB_ICON or
                    THB_TOOLTIP or
                    THB_FLAGS

        button.iId =
            id

        button.iBitmap =
            0

        button.hIcon =
            icon
                ?: error(
                    "Taskbar icon для button=$id не загружена",
                )

        button.dwFlags =
            THBF_ENABLED

        button.szTip.fill(
            '\u0000',
        )

        tooltip
            .take(
                THUMB_TOOLTIP_LENGTH -
                        1,
            )
            .toCharArray()
            .copyInto(
                button.szTip,
            )
    }

    private fun loadIcons() {
        previousIcon =
            loadIcon(
                fileName =
                    "previous.ico",
                encoded =
                    ICON_PREVIOUS_BASE64,
            )

        playIcon =
            loadIcon(
                fileName =
                    "play.ico",
                encoded =
                    ICON_PLAY_BASE64,
            )

        pauseIcon =
            loadIcon(
                fileName =
                    "pause.ico",
                encoded =
                    ICON_PAUSE_BASE64,
            )

        nextIcon =
            loadIcon(
                fileName =
                    "next.ico",
                encoded =
                    ICON_NEXT_BASE64,
            )
    }

    private fun loadIcon(
        fileName: String,
        encoded: String,
    ): HICON {
        val iconDirectory =
            File(
                cacheDirectory,
                "taskbar-icons",
            ).apply {
                mkdirs()
            }

        val bytes =
            Base64
                .getDecoder()
                .decode(
                    encoded,
                )

        val file =
            File(
                iconDirectory,
                fileName,
            )

        if (
            !file.isFile ||
            file.length() !=
            bytes.size.toLong()
        ) {
            file.writeBytes(
                bytes,
            )
        }

        val iconWidth =
            User32.INSTANCE
                .GetSystemMetrics(
                    SM_CXICON,
                )
                .coerceAtLeast(
                    16,
                )

        val iconHeight =
            User32.INSTANCE
                .GetSystemMetrics(
                    SM_CYICON,
                )
                .coerceAtLeast(
                    16,
                )

        val pointer =
            User32Icons.INSTANCE
                .LoadImageW(
                    null,
                    WString(
                        file.absolutePath,
                    ),
                    IMAGE_ICON,
                    iconWidth,
                    iconHeight,
                    LR_LOADFROMFILE,
                )
                ?: error(
                    "LoadImageW не смог загрузить ${file.absolutePath}",
                )

        return HICON(
            pointer,
        )
    }

    private fun destroyIcons() {
        listOfNotNull(
            previousIcon,
            playIcon,
            pauseIcon,
            nextIcon,
        ).forEach { icon ->
            runCatching {
                User32Icons.INSTANCE
                    .DestroyIcon(
                        icon,
                    )
            }
        }

        previousIcon =
            null

        playIcon =
            null

        pauseIcon =
            null

        nextIcon =
            null
    }

    private fun callOriginalWindowProc(
        hwnd: HWND,
        message: Int,
        wParam: WPARAM,
        lParam: LPARAM,
    ): LRESULT {
        val original =
            oldWindowProc

        return if (
            original != null &&
            Pointer.nativeValue(
                original,
            ) != 0L
        ) {
            User32.INSTANCE
                .CallWindowProc(
                    original,
                    hwnd,
                    message,
                    wParam,
                    lParam,
                )
        } else {
            User32.INSTANCE
                .DefWindowProc(
                    hwnd,
                    message,
                    wParam,
                    lParam,
                )
        }
    }

    private fun restoreWindowProc() {
        val currentHwnd =
            hwnd
                ?: return

        val original =
            oldWindowProc
                ?: return

        if (
            !User32.INSTANCE
                .IsWindow(
                    currentHwnd,
                )
        ) {
            return
        }

        if (
            Platform.is64Bit()
        ) {
            User32.INSTANCE
                .SetWindowLongPtr(
                    currentHwnd,
                    WinUser.GWL_WNDPROC,
                    original,
                )
        } else {
            User32.INSTANCE
                .SetWindowLong(
                    currentHwnd,
                    WinUser.GWL_WNDPROC,
                    Pointer.nativeValue(
                        original,
                    ).toInt(),
                )
        }
    }

    private fun releaseNativeResourcesOnAwtThread() {
        runCatching {
            restoreWindowProc()
        }.onFailure { error ->
            logger.error(
                TAG,
                "[close] Не удалось восстановить WndProc",
                error,
            )
        }

        oldWindowProc =
            null

        windowProc =
            null

        buttonsAdded =
            false

        appliedIsPlaying =
            null

        taskbarList
            ?.let { taskbar ->
                runCatching {
                    taskbar.release()
                }
            }

        taskbarList =
            null

        destroyIcons()

        if (
            comUninitializeNeeded
        ) {
            runCatching {
                Ole32.INSTANCE
                    .CoUninitialize()
            }

            comUninitializeNeeded =
                false
        }

        hwnd =
            null

        attached =
            false
    }

    private fun requireSucceeded(
        operation: String,
        result: Int,
    ) {
        if (
            result < 0
        ) {
            error(
                "$operation failed: " +
                        formatHResult(
                            result,
                        ),
            )
        }
    }

    private fun formatHResult(
        result: Int,
    ): String =
        "0x%08X".format(
            result,
        )

    private fun runOnAwtThread(
        block: () -> Unit,
    ) {
        if (
            EventQueue.isDispatchThread()
        ) {
            block()
        } else {
            EventQueue.invokeAndWait {
                block()
            }
        }
    }

    /**
     * Минимальный wrapper ITaskbarList3.
     *
     * COM vtable:
     *  0 QueryInterface
     *  1 AddRef
     *  2 Release
     *  3 HrInit
     * 15 ThumbBarAddButtons
     * 16 ThumbBarUpdateButtons
     */
    private class TaskbarList3(
        pointer: Pointer,
    ) : COMInvoker() {

        init {
            setPointer(
                pointer,
            )
        }

        fun hrInit():
                Int =
            _invokeNativeInt(
                VTABLE_HR_INIT,
                arrayOf(
                    pointer,
                ),
            )

        fun thumbBarAddButtons(
            hwnd: HWND,
            buttons: Array<ThumbButton>,
        ): Int =
            _invokeNativeInt(
                VTABLE_THUMB_BAR_ADD_BUTTONS,
                arrayOf(
                    pointer,
                    hwnd,
                    buttons.size,
                    buttons.first()
                        .pointer,
                ),
            )

        fun thumbBarUpdateButton(
            hwnd: HWND,
            button: ThumbButton,
        ): Int =
            _invokeNativeInt(
                VTABLE_THUMB_BAR_UPDATE_BUTTONS,
                arrayOf(
                    pointer,
                    hwnd,
                    1,
                    button.pointer,
                ),
            )

        fun release() {
            _invokeNativeInt(
                VTABLE_RELEASE,
                arrayOf(
                    pointer,
                ),
            )
        }
    }

    /**
     * Win32 THUMBBUTTON.
     */
    @Structure.FieldOrder(
        "dwMask",
        "iId",
        "iBitmap",
        "hIcon",
        "szTip",
        "dwFlags",
    )
    private class ThumbButton :
        Structure() {

        @JvmField
        var dwMask =
            0

        @JvmField
        var iId =
            0

        @JvmField
        var iBitmap =
            0

        @JvmField
        var hIcon:
                HICON? =
            null

        @JvmField
        var szTip =
            CharArray(
                THUMB_TOOLTIP_LENGTH,
            )

        @JvmField
        var dwFlags =
            0
    }

    private interface User32Icons :
        StdCallLibrary {

        fun LoadImageW(
            hInst: Pointer?,
            name: WString,
            type: Int,
            cx: Int,
            cy: Int,
            fuLoad: Int,
        ): Pointer?

        fun DestroyIcon(
            hIcon: HICON,
        ): Boolean

        companion object {

            val INSTANCE:
                    User32Icons =
                Native.load(
                    "user32",
                    User32Icons::class.java,
                )
        }
    }

    private companion object {

        const val TAG =
            "WindowsTaskbarControls"

        /*
         * Win32 message.
         *
         * JNA 5.14.0 не предоставляет его здесь
         * в удобном для Kotlin виде, поэтому фиксируем
         * официальный Win32 numeric value.
         */
        const val WM_COMMAND =
            0x0111

        const val CLSID_TASKBAR_LIST =
            "{56FDF344-FD6D-11D0-958A-006097C9A090}"

        const val IID_ITASKBAR_LIST3 =
            "{EA1AFB91-9E28-4B86-90E9-9E9F8A5EEFAF}"

        const val RPC_E_CHANGED_MODE =
            0x80010106.toInt()

        const val VTABLE_RELEASE =
            2

        const val VTABLE_HR_INIT =
            3

        const val VTABLE_THUMB_BAR_ADD_BUTTONS =
            15

        const val VTABLE_THUMB_BAR_UPDATE_BUTTONS =
            16

        const val THB_ICON =
            0x00000002

        const val THB_TOOLTIP =
            0x00000004

        const val THB_FLAGS =
            0x00000008

        const val THBF_ENABLED =
            0x00000000

        const val THBN_CLICKED =
            0x1800

        const val THUMB_TOOLTIP_LENGTH =
            260

        const val BUTTON_PREVIOUS =
            1001

        const val BUTTON_PLAY_PAUSE =
            1002

        const val BUTTON_NEXT =
            1003

        const val TASKBAR_BUTTON_CREATED_MESSAGE =
            "TaskbarButtonCreated"

        const val IMAGE_ICON =
            1

        const val LR_LOADFROMFILE =
            0x00000010

        const val SM_CXICON =
            11

        const val SM_CYICON =
            12

        const val ADD_RETRY_COUNT =
            20

        const val ADD_RETRY_DELAY_MS =
            250L

        const val ICON_PREVIOUS_BASE64 =
            "AAABAAEAICAAAAAAIAAkAgAAFgAAAIlQTkcNChoKAAAADUlIRFIAAAAgAAAAIAgGAAAAc3p69AAAAetJREFUeJztlr2KFEEQgL+aXU9N/EMEDUVEMBLRTOFAUO8JvIcwEyMDBVNBMDO4TLg3OB9CxODAN9DM6AI5d+Yz2Fpm7tzbnd710GALhqmZ7qr6uqu7umElK/nfRQ01/lnwaXpfW7X6GxDnSx2pg2l6iYNKHaof1B/qJ/XyvHSkXZX6GfW9ul4EMgmgXlB/2sqDoxwl2LDz/UT9mnYzAYbTfk78AHvAWuqjI4AHEVEDI/U68BZ4nM317OHOBgCogOg83cABVBFRq2vAc+AZcDYD28P//A7TpDPqWn0IvAFuZnMNDICmj6/S1V3RjvqKugV8zOAjxqMuWvUlMxAR0QCN+hR4AVzKoL2me1mAX+p94DVwL/+N0sfClbIkBQ1wDbiT33Wh/dIAJyNiC7gKbDPOdUW74o8dQLWKiO8RsQlsALsJEvTY88sCEBFNlugqInaA28ArYJ926xXNxiI5NEEGEbEfES8TZIe2cE2tmosA2HkfGFnWgkiQ3YjYADaBb7S7a24xmgUQjM+BJvUTf3SIMEGqTMs2cAt4x3gWqj4QB2Ry5Kqn1S95ou2pN7L9SOhD94C76mf10eG2XhD5vqiu5ynX61Y0SUvqp9RzfW17QRX077XAZzqdHLnkyi8B6NgTEQsXqpWs5NjlN9sFTPWBxTg0AAAAAElFTkSuQmCC"

        const val ICON_PLAY_BASE64 =
            "AAABAAEAICAAAAAAIACDAQAAFgAAAIlQTkcNChoKAAAADUlIRFIAAAAgAAAAIAgGAAAAc3p69AAAAUpJREFUeJzt1j9KA0EUx/FJjJAuClroAcQDCB5AEDyDvZXewELwBh5CCy9g4QG8QuwtFBERK2PysZnFR0hi/uxGlPxgmdmdN7zvezPzdlJaaKG/JNRR/y3nS4P683Bcy+06rrGd3+eTjQDQQhdvOA7jjcKmaoBVPPnWLXaDXTXL0gfwgh46GaKDMzQKiNKzMQBAhvgM2bjDXphTXjZGABRtJ4BcoFVAlJKNHwAKdfMDbRyE+Y15ABSKy3KFjZmcTwHQn40HHKFpSM2oopDUUkq93N9MKe2klJZjMBNpwgx0Q7+N/ZnDGRMgnoYPnAunoWqA/npQbnUcARCjfsUpirUu7/8wBCAWnxtsBftyN3YfwHPYaI84CXazFZwxAFp4z84vsZm/V3svCABruMdhGKsm6iEgTazkfr20TTYFyPzugwOc/07UC/1rfQFKqdHqTlVR1AAAAABJRU5ErkJggg=="

        const val ICON_PAUSE_BASE64 =
            "AAABAAEAICAAAAAAIAAwAQAAFgAAAIlQTkcNChoKAAAADUlIRFIAAAAgAAAAIAgGAAAAc3p69AAAAPdJREFUeJztlrFKxEAURe9NVnsFC//Bz/Bb/Rx7G5vFShAEUQvdY7EvELOzyZuwYOHc5pHh5M3JpJgntfz3OAsCv1jbrGFWZdq4tJZhSqk5gQtJxDuvtndrmKoAHbAB7oAX4DnqPXANGOgTTPpDx5s76iXwyWFuR+wsA/TH9ukyLpLeou4kfUf9qmSK2SQEBlHPPGeZYuM/TRNoAk2gCTSBrMB0suHI2hKzSsCSzrW/3YabzpLOKpl6gZgJPiQ9BttH03dJ22C6JUYzJzF7XQK2DXAl6SYadZKebD8MQ8sSc7LhdCp3CiY1q0Wj8e9iOnBmmJaWUn4A9XzsThOPmBEAAAAASUVORK5CYII="

        const val ICON_NEXT_BASE64 =
            "AAABAAEAICAAAAAAIAAYAgAAFgAAAIlQTkcNChoKAAAADUlIRFIAAAAgAAAAIAgGAAAAc3p69AAAAd9JREFUeJztljFrFUEUhb/79hliFSMqaC0iWFqkEhQFwX8gVmInxMbCxlr7/AA7QbAXCzsrJaDFq/wBKYJYiIXEt/tZvFkzxN2480JeIe/AssMu98yZc+/MHVhiif8ZaqhxVJKROppn8q5xKUmVjcelROr6POL/KFbPqq/Vy12iemJHSexL9Zu6rZ4vSkcmYE2t1e/qU3WlFdFFlsWdVn+6j1tDxHcRrau7GdFEvd3nxoG4r2qTFnC9T8CQ/IwBgV/AFeCt+kK9EBH1IUU6AiJ7d2JogUQS0iQx94FP6mZENBHRpNUVV3tphbarmQLngC31nboREfUm5NgEtGjTUgM3gffqlroWEfUiBMDMiSqJOAFsAtvqg/RvUDqOIiDnmKbxReBq+uYiBNTsF+gHYCMiHiZBx+pAk54K2AUeAdci4mPaksFAB8aFE5tNDPAKeBwROzA7aNLZMJiwxIHW7gqYAHci4m5E7GSNqingA4Y50No9BvaA58CziNhLdhsRU/ir7Zq9ey35l4AGWGXm1BvgSURM0mTVIXs+gJUUP2K2TYcjaypn1C/qvexfZyds49JzUv2cGtiPtp0X3w3UVfVUGzyE4ID4G+ql/PtcKD3f+0QtNDiloprrSrbEEovEby4Ag3zKUJuiAAAAAElFTkSuQmCC"
    }
}