package com.example.scedulealarm

import com.example.scedulealarm.data.model.Lesson
import com.example.scedulealarm.data.model.LessonPhase
import com.example.scedulealarm.data.model.ScheduleData
import com.example.scedulealarm.data.model.ScheduleState
import com.example.scedulealarm.data.model.WeekParity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

class ScheduleStateTest {

    private val zone = ZoneId.of("Asia/Novosibirsk")

    @Test
    fun testLessonMicroPhases() {
        val lesson = Lesson(1, 1, "09:00", "10:35", "Математика", "Мат", "лек", "Лекция", "214 КПА", "Иванов", WeekParity.ALL)
        val schedule = ScheduleData("26946", "url", System.currentTimeMillis(), WeekParity.ALL, listOf(lesson))
        val mondayDate = LocalDate.now(zone).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))

        // 1. First half at 09:20 -> target 09:45, "До перерыва:"
        val time0920 = mondayDate.atTime(9, 20).atZone(zone).toInstant().toEpochMilli()
        val expectedPhase1End = mondayDate.atTime(9, 45).atZone(zone).toInstant().toEpochMilli()
        val statePhase1 = getStateForTime(schedule, time0920)
        assertTrue(statePhase1 is ScheduleState.DuringLesson)
        val during1 = statePhase1 as ScheduleState.DuringLesson
        assertEquals(LessonPhase.FIRST_HALF, during1.phase)
        assertEquals("До перерыва:", during1.statusTitle)
        assertEquals(expectedPhase1End, during1.endsAtMillis)

        // 2. Mid-break at 09:47 -> target 09:50, "Перерыв (5 мин):"
        val time0947 = mondayDate.atTime(9, 47).atZone(zone).toInstant().toEpochMilli()
        val expectedBreakEnd = mondayDate.atTime(9, 50).atZone(zone).toInstant().toEpochMilli()
        val stateBreak = getStateForTime(schedule, time0947)
        assertTrue(stateBreak is ScheduleState.DuringLesson)
        val duringBreak = stateBreak as ScheduleState.DuringLesson
        assertEquals(LessonPhase.MID_BREAK, duringBreak.phase)
        assertEquals("Перерыв (5 мин):", duringBreak.statusTitle)
        assertEquals(expectedBreakEnd, duringBreak.endsAtMillis)

        // 3. Second half at 10:00 -> target 10:35, "До конца пары:"
        val time1000 = mondayDate.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        val expectedPhase2End = mondayDate.atTime(10, 35).atZone(zone).toInstant().toEpochMilli()
        val statePhase2 = getStateForTime(schedule, time1000)
        assertTrue(statePhase2 is ScheduleState.DuringLesson)
        val during2 = statePhase2 as ScheduleState.DuringLesson
        assertEquals(LessonPhase.SECOND_HALF, during2.phase)
        assertEquals("До конца пары:", during2.statusTitle)
        assertEquals(expectedPhase2End, during2.endsAtMillis)
    }

    @Test
    fun testTransitionToNextDayWhenAllLessonsEnded() {
        // Monday: 2 lessons (1st at 09:00-10:35, 2nd at 10:50-12:25)
        // Tuesday: 1 lesson (1st at 09:00-10:35)
        val lessons = listOf(
            Lesson(1, 1, "09:00", "10:35", "Математика", "Мат", "лек", "Лекция", "214 КПА", "Иванов", WeekParity.ALL),
            Lesson(1, 2, "10:50", "12:25", "Физика", "Физ", "пр", "Практика", "3107", "Петров", WeekParity.ALL),
            Lesson(2, 1, "09:00", "10:35", "Программирование", "Прогр", "лек", "Лекция", "313 КПА", "Сидоров", WeekParity.ALL)
        )

        val schedule = ScheduleData(
            groupName = "Группа 26946",
            groupUrl = "https://table.nsu.ru/group/26946",
            fetchedAtMillis = System.currentTimeMillis(),
            baseParity = WeekParity.ALL,
            lessons = lessons
        )

        val mondayDate = LocalDate.now(zone).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))

        // 0. Time at 02:00 AM on Monday (night mode before 05:00 AM)
        val timeNight = mondayDate.atTime(2, 0).atZone(zone).toInstant().toEpochMilli()
        val stateNight = getStateForTime(schedule, timeNight)
        assertTrue(stateNight is ScheduleState.DayFinished)
        assertEquals("Пар на сегодня больше нет", stateNight.subjectText)

        // 1. Time at 08:30 on Monday (morning after 05:00 AM, before 1st lesson)
        val timeBefore = mondayDate.atTime(8, 30).atZone(zone).toInstant().toEpochMilli()
        val stateBefore = getStateForTime(schedule, timeBefore)
        assertTrue(stateBefore is ScheduleState.BeforeLesson)
        assertEquals("Математика", (stateBefore as ScheduleState.BeforeLesson).lesson.subject)

        // 2. Time at 10:00 on Monday (during 1st lesson second half)
        val timeDuring = mondayDate.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        val stateDuring = getStateForTime(schedule, timeDuring)
        assertTrue(stateDuring is ScheduleState.DuringLesson)
        assertEquals("Математика", (stateDuring as ScheduleState.DuringLesson).lesson.subject)

        // 3. Time at 15:00 on Monday (all lessons for Monday ended) -> DayFinished "Пар на сегодня больше нет"
        val timeAfter = mondayDate.atTime(15, 0).atZone(zone).toInstant().toEpochMilli()
        val stateAfter = getStateForTime(schedule, timeAfter)
        assertTrue(stateAfter is ScheduleState.DayFinished)
        assertEquals("Пар на сегодня больше нет", stateAfter.subjectText)
        val finishedState = stateAfter as ScheduleState.DayFinished
        assertEquals("Программирование", finishedState.nextLesson?.subject)
    }

    private fun getStateForTime(schedule: ScheduleData, nowMillis: Long): ScheduleState {
        val nowZdt = Instant.ofEpochMilli(nowMillis).atZone(zone)
        if (nowZdt.hour < 5) {
            val next = schedule.lessons.firstOrNull { it.dayOfWeek == nowZdt.dayOfWeek.value }
            return ScheduleState.DayFinished(next, "Сегодня", "Сегодня, 09:00")
        }

        val dayOfWeekIndex = nowZdt.dayOfWeek.value
        val todayLessons = schedule.lessons
            .filter { it.dayOfWeek == dayOfWeekIndex }
            .sortedBy { it.slotNumber }

        val todayDate = nowZdt.toLocalDate()
        for (lesson in todayLessons) {
            val startParts = lesson.startTime.split(":").map { it.toInt() }
            val endParts = lesson.endTime.split(":").map { it.toInt() }
            val startZdt = todayDate.atTime(startParts[0], startParts[1]).atZone(zone)
            val endZdt = todayDate.atTime(endParts[0], endParts[1]).atZone(zone)
            val startMillis = startZdt.toInstant().toEpochMilli()
            val endMillis = endZdt.toInstant().toEpochMilli()

            if (nowMillis < startMillis) {
                return ScheduleState.BeforeLesson(lesson, startMillis)
            } else if (nowMillis < endMillis) {
                val firstHalfEndMillis = startZdt.plusMinutes(45).toInstant().toEpochMilli()
                val breakEndMillis = startZdt.plusMinutes(50).toInstant().toEpochMilli()

                return when {
                    nowMillis < firstHalfEndMillis -> {
                        ScheduleState.DuringLesson(
                            lesson = lesson,
                            endsAtMillis = firstHalfEndMillis,
                            phase = LessonPhase.FIRST_HALF,
                            fullLessonEndMillis = endMillis
                        )
                    }
                    nowMillis < breakEndMillis -> {
                        ScheduleState.DuringLesson(
                            lesson = lesson,
                            endsAtMillis = breakEndMillis,
                            phase = LessonPhase.MID_BREAK,
                            fullLessonEndMillis = endMillis
                        )
                    }
                    else -> {
                        ScheduleState.DuringLesson(
                            lesson = lesson,
                            endsAtMillis = endMillis,
                            phase = LessonPhase.SECOND_HALF,
                            fullLessonEndMillis = endMillis
                        )
                    }
                }
            }
        }

        // All lessons today ended: find next upcoming
        for (offset in 1..7) {
            val checkZdt = nowZdt.plusDays(offset.toLong())
            val checkDay = checkZdt.dayOfWeek.value
            val nextLessons = schedule.lessons.filter { it.dayOfWeek == checkDay }.sortedBy { it.slotNumber }
            if (nextLessons.isNotEmpty()) {
                val next = nextLessons.first()
                return ScheduleState.DayFinished(next, "Завтра", "Завтра, ${next.startTime}")
            }
        }
        return ScheduleState.DayFinished(null, null, null)
    }
}
