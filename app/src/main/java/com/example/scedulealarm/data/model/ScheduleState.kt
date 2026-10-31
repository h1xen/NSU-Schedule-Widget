package com.example.scedulealarm.data.model

sealed class ScheduleState {
    abstract val statusTitle: String
    abstract val roomText: String
    abstract val subjectText: String
    abstract val typeText: String

    data class BeforeLesson(
        val lesson: Lesson,
        val startsAtMillis: Long
    ) : ScheduleState() {
        override val statusTitle: String = "До начала:"
        override val roomText: String = lesson.cleanRoom
        override val subjectText: String = lesson.subject
        override val typeText: String = lesson.normalizedType
    }

    data class DuringLesson(
        val lesson: Lesson,
        val endsAtMillis: Long, // End of current micro-phase for chronometer & transition alarm
        val phase: LessonPhase = LessonPhase.SECOND_HALF,
        val fullLessonEndMillis: Long = endsAtMillis
    ) : ScheduleState() {
        override val statusTitle: String = phase.statusTitle
        override val roomText: String = lesson.cleanRoom
        override val subjectText: String = lesson.subject
        override val typeText: String = lesson.normalizedType

        val phaseEndsAtMillis: Long
            get() = endsAtMillis
    }

    data class DayFinished(
        val nextLesson: Lesson? = null,
        val nextDayName: String? = null,
        val nextLessonDateInfo: String? = null
    ) : ScheduleState() {
        override val statusTitle: String = ""
        override val roomText: String = ""
        override val subjectText: String = "Пар на сегодня больше нет"
        override val typeText: String = ""
    }

    data class NoLessonsToday(
        val nextLesson: Lesson? = null,
        val nextDayName: String? = null,
        val nextLessonDateInfo: String? = null
    ) : ScheduleState() {
        override val statusTitle: String = ""
        override val roomText: String = ""
        override val subjectText: String = "Пар на сегодня больше нет"
        override val typeText: String = ""
    }

    object NoScheduleData : ScheduleState() {
        override val statusTitle: String = "Нет данных"
        override val roomText: String = "—"
        override val subjectText: String = "Нажмите 'Обновить', чтобы загрузить расписание"
        override val typeText: String = "НГУ"
    }
}
