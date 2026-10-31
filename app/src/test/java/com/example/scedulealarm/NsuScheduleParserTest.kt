package com.example.scedulealarm

import com.example.scedulealarm.data.model.LessonSlot
import com.example.scedulealarm.data.model.WeekParity
import com.example.scedulealarm.parser.NsuScheduleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class NsuScheduleParserTest {

    @Test
    fun testParseDesktopTable() {
        val sampleHtml = """
            <!DOCTYPE html>
            <html>
            <head><title>Группа 26946 | Расписание</title></head>
            <body>
                <h1>Группа 26946</h1>
                <div class="parity">Нечетная неделя</div>
                <table class="time-table">
                    <tr>
                        <th>Время</th>
                        <th>Понедельник</th>
                        <th>Вторник</th>
                        <th>Среда</th>
                        <th>Четверг</th>
                        <th>Пятница</th>
                        <th>Суббота</th>
                    </tr>
                    <tr>
                        <td>9:00</td>
                        <td>
                            <div class="cell">
                                <span class="type lek" title="лекция">лек</span>
                                <div class="subject" title="Императивное программирование">Императ.прогр.</div>
                                <div class="room">Ауд. 214 КПА</div>
                                <a class="tutor">Филиппов М.В.</a>
                            </div>
                        </td>
                        <td></td>
                        <td>
                            <div class="cell">
                                <span class="type pr" title="практическое занятие">пр</span>
                                <div class="subject" title="Физическая культура">Физкультура</div>
                                <div class="room">Ауд. Спортивный комплекс</div>
                                <div class="week">Нечетная</div>
                            </div>
                        </td>
                        <td></td>
                        <td></td>
                        <td></td>
                    </tr>
                    <tr>
                        <td>10:50</td>
                        <td></td>
                        <td>
                            <div class="cell">
                                <span class="type pr" title="практическое занятие">пр</span>
                                <div class="subject" title="Цифровые платформы">Цифр.платформы</div>
                                <div class="room"><a href="#">т3213</a></div>
                                <a class="tutor">Бондарев Д.В.</a>
                            </div>
                        </td>
                        <td></td>
                        <td></td>
                        <td></td>
                        <td></td>
                    </tr>
                </table>
            </body>
            </html>
        """.trimIndent()

        val data = NsuScheduleParser.parse(sampleHtml)
        assertEquals("Группа 26946", data.groupName)
        assertEquals(WeekParity.ODD, data.baseParity)
        assertEquals(3, data.lessons.size)

        // Lesson 1: Monday 9:00
        val lesson1 = data.lessons[0]
        assertEquals(1, lesson1.dayOfWeek)
        assertEquals(1, lesson1.slotNumber)
        assertEquals("09:00", lesson1.startTime)
        assertEquals("10:35", lesson1.endTime)
        assertEquals("Императивное программирование", lesson1.subject)
        assertEquals("214 КПА", lesson1.cleanRoom)
        assertEquals("ЛЕКЦИЯ", lesson1.normalizedType)
        assertEquals(WeekParity.ALL, lesson1.parity)

        // Lesson 2: Wednesday 9:00 (Odd week only)
        val lesson2 = data.lessons[1]
        assertEquals(3, lesson2.dayOfWeek)
        assertEquals(WeekParity.ODD, lesson2.parity)
        assertEquals("Спортивный комплекс", lesson2.cleanRoom)

        // Lesson 3: Tuesday 10:50
        val lesson3 = data.lessons[2]
        assertEquals(2, lesson3.dayOfWeek)
        assertEquals(2, lesson3.slotNumber)
        assertEquals("10:50", lesson3.startTime)
        assertEquals("12:25", lesson3.endTime)
        assertEquals("т3213", lesson3.cleanRoom)
    }

    @Test
    fun testParseMobileFormat() {
        val mobileHtml = """
            <!DOCTYPE html>
            <html>
            <head><title>Группа 26946 | Расписание занятий НГУ</title></head>
            <body>
                <div class="parity">Четная неделя</div>
                <div class='item-class' data-hash='weekday0'>
                    <div class='table-header'>
                        <div class='day-name'>Понедельник</div>
                    </div>
                    <div>
                        <table class='day'>
                            <tr class="odd">
                                <td>9:00</td>
                                <td>
                                    <div class='subject'>Императ.прогр.</div>
                                    <a class="tutor">Филиппов М.В.</a>
                                </td>
                                <td><span class='circle lek'>лек</span></td>
                                <td><div class='room'>214 КПА</div></td>
                                <td></td>
                            </tr>
                            <tr>
                                <td>10:50</td>
                                <td>
                                    <div class='subject'>Цифр.платформы</div>
                                    <a class="tutor">Бондарев Д.В.</a>
                                </td>
                                <td><span class='circle pr'>пр</span></td>
                                <td><div class='room'>т3213</div></td>
                                <td><div class='week'>Чет</div></td>
                            </tr>
                        </table>
                    </div>
                </div>
            </body>
            </html>
        """.trimIndent()

        val data = NsuScheduleParser.parse(mobileHtml)
        assertEquals("Группа 26946", data.groupName)
        assertEquals(WeekParity.EVEN, data.baseParity)
        assertEquals(2, data.lessons.size)

        val l1 = data.lessons[0]
        assertEquals(1, l1.dayOfWeek)
        assertEquals(1, l1.slotNumber)
        assertEquals("09:00", l1.startTime)
        assertEquals("10:35", l1.endTime)
        assertEquals("Императ.прогр.", l1.subject)
        assertEquals("214 КПА", l1.cleanRoom)
        assertEquals("ЛЕКЦИЯ", l1.normalizedType)
        assertEquals(WeekParity.ALL, l1.parity)

        val l2 = data.lessons[1]
        assertEquals(1, l2.dayOfWeek)
        assertEquals(2, l2.slotNumber)
        assertEquals(WeekParity.EVEN, l2.parity)
        assertEquals("т3213", l2.cleanRoom)
    }

    @Test
    fun testSlots() {
        assertEquals(7, LessonSlot.ALL_SLOTS.size)
        val slot1 = LessonSlot.findByNumber(1)
        assertNotNull(slot1)
        assertEquals("09:00", slot1?.startTime)
        assertEquals("10:35", slot1?.endTime)
    }

    @Test
    fun testRealGroups() {
        val f46 = java.io.File("Z:/group_26946.html")
        if (f46.exists()) {
            val d46 = NsuScheduleParser.parse(f46.readText())
            println("=== GROUP 26946: total lessons = ${d46.lessons.size} ===")
            for (day in 1..7) {
                val dayLessons = d46.lessons.filter { it.dayOfWeek == day }.sortedBy { it.slotNumber }
                println("Day $day: ${dayLessons.size} lessons")
                for (l in dayLessons) {
                    println("  slot ${l.slotNumber} (${l.startTime}-${l.endTime}) [${l.parity}]: ${l.subject} | ${l.cleanRoom}")
                }
            }
        }

        val f47 = java.io.File("Z:/group_26947.html")
        if (f47.exists()) {
            val d47 = NsuScheduleParser.parse(f47.readText())
            println("=== GROUP 26947: total lessons = ${d47.lessons.size} ===")
            for (day in 1..7) {
                val dayLessons = d47.lessons.filter { it.dayOfWeek == day }.sortedBy { it.slotNumber }
                println("Day $day: ${dayLessons.size} lessons")
                for (l in dayLessons) {
                    println("  slot ${l.slotNumber} (${l.startTime}-${l.endTime}) [${l.parity}]: ${l.subject} | ${l.cleanRoom}")
                }
            }
        }

        val fMob = java.io.File("Z:/group_26946_mobile.html")
        if (fMob.exists()) {
            val dMob = NsuScheduleParser.parse(fMob.readText())
            println("=== GROUP 26946 MOBILE: total lessons = ${dMob.lessons.size} ===")
            for (day in 1..7) {
                val dayLessons = dMob.lessons.filter { it.dayOfWeek == day }.sortedBy { it.slotNumber }
                println("Day $day: ${dayLessons.size} lessons")
                for (l in dayLessons) {
                    println("  slot ${l.slotNumber} (${l.startTime}-${l.endTime}) [${l.parity}]: ${l.subject} | ${l.cleanRoom}")
                }
            }
        }

        val fMob47 = java.io.File("Z:/group_26947_mobile.html")
        if (fMob47.exists()) {
            val dMob47 = NsuScheduleParser.parse(fMob47.readText())
            println("=== GROUP 26947 MOBILE: total lessons = ${dMob47.lessons.size} ===")
            for (day in 1..7) {
                val dayLessons = dMob47.lessons.filter { it.dayOfWeek == day }.sortedBy { it.slotNumber }
                println("Day $day: ${dayLessons.size} lessons")
                for (l in dayLessons) {
                    println("  slot ${l.slotNumber} (${l.startTime}-${l.endTime}) [${l.parity}]: ${l.subject} | ${l.cleanRoom}")
                }
            }
        }
    }
}
