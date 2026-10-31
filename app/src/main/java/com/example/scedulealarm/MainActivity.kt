package com.example.scedulealarm

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.example.scedulealarm.BuildConfig
import com.example.scedulealarm.data.model.GroupItem
import com.example.scedulealarm.data.model.ScheduleData
import com.example.scedulealarm.data.model.ScheduleState
import com.example.scedulealarm.databinding.ActivityMainBinding
import com.example.scedulealarm.databinding.BottomSheetGroupSelectionBinding
import com.example.scedulealarm.databinding.BottomSheetSubjectFilterBinding
import com.example.scedulealarm.repository.ScheduleRepository
import com.example.scedulealarm.service.ScheduleForegroundService
import com.example.scedulealarm.ui.DayScheduleUiModel
import com.example.scedulealarm.ui.GroupBottomSheetAdapter
import com.example.scedulealarm.ui.SubjectFilterBottomSheetAdapter
import com.example.scedulealarm.ui.WeekSchedulePagerAdapter
import com.example.scedulealarm.update.UpdateInfo
import com.example.scedulealarm.update.UpdateManager
import com.example.scedulealarm.worker.ScheduleSyncWorker
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: ScheduleRepository
    private lateinit var weekScheduleAdapter: WeekSchedulePagerAdapter

    private val availableGroups = mutableListOf<GroupItem>()

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            enableService()
        } else {
            Toast.makeText(
                this,
                "Без разрешения на уведомления виджет не сможет отображаться на экране блокировки",
                Toast.LENGTH_LONG
            ).show()
            binding.switchService.isChecked = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Edge-to-Edge: protect header and content from overlapping system bars
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        repository = ScheduleRepository(this)

        setupWeekSchedulePager()
        setupGroupSelector()
        setupListeners()
        setupTabNavigation()
        selectTab(isScheduleTab = true)

        // Schedule 24h background sync
        ScheduleSyncWorker.schedulePeriodicSync(this)

        // Load cached data or fetch if empty
        val cached = repository.getCachedSchedule()
        if (cached == null) {
            refreshSchedule(isManual = false)
        } else {
            updateUiWithSchedule(cached)
        }

        checkBatteryOptimizationStatus()
        updateFilterSummary()

        // Check for OTA app update in background
        checkForAppUpdates()
    }

    override fun onResume() {
        super.onResume()
        updateServiceStateUi()
        val currentGroup = repository.getSelectedGroup()
        updateHeaderSubtitle(currentGroup.name)
        binding.tvSelectedGroupName.text = currentGroup.name
        repository.getCachedSchedule()?.let { updateUiWithSchedule(it) }
        updateFilterSummary()
        checkBatteryOptimizationStatus()
        checkForAppUpdates()
    }

    private fun setupWeekSchedulePager() {
        weekScheduleAdapter = WeekSchedulePagerAdapter { position ->
            if (position == binding.pagerWeekSchedule.currentItem) {
                adjustPagerHeight(binding.pagerWeekSchedule, position)
            }
        }
        binding.pagerWeekSchedule.adapter = weekScheduleAdapter

        binding.pagerWeekSchedule.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                updateDayHeader(position)
                adjustPagerHeight(binding.pagerWeekSchedule, position)
            }

            override fun onPageScrollStateChanged(state: Int) {
                super.onPageScrollStateChanged(state)
                if (state == ViewPager2.SCROLL_STATE_IDLE) {
                    adjustPagerHeight(binding.pagerWeekSchedule, binding.pagerWeekSchedule.currentItem)
                }
            }
        })

        binding.btnPrevDay.setOnClickListener {
            val current = binding.pagerWeekSchedule.currentItem
            if (current > 0) {
                binding.pagerWeekSchedule.setCurrentItem(current - 1, true)
            }
        }

        binding.btnNextDay.setOnClickListener {
            val current = binding.pagerWeekSchedule.currentItem
            if (current < (weekScheduleAdapter.itemCount - 1)) {
                binding.pagerWeekSchedule.setCurrentItem(current + 1, true)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
            val current = binding.pagerWeekSchedule.currentItem
            if (current < weekScheduleAdapter.itemCount - 1) {
                binding.pagerWeekSchedule.setCurrentItem(current + 1, true)
                return true
            }
        } else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
            val current = binding.pagerWeekSchedule.currentItem
            if (current > 0) {
                binding.pagerWeekSchedule.setCurrentItem(current - 1, true)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun adjustPagerHeight(viewPager: ViewPager2, position: Int) {
        if (position != viewPager.currentItem) {
            return
        }
        if (viewPager.width <= 0) {
            viewPager.post { adjustPagerHeight(viewPager, position) }
            return
        }
        val rv = viewPager.getChildAt(0) as? RecyclerView ?: return
        val vh = rv.findViewHolderForAdapterPosition(position)
        if (vh == null) {
            viewPager.post { adjustPagerHeight(viewPager, position) }
            return
        }
        val itemView = vh.itemView
        itemView.post {
            if (position != viewPager.currentItem) return@post
            val wSpec = View.MeasureSpec.makeMeasureSpec(viewPager.width, View.MeasureSpec.EXACTLY)
            val hSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            itemView.measure(wSpec, hSpec)
            val targetHeight = itemView.measuredHeight
            android.util.Log.d("PAGER_HEIGHT", "adjustPagerHeight pos: $position, targetHeight: $targetHeight, vpHeight: ${viewPager.height}")
            if (targetHeight > 0 && viewPager.layoutParams.height != targetHeight) {
                viewPager.layoutParams.height = targetHeight
                viewPager.requestLayout()
            }
        }
    }

    private fun updateDayHeader(position: Int) {
        val dayModel = weekScheduleAdapter.getDayAt(position) ?: return
        binding.tvScheduleDayTitle.text = "${dayModel.dayName}, ${dayModel.formattedDate}"
        binding.tvScheduleTodayBadge.visibility = if (dayModel.isToday) View.VISIBLE else View.GONE

        val count = dayModel.lessons.size
        val countStr = if (count == 0) {
            "Пар нет"
        } else {
            "$count ${getLessonsPlural(count)}"
        }

        val schedule = repository.getCachedSchedule()
        val parityStr = if (schedule != null) {
            val parity = repository.getEffectiveParityForDate(schedule, System.currentTimeMillis())
            " • ${parity.displayName} неделя"
        } else ""

        binding.tvScheduleCount.text = "$countStr$parityStr"

        binding.btnPrevDay.isEnabled = position > 0
        binding.ivPrevDay.alpha = if (position > 0) 1.0f else 0.3f

        val maxPos = weekScheduleAdapter.itemCount - 1
        binding.btnNextDay.isEnabled = position < maxPos
        binding.ivNextDay.alpha = if (position < maxPos) 1.0f else 0.3f
    }

    private fun getLessonsPlural(count: Int): String {
        val mod10 = count % 10
        val mod100 = count % 100
        return when {
            mod100 in 11..19 -> "пар"
            mod10 == 1 -> "пара"
            mod10 in 2..4 -> "пары"
            else -> "пар"
        }
    }

    private fun setupGroupSelector() {
        val selectedGroup = repository.getSelectedGroup()
        updateHeaderSubtitle(selectedGroup.name)
        binding.tvSelectedGroupName.text = selectedGroup.name
        binding.tvSelectedGroupSubtitle.text = "Нажмите для выбора из каталога НГУ"

        // Load groups from cache if available
        val cached = repository.getCachedGroups()
        if (cached.isNotEmpty()) {
            availableGroups.clear()
            availableGroups.addAll(cached)
        } else {
            loadGroupsCatalog()
        }

        // Tapping the card opens the bottom sheet
        binding.cardGroupSelector.setOnClickListener {
            showGroupSelectionBottomSheet()
        }
    }

    private fun showGroupSelectionBottomSheet() {
        val bottomSheetDialog = BottomSheetDialog(
            this,
            R.style.DarkSlateBottomSheetDialogTheme
        )
        val sheetBinding = BottomSheetGroupSelectionBinding.inflate(layoutInflater)
        bottomSheetDialog.setContentView(sheetBinding.root)
        bottomSheetDialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        bottomSheetDialog.behavior.skipCollapsed = true

        val currentSelected = repository.getSelectedGroup()
        val displayGroups = mutableListOf<GroupItem>()
        displayGroups.addAll(availableGroups)

        val adapter = GroupBottomSheetAdapter(displayGroups, currentSelected.name) { selectedGroup ->
            bottomSheetDialog.dismiss()
            switchGroup(selectedGroup)
        }

        sheetBinding.recyclerGroups.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            this.adapter = adapter
        }

        sheetBinding.btnCloseSheet.setOnClickListener {
            bottomSheetDialog.dismiss()
        }

        fun filterGroups(query: String) {
            val trimmed = query.trim()
            val filtered = if (trimmed.isEmpty()) {
                availableGroups.toList()
            } else {
                val list = availableGroups.filter { it.name.contains(trimmed, ignoreCase = true) }.toMutableList()
                val exactMatch = availableGroups.any { it.name.equals(trimmed, ignoreCase = true) }
                if (!exactMatch) {
                    list.add(0, GroupItem(name = trimmed, urlPath = "manual/group/$trimmed"))
                }
                list
            }

            adapter.updateData(filtered, currentSelected.name)
            sheetBinding.tvEmptyGroups.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
            sheetBinding.recyclerGroups.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
        }

        sheetBinding.etSearchGroup.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val text = s?.toString().orEmpty()
                sheetBinding.ivClearSearch.visibility = if (text.isNotEmpty()) View.VISIBLE else View.GONE
                filterGroups(text)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        sheetBinding.ivClearSearch.setOnClickListener {
            sheetBinding.etSearchGroup.text?.clear()
        }

        // Fetch groups if cache was empty
        if (availableGroups.isEmpty()) {
            sheetBinding.progressCatalogLoading.visibility = View.VISIBLE
            lifecycleScope.launch {
                val result = repository.fetchAvailableGroups(forceRefresh = false)
                sheetBinding.progressCatalogLoading.visibility = View.GONE
                if (result.isSuccess) {
                    availableGroups.clear()
                    availableGroups.addAll(result.getOrThrow())
                    filterGroups(sheetBinding.etSearchGroup.text?.toString().orEmpty())
                }
            }
        }

        bottomSheetDialog.show()
    }

    private fun loadGroupsCatalog() {
        binding.progressGroupLoad.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = repository.fetchAvailableGroups(forceRefresh = false)
            binding.progressGroupLoad.visibility = View.GONE
            if (result.isSuccess) {
                val list = result.getOrThrow()
                availableGroups.clear()
                availableGroups.addAll(list)
            }
        }
    }

    private fun switchGroup(group: GroupItem) {
        val current = repository.getSelectedGroup()
        if (group.name == current.name && repository.getCachedSchedule() != null) {
            return
        }

        updateHeaderSubtitle(group.name)
        binding.tvSelectedGroupName.text = group.name
        setRefreshLoading(true)

        lifecycleScope.launch {
            val result = repository.selectGroupAndReload(group, this@MainActivity)
            setRefreshLoading(false)

            if (result.isSuccess) {
                val data = result.getOrThrow()
                updateUiWithSchedule(data)
                Snackbar.make(
                    binding.root,
                    "Группа ${group.name} успешно выбрана!",
                    Snackbar.LENGTH_SHORT
                ).show()
            } else {
                val err = result.exceptionOrNull()?.localizedMessage ?: "Ошибка сети"
                Snackbar.make(
                    binding.root,
                    "Не удалось загрузить группу ${group.name}: $err",
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun setupListeners() {
        binding.switchService.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                requestPermissionsAndStartService()
            } else {
                disableService()
            }
        }

        binding.btnHeaderRefresh.setOnClickListener {
            refreshSchedule(isManual = true)
        }

        binding.btnIgnoreBattery.setOnClickListener {
            requestIgnoreBatteryOptimizations()
        }

        binding.cardSubjectFilter.setOnClickListener {
            showSubjectFilterDialog()
        }
    }

    private fun setupTabNavigation() {
        binding.tabSchedule.setOnClickListener {
            selectTab(isScheduleTab = true)
        }

        binding.tabSettings.setOnClickListener {
            selectTab(isScheduleTab = false)
        }

        binding.btnRestartWidget.setOnClickListener {
            if (repository.isServiceEnabled()) {
                ScheduleForegroundService.startOrUpdate(this)
                updatePreviewCard()
                Snackbar.make(binding.root, "Служба виджета перезапущена!", Snackbar.LENGTH_SHORT).show()
            } else {
                Snackbar.make(binding.root, "Включите виджет переключателем выше", Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun selectTab(isScheduleTab: Boolean) {
        if (isScheduleTab) {
            // Schedule tab active
            binding.tabSchedule.setBackgroundResource(R.drawable.bg_tab_active)
            binding.tvTabSchedule.setTextColor(ContextCompat.getColor(this, R.color.dashboard_text_primary))
            binding.ivTabSchedule.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.dashboard_text_primary))

            binding.tabSettings.setBackgroundColor(Color.TRANSPARENT)
            binding.tvTabSettings.setTextColor(ContextCompat.getColor(this, R.color.dashboard_text_muted))
            binding.ivTabSettings.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.dashboard_text_muted))

            binding.layoutTabContentSchedule.visibility = View.VISIBLE
            binding.layoutTabContentSettings.visibility = View.GONE

            // Re-adjust week pager height for current day
            binding.pagerWeekSchedule.post {
                adjustPagerHeight(binding.pagerWeekSchedule, binding.pagerWeekSchedule.currentItem)
            }
        } else {
            // Settings tab active
            binding.tabSchedule.setBackgroundColor(Color.TRANSPARENT)
            binding.tvTabSchedule.setTextColor(ContextCompat.getColor(this, R.color.dashboard_text_muted))
            binding.ivTabSchedule.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.dashboard_text_muted))

            binding.tabSettings.setBackgroundResource(R.drawable.bg_tab_active)
            binding.tvTabSettings.setTextColor(ContextCompat.getColor(this, R.color.dashboard_text_primary))
            binding.ivTabSettings.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.dashboard_text_primary))

            binding.layoutTabContentSchedule.visibility = View.GONE
            binding.layoutTabContentSettings.visibility = View.VISIBLE

            updatePreviewCard()
            updateServiceStateUi()
        }
    }

    private fun updateHeaderSubtitle(groupName: String) {
        val text = if (groupName.isBlank()) {
            "Группа не выбрана"
        } else if (groupName.startsWith("Группа", ignoreCase = true)) {
            groupName
        } else {
            "Группа $groupName"
        }
        binding.toolbar.subtitle = text
        binding.tvHeaderSubtitle.text = text
    }

    private fun setRefreshLoading(isLoading: Boolean) {
        binding.btnHeaderRefresh.isEnabled = !isLoading

        if (isLoading) {
            binding.ivHeaderRefresh.visibility = View.GONE
            binding.progressHeaderRefresh.visibility = View.VISIBLE
        } else {
            binding.ivHeaderRefresh.visibility = View.VISIBLE
            binding.progressHeaderRefresh.visibility = View.GONE
        }
    }

    private fun updateFilterSummary() {
        val hiddenCount = repository.getHiddenSubjects().size
        if (hiddenCount == 0) {
            binding.tvFilterSummary.text = "Все предметы отображаются"
        } else {
            binding.tvFilterSummary.text = "Скрыто предметов: $hiddenCount"
        }
    }

    private fun showSubjectFilterDialog() {
        val allSubjects = repository.getAllUniqueSubjects()
        if (allSubjects.isEmpty()) {
            Snackbar.make(
                binding.root,
                "Расписание ещё не загружено. Нажмите «Обновить расписание».",
                Snackbar.LENGTH_SHORT
            ).show()
            return
        }

        val bottomSheetDialog = BottomSheetDialog(
            this,
            R.style.DarkSlateBottomSheetDialogTheme
        )
        val sheetBinding = BottomSheetSubjectFilterBinding.inflate(layoutInflater)
        bottomSheetDialog.setContentView(sheetBinding.root)
        bottomSheetDialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        bottomSheetDialog.behavior.skipCollapsed = true

        val currentHidden = repository.getHiddenSubjects()
        val selectedSubjects = allSubjects.filter { !currentHidden.contains(it) }.toMutableSet()

        val adapter = SubjectFilterBottomSheetAdapter(allSubjects, selectedSubjects)
        sheetBinding.recyclerFilterSubjects.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            this.adapter = adapter
        }

        sheetBinding.btnCloseFilterSheet.setOnClickListener {
            bottomSheetDialog.dismiss()
        }

        sheetBinding.btnApplyFilter.setOnClickListener {
            val newHidden = allSubjects.filter { !selectedSubjects.contains(it) }.toSet()
            repository.setHiddenSubjects(newHidden)
            updateFilterSummary()

            val filteredSchedule = repository.getCachedSchedule()
            if (filteredSchedule != null) {
                updateUiWithSchedule(filteredSchedule)
            } else {
                updatePreviewCard()
            }

            if (repository.isServiceEnabled()) {
                ScheduleForegroundService.startOrUpdate(this@MainActivity)
            }

            bottomSheetDialog.dismiss()
            Snackbar.make(binding.root, "Фильтр предметов применен", Snackbar.LENGTH_SHORT).show()
        }

        sheetBinding.btnShowAllFilter.setOnClickListener {
            adapter.selectAll()
            repository.setHiddenSubjects(emptySet())
            updateFilterSummary()

            val filteredSchedule = repository.getCachedSchedule()
            if (filteredSchedule != null) {
                updateUiWithSchedule(filteredSchedule)
            } else {
                updatePreviewCard()
            }

            if (repository.isServiceEnabled()) {
                ScheduleForegroundService.startOrUpdate(this@MainActivity)
            }

            bottomSheetDialog.dismiss()
            Snackbar.make(binding.root, "Все предметы включены", Snackbar.LENGTH_SHORT).show()
        }

        bottomSheetDialog.show()
    }

    private fun checkForAppUpdates() {
        lifecycleScope.launch {
            val result = UpdateManager.checkForUpdate(this@MainActivity)
            if (result.isSuccess) {
                val update = result.getOrNull()
                // Strict check: show banner ONLY if remote version is strictly greater than installed
                if (update != null && update.versionCode > BuildConfig.VERSION_CODE) {
                    showUpdateBanner(update)
                } else {
                    binding.cardUpdateBanner.visibility = View.GONE
                }
            } else {
                binding.cardUpdateBanner.visibility = View.GONE
            }
        }
    }

    private fun showUpdateBanner(update: UpdateInfo) {
        // Enforce hard visibility check
        if (update.versionCode <= BuildConfig.VERSION_CODE) {
            binding.cardUpdateBanner.visibility = View.GONE
            return
        }

        binding.cardUpdateBanner.visibility = View.VISIBLE
        binding.tvUpdateTitle.text = "Доступно обновление v${update.versionName}"
        binding.tvUpdateChangelog.text = update.changelog

        binding.btnDismissUpdate.setOnClickListener {
            binding.cardUpdateBanner.visibility = View.GONE
        }

        binding.btnInstallUpdate.setOnClickListener {
            startUpdateDownload(update)
        }
    }

    private fun startUpdateDownload(update: UpdateInfo) {
        binding.btnInstallUpdate.isEnabled = false
        binding.btnDismissUpdate.isEnabled = false
        binding.progressUpdateDownload.visibility = View.VISIBLE
        binding.progressUpdateDownload.progress = 0
        binding.tvUpdateStatus.visibility = View.VISIBLE
        binding.tvUpdateStatus.text = "Подключение к серверу..."

        lifecycleScope.launch {
            val result = UpdateManager.downloadApk(this@MainActivity, update) { percent ->
                binding.progressUpdateDownload.progress = percent
                binding.tvUpdateStatus.text = "Скачивание: $percent%"
            }

            binding.btnInstallUpdate.isEnabled = true
            binding.btnDismissUpdate.isEnabled = true

            if (result.isSuccess) {
                val apkFile = result.getOrThrow()
                binding.tvUpdateStatus.text = "SHA-256 проверен. Запуск установки..."
                UpdateManager.installApk(this@MainActivity, apkFile)
            } else {
                val error = result.exceptionOrNull()?.localizedMessage ?: "Ошибка загрузки"
                binding.tvUpdateStatus.text = error
                Snackbar.make(binding.root, error, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun requestPermissionsAndStartService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(AlarmManager::class.java)
            if (!alarmManager.canScheduleExactAlarms()) {
                Toast.makeText(
                    this,
                    "Для своевременного переключения таймера пар разрешите точные будильники",
                    Toast.LENGTH_LONG
                ).show()
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    // Fallback
                }
            }
        }

        enableService()
    }

    private fun enableService() {
        repository.setServiceEnabled(true)
        ScheduleForegroundService.startOrUpdate(this)
        updateServiceStateUi()
        Snackbar.make(binding.root, "Виджет закреплен на экране блокировки", Snackbar.LENGTH_SHORT).show()
    }

    private fun disableService() {
        repository.setServiceEnabled(false)
        ScheduleForegroundService.stopService(this)
        updateServiceStateUi()
        Snackbar.make(binding.root, "Виджет отключен", Snackbar.LENGTH_SHORT).show()
    }

    private fun updateServiceStateUi() {
        val isEnabled = repository.isServiceEnabled()
        binding.switchService.isChecked = isEnabled
        binding.tvServiceStatus.text = if (isEnabled) {
            getString(R.string.status_running)
        } else {
            getString(R.string.status_not_running)
        }
    }

    private fun refreshSchedule(isManual: Boolean) {
        setRefreshLoading(true)

        lifecycleScope.launch {
            val result = repository.fetchAndSaveSchedule()
            setRefreshLoading(false)

            if (result.isSuccess) {
                val data = result.getOrThrow()
                updateUiWithSchedule(data)
                if (repository.isServiceEnabled()) {
                    ScheduleForegroundService.updateNotification(this@MainActivity)
                }
                if (isManual) {
                    checkForAppUpdates()
                    Snackbar.make(
                        binding.root,
                        "Расписание успешно обновлено!",
                        Snackbar.LENGTH_SHORT
                    ).show()
                }
            } else {
                val err = result.exceptionOrNull()?.localizedMessage ?: "Ошибка сети"
                if (isManual) {
                    Snackbar.make(
                        binding.root,
                        "Не удалось обновить: $err",
                        Snackbar.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun updateUiWithSchedule(data: ScheduleData) {
        val group = repository.getSelectedGroup()
        updateHeaderSubtitle(group.name)
        binding.tvSelectedGroupName.text = group.name

        val now = System.currentTimeMillis()
        val parity = repository.getEffectiveParityForDate(data, now)
        binding.tvParityBadge.text = "${parity.displayName} неделя"

        val lastSync = repository.getLastSyncedTimestamp()
        if (lastSync > 0) {
            val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
            binding.tvLastSynced.text = getString(R.string.schedule_last_updated, sdf.format(Date(lastSync)))
        }

        // Build 7 days model for the week (Monday to Sunday)
        val nowZdt = Instant.ofEpochMilli(now).atZone(repository.nsuZone)
        val mondayLocalDate = nowZdt.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toLocalDate()
        val ruLocale = Locale("ru")
        val monthFormatter = DateTimeFormatter.ofPattern("d MMMM", ruLocale)

        val weekDays = (1..7).map { dayIdx ->
            val date = mondayLocalDate.plusDays((dayIdx - 1).toLong())
            val dayName = repository.getDayOfWeekName(dayIdx)
            val formattedDate = date.format(monthFormatter)
            val isToday = (dayIdx == nowZdt.dayOfWeek.value)
            val dayLessons = data.lessons
                .filter { it.dayOfWeek == dayIdx && it.parity.isCompatibleWith(parity) }
                .sortedBy { it.slotNumber }

            DayScheduleUiModel(
                dayIndex = dayIdx,
                dayName = dayName,
                localDate = date,
                formattedDate = formattedDate,
                isToday = isToday,
                lessons = dayLessons
            )
        }

        weekScheduleAdapter.updateData(weekDays)

        // Select today's page
        val todayPosition = (nowZdt.dayOfWeek.value - 1).coerceIn(0, 6)
        if (binding.pagerWeekSchedule.currentItem != todayPosition) {
            binding.pagerWeekSchedule.setCurrentItem(todayPosition, false)
        }
        updateDayHeader(binding.pagerWeekSchedule.currentItem)
        adjustPagerHeight(binding.pagerWeekSchedule, binding.pagerWeekSchedule.currentItem)

        // Update preview card (widget preview - completely decoupled from pager)
        updatePreviewCard()
    }

    private fun updatePreviewCard() {
        val state = repository.getCurrentScheduleState()

        when (state) {
            is ScheduleState.BeforeLesson -> {
                binding.layoutPreviewRoomBadge.visibility = View.VISIBLE
                binding.tvPreviewType.visibility = View.VISIBLE
                binding.layoutPreviewTimerRow.visibility = View.VISIBLE
                binding.tvPreviewStatusLabel.visibility = View.VISIBLE
                binding.chronometerPreview.visibility = View.VISIBLE
                binding.tvPreviewStaticInfo.visibility = View.GONE

                binding.tvPreviewRoom.text = state.roomText
                binding.tvPreviewSubject.text = state.subjectText
                binding.tvPreviewType.text = state.typeText
                binding.tvPreviewStatusLabel.text = state.statusTitle

                val timeDeltaMillis = state.startsAtMillis - System.currentTimeMillis()
                val base = SystemClock.elapsedRealtime() + timeDeltaMillis
                binding.chronometerPreview.isCountDown = true
                binding.chronometerPreview.base = base
                binding.chronometerPreview.start()
            }

            is ScheduleState.DuringLesson -> {
                binding.layoutPreviewRoomBadge.visibility = View.VISIBLE
                binding.tvPreviewType.visibility = View.VISIBLE
                binding.layoutPreviewTimerRow.visibility = View.VISIBLE
                binding.tvPreviewStatusLabel.visibility = View.VISIBLE
                binding.chronometerPreview.visibility = View.VISIBLE
                binding.tvPreviewStaticInfo.visibility = View.GONE

                binding.tvPreviewRoom.text = state.roomText
                binding.tvPreviewSubject.text = state.subjectText
                binding.tvPreviewType.text = state.typeText
                binding.tvPreviewStatusLabel.text = state.statusTitle

                val timeDeltaMillis = state.endsAtMillis - System.currentTimeMillis()
                val base = SystemClock.elapsedRealtime() + timeDeltaMillis
                binding.chronometerPreview.isCountDown = true
                binding.chronometerPreview.base = base
                binding.chronometerPreview.start()
            }

            is ScheduleState.DayFinished,
            is ScheduleState.NoLessonsToday -> {
                binding.layoutPreviewRoomBadge.visibility = View.GONE
                binding.tvPreviewType.visibility = View.GONE
                binding.layoutPreviewTimerRow.visibility = View.GONE
                binding.chronometerPreview.stop()
                binding.tvPreviewSubject.text = "Пар на сегодня больше нет"
            }

            is ScheduleState.NoScheduleData -> {
                binding.layoutPreviewRoomBadge.visibility = View.GONE
                binding.tvPreviewType.visibility = View.GONE
                binding.layoutPreviewTimerRow.visibility = View.GONE
                binding.chronometerPreview.stop()
                binding.tvPreviewSubject.text = "Нажмите 'Обновить'"
            }
        }
    }

    private fun checkBatteryOptimizationStatus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            val isIgnoring = powerManager?.isIgnoringBatteryOptimizations(packageName) ?: false
            if (isIgnoring) {
                binding.cardBatteryOptimizations.visibility = View.GONE
            } else {
                binding.cardBatteryOptimizations.visibility = View.VISIBLE
            }
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                try {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(intent)
                } catch (fallbackEx: Exception) {
                    Toast.makeText(this, "Откройте настройки приложений и отключите оптимизацию батареи", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
