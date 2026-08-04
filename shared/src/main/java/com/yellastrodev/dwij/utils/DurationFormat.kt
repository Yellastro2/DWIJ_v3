package com.yellastrodev.dwij.utils

class DurationFormat {
    companion object {
        fun formatDuration(ms: Int): String {
            val seconds = ms / 1000
            val minutes = seconds / 60
            val hours = minutes / 60
            val days = hours / 24

            val parts = mutableListOf<String>()

            when {
                days > 0 -> {
                    parts.add("$days ${plural(days, "день", "дня", "дней")}")
                    val remHours = hours % 24
                    if (remHours > 0) {
                        parts.add("$remHours ${plural(remHours, "час", "часа", "часов")}")
                    }
                }
                hours > 0 -> {
                    parts.add("$hours ${plural(hours, "час", "часа", "часов")}")
                    val remMinutes = minutes % 60
                    if (remMinutes > 0) {
                        parts.add("$remMinutes ${plural(remMinutes, "мин", "мин", "мин")}")
                    }
                }
                minutes > 0 -> {
                    parts.add("$minutes ${plural(minutes, "мин", "мин", "мин")}")
                    val remSeconds = seconds % 60
                    if (remSeconds > 0) {
                        parts.add("$remSeconds ${plural(remSeconds, "сек", "сек", "сек")}")
                    }
                }
                else -> {
                    parts.add("$seconds ${plural(seconds, "сек", "сек", "сек")}")
                }
            }

            return parts.take(2).joinToString(", ")
        }

        // простая функция для склонения
        fun plural(value: Int, one: String, few: String, many: String): String {
            val mod10 = value % 10
            val mod100 = value % 100
            return when {
                mod10 == 1 && mod100 != 11 -> one
                mod10 in 2..4 && (mod100 < 10 || mod100 >= 20) -> few
                else -> many
            }
        }

    }
}