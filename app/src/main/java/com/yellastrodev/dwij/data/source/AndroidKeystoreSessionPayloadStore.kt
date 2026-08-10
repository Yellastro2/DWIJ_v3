package com.yellastrodev.dwij.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.yellastrodev.yamusicsdk.YamLogger
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Защищает payload авторизации ключом AES-GCM из Android Keystore.
 *
 * В отдельном SharedPreferences-файле сохраняются только IV и ciphertext;
 * материал ключа остаётся неизвлекаемым из Android Keystore.
 */
class AndroidKeystoreSessionPayloadStore(
    context: Context,
    private val logger: YamLogger,
) : ProtectedSessionPayloadStore {

    private val lock = Any()

    private val preferences =
        context.applicationContext
            .getSharedPreferences(
                PREFERENCES_NAME,
                Context.MODE_PRIVATE,
            )

    override fun read(): ByteArray? =
        synchronized(lock) {
            val encodedIv =
                preferences.getString(
                    KEY_IV,
                    null,
                )

            val encodedCiphertext =
                preferences.getString(
                    KEY_CIPHERTEXT,
                    null,
                )

            if (
                encodedIv == null ||
                encodedCiphertext == null
            ) {
                if (
                    encodedIv != null ||
                    encodedCiphertext != null
                ) {
                    logger.warning(
                        TAG,
                        "[read] Защищённая сессия записана не полностью",
                    )

                    clearQuietly()
                }

                return@synchronized null
            }

            try {
                val cipher =
                    Cipher.getInstance(
                        CIPHER_TRANSFORMATION,
                    )

                cipher.init(
                    Cipher.DECRYPT_MODE,
                    secretKey(),
                    GCMParameterSpec(
                        GCM_TAG_LENGTH_BITS,
                        Base64.decode(
                            encodedIv,
                            Base64.NO_WRAP,
                        ),
                    ),
                )

                cipher.updateAAD(
                    AUTHENTICATED_DATA,
                )

                cipher.doFinal(
                    Base64.decode(
                        encodedCiphertext,
                        Base64.NO_WRAP,
                    ),
                )
            } catch (error: Exception) {
                logger.error(
                    TAG,
                    "[read] Не удалось расшифровать сохранённую авторизацию",
                    error,
                )

                clearQuietly()
                null
            }
        }

    override fun write(
        payload: ByteArray,
    ) {
        synchronized(lock) {
            val cipher =
                Cipher.getInstance(
                    CIPHER_TRANSFORMATION,
                )

            cipher.init(
                Cipher.ENCRYPT_MODE,
                secretKey(),
            )

            cipher.updateAAD(
                AUTHENTICATED_DATA,
            )

            val ciphertext =
                cipher.doFinal(payload)

            check(
                preferences.edit()
                    .putString(
                        KEY_IV,
                        Base64.encodeToString(
                            cipher.iv,
                            Base64.NO_WRAP,
                        ),
                    )
                    .putString(
                        KEY_CIPHERTEXT,
                        Base64.encodeToString(
                            ciphertext,
                            Base64.NO_WRAP,
                        ),
                    )
                    .commit(),
            ) {
                "Не удалось сохранить защищённую сессию"
            }
        }
    }

    override fun clear() {
        synchronized(lock) {
            check(
                preferences.edit()
                    .clear()
                    .commit(),
            ) {
                "Не удалось удалить защищённую сессию"
            }
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore =
            KeyStore.getInstance(
                ANDROID_KEY_STORE,
            ).apply {
                load(null)
            }

        val existingKey =
            keyStore.getKey(
                KEY_ALIAS,
                null,
            ) as? SecretKey

        if (existingKey != null) {
            return existingKey
        }

        return KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEY_STORE,
        ).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or
                            KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(
                    KeyProperties.BLOCK_MODE_GCM,
                ).setEncryptionPaddings(
                    KeyProperties.ENCRYPTION_PADDING_NONE,
                ).setKeySize(
                    KEY_SIZE_BITS,
                ).build(),
            )
        }.generateKey()
    }

    private fun clearQuietly() {
        runCatching {
            preferences.edit()
                .clear()
                .commit()
        }
    }

    private companion object {
        const val TAG =
            "AndroidSecureSession"

        const val PREFERENCES_NAME =
            "dwij_yandex_session"

        const val KEY_ALIAS =
            "dwij_yandex_session_v1"

        const val KEY_IV =
            "iv"

        const val KEY_CIPHERTEXT =
            "ciphertext"

        const val ANDROID_KEY_STORE =
            "AndroidKeyStore"

        const val CIPHER_TRANSFORMATION =
            "AES/GCM/NoPadding"

        const val KEY_SIZE_BITS =
            256

        const val GCM_TAG_LENGTH_BITS =
            128

        val AUTHENTICATED_DATA: ByteArray =
            "dwij-yandex-session-v1"
                .toByteArray(
                    StandardCharsets.UTF_8,
                )
    }
}
