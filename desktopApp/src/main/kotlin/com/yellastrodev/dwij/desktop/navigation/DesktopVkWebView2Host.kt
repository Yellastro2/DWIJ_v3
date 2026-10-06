package com.yellastrodev.dwij.desktop.navigation

import com.yellastrodev.dwij.desktop.DesktopPaths
import com.yellastrodev.vkmusicsdk.VkWebSession
import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.*

/** Приватный IPC WebView2 для входа и явного сброса профиля; секреты и stderr не попадают в логи. */
internal class DesktopVkWebView2Host(private val paths: DesktopPaths) : Closeable {
    private val process = AtomicReference<Process?>()
    private val closed = AtomicBoolean(false)
    private val writeLock = Any()
    private var reader: BufferedReader? = null

    /** Извлекает упакованный host и запускает отдельное окно с постоянным профилем в LocalAppData. */
    fun start() {
        if (closed.get()) return
        val helper = extractHelper()
        val profile = File(paths.cacheDirectory.parentFile, "vk-webview2-profile").absoluteFile
        val started = ProcessBuilder(helper.absolutePath, "--user-data", profile.absolutePath)
            .directory(helper.parentFile).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        process.set(started)
        if (closed.get()) {
            if (process.compareAndSet(started, null)) stop(started)
            return
        }
        reader = started.inputStream.bufferedReader(Charsets.UTF_8)
    }

    /** Вызывается на IO: очищает профиль через WebView2 API, не удаляя каталог и сохранённый SDK payload. */
    fun resetBrowserSession() {
        check(!closed.get())
        val profile = File(paths.cacheDirectory.parentFile, "vk-webview2-profile").absoluteFile
        if (!profile.isDirectory) return
        val helper = extractHelper()
        val active = ProcessBuilder(helper.absolutePath, "--reset", "--user-data", profile.absolutePath)
            .directory(helper.parentFile).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        process.set(active)
        if (!active.waitFor(30, TimeUnit.SECONDS)) throw IOException("Сброс браузера VK превысил время ожидания")
        val success = runCatching {
            val line = active.inputStream.bufferedReader(Charsets.UTF_8).readLine() ?: return@runCatching false
            if (line.length > 32_768) return@runCatching false
            val response = Json.parseToJsonElement(line).jsonObject
            active.exitValue() == 0 && response["type"]?.jsonPrimitive?.content == "reset"
        }.getOrDefault(false)
        if (!success) throw IOException("Не удалось сбросить браузер VK. Закройте окно входа и повторите")
    }

    /** Читает один ответ; содержимое JSON и исключения разбора никогда не передаются диагностическому logger. */
    fun readMessage(): VkWebView2Message? = reader?.readLine()?.let(::parseVkWebView2Message)

    /** Передаёт безопасное состояние проверки в окно; секретов в обратном направлении нет. */
    fun updateState(busy: Boolean, error: String?) = send(buildJsonObject {
        put("type", "state")
        put("busy", busy)
        error?.let { put("error", it) }
    })

    /** Пишет одну команду в stdin host; закрытый pipe при завершении входа не считается сетевой ошибкой. */
    private fun send(message: JsonObject) = synchronized(writeLock) {
        val active = process.get() ?: return@synchronized
        runCatching {
            active.outputStream.write((message.toString() + "\n").toByteArray(Charsets.UTF_8))
            active.outputStream.flush()
        }
        Unit
    }

    /** Закрывает окно при выходе из карточки/приложения, не блокируя Compose UI ожиданием процесса. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        send(buildJsonObject { put("type", "close") })
        process.getAndSet(null)?.let { stop(it) }
    }

    /** Даёт WinForms освободить браузер; зависший host завершается после ограниченного ожидания. */
    private fun stop(active: Process) {
        Thread({
            runCatching { active.outputStream.close() }
            runCatching {
                if (!active.waitFor(2, TimeUnit.SECONDS)) {
                    active.destroy()
                    if (!active.waitFor(1, TimeUnit.SECONDS)) active.destroyForcibly()
                }
            }
            runCatching { active.inputStream.close() }
        }, "dwij-vk-webview2-close").apply { isDaemon = true }.start()
    }

    /** Извлекает SDK-файлы рядом с host; digest-каталог не перезаписывает работающий EXE другой сборки. */
    private fun extractHelper(): File = synchronized(extractionLock) {
        val files = helperFiles.associateWith { name ->
            javaClass.getResourceAsStream("/windows-webview2/$name")?.use { it.readBytes() }
                ?: throw IOException("Компонент входа VK отсутствует в сборке. Пересоберите desktop-приложение")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        files.forEach { (name, bytes) -> digest.update(name.toByteArray(Charsets.UTF_8)); digest.update(bytes) }
        val version = digest.digest().take(12).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val directory = File(paths.cacheDirectory.parentFile, "vk-webview2-host/$version")
        check(directory.isDirectory || directory.mkdirs()) { "Не удалось подготовить компонент входа VK" }
        files.forEach { (name, bytes) ->
            val target = File(directory, name)
            if (!target.isFile || !target.readBytes().contentEquals(bytes)) {
                val temporary = Files.createTempFile(directory.toPath(), "vk-host-", ".tmp")
                try {
                    Files.write(temporary, bytes)
                    Files.move(temporary, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } finally { Files.deleteIfExists(temporary) }
            }
        }
        File(directory, "Dwij.VkWebView2.exe")
    }

    private companion object {
        private val extractionLock = Any()
        private val helperFiles = listOf("Dwij.VkWebView2.exe", "Dwij.VkWebView2.exe.config",
            "Microsoft.Web.WebView2.Core.dll", "Microsoft.Web.WebView2.WinForms.dll", "WebView2Loader.dll")
    }
}

/** Секреты сессии остаются в объекте SDK, ошибки host имеют только заранее известный код. */
internal sealed interface VkWebView2Message {
    /** Cookies передаются только напрямую в общий репозиторий, без saved state. */
    class Session(val value: VkWebSession) : VkWebView2Message
    /** Ошибка запуска/Runtime содержит безопасный машинный код, а не Exception.Message браузера. */
    class Error(val code: String) : VkWebView2Message
    /** Пользователь закрыл отдельное окно входа. */
    data object Closed : VkWebView2Message
}

/** Проверяет протокол host; повреждённый JSON не раскрывается через сообщение исключения. */
internal fun parseVkWebView2Message(line: String): VkWebView2Message = try {
    require(line.length <= 32_768)
    val root = Json.parseToJsonElement(line).jsonObject
    when (root.getValue("type").jsonPrimitive.content) {
        "session" -> VkWebView2Message.Session(VkWebSession(
            p = root.getValue("p").jsonPrimitive.content,
            remixsid = root.getValue("remixsid").jsonPrimitive.content,
            userAgent = root.getValue("userAgent").jsonPrimitive.content,
        ))
        "error" -> VkWebView2Message.Error(root["code"]?.jsonPrimitive?.contentOrNull.orEmpty())
        "closed" -> VkWebView2Message.Closed
        else -> throw IOException("Неизвестный ответ компонента входа VK")
    }
} catch (_: Exception) { throw IOException("Не удалось прочитать ответ компонента входа VK") }
