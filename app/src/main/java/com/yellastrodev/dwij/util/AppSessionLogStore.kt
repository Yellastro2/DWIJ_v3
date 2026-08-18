package com.yellastrodev.dwij.util

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.yellastrodev.dwij.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.Writer
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.regex.Pattern
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Сохраняет logcat текущего процесса в ограниченные файлы сессий приложения.
 *
 * Новая сессия начинается с хвоста предыдущей, один файл ограничен пятью мегабайтами,
 * а в хранилище остаются только текущая и предыдущая сессии.
 */
object AppSessionLogStore {
    private const val TAG = "Dwij/AppSessionLogStore"
    private const val LOG_DIR_NAME = "app-session-logs"
    private const val SHARE_DIR_NAME = "log_exports"
    private const val EXPORT_FILE_NAME = "dwij-app-session-logs.zip"
    private const val MAX_LOG_BYTES = 5L * 1024L * 1024L
    private const val KEEP_SESSION_FILES = 2
    private const val PREVIOUS_SESSION_TAIL_LINES = 300
    private const val TRIM_CHECK_INTERVAL_LINES = 128
    private const val LOG_FLUSH_INTERVAL_MS = 5_000L

    private val sessionFileFormat =
        SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    private val threadtimeLine = Pattern.compile(
        "^(\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}.\\d{3})(?:\\s+[0-9A-Za-z]+)?\\s+(\\d+)\\s+(\\d+)\\s+([A-Z])\\s+(.+?)\\s*: (.*)$",
    )

    private val excludedTags =
        setOf(
            "StrictMode",
            "Audio",
            "libMEOW",
            "libMEOW_gift",
            "BufferQueueDebug",
            "BufferQueueConsumer",
            "nativeloader",
            "GraphicsEnvironment",
            "SKIA",
            "HWUI",
            "MIUIInput",
            "MiResource",
            "libc",
            "VideoCapabilities",
            "AudioStreamBuilder",
            "AudioTrackImpl",
            "BLASTBufferQueue",
            "AudioTrack",
            "AAudio",
            "OpenGLRenderer",
            "InputTransport",
            "InsetsSource",
            "AutofillManager",
            "ViewRootImplExtImpl",
            "GPUAUX",
            "CompatibilityChangeReporter",
            "MediaStore",
            "ProfileInstaller",
            "SurfaceFactory",
            "WM-GreedyScheduler",
            "WM-WorkConstraintsTrack",
            "WM-Processor",
            "WM-SystemJobService",
            "WM-SystemJobScheduler",
            "WM-ForceStopRunnable",
            "WM-PackageManagerHelper",
            "os.SingleLiceFactory",
            "os.SingleLice",
            "os.LiceInfo",
            "TranClassInfo",
            "TranAppturboServiceImpl",
            "TranActivityAppturboImpl",
            "TranAppturboPolicyImpl",
            "TranChoreographerImpl",
            "ActivityThreadLice",
            "OSServiceManager",
            "MSYNC3-VariableRefreshRate",
            "AppTurboPolicyImpl-TranBaseWrapper",
            "PowerHalWrapper",
            "ScrollIdentify",
            "TranFlingManagerImpl",
            "config_debug",
            "libPerfCtl",
            "QT",
            "FBI",
            "mali",
            "hw-ProcessState",
            "binder_sample",
        ).mapTo(
            mutableSetOf(),
        ) { tag ->
            tag.lowercase(
                Locale.US,
            )
        }

    private val excludedTagPrefixes =
        listOf(
            "wm_on_",
        )

    private val fileLock = Any()
    private val pendingLogLines = StringBuilder()

    private var recorderJob: Job? = null
    private var activeWriter: Writer? = null
    private var currentSessionHeader = ""

    /** Запускает запись новой сессии и не создаёт второй reader при повторном вызове. */
    fun start(
        context: Context,
        scope: CoroutineScope,
    ) {
        if (recorderJob != null) {
            Log.d(TAG, "[start] Запись сессии приложения уже запущена")
            return
        }

        val appContext = context.applicationContext
        recorderJob = scope.launch(Dispatchers.IO) {
            val previousSessionFile = latestSessionFiles(appContext).firstOrNull()
            val logFile = createSessionFile(appContext)
            currentSessionHeader = buildSessionHeader(appContext)
            appendPreviousSessionTail(logFile, previousSessionFile)
            logFile.appendText(currentSessionHeader, StandardCharsets.UTF_8)
            pruneOldSessionFiles(appContext)
            Log.i(TAG, "[start] Старт сессии приложения, файл=${logFile.name}")
            streamCurrentProcessLogcat(logFile)
        }
    }

    /** Создаёт согласованный ZIP-снимок двух последних сессий в cache для FileProvider. */
    suspend fun createShareArchive(context: Context): File = withContext(Dispatchers.IO) {
        synchronized(fileLock) {
            flushPendingLogLinesLocked()
            val files = latestSessionFiles(context.applicationContext)
            if (files.isEmpty()) {
                throw IllegalStateException("Файлы сессий приложения ещё не созданы")
            }

            val exportDirectory = File(context.cacheDir, SHARE_DIR_NAME)
            if (!exportDirectory.isDirectory && !exportDirectory.mkdirs()) {
                throw IllegalStateException(
                    "Не удалось создать каталог экспорта журналов: ${exportDirectory.absolutePath}",
                )
            }
            exportDirectory.listFiles()?.forEach { oldFile ->
                if (oldFile.isFile && !oldFile.delete()) {
                    Log.w(TAG, "[createShareArchive] Не удалось удалить старый архив ${oldFile.name}")
                }
            }

            val archive = File(exportDirectory, EXPORT_FILE_NAME)
            try {
                FileOutputStream(archive).use { output ->
                    writeZip(files, output)
                }
            } catch (error: Throwable) {
                archive.delete()
                throw error
            }
            archive
        }
    }

    /** Возвращает внутренний каталог журналов приложения. */
    private fun logDirectory(context: Context): File =
        File(context.filesDir, LOG_DIR_NAME)

    /** Создаёт уникальный файл текущей сессии с временной меткой в имени. */
    private fun createSessionFile(context: Context): File {
        val directory = logDirectory(context)
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IllegalStateException("Не удалось создать каталог журналов: ${directory.absolutePath}")
        }

        val baseName = synchronized(sessionFileFormat) {
            sessionFileFormat.format(Date())
        }
        var candidate = File(directory, "$baseName.log")
        var suffix = 1
        while (candidate.exists()) {
            candidate = File(directory, "$baseName-$suffix.log")
            suffix += 1
        }
        return candidate
    }

    /** Собирает диагностический заголовок сессии, включая ANDROID_ID устройства. */
    private fun buildSessionHeader(context: Context): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID,
        )?.trim().orEmpty()

        return buildString {
            appendLine("[appSession] deviceId=$androidId")
            appendLine("[appSession] Старт сессии приложения")
            appendLine("[appSession] sessionId=${UUID.randomUUID()}")
            appendLine("[appSession] startedAt=${Date()}")
            appendLine("[appSession] appVersion=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("[appSession] androidSdk=${Build.VERSION.SDK_INT}")
            appendLine("[appSession] manufacturer=${Build.MANUFACTURER}")
            appendLine("[appSession] model=${Build.MODEL}")
            appendLine("[appSession] device=${Build.DEVICE}")
            appendLine()
        }
    }

    /** Дописывает хвост предыдущей сессии и разделитель перезапуска. */
    private fun appendPreviousSessionTail(logFile: File, previousSessionFile: File?) {
        if (previousSessionFile == null || !previousSessionFile.isFile) return

        try {
            val previousTailLines = tailLines(previousSessionFile, PREVIOUS_SESSION_TAIL_LINES)
            logFile.bufferedWriter(StandardCharsets.UTF_8, 8192).use { writer ->
                writer.appendLine(
                    "[appSession] Хвост предыдущей сессии: файл=${previousSessionFile.name}, строк=${previousTailLines.size}",
                )
                previousTailLines.forEach { line ->
                    writer.appendLine(line)
                }
                writer.appendLine("[appSession] был перезапуск приложения, ниже начинается новая сессия")
                writer.newLine()
            }
        } catch (error: Throwable) {
            Log.w(
                TAG,
                "[appendPreviousSessionTail] Не удалось добавить хвост предыдущей сессии ${previousSessionFile.name}",
                error,
            )
        }
    }

    /** Возвращает последние строки файла без загрузки всего файла в память. */
    private fun tailLines(file: File, lineLimit: Int): List<String> {
        if (lineLimit <= 0) return emptyList()
        val lines = ArrayDeque<String>(lineLimit)
        file.bufferedReader(StandardCharsets.UTF_8).useLines { sequence ->
            sequence.forEach { line ->
                if (lines.size == lineLimit) lines.removeFirst()
                lines.addLast(line)
            }
        }
        return lines.toList()
    }

    /** Читает logcat текущего PID, сохраняет stacktrace и периодически сбрасывает буфер на диск. */
    private suspend fun streamCurrentProcessLogcat(logFile: File) {
        val currentPid = Process.myPid().toString()
        val builder = ProcessBuilder()
            .command("logcat", "--pid", currentPid, "-T", "1", "-b", "all", "-v", "threadtime", "*:V")
            .redirectErrorStream(true)
        builder.environment()["LC_ALL"] = "C"

        var process: java.lang.Process? = null
        var lastFlushAtMs = SystemClock.elapsedRealtime()
        var linesSinceTrimCheck = 0
        var previousLineIncluded = false

        try {
            synchronized(fileLock) {
                activeWriter = FileOutputStream(logFile, true).bufferedWriter(StandardCharsets.UTF_8)
            }
            process = builder.start()
            BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8)).use { stdout ->
                while (currentCoroutineContext().isActive) {
                    val line = stdout.readLine() ?: break
                    val shouldInclude = shouldIncludeLine(line, currentPid, previousLineIncluded)
                    previousLineIncluded = shouldInclude
                    if (!shouldInclude) continue

                    synchronized(fileLock) {
                        pendingLogLines.append(line).append('\n')
                    }
                    linesSinceTrimCheck += 1

                    val nowMs = SystemClock.elapsedRealtime()
                    if (nowMs - lastFlushAtMs >= LOG_FLUSH_INTERVAL_MS) {
                        synchronized(fileLock) {
                            flushPendingLogLinesLocked()
                        }
                        lastFlushAtMs = nowMs

                        if (linesSinceTrimCheck >= TRIM_CHECK_INTERVAL_LINES) {
                            linesSinceTrimCheck = 0
                            if (logFile.length() > MAX_LOG_BYTES) {
                                synchronized(fileLock) {
                                    activeWriter?.close()
                                    activeWriter = null
                                    trimLogStart(logFile, currentSessionHeader)
                                    activeWriter = FileOutputStream(logFile, true)
                                        .bufferedWriter(StandardCharsets.UTF_8)
                                }
                                Log.i(
                                    TAG,
                                    "[streamCurrentProcessLogcat] Журнал обрезан до второй половины файла=${logFile.name}",
                                )
                            }
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            Log.e(TAG, "[streamCurrentProcessLogcat] Не удалось вести журнал сессии приложения", error)
        } finally {
            synchronized(fileLock) {
                runCatching {
                    flushPendingLogLinesLocked()
                    activeWriter?.close()
                }
                activeWriter = null
            }
            process?.destroy()
        }
    }

    /** Сбрасывает общий буфер в активный файл; вызывается только под [fileLock]. */
    private fun flushPendingLogLinesLocked() {
        if (pendingLogLines.isEmpty()) {
            activeWriter?.flush()
            return
        }
        val writer = activeWriter ?: return
        writer.write(pendingLogLines.toString())
        writer.flush()
        pendingLogLines.clear()
    }

    /**
     * Проверяет принадлежность строки текущему PID и отбрасывает системные теги без учёта регистра.
     * Нераспознанные строки сохраняются только как продолжение включённого stacktrace.
     */
    private fun shouldIncludeLine(
        line: String,
        currentPid: String,
        previousLineIncluded: Boolean,
    ): Boolean {
        val matcher = threadtimeLine.matcher(line)
        if (!matcher.matches()) return previousLineIncluded
        val pid = matcher.group(2)
        val normalizedTag =
            matcher.group(
                5,
            )
                ?.trim()
                ?.lowercase(
                    Locale.US,
                )

        return pid == currentPid &&
            normalizedTag != null &&
            normalizedTag !in excludedTags &&
            excludedTagPrefixes.none { prefix ->
                normalizedTag.startsWith(
                    prefix,
                )
            }
    }

    /** Оставляет вторую половину переполненного файла, начиная с полной строки. */
    private fun trimLogStart(file: File, sessionHeader: String) {
        val length = file.length()
        if (length <= MAX_LOG_BYTES) return

        val keepSize = (length / 2L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val bytes = ByteArray(keepSize)
        file.inputStream().use { input ->
            input.skip(length - keepSize)
            input.read(bytes)
        }
        val firstLineStart = bytes.indexOfFirst { it == '\n'.code.toByte() }
            .takeIf { it >= 0 }
            ?.plus(1)
            ?: 0
        val trimHeader = buildString {
            appendLine("[appSession] Начало журнала обрезано после превышения 5 МБ")
            append(sessionHeader)
        }.toByteArray(StandardCharsets.UTF_8)
        file.outputStream().use { output ->
            output.write(trimHeader)
            output.write(bytes, firstLineStart, bytes.size - firstLineStart)
        }
    }

    /** Удаляет старые сессии, оставляя два последних файла. */
    private fun pruneOldSessionFiles(context: Context) {
        latestSessionFiles(context).drop(KEEP_SESSION_FILES).forEach { file ->
            if (file.delete()) {
                Log.i(TAG, "[pruneOldSessionFiles] Удалён старый файл журнала ${file.name}")
            }
        }
    }

    /** Возвращает файлы сессий от новых к старым. */
    private fun latestSessionFiles(context: Context): List<File> =
        logDirectory(context)
            .listFiles { file -> file.isFile && file.extension == "log" }
            .orEmpty()
            .sortedByDescending { file ->
                file.name
            }

    /** Упаковывает переданные файлы сессий в ZIP-поток. */
    private fun writeZip(files: List<File>, output: java.io.OutputStream) {
        ZipOutputStream(output).use { zip ->
            files.forEach { file ->
                zip.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }
}
