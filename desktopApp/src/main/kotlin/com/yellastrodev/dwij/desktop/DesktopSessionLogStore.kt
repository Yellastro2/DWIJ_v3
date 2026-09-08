package com.yellastrodev.dwij.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.RandomAccessFile
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Хранит ограниченные журналы Windows-сессий и формирует диагностический ZIP.
 *
 * Запись синхронная и сбрасывается после каждого сообщения, чтобы последние строки
 * сохранились даже при аварийном завершении процесса.
 */
class DesktopSessionLogStore private constructor(
    private val logDirectory: File,
    private val currentLogFile: File,
    private val currentSessionHeader: String,
) : AutoCloseable {

    private val lock = Any()
    private val previousUncaughtExceptionHandler =
        Thread.getDefaultUncaughtExceptionHandler()

    private val uncaughtExceptionHandler =
        Thread.UncaughtExceptionHandler { thread, error ->
            write(
                level = "E",
                tag = "UncaughtException",
                message =
                    "[uncaughtException] Необработанная ошибка в потоке ${thread.name}",
                cause = error,
            )
            previousUncaughtExceptionHandler
                ?.uncaughtException(
                    thread,
                    error,
                )
        }

    private var writer: BufferedWriter =
        FileOutputStream(
            currentLogFile,
            true,
        ).bufferedWriter(
            StandardCharsets.UTF_8,
        )

    private var isClosed = false
    private var persistenceFailure: Throwable? = null

    init {
        Thread.setDefaultUncaughtExceptionHandler(
            uncaughtExceptionHandler,
        )
    }

    /** Печатает сообщение в консоль и сохраняет его в текущую desktop-сессию. */
    fun write(
        level: String,
        tag: String,
        message: String,
        cause: Throwable? = null,
    ) {
        val line =
            "${LocalDateTime.now().format(LOG_LINE_TIME_FORMAT)} $level/$tag: $message"

        val console =
            if (level == "E") {
                System.err
            } else {
                System.out
            }

        console.println(
            line,
        )
        cause?.printStackTrace(
            console,
        )

        synchronized(lock) {
            if (
                isClosed ||
                persistenceFailure != null
            ) {
                return
            }

            try {
                writer.appendLine(
                    line,
                )

                cause?.let { error ->
                    writer.write(
                        error.stackTraceText(),
                    )
                }

                writer.flush()

                if (currentLogFile.length() > MAX_LOG_BYTES) {
                    writer.close()
                    trimLogStart(
                        currentLogFile,
                        currentSessionHeader,
                    )
                    writer =
                        FileOutputStream(
                            currentLogFile,
                            true,
                        ).bufferedWriter(
                            StandardCharsets.UTF_8,
                        )
                }
            } catch (error: Throwable) {
                persistenceFailure =
                    error
                runCatching {
                    writer.close()
                }
                System.err.println(
                    "[appSession] [write] Файловый журнал отключён после ошибки записи",
                )
                error.printStackTrace(
                    System.err,
                )
            }
        }
    }

    /** Атомарно записывает две последние desktop-сессии в выбранный ZIP-файл. */
    suspend fun exportArchive(
        targetFile: File,
    ) {
        withContext(
            Dispatchers.IO,
        ) {
            synchronized(lock) {
                check(!isClosed) {
                    "Хранилище журналов уже закрыто"
                }
                check(persistenceFailure == null) {
                    "Файловый журнал недоступен после ошибки записи"
                }

                writer.flush()

                val files =
                    latestSessionFiles(
                        logDirectory,
                    )

                check(files.isNotEmpty()) {
                    "Файлы desktop-сессий ещё не созданы"
                }

                val absoluteTargetFile =
                    targetFile.absoluteFile

                val targetDirectory =
                    checkNotNull(
                        absoluteTargetFile.parentFile,
                    ) {
                        "Не удалось определить каталог архива"
                    }

                check(
                    targetDirectory.isDirectory ||
                        targetDirectory.mkdirs()
                ) {
                    "Не удалось создать каталог архива: ${targetDirectory.absolutePath}"
                }

                val temporaryFile =
                    File(
                        targetDirectory,
                        ".${absoluteTargetFile.name}.${UUID.randomUUID()}.tmp",
                    )

                try {
                    FileOutputStream(
                        temporaryFile,
                    ).use { output ->
                        writeZip(
                            files,
                            output,
                        )
                    }

                    moveAtomically(
                        source =
                            temporaryFile,
                        target =
                            absoluteTargetFile,
                    )
                } catch (error: Throwable) {
                    temporaryFile.delete()
                    throw error
                }
            }
        }
    }

    /** Восстанавливает обработчик ошибок процесса и закрывает текущий файл журнала. */
    override fun close() {
        synchronized(lock) {
            if (isClosed) {
                return
            }

            isClosed = true
            runCatching {
                writer.flush()
                writer.close()
            }.onFailure { error ->
                System.err.println(
                    "[appSession] [close] Не удалось закрыть файл журнала",
                )
                error.printStackTrace(
                    System.err,
                )
            }

            if (
                Thread.getDefaultUncaughtExceptionHandler() ===
                uncaughtExceptionHandler
            ) {
                Thread.setDefaultUncaughtExceptionHandler(
                    previousUncaughtExceptionHandler,
                )
            }
        }
    }

    companion object {
        private const val LOG_DIR_NAME =
            "logs"
        private const val INSTALLATION_ID_KEY =
            "desktop.diagnostic.installation.id"
        private const val MAX_LOG_BYTES =
            5L * 1024L * 1024L
        private const val PREVIOUS_SESSION_TAIL_LINES =
            300

        private val SESSION_FILE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern(
                "yyyy-MM-dd_HH-mm-ss",
            )

        private val LOG_LINE_TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern(
                "MM-dd HH:mm:ss.SSS",
            )

        /** Создаёт новую desktop-сессию и добавляет в неё хвост предыдущего запуска. */
        fun start(
            paths: DesktopPaths,
            settingsStore: DesktopLocalKeyValueStore,
        ): DesktopSessionLogStore {
            val logDirectory =
                File(
                    paths.cacheDirectory.parentFile,
                    LOG_DIR_NAME,
                ).apply {
                    check(
                        isDirectory ||
                            mkdirs()
                    ) {
                        "Не удалось создать каталог журналов: $absolutePath"
                    }
                }

            val previousSessionFile =
                latestSessionFiles(
                    logDirectory,
                ).firstOrNull()

            val currentLogFile =
                createSessionFile(
                    logDirectory,
                )

            val installationId =
                settingsStore
                    .getString(
                        INSTALLATION_ID_KEY,
                    )
                    ?.takeIf(
                        String::isNotBlank,
                    )
                    ?: UUID.randomUUID()
                        .toString()
                        .also { generatedId ->
                            settingsStore.edit {
                                putString(
                                    INSTALLATION_ID_KEY,
                                    generatedId,
                                )
                            }
                        }

            val sessionHeader =
                buildSessionHeader(
                    installationId,
                )

            appendPreviousSessionTail(
                logFile =
                    currentLogFile,
                previousSessionFile =
                    previousSessionFile,
            )

            currentLogFile.appendText(
                sessionHeader,
                StandardCharsets.UTF_8,
            )

            pruneOldSessionFiles(
                logDirectory =
                    logDirectory,
                currentLogFile =
                    currentLogFile,
                previousSessionFile =
                    previousSessionFile,
            )

            return DesktopSessionLogStore(
                logDirectory =
                    logDirectory,
                currentLogFile =
                    currentLogFile,
                currentSessionHeader =
                    sessionHeader,
            )
        }

        /** Создаёт уникальное имя файла сессии по локальному времени запуска. */
        private fun createSessionFile(
            logDirectory: File,
        ): File {
            val baseName =
                LocalDateTime.now()
                    .format(
                        SESSION_FILE_FORMAT,
                    )

            var candidate =
                File(
                    logDirectory,
                    "$baseName.log",
                )

            var suffix = 1

            while (candidate.exists()) {
                candidate =
                    File(
                        logDirectory,
                        "$baseName-$suffix.log",
                    )
                suffix += 1
            }

            return candidate
        }

        /** Собирает заголовок сессии без пользовательских имён и путей. */
        private fun buildSessionHeader(
            installationId: String,
        ): String =
            buildString {
                appendLine("[appSession] installationId=$installationId")
                appendLine("[appSession] Старт Windows-сессии приложения")
                appendLine("[appSession] sessionId=${UUID.randomUUID()}")
                appendLine("[appSession] startedAt=${LocalDateTime.now()}")
                appendLine(
                    "[appSession] appVersion=${System.getProperty("dwij.app.version", "unknown")}",
                )
                appendLine("[appSession] osName=${System.getProperty("os.name", "unknown")}")
                appendLine("[appSession] osVersion=${System.getProperty("os.version", "unknown")}")
                appendLine("[appSession] osArch=${System.getProperty("os.arch", "unknown")}")
                appendLine("[appSession] javaVersion=${System.getProperty("java.version", "unknown")}")
                appendLine()
            }

        /** Добавляет последние строки предыдущего запуска и явный разделитель сессий. */
        private fun appendPreviousSessionTail(
            logFile: File,
            previousSessionFile: File?,
        ) {
            if (
                previousSessionFile == null ||
                !previousSessionFile.isFile
            ) {
                return
            }

            val previousTailLines =
                tailLines(
                    previousSessionFile,
                    PREVIOUS_SESSION_TAIL_LINES,
                )

            logFile
                .bufferedWriter(
                    StandardCharsets.UTF_8,
                    8192,
                )
                .use { writer ->
                    writer.appendLine(
                        "[appSession] Хвост предыдущей сессии: файл=${previousSessionFile.name}, строк=${previousTailLines.size}",
                    )
                    previousTailLines.forEach { line ->
                        writer.appendLine(
                            line,
                        )
                    }
                    writer.appendLine(
                        "[appSession] был перезапуск приложения, ниже начинается новая сессия",
                    )
                    writer.newLine()
                }
        }

        /** Читает последние строки файла без загрузки полного журнала в память. */
        private fun tailLines(
            file: File,
            lineLimit: Int,
        ): List<String> {
            val lines =
                ArrayDeque<String>(
                    lineLimit,
                )

            file.bufferedReader(
                StandardCharsets.UTF_8,
            ).useLines { sequence ->
                sequence.forEach { line ->
                    if (lines.size == lineLimit) {
                        lines.removeFirst()
                    }
                    lines.addLast(
                        line,
                    )
                }
            }

            return lines.toList()
        }

        /** Оставляет вторую половину журнала, начиная с ближайшей полной строки. */
        private fun trimLogStart(
            file: File,
            sessionHeader: String,
        ) {
            val length =
                file.length()

            if (length <= MAX_LOG_BYTES) {
                return
            }

            val keepSize =
                (length / 2L)
                    .coerceAtMost(
                        Int.MAX_VALUE.toLong(),
                    )
                    .toInt()

            val bytes =
                ByteArray(
                    keepSize,
                )

            RandomAccessFile(
                file,
                "r",
            ).use { input ->
                input.seek(
                    length - keepSize,
                )
                input.readFully(
                    bytes,
                )
            }

            val firstLineStart =
                bytes.indexOfFirst { byte ->
                    byte == '\n'.code.toByte()
                }
                    .takeIf { index ->
                        index >= 0
                    }
                    ?.plus(
                        1,
                    )
                    ?: 0

            val trimHeader =
                buildString {
                    appendLine(
                        "[appSession] Начало журнала обрезано после превышения 5 МБ",
                    )
                    append(
                        sessionHeader,
                    )
                }.toByteArray(
                    StandardCharsets.UTF_8,
                )

            file.outputStream()
                .use { output ->
                    output.write(
                        trimHeader,
                    )
                    output.write(
                        bytes,
                        firstLineStart,
                        bytes.size - firstLineStart,
                    )
                }
        }

        /** Удаляет старые файлы, оставляя текущую и предыдущую desktop-сессии. */
        private fun pruneOldSessionFiles(
            logDirectory: File,
            currentLogFile: File,
            previousSessionFile: File?,
        ) {
            latestSessionFiles(
                logDirectory,
            )
                .filterNot { file ->
                    file == currentLogFile ||
                        file == previousSessionFile
                }
                .forEach(
                    File::delete,
                )
        }

        /** Возвращает файлы сессий от новых к старым. */
        private fun latestSessionFiles(
            logDirectory: File,
        ): List<File> =
            logDirectory
                .listFiles { file ->
                    file.isFile &&
                        file.extension.equals(
                            "log",
                            ignoreCase =
                                true,
                        )
                }
                .orEmpty()
                .sortedWith(
                    compareByDescending<File> { file ->
                        file.lastModified()
                    }.thenByDescending { file ->
                        file.name
                    },
                )

        /** Записывает файлы desktop-сессий в ZIP без раскрытия абсолютных путей. */
        private fun writeZip(
            files: List<File>,
            output: java.io.OutputStream,
        ) {
            ZipOutputStream(
                output,
            ).use { zip ->
                files.forEach { file ->
                    zip.putNextEntry(
                        ZipEntry(
                            file.name,
                        ),
                    )
                    file.inputStream()
                        .use { input ->
                            input.copyTo(
                                zip,
                            )
                        }
                    zip.closeEntry()
                }
            }
        }

        /** Перемещает архив атомарно, не перезаписывая существующий файл. */
        private fun moveAtomically(
            source: File,
            target: File,
        ) {
            runCatching {
                Files.move(
                    source.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                )
            }.recoverCatching {
                Files.move(
                    source.toPath(),
                    target.toPath(),
                )
            }.getOrThrow()
        }

        /** Преобразует stacktrace в UTF-8-совместимый текст для файла сессии. */
        private fun Throwable.stackTraceText(): String {
            val output =
                StringWriter()

            PrintWriter(
                output,
            ).use { writer ->
                printStackTrace(
                    writer,
                )
            }

            return output.toString()
        }
    }
}
