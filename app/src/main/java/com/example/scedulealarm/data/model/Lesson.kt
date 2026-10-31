package com.example.scedulealarm.data.model

import com.google.gson.annotations.SerializedName

data class Lesson(
    @SerializedName("dayOfWeek")
    val dayOfWeek: Int, // 1 = Monday .. 6 = Saturday

    @SerializedName("slotNumber")
    val slotNumber: Int, // 1..7

    @SerializedName("startTime")
    val startTime: String,

    @SerializedName("endTime")
    val endTime: String,

    @SerializedName("subject")
    val subject: String,

    @SerializedName("subjectShort")
    val subjectShort: String,

    @SerializedName("type")
    val type: String, // "лек", "пр", "лаб"

    @SerializedName("typeFull")
    val typeFull: String, // "Лекция", "Практика"

    @SerializedName("room")
    val room: String,

    @SerializedName("tutor")
    val tutor: String,

    @SerializedName("parity")
    val parity: WeekParity
) {
    val cleanRoom: String
        get() {
            var r = room.trim()
            if (r.startsWith("Ауд.", ignoreCase = true)) {
                r = r.substring(4).trim()
            }
            return if (r.isBlank()) "—" else r
        }

    val normalizedType: String
        get() {
            val lower = type.lowercase().trim()
            return when {
                lower.contains("лек") -> "ЛЕКЦИЯ"
                lower.contains("пр") -> "ПРАКТИКА"
                lower.contains("лаб") -> "ЛАБ"
                else -> if (type.isNotBlank()) type.uppercase() else "ЗАНЯТИЕ"
            }
        }
}
