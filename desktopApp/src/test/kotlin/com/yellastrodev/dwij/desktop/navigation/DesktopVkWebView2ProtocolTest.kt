package com.yellastrodev.dwij.desktop.navigation

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

/** Проверяет приватный протокол передачи сессии и отсутствие секретов в ошибках разбора. */
class DesktopVkWebView2ProtocolTest {
    /** Cookies сохраняют символы '=' внутри значения и не печатаются через toString объекта SDK. */
    @Test fun receivesSessionWithoutLosingCookieValue() {
        val message = parseVkWebView2Message(
            """{"type":"session","p":"synthetic=p","remixsid":"synthetic-sid","userAgent":"test-browser"}""")
            as VkWebView2Message.Session
        assertEquals("synthetic=p", message.value.p)
        assertEquals("synthetic-sid", message.value.remixsid)
        assertEquals("test-browser", message.value.userAgent)
        assertFalse(message.value.toString().contains("synthetic"))
    }

    /** Некорректные ответы и cookie-инъекции дают одинаковую безопасную ошибку без исходного JSON. */
    @Test fun invalidMessagesDoNotExposeSecrets() {
        for (value in listOf(
            "synthetic-secret",
            """{"type":"session","p":"synthetic-secret","remixsid":"sid"}""",
            """{"type":"session","p":"synthetic-secret; other=1","remixsid":"sid","userAgent":"ua"}""",
        )) {
            val error = assertThrows(IOException::class.java) { parseVkWebView2Message(value) }
            assertFalse(error.message.orEmpty().contains("synthetic-secret"))
        }
    }

    /** Закрытие окна и отсутствие Runtime отличаются от отклонённой VK-авторизации. */
    @Test fun recognizesHostLifecycleMessages() {
        assertSame(VkWebView2Message.Closed, parseVkWebView2Message("""{"type":"closed"}"""))
        val error = parseVkWebView2Message("""{"type":"error","code":"runtime_missing"}""") as VkWebView2Message.Error
        assertEquals("runtime_missing", error.code)
    }
}
