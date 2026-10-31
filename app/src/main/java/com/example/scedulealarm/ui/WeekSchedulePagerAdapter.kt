package com.example.scedulealarm.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.scedulealarm.R
import com.example.scedulealarm.data.model.Lesson
import com.example.scedulealarm.data.model.LessonSlot
import com.example.scedulealarm.databinding.ItemDaySchedulePageBinding
import com.example.scedulealarm.databinding.ItemLessonBinding
import com.example.scedulealarm.databinding.ItemScheduleWindowBinding
import java.time.LocalDate

data class DayScheduleUiModel(
    val dayIndex: Int, // 1..7 (Monday..Sunday)
    val dayName: String, // "Понедельник", "Вторник", etc.
    val localDate: LocalDate,
    val formattedDate: String, // "29 октября"
    val isToday: Boolean,
    val lessons: List<Lesson>
)

class WeekSchedulePagerAdapter(
    private var days: List<DayScheduleUiModel> = emptyList(),
    private val onPageBound: ((position: Int) -> Unit)? = null
) : RecyclerView.Adapter<WeekSchedulePagerAdapter.DayPageViewHolder>() {

    fun updateData(newDays: List<DayScheduleUiModel>) {
        this.days = newDays
        notifyDataSetChanged()
    }

    fun getDayAt(position: Int): DayScheduleUiModel? {
        return days.getOrNull(position)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DayPageViewHolder {
        val binding = ItemDaySchedulePageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return DayPageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DayPageViewHolder, position: Int) {
        holder.bind(days[position])
        holder.itemView.post {
            onPageBound?.invoke(position)
        }
    }

    override fun getItemCount(): Int = days.size

    class DayPageViewHolder(private val binding: ItemDaySchedulePageBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(dayModel: DayScheduleUiModel) {
            binding.layoutDayLessons.removeAllViews()

            if (dayModel.lessons.isEmpty()) {
                binding.tvPageEmpty.visibility = View.VISIBLE
                binding.layoutDayLessons.visibility = View.GONE
            } else {
                binding.tvPageEmpty.visibility = View.GONE
                binding.layoutDayLessons.visibility = View.VISIBLE

                val inflater = LayoutInflater.from(binding.root.context)
                var prevLesson: Lesson? = null

                for (lesson in dayModel.lessons) {
                    // Detect window (gap) between lessons in a day
                    if (prevLesson != null && lesson.slotNumber > prevLesson.slotNumber + 1) {
                        val firstEmptySlot = LessonSlot.findByNumber(prevLesson.slotNumber + 1)
                        val lastEmptySlot = LessonSlot.findByNumber(lesson.slotNumber - 1)

                        val windowStart = firstEmptySlot?.startTime ?: prevLesson.endTime
                        val windowEnd = lastEmptySlot?.endTime ?: lesson.startTime

                        val startMins = (firstEmptySlot?.startHour ?: 0) * 60 + (firstEmptySlot?.startMinute ?: 0)
                        val endMins = (lastEmptySlot?.endHour ?: 0) * 60 + (lastEmptySlot?.endMinute ?: 0)
                        val diffMins = (endMins - startMins).coerceAtLeast(0)

                        val durationStr = if (diffMins > 0) {
                            val hours = diffMins / 60
                            val mins = diffMins % 60
                            when {
                                hours > 0 && mins > 0 -> "$hours ч $mins мин"
                                hours > 0 -> "$hours ч"
                                else -> "$mins мин"
                            }
                        } else {
                            "Окно"
                        }

                        val windowBinding = ItemScheduleWindowBinding.inflate(
                            inflater,
                            binding.layoutDayLessons,
                            false
                        )
                        windowBinding.tvWindowLabel.text = "Окно между парами • $windowStart – $windowEnd"
                        windowBinding.tvWindowDuration.text = durationStr
                        binding.layoutDayLessons.addView(windowBinding.root)
                    }

                    // Add lesson card
                    val lessonBinding = ItemLessonBinding.inflate(
                        inflater,
                        binding.layoutDayLessons,
                        false
                    )

                    lessonBinding.tvItemSlot.text = "${lesson.slotNumber} пара"
                    lessonBinding.tvItemTime.text = "${lesson.startTime}\n${lesson.endTime}"
                    lessonBinding.tvItemType.text = lesson.normalizedType
                    lessonBinding.tvItemParity.text = lesson.parity.displayName
                    lessonBinding.tvItemSubject.text = lesson.subject
                    lessonBinding.tvItemRoom.text = "Ауд. ${lesson.cleanRoom}"

                    if (lesson.tutor.isNotBlank()) {
                        lessonBinding.tvItemTutor.visibility = View.VISIBLE
                        lessonBinding.tvItemTutor.text = "• ${lesson.tutor}"
                    } else {
                        lessonBinding.tvItemTutor.visibility = View.GONE
                    }

                    binding.layoutDayLessons.addView(lessonBinding.root)
                    prevLesson = lesson
                }
                android.util.Log.d("PAGER_BIND", "Day ${dayModel.dayName} (${dayModel.dayIndex}) has ${dayModel.lessons.size} lessons. Total views added: ${binding.layoutDayLessons.childCount}")
            }
        }
    }
}
