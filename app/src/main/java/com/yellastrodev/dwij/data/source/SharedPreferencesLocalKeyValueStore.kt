package com.yellastrodev.dwij.storage

import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager

/**
 * Android-реализация общего key-value storage через default SharedPreferences.
 *
 * Здесь намеренно нет никаких знаний о настройках DWIJ, ключах,
 * YandexSession, RepeatMode и других моделях приложения.
 */
@Suppress("DEPRECATION")
class SharedPreferencesLocalKeyValueStore(
    context: Context,
) : LocalKeyValueStore {

    private val preferences =
        PreferenceManager
            .getDefaultSharedPreferences(
                context.applicationContext,
            )

    override fun getString(
        key: String,
    ): String? =
        preferences.getString(
            key,
            null,
        )

    override fun getLong(
        key: String,
    ): Long? =
        if (preferences.contains(key)) {
            preferences.getLong(
                key,
                0L,
            )
        } else {
            null
        }

    override fun getBoolean(
        key: String,
    ): Boolean? =
        if (preferences.contains(key)) {
            preferences.getBoolean(
                key,
                false,
            )
        } else {
            null
        }

    override fun edit(
        block: LocalKeyValueStore.Editor.() -> Unit,
    ) {
        val sharedPreferencesEditor =
            preferences.edit()

        val editor =
            SharedPreferencesEditor(
                sharedPreferencesEditor,
            )

        block(editor)

        sharedPreferencesEditor.apply()
    }

    private class SharedPreferencesEditor(
        private val editor:
        SharedPreferences.Editor,
    ) : LocalKeyValueStore.Editor {

        override fun putString(
            key: String,
            value: String,
        ) {
            editor.putString(
                key,
                value,
            )
        }

        override fun putLong(
            key: String,
            value: Long,
        ) {
            editor.putLong(
                key,
                value,
            )
        }

        override fun putBoolean(
            key: String,
            value: Boolean,
        ) {
            editor.putBoolean(
                key,
                value,
            )
        }

        override fun remove(
            key: String,
        ) {
            editor.remove(key)
        }
    }
}