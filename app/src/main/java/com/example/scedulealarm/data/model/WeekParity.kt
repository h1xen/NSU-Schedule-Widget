package com.example.scedulealarm.data.model

import com.google.gson.annotations.SerializedName

enum class WeekParity(val displayName: String) {
    @SerializedName("ALL")
    ALL("Все недели"),

    @SerializedName("ODD")
    ODD("Нечетная"),

    @SerializedName("EVEN")
    EVEN("Четная");

    fun isCompatibleWith(other: WeekParity): Boolean {
        if (this == ALL || other == ALL) return true
        return this == other
    }

    fun opposite(): WeekParity {
        return when (this) {
            ODD -> EVEN
            EVEN -> ODD
            ALL -> ALL
        }
    }

    companion object {
        fun fromString(text: String?): WeekParity {
            if (text == null) return ALL
            val clean = text.trim().lowercase()
            return when {
                clean.contains("нечет") -> ODD
                clean.contains("чет") -> EVEN
                else -> ALL
            }
        }
    }
}
