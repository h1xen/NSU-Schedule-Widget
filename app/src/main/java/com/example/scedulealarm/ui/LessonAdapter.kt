package com.example.scedulealarm.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.scedulealarm.data.model.Lesson
import com.example.scedulealarm.databinding.ItemLessonBinding

class LessonAdapter(
    private var lessons: List<Lesson> = emptyList()
) : RecyclerView.Adapter<LessonAdapter.LessonViewHolder>() {

    fun updateData(newLessons: List<Lesson>) {
        this.lessons = newLessons
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LessonViewHolder {
        val binding = ItemLessonBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return LessonViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LessonViewHolder, position: Int) {
        holder.bind(lessons[position])
    }

    override fun getItemCount(): Int = lessons.size

    class LessonViewHolder(private val binding: ItemLessonBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(lesson: Lesson) {
            binding.tvItemSlot.text = "${lesson.slotNumber} пара"
            binding.tvItemTime.text = "${lesson.startTime}\n${lesson.endTime}"
            binding.tvItemType.text = lesson.normalizedType
            binding.tvItemParity.text = lesson.parity.displayName
            binding.tvItemSubject.text = lesson.subject
            binding.tvItemRoom.text = "Ауд. ${lesson.cleanRoom}"

            if (lesson.tutor.isNotBlank()) {
                binding.tvItemTutor.visibility = View.VISIBLE
                binding.tvItemTutor.text = "• ${lesson.tutor}"
            } else {
                binding.tvItemTutor.visibility = View.GONE
            }
        }
    }
}
