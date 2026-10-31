package com.example.scedulealarm.parser

import com.example.scedulealarm.data.model.Lesson
import com.example.scedulealarm.data.model.LessonSlot
import com.example.scedulealarm.data.model.ScheduleData
import com.example.scedulealarm.data.model.WeekParity
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

object NsuScheduleParser {

    fun parse(html: String, targetUrl: String = "https://table.nsu.ru/group/26946"): ScheduleData {
        val doc: Document = Jsoup.parse(html)

        // 1. Group title
        val groupHeading = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.title().substringBefore("|").trim().ifEmpty { "Расписание" }

        // 2. Week parity (e.g., <div class="parity">Нечетная неделя</div>)
        val parityElement = doc.selectFirst("div.parity")
        val baseParity = WeekParity.fromString(parityElement?.text())

        val lessons = mutableListOf<Lesson>()

        // 3. Try Desktop layout: table.time-table
        val desktopTable = doc.selectFirst("table.time-table")
        if (desktopTable != null) {
            parseDesktopTable(desktopTable, lessons)
        } else {
            // 4. Try Mobile layout: div.item-class or table.day
            val mobileDayContainers = doc.select("div.item-class")
            if (mobileDayContainers.isNotEmpty()) {
                parseMobileContainers(mobileDayContainers, lessons)
            } else {
                // Fallback: look for any table.day or general tables
                val dayTables = doc.select("table.day")
                if (dayTables.isNotEmpty()) {
                    parseMobileDayTables(dayTables, lessons)
                } else {
                    // Ultra-fallback: search all tables in document
                    parseGenericTables(doc, lessons)
                }
            }
        }

        return ScheduleData(
            groupName = groupHeading,
            groupUrl = targetUrl,
            fetchedAtMillis = System.currentTimeMillis(),
            baseParity = baseParity,
            lessons = lessons
        )
    }

    private fun parseDesktopTable(table: Element, lessons: MutableList<Lesson>) {
        val rows = table.select("tr")
        var currentRowSlot = 0

        for (row in rows) {
            if (row.select("th").isNotEmpty()) continue
            val tds = row.select("td")
            if (tds.isEmpty()) continue

            currentRowSlot++
            val timeText = tds[0].text().trim()
            val slot = LessonSlot.findByTimeString(timeText)
                ?: LessonSlot.findByNumber(currentRowSlot)
                ?: LessonSlot.ALL_SLOTS.getOrNull(currentRowSlot - 1)
                ?: LessonSlot(currentRowSlot, "09:00", "10:35", 9, 0, 10, 35)

            // Columns 1..6 = Monday..Saturday
            for (colIdx in 1 until tds.size) {
                if (colIdx > 6) break
                val dayOfWeek = colIdx
                val cellTd = tds[colIdx]

                val lessonDivs = cellTd.select("div.cell")
                for (cell in lessonDivs) {
                    val typeSpan = cell.selectFirst("span.type")
                    val typeShort = typeSpan?.text()?.trim().orEmpty()
                    val typeFull = typeSpan?.attr("title")?.trim()?.ifEmpty { typeShort } ?: typeShort

                    val subjDiv = cell.selectFirst("div.subject")
                    val subjTitle = subjDiv?.attr("title")?.trim()?.ifEmpty { null }
                        ?: subjDiv?.text()?.trim().orEmpty()
                    val subjShort = subjDiv?.text()?.trim().orEmpty().ifEmpty { subjTitle }

                    val roomDiv = cell.selectFirst("div.room")
                    val roomText = roomDiv?.text()?.trim().orEmpty()

                    val tutorElem = cell.selectFirst("a.tutor")
                    val tutorText = tutorElem?.text()?.trim().orEmpty()

                    val weekDiv = cell.selectFirst("div.week")
                    val parity = WeekParity.fromString(weekDiv?.text())

                    if (subjTitle.isNotBlank() || roomText.isNotBlank()) {
                        lessons.add(
                            Lesson(
                                dayOfWeek = dayOfWeek,
                                slotNumber = slot.slotNumber,
                                startTime = slot.startTime,
                                endTime = slot.endTime,
                                subject = subjTitle,
                                subjectShort = subjShort,
                                type = typeShort,
                                typeFull = typeFull,
                                room = roomText,
                                tutor = tutorText,
                                parity = parity
                            )
                        )
                    }
                }
            }
        }
    }

    private fun parseMobileContainers(containers: List<Element>, lessons: MutableList<Lesson>) {
        for (container in containers) {
            // Determine day of week
            val hash = container.attr("data-hash").trim() // e.g. "weekday0"
            val dayName = container.selectFirst("div.day-name")?.text()?.trim().orEmpty()
            val dayOfWeek = parseDayOfWeek(hash, dayName)

            val table = container.selectFirst("table.day") ?: container.selectFirst("table")
            if (table != null) {
                parseMobileRows(table, dayOfWeek, lessons)
            }
        }
    }

    private fun parseMobileDayTables(tables: List<Element>, lessons: MutableList<Lesson>) {
        for ((idx, table) in tables.withIndex()) {
            val dayOfWeek = (idx % 6) + 1
            parseMobileRows(table, dayOfWeek, lessons)
        }
    }

    private fun parseMobileRows(table: Element, dayOfWeek: Int, lessons: MutableList<Lesson>) {
        val rows = table.select("tr")
        var rowSlot = 0
        var lastSlot: LessonSlot? = null

        for (row in rows) {
            val tds = row.select("td")
            if (tds.isEmpty()) continue

            val timeText = tds[0].text().trim()
            val parsedSlot = LessonSlot.findByTimeString(timeText)
            val slot = if (parsedSlot != null) {
                rowSlot = parsedSlot.slotNumber
                parsedSlot
            } else if (timeText.isBlank() && lastSlot != null) {
                // Secondary elective in the same timeslot
                lastSlot
            } else {
                rowSlot++
                LessonSlot.findByNumber(rowSlot)
                    ?: LessonSlot.ALL_SLOTS.getOrNull(rowSlot - 1)
                    ?: LessonSlot(rowSlot, "09:00", "10:35", 9, 0, 10, 35)
            }
            lastSlot = slot

            if (tds.size >= 4) {
                val subjDiv = tds[1].selectFirst("div.subject")
                val subject = subjDiv?.text()?.trim().orEmpty()
                val tutor = tds[1].selectFirst("a.tutor")?.text()?.trim().orEmpty()

                val typeSpan = row.selectFirst("span.circle, span.type")
                val typeShort = typeSpan?.text()?.trim().orEmpty()
                val typeFull = typeSpan?.attr("title")?.trim()?.ifEmpty { typeShort } ?: typeShort

                val roomDiv = row.selectFirst("div.room")
                val roomText = roomDiv?.text()?.trim().orEmpty()

                val weekDiv = row.selectFirst("div.week")
                val parity = WeekParity.fromString(weekDiv?.text())

                if (subject.isNotBlank() || roomText.isNotBlank()) {
                    lessons.add(
                        Lesson(
                            dayOfWeek = dayOfWeek,
                            slotNumber = slot.slotNumber,
                            startTime = slot.startTime,
                            endTime = slot.endTime,
                            subject = subject,
                            subjectShort = subject,
                            type = typeShort,
                            typeFull = typeFull,
                            room = roomText,
                            tutor = tutor,
                            parity = parity
                        )
                    )
                }
            }
        }
    }

    private fun parseGenericTables(doc: Document, lessons: MutableList<Lesson>) {
        val allTables = doc.select("table")
        for ((tIdx, table) in allTables.withIndex()) {
            val rows = table.select("tr")
            for (row in rows) {
                val tds = row.select("td")
                if (tds.size < 2) continue
                val firstText = tds[0].text().trim()
                val slot = LessonSlot.findByTimeString(firstText) ?: continue

                // Check other columns
                for (col in 1 until tds.size) {
                    val text = tds[col].text().trim()
                    if (text.isNotBlank()) {
                        lessons.add(
                            Lesson(
                                dayOfWeek = ((col - 1) % 6) + 1,
                                slotNumber = slot.slotNumber,
                                startTime = slot.startTime,
                                endTime = slot.endTime,
                                subject = text,
                                subjectShort = text,
                                type = "ЗАНЯТИЕ",
                                typeFull = "Занятие",
                                room = "—",
                                tutor = "",
                                parity = WeekParity.ALL
                            )
                        )
                    }
                }
            }
        }
    }

    private fun parseDayOfWeek(hash: String, dayName: String): Int {
        if (hash.startsWith("weekday")) {
            val num = hash.removePrefix("weekday").toIntOrNull()
            if (num != null && num in 0..5) {
                return num + 1 // weekday0 -> 1 (Monday)
            }
        }

        val clean = dayName.lowercase().trim()
        return when {
            clean.contains("понедельник") -> 1
            clean.contains("вторник") -> 2
            clean.contains("среда") -> 3
            clean.contains("четверг") -> 4
            clean.contains("пятница") -> 5
            clean.contains("суббота") -> 6
            clean.contains("воскресенье") -> 7
            else -> 1
        }
    }
}
