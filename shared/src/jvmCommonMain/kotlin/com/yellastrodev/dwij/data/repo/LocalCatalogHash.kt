package com.yellastrodev.dwij.data.repo

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/** Вычисляет стабильный SHA-256 только из локальных полей, используемых резолвером. */
fun localCatalogInputHash(
    title: String,
    artist: String?,
): String {
    val payload = listOf(title, artist.orEmpty())
        .joinToString(HASH_FIELD_SEPARATOR, transform = ::normalizeHashField)
    return MessageDigest.getInstance("SHA-256")
        .digest(payload.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }
}

private fun normalizeHashField(value: String): String = Normalizer
    .normalize(value, Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT)
    .replace('ё', 'е')
    .replace(NON_ALPHANUMERIC, " ")
    .trim()
    .replace(MULTIPLE_SPACES, " ")

private const val HASH_FIELD_SEPARATOR = "\u001F"
private val NON_ALPHANUMERIC = Regex("[^\\p{L}\\p{N}]+")
private val MULTIPLE_SPACES = Regex("\\s+")
