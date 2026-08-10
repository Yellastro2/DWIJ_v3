package com.yellastrodev.dwij.desktop

import com.sun.jna.platform.win32.Crypt32Util
import com.yellastrodev.dwij.storage.ProtectedSessionPayloadStore
import com.yellastrodev.yamusicsdk.YamLogger
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Защищает payload авторизации через Windows DPAPI в области текущего
 * пользователя и сохраняет ciphertext отдельным атомарно заменяемым файлом.
 */
class WindowsDpapiSessionPayloadStore(
    private val file: File,
    private val logger: YamLogger,
) : ProtectedSessionPayloadStore {

    private val lock = Any()

    override fun read(): ByteArray? =
        synchronized(lock) {
            if (!file.isFile) {
                return@synchronized null
            }

            try {
                Crypt32Util.cryptUnprotectData(
                    file.readBytes(),
                )
            } catch (error: Exception) {
                logger.error(
                    TAG,
                    "[read] Не удалось расшифровать сохранённую авторизацию",
                    error,
                )

                runCatching {
                    Files.deleteIfExists(
                        file.toPath(),
                    )
                }

                null
            }
        }

    override fun write(
        payload: ByteArray,
    ) {
        synchronized(lock) {
            val ciphertext =
                Crypt32Util.cryptProtectData(
                    payload,
                )

            file.parentFile
                ?.mkdirs()

            val temporaryFile =
                File(
                    file.parentFile,
                    "${file.name}.tmp",
                )

            temporaryFile.writeBytes(
                ciphertext,
            )

            runCatching {
                Files.move(
                    temporaryFile.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            }.recoverCatching {
                Files.move(
                    temporaryFile.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrThrow()
        }
    }

    override fun clear() {
        synchronized(lock) {
            Files.deleteIfExists(
                file.toPath(),
            )

            Files.deleteIfExists(
                File(
                    file.parentFile,
                    "${file.name}.tmp",
                ).toPath(),
            )
        }
    }

    private companion object {
        const val TAG =
            "WindowsSecureSession"
    }
}
