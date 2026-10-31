package com.example.scedulealarm.data.model

data class LessonSlot(
    val slotNumber: Int,
    val startTime: String,
    val endTime: String,
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int
) {
    val startMinutesOfDay: Int
        get() = startHour * 60 + startMinute

    val endMinutesOfDay: Int
        get() = endHour * 60 + endMinute

    companion object {
        val ALL_SLOTS = listOf(
            LessonSlot(1, "09:00", "10:35", 9, 0, 10, 35),
            LessonSlot(2, "10:50", "12:25", 10, 50, 12, 25),
            LessonSlot(3, "12:40", "14:15", 12, 40, 14, 15),
            LessonSlot(4, "14:30", "16:05", 14, 30, 16, 5),
            LessonSlot(5, "16:20", "17:55", 16, 20, 17, 55),
            LessonSlot(6, "18:10", "19:45", 18, 10, 19, 45),
            LessonSlot(7, "20:00", "21:35", 20, 0, 21, 35)
        )

        fun findByNumber(slotNumber: Int): LessonSlot? {
            return ALL_SLOTS.firstOrNull { it.slotNumber == slotNumber }
        }

        fun findByTimeString(timeStr: String): LessonSlot? {
            val trimmed = timeStr.trim()
            if (trimmed.isEmpty()) return null
            val clean = trimmed.replace("0", "")
            return ALL_SLOTS.firstOrNull {
                it.startTime.replace("0", "") == clean ||
                it.startTime.startsWith(trimmed) ||
                trimmed.startsWith(it.startTime.replace("^0".toRegex(), ""))
            }
        }

        fun slotNumberForIndex(index: Int): Int {
            return if (index in 1..ALL_SLOTS.size) index else 1
        }
    }
}
