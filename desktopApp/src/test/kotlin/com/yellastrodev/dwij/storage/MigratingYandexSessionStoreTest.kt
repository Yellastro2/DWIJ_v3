package com.yellastrodev.dwij.storage

import com.yellastrodev.dwij.YA_SESSION_SECURE_MIGRATION_DONE
import com.yellastrodev.dwij.auth.YandexSession
import com.yellastrodev.yamusicsdk.YamLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Проверяет безопасный порядок миграции сохранённой авторизации. */
class MigratingYandexSessionStoreTest {

    @Test
    fun readMigratesLegacySessionAndRemovesPlaintext() {
        val state =
            InMemoryLocalKeyValueStore()

        val legacy =
            StoredYandexSessionStore(state)

        val payloadStore =
            InMemoryPayloadStore()

        val primary =
            ProtectedYandexSessionStore(
                payloadStore,
            )

        legacy.write(SESSION)

        val migrating =
            migratingStore(
                primary =
                    primary,
                legacy =
                    legacy,
                state =
                    state,
            )

        assertEquals(
            SESSION,
            migrating.read(),
        )
        assertEquals(
            SESSION,
            primary.read(),
        )
        assertNull(
            legacy.read(),
        )
        assertTrue(
            state.getBoolean(
                YA_SESSION_SECURE_MIGRATION_DONE,
            ) == true,
        )
    }

    @Test
    fun failedMigrationKeepsLegacySessionForRetry() {
        val state =
            InMemoryLocalKeyValueStore()

        val legacy =
            StoredYandexSessionStore(state)

        val payloadStore =
            InMemoryPayloadStore(
                failWrites =
                    true,
            )

        legacy.write(SESSION)

        val migrating =
            migratingStore(
                primary =
                    ProtectedYandexSessionStore(
                        payloadStore,
                    ),
                legacy =
                    legacy,
                state =
                    state,
            )

        assertEquals(
            SESSION,
            migrating.read(),
        )
        assertEquals(
            SESSION,
            legacy.read(),
        )
        assertFalse(
            state.getBoolean(
                YA_SESSION_SECURE_MIGRATION_DONE,
            ) == true,
        )
    }

    @Test
    fun completedMigrationNeverFallsBackToPlaintext() {
        val state =
            InMemoryLocalKeyValueStore()

        val legacy =
            StoredYandexSessionStore(state)

        legacy.write(SESSION)
        state.edit {
            putBoolean(
                YA_SESSION_SECURE_MIGRATION_DONE,
                true,
            )
        }

        val migrating =
            migratingStore(
                primary =
                    ProtectedYandexSessionStore(
                        InMemoryPayloadStore(),
                    ),
                legacy =
                    legacy,
                state =
                    state,
            )

        assertNull(
            migrating.read(),
        )
        assertNull(
            legacy.read(),
        )
    }

    @Test
    fun newSessionIsWrittenOnlyToProtectedStorage() {
        val state =
            InMemoryLocalKeyValueStore()

        val legacy =
            StoredYandexSessionStore(state)

        val primary =
            ProtectedYandexSessionStore(
                InMemoryPayloadStore(),
            )

        legacy.write(
            SESSION.copy(
                accessToken =
                    "old-access-token",
            ),
        )

        val migrating =
            migratingStore(
                primary =
                    primary,
                legacy =
                    legacy,
                state =
                    state,
            )

        migrating.write(SESSION)

        assertEquals(
            SESSION,
            primary.read(),
        )
        assertNull(
            legacy.read(),
        )
    }

    private fun migratingStore(
        primary: ProtectedYandexSessionStore,
        legacy: StoredYandexSessionStore,
        state: LocalKeyValueStore,
    ): MigratingYandexSessionStore =
        MigratingYandexSessionStore(
            primary =
                primary,
            legacy =
                legacy,
            migrationState =
                state,
            logger =
                NoOpLogger,
        )

    private class InMemoryPayloadStore(
        private val failWrites: Boolean = false,
    ) : ProtectedSessionPayloadStore {

        private var payload: ByteArray? = null

        override fun read(): ByteArray? =
            payload?.copyOf()

        override fun write(
            payload: ByteArray,
        ) {
            check(!failWrites) {
                "Запись намеренно отклонена тестом"
            }

            this.payload =
                payload.copyOf()
        }

        override fun clear() {
            payload = null
        }
    }

    private class InMemoryLocalKeyValueStore :
        LocalKeyValueStore {

        private val values =
            mutableMapOf<String, Any>()

        override fun getString(
            key: String,
        ): String? =
            values[key] as? String

        override fun getLong(
            key: String,
        ): Long? =
            values[key] as? Long

        override fun getBoolean(
            key: String,
        ): Boolean? =
            values[key] as? Boolean

        override fun edit(
            block: LocalKeyValueStore.Editor.() -> Unit,
        ) {
            EditorImpl()
                .apply(block)
        }

        private inner class EditorImpl :
            LocalKeyValueStore.Editor {

            override fun putString(
                key: String,
                value: String,
            ) {
                values[key] = value
            }

            override fun putLong(
                key: String,
                value: Long,
            ) {
                values[key] = value
            }

            override fun putBoolean(
                key: String,
                value: Boolean,
            ) {
                values[key] = value
            }

            override fun remove(
                key: String,
            ) {
                values.remove(key)
            }
        }
    }

    private object NoOpLogger : YamLogger {

        override fun info(
            tag: String,
            message: String,
        ) = Unit

        override fun debug(
            tag: String,
            message: String,
        ) = Unit

        override fun warning(
            tag: String,
            message: String,
        ) = Unit

        override fun error(
            tag: String,
            message: String,
            cause: Throwable?,
        ) = Unit
    }

    private companion object {
        val SESSION =
            YandexSession(
                accessToken =
                    "access-token",
                refreshToken =
                    "refresh-token",
                expiresAtMillis =
                    123_456_789L,
                login =
                    "listener",
                userId =
                    "42",
            )
    }
}
