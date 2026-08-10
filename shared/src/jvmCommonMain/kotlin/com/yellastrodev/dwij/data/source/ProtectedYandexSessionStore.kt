package com.yellastrodev.dwij.storage

import com.yellastrodev.dwij.auth.YandexSession
import com.yellastrodev.dwij.auth.YandexSessionStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

/**
 * Платформенное хранилище защищённого бинарного payload авторизации.
 *
 * Android-реализация шифрует payload ключом из Android Keystore, а Windows-
 * реализация передаёт его в DPAPI. Открытый текст не должен записываться на диск.
 */
interface ProtectedSessionPayloadStore {

    fun read(): ByteArray?

    fun write(payload: ByteArray)

    fun clear()
}

/**
 * Кодирует [YandexSession] в версионированный бинарный формат и передаёт его
 * платформенному защищённому хранилищу.
 */
class ProtectedYandexSessionStore(
    private val payloadStore: ProtectedSessionPayloadStore,
) : YandexSessionStore {

    override fun read(): YandexSession? =
        payloadStore
            .read()
            ?.let(::decode)

    override fun write(
        session: YandexSession,
    ) {
        require(
            session.accessToken.isNotBlank(),
        ) {
            "Нельзя сохранить сессию с пустым access token"
        }

        payloadStore.write(
            encode(session),
        )
    }

    override fun clear() {
        payloadStore.clear()
    }

    private fun encode(
        session: YandexSession,
    ): ByteArray =
        ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(FORMAT_MAGIC)
                output.writeInt(FORMAT_VERSION)
                output.writeString(session.accessToken)
                output.writeNullableString(session.refreshToken)
                output.writeNullableLong(session.expiresAtMillis)
                output.writeNullableString(session.login)
                output.writeNullableString(session.userId)
            }

            buffer.toByteArray()
        }

    private fun decode(
        payload: ByteArray,
    ): YandexSession =
        DataInputStream(
            ByteArrayInputStream(payload),
        ).use { input ->
            require(input.readInt() == FORMAT_MAGIC) {
                "Неизвестный формат защищённой сессии"
            }

            require(input.readInt() == FORMAT_VERSION) {
                "Неподдерживаемая версия защищённой сессии"
            }

            val session =
                YandexSession(
                    accessToken =
                        input.readString(),
                    refreshToken =
                        input.readNullableString(),
                    expiresAtMillis =
                        input.readNullableLong(),
                    login =
                        input.readNullableString(),
                    userId =
                        input.readNullableString(),
                )

            require(session.accessToken.isNotBlank()) {
                "Защищённая сессия содержит пустой access token"
            }

            require(input.available() == 0) {
                "Защищённая сессия содержит лишние данные"
            }

            session
        }

    private fun DataOutputStream.writeString(
        value: String,
    ) {
        val bytes =
            value.toByteArray(
                StandardCharsets.UTF_8,
            )

        require(bytes.size <= MAX_STRING_BYTES) {
            "Поле защищённой сессии слишком длинное"
        }

        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataOutputStream.writeNullableString(
        value: String?,
    ) {
        writeBoolean(value != null)
        if (value != null) {
            writeString(value)
        }
    }

    private fun DataOutputStream.writeNullableLong(
        value: Long?,
    ) {
        writeBoolean(value != null)
        if (value != null) {
            writeLong(value)
        }
    }

    private fun DataInputStream.readString(): String {
        val size = readInt()

        require(size in 0..MAX_STRING_BYTES) {
            "Некорректная длина поля защищённой сессии"
        }

        val bytes = ByteArray(size)
        readFully(bytes)

        return String(
            bytes,
            StandardCharsets.UTF_8,
        )
    }

    private fun DataInputStream.readNullableString(): String? =
        if (readBoolean()) {
            readString()
        } else {
            null
        }

    private fun DataInputStream.readNullableLong(): Long? =
        if (readBoolean()) {
            readLong()
        } else {
            null
        }

    private companion object {
        const val FORMAT_MAGIC =
            0x4457494A

        const val FORMAT_VERSION =
            1

        const val MAX_STRING_BYTES =
            1024 * 1024
    }
}
