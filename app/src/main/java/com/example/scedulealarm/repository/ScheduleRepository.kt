package com.example.scedulealarm.repository

import android.content.Context
import android.content.SharedPreferences
import com.example.scedulealarm.data.model.GroupItem
import com.example.scedulealarm.data.model.Lesson
import com.example.scedulealarm.data.model.LessonPhase
import com.example.scedulealarm.data.model.LessonSlot
import com.example.scedulealarm.data.model.ScheduleData
import com.example.scedulealarm.data.model.ScheduleState
import com.example.scedulealarm.data.model.WeekParity
import com.example.scedulealarm.parser.NsuScheduleParser
import com.example.scedulealarm.service.ScheduleForegroundService
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.ConcurrentHashMap

class ScheduleRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    val nsuZone: ZoneId = ZoneId.of("Asia/Novosibirsk")

    @Volatile
    private var rawMemoryCache: ScheduleData? = null

    @Volatile
    private var groupsMemoryCache: List<GroupItem>? = null

    init {
        rawMemoryCache = loadFromPrefs()
    }

    // -------------------------------------------------------------
    // Schedule Cache & Storage (with Subject Filter support)
    // -------------------------------------------------------------

    fun getRawCachedSchedule(): ScheduleData? {
        if (rawMemoryCache == null) {
            rawMemoryCache = loadFromPrefs()
        }
        return rawMemoryCache
    }

    fun getCachedSchedule(): ScheduleData? {
        val raw = getRawCachedSchedule() ?: return null
        val hidden = getHiddenSubjects()
        if (hidden.isEmpty()) return raw
        return raw.copy(
            lessons = raw.lessons.filter { it.subject.trim() !in hidden }
        )
    }

    fun getHiddenSubjects(): Set<String> {
        return prefs.getStringSet(KEY_HIDDEN_SUBJECTS, emptySet()) ?: emptySet()
    }

    fun setHiddenSubjects(hidden: Set<String>) {
        prefs.edit().putStringSet(KEY_HIDDEN_SUBJECTS, hidden).apply()
    }

    fun getAllUniqueSubjects(): List<String> {
        val raw = getRawCachedSchedule() ?: return emptyList()
        return raw.lessons
            .map { it.subject.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }

    private fun loadFromPrefs(): ScheduleData? {
        val json = prefs.getString(KEY_SCHEDULE_JSON, null) ?: return null
        return try {
            gson.fromJson(json, ScheduleData::class.java)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Completely purges the cached schedule in memory and in SharedPreferences
     * so that old data is never accumulated when switching groups.
     */
    fun clearScheduleCache() {
        rawMemoryCache = null
        prefs.edit()
            .remove(KEY_SCHEDULE_JSON)
            .remove(KEY_LAST_SYNCED)
            .apply()
    }

    // -------------------------------------------------------------
    // Group Selection & Storage
    // -------------------------------------------------------------

    fun getSelectedGroup(): GroupItem {
        val name = prefs.getString(KEY_SELECTED_GROUP_NAME, GroupItem.DEFAULT.name)
            ?: GroupItem.DEFAULT.name
        val url = prefs.getString(KEY_SELECTED_GROUP_URL, GroupItem.DEFAULT.urlPath)
            ?: GroupItem.DEFAULT.urlPath
        return GroupItem(name, url)
    }

    fun saveSelectedGroup(group: GroupItem) {
        prefs.edit()
            .putString(KEY_SELECTED_GROUP_NAME, group.name)
            .putString(KEY_SELECTED_GROUP_URL, group.urlPath)
            .apply()
    }

    // -------------------------------------------------------------
    // University Groups Catalog (Cached strictly in a single key)
    // -------------------------------------------------------------

    fun getCachedGroups(): List<GroupItem> {
        if (groupsMemoryCache != null) return groupsMemoryCache!!
        val json = prefs.getString(KEY_CACHED_GROUPS_JSON, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<GroupItem>>() {}.type
            val list: List<GroupItem> = gson.fromJson(json, type) ?: emptyList()
            groupsMemoryCache = list
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveGroupsToCache(groups: List<GroupItem>) {
        groupsMemoryCache = groups
        val json = gson.toJson(groups)
        prefs.edit()
            .putString(KEY_CACHED_GROUPS_JSON, json)
            .apply()
    }

    /**
     * Downloads the full catalog of study groups from NSU table service.
     * Uses Dispatchers.IO and parallel requests across faculties.
     */
    suspend fun fetchAvailableGroups(forceRefresh: Boolean = false): Result<List<GroupItem>> =
        withContext(Dispatchers.IO) {
            if (!forceRefresh) {
                val cached = getCachedGroups()
                if (cached.isNotEmpty()) {
                    return@withContext Result.success(cached)
                }
            }

            try {
                // 1. Fetch faculties page
                val facultiesDoc = try {
                    Jsoup.connect("https://table.nsu.ru/faculties")
                        .userAgent(USER_AGENT)
                        .timeout(10000)
                        .get()
                } catch (e: Exception) {
                    null
                }

                val facultyPaths = mutableListOf<String>()
                facultiesDoc?.select("a[href]")?.forEach { a ->
                    val href = a.attr("href").trim()
                    if (href.startsWith("/faculty/") && href !in facultyPaths) {
                        facultyPaths.add(href)
                    }
                }

                if (facultyPaths.isEmpty()) {
                    facultyPaths.addAll(
                        listOf(
                            "/faculty/ggf", "/faculty/gi", "/faculty/iir", "/faculty/immt",
                            "/faculty/iphp", "/faculty/mmf", "/faculty/pish", "/faculty/fen",
                            "/faculty/fit", "/faculty/ff", "/faculty/ef"
                        )
                    )
                }

                // 2. Fetch all faculty group links in parallel
                val groupsMap = ConcurrentHashMap<String, String>()

                coroutineScope {
                    val tasks = facultyPaths.map { fPath ->
                        async {
                            try {
                                val doc = Jsoup.connect("https://table.nsu.ru$fPath")
                                    .userAgent(USER_AGENT)
                                    .timeout(10000)
                                    .get()

                                for (a in doc.select("a[href]")) {
                                    val href = a.attr("href").trim()
                                    if (href.contains("/group/") &&
                                        !href.contains("ok.ru") &&
                                        !href.contains("vk.com")
                                    ) {
                                        val name = a.text().trim()
                                        val cleanPath =
                                            if (href.startsWith("/")) href.substring(1) else href
                                        if (name.isNotEmpty() && cleanPath.startsWith("group/")) {
                                            groupsMap[name] = cleanPath
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                // Ignore individual faculty network glitch
                            }
                        }
                    }
                    tasks.awaitAll()
                }

                // Ensure default group is always present
                groupsMap.putIfAbsent(GroupItem.DEFAULT.name, GroupItem.DEFAULT.urlPath)

                val sortedGroups = groupsMap.map { (name, path) -> GroupItem(name, path) }
                    .sortedWith { g1, g2 -> compareGroupNames(g1.name, g2.name) }

                saveGroupsToCache(sortedGroups)
                Result.success(sortedGroups)
            } catch (e: Exception) {
                val cached = getCachedGroups()
                if (cached.isNotEmpty()) {
                    Result.success(cached)
                } else {
                    Result.success(listOf(GroupItem.DEFAULT))
                }
            }
        }

    private fun compareGroupNames(s1: String, s2: String): Int {
        return try {
            val parts1 = s1.split(".")
            val parts2 = s2.split(".")
            val num1 = parts1[0].toLongOrNull()
            val num2 = parts2[0].toLongOrNull()
            if (num1 != null && num2 != null) {
                val cmp = num1.compareTo(num2)
                if (cmp != 0) return cmp
                val sub1 = parts1.getOrNull(1)?.toIntOrNull() ?: 0
                val sub2 = parts2.getOrNull(1)?.toIntOrNull() ?: 0
                sub1.compareTo(sub2)
            } else {
                s1.compareTo(s2)
            }
        } catch (e: Exception) {
            s1.compareTo(s2)
        }
    }

    // -------------------------------------------------------------
    // Schedule Fetching & Switching
    // -------------------------------------------------------------

    fun isServiceEnabled(): Boolean {
        return prefs.getBoolean(KEY_SERVICE_ENABLED, false)
    }

    fun setServiceEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SERVICE_ENABLED, enabled).apply()
    }

    suspend fun fetchAndSaveSchedule(
        group: GroupItem = getSelectedGroup()
    ): Result<ScheduleData> = withContext(Dispatchers.IO) {
        val cleanPath = group.urlPath.trim().removePrefix("/")
        val targetUrl = "https://table.nsu.ru/$cleanPath"

        try {
            val response = Jsoup.connect(targetUrl)
                .userAgent(USER_AGENT)
                .header(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
                )
                .header("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
                .timeout(15000)
                .get()

            val parsedData = NsuScheduleParser.parse(response.html(), targetUrl)
            val scheduleData = if (parsedData.groupName.isBlank() || parsedData.groupName == "Расписание") {
                parsedData.copy(groupName = "Группа ${group.name}")
            } else {
                parsedData
            }

            val json = gson.toJson(scheduleData)
            // Overwrite strictly the single key
            prefs.edit()
                .putString(KEY_SCHEDULE_JSON, json)
                .putLong(KEY_LAST_SYNCED, System.currentTimeMillis())
                .apply()

            rawMemoryCache = scheduleData
            val filteredData = getCachedSchedule() ?: scheduleData
            Result.success(filteredData)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Switches the active group:
     * 1. Purges previous group schedule cache.
     * 2. Saves selected group.
     * 3. Fetches new group schedule.
     * 4. Restarts/updates ScheduleForegroundService if enabled.
     */
    suspend fun selectGroupAndReload(group: GroupItem, context: Context): Result<ScheduleData> {
        saveSelectedGroup(group)
        clearScheduleCache()
        val result = fetchAndSaveSchedule(group)
        if (result.isSuccess && isServiceEnabled()) {
            ScheduleForegroundService.startOrUpdate(context)
        }
        return result
    }

    fun getLastSyncedTimestamp(): Long {
        return prefs.getLong(KEY_LAST_SYNCED, 0L)
    }

    /**
     * Accurately calculates week parity (ODD/EVEN) for any timestamp in Novosibirsk timezone
     * based on calendar weeks delta since base fetch.
     */
    fun getEffectiveParityForDate(schedule: ScheduleData, targetTimeMillis: Long): WeekParity {
        if (schedule.baseParity == WeekParity.ALL) return WeekParity.ALL

        val baseZdt = Instant.ofEpochMilli(schedule.fetchedAtMillis).atZone(nsuZone)
        val targetZdt = Instant.ofEpochMilli(targetTimeMillis).atZone(nsuZone)

        val baseMonday =
            baseZdt.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toLocalDate()
        val targetMonday =
            targetZdt.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toLocalDate()

        val diffWeeks = ChronoUnit.WEEKS.between(baseMonday, targetMonday)

        return if (Math.abs(diffWeeks) % 2L == 0L) {
            schedule.baseParity
        } else {
            schedule.baseParity.opposite()
        }
    }

    /**
     * Determines the current UI/Lockscreen state for the schedule taking into account
     * the exact Novosibirsk timezone, current time, and day-of-week.
     */
    fun getCurrentScheduleState(nowMillis: Long = System.currentTimeMillis()): ScheduleState {
        val schedule = getCachedSchedule() ?: return ScheduleState.NoScheduleData

        val nowZdt = Instant.ofEpochMilli(nowMillis).atZone(nsuZone)
        val currentHour = nowZdt.hour

        // Night mode check: If time is strictly before 05:00 AM, it is night period -> DayFinished
        if (currentHour < 5) {
            val (nextLesson, nextDayName, nextInfo) = findNextUpcomingLesson(
                schedule,
                nowZdt,
                offsetDaysStart = 0
            )
            return ScheduleState.DayFinished(nextLesson, nextDayName, nextInfo)
        }

        val currentParity = getEffectiveParityForDate(schedule, nowMillis)

        // 1 = Monday .. 7 = Sunday
        val dayOfWeekIndex = nowZdt.dayOfWeek.value

        val todayLessons = schedule.lessons
            .filter { it.dayOfWeek == dayOfWeekIndex && it.parity.isCompatibleWith(currentParity) }
            .sortedBy { it.slotNumber }

        if (todayLessons.isEmpty()) {
            val (nextLesson, nextDayName, nextInfo) = findNextUpcomingLesson(
                schedule,
                nowZdt,
                offsetDaysStart = 1
            )
            return ScheduleState.DayFinished(nextLesson, nextDayName, nextInfo)
        }

        val todayDate = nowZdt.toLocalDate()

        // Check each lesson today in order
        for (lesson in todayLessons) {
            val slot = LessonSlot.findByNumber(lesson.slotNumber)
                ?: LessonSlot(lesson.slotNumber, lesson.startTime, lesson.endTime, 9, 0, 10, 35)

            val startParts = lesson.startTime.split(":").mapNotNull { it.toIntOrNull() }
            val endParts = lesson.endTime.split(":").mapNotNull { it.toIntOrNull() }
            val startHour = if (startParts.size >= 2) startParts[0] else slot.startHour
            val startMinute = if (startParts.size >= 2) startParts[1] else slot.startMinute
            val endHour = if (endParts.size >= 2) endParts[0] else slot.endHour
            val endMinute = if (endParts.size >= 2) endParts[1] else slot.endMinute

            val startZdt = todayDate.atTime(startHour, startMinute).atZone(nsuZone)
            val endZdt = todayDate.atTime(endHour, endMinute).atZone(nsuZone)

            val startMillis = startZdt.toInstant().toEpochMilli()
            val endMillis = endZdt.toInstant().toEpochMilli()

            if (nowMillis < startMillis) {
                // Lesson hasn't started yet
                return ScheduleState.BeforeLesson(lesson, startMillis)
            } else if (nowMillis < endMillis) {
                // Lesson is in progress: divide into 3 micro-phases
                // 1. First half: startTime to startTime + 45 min
                // 2. Break: startTime + 45 min to startTime + 50 min
                // 3. Second half: startTime + 50 min to endTime
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

        // If we reach here, current time is past the end of the last lesson of today!
        // Show first lesson of NEXT school day
        val (nextLesson, nextDayName, nextInfo) = findNextUpcomingLesson(
            schedule,
            nowZdt,
            offsetDaysStart = 1
        )
        return ScheduleState.DayFinished(nextLesson, nextDayName, nextInfo)
    }

    /**
     * Finds the next lesson starting from offsetDays ahead in Novosibirsk timezone.
     */
    fun findNextUpcomingLesson(
        schedule: ScheduleData,
        baseZdt: ZonedDateTime,
        offsetDaysStart: Int = 1
    ): Triple<Lesson?, String?, String?> {
        for (offset in offsetDaysStart..14) {
            val checkZdt = baseZdt.plusDays(offset.toLong())
            val dayIdx = checkZdt.dayOfWeek.value // 1 = Mon .. 7 = Sun

            val parity = getEffectiveParityForDate(schedule, checkZdt.toInstant().toEpochMilli())
            val dayLessons = schedule.lessons
                .filter { it.dayOfWeek == dayIdx && it.parity.isCompatibleWith(parity) }
                .sortedBy { it.slotNumber }

            if (dayLessons.isNotEmpty()) {
                val next = dayLessons.first()
                val dayName = getDayOfWeekName(dayIdx)
                val relativeName = when (offset) {
                    0 -> "Сегодня"
                    1 -> "Завтра"
                    2 -> "Послезавтра"
                    else -> dayName
                }
                val info = "$relativeName (${dayName.take(2)}), ${next.startTime}"
                return Triple(next, dayName, info)
            }
        }
        return Triple(null, null, null)
    }

    /**
     * Returns the exact epoch millis when the next state change occurs.
     */
    fun getNextTransitionTime(nowMillis: Long = System.currentTimeMillis()): Long {
        val state = getCurrentScheduleState(nowMillis)
        val nowZdt = Instant.ofEpochMilli(nowMillis).atZone(nsuZone)

        return when (state) {
            is ScheduleState.BeforeLesson -> state.startsAtMillis
            is ScheduleState.DuringLesson -> state.endsAtMillis
            is ScheduleState.DayFinished,
            is ScheduleState.NoLessonsToday -> {
                // Schedule alarm for 05:00:00 AM in Novosibirsk timezone
                // (today if currently before 05:00 AM, otherwise tomorrow morning)
                val target5amZdt = if (nowZdt.hour < 5) {
                    nowZdt.toLocalDate().atTime(5, 0).atZone(nsuZone)
                } else {
                    nowZdt.toLocalDate().plusDays(1).atTime(5, 0).atZone(nsuZone)
                }
                target5amZdt.toInstant().toEpochMilli()
            }
            is ScheduleState.NoScheduleData -> nowMillis + 60 * 60 * 1000L
        }
    }

    fun getDayOfWeekName(dayIdx: Int): String {
        return when (dayIdx) {
            1 -> "Понедельник"
            2 -> "Вторник"
            3 -> "Среда"
            4 -> "Четверг"
            5 -> "Пятница"
            6 -> "Суббота"
            7 -> "Воскресенье"
            else -> ""
        }
    }

    companion object {
        private const val PREFS_NAME = "nsu_schedule_prefs"
        private const val KEY_SCHEDULE_JSON = "key_schedule_json"
        private const val KEY_SERVICE_ENABLED = "key_service_enabled"
        private const val KEY_LAST_SYNCED = "key_last_synced"
        private const val KEY_SELECTED_GROUP_NAME = "key_selected_group_name"
        private const val KEY_SELECTED_GROUP_URL = "key_selected_group_url"
        private const val KEY_CACHED_GROUPS_JSON = "key_cached_groups_json"
        private const val KEY_HIDDEN_SUBJECTS = "key_hidden_subjects"

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
