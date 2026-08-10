package com.yellastrodev.dwij.storage

import com.yellastrodev.dwij.YA_SESSION_SECURE_MIGRATION_DONE
import com.yellastrodev.dwij.auth.YandexSession
import com.yellastrodev.dwij.auth.YandexSessionStore
import com.yellastrodev.yamusicsdk.YamLogger

/**
 * Переносит существующую plaintext-сессию в защищённое хранилище.
 *
 * Старые значения удаляются только после успешной записи и контрольного чтения
 * новой сессии. После завершённой миграции fallback к plaintext запрещён.
 */
class MigratingYandexSessionStore(
    private val primary: YandexSessionStore,
    private val legacy: YandexSessionStore,
    private val migrationState: LocalKeyValueStore,
    private val logger: YamLogger,
) : YandexSessionStore {

    override fun read(): YandexSession? {
        val protectedSession =
            try {
                primary.read()
            } catch (error: Exception) {
                logger.error(
                    TAG,
                    "[read] Не удалось прочитать защищённую сессию",
                    error,
                )

                null
            }

        if (protectedSession != null) {
            completeMigrationCleanup()
            return protectedSession
        }

        if (migrationCompleted()) {
            clearLegacyAfterMigration()
            return null
        }

        val legacySession =
            try {
                legacy.read()
            } catch (error: Exception) {
                logger.error(
                    TAG,
                    "[read] Не удалось прочитать прежнюю сессию",
                    error,
                )

                null
            }
                ?: return null

        return migrateOrKeepLegacy(
            legacySession,
        )
    }

    override fun write(
        session: YandexSession,
    ) {
        primary.write(session)

        check(primary.read() == session) {
            "Контрольное чтение защищённой сессии не совпало с записанной сессией"
        }

        completeMigrationCleanup()
    }

    override fun clear() {
        var firstFailure: Exception? = null

        try {
            primary.clear()
        } catch (error: Exception) {
            firstFailure = error
        }

        try {
            legacy.clear()
        } catch (error: Exception) {
            if (firstFailure == null) {
                firstFailure = error
            }
        }

        try {
            markMigrationCompleted()
        } catch (error: Exception) {
            if (firstFailure == null) {
                firstFailure = error
            }
        }

        firstFailure?.let { error ->
            throw error
        }
    }

    private fun migrateOrKeepLegacy(
        legacySession: YandexSession,
    ): YandexSession =
        try {
            primary.write(legacySession)

            val verifiedSession =
                primary.read()

            check(verifiedSession == legacySession) {
                "Контрольное чтение мигрированной сессии не совпало с исходной"
            }

            completeMigrationCleanup()

            logger.info(
                TAG,
                "[migrate] Авторизация перенесена в защищённое хранилище",
            )

            verifiedSession
        } catch (error: Exception) {
            logger.error(
                TAG,
                "[migrate] Не удалось перенести авторизацию; прежняя сессия сохранена",
                error,
            )

            legacySession
        }

    private fun completeMigrationCleanup() {
        try {
            markMigrationCompleted()
        } catch (error: Exception) {
            logger.error(
                TAG,
                "[cleanup] Не удалось отметить завершение миграции",
                error,
            )

            return
        }

        clearLegacyAfterMigration()
    }

    private fun clearLegacyAfterMigration() {
        try {
            legacy.clear()
        } catch (error: Exception) {
            logger.error(
                TAG,
                "[cleanup] Не удалось удалить прежнюю plaintext-сессию",
                error,
            )
        }
    }

    private fun migrationCompleted(): Boolean =
        migrationState.getBoolean(
            YA_SESSION_SECURE_MIGRATION_DONE,
        ) == true

    private fun markMigrationCompleted() {
        migrationState.edit {
            putBoolean(
                YA_SESSION_SECURE_MIGRATION_DONE,
                true,
            )
        }
    }

    private companion object {
        const val TAG =
            "MigratingYandexSessionStore"
    }
}
