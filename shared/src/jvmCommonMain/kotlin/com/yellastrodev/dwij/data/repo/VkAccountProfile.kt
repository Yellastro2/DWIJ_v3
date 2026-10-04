package com.yellastrodev.dwij.data.repo

/** Имя владельца музыкального токена; сохраняется только в защищённой сессии VK. */
data class VkAccountProfile(val id: Long, val firstName: String, val lastName: String) {
    val displayName: String get() = listOf(firstName, lastName).filter(String::isNotBlank).joinToString(" ")
}
