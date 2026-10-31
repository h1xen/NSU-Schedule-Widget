package com.example.scedulealarm

import com.example.scedulealarm.data.model.Lesson
import com.example.scedulealarm.data.model.LessonPhase
import com.example.scedulealarm.data.model.ScheduleData
import com.example.scedulealarm.data.model.ScheduleState
import com.example.scedulealarm.data.model.WeekParity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

class ScheduleFilterTest {

    private val zone = ZoneId.of("Asia/Novosibirsk")

    @Test
    fun testSubjectFilteringExcludesHiddenSubjects() {
        val l1 = Lesson(1, 1, "09:00", "10:35", "Английский язык (электив)", "Англ", "пр", "Практика", "110", "Smith", WeekParity.ALL)
        val l2 = Lesson(1, 2, "10:50", "12:25", "Математический анализ", "МатАн", "лек", "Лекция", "214 КПА", "Иванов", WeekParity.ALL)
        val l3 = Lesson(1, 3, "12:40", "14:15", "Физкультура (Секция)", "Физ-ра", "пр", "Практика", "Спортивный комплекс", "Сидоров", WeekParity.ALL)

        val rawSchedule = ScheduleData(
            groupName = "Группа 26946",
            groupUrl = "https://table.nsu.ru/group/26946",
            fetchedAtMillis = System.currentTimeMillis(),
            baseParity = WeekParity.ALL,
            lessons = listOf(l1, l2, l3)
        )

        // 1. Verify unique subjects list
        val uniqueSubjects = rawSchedule.lessons.map { it.subject.trim() }.distinct().sorted()
        assertEquals(3, uniqueSubjects.size)
        assertTrue(uniqueSubjects.contains("Английский язык (электив)"))
        assertTrue(uniqueSubjects.contains("Математический анализ"))
        assertTrue(uniqueSubjects.contains("Физкультура (Секция)"))

        // 2. Hide English and Physical culture
        val hidden = setOf("Английский язык (электив)", "Физкультура (Секция)")
        val filteredLessons = rawSchedule.lessons.filter { it.subject.trim() !in hidden }

        assertEquals(1, filteredLessons.size)
        assertEquals("Математический анализ", filteredLessons.first().subject)

        // 3. Verify state calculation skips hidden 1st lesson and points to 2nd lesson (Math)
        val mondayDate = LocalDate.now(zone).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
        val time0915 = mondayDate.atTime(9, 15).atZone(zone).toInstant().toEpochMilli()

        // Without filter: time 09:15 is DuringLesson English
        val rawState = calculateState(rawSchedule.lessons, mondayDate, time0915)
        assertTrue(rawState is ScheduleState.DuringLesson)
        assertEquals("Английский язык (электив)", (rawState as ScheduleState.DuringLesson).lesson.subject)

        // With filter: time 09:15 is BeforeLesson Math (starts at 10:50)
        val filteredState = calculateState(filteredLessons, mondayDate, time0915)
        assertTrue(filteredState is ScheduleState.BeforeLesson)
        assertEquals("Математический анализ", (filteredState as ScheduleState.BeforeLesson).lesson.subject)
    }

    private fun calculateState(lessons: List<Lesson>, todayDate: LocalDate, nowMillis: Long): ScheduleState {
        for (lesson in lessons) {
            val startParts = lesson.startTime.split(":").map { it.toInt() }
            val endParts = lesson.endTime.split(":").map { it.toInt() }
            val startMillis = todayDate.atTime(startParts[0], startParts[1]).atZone(zone).toInstant().toEpochMilli()
            val endMillis = todayDate.atTime(endParts[0], endParts[1]).atZone(zone).toInstant().toEpochMilli()

            if (nowMillis < startMillis) {
                return ScheduleState.BeforeLesson(lesson, startMillis)
            } else if (nowMillis < endMillis) {
                return ScheduleState.DuringLesson(lesson, endMillis, LessonPhase.FIRST_HALF, endMillis)
            }
        }
        return ScheduleState.DayFinished(null, null, null)
    }
}
